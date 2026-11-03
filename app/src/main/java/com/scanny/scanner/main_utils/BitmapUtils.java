package com.scanny.scanner.main_utils;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class BitmapUtils {
    private static final String TAG = "BitmapUtils";

    /**
     * Writes an image into {@code dir} under a new, unused name. The data goes to a temporary file
     * that is renamed only once it is completely on disk, so a failed write (e.g. storage full)
     * never leaves a truncated image behind. Returns null when nothing could be written; callers
     * must then leave the database untouched.
     */
    public static File writeImageFile(File dir, byte[] bytes) {
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            Log.w(TAG, "No storage directory for images");
            return null;
        }
        long now = System.currentTimeMillis();
        File file = new File(dir, now + ".jpg");
        // Pages saved within the same millisecond must not overwrite each other.
        for (int i = 1; file.exists(); i++) {
            file = new File(dir, now + "_" + i + ".jpg");
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        } catch (IOException e) {
            Log.w(TAG, "Cannot write to " + tmp, e);
            tmp.delete();
            return null;
        }
        if (!tmp.renameTo(file)) {
            Log.w(TAG, "Cannot rename " + tmp);
            tmp.delete();
            return null;
        }
        return file;
    }

    public static byte[] getBytes(Bitmap bitmap) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    public static Bitmap getImage(byte[] bArr) {
        return BitmapFactory.decodeByteArray(bArr, 0, bArr.length);
    }

    /**
     * Writes {@code bitmap} to a private cache file and returns a FileProvider URI for it (for
     * sharing, or as uCrop's input). This used to insert the image into the public MediaStore,
     * which put full-resolution scans (IDs, signatures) into the user's gallery and photo backup.
     * {@code ContentResolver.delete} on the returned URI deletes the file.
     */
    public static Uri getUri(Context context, Bitmap bitmap) throws IOException {
        if (bitmap == null) {
            throw new IOException("No image");
        }
        File dir = new File(context.getCacheDir(), "share");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File file = new File(dir, System.currentTimeMillis() + ".jpg");
        FileOutputStream outputStream = new FileOutputStream(file);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)) {
                throw new IOException("Cannot encode image");
            }
        } finally {
            outputStream.close();
        }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
    }

    public static Bitmap getBitmap(Context context, Uri uri) throws IOException {
        return MediaStore.Images.Media.getBitmap(context.getContentResolver(), uri);
    }

    public static Uri getURIFromFile(String str, Activity activity) {
        return FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", new File(str));
    }

    public static Bitmap getBitmapFromAsset(Context context, String str) {
        try {
            return BitmapFactory.decodeStream(context.getAssets().open(str));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Bitmap overlay(Bitmap bitmap, Bitmap bitmap2) {
        Bitmap createBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());
        Canvas canvas = new Canvas(createBitmap);
        canvas.drawBitmap(bitmap, new Matrix(), (Paint) null);
        canvas.drawBitmap(bitmap2, new Matrix(), (Paint) null);
        return createBitmap;
    }
}
