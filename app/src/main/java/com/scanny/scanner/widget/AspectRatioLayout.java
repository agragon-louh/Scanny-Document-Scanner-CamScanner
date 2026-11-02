package com.scanny.scanner.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * FrameLayout that keeps a fixed width:height ratio, fitting inside the space it is given.
 * Local replacement for {@code com.takwolf.android:aspectratio}, which was only published on
 * JCenter and can no longer be downloaded.
 */
public class AspectRatioLayout extends FrameLayout {

    private float ratioWidth = 0f;
    private float ratioHeight = 0f;

    public AspectRatioLayout(Context context) {
        super(context);
    }

    public AspectRatioLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatioLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setAspectRatio(float width, float height) {
        this.ratioWidth = width;
        this.ratioHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (ratioWidth <= 0 || ratioHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int maxWidth = View.MeasureSpec.getSize(widthMeasureSpec);
        int maxHeight = View.MeasureSpec.getSize(heightMeasureSpec);
        boolean heightBounded = View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED;
        int width = maxWidth;
        int height = Math.round(width * ratioHeight / ratioWidth);
        if (heightBounded && height > maxHeight) {
            height = maxHeight;
            width = Math.round(height * ratioWidth / ratioHeight);
        }
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    }
}
