package com.scanny.scanner.activity;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import android.view.WindowManager;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Image;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfPageEventHelper;
import com.itextpdf.text.pdf.PdfWriter;
import com.scanny.scanner.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;

public class BaseActivity extends AppCompatActivity {
    private static final String TAG = "BaseActivity";

    public static Bitmap imageBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int i = -1;
        int height = bitmap.getHeight();
        int i2 = -1;
        int i3 = width;
        int i4 = 0;
        while (i4 < bitmap.getHeight()) {
            int i5 = i2;
            int i6 = i3;
            for (int i7 = 0; i7 < bitmap.getWidth(); i7++) {
                if (((bitmap.getPixel(i7, i4) >> 24) & 255) > 0) {
                    if (i7 < i6) {
                        i6 = i7;
                    }
                    if (i7 > i) {
                        i = i7;
                    }
                    if (i4 < height) {
                        height = i4;
                    }
                    if (i4 > i5) {
                        i5 = i4;
                    }
                }
            }
            i4++;
            i3 = i6;
            i2 = i5;
        }
        if (i < i3 || i2 < height) {
            return null;
        }
        return Bitmap.createBitmap(bitmap, i3, height, (i - i3) + 1, (i2 - height) + 1);
    }

    public static void hideKeyboard(Activity activity) {
        try {
            ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getCurrentFocus().getWindowToken(), 2);
        } catch (Exception e) {
            Log.e("KeyBoardUtil", e.toString(), e);
        }
    }

    public static Uri getURIFromFile(String str, Activity activity) {
        return FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", new File(str));
    }

    @Override
    public void onCreate(Bundle bundle) {
        // Apps targeting API 35+ are always drawn edge-to-edge. Enable it explicitly so older
        // Android versions behave the same, then pad the content in onPostCreate().
        EdgeToEdge.enable(this);
        super.onCreate(bundle);
        // Predictive back (default for apps targeting API 36+) no longer calls an overridden
        // Activity#onBackPressed(). Route the system back gesture through handleBack() instead;
        // explicit onBackPressed() calls (toolbar back buttons) still end up here as well.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
    }

    /** Called for system back and for onBackPressed(). Subclasses override for custom behaviour. */
    protected void handleBack() {
        finish();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        int adjust = getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
        final boolean padForKeyboard = adjust != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                && adjust != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int bottom = bars.bottom;
            if (padForKeyboard) {
                bottom = Math.max(bottom, windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            }
            view.setPadding(bars.left, bars.top, bars.right, bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
    }

    /**
     * Resolves {@code name + ".pdf"} inside {@code dir} and refuses names that would escape it
     * (e.g. a document called "../../something"), since names are user supplied.
     */
    protected static File pdfFileInside(File dir, String name) throws IOException {
        File file = new File(dir, name + ".pdf");
        String dirPath = dir.getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(dirPath)) {
            throw new IOException("Invalid document name");
        }
        return file;
    }

    private static String randomOwnerPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public Bitmap getMainFrameBitmap(View view) {
        Bitmap createBitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(createBitmap));
        return imageBitmap(createBitmap);
    }

    public Bitmap getBitmap(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(new FileInputStream(file), (Rect) null, options);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void saveImageToGallery(String path, Context context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            ContentValues contentValues = new ContentValues();
            contentValues.put("datetaken", Long.valueOf(System.currentTimeMillis()));
            contentValues.put("mime_type", "image/jpeg");
            contentValues.put("_data", path);
            context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
        }
        // On Android 10+ inserting a raw "_data" path is rejected for files outside DCIM/ or
        // Pictures/ (IllegalArgumentException -> crash in the AsyncTask). The media scanner
        // indexes the file written to Download/Scanny/Images on its own.

        MediaScannerConnection.scanFile(this, new String[]{path}, null,
                null);
    }

    public String[] getColorFilterName() {
        return new String[]{"Original", "Sarurize", "Mono", "Tone", "Natural", "Mellow", "Luv", "Soft", "Grey", "Sketchy", "Emerald", "Blurry"};
    }

    public ArrayList<Integer> waterMarkFontList() {
        ArrayList<Integer> arrayList = new ArrayList<>();
        arrayList.add(Integer.valueOf(R.font.roboto_medium));
        arrayList.add(Integer.valueOf(R.font.aa));
        arrayList.add(Integer.valueOf(R.font.aclonica_regular));
        arrayList.add(Integer.valueOf(R.font.aileron_regular));
        arrayList.add(Integer.valueOf(R.font.aileron_thin));
        arrayList.add(Integer.valueOf(R.font.book_antiqua));
        arrayList.add(Integer.valueOf(R.font.cambria));
        arrayList.add(Integer.valueOf(R.font.cherrycreamsoda_regular));
        arrayList.add(Integer.valueOf(R.font.chewy_regular));
        arrayList.add(Integer.valueOf(R.font.comingsoon_regular));
        arrayList.add(Integer.valueOf(R.font.didot_regular));
        arrayList.add(Integer.valueOf(R.font.ebgaramondsc_regular));
        arrayList.add(Integer.valueOf(R.font.fontdinerswanky_regular));
        arrayList.add(Integer.valueOf(R.font.georgia_regular));
        arrayList.add(Integer.valueOf(R.font.helvetica));
        arrayList.add(Integer.valueOf(R.font.helvetica_compressed));
        arrayList.add(Integer.valueOf(R.font.helvetica_light));
        arrayList.add(Integer.valueOf(R.font.maidenorange_regular));
        arrayList.add(Integer.valueOf(R.font.montez_regular));
        arrayList.add(Integer.valueOf(R.font.opensans_light));
        arrayList.add(Integer.valueOf(R.font.rancho_regular));
        arrayList.add(Integer.valueOf(R.font.rochester_regular));
        arrayList.add(Integer.valueOf(R.font.tinos_regular));
        arrayList.add(Integer.valueOf(R.font.trebuc));
        arrayList.add(Integer.valueOf(R.font.yellowtail_regular));
        return arrayList;
    }

    public ArrayList<String> getAssertFile(String str) {
        ArrayList<String> arrayList;
        try {
            String[] list = getAssets().list(str);
            if (list.length == 0) {
                return null;
            }
            arrayList = new ArrayList<>();
            int i = 0;
            while (i < list.length) {
                try {
                    arrayList.add(list[i]);
                    i++;
                } catch (Exception e) {
                    Log.e("tag", "I/O Exception", e);
                    return arrayList;
                }
            }
            return arrayList;
        } catch (IOException e2) {
            arrayList = null;
            Log.e("tag", "I/O Exception", e2);
            return arrayList;
        }
    }

    public void showSoftKeyboard(View view) {
        if (view.requestFocus()) {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(view, 1);
        }
    }

    public void hideSoftKeyboard(View view) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public void createPDFfromBitmap(String str, ArrayList<Bitmap> arrayList, String str2) {
        Document document = new Document();
        try {
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + getResources().getString(R.string.app_name));
            if (!file.exists()) {
                file.mkdirs();
            }
            File file2 = pdfFileInside(file, str);
            if (file2.exists()) {
                file2.delete();
            }
            PdfWriter.getInstance(document, new FileOutputStream(file2)).setPageEvent(new HeaderAndFooterOfPDf());
            document.open();
            document.addCreationDate();
            document.addAuthor(getResources().getString(R.string.app_name));
            document.addCreator(getResources().getString(R.string.app_name));
            Iterator<Bitmap> it = arrayList.iterator();
            while (it.hasNext()) {
                document.setPageSize(PageSize.A4);
                document.newPage();
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                it.next().compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
                Image instance = Image.getInstance(byteArrayOutputStream.toByteArray());
                instance.scaleToFit(PageSize.A4.getWidth(), PageSize.A4.getHeight());
                instance.setAbsolutePosition((PageSize.A4.getWidth() - instance.getScaledWidth()) / 2.0f, (PageSize.A4.getHeight() - instance.getScaledHeight()) / 2.0f);
                document.add(instance);
            }
            document.close();
        } catch (DocumentException | IOException e) {
            Log.e(TAG, "Make Folder to PDF: " + e.getMessage());
        }
    }

    public void createProtectedPDFfromBitmap(String str, ArrayList<Bitmap> arrayList, String str2, String str3) {
        Document document = new Document();
        try {
            if (str3.equals("temp")) {
                File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + getResources().getString(R.string.app_name));
                if (!file.exists()) {
                    file.mkdirs();
                }
                File file2 = pdfFileInside(file, str);
                if (file2.exists()) {
                    file2.delete();
                }
                PdfWriter.getInstance(document, new FileOutputStream(file2)).setEncryption(str2.getBytes(java.nio.charset.StandardCharsets.UTF_8), randomOwnerPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8), PdfWriter.ALLOW_PRINTING, PdfWriter.ENCRYPTION_AES_256);
            } else {
                File file3 = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + getResources().getString(R.string.app_name) + "/Download");
                if (!file3.exists()) {
                    file3.mkdirs();
                }
                File file4 = pdfFileInside(file3, str);
                if (file4.exists()) {
                    file4.delete();
                }
                PdfWriter.getInstance(document, new FileOutputStream(file4)).setEncryption(str2.getBytes(java.nio.charset.StandardCharsets.UTF_8), randomOwnerPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8), PdfWriter.ALLOW_PRINTING, PdfWriter.ENCRYPTION_AES_256);
            }
            document.open();
            document.addCreationDate();
            document.addAuthor(getResources().getString(R.string.app_name));
            document.addCreator(getResources().getString(R.string.app_name));
            Iterator<Bitmap> it = arrayList.iterator();
            while (it.hasNext()) {
                document.setPageSize(PageSize.A4);
                document.newPage();
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                it.next().compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
                Image instance = Image.getInstance(byteArrayOutputStream.toByteArray());
                instance.scaleToFit(PageSize.A4.getWidth(), PageSize.A4.getHeight());
                instance.setAbsolutePosition((PageSize.A4.getWidth() - instance.getScaledWidth()) / 2.0f, (PageSize.A4.getHeight() - instance.getScaledHeight()) / 2.0f);
                document.add(instance);
            }
            document.close();
        } catch (DocumentException | IOException e) {
            Log.e(TAG, "Make Folder to PDF: " + e.getMessage());
        }
    }

    private class HeaderAndFooterOfPDf extends PdfPageEventHelper {

        private HeaderAndFooterOfPDf() {
        }

        @Override
        public void onEndPage(PdfWriter pdfWriter, Document document) {
            ColumnText.showTextAligned(pdfWriter.getDirectContent(), 1, new Phrase(String.format("%d", new Object[]{Integer.valueOf(pdfWriter.getPageNumber())})), 300.0f, 62.0f, 0.0f);
        }
    }
}
