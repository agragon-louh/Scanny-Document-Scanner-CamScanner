package com.scanny.scanner.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.scanny.scanner.R;

/**
 * Free-hand signature pad. Local replacement for {@code com.kyanogen.signatureview}, which
 * was only published on JCenter and can no longer be downloaded. Implements the API subset
 * used by the app: setPenSize, setPenColor, clearCanvas, isBitmapEmpty, getSignatureBitmap.
 */
public class SignatureView extends View {

    private Bitmap bitmap;
    private Canvas bitmapCanvas;
    private final Paint penPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Path path = new Path();
    private int backgroundColor = Color.TRANSPARENT;
    private boolean enableSignature = true;
    private boolean empty = true;
    private float lastX;
    private float lastY;

    public SignatureView(Context context) {
        this(context, null);
    }

    public SignatureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SignatureView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int penColor = Color.BLACK;
        float penSize = 5 * getResources().getDisplayMetrics().density;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SignatureView);
            penColor = a.getColor(R.styleable.SignatureView_sv_penColor, penColor);
            penSize = a.getDimension(R.styleable.SignatureView_sv_penSize, penSize);
            backgroundColor = a.getColor(R.styleable.SignatureView_sv_backgroundColor, backgroundColor);
            enableSignature = a.getBoolean(R.styleable.SignatureView_sv_enableSignature, true);
            a.recycle();
        }
        penPaint.setColor(penColor);
        penPaint.setStrokeWidth(penSize);
        penPaint.setStyle(Paint.Style.STROKE);
        penPaint.setStrokeJoin(Paint.Join.ROUND);
        penPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setPenSize(float size) {
        penPaint.setStrokeWidth(size);
    }

    public void setPenColor(int color) {
        penPaint.setColor(color);
    }

    public void setEnableSignature(boolean enable) {
        this.enableSignature = enable;
    }

    public void clearCanvas() {
        path.reset();
        if (bitmap != null) {
            bitmap.eraseColor(backgroundColor);
        }
        empty = true;
        invalidate();
    }

    public boolean isBitmapEmpty() {
        return empty;
    }

    public Bitmap getSignatureBitmap() {
        if (bitmap == null) {
            return null;
        }
        // Flush the stroke that is still in progress.
        Bitmap result = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (!path.isEmpty()) {
            new Canvas(result).drawPath(path, penPaint);
        }
        return result;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }
        Bitmap newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        newBitmap.eraseColor(backgroundColor);
        Canvas newCanvas = new Canvas(newBitmap);
        if (bitmap != null) {
            newCanvas.drawBitmap(bitmap, 0, 0, null);
            bitmap.recycle();
        }
        bitmap = newBitmap;
        bitmapCanvas = newCanvas;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0, 0, null);
        }
        canvas.drawPath(path, penPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!enableSignature) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                path.reset();
                path.moveTo(x, y);
                lastX = x;
                lastY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f);
                lastX = x;
                lastY = y;
                empty = false;
                break;
            case MotionEvent.ACTION_UP:
                path.lineTo(x, y);
                if (bitmapCanvas != null) {
                    bitmapCanvas.drawPath(path, penPaint);
                }
                path.reset();
                empty = false;
                performClick();
                break;
            default:
                return false;
        }
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
