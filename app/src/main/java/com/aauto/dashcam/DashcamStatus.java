package com.aauto.dashcam;

public record DashcamStatus(
        DashcamState state,
        boolean loopEnabled,
        long durationMs,
        String message,
        boolean frontCamera,
        boolean cameraReady,
        boolean waitingForCamera
) {
}
