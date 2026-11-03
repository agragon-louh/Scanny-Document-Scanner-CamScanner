package com.scanny.scanner.main_utils;

import android.content.Context;
import android.util.Log;

import com.scanny.scanner.db.DBHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Where the app keeps its files. Scans and signatures live in internal storage
 * ({@code getFilesDir()}), which only this app can read. They used to live in the app's
 * external files dir ({@code Android/data/<pkg>/files}), which is readable over USB, by file
 * managers on older Android versions and by anything with broad storage access.
 */
public final class AppStorage {
    private static final String TAG = "AppStorage";
    /** Temporary files in these cache dirs are removed once they are older than this. */
    private static final long CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private AppStorage() {
    }

    /** Root for page images; DBHelper only deletes files inside it. */
    public static File root(Context context) {
        return context.getFilesDir();
    }

    /** Page images. {@link BitmapUtils#writeImageFile} creates it on first use. */
    public static File documentsDir(Context context) {
        return new File(context.getFilesDir(), "Documents");
    }

    public static File signatureDir(Context context) {
        return new File(context.getFilesDir(), "Signature");
    }

    /** Raw camera captures, kept only long enough to read their EXIF orientation. */
    public static File captureDir(Context context) {
        File dir = new File(context.getCacheDir(), "capture");
        dir.mkdirs();
        return dir;
    }

    /** Files handed to other apps through the FileProvider (images, QR codes). */
    public static File shareDir(Context context) {
        File dir = new File(context.getCacheDir(), "share");
        dir.mkdirs();
        return dir;
    }

    /**
     * Deletes stale share, PDF and capture files from the cache. Recent files are kept because
     * the app they were shared with may still be reading them.
     */
    public static void clearStaleCache(Context context) {
        long cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS;
        for (String name : new String[]{"share", "pdf", "capture"}) {
            File[] files = new File(context.getCacheDir(), name).listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file.isFile() && file.lastModified() < cutoff) {
                    file.delete();
                }
            }
        }
    }

    /**
     * Moves everything from the old external files dir into internal storage and repoints the
     * database at the new copies. Safe to run on every launch: it does nothing once the old dir
     * is empty, and an interrupted run is simply repeated. If copying fails (e.g. no space) the
     * old files and database paths are left untouched so nothing is lost.
     * Must not run on the main thread.
     */
    public static void migrateFromExternal(Context context) {
        File oldRoot = context.getExternalFilesDir(null);
        if (oldRoot == null) {
            return;
        }
        ArrayList<File> oldFiles = new ArrayList<>();
        listFiles(oldRoot, oldFiles);
        if (oldFiles.isEmpty()) {
            return;
        }
        File newRoot = root(context);
        String oldPrefix = oldRoot.getAbsolutePath() + File.separator;
        try {
            for (File file : oldFiles) {
                File target = new File(newRoot, file.getAbsolutePath().substring(oldPrefix.length()));
                copy(file, target);
            }
        } catch (IOException e) {
            Log.w(TAG, "Migration to internal storage failed; keeping external files", e);
            return;
        }
        DBHelper dbHelper = new DBHelper(context);
        try {
            dbHelper.relocateImagePaths(oldPrefix, newRoot.getAbsolutePath() + File.separator);
        } finally {
            dbHelper.close();
        }
        for (File file : oldFiles) {
            file.delete();
        }
        deleteEmptyDirs(oldRoot);
    }

    private static void listFiles(File dir, ArrayList<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                listFiles(file, out);
            } else {
                out.add(file);
            }
        }
    }

    /** Copies via a temp file and rename, so a crash never leaves a truncated target. */
    private static void copy(File source, File target) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(tmp)) {
            copyStream(in, out);
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Cannot rename " + tmp);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void deleteEmptyDirs(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                deleteEmptyDirs(file);
                file.delete();
            }
        }
    }
}
