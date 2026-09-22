package com.aauto.dashcam.api;

import com.aauto.dashcam.api.IDashcamCallback;

interface IDashcamControl {
    const int STATE_IDLE = 0;
    const int STATE_RECORDING = 1;
    const int STATE_PAUSED = 2;

    oneway void play();
    oneway void pause();
    oneway void resumeRecording();
    oneway void stop();
    oneway void setLoopEnabled(boolean enabled);
    oneway void toggleCamera();

    int getState();
    boolean isLoopEnabled();
    boolean isFrontCamera();
    long getDurationMs();
    String getStatusMessage();

    void registerCallback(IDashcamCallback callback);
    void unregisterCallback(IDashcamCallback callback);
}
