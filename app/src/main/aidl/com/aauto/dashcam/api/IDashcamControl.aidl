package com.aauto.dashcam.api;

import com.aauto.dashcam.api.IDashcamCallback;

interface IDashcamControl {
    const int STATE_IDLE = 0;
    const int STATE_RECORDING = 1;
    const int STATE_PAUSED = 2;
    /** Idle and no camera bound: Dashcam has to be opened on the phone before recording. */
    const int STATE_NO_CAMERA = 3;
    /** Recording, but no frames are arriving (e.g. another app has the camera); clock frozen. */
    const int STATE_WAITING_FOR_CAMERA = 4;

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
