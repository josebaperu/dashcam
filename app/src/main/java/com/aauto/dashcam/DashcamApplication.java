package com.aauto.dashcam;

import android.app.Application;
import android.content.Context;

public class DashcamApplication extends Application {
    private RecordingEngine engine;

    public static DashcamApplication get(Context context) {
        return (DashcamApplication) context.getApplicationContext();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new RecordingEngine(this);
    }

    public RecordingEngine engine() {
        return engine;
    }
}
