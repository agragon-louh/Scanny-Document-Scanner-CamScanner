package com.scanlibrary;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

import org.opencv.android.Utils;
import org.opencv.core.Mat;


public class ScanActivity extends Activity implements IScanner, ComponentCallbacks2 {

    static {
        // OpenCV 4 from Maven Central (org.opencv:opencv). The filters used to live in a
        // prebuilt libScanner.so linked against a 2017 OpenCV 3; they are now plain Java on top
        // of the OpenCV Java API (ScanImageOps).
        System.loadLibrary("opencv_java4");
    }

    private interface MatOp {
        Mat apply(Mat rgba);
    }

    /**
     * Runs {@code op} on the pixels of {@code bitmap} and returns the result as a new bitmap of
     * the same config. Like the native code, only ARGB_8888 input is accepted (null otherwise).
     */
    private static Bitmap filter(Bitmap bitmap, MatOp op) {
        if (bitmap == null || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            return null;
        }
        Mat src = new Mat();
        Mat dst = null;
        try {
            Utils.bitmapToMat(bitmap, src);
            dst = op.apply(src);
            Bitmap result = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(dst, result);
            return result;
        } finally {
            src.release();
            if (dst != null) {
                dst.release();
            }
        }
    }

    public static Bitmap getScannedBitmap(Bitmap bitmap, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
        return filter(bitmap, rgba -> ScanImageOps.warp(rgba, x1, y1, x2, y2, x3, y3, x4, y4));
    }

    /** Text-line deskew (see {@link ScanImageOps#deskew}); null for non-ARGB_8888 input. */
    public static Bitmap getDeskewedBitmap(Bitmap bitmap) {
        return filter(bitmap, ScanImageOps::deskew);
    }

    public static Bitmap getGrayBitmap(Bitmap bitmap) {
        return filter(bitmap, ScanImageOps::gray);
    }

    public static Bitmap getMagicColorBitmap(Bitmap bitmap) {
        return filter(bitmap, ScanImageOps::magicColor);
    }

    public static Bitmap getBWBitmap(Bitmap bitmap) {
        return filter(bitmap, ScanImageOps::blackAndWhite);
    }

    /** Document corners as {@code [x1, x2, x3, x4, y1, y2, y3, y4]}, or null for non-ARGB_8888 input. */
    public static float[] getPoints(Bitmap bitmap) {
        if (bitmap == null || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            return null;
        }
        Mat src = new Mat();
        try {
            Utils.bitmapToMat(bitmap, src);
            return ScanImageOps.findCorners(src);
        } finally {
            src.release();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.scan_layout);
        init();
    }

    private void init() {
        PickImageFragment fragment = new PickImageFragment();
        Bundle bundle = new Bundle();
        bundle.putInt(ScanConstants.OPEN_INTENT_PREFERENCE, getPreferenceContent());
        fragment.setArguments(bundle);
        android.app.FragmentManager fragmentManager = getFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        fragmentTransaction.add(R.id.content, fragment);
        fragmentTransaction.commit();
    }

    protected int getPreferenceContent() {
        return getIntent().getIntExtra(ScanConstants.OPEN_INTENT_PREFERENCE, 0);
    }

    @Override
    public void onBitmapSelect(Uri uri) {
        ScanFragment fragment = new ScanFragment();
        Bundle bundle = new Bundle();
        bundle.putParcelable(ScanConstants.SELECTED_BITMAP, uri);
        fragment.setArguments(bundle);
        android.app.FragmentManager fragmentManager = getFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        fragmentTransaction.add(R.id.content, fragment);
        fragmentTransaction.addToBackStack(ScanFragment.class.toString());
        fragmentTransaction.commit();
    }

    @Override
    public void onScanFinish(Uri uri) {
        ResultFragment fragment = new ResultFragment();
        Bundle bundle = new Bundle();
        bundle.putParcelable(ScanConstants.SCANNED_RESULT, uri);
        fragment.setArguments(bundle);
        android.app.FragmentManager fragmentManager = getFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        fragmentTransaction.add(R.id.content, fragment);
        fragmentTransaction.addToBackStack(ResultFragment.class.toString());
        fragmentTransaction.commit();
    }

    @Override
    public void onTrimMemory(int level) {
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                /*
                   Release any UI objects that currently hold memory.

                   The user interface has moved to the background.
                */
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                /*
                   Release any memory that your app doesn't need to run.

                   The device is running low on memory while the app is running.
                   The event raised indicates the severity of the memory-related event.
                   If the event is TRIM_MEMORY_RUNNING_CRITICAL, then the system will
                   begin killing background processes.
                */
                break;
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                /*
                   Release as much memory as the process can.

                   The app is on the LRU list and the system is running low on memory.
                   The event raised indicates where the app sits within the LRU list.
                   If the event is TRIM_MEMORY_COMPLETE, the process will be one of
                   the first to be terminated.
                */
                new AlertDialog.Builder(this)
                        .setTitle(R.string.low_memory)
                        .setMessage(R.string.low_memory_message)
                        .create()
                        .show();
                break;
            default:
                /*
                  Release any non-critical data structures.

                  The app received an unrecognized memory level value
                  from the system. Treat this as a generic low-memory message.
                */
                break;
        }
    }
}