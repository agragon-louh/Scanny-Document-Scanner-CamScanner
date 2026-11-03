package com.scanny.scanner.main_utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import com.scanny.scanner.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * User exports (PDFs and images) in the public {@code Download/<app name>/} folder.
 *
 * <p>The app no longer sets {@code requestLegacyExternalStorage}, which on Android 10 gave it
 * unscoped access to all of shared storage. Without it, Android 10 does not allow direct file
 * access to {@code Download/}, so exports go through {@link MediaStore.Downloads} there.
 * Android 9 and older write with WRITE_EXTERNAL_STORAGE, and Android 11+ lets apps create their
 * own files in {@code Download/} with the File API.</p>
 */
public final class ExportStorage {

    private ExportStorage() {
    }

    /** True on Android 10, the one version where exports must go through MediaStore. */
    public static boolean needsMediaStore() {
        return Build.VERSION.SDK_INT == Build.VERSION_CODES.Q;
    }

    /** {@code Download/<app name>} or {@code Download/<app name>/<sub>}. */
    public static File publicDir(Context context, String sub) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                context.getString(R.string.app_name));
        return sub == null ? dir : new File(dir, sub);
    }

    /**
     * Copies {@code source} into {@code Download/<app name>[/sub]} through MediaStore and returns
     * the file name MediaStore stored. MediaStore adds " (1)" etc. when the name is taken, so an
     * existing export is never overwritten.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    public static String publish(Context context, File source, String sub, String mimeType) throws IOException {
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + context.getString(R.string.app_name)
                + (sub == null ? "" : "/" + sub) + "/";
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore insert failed");
        }
        try {
            try (InputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("Cannot open MediaStore entry");
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } catch (IOException | RuntimeException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        }
        return source.getName();
    }
}
