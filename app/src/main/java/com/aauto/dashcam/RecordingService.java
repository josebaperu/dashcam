package com.aauto.dashcam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.lifecycle.LifecycleService;

public class RecordingService extends LifecycleService implements RecordingEngine.Listener {
    public static final String ACTION_SHUTDOWN = "com.aauto.dashcam.action.SHUTDOWN";
    public static final String ACTION_EXIT_UI = "com.aauto.dashcam.action.EXIT_UI";
    private static final String CHANNEL_ID = "dashcam_recording";
    private static final int NOTIFICATION_ID = 42;
    /** Renewed on every status while recording (at least once a second); lapses only if those stop. */
    private static final long WAKE_LOCK_TIMEOUT_MS = 5L * 60L * 1000L;

    private PowerManager.WakeLock wakeLock;
    /** Text currently shown; Android drops updates from apps posting more than ~5 per second. */
    @Nullable private String postedText;

    public static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, RecordingService.class));
        } catch (RuntimeException e) {
            Log.w("RecordingService", "Unable to start foreground service", e);
        }
    }

    public static void shutdown(Context context) {
        Intent intent = new Intent(context, RecordingService.class);
        intent.setAction(ACTION_SHUTDOWN);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            Log.w("RecordingService", "Unable to stop recording service", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startInForeground(notificationText(DashcamApplication.get(this).engine().snapshot()));
        DashcamApplication.get(this).engine().addListener(this);
        DashcamApplication.get(this).engine().recoverStorage();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            DashcamApplication.get(this).engine().bindToLifecycle(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_SHUTDOWN.equals(intent.getAction())) {
            closeDown();
            return START_NOT_STICKY;
        }
        // Current state, not "ready": this also runs while recording or paused.
        startInForeground(notificationText(DashcamApplication.get(this).engine().snapshot()));
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return null;
    }

    @Override
    public void onDestroy() {
        DashcamApplication.get(this).engine().removeListener(this);
        releaseWakeLock();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    private void closeDown() {
        DashcamApplication.get(this).engine().unbindCamera();
        releaseWakeLock();
        Intent exitUi = new Intent(ACTION_EXIT_UI);
        exitUi.setPackage(getPackageName());
        sendBroadcast(exitUi);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onStatus(DashcamStatus status) {
        String text = notificationText(status);
        if (!text.equals(postedText)) {
            postedText = text;
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, notification(text));
        }
        if (status.state() == DashcamState.RECORDING) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
    }

    private String notificationText(DashcamStatus status) {
        return switch (status.state()) {
            case RECORDING -> status.waitingForCamera()
                    ? getString(R.string.notification_waiting)
                    : getString(R.string.notification_recording)
                            + "  " + formatDuration(status.durationMs());
            case PAUSED -> getString(R.string.notification_paused);
            case IDLE -> getString(R.string.notification_ready);
        };
    }

    private void startInForeground(String text) {
        postedText = text;
        int types = 0;
        // Camera and microphone service types exist from Android 11; Android 10 needs none.
        if (Build.VERSION.SDK_INT >= 30) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            // Keeps GPS speed updating for the overlay while the app is in the background.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), types);
        } catch (RuntimeException e) {
            Log.e("RecordingService", "startForeground failed", e);
        }
    }

    private Notification notification(String text) {
        PendingIntent content = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent exitIntent = new Intent(this, RecordingService.class);
        exitIntent.setAction(ACTION_SHUTDOWN);
        PendingIntent exit = PendingIntent.getService(
                this,
                1,
                exitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(R.drawable.ic_exit, getString(R.string.exit), exit)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcam:recording");
            wakeLock.setReferenceCounted(false);
        }
        // Not reference counted, so this just pushes the timeout back while recording continues.
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }
}
