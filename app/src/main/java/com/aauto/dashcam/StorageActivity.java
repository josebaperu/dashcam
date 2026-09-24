package com.aauto.dashcam;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class StorageActivity extends AppCompatActivity implements ClipAdapter.Listener {
    private LoopStorage storage;
    private ClipAdapter adapter;
    private TextView empty;
    private MaterialButton btnDelete;
    private MaterialButton btnDeleteAll;
    private boolean showingLoop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage);
        storage = new LoopStorage(this);
        empty = findViewById(R.id.empty);
        btnDelete = findViewById(R.id.btnDelete);
        btnDeleteAll = findViewById(R.id.btnDeleteAll);
        RecyclerView list = findViewById(R.id.list);
        adapter = new ClipAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnDelete.setOnClickListener(v -> confirmDeleteSelected());
        btnDeleteAll.setOnClickListener(v -> confirmDeleteAll());

        Spinner spinner = findViewById(R.id.folderSpinner);
        ArrayAdapter<CharSequence> folders = ArrayAdapter.createFromResource(
                this, R.array.storage_folders, R.layout.spinner_item);
        folders.setDropDownViewResource(R.layout.spinner_item);
        spinner.setAdapter(folders);
        View folderPicker = findViewById(R.id.folderPicker);
        folderPicker.setOnClickListener(v -> spinner.performClick());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showingLoop = position == 1;
                adapter.clearSelection();
                reload();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onOpen(LoopStorage.Clip clip) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(clip.uri(), "video/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.storage_no_player, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onSelectionChanged(int selectedCount) {
        int visibility = selectedCount > 0 ? View.VISIBLE : View.GONE;
        btnDelete.setVisibility(visibility);
        btnDeleteAll.setVisibility(visibility);
    }

    private void reload() {
        // Via the engine: recording can start from the car while this screen is open, and
        // cleanup must not touch the pending row of a clip being recorded.
        DashcamApplication.get(this).engine().recoverStorage();
        List<LoopStorage.Clip> clips = storage.listClips(showingLoop);
        adapter.setClips(clips);
        empty.setText(showingLoop ? R.string.storage_empty_loop : R.string.storage_empty);
        empty.setVisibility(clips.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmDeleteSelected() {
        List<LoopStorage.Clip> clips = adapter.selectedClips();
        if (clips.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(getResources().getQuantityString(
                        R.plurals.storage_delete_confirm, clips.size(), clips.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    for (LoopStorage.Clip clip : clips) {
                        storage.deleteClip(clip.uri());
                    }
                    adapter.clearSelection();
                    reload();
                })
                .show();
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.storage_delete_all_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_all, (d, w) -> {
                    storage.deleteAll(showingLoop);
                    adapter.clearSelection();
                    reload();
                })
                .show();
    }
}
