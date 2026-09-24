package com.aauto.dashcam;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Recorder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Public DCIM/Dashcam album so clips show up in Gallery / Photos.
 */
final class LoopStorage {
    private static final String TAG = "LoopStorage";
    static final String ALBUM = "Dashcam";
    static final String LOOP_FOLDER = "loop";
    static final String RELATIVE_PATH = Environment.DIRECTORY_DCIM + "/" + ALBUM;
    static final String RELATIVE_PATH_LOOP = RELATIVE_PATH + "/" + LOOP_FOLDER;
    static final long LOOP_CAP_BYTES = 5L * 1024L * 1024L * 1024L;
    /** ~8 Mbps, typical CameraX 720p with audio. */
    static final long HD_BYTES_PER_SECOND = 1_000_000L;
    static final long MIN_PUBLISH_BYTES = 50_000L;

    private final Context app;
    private final SimpleDateFormat names =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    LoopStorage(Context context) {
        app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT < 29) {
            ensureDir(publicDir(false));
            ensureDir(publicDir(true));
        }
    }

    String newDisplayName() {
        return "dashcam_" + names.format(new Date())
                + "_" + (System.nanoTime() % 10_000L) + ".mp4";
    }

    PendingRecording prepare(Recorder recorder, Context context, String displayName, boolean loop) {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(loop));
            MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                    app.getContentResolver(),
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(values)
                    .build();
            return recorder.prepareRecording(context, options);
        }
        File dir = publicDir(loop);
        ensureDir(dir);
        File file = new File(dir, displayName);
        FileOutputOptions options = new FileOutputOptions.Builder(file).build();
        return recorder.prepareRecording(context, options);
    }

    void scanIfNeeded(String displayName, boolean loop) {
        if (Build.VERSION.SDK_INT >= 29) {
            return;
        }
        File file = new File(publicDir(loop), displayName);
        MediaScannerConnection.scanFile(
                app, new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
    }

    /**
     * Call only while idle. CameraX leaves MediaStore IS_PENDING=1 rows and
     * cache temps; those stay invisible and can block the next insert until
     * app storage is cleared. RELATIVE_PATH is often still null on pending
     * rows, so this scans every pending video this app can see.
     */
    void cleanupOrphans() {
        cleanupPending();
        sweepEmptyAlbumFiles();
        sweepAppTemps();
    }

    void cleanupPending() {
        if (Build.VERSION.SDK_INT < 29) {
            return;
        }
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.RELATIVE_PATH
        };
        String selection = MediaStore.MediaColumns.IS_PENDING + "=1";
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        int cleaned = 0;
        try (Cursor cursor = queryPending(collection, projection, selection, null)) {
            if (cursor == null) {
                return;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameCol);
                String path = cursor.getString(pathCol);
                if (!ours(name, path)) {
                    continue;
                }
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol));
                finishClip(uri, cursor.getLong(sizeCol) >= MIN_PUBLISH_BYTES);
                cleaned++;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to clean pending clips", e);
        }
        if (cleaned > 0) {
            Log.i(TAG, "Recovered or dropped " + cleaned + " pending MediaStore rows");
        }
    }

    private static boolean ours(String displayName, String relativePath) {
        if (displayName != null && displayName.startsWith("dashcam_")) {
            return true;
        }
        if (relativePath != null && relativePath.contains(ALBUM)) {
            return true;
        }
        return displayName == null && relativePath == null;
    }

    private void sweepEmptyAlbumFiles() {
        sweepEmptyFiles(publicDir(false));
        sweepEmptyFiles(publicDir(true));
    }

    private static void sweepEmptyFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && file.length() == 0L && file.getName().endsWith(".mp4")) {
                if (!file.delete()) {
                    Log.w(TAG, "Unable to delete empty " + file);
                }
            }
        }
    }

    private void sweepAppTemps() {
        wipeVideoTemps(app.getCacheDir());
        wipeVideoTemps(app.getExternalCacheDir());
        wipeVideoTemps(new File(app.getFilesDir(), "video"));
        wipeVideoTemps(new File(app.getNoBackupFilesDir(), "video"));
    }

    private static void wipeVideoTemps(@Nullable File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.US);
            if (file.isDirectory()) {
                if (name.contains("camera") || name.contains("video") || name.contains("record")) {
                    wipeVideoTemps(file);
                    file.delete();
                }
                continue;
            }
            if (name.endsWith(".tmp") || name.endsWith(".mp4") || name.endsWith(".pending")
                    || name.startsWith("camerax") || name.startsWith("recording")) {
                if (!file.delete()) {
                    Log.w(TAG, "Unable to delete temp " + file);
                }
            }
        }
    }

    void finishClip(@Nullable Uri uri, boolean keep) {
        if (uri == null || Build.VERSION.SDK_INT < 29) {
            return;
        }
        if (keep) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try {
                app.getContentResolver().update(uri, values, null, null);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to publish " + uri, e);
            }
            return;
        }
        try {
            app.getContentResolver().delete(uri, null, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to drop pending " + uri, e);
        }
    }

    @Nullable
    private Cursor queryPending(Uri collection, String[] projection, String selection, String[] args) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                collection = MediaStore.setIncludePending(collection);
            } catch (RuntimeException ignored) {
            }
        }
        return app.getContentResolver().query(collection, projection, selection, args, null);
    }

    void pruneLoop() {
        int guard = 0;
        while (usedBytes(true) > LOOP_CAP_BYTES && guard++ < 1000) {
            if (!deleteOldest(true)) {
                return;
            }
        }
    }

    private boolean deleteOldest(boolean loop) {
        List<Clip> clips = listClips(loop);
        if (clips.isEmpty()) {
            return false;
        }
        return deleteClip(clips.get(clips.size() - 1).uri());
    }

    static String relativePath(boolean loop) {
        return loop ? RELATIVE_PATH_LOOP : RELATIVE_PATH;
    }

    static File publicDir(boolean loop) {
        File root = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), ALBUM);
        return loop ? new File(root, LOOP_FOLDER) : root;
    }

    private static void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create clip directory " + dir);
        }
    }

    long availableBytes() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (dir == null || dir.getAbsolutePath() == null) {
            dir = Environment.getDataDirectory();
        }
        try {
            if (!dir.exists()) {
                File parent = dir.getParentFile();
                dir = parent != null ? parent : Environment.getDataDirectory();
            }
            return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Unable to read free space", e);
            return 0L;
        }
    }

    String snackbarMessage() {
        long regular = Math.max(0L, availableBytes());
        long loop = Math.min(
                Math.max(0L, LOOP_CAP_BYTES - usedBytes(true)),
                regular);
        return "Each clip is up to 10 min. "
                + "Recordings: " + formatBytes(regular)
                + " (~" + formatDuration(estimatedMs(regular)) + ") in "
                + publicDir(false).getAbsolutePath()
                + ". Loop: " + formatBytes(loop)
                + " of 5 GB (~" + formatDuration(estimatedMs(loop)) + ") in "
                + publicDir(true).getAbsolutePath() + ".";
    }

    static long estimatedMs(long bytes) {
        return bytes * 1000L / HD_BYTES_PER_SECOND;
    }

    static String formatDuration(long durationMs) {
        long totalMinutes = Math.max(0L, durationMs) / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0L) {
            return hours + " h " + minutes + " min";
        }
        if (totalMinutes == 0L && durationMs > 0L) {
            return "< 1 min";
        }
        return minutes + " min";
    }

    long usedBytes(boolean loop) {
        long total = 0L;
        for (Clip clip : listClips(loop)) {
            total += clip.sizeBytes();
        }
        return total;
    }

    List<Clip> listClips(boolean loop) {
        List<Clip> clips = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            String[] projection = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.RELATIVE_PATH
            };
            String selection;
            String[] args;
            if (loop) {
                selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
                args = new String[]{RELATIVE_PATH_LOOP + "%"};
            } else {
                selection = "(" + MediaStore.Video.Media.RELATIVE_PATH + "=? OR "
                        + MediaStore.Video.Media.RELATIVE_PATH + "=?)"
                        + " AND " + MediaStore.Video.Media.RELATIVE_PATH + " NOT LIKE ?";
                args = new String[]{RELATIVE_PATH, RELATIVE_PATH + "/", RELATIVE_PATH_LOOP + "%"};
            }
            try (Cursor cursor = app.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (cursor == null) {
                    return clips;
                }
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    Uri uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    String name = cursor.getString(nameCol);
                    long dateMs = cursor.getLong(dateCol) * 1000L;
                    long size = cursor.getLong(sizeCol);
                    clips.add(new Clip(uri, name == null ? "clip" : name, dateMs, size));
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Unable to list clips", e);
            }
            return clips;
        }
        File[] files = publicDir(loop).listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null) {
            return clips;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    app, app.getPackageName() + ".files", file);
            clips.add(new Clip(uri, file.getName(), file.lastModified(), file.length()));
        }
        return clips;
    }

    boolean deleteClip(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return app.getContentResolver().delete(uri, null, null) > 0;
            }
            String name = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
            File file = new File(publicDir(false), name);
            if (!file.exists()) {
                file = new File(publicDir(true), name);
            }
            boolean ok = file.exists() && file.delete();
            if (ok) {
                MediaScannerConnection.scanFile(
                        app, new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            }
            return ok;
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to delete " + uri, e);
            return false;
        }
    }

    int deleteAll(boolean loop) {
        int removed = 0;
        for (Clip clip : listClips(loop)) {
            if (deleteClip(clip.uri())) {
                removed++;
            }
        }
        return removed;
    }

    record Clip(Uri uri, String displayName, long dateAddedMs, long sizeBytes) {
    }

    static String formatBytes(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1f GB", gb);
        }
        return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0));
    }

}
