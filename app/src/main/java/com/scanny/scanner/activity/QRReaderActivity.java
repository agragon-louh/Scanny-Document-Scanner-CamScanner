package com.scanny.scanner.activity;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.Result;
import com.scanny.scanner.R;
import com.scanny.scanner.qrcode_reader.ZXingScannerView;


import java.util.ArrayList;
import java.util.Iterator;

public class QRReaderActivity extends BaseActivity implements ZXingScannerView.ResultHandler {
    private static final String TAG = "QRReaderActivity";

    public String barcode_result;
    public ViewGroup viewGroup;
    public ZXingScannerView zXingScannerView;
    private ToneGenerator toneGenerator;
    protected int camera_id = -1;
    private ArrayList<Integer> selected_indices;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_qrreader);
        init();
    }

    private void init() {
        viewGroup = (ViewGroup) findViewById(R.id.fl_camera);
        zXingScannerView = new ZXingScannerView(this);
        viewGroup.addView(zXingScannerView);
        findViewById(R.id.iv_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });
    }

    public void setupBarcodeFormats() {
        ArrayList arrayList = new ArrayList();

        if (selected_indices == null || selected_indices.isEmpty()) {
            selected_indices = new ArrayList<>();
            for (int i = 0; i < ZXingScannerView.ALL_FORMATS.size(); i++) {
                selected_indices.add(Integer.valueOf(i));
            }
        }
        Iterator<Integer> it = selected_indices.iterator();
        while (it.hasNext()) {
            arrayList.add(ZXingScannerView.ALL_FORMATS.get(it.next().intValue()));
        }

        if (zXingScannerView != null) {
            zXingScannerView.setFormats(arrayList);
        }
    }

    @Override
    public void handleResult(Result result) {
        barcode_result = result.getText();
        try {
            // One generator for the screen: a new one per scan was never released and eventually
            // failed to allocate (RuntimeException) after many scans.
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(5, 100);
            }
            toneGenerator.startTone(24);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot play scan tone", e);
        }
        final Dialog dialog = new Dialog(this, R.style.ThemeWithRoundShape);
        dialog.requestWindowFeature(1);
        dialog.setContentView(R.layout.barcode_result_dialog);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.getWindow().setLayout(-1, -2);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);

        TextView tv_search = dialog.findViewById(R.id.tv_search);
        TextView tv_result = dialog.findViewById(R.id.tv_result);

        if (barcode_result.startsWith("tel")) {
            tv_search.setText("Call");
        }
        tv_result.setText(barcode_result);
        // Copying is opt-in (long-press to select): closing the dialog used to put every scanned
        // value, Wi-Fi passwords and tokens included, on the shared clipboard.
        tv_result.setTextIsSelectable(true);

        ((TextView) dialog.findViewById(R.id.tv_share)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent("android.intent.action.SEND");
                intent.setType("text/*");
                intent.putExtra("android.intent.extra.SUBJECT", "");
                intent.putExtra("android.intent.extra.TEXT", barcode_result);
                startActivity(Intent.createChooser(intent, "Share text using"));
                dialog.dismiss();
            }
        });
        ((TextView) dialog.findViewById(R.id.tv_search)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent;
                if (barcode_result.startsWith("tel")) {
                    intent = new Intent(Intent.ACTION_DIAL);
                    intent.setData(Uri.parse(barcode_result));
                } else {
                    // Do not hard-code the Google app: it crashed with ActivityNotFoundException
                    // on devices without it. Any web-search handler may answer.
                    intent = new Intent(Intent.ACTION_WEB_SEARCH);
                    intent.putExtra(android.app.SearchManager.QUERY, barcode_result);
                }
                try {
                    startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(QRReaderActivity.this, "No app found to handle this action", Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
            }
        });
        ((ImageView) dialog.findViewById(R.id.iv_close)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // The camera is still open after a result; opening it again failed and leaked the
                // first instance (frozen preview, camera held until the process died).
                if (zXingScannerView != null) {
                    zXingScannerView.resumeCameraPreview(QRReaderActivity.this);
                }
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    @Override
    public void onResume() {
        if (zXingScannerView == null) {
            zXingScannerView = new ZXingScannerView(this);
            viewGroup.addView(zXingScannerView);
        }
        zXingScannerView.setResultHandler(this);
        zXingScannerView.startCamera(camera_id);
        setupBarcodeFormats();
        super.onResume();
    }

    @Override
    public void onPause() {
        // Release the camera while in the background so other apps can use it.
        if (zXingScannerView != null) {
            zXingScannerView.stopCamera();
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (zXingScannerView != null) {
            zXingScannerView.stopCamera();
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        super.onDestroy();
    }
}
