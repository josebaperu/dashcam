package com.aauto.dashcam;

import com.aauto.dashcam.api.IDashcamControl;

public enum DashcamState {
    IDLE(IDashcamControl.STATE_IDLE),
    RECORDING(IDashcamControl.STATE_RECORDING),
    PAUSED(IDashcamControl.STATE_PAUSED);

    public final int code;

    DashcamState(int code) {
        this.code = code;
    }

    public static DashcamState fromCode(int code) {
        return switch (code) {
            case IDashcamControl.STATE_RECORDING -> RECORDING;
            case IDashcamControl.STATE_PAUSED -> PAUSED;
            default -> IDLE;
        };
    }
}
