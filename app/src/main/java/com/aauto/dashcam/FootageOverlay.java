package com.aauto.dashcam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraEffect;
import androidx.camera.effects.Frame;
import androidx.camera.effects.OverlayEffect;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Burns GPS speed (bottom left) and date/time (bottom right) into recorded video.
 * The preview stays clean; only the VideoCapture stream gets the overlay.
 */
final class FootageOverlay implements LocationListener {
    private static final String TAG = "FootageOverlay";
    /** A GPS speed older than this is shown as unknown rather than frozen. */
    private static final long SPEED_STALE_MS = 5_000L;

    private final Context app;
    private final OverlayEffect effect;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix displayToBuffer = new Matrix();
    private final RectF bounds = new RectF();
    // Only touched on the effect's thread.
    private final SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private volatile float speedKph = Float.NaN;
    private volatile long speedAtElapsed;
    private boolean listening;

    FootageOverlay(Context context) {
        app = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("dashcam-overlay");
        thread.start();
        effect = new OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, new Handler(thread.getLooper()),
                t -> Log.w(TAG, "Overlay effect error", t));
        fill.setColor(Color.WHITE);
        fill.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        outline.setColor(Color.BLACK);
        outline.setTypeface(fill.getTypeface());
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        effect.setOnDrawListener(this::draw);
    }

    CameraEffect effect() {
        return effect;
    }

    /** Starts GPS updates; without location permission the speed just reads "--". */
    @MainThread
    void start() {
        if (listening || ContextCompat.checkSelfPermission(app,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationManager manager = app.getSystemService(LocationManager.class);
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this,
                    Looper.getMainLooper());
            listening = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "GPS unavailable", e);
        }
    }

    @MainThread
    void stop() {
        if (listening) {
            app.getSystemService(LocationManager.class).removeUpdates(this);
            listening = false;
        }
        speedKph = Float.NaN;
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        speedKph = location.hasSpeed() ? location.getSpeed() * 3.6f : Float.NaN;
        speedAtElapsed = SystemClock.elapsedRealtime();
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        speedKph = Float.NaN;
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    private boolean draw(Frame frame) {
        Canvas canvas = frame.getOverlayCanvas();
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        // Draw in upright video coordinates: the buffer may be stored rotated, with the
        // rotation applied at playback, so map the visible crop back through it.
        Rect crop = frame.getCropRect();
        int rotation = frame.getRotationDegrees();
        boolean sideways = rotation % 180 != 0;
        float width = sideways ? crop.height() : crop.width();
        float height = sideways ? crop.width() : crop.height();
        displayToBuffer.setRotate(-rotation);
        bounds.set(0f, 0f, width, height);
        displayToBuffer.mapRect(bounds);
        displayToBuffer.postTranslate(crop.left - bounds.left, crop.top - bounds.top);
        canvas.save();
        canvas.concat(displayToBuffer);

        float textSize = Math.min(width, height) * 0.05f;
        fill.setTextSize(textSize);
        outline.setTextSize(textSize);
        outline.setStrokeWidth(textSize * 0.15f);
        float margin = textSize * 0.6f;
        float baseline = height - margin;

        float speed = speedKph;
        boolean fresh = SystemClock.elapsedRealtime() - speedAtElapsed <= SPEED_STALE_MS;
        String speedText = Float.isNaN(speed) || !fresh
                ? "-- km/h"
                : Math.round(speed) + " km/h";
        drawText(canvas, speedText, margin, baseline, Paint.Align.LEFT);
        drawText(canvas, clock.format(new Date()), width - margin, baseline, Paint.Align.RIGHT);

        canvas.restore();
        return true;
    }

    private void drawText(Canvas canvas, String text, float x, float y, Paint.Align align) {
        outline.setTextAlign(align);
        fill.setTextAlign(align);
        canvas.drawText(text, x, y, outline);
        canvas.drawText(text, x, y, fill);
    }
}
