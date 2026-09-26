package com.aauto.dashcam;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements RecordingEngine.Listener {
    private PreviewView preview;
    private View recDot;
    private TextView status;
    private TextView timer;
    private TextView message;
    private TextView loopBadge;
    private MaterialButton btnPlay;
    private MaterialButton btnPause;
    private MaterialButton btnResume;
    private MaterialButton btnStop;
    private MaterialButton btnLoop;
    private MaterialButton btnCamera;
    private MaterialButton btnStorage;
    private MaterialButton btnInfo;
    private MaterialButton btnExit;

    private final BroadcastReceiver exitReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finishAndRemoveTask();
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    this::onPermissions);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyScreenPadding(findViewById(R.id.root));
        preview = findViewById(R.id.preview);
        recDot = findViewById(R.id.recDot);
        status = findViewById(R.id.status);
        timer = findViewById(R.id.timer);
        message = findViewById(R.id.message);
        loopBadge = findViewById(R.id.loopBadge);
        btnPlay = findViewById(R.id.btnPlay);
        btnPause = findViewById(R.id.btnPause);
        btnResume = findViewById(R.id.btnResume);
        btnStop = findViewById(R.id.btnStop);
        btnLoop = findViewById(R.id.btnLoop);
        btnCamera = findViewById(R.id.btnCamera);
        btnStorage = findViewById(R.id.btnStorage);
        btnInfo = findViewById(R.id.btnInfo);
        btnExit = findViewById(R.id.btnExit);

        btnPlay.setOnClickListener(v -> engine().play());
        btnPause.setOnClickListener(v -> engine().pause());
        btnResume.setOnClickListener(v -> engine().resume());
        btnStop.setOnClickListener(v -> engine().stop());
        btnLoop.setOnClickListener(v -> engine().setLoopEnabled(!engine().snapshot().loopEnabled()));
        btnCamera.setOnClickListener(v -> engine().toggleCamera());
        btnStorage.setOnClickListener(v -> startActivity(new Intent(this, StorageActivity.class)));
        btnInfo.setOnClickListener(v -> showStorageInfo());
        btnExit.setOnClickListener(v -> RecordingService.shutdown(this));
        int cameraBlue = ContextCompat.getColor(this, R.color.camera_toggle);
        btnCamera.setTextColor(cameraBlue);

        ContextCompat.registerReceiver(
                this,
                exitReceiver,
                new IntentFilter(RecordingService.ACTION_EXIT_UI),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        if (hasRequiredPermissions()) {
            startCameraSession();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                // Installs that predate the speed overlay only granted camera/mic.
                permissionLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION});
            }
        } else {
            permissionLauncher.launch(neededPermissions());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine().addListener(this);
        if (hasRequiredPermissions()) {
            engine().attachPreview(preview);
        }
    }

    @Override
    protected void onStop() {
        engine().detachPreview();
        engine().removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(exitReceiver);
        super.onDestroy();
    }

    @Override
    public void onStatus(DashcamStatus snapshot) {
        status.setText(switch (snapshot.state()) {
            case IDLE -> getString(R.string.status_idle);
            case RECORDING -> getString(R.string.status_recording);
            case PAUSED -> getString(R.string.status_paused);
        });
        timer.setText(RecordingService.formatDuration(snapshot.durationMs()));
        message.setText(snapshot.message());
        recDot.setVisibility(snapshot.state() == DashcamState.RECORDING
                && !snapshot.waitingForCamera() ? View.VISIBLE : View.INVISIBLE);
        loopBadge.setText(snapshot.loopEnabled() ? R.string.loop_on : R.string.loop_off);
        loopBadge.setTextColor(ContextCompat.getColor(
                this, snapshot.loopEnabled() ? R.color.ok : R.color.text_muted));
        btnLoop.setText(snapshot.loopEnabled() ? R.string.loop_on : R.string.loop_off);
        btnCamera.setText(snapshot.frontCamera() ? R.string.camera_front : R.string.camera_rear);
        btnCamera.setTextColor(ContextCompat.getColor(this, R.color.camera_toggle));

        btnPlay.setEnabled(snapshot.state() == DashcamState.IDLE);
        btnPause.setEnabled(snapshot.state() == DashcamState.RECORDING);
        btnResume.setEnabled(snapshot.state() == DashcamState.PAUSED);
        btnStop.setEnabled(snapshot.state() != DashcamState.IDLE);
        // Switching restarts the clip, which would undo a pause.
        btnCamera.setEnabled(snapshot.state() != DashcamState.PAUSED);
        btnStorage.setEnabled(snapshot.state() == DashcamState.IDLE);
        btnInfo.setEnabled(snapshot.state() == DashcamState.IDLE);
    }

    private void onPermissions(Map<String, Boolean> result) {
        if (hasRequiredPermissions()) {
            startCameraSession();
        } else {
            message.setText(R.string.permission_needed);
        }
    }

    private void startCameraSession() {
        RecordingService.start(this);
        engine().attachPreview(preview);
        engine().startSpeedTracking();
    }

    private void applyScreenPadding(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int extra = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
            int extraEnd = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE
                    ? (int) TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 28, getResources().getDisplayMetrics())
                    : 0;
            v.setPadding(
                    bars.left + extra,
                    bars.top + extra,
                    bars.right + extra + extraEnd,
                    bars.bottom + extra);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void showStorageInfo() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.info)
                .setMessage(new LoopStorage(this).snackbarMessage())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private RecordingEngine engine() {
        return DashcamApplication.get(this).engine();
    }

    private boolean hasRequiredPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT <= 28) {
            return camera && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return camera;
    }

    private String[] neededPermissions() {
        ArrayList<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT <= 28) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }
}
