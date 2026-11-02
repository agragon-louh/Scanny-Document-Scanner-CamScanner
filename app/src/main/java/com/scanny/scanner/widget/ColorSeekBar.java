package com.scanny.scanner.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.scanny.scanner.R;

/**
 * Horizontal gradient color slider. Local replacement for
 * {@code com.divyanshu.colorseekbar:colorseekbar}, which was only published on JCenter and
 * can no longer be downloaded. Same public API as the subset the app used.
 */
public class ColorSeekBar extends View {

    public interface OnColorChangeListener {
        void onColorChangeListener(int color);
    }

    private int[] colorSeeds = new int[]{Color.BLACK, Color.RED, Color.YELLOW, Color.GREEN,
            Color.CYAN, Color.BLUE, Color.MAGENTA, Color.WHITE};
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private float position = 0f; // 0..1
    private OnColorChangeListener listener;
    private final float barHeight;
    private final float thumbRadius;

    public ColorSeekBar(Context context) {
        this(context, null);
    }

    public ColorSeekBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ColorSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        barHeight = 6 * density;
        thumbRadius = 9 * density;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ColorSeekBar);
            int seedsRes = a.getResourceId(R.styleable.ColorSeekBar_csb_colorSeeds, 0);
            a.recycle();
            if (seedsRes != 0) {
                TypedArray seeds = getResources().obtainTypedArray(seedsRes);
                int[] colors = new int[seeds.length()];
                for (int i = 0; i < colors.length; i++) {
                    colors[i] = seeds.getColor(i, Color.BLACK);
                }
                seeds.recycle();
                if (colors.length >= 2) {
                    colorSeeds = colors;
                }
            }
        }
        thumbStroke.setStyle(Paint.Style.STROKE);
        thumbStroke.setStrokeWidth(2 * density);
        thumbStroke.setColor(Color.WHITE);
    }

    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.listener = listener;
    }

    public int getColor() {
        return colorAt(position);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) (thumbRadius * 2 + getPaddingTop() + getPaddingBottom());
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float left = getPaddingLeft() + thumbRadius;
        float right = w - getPaddingRight() - thumbRadius;
        float cy = getPaddingTop() + (h - getPaddingTop() - getPaddingBottom()) / 2f;
        barRect.set(left, cy - barHeight / 2f, Math.max(left + 1, right), cy + barHeight / 2f);
        barPaint.setShader(new LinearGradient(barRect.left, 0, barRect.right, 0, colorSeeds, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(barRect, barHeight / 2f, barHeight / 2f, barPaint);
        float cx = barRect.left + position * barRect.width();
        thumbPaint.setColor(getColor());
        canvas.drawCircle(cx, barRect.centerY(), thumbRadius, thumbPaint);
        canvas.drawCircle(cx, barRect.centerY(), thumbRadius, thumbStroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float width = barRect.width();
                position = width <= 0 ? 0 : Math.max(0f, Math.min(1f, (event.getX() - barRect.left) / width));
                invalidate();
                if (listener != null) {
                    listener.onColorChangeListener(getColor());
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    performClick();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int colorAt(float fraction) {
        if (fraction <= 0f) {
            return colorSeeds[0];
        }
        if (fraction >= 1f) {
            return colorSeeds[colorSeeds.length - 1];
        }
        float scaled = fraction * (colorSeeds.length - 1);
        int i = (int) scaled;
        float t = scaled - i;
        int c0 = colorSeeds[i];
        int c1 = colorSeeds[i + 1];
        return Color.argb(
                Math.round(Color.alpha(c0) + (Color.alpha(c1) - Color.alpha(c0)) * t),
                Math.round(Color.red(c0) + (Color.red(c1) - Color.red(c0)) * t),
                Math.round(Color.green(c0) + (Color.green(c1) - Color.green(c0)) * t),
                Math.round(Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * t));
    }
}
