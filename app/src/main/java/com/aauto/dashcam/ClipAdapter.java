package com.aauto.dashcam;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ClipAdapter extends RecyclerView.Adapter<ClipAdapter.Holder> {
    interface Listener {
        void onOpen(LoopStorage.Clip clip);

        void onSelectionChanged(int selectedCount);
    }

    private final List<LoopStorage.Clip> clips = new ArrayList<>();
    private final Set<android.net.Uri> selected = new HashSet<>();
    private final DateFormat dates = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT);
    private final Listener listener;
    private boolean selecting;

    ClipAdapter(Listener listener) {
        this.listener = listener;
    }

    void setClips(List<LoopStorage.Clip> next) {
        clips.clear();
        clips.addAll(next);
        selected.retainAll(uris());
        if (selected.isEmpty()) {
            selecting = false;
        }
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    List<LoopStorage.Clip> selectedClips() {
        List<LoopStorage.Clip> out = new ArrayList<>();
        for (LoopStorage.Clip clip : clips) {
            if (selected.contains(clip.uri())) {
                out.add(clip);
            }
        }
        return out;
    }

    List<LoopStorage.Clip> allClips() {
        return new ArrayList<>(clips);
    }

    void clearSelection() {
        selected.clear();
        selecting = false;
        notifyDataSetChanged();
        listener.onSelectionChanged(0);
    }

    private Set<android.net.Uri> uris() {
        Set<android.net.Uri> set = new HashSet<>();
        for (LoopStorage.Clip clip : clips) {
            set.add(clip.uri());
        }
        return set;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_clip, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LoopStorage.Clip clip = clips.get(position);
        holder.name.setText(clip.displayName());
        holder.meta.setText(dates.format(new Date(clip.dateAddedMs()))
                + "  ·  " + LoopStorage.formatBytes(clip.sizeBytes()));
        boolean checked = selected.contains(clip.uri());
        holder.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
        holder.check.setChecked(checked);
        holder.itemView.setBackgroundColor(checked ? 0x332A2F38 : 0x00000000);
        holder.itemView.setOnClickListener(v -> {
            if (selecting) {
                toggle(clip);
            } else {
                listener.onOpen(clip);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            selecting = true;
            selected.add(clip.uri());
            notifyDataSetChanged();
            listener.onSelectionChanged(selected.size());
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return clips.size();
    }

    private void toggle(LoopStorage.Clip clip) {
        if (!selected.add(clip.uri())) {
            selected.remove(clip.uri());
        }
        if (selected.isEmpty()) {
            selecting = false;
        }
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final CheckBox check;
        final TextView name;
        final TextView meta;

        Holder(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.check);
            name = itemView.findViewById(R.id.name);
            meta = itemView.findViewById(R.id.meta);
        }
    }
}
