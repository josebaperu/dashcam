package com.aauto.dashcam;

import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleService;

import com.aauto.dashcam.api.IDashcamCallback;
import com.aauto.dashcam.api.IDashcamControl;

import java.util.Objects;

/**
 * Signature-protected binder that the Android Auto helper uses to drive the camera.
 */
public class DashcamControlService extends LifecycleService implements RecordingEngine.Listener {
    private static final long DURATION_BUCKET_MS = 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RemoteCallbackList<IDashcamCallback> callbacks = new RemoteCallbackList<>();
    private int lastBroadcastState = Integer.MIN_VALUE;
    private boolean lastBroadcastLoop;
    private boolean lastBroadcastFront;
    private String lastBroadcastMessage;
    private long lastBroadcastBucket = Long.MIN_VALUE;

    private final IDashcamControl.Stub binder = new IDashcamControl.Stub() {
        @Override
        public void play() {
            runEngine(RecordingEngine::play);
        }

        @Override
        public void pause() {
            runEngine(RecordingEngine::pause);
        }

        @Override
        public void resumeRecording() {
            runEngine(RecordingEngine::resume);
        }

        @Override
        public void stop() {
            runEngine(RecordingEngine::stop);
        }

        @Override
        public void setLoopEnabled(boolean enabled) {
            runEngine(engine -> engine.setLoopEnabled(enabled));
        }

        @Override
        public void toggleCamera() {
            runEngine(RecordingEngine::toggleCamera);
        }

        @Override
        public int getState() {
            return wireState(engine().snapshot());
        }

        @Override
        public boolean isLoopEnabled() {
            return engine().snapshot().loopEnabled();
        }

        @Override
        public boolean isFrontCamera() {
            return engine().snapshot().frontCamera();
        }

        @Override
        public long getDurationMs() {
            return engine().snapshot().durationMs();
        }

        @Override
        public String getStatusMessage() {
            return engine().snapshot().message();
        }

        @Override
        public void registerCallback(IDashcamCallback callback) {
            if (callback != null) {
                callbacks.register(callback);
                // First snapshot goes out from the main thread like every broadcast, so an older
                // snapshot can't overtake a newer broadcast. Registering again just re-sends it.
                main.post(() -> {
                    DashcamStatus status = engine().snapshot();
                    try {
                        callback.onStatusChanged(
                                wireState(status),
                                status.loopEnabled(),
                                status.durationMs(),
                                status.message(),
                                status.frontCamera());
                    } catch (RemoteException ignored) {
                    }
                });
            }
        }

        @Override
        public void unregisterCallback(IDashcamCallback callback) {
            if (callback != null) {
                callbacks.unregister(callback);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // No RecordingService.start here: a bind can arrive while Dashcam is in the background,
        // where Android won't grant a camera foreground service. Report STATE_NO_CAMERA instead.
        engine().addListener(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    @Override
    public void onDestroy() {
        engine().removeListener(this);
        callbacks.kill();
        super.onDestroy();
    }

    @Override
    public void onStatus(DashcamStatus status) {
        long bucket = status.durationMs() / DURATION_BUCKET_MS;
        int wireState = wireState(status);
        boolean stateChanged = wireState != lastBroadcastState
                || status.loopEnabled() != lastBroadcastLoop
                || status.frontCamera() != lastBroadcastFront
                || !Objects.equals(status.message(), lastBroadcastMessage);
        boolean tick = bucket != lastBroadcastBucket;
        if (!stateChanged && !tick) {
            return;
        }
        lastBroadcastState = wireState;
        lastBroadcastLoop = status.loopEnabled();
        lastBroadcastFront = status.frontCamera();
        lastBroadcastMessage = status.message();
        lastBroadcastBucket = bucket;

        int count = callbacks.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                callbacks.getBroadcastItem(i).onStatusChanged(
                        wireState,
                        status.loopEnabled(),
                        status.durationMs(),
                        status.message(),
                        status.frontCamera());
            } catch (RemoteException ignored) {
            }
        }
        callbacks.finishBroadcast();
    }

    /** Maps camera problems onto extra wire states so the car can say why nothing records. */
    private static int wireState(DashcamStatus status) {
        if (status.state() == DashcamState.IDLE && !status.cameraReady()) {
            return IDashcamControl.STATE_NO_CAMERA;
        }
        if (status.state() == DashcamState.RECORDING && status.waitingForCamera()) {
            return IDashcamControl.STATE_WAITING_FOR_CAMERA;
        }
        return status.state().code;
    }

    private RecordingEngine engine() {
        return DashcamApplication.get(this).engine();
    }

    private void runEngine(EngineAction action) {
        main.post(() -> action.run(engine()));
    }

    @FunctionalInterface
    private interface EngineAction {
        void run(RecordingEngine engine);
    }
}
