package com.scanny.scanner.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.core.content.res.ResourcesCompat;

/**
 * Draws a tiled, rotated text watermark over an image. Local replacement for
 * {@code com.huangyz0918:androidwm-light}, which was only published on JCenter and can no
 * longer be downloaded.
 */
public final class WatermarkUtil {

    private WatermarkUtil() {
    }

    /**
     * @param fontRes   font resource id (R.font.*), or 0 for the default typeface
     * @param alpha     0..255 text alpha
     * @param textSizeSp text size in "watermark units" (scaled relative to image width)
     * @param rotation  rotation in degrees (e.g. -30)
     */
    public static void applyTiledText(Context context, ImageView imageView, String text, int fontRes,
                                      int color, int alpha, float textSizeSp, float rotation) {
        Drawable drawable = imageView.getDrawable();
        if (!(drawable instanceof BitmapDrawable) || text == null || text.isEmpty()) {
            return;
        }
        Bitmap source = ((BitmapDrawable) drawable).getBitmap();
        if (source == null) {
            return;
        }
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setAlpha(Math.max(0, Math.min(255, alpha)));
        // Scale relative to image width so the watermark looks the same for any resolution.
        paint.setTextSize(Math.max(12f, result.getWidth() * textSizeSp / 400f));
        Typeface typeface = null;
        if (fontRes != 0) {
            try {
                typeface = ResourcesCompat.getFont(context, fontRes);
            } catch (Exception ignored) {
                typeface = null;
            }
        }
        if (typeface != null) {
            paint.setTypeface(typeface);
        }

        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        int stepX = Math.max(1, bounds.width() + bounds.height() * 2);
        int stepY = Math.max(1, bounds.height() * 4);
        int diagonal = (int) Math.hypot(result.getWidth(), result.getHeight());

        canvas.save();
        canvas.rotate(rotation, result.getWidth() / 2f, result.getHeight() / 2f);
        for (int y = -diagonal; y < diagonal * 2; y += stepY) {
            int offset = ((y / stepY) % 2 == 0) ? 0 : stepX / 2;
            for (int x = -diagonal + offset; x < diagonal * 2; x += stepX) {
                canvas.drawText(text, x, y, paint);
            }
        }
        canvas.restore();
        imageView.setImageBitmap(result);
    }
}
