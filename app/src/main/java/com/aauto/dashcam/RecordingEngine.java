package com.aauto.dashcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Owns the camera, a low-res preview, and one active recording at a time.
 * Defaults to the rear camera. Loop mode splits the capture into 60-second clips.
 */
public final class RecordingEngine {
    public interface Listener {
        void onStatus(DashcamStatus status);
    }

    private static final String TAG = "RecordingEngine";
    private static final String PREFS = "dashcam";
    private static final String KEY_LOOP = "loop_enabled";
    static final long LOOP_SEGMENT_MS = 10L * 60L * 1000L;
    /** Clips in a row that may fail with no footage before recording gives up. */
    private static final int MAX_FAILED_CLIPS = 3;

    /**
     * One started clip. Its CameraX events carry it, so a late event from a clip that was
     * already replaced can't drive the clip recording now.
     */
    private record Segment(String name, boolean loop) {
    }

    private final Context app;
    private final LoopStorage storage;
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = this::onHeartbeat;

    @Nullable private ProcessCameraProvider cameraProvider;
    @Nullable private Preview preview;
    @Nullable private VideoCapture<Recorder> videoCapture;
    @Nullable private Recording recording;
    @Nullable private LifecycleOwner lifecycleOwner;
    @Nullable private Preview.SurfaceProvider surfaceProvider;

    private DashcamState state = DashcamState.IDLE;
    private boolean loopEnabled;
    private boolean useFront;
    private boolean wantRecording;
    private boolean rotating;
    private boolean pendingCameraSwitch;
    private long baseDurationMs;
    private long runningSinceElapsed;
    private String currentClipName = "";
    /** The clip whose events drive state; stays set after stop() until its Finalize arrives. */
    @Nullable private Segment openSegment;
    private int failedClips;
    private String message = "Ready";

    public RecordingEngine(Context context) {
        app = context.getApplicationContext();
        storage = new LoopStorage(app);
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loopEnabled = prefs.getBoolean(KEY_LOOP, false);
        storage.cleanupOrphans();
    }

    @MainThread
    public synchronized void recoverStorage() {
        // A clip that is still closing has a pending MediaStore row that cleanup would drop.
        if (state == DashcamState.RECORDING || state == DashcamState.PAUSED
                || openSegment != null) {
            return;
        }
        storage.cleanupOrphans();
    }

    @MainThread
    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onStatus(snapshot());
    }

    @MainThread
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @MainThread
    public synchronized DashcamStatus snapshot() {
        return new DashcamStatus(state, loopEnabled, liveDurationMs(), statusMessage(), useFront,
                videoCapture != null);
    }

    @MainThread
    public void bindToLifecycle(LifecycleOwner owner) {
        lifecycleOwner = owner;
        if (cameraProvider != null) {
            bindUseCases();
            return;
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(app);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Camera provider failed", e);
                message = "Camera unavailable";
                emit();
            }
        }, ContextCompat.getMainExecutor(app));
    }

    @MainThread
    public void attachPreview(PreviewView previewView) {
        surfaceProvider = previewView.getSurfaceProvider();
        if (preview != null) {
            preview.setSurfaceProvider(surfaceProvider);
        }
    }

    @MainThread
    public void detachPreview() {
        surfaceProvider = null;
        if (preview != null) {
            preview.setSurfaceProvider(null);
        }
    }

    @MainThread
    public synchronized void unbindCamera() {
        wantRecording = false;
        rotating = false;
        if (recording != null) {
            recording.stop();
            recording = null;
        }
        if (preview != null) {
            preview.setSurfaceProvider(null);
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        preview = null;
        videoCapture = null;
        lifecycleOwner = null;
        surfaceProvider = null;
        useFront = false;
        pendingCameraSwitch = false;
        state = DashcamState.IDLE;
        resetDuration();
        stopHeartbeat();
        message = "Stopped";
        emit();
    }

    @MainThread
    public synchronized void play() {
        if (state == DashcamState.PAUSED) {
            resume();
            return;
        }
        if (state == DashcamState.RECORDING) {
            return;
        }
        if (!hasCameraPermission()) {
            message = "Camera permission missing";
            emit();
            return;
        }
        if (videoCapture == null) {
            message = "Camera is not ready";
            emit();
            return;
        }
        wantRecording = true;
        rotating = false;
        failedClips = 0;
        resetDuration();
        recoverStorage();
        startClip();
    }

    @MainThread
    public synchronized void pause() {
        if (state != DashcamState.RECORDING || recording == null) {
            return;
        }
        freezeClock();
        stopHeartbeat();
        recording.pause();
    }

    @MainThread
    public synchronized void resume() {
        if (state != DashcamState.PAUSED || recording == null) {
            return;
        }
        startRunningClock();
        ensureHeartbeat();
        recording.resume();
    }

    @MainThread
    public synchronized void stop() {
        wantRecording = false;
        rotating = false;
        if (recording != null) {
            recording.stop();
            recording = null;
        } else if (openSegment == null) {
            state = DashcamState.IDLE;
            resetDuration();
            stopHeartbeat();
            message = "Stopped";
            emit();
        }
        // Otherwise a clip is still closing (loop rollover); its Finalize moves to IDLE.
    }

    @MainThread
    public synchronized void setLoopEnabled(boolean enabled) {
        loopEnabled = enabled;
        prefs.edit().putBoolean(KEY_LOOP, enabled).apply();
        message = enabled ? "Loop on" : "Loop off";
        emit();
    }

    @MainThread
    public synchronized void toggleCamera() {
        boolean nextFront = !useFront;
        CameraSelector selector = selectorFor(nextFront);
        if (cameraProvider != null) {
            try {
                if (!cameraProvider.hasCamera(selector)) {
                    message = nextFront ? "No front camera" : "No rear camera";
                    emit();
                    return;
                }
            } catch (CameraInfoUnavailableException e) {
                message = "Camera unavailable";
                emit();
                return;
            }
        }
        useFront = nextFront;
        message = useFront ? "Front camera" : "Rear camera";
        boolean restart = state == DashcamState.RECORDING || state == DashcamState.PAUSED;
        if (recording != null) {
            pendingCameraSwitch = true;
            wantRecording = restart;
            recording.stop();
            recording = null;
            emit();
            return;
        }
        if (openSegment != null) {
            // A clip is still closing (rollover or an earlier tap): its Finalize rebinds
            // with whichever camera is selected by then, so repeated taps just coalesce.
            pendingCameraSwitch = true;
            emit();
            return;
        }
        bindUseCases();
        if (restart) {
            wantRecording = true;
            resetDuration();
            startClip();
        }
    }

    private static CameraSelector selectorFor(boolean front) {
        return front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
    }

    private void bindUseCases() {
        if (cameraProvider == null || lifecycleOwner == null) {
            // Nothing to bind (e.g. after Exit), but camera/message changes still need publishing.
            emit();
            return;
        }
        ResolutionSelector lowRes = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        new Size(320, 240),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
        preview = new Preview.Builder()
                .setResolutionSelector(lowRes)
                .build();
        if (surfaceProvider != null) {
            preview.setSurfaceProvider(surfaceProvider);
        }

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.fromOrderedList(
                        Arrays.asList(Quality.HD, Quality.SD, Quality.LOWEST),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        cameraProvider.unbindAll();
        try {
            cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selectorFor(useFront),
                    preview,
                    videoCapture);
            message = useFront ? "Front camera" : "Rear camera";
        } catch (RuntimeException e) {
            Log.e(TAG, "Bind camera failed", e);
            videoCapture = null;
            message = "Camera bind failed";
        }
        emit();
    }

    @SuppressLint("MissingPermission")
    private void startClip() {
        if (videoCapture == null) {
            message = "Camera is not ready";
            emit();
            return;
        }
        currentClipName = storage.newDisplayName();
        Segment segment = new Segment(currentClipName, loopEnabled);
        PendingRecording pending = storage.prepare(
                videoCapture.getOutput(), app, segment.name(), segment.loop());
        if (hasAudioPermission()) {
            pending = pending.withAudioEnabled();
        }
        try {
            recording = pending.start(ContextCompat.getMainExecutor(app),
                    event -> onRecordEvent(segment, event));
            openSegment = segment;
            state = DashcamState.RECORDING;
            startRunningClock();
            message = clipStateMessage();
            ensureHeartbeat();
            emit();
        } catch (SecurityException e) {
            Log.e(TAG, "Recording denied", e);
            wantRecording = false;
            resetDuration();
            stopHeartbeat();
            message = "Recording permission denied";
            emit();
        }
    }

    private synchronized void onRecordEvent(Segment segment, VideoRecordEvent event) {
        if (segment != openSegment) {
            // Replaced before it closed: keep its file, but it must not touch current state.
            if (event instanceof VideoRecordEvent.Finalize finalize) {
                finishSegment(segment, finalize);
            }
            return;
        }
        switch (event) {
            case VideoRecordEvent.Status status -> {
                long recordedMs = TimeUnit.NANOSECONDS.toMillis(
                        status.getRecordingStats().getRecordedDurationNanos());
                if (wantRecording && !rotating
                        && recordedMs >= LOOP_SEGMENT_MS
                        && recording != null) {
                    rotating = true;
                    recording.stop();
                    recording = null;
                }
                emit();
            }
            case VideoRecordEvent.Pause ignored -> {
                freezeClock();
                state = DashcamState.PAUSED;
                stopHeartbeat();
                message = clipStateMessage();
                emit();
            }
            case VideoRecordEvent.Resume ignored -> {
                startRunningClock();
                state = DashcamState.RECORDING;
                ensureHeartbeat();
                message = clipStateMessage();
                emit();
            }
            case VideoRecordEvent.Finalize finalize -> {
                recording = null;
                openSegment = null;
                boolean kept = finishSegment(segment, finalize);
                if (pendingCameraSwitch) {
                    pendingCameraSwitch = false;
                    rotating = false;
                    bindUseCases();
                    if (wantRecording) {
                        resetDuration();
                        startClip();
                    } else {
                        state = DashcamState.IDLE;
                        resetDuration();
                        stopHeartbeat();
                        emit();
                    }
                    return;
                }
                failedClips = kept ? 0 : failedClips + 1;
                if (finalize.hasError()) {
                    Log.w(TAG, "Finalize error " + finalize.getError(), finalize.getCause());
                    if (wantRecording && failedClips >= MAX_FAILED_CLIPS) {
                        // Give up rather than restart in a tight loop (e.g. storage full).
                        wantRecording = false;
                        state = DashcamState.IDLE;
                        resetDuration();
                        stopHeartbeat();
                        message = finalize.getError()
                                == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE
                                ? "Storage full"
                                : "Recording failed";
                        emit();
                        return;
                    }
                    if (!wantRecording) {
                        state = DashcamState.IDLE;
                        resetDuration();
                        stopHeartbeat();
                        message = "Stopped";
                        emit();
                        return;
                    }
                }
                if (wantRecording) {
                    rotating = false;
                    resetDuration();
                    startClip();
                } else {
                    wantRecording = false;
                    rotating = false;
                    state = DashcamState.IDLE;
                    resetDuration();
                    stopHeartbeat();
                    message = "Stopped";
                    emit();
                }
            }
            default -> {
            }
        }
    }

    /** Publishes or drops a closed clip's file. Returns whether any footage was kept. */
    private boolean finishSegment(Segment segment, VideoRecordEvent.Finalize finalize) {
        Uri output = finalize.getOutputResults().getOutputUri();
        if (output != null && Uri.EMPTY.equals(output)) {
            output = null;
        }
        boolean keep = !finalize.hasError()
                || finalize.getRecordingStats().getNumBytesRecorded()
                >= LoopStorage.MIN_PUBLISH_BYTES;
        storage.finishClip(output, keep);
        storage.scanIfNeeded(segment.name(), segment.loop());
        if (segment.loop()) {
            storage.pruneLoop();
        }
        return keep;
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void emit() {
        DashcamStatus status = snapshot();
        for (Listener listener : listeners) {
            listener.onStatus(status);
        }
    }

    private String statusMessage() {
        String clip = clipStateMessage();
        return clip.isEmpty() ? message : clip;
    }

    private String clipStateMessage() {
        if (currentClipName.isEmpty()) {
            return "";
        }
        return switch (state) {
            case RECORDING -> "Recording " + currentClipName;
            case PAUSED -> "Paused " + currentClipName;
            default -> "";
        };
    }

    private long liveDurationMs() {
        long duration = baseDurationMs;
        if (runningSinceElapsed != 0L) {
            duration += Math.max(0L, SystemClock.elapsedRealtime() - runningSinceElapsed);
        }
        return duration;
    }

    private void resetDuration() {
        baseDurationMs = 0L;
        runningSinceElapsed = 0L;
    }

    private void startRunningClock() {
        if (runningSinceElapsed == 0L) {
            runningSinceElapsed = SystemClock.elapsedRealtime();
        }
    }

    private void freezeClock() {
        baseDurationMs = liveDurationMs();
        runningSinceElapsed = 0L;
    }

    private void ensureHeartbeat() {
        mainHandler.removeCallbacks(heartbeat);
        mainHandler.postDelayed(heartbeat, 1000L);
    }

    private void stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeat);
    }

    private void onHeartbeat() {
        synchronized (this) {
            if (state != DashcamState.RECORDING) {
                return;
            }
            emit();
            mainHandler.removeCallbacks(heartbeat);
            mainHandler.postDelayed(heartbeat, 1000L);
        }
    }
}
