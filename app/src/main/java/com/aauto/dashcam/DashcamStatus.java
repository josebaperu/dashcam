package com.aauto.dashcam;

public record DashcamStatus(
        DashcamState state,
        boolean loopEnabled,
        long durationMs,
        String message,
        boolean frontCamera,
        boolean cameraReady
) {
    public static DashcamStatus idle(boolean loopEnabled, String message) {
        return new DashcamStatus(DashcamState.IDLE, loopEnabled, 0L, message, false, false);
    }
}
