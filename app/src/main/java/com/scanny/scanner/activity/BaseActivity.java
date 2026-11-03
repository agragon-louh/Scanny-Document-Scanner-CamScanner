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
import android.widget.Toast;

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

    /** False in a fresh process, i.e. after Android killed the app while it was in the background. */
    private static boolean processAlive;
    private boolean stateLost;

    @Override
    public void onCreate(Bundle bundle) {
        stateLost = bundle != null && !processAlive;
        processAlive = true;
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

    /**
     * Call right after super.onCreate() in screens that depend on static state (the image being
     * edited, the current folder, captured pages). That state is gone when Android recreates the
     * screen in a new process, which used to crash these screens or act on the wrong folder.
     * Returns true after sending the user back to the home screen; the caller must return.
     */
    protected boolean restartIfStateLost() {
        if (!stateLost) {
            return false;
        }
        startActivity(new android.content.Intent(this, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        return true;
    }

    /**
     * Keeps this screen out of screenshots, screen recordings and the recent-apps thumbnail.
     * Used on screens that show scanned documents (IDs, signatures).
     */
    protected void blockScreenCapture() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
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
        return imageBitmap(captureView(view));
    }

    /**
     * Draws {@code view} into a bitmap. Must run on the UI thread: views were drawn from
     * AsyncTask.doInBackground, racing the UI thread's own drawing. Trim the result with
     * {@link #imageBitmap} off the UI thread (it reads every pixel).
     */
    public Bitmap captureView(View view) {
        Bitmap createBitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(createBitmap));
        return createBitmap;
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

    /**
     * Writes {@code pages} to a PDF and returns the file, or null on failure (callers must not
     * report success or share anything then).
     *
     * <p>{@code mode} "temp" (open/share) writes to the app's private cache, replacing the
     * previous copy. Any other mode is a user export to Download/Scanny and never overwrites an
     * existing file. Previously both went to Download/Scanny/&lt;name&gt;.pdf, so opening or
     * sharing a page silently replaced the user's saved export.
     */
    public File createPDFfromBitmap(String str, ArrayList<?> arrayList, String str2) {
        File dir = str2.equals("temp") ? new File(getCacheDir(), "pdf")
                : new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + getResources().getString(R.string.app_name));
        return writePdf(dir, str, arrayList, null, str2.equals("temp"));
    }

    public File createProtectedPDFfromBitmap(String str, ArrayList<?> arrayList, String str2, String str3) {
        File dir = str3.equals("temp") ? new File(getCacheDir(), "pdf")
                : new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + getResources().getString(R.string.app_name) + "/Download");
        return writePdf(dir, str, arrayList, str2, str3.equals("temp"));
    }

    /**
     * Dismisses a progress dialog unless this activity is already finishing or destroyed. A
     * background task finishing after rotation or back used to crash with "View not attached to
     * window manager".
     */
    protected void dismissSafely(android.app.Dialog dialog) {
        if (dialog == null || !dialog.isShowing() || isFinishing() || isDestroyed()) {
            return;
        }
        try {
            dialog.dismiss();
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Dialog already detached", e);
        }
    }

    /** Shows an error toast and returns true when {@code pdf} (from createPDF...) is null. */
    protected boolean pdfFailed(File pdf) {
        if (pdf != null) {
            return false;
        }
        Toast.makeText(this, "Could not create the PDF", Toast.LENGTH_SHORT).show();
        return true;
    }

    /** Longest side of a page image in the PDF: A4 at 300 dpi. */
    private static final int MAX_PAGE_SIDE = 3508;

    /**
     * Decodes an image file no larger than needed for a PDF page. Pages used to be decoded at
     * full resolution all at once, which ran out of memory for folders of 20+ pages.
     */
    private static Bitmap decodePage(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= MAX_PAGE_SIDE) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(path, options);
    }

    /** {@code pages} holds Bitmaps or image file paths (Strings); paths are decoded one by one. */
    private File writePdf(File dir, String name, ArrayList<?> pages, String password, boolean replace) {
        File file = null;
        Document document = new Document();
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File target = pdfFileInside(dir, name);
            if (replace) {
                if (target.exists() && !target.delete()) {
                    throw new IOException("Cannot replace " + target.getName());
                }
            } else {
                for (int i = 1; target.exists(); i++) {
                    target = pdfFileInside(dir, name + " (" + i + ")");
                }
            }
            // Only set once the target is ours, so the cleanup below never deletes an old export.
            file = target;
            PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(file));
            if (password == null) {
                writer.setPageEvent(new HeaderAndFooterOfPDf());
            } else {
                writer.setEncryption(password.getBytes(java.nio.charset.StandardCharsets.UTF_8), randomOwnerPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8), PdfWriter.ALLOW_PRINTING, PdfWriter.ENCRYPTION_AES_256);
            }
            document.open();
            document.addCreationDate();
            document.addAuthor(getResources().getString(R.string.app_name));
            document.addCreator(getResources().getString(R.string.app_name));
            int pageCount = 0;
            Iterator<?> it = pages.iterator();
            while (it.hasNext()) {
                Object item = it.next();
                boolean decoded = item instanceof String;
                Bitmap page = decoded ? decodePage((String) item) : (Bitmap) item;
                if (page == null) {
                    // Missing or unreadable image file: skip the page instead of crashing.
                    continue;
                }
                document.setPageSize(PageSize.A4);
                document.newPage();
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                page.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
                if (decoded) {
                    page.recycle();
                }
                Image instance = Image.getInstance(byteArrayOutputStream.toByteArray());
                instance.scaleToFit(PageSize.A4.getWidth(), PageSize.A4.getHeight());
                instance.setAbsolutePosition((PageSize.A4.getWidth() - instance.getScaledWidth()) / 2.0f, (PageSize.A4.getHeight() - instance.getScaledHeight()) / 2.0f);
                document.add(instance);
                pageCount++;
            }
            if (pageCount == 0) {
                throw new IOException("No readable pages");
            }
            document.close();
            return file;
        } catch (DocumentException | IOException | RuntimeException e) {
            Log.e(TAG, "Make Folder to PDF: " + e.getMessage());
            if (document.isOpen()) {
                try {
                    document.close();
                } catch (RuntimeException ignored) {
                }
            }
            if (file != null) {
                file.delete();
            }
            return null;
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
