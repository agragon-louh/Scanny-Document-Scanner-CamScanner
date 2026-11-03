package com.scanny.scanner.activity;

import androidx.core.content.ContextCompat;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.chrisbanes.photoview.PhotoView;
import com.scanny.scanner.R;
import com.scanny.scanner.db.DBHelper;
import com.scanny.scanner.main_utils.AppStorage;
import com.scanny.scanner.main_utils.BitmapUtils;
import com.scanny.scanner.main_utils.Constant;
import com.scanny.scanner.main_utils.DocumentFilter;
import com.scanny.scanner.models.DBModel;
import com.scanny.scanner.utils.NavigationUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class CurrentFilterActivity extends BaseActivity implements View.OnClickListener {
    public String current_docs_name;
    public DBHelper dataBaseHelper;
    public PhotoView iv_preview_filter;
    public Bitmap original;
    public String selected_group_name;
    // True when the saved page started a new folder, so Delete on the next screen removes the folder.
    public boolean created_new_group;
    public Bitmap tempBitmap;
    /** Downscaled copy of {@link #original} that filter previews run on (see DocumentFilter). */
    private Bitmap preview;
    /** The filter currently shown; applied to the full-size page when it is saved. */
    private int selectedFilter = DocumentFilter.ORIGINAL;
    /** Increments with every filter tap, so a slow earlier preview cannot overwrite a later one. */
    private int previewRequest;
    protected ImageView iv_back;
    protected ImageView iv_done;
    protected TextView iv_ocv_black;
    protected TextView iv_original;
    protected TextView iv_color;
    protected TextView iv_sharp_black;
    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constant.IdentifyActivity.equals("SavedDocumentActivity")) {
                Intent intent2 = new Intent(CurrentFilterActivity.this, SavedDocumentActivity.class);
                intent2.putExtra("scan_doc_group_name", selected_group_name);
                intent2.putExtra("current_doc_name", current_docs_name);
                intent2.putExtra("is_new_group", created_new_group);
                startActivity(intent2);
                Constant.IdentifyActivity = "";
                finish();
            } else if (Constant.IdentifyActivity.equals("IDCardPreviewActivity2")) {
                startActivity(new Intent(CurrentFilterActivity.this, IDCardPreviewActivity.class));
                Constant.IdentifyActivity = "";
                finish();
            }
        }
    };
    private ProgressDialog progressDialog;

    @Override
    public void onResume() {
        super.onResume();

        ContextCompat.registerReceiver(CurrentFilterActivity.this, broadcastReceiver, new IntentFilter(getPackageName() + ".SavedDocumentActivity"), ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(CurrentFilterActivity.this, broadcastReceiver, new IntentFilter(getPackageName() + ".IDCardPreviewActivity2"), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (broadcastReceiver != null) {
            try {
                unregisterReceiver(broadcastReceiver);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (restartIfStateLost()) {
            return;
        }
        blockScreenCapture();
        setContentView(R.layout.activity_current_filter);
        dataBaseHelper = new DBHelper(this);
        init();
    }

    private void init() {
        iv_back = (ImageView) findViewById(R.id.iv_back);
        iv_done = (ImageView) findViewById(R.id.iv_done);
        iv_preview_filter = (PhotoView) findViewById(R.id.iv_preview_filter);
        iv_original = (TextView) findViewById(R.id.iv_original);
        iv_color = (TextView) findViewById(R.id.iv_color);
        iv_sharp_black = (TextView) findViewById(R.id.iv_sharp_black);
        iv_ocv_black = (TextView) findViewById(R.id.iv_ocv_black);
        if (Constant.original != null) {
            original = Constant.original;
            preview = DocumentFilter.downscale(original, DocumentFilter.PREVIEW_SIDE);
            iv_preview_filter.setImageBitmap(original);
        }

    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.iv_back:
                onBackPressed();
                return;
            case R.id.iv_original:
                showFilter(DocumentFilter.ORIGINAL, iv_original);
                return;
            case R.id.iv_color:
                showFilter(DocumentFilter.MAGIC_COLOR, iv_color);
                return;
            case R.id.iv_sharp_black:
                showFilter(DocumentFilter.GRAY, iv_sharp_black);
                return;
            case R.id.iv_ocv_black:
                showFilter(DocumentFilter.BLACK_WHITE, iv_ocv_black);
                return;
            case R.id.iv_done:
                if (original == null) {
                    return;
                }
                if (!Constant.current_camera_view.equals("ID Card") || !Constant.card_type.equals("Single")) {
                    // insertGroup applies the selected filter to the full-size page while saving.
                    Constant.original = original;
                    new insertGroup(selectedFilter).execute(new Bitmap[]{original});
                    return;
                }
                showProgressDialog();
                final int filter = selectedFilter;
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap full;
                        try {
                            full = DocumentFilter.apply(filter, original);
                        } catch (OutOfMemoryError e) {
                            full = original;
                        }
                        final Bitmap result = full;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                dismissProgressDialog();
                                if (isFinishing() || isDestroyed()) {
                                    return;
                                }
                                Constant.singleSideBitmap = result;
                                Constant.IdentifyActivity = "IDCardPreviewActivity2";
                                NavigationUtils.jumpNextActivity(CurrentFilterActivity.this);
                            }
                        });
                    }
                });
                return;
            default:
                return;
        }
    }

    /**
     * Shows {@code filter} applied to the downscaled preview and highlights its button. The preview
     * is a fraction of the page's pixels, so this is near-instant; the full-size page is filtered
     * once, on save.
     */
    private void showFilter(final int filter, TextView selectedButton) {
        selectedFilter = filter;
        for (TextView button : new TextView[]{iv_original, iv_color, iv_sharp_black, iv_ocv_black}) {
            boolean selected = button == selectedButton;
            button.setBackgroundResource(selected ? R.drawable.filter_selection_bg : R.drawable.filter_bg);
            button.setTextColor(getResources().getColor(selected ? R.color.white : R.color.black));
        }
        final int request = ++previewRequest;
        if (filter == DocumentFilter.ORIGINAL || preview == null) {
            tempBitmap = original;
            iv_preview_filter.setImageBitmap(original);
            return;
        }
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap shown;
                try {
                    shown = DocumentFilter.apply(filter, preview);
                } catch (OutOfMemoryError e) {
                    shown = preview;
                }
                final Bitmap result = shown;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (request != previewRequest || isFinishing() || isDestroyed()) {
                            return; // a later tap superseded this one
                        }
                        tempBitmap = result;
                        iv_preview_filter.setImageBitmap(result);
                    }
                });
            }
        });
    }

    private void showProgressDialog() {
        progressDialog = new ProgressDialog(this);
        progressDialog.setIndeterminate(true);
        progressDialog.setMessage("Applying Filter...");
        progressDialog.setCancelable(false);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();
    }

    public void dismissProgressDialog() {
        if (progressDialog.isShowing()) {
            dismissSafely(progressDialog);
        }
    }

    private class insertGroup extends AsyncTask<Bitmap, Void, Bitmap> {
        boolean saveFailed;
        String current_doc_name;
        boolean created_new_group;
        String group_date;
        String group_name;
        ProgressDialog progressDialog;
        /** Filter to apply to the full-size page before it is written. */
        final int filter;
        /** The page as saved (filtered); becomes Constant.original for the next screen. */
        Bitmap saved;

        private insertGroup(int filter) {
            this.filter = filter;
        }

        @Override
        public void onPreExecute() {
            super.onPreExecute();
            progressDialog = new ProgressDialog(CurrentFilterActivity.this);
            progressDialog.setIndeterminate(true);
            progressDialog.setCancelable(false);
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.setMessage("Processing...");
            progressDialog.show();
        }

        @Override
        public Bitmap doInBackground(Bitmap... bitmapArr) {
            Bitmap bitmap = bitmapArr.length > 0 ? bitmapArr[0] : null;
            if (bitmap == null) {
                return null;
            }
            try {
                bitmap = DocumentFilter.apply(filter, bitmap);
            } catch (OutOfMemoryError e) {
                // Save the unfiltered page rather than nothing.
            }
            saved = bitmap;
            byte[] bytes = BitmapUtils.getBytes(bitmap);
            File file = BitmapUtils.writeImageFile(AppStorage.documentsDir(CurrentFilterActivity.this), bytes);
            if (file == null) {
                saveFailed = true;
                return null;
            }
            created_new_group = Constant.inputType.equals("Group");
            if (Constant.inputType.equals("Group")) {
                group_name = "Scanny" + Constant.getDateTime("_ddMMHHmmss");
                group_date = Constant.getDateTime("yyyy-MM-dd  hh:mm a");
                current_doc_name = Constant.newDocName();
                group_name = dataBaseHelper.createGroup(new DBModel(group_name, group_date, file.getPath(), Constant.current_tag));
                dataBaseHelper.addGroupDoc(group_name, file.getPath(), current_doc_name, "Insert text here...");
                return null;
            }
            group_name = GroupDocumentActivity.current_group;
            current_doc_name = Constant.newDocName();
            dataBaseHelper.addGroupDoc(group_name, file.getPath(), current_doc_name, "Insert text here...");
            return null;
        }

        @Override
        public void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            dismissSafely(progressDialog);
            if (saveFailed) {
                Toast.makeText(CurrentFilterActivity.this, R.string.page_save_failed, Toast.LENGTH_LONG).show();
                return;
            }
            if (saved != null) {
                Constant.original = saved;
            }
            selected_group_name = group_name;
            current_docs_name = this.current_doc_name;
            CurrentFilterActivity.this.created_new_group = this.created_new_group;
            Constant.IdentifyActivity = "SavedDocumentActivity";
            NavigationUtils.jumpNextActivity(CurrentFilterActivity.this);
        }
    }
}
