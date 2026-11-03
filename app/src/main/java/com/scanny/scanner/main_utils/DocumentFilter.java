package com.scanny.scanner.main_utils;

import android.graphics.Bitmap;

import com.scanlibrary.ScanActivity;

/**
 * The document filters offered after a scan (Original, Magic Color, Gray, B&W), and a helper for
 * previewing them on a downscaled copy so switching is instant; the selected filter is applied to
 * the full-size page only when it is saved.
 */
public final class DocumentFilter {

    public static final int ORIGINAL = 0;
    public static final int MAGIC_COLOR = 1;
    public static final int GRAY = 2;
    public static final int BLACK_WHITE = 3;

    /** Longest side of the preview copy: sharp on a phone screen, a fraction of a full scan's pixels. */
    public static final int PREVIEW_SIDE = 1600;

    private DocumentFilter() {
    }

    /**
     * Applies {@code filter} to {@code bitmap} (call off the main thread). Returns {@code bitmap}
     * itself for {@link #ORIGINAL}, or when the filter cannot run on it (e.g. not ARGB_8888).
     */
    public static Bitmap apply(int filter, Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888 ? bitmap : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb == null) {
            return bitmap;
        }
        Bitmap out;
        switch (filter) {
            case MAGIC_COLOR:
                out = ScanActivity.getMagicColorBitmap(argb);
                break;
            case GRAY:
                out = ScanActivity.getGrayBitmap(argb);
                break;
            case BLACK_WHITE:
                out = ScanActivity.getBWBitmap(argb);
                break;
            default:
                out = null;
                break;
        }
        if (argb != bitmap && argb != out) {
            argb.recycle();
        }
        return out != null ? out : bitmap;
    }

    /** {@code bitmap} scaled so its longer side is at most {@code maxSide} (the same bitmap if it already fits). */
    public static Bitmap downscale(Bitmap bitmap, int maxSide) {
        if (bitmap == null) {
            return null;
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxSide) {
            return bitmap;
        }
        double s = maxSide / (double) longest;
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s)), true);
    }
}
