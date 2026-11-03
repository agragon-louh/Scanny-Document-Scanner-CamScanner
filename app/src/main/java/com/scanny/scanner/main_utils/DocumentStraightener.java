package com.scanny.scanner.main_utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;

import com.scanlibrary.ScanActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.pqpo.smartcropperlib.SmartCropper;

/**
 * Auto-straighten: finds the page quadrilateral, perspective-warps it to a rectangle, then rotates
 * away any remaining text-line skew. Runs before the crop screen so it opens on a flat page.
 *
 * <p>SmartCropper (the model the crop screen already uses) proposes the page corners; the pipeline
 * itself ({@code ScanImageOps.straighten}, unit-tested on the JVM) checks them and falls back to
 * its own OpenCV detector when they are implausible or just the whole frame. Every step is
 * best-effort: on any failure the input bitmap is returned unchanged.</p>
 */
public final class DocumentStraightener {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DocumentStraightener() {
    }

    public interface Callback {
        /** Called on the main thread with the straightened bitmap (the input itself if nothing changed). */
        void onDone(Bitmap result);
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Constant.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(Constant.KEY_AUTO_STRAIGHTEN, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(Constant.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(Constant.KEY_AUTO_STRAIGHTEN, enabled).apply();
    }

    /**
     * Straightens {@code source} off the main thread when the setting is on and posts the result to
     * {@code callback} on the main thread. When off, the callback fires immediately with the source.
     * On success, {@link Constant#rawOriginal}/{@link Constant#straightened} are set so the crop
     * screen can restore the untouched page.
     */
    public static void straightenAsync(Context context, final Bitmap source, final Callback callback) {
        if (source == null || !isEnabled(context)) {
            Constant.rawOriginal = null;
            Constant.straightened = null;
            callback.onDone(source);
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap result = straighten(source);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result != source) {
                            Constant.rawOriginal = source;
                            Constant.straightened = result;
                        } else {
                            Constant.rawOriginal = null;
                            Constant.straightened = null;
                        }
                        callback.onDone(result);
                    }
                });
            }
        });
    }

    /** Synchronous core; call from a background thread. Never throws; returns {@code source} when unchanged. */
    public static Bitmap straighten(Bitmap source) {
        if (source == null) {
            return null;
        }
        try {
            Bitmap argb = source.getConfig() == Bitmap.Config.ARGB_8888
                    ? source : source.copy(Bitmap.Config.ARGB_8888, false);
            if (argb == null) {
                return source;
            }
            Bitmap result = ScanActivity.getStraightenedBitmap(argb, smartCropperHint(argb));
            if (argb != source && argb != result) {
                argb.recycle();
            }
            return result != null ? result : source;
        } catch (Throwable t) { // includes OutOfMemoryError and missing-native-library errors
            return source;
        }
    }

    /** SmartCropper's corners as {@code [x0, y0, ..., x3, y3]}, or null if it is unavailable. */
    private static float[] smartCropperHint(Bitmap bitmap) {
        try {
            Point[] pts = SmartCropper.scan(bitmap);
            if (pts == null || pts.length != 4) {
                return null;
            }
            float[] hint = new float[8];
            for (int i = 0; i < 4; i++) {
                hint[2 * i] = pts[i].x;
                hint[2 * i + 1] = pts[i].y;
            }
            return hint;
        } catch (Throwable ignored) { // model failed to load, native crash guard, etc.
            return null;
        }
    }
}
