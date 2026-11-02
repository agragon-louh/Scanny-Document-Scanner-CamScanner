package com.scanny.scanner.activity;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.scanny.scanner.R;
import com.scanny.scanner.utils.NavigationUtils;

import java.io.IOException;

/**
 * Shows a PDF with the platform {@link PdfRenderer}.
 *
 * <p>Previously used {@code com.github.barteksc:android-pdf-viewer} (JCenter-only, no longer
 * downloadable, unmaintained, and bundling native pdfium libraries that are not 16 KB
 * page-size aligned). The old code also logged the document's metadata to logcat.</p>
 */
public class PDFViewerActivity extends BaseActivity {
    protected Uri pdf_uri;
    protected String title;
    protected TextView tv_page;
    protected TextView tv_title;
    private RecyclerView pdfView;
    private ParcelFileDescriptor fileDescriptor;
    private PdfRenderer renderer;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_pdfviewer);
        init();
    }

    private void init() {

        tv_title = (TextView) findViewById(R.id.tv_title);
        tv_page = (TextView) findViewById(R.id.tv_page);
        pdfView = (RecyclerView) findViewById(R.id.pdfView);
        pdfView.setBackgroundColor(getResources().getColor(R.color.bg_color));
        title = getIntent().getStringExtra("title");
        tv_title.setText(title);
        findViewById(R.id.iv_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });
        String path = getIntent().getStringExtra("pdf_path");
        if (path == null) {
            finish();
            return;
        }
        pdf_uri = Uri.parse(path);
        loadPDF(pdf_uri);
    }

    private void loadPDF(Uri uri) {
        try {
            fileDescriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (fileDescriptor == null) {
                throw new IOException("Cannot open " + uri);
            }
            renderer = new PdfRenderer(fileDescriptor);
        } catch (IOException | SecurityException e) {
            // SecurityException: password-protected PDFs cannot be rendered by PdfRenderer.
            Toast.makeText(this, "Unable to open this PDF", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        pdfView.setLayoutManager(layoutManager);
        pdfView.setAdapter(new PageAdapter());
        updatePageLabel(0);
        pdfView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                int position = layoutManager.findFirstVisibleItemPosition();
                if (position != RecyclerView.NO_POSITION) {
                    updatePageLabel(position);
                }
            }
        });
    }

    private void updatePageLabel(int page) {
        if (renderer != null) {
            tv_page.setText(String.format("%s / %s", page + 1, renderer.getPageCount()));
        }
    }

    @Override
    protected void onDestroy() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (IOException ignored) {
            }
            fileDescriptor = null;
        }
        super.onDestroy();
    }

    private class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            final ImageView imageView;

            Holder(ImageView imageView) {
                super(imageView);
                this.imageView = imageView;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView imageView = new ImageView(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int spacing = (int) (6 * getResources().getDisplayMetrics().density);
            lp.setMargins(0, spacing, 0, spacing);
            imageView.setLayoutParams(lp);
            imageView.setAdjustViewBounds(true);
            imageView.setBackgroundColor(Color.WHITE);
            return new Holder(imageView);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            if (renderer == null) {
                return;
            }
            int targetWidth = pdfView.getWidth() > 0 ? pdfView.getWidth() : getResources().getDisplayMetrics().widthPixels;
            synchronized (PDFViewerActivity.this) {
                PdfRenderer.Page page = renderer.openPage(position);
                try {
                    int width = Math.max(1, targetWidth);
                    int height = Math.max(1, (int) ((float) page.getHeight() * width / page.getWidth()));
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    holder.imageView.setImageBitmap(bitmap);
                } finally {
                    page.close();
                }
            }
        }

        @Override
        public int getItemCount() {
            return renderer == null ? 0 : renderer.getPageCount();
        }
    }
}
