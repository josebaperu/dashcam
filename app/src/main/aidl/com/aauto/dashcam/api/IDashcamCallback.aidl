package com.aauto.dashcam.api;

interface IDashcamCallback {
    oneway void onStatusChanged(int state, boolean loopEnabled, long durationMs, String message, boolean frontCamera);
}
