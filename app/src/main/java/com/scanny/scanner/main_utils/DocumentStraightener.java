package com.scanny.scanner.main_utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;

import com.scanlibrary.ScanActivity;

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.pqpo.smartcropperlib.SmartCropper;

/**
 * Auto-straighten: finds the page quadrilateral, perspective-warps it to a rectangle, then rotates
 * away any remaining text-line skew. Runs before the crop screen so it opens on a flat page.
 *
 * <p>Detection prefers SmartCropper (the model the crop screen already uses) and falls back to the
 * OpenCV contour search when SmartCropper only returns the whole frame. Every step is best-effort:
 * on any failure the input bitmap is returned unchanged.</p>
 */
public final class DocumentStraightener {

    /** A detected quad smaller than this share of the frame is treated as a false positive. */
    private static final double MIN_QUAD_AREA_FRACTION = 0.20;
    /** Corners within this share of the frame size of the frame's own corners mean "no page found". */
    private static final double FULL_FRAME_TOLERANCE = 0.02;

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

    /** Synchronous core; call from a background thread. Never throws; returns {@code source} on failure. */
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
            Bitmap page = argb;
            Point[] quad = detectQuad(argb);
            if (quad != null) {
                Bitmap warped = SmartCropper.crop(argb, quad);
                if (warped != null) {
                    page = warped;
                }
            }
            Bitmap deskewed = ScanActivity.getDeskewedBitmap(page);
            Bitmap result = deskewed != null ? deskewed : page;
            return result;
        } catch (Throwable t) { // includes OutOfMemoryError and missing-native-library errors
            return source;
        }
    }

    /** Page corners ordered top-left, top-right, bottom-right, bottom-left; null if no real page found. */
    private static Point[] detectQuad(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        Point[] quad = null;
        try {
            Point[] scanned = SmartCropper.scan(bitmap);
            if (scanned != null && scanned.length == 4 && !isFullFrame(scanned, w, h)) {
                quad = scanned;
            }
        } catch (Throwable ignored) {
            // fall through to the OpenCV detector
        }
        if (quad == null) {
            float[] c = ScanActivity.getPoints(bitmap); // [x1..x4, y1..y4], unordered contour points
            if (c != null) {
                Point[] pts = new Point[4];
                for (int i = 0; i < 4; i++) {
                    pts[i] = new Point(Math.round(c[i]), Math.round(c[i + 4]));
                }
                if (!isFullFrame(pts, w, h)) {
                    quad = pts;
                }
            }
        }
        if (quad == null) {
            return null;
        }
        quad = order(quad);
        return area(quad) >= MIN_QUAD_AREA_FRACTION * w * h ? quad : null;
    }

    private static boolean isFullFrame(Point[] pts, int w, int h) {
        double tx = FULL_FRAME_TOLERANCE * w;
        double ty = FULL_FRAME_TOLERANCE * h;
        int[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        for (int[] corner : corners) {
            boolean near = false;
            for (Point p : pts) {
                if (Math.abs(p.x - corner[0]) <= tx && Math.abs(p.y - corner[1]) <= ty) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                return false;
            }
        }
        return true;
    }

    /** Orders any four corners as TL, TR, BR, BL (smallest x+y is TL, largest is BR, x-y gives TR/BL). */
    static Point[] order(Point[] pts) {
        Point[] sorted = Arrays.copyOf(pts, 4);
        Arrays.sort(sorted, new Comparator<Point>() {
            @Override
            public int compare(Point a, Point b) {
                return Integer.compare(a.x + a.y, b.x + b.y);
            }
        });
        Point tl = sorted[0];
        Point br = sorted[3];
        Point a = sorted[1];
        Point b = sorted[2];
        // Of the two remaining corners, top-right has the larger x - y.
        Point tr = (a.x - a.y) >= (b.x - b.y) ? a : b;
        Point bl = tr == a ? b : a;
        return new Point[]{tl, tr, br, bl};
    }

    private static double area(Point[] q) {
        double s = 0;
        for (int i = 0; i < 4; i++) {
            Point p = q[i];
            Point n = q[(i + 1) % 4];
            s += (double) p.x * n.y - (double) n.x * p.y;
        }
        return Math.abs(s) / 2;
    }
}
