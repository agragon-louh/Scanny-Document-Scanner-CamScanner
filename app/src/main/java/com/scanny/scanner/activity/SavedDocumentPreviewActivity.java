package com.scanny.scanner.activity;

import androidx.core.content.ContextCompat;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.itextpdf.text.pdf.PdfObject;
import com.scanny.scanner.R;
import com.scanny.scanner.adapter.PreviewPagerAdapter;
import com.scanny.scanner.adapter.SavedToolsAdapter;
import com.scanny.scanner.db.DBHelper;
import com.scanny.scanner.document_view.ViewPagerFixed;
import com.scanny.scanner.main_utils.PdfProtector;
import com.scanny.scanner.main_utils.AppStorage;
import com.scanny.scanner.main_utils.BitmapUtils;
import com.scanny.scanner.main_utils.Constant;
import com.scanny.scanner.models.DBModel;
import com.scanny.scanner.models.SavedToolType;
import com.scanny.scanner.utils.NavigationUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public class SavedDocumentPreviewActivity extends BaseActivity implements View.OnClickListener, SavedToolsAdapter.OnSavedToolSelected {
    private static final String TAG = "SavedDocumentPreviewAct";
    public static ArrayList<DBModel> currentDocList = new ArrayList<>();
    public static SavedDocumentPreviewActivity savedDocumentPreviewActivity;
    public String current_doc_name;
    public DBHelper dataBaseHelper;
    public String edit_doc_grp_name;
    public int position;
    public PreviewPagerAdapter previewPagerAdapter;
    public Uri shareUri;
    public TextView tv_page;
    public ViewPagerFixed viewPager;
    protected ImageView iv_back;
    protected ImageView iv_home;
    protected String noteTxt;
    protected Uri pdfUri;
    protected SavedToolsAdapter savedToolsAdapter;
    protected String selected_group_name;
    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constant.IdentifyActivity.equals("NoteActivity_Preview")) {
                Intent intent2 = new Intent(SavedDocumentPreviewActivity.this, NoteActivity.class);
                intent2.putExtra("group_name", edit_doc_grp_name);
                intent2.putExtra("current_doc_name", current_doc_name);
                intent2.putExtra("note", noteTxt);
                startActivity(intent2);
                Constant.IdentifyActivity = "";
            } else if (Constant.IdentifyActivity.equals("ImageToTextActivity_Preview")) {
                Intent intent3 = new Intent(SavedDocumentPreviewActivity.this, ImageToTextActivity.class);
                intent3.putExtra("group_name", edit_doc_grp_name);
                startActivity(intent3);
                Constant.IdentifyActivity = "";
            } else if (Constant.IdentifyActivity.equals("PDFViewerActivity_Preview")) {
                Intent intent4 = new Intent(SavedDocumentPreviewActivity.this, PDFViewerActivity.class);
                intent4.putExtra("title", selected_group_name + ".pdf");
                intent4.putExtra("pdf_path", pdfUri.toString());
                startActivity(intent4);
                Constant.IdentifyActivity = "";
            } else if (Constant.IdentifyActivity.equals("DocumentEditorActivity_SavedPreview")) {
                Intent intent5 = new Intent(SavedDocumentPreviewActivity.this, DocumentEditorActivity.class);
                intent5.putExtra("TAG", "ScannerActivity");
                intent5.putExtra("position", position);
                intent5.putExtra("edited_doc_grp_name", edit_doc_grp_name);
                intent5.putExtra("current_doc_name", current_doc_name);
                // Editing a page of an existing folder.
                intent5.putExtra("is_new_group", false);
                startActivityForResult(intent5, 21);
                Constant.IdentifyActivity = "";
            }
        }
    };
    private RecyclerView rv_saved_tools;

    @Override
    public void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(SavedDocumentPreviewActivity.this, broadcastReceiver, new IntentFilter(getPackageName() + ".DocumentEditorActivity_SavedPreview"), ContextCompat.RECEIVER_NOT_EXPORTED);

        ContextCompat.registerReceiver(SavedDocumentPreviewActivity.this, broadcastReceiver, new IntentFilter(getPackageName() + ".PDFViewerActivity_Preview"), ContextCompat.RECEIVER_NOT_EXPORTED);

        ContextCompat.registerReceiver(SavedDocumentPreviewActivity.this, broadcastReceiver, new IntentFilter(getPackageName() + ".NoteActivity_Preview"), ContextCompat.RECEIVER_NOT_EXPORTED);

        ContextCompat.registerReceiver(SavedDocumentPreviewActivity.this, broadcastReceiver, new IntentFilter(getPackageName() + ".ImageToTextActivity_Preview"), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        if (shareUri != null) {
            getContentResolver().delete(shareUri, (String) null, (String[]) null);
        }
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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // A rename changes the folder name after this activity was started; keep the new name.
        outState.putString("edit_doc_group_name", edit_doc_grp_name);
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (restartIfStateLost()) {
            return;
        }
        blockScreenCapture();
        setContentView(R.layout.activity_saved_document_preview);
        savedDocumentPreviewActivity = this;
        dataBaseHelper = new DBHelper(this);

        init();
        bindView(bundle);
    }

    private void init() {

        iv_back = (ImageView) findViewById(R.id.iv_back);
        iv_home = (ImageView) findViewById(R.id.iv_home);
        viewPager = (ViewPagerFixed) findViewById(R.id.viewPager);
        tv_page = (TextView) findViewById(R.id.tv_page);
        rv_saved_tools = (RecyclerView) findViewById(R.id.rv_saved_tools);
    }

    private void bindView(Bundle bundle) {
        edit_doc_grp_name = bundle != null && bundle.getString("edit_doc_group_name") != null
                ? bundle.getString("edit_doc_group_name") : getIntent().getStringExtra("edit_doc_group_name");
        current_doc_name = getIntent().getStringExtra("current_doc_name");
        position = getIntent().getIntExtra("position", -1);
//        currentDocList = GroupDocumentActivity.currentGroupList;

        new getAllDoc().execute();

    }

    private void setSavedToolsAdapter() {
        rv_saved_tools.setHasFixedSize(true);
        rv_saved_tools.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        savedToolsAdapter = new SavedToolsAdapter(this);
        rv_saved_tools.setAdapter(savedToolsAdapter);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.iv_back) {
            onBackPressed();
        } else if (id == R.id.iv_home) {
            if (GroupDocumentActivity.groupDocumentActivity != null) {
                GroupDocumentActivity.groupDocumentActivity.finish();
            }
            finish();
        }
    }

    @Override
    public void onSavedToolSelected(SavedToolType savedToolType) {
        switch (savedToolType) {
            case EDIT:
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Constant.original = BitmapFactory.decodeStream(new FileInputStream(currentDocList.get(position).getGroup_doc_img()), (Rect) null, options);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Constant.IdentifyActivity = "DocumentEditorActivity_SavedPreview";
                NavigationUtils.jumpNextActivity(SavedDocumentPreviewActivity.this);
                return;
            case OPENPDF:
                try {
                    BitmapFactory.Options options2 = new BitmapFactory.Options();
                    options2.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Constant.original = BitmapFactory.decodeStream(new FileInputStream(currentDocList.get(position).getGroup_doc_img()), (Rect) null, options2);
                } catch (IOException e2) {
                    e2.printStackTrace();
                }
                ArrayList arrayList = new ArrayList();
                arrayList.add(Constant.original);
                new openPDF(arrayList, edit_doc_grp_name).execute(new String[0]);
                return;
            case NAME:
                updateGroupName(edit_doc_grp_name);
                return;
            case ROTATE:
                try {
                    BitmapFactory.Options options3 = new BitmapFactory.Options();
                    options3.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Constant.original = BitmapFactory.decodeStream(new FileInputStream(currentDocList.get(position).getGroup_doc_img()), (Rect) null, options3);
                } catch (IOException e3) {
                    e3.printStackTrace();
                }
                new rotateDoc().execute(new String[0]);
                return;
            case NOTE:
                noteTxt = dataBaseHelper.getSingleNote(edit_doc_grp_name, current_doc_name);
                Constant.IdentifyActivity = "NoteActivity_Preview";
                NavigationUtils.jumpNextActivity(SavedDocumentPreviewActivity.this);
                return;
            case ImageToText:
                try {
                    BitmapFactory.Options options4 = new BitmapFactory.Options();
                    options4.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Constant.original = BitmapFactory.decodeStream(new FileInputStream(currentDocList.get(position).getGroup_doc_img()), (Rect) null, options4);
                } catch (IOException e4) {
                    e4.printStackTrace();
                }
                Constant.IdentifyActivity = "ImageToTextActivity_Preview";
                NavigationUtils.jumpNextActivity(SavedDocumentPreviewActivity.this);
                return;
            case SHARE:
                try {
                    BitmapFactory.Options options5 = new BitmapFactory.Options();
                    options5.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Constant.original = BitmapFactory.decodeStream(new FileInputStream(currentDocList.get(position).getGroup_doc_img()), (Rect) null, options5);
                } catch (IOException e5) {
                    e5.printStackTrace();
                }
                shareGroupDoc();
                return;
            case DELETE:
                final Dialog dialog = new Dialog(this, R.style.ThemeWithRoundShape);
                dialog.requestWindowFeature(1);
                dialog.setContentView(R.layout.delete_document_dialog);
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
                dialog.getWindow().setLayout(-1, -2);
                dialog.setCanceledOnTouchOutside(false);
                dialog.setCancelable(false);
                ((TextView) dialog.findViewById(R.id.tv_delete)).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (currentDocList.size() == 1) {
                            dataBaseHelper.deleteGroup(edit_doc_grp_name);
                            if (GroupDocumentActivity.groupDocumentActivity != null) {
                                GroupDocumentActivity.groupDocumentActivity.finish();
                            }
                            dialog.dismiss();
                            finish();
                            return;
                        }
                        dataBaseHelper.deleteSingleDoc(edit_doc_grp_name, current_doc_name);
                        if (position == 0) {
                            dataBaseHelper.updateGroupFirstImg(edit_doc_grp_name, currentDocList.get(position + 1).getGroup_doc_img());
                            viewPager.setAdapter((PagerAdapter) null);
                            currentDocList.remove(position);
                            previewPagerAdapter.notifyDataSetChanged();
                            previewPagerAdapter = new PreviewPagerAdapter(savedDocumentPreviewActivity, currentDocList);
                            viewPager.setAdapter(previewPagerAdapter);
                            viewPager.setCurrentItem(position);
                            position = viewPager.getCurrentItem();
                            current_doc_name = currentDocList.get(position).getGroup_doc_name();
                        } else {
                            viewPager.setAdapter((PagerAdapter) null);
                            currentDocList.remove(position);
                            previewPagerAdapter.notifyDataSetChanged();
                            previewPagerAdapter = new PreviewPagerAdapter(savedDocumentPreviewActivity, currentDocList);
                            viewPager.setAdapter(previewPagerAdapter);
                            viewPager.setCurrentItem(position - 1);

                            position = viewPager.getCurrentItem();
                            current_doc_name = currentDocList.get(position).getGroup_doc_name();
                        }
                        tv_page.setText(String.format("%s / %s", new Object[]{Integer.valueOf(position + 1), Integer.valueOf(currentDocList.size())}));
                        dialog.dismiss();
                    }
                });
                ((ImageView) dialog.findViewById(R.id.iv_close)).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        dialog.dismiss();
                    }
                });
                dialog.show();
                return;
            default:
                return;
        }
    }

    private void updateGroupName(final String str) {
        final Dialog dialog = new Dialog(this, R.style.ThemeWithRoundShape);
        dialog.requestWindowFeature(1);
        dialog.setContentView(R.layout.update_group_name);
        dialog.getWindow().setLayout(-1, -2);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        final EditText editText = (EditText) dialog.findViewById(R.id.et_group_name);
        editText.setText(str);
        editText.setSelection(editText.length());
        ((TextView) dialog.findViewById(R.id.tv_done)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (editText.getText().toString().equals("") || Character.isDigit(editText.getText().toString().charAt(0))) {
                    Toast.makeText(SavedDocumentPreviewActivity.this, "Please Enter Valid Document Name!", Toast.LENGTH_SHORT).show();
                    return;
                }
                String newName = editText.getText().toString().trim();
                if (!dataBaseHelper.updateGroupName(SavedDocumentPreviewActivity.this, str, newName)) {
                    return;
                }
                dialog.dismiss();
                edit_doc_grp_name = newName;
                getIntent().putExtra("edit_doc_group_name", newName);
                GroupDocumentActivity.current_group = newName;
            }
        });
        ((TextView) dialog.findViewById(R.id.tv_cancel)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private void shareGroupDoc() {
        final Dialog dialog = new Dialog(this, R.style.ThemeWithRoundShape);
        dialog.requestWindowFeature(1);
        dialog.setContentView(R.layout.share_group_doc);
        dialog.getWindow().setLayout(-1, -2);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        ((RelativeLayout) dialog.findViewById(R.id.rl_share_pdf)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ArrayList arrayList = new ArrayList();
                arrayList.add(Constant.original);
                new shareAsPDF(arrayList, PdfObject.TEXT_PDFDOCENCODING, "").execute(new String[0]);
                dialog.dismiss();
            }
        });
        ((RelativeLayout) dialog.findViewById(R.id.rl_share_img)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    shareUri = BitmapUtils.getUri(savedDocumentPreviewActivity, Constant.original);
                } catch (IOException e) {
                    Toast.makeText(SavedDocumentPreviewActivity.this, "Could not share the image", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    return;
                }
                Intent intent = new Intent();
                intent.setAction("android.intent.action.SEND");
                intent.setType("image/*");
                intent.putExtra("android.intent.extra.STREAM", shareUri);
                intent.putExtra("android.intent.extra.TEXT", "");
                intent.putExtra("android.intent.extra.SUBJECT", "");
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, (CharSequence) null));
                dialog.dismiss();
            }
        });
        ((RelativeLayout) dialog.findViewById(R.id.rl_share_pdf_pswrd)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sharePDFWithPswrd();
                dialog.dismiss();
            }
        });
        ((ImageView) dialog.findViewById(R.id.iv_close)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    public void sharePDFWithPswrd() {
        final Dialog dialog = new Dialog(this, R.style.ThemeWithRoundShape);
        dialog.requestWindowFeature(1);
        dialog.setContentView(R.layout.set_pdf_pswrd);
        dialog.getWindow().setLayout(-1, -2);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        final EditText et_enter_pswrd = (EditText) dialog.findViewById(R.id.et_enter_pswrd);
        final ImageView iv_enter_pswrd_show = (ImageView) dialog.findViewById(R.id.iv_enter_pswrd_show);
        final ImageView iv_enter_pswrd_hide = (ImageView) dialog.findViewById(R.id.iv_enter_pswrd_hide);
        final EditText et_confirm_pswrd = (EditText) dialog.findViewById(R.id.et_confirm_pswrd);
        final ImageView iv_confirm_pswrd_show = (ImageView) dialog.findViewById(R.id.iv_confirm_pswrd_show);
        final ImageView iv_confirm_pswrd_hide = (ImageView) dialog.findViewById(R.id.iv_confirm_pswrd_hide);

        Typeface typeface = Typeface.createFromAsset(getAssets(), "font/inter_medium.ttf");
        et_enter_pswrd.setTypeface(typeface);
        et_confirm_pswrd.setTypeface(typeface);
        et_enter_pswrd.setInputType(129);
        et_confirm_pswrd.setInputType(129);

        iv_enter_pswrd_show.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                iv_enter_pswrd_show.setVisibility(View.GONE);
                iv_enter_pswrd_hide.setVisibility(View.VISIBLE);
                et_enter_pswrd.setTransformationMethod(new HideReturnsTransformationMethod());
                et_enter_pswrd.setSelection(et_enter_pswrd.getText().length());
            }
        });
        iv_enter_pswrd_hide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                iv_enter_pswrd_show.setVisibility(View.VISIBLE);
                iv_enter_pswrd_hide.setVisibility(View.GONE);
                et_enter_pswrd.setTransformationMethod(new PasswordTransformationMethod());
                et_enter_pswrd.setSelection(et_enter_pswrd.getText().length());
            }
        });
        iv_confirm_pswrd_show.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                iv_confirm_pswrd_show.setVisibility(View.GONE);
                iv_confirm_pswrd_hide.setVisibility(View.VISIBLE);
                et_confirm_pswrd.setTransformationMethod(new HideReturnsTransformationMethod());
                et_confirm_pswrd.setSelection(et_confirm_pswrd.getText().length());
            }
        });
        iv_confirm_pswrd_hide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                iv_confirm_pswrd_show.setVisibility(View.VISIBLE);
                iv_confirm_pswrd_hide.setVisibility(View.GONE);
                et_confirm_pswrd.setTransformationMethod(new PasswordTransformationMethod());
                et_confirm_pswrd.setSelection(et_confirm_pswrd.getText().length());
            }
        });
        ((TextView) dialog.findViewById(R.id.tv_done)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (et_enter_pswrd.getText().toString().equals("") || et_confirm_pswrd.getText().toString().equals("")) {
                    Toast.makeText(getApplicationContext(), "Please Enter Password", Toast.LENGTH_SHORT).show();
                } else if (!PdfProtector.isStrongEnough(et_enter_pswrd.getText().toString())) {
                    Toast.makeText(getApplicationContext(), "Password must be at least " + PdfProtector.MIN_PASSWORD_LENGTH + " characters", Toast.LENGTH_LONG).show();
                } else if (!et_enter_pswrd.getText().toString().equals(et_confirm_pswrd.getText().toString())) {
                    Toast.makeText(getApplicationContext(), "Your password & Confirm password do not match.", Toast.LENGTH_LONG).show();
                } else {
                    ArrayList arrayList = new ArrayList();
                    arrayList.add(Constant.original);
                    new shareAsPDF(arrayList, "PDF With Password", et_enter_pswrd.getText().toString()).execute(new String[0]);
                    dialog.dismiss();
                }
            }
        });
        ((TextView) dialog.findViewById(R.id.tv_cancel)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == -1 && requestCode == 21) {
            new updateEditedImg().execute(new String[0]);
        }
    }

    public class getAllDoc extends AsyncTask<String, Void, String> {
        ProgressDialog progressDialog;

        public getAllDoc() {
        }

        @Override
        public void onPreExecute() {
            super.onPreExecute();
            progressDialog = new ProgressDialog(SavedDocumentPreviewActivity.this);
            progressDialog.setIndeterminate(true);
            progressDialog.setMessage("Please Wait...");
            progressDialog.setCancelable(false);
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.show();
        }

        @Override
        public String doInBackground(String... strArr) {
            // A new list: the visible pager adapter must not see this one change off the UI thread.
            currentDocList = dataBaseHelper.getGroupDocs(edit_doc_grp_name);
            return null;
        }

        @Override
        public void onPostExecute(String str) {
            super.onPostExecute(str);
            if (currentDocList.size() > 0) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        previewPagerAdapter = new PreviewPagerAdapter(SavedDocumentPreviewActivity.this, currentDocList);
                        viewPager.setAdapter(previewPagerAdapter);
                        viewPager.setCurrentItem(position);
                        tv_page.setText(String.format("%s / %s", new Object[]{Integer.valueOf(position + 1), Integer.valueOf(currentDocList.size())}));
                        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                            @Override
                            public void onPageScrollStateChanged(int i) {
                            }

                            @Override
                            public void onPageScrolled(int i, float f, int i2) {
                            }

                            @Override
                            public void onPageSelected(int i) {
                                Log.e(TAG, "onPageSelected: " + i);
                                position = i;
                                current_doc_name = currentDocList.get(position).getGroup_doc_name();
                                tv_page.setText(String.format("%s / %s", new Object[]{Integer.valueOf(position + 1), Integer.valueOf(currentDocList.size())}));
                            }
                        });
                        setSavedToolsAdapter();
                    }
                });

            } else {
//                Toast.makeText(getApplicationContext(), "Something Went Wrong...", Toast.LENGTH_SHORT).show();
            }
            dismissSafely(progressDialog);
        }
    }

    public class openPDF extends AsyncTask<String, Void, String> {
        File pdfFile;
        ArrayList<Bitmap> current_bitmap;
        String group_name;
        ProgressDialog progressDialog;

        public openPDF(ArrayList<Bitmap> arrayList, String str) {
            current_bitmap = arrayList;
            group_name = str;
        }

        @Override
        public void onPreExecute() {
            super.onPreExecute();
            progressDialog = new ProgressDialog(SavedDocumentPreviewActivity.this);
            progressDialog.setIndeterminate(true);
            progressDialog.setMessage("Please Wait...");
            progressDialog.setCancelable(false);
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.show();
        }

        @Override
        public String doInBackground(String... strArr) {
            pdfFile = createPDFfromBitmap(group_name, current_bitmap, "temp");
            return null;
        }

        @Override
        public void onPostExecute(String str) {
            super.onPostExecute(str);
            if (pdfFailed(pdfFile)) {
                dismissSafely(progressDialog);
                return;
            }
            pdfUri = BaseActivity.getURIFromFile(pdfFile.getPath(), SavedDocumentPreviewActivity.this);
            dismissSafely(progressDialog);
            selected_group_name = group_name;
            Constant.IdentifyActivity = "PDFViewerActivity_Preview";
            NavigationUtils.jumpNextActivity(SavedDocumentPreviewActivity.this);
        }
    }

    private class rotateDoc extends AsyncTask<String, Void, String> {
        boolean saveFailed;
        ProgressDialog progressDialog;

        private rotateDoc() {
        }

        @Override
        public void onPreExecute() {
            super.onPreExecute();
            progressDialog = new ProgressDialog(SavedDocumentPreviewActivity.this);
            progressDialog.setIndeterminate(true);
            progressDialog.setMessage("Please Wait...");
            progressDialog.setCancelable(false);
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.show();
        }

        @Override
        public String doInBackground(String... strArr) {
            Bitmap bitmap = Constant.original;
            Matrix matrix = new Matrix();
            matrix.postRotate(90.0f);
            Bitmap createBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            System.gc();
            Constant.original = createBitmap;
            byte[] bytes = BitmapUtils.getBytes(Constant.original);
            File file = BitmapUtils.writeImageFile(AppStorage.documentsDir(SavedDocumentPreviewActivity.this), bytes);
            if (file == null) {
                saveFailed = true;
                return null;
            }
            if (position == 0) {
                dataBaseHelper.updateGroupFirstImg(edit_doc_grp_name, file.getPath());
                dataBaseHelper.updateGroupListDoc(edit_doc_grp_name, current_doc_name, file.getPath());
            } else {
                dataBaseHelper.updateGroupListDoc(edit_doc_grp_name, current_doc_name, file.getPath());
            }
            currentDocList = dataBaseHelper.getGroupDocs(edit_doc_grp_name);
            return null;
        }

        @Override
        public void onPostExecute(String str) {
            super.onPostExecute(str);
            previewPagerAdapter = new PreviewPagerAdapter(savedDocumentPreviewActivity, currentDocList);
            viewPager.setAdapter(previewPagerAdapter);
            viewPager.setCurrentItem(position);
            dismissSafely(progressDialog);
            if (saveFailed) {
                Toast.makeText(SavedDocumentPreviewActivity.this, R.string.page_save_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    private class shareAsPDF extends AsyncTask<String, Void, String> {
        File pdfFile;
        private ArrayList<Bitmap> bitmap;
        private String inputType;
        private String password;
        private ProgressDialog progressDialog;

        private shareAsPDF(ArrayList<Bitmap> arrayList, String str, String str2) {
            bitmap = arrayList;
            inputType = str;
            password = str2;
        }

        @Override
        public void onPreExecute() {
            super.onPreExecute();
            progressDialog = new ProgressDialog(SavedDocumentPreviewActivity.this);
            progressDialog.setIndeterminate(true);
            progressDialog.setMessage("Please Wait...");
            progressDialog.setCancelable(false);
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.show();
        }

        @Override
        public String doInBackground(String... strArr) {
            if (inputType.equals(PdfObject.TEXT_PDFDOCENCODING)) {
                pdfFile = createPDFfromBitmap(edit_doc_grp_name, bitmap, "temp");
                return null;
            }
            pdfFile = createProtectedPDFfromBitmap(edit_doc_grp_name, bitmap, password, "temp");
            return null;
        }

        @Override
        public void onPostExecute(String str) {
            super.onPostExecute(str);
            if (pdfFailed(pdfFile)) {
                dismissSafely(progressDialog);
                return;
            }
            Uri uRIFromFile = BaseActivity.getURIFromFile(pdfFile.getPath(), SavedDocumentPreviewActivity.this);
            Intent intent = new Intent();
            intent.setAction("android.intent.action.SEND");
            intent.setType("application/pdf");
            intent.putExtra("android.intent.extra.STREAM", uRIFromFile);
            intent.putExtra("android.intent.extra.TEXT", "");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent createChooser = Intent.createChooser(intent, (CharSequence) null);
            if (progressDialog.isShowing()) {
                dismissSafely(progressDialog);
            }
            startActivity(createChooser);
        }
    }

    private class updateEditedImg extends AsyncTask<String, Void, String> {
        boolean saveFailed;
        private ProgressDialog progressDialog;

        private updateEditedImg() {
        }

        @Override
        public void onPreExecute() {
            super.onPreExecute();
            progressDialog = new ProgressDialog(SavedDocumentPreviewActivity.this);
            progressDialog.setIndeterminate(true);
            progressDialog.setMessage("Please Wait...");
            progressDialog.setCancelable(false);
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.show();
        }

        @Override
        public String doInBackground(String... strArr) {
            if (Constant.original == null) {
                return null;
            }
            byte[] bytes = BitmapUtils.getBytes(Constant.original);
            File file = BitmapUtils.writeImageFile(AppStorage.documentsDir(SavedDocumentPreviewActivity.this), bytes);
            if (file == null) {
                saveFailed = true;
                return null;
            }
            if (position == 0) {
                dataBaseHelper.updateGroupFirstImg(edit_doc_grp_name, file.getPath());
                dataBaseHelper.updateGroupListDoc(edit_doc_grp_name, current_doc_name, file.getPath());
            } else {
                dataBaseHelper.updateGroupListDoc(edit_doc_grp_name, current_doc_name, file.getPath());
            }
            currentDocList = dataBaseHelper.getGroupDocs(edit_doc_grp_name);
            return null;
        }

        @Override
        public void onPostExecute(String str) {
            super.onPostExecute(str);
            previewPagerAdapter = new PreviewPagerAdapter(savedDocumentPreviewActivity, currentDocList);
            viewPager.setAdapter(previewPagerAdapter);
            viewPager.setCurrentItem(position);
            dismissSafely(progressDialog);
            if (saveFailed) {
                Toast.makeText(SavedDocumentPreviewActivity.this, R.string.page_save_failed, Toast.LENGTH_LONG).show();
            }
        }
    }
}
