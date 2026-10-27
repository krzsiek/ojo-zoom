package it.danieleverducci.ojo.utils;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saves snapshots to Pictures/OjoZoom and recordings to Movies/OjoZoom, where the gallery finds them.
 * VLC needs a plain directory to record into, so recordings are written to the app's own storage
 * first (one directory per recording) and moved to Movies/OjoZoom when they are finished.
 * Recordings left there by a crash are moved the next time the cameras are shown.
 */
public class MediaSaver {
    private static final String TAG = "MediaSaver";
    private static final String FOLDER = "OjoZoom";
    private static final String RECORDINGS_TEMP_DIR = "recordings";
    private static final long FILE_CHECK_INTERVAL_MS = 1000;
    private static final long FILE_CLOSE_TIMEOUT_MS = 20000;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Set<String> activeRecordings = new HashSet<>();

    public interface Callback {
        /**
         * Called on the main thread
         * @param success false if nothing was saved
         */
        void onDone(boolean success);
    }

    /**
     * Android 6 - 9 need the storage permission to write to Pictures and Movies
     */
    public static boolean needsStoragePermission(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Free space on the storage recordings are written to
     */
    public static long getFreeBytes(Context context) {
        try {
            return new StatFs(getRecordingsRoot(context).getAbsolutePath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    public static long getSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null)
            for (File f : files)
                size += f.length();
        return size;
    }

    public static void saveSnapshot(Context context, Bitmap bitmap, String cameraName, Callback callback) {
        Context appContext = context.getApplicationContext();
        String name = fileName(cameraName) + ".jpg";
        executor.execute(() -> {
            boolean success = false;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
                    success = writeToMediaStore(appContext,
                            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
                            out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out));
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER);
                    dir.mkdirs();
                    File file = new File(dir, name);
                    try (OutputStream out = new FileOutputStream(file)) {
                        success = bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    }
                    if (success)
                        scanFile(appContext, file);
                }
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Unable to save snapshot", e);
            }
            bitmap.recycle();
            boolean result = success;
            mainHandler.post(() -> callback.onDone(result));
        });
    }

    /**
     * Creates the directory a new recording is written to and marks it as in use
     */
    public static File newRecordingDir(Context context, String cameraName) {
        File dir = new File(getRecordingsRoot(context), fileName(cameraName));
        dir.mkdirs();
        synchronized (activeRecordings) {
            activeRecordings.add(dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * Moves a finished recording to Movies/OjoZoom, once VLC has finished writing it
     */
    public static void finishRecording(Context context, File dir, Callback callback) {
        Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            // VLC closes the file asynchronously (later still if the view changes at the same time):
            // wait until the size stops changing
            long lastSize = -1;
            long waited = 0;
            while (waited < FILE_CLOSE_TIMEOUT_MS) {
                sleep(FILE_CHECK_INTERVAL_MS);
                waited += FILE_CHECK_INTERVAL_MS;
                long size = getSize(dir);
                if (size > 0 && size == lastSize)
                    break;
                lastSize = size;
            }
            synchronized (activeRecordings) {
                activeRecordings.remove(dir.getAbsolutePath());
            }
            boolean success = publishRecordingDir(appContext, dir);
            mainHandler.post(() -> callback.onDone(success));
        });
    }

    /**
     * Moves recordings left behind (app killed while recording) to Movies/OjoZoom
     */
    public static void publishLeftoverRecordings(Context context) {
        Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            File[] dirs = getRecordingsRoot(appContext).listFiles();
            if (dirs == null)
                return;
            for (File dir : dirs) {
                synchronized (activeRecordings) {
                    if (activeRecordings.contains(dir.getAbsolutePath()))
                        continue;
                }
                if (dir.isDirectory())
                    publishRecordingDir(appContext, dir);
            }
        });
    }

    private static boolean publishRecordingDir(Context context, File dir) {
        boolean success = false;
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (f.length() == 0)
                    continue;   // Never written: removed with the directory below
                // The file name chosen by VLC can contain the stream url: use the camera name and time instead
                String name = dir.getName() + (i > 0 ? "_" + i : "") + extensionOf(f);
                try {
                    if (publishRecording(context, f, name))
                        success = true;
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Unable to move recording " + f, e);
                }
            }
        }
        // Keep the directory if a file could not be moved (e.g. no space for the copy), retry next time
        File[] left = dir.listFiles();
        boolean onlyEmptyFiles = true;
        if (left != null)
            for (File f : left)
                if (f.length() > 0)
                    onlyEmptyFiles = false;
        if (onlyEmptyFiles) {
            if (left != null)
                for (File f : left)
                    f.delete();
            dir.delete();
        }
        return success;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static boolean publishRecording(Context context, File file, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, videoMimeType(name));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER);
            boolean ok = writeToMediaStore(context,
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
                    out -> {
                        try (InputStream in = new FileInputStream(file)) {
                            copy(in, out);
                        }
                        return true;
                    });
            if (ok)
                file.delete();
            return ok;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER);
        dir.mkdirs();
        File dest = new File(dir, name);
        if (!file.renameTo(dest)) {
            try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(dest)) {
                copy(in, out);
            } catch (IOException e) {
                dest.delete();
                throw e;
            }
            file.delete();
        }
        scanFile(context, dest);
        return true;
    }

    private interface StreamWriter {
        boolean write(OutputStream out) throws IOException;
    }

    private static boolean writeToMediaStore(Context context, Uri collection, ContentValues values, StreamWriter writer) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, values);
        if (uri == null)
            return false;
        boolean ok = false;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            ok = out != null && writer.write(out);
        } finally {
            if (ok) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } else {
                resolver.delete(uri, null, null);
            }
        }
        return ok;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0)
            out.write(buffer, 0, read);
    }

    private static void scanFile(Context context, File file) {
        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
    }

    private static File getRecordingsRoot(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null)
            base = context.getFilesDir();
        File root = new File(base, RECORDINGS_TEMP_DIR);
        root.mkdirs();
        return root;
    }

    private static String fileName(String cameraName) {
        String safeName = cameraName == null ? "" : cameraName.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        if (safeName.isEmpty())
            safeName = "camera";
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return FOLDER + "_" + safeName + "_" + time;
    }

    private static String extensionOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot).toLowerCase(Locale.US) : ".ts";
    }

    private static String videoMimeType(String name) {
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".mpg") || name.endsWith(".ps")) return "video/mpeg";
        if (name.endsWith(".avi")) return "video/x-msvideo";
        if (name.endsWith(".mov")) return "video/quicktime";
        return "video/mp2t";
    }
}
