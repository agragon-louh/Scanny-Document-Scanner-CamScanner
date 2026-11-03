package com.scanny.scanner.adapter;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.scanny.scanner.R;
import com.scanny.scanner.activity.DocumentEditorActivity;
import com.scanny.scanner.document_view.ColorFilter;
import com.scanny.scanner.main_utils.Constant;
import com.scanny.scanner.main_utils.DocumentFilter;


public class ColorFilterAdapter extends RecyclerView.Adapter<ColorFilterAdapter.ViewHolder> {

    public Activity activity;
    private ColorFilter colorFilter = new ColorFilter();
    private String[] colorFilterName;
    /** Longest side of the strip thumbnails (the views are about 45dp; 256 px is ample). */
    private static final int THUMB_SIDE = 256;
    /** Small copy of the page the thumbnails are rendered from, and the rendered thumbnails. */
    private Bitmap thumbSource;
    private Bitmap thumbSourceOf;
    private Bitmap[] thumbs;

    public ColorFilterAdapter(Activity activity2, String[] strArr) {
        this.activity = activity2;
        this.colorFilterName = strArr;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        return new ViewHolder(LayoutInflater.from(activity).inflate(R.layout.color_filter_list_item, viewGroup, false));
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, final int i) {
        viewHolder.tv_filter_name.setText(colorFilterName[i]);
        viewHolder.iv_filter_view.setImageBitmap(thumbnail(i));
        viewHolder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Constant.filterPosition = i;
                notifyDataSetChanged();
                ((DocumentEditorActivity) activity).onColorFilterSeleced(activity, i);
            }
        });
        if (Constant.filterPosition == i) {
            viewHolder.ly_img.setBackground(activity.getResources().getDrawable(R.drawable.img_border_selected));
            viewHolder.tv_filter_name.setTextColor(activity.getResources().getColor(R.color.black));
            return;
        }
        viewHolder.ly_img.setBackground(activity.getResources().getDrawable(R.drawable.img_border_unselected));
        viewHolder.tv_filter_name.setTextColor(activity.getResources().getColor(R.color.white));
    }

    /**
     * Filter {@code i} rendered on a small copy of the page, cached. The thumbnails used to be
     * rendered from the full-size page on every bind (12 full-size bitmaps per refresh).
     */
    private Bitmap thumbnail(int i) {
        Bitmap page = Constant.original;
        if (page == null) {
            return null;
        }
        if (thumbSourceOf != page || thumbs == null) {
            thumbSourceOf = page;
            thumbSource = DocumentFilter.downscale(page, THUMB_SIDE);
            thumbs = new Bitmap[colorFilterName.length];
        }
        if (i < 0 || i >= thumbs.length) {
            return thumbSource;
        }
        if (thumbs[i] == null) {
            thumbs[i] = render(i, thumbSource);
        }
        return thumbs[i];
    }

    private Bitmap render(int i, Bitmap src) {
        switch (i) {
            case 1:
                return colorFilter.filter1(activity, src);
            case 2:
                return colorFilter.filter2(activity, src);
            case 3:
                return colorFilter.filter3(activity, src);
            case 4:
                return colorFilter.filter4(activity, src);
            case 5:
                return colorFilter.filter5(activity, src);
            case 6:
                return colorFilter.filter6(activity, src);
            case 7:
                return colorFilter.filter7(activity, src);
            case 8:
                return colorFilter.filter8(activity, src);
            case 9:
                return colorFilter.filter9(activity, src);
            case 10:
                return colorFilter.filter10(activity, src);
            case 11:
                return colorFilter.filter11(activity, src);
            default:
                return src;
        }
    }

    @Override
    public int getItemCount() {
        return colorFilterName.length;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ImageView iv_filter_view;
        LinearLayout ly_img;
        TextView tv_filter_name;

        public ViewHolder(View view) {
            super(view);
            ly_img = (LinearLayout) view.findViewById(R.id.ly_img);
            iv_filter_view = (ImageView) view.findViewById(R.id.iv_filter_view);
            tv_filter_name = (TextView) view.findViewById(R.id.tv_filter_name);
        }
    }
}
