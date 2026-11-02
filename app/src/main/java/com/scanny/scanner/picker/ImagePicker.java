package com.scanny.scanner.picker;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;

import java.util.ArrayList;

/**
 * Minimal drop-in replacement for the unmaintained {@code com.nguyenhoanglam:ImagePicker}
 * library, built on the Android system photo picker.
 *
 * <p>Why: the old library read the whole gallery itself and therefore needed
 * READ_EXTERNAL_STORAGE, which is never granted on Android 13+ (API 33+). The system photo
 * picker needs no storage permission at all and only hands the app the images the user
 * actually selected.</p>
 *
 * <p>The builder keeps the old method names so call sites stay unchanged; purely cosmetic
 * options (colors, folder mode, ...) are ignored because the system picker owns its UI.</p>
 */
public final class ImagePicker {

    private ImagePicker() {
    }

    public static Builder with(Activity activity) {
        return new Builder(activity);
    }

    public static boolean shouldHandleResult(int requestCode, int resultCode, Intent data, int expectedRequestCode) {
        return requestCode == expectedRequestCode && resultCode == Activity.RESULT_OK && data != null;
    }

    public static ArrayList<Image> getImages(Intent data) {
        ArrayList<Image> images = new ArrayList<>();
        if (data == null) {
            return images;
        }
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    images.add(new Image(uri));
                }
            }
        } else if (data.getData() != null) {
            images.add(new Image(data.getData()));
        }
        return images;
    }

    /** True when {@link MediaStore#ACTION_PICK_IMAGES} is available (Android 13+, or 11/12 with the SDK extension). */
    static boolean isSystemPickerAvailable() {
        if (Build.VERSION.SDK_INT >= 33) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2;
        }
        return false;
    }

    public static final class Builder {
        private final Activity activity;
        private boolean multipleMode = false;
        private int maxSize = 1;
        private int requestCode = 100;

        Builder(Activity activity) {
            this.activity = activity;
        }

        public Builder setMultipleMode(boolean multipleMode) {
            this.multipleMode = multipleMode;
            return this;
        }

        public Builder setMaxSize(int maxSize) {
            this.maxSize = Math.max(1, maxSize);
            return this;
        }

        public Builder setRequestCode(int requestCode) {
            this.requestCode = requestCode;
            return this;
        }

        // ---- Cosmetic options of the old library, kept for source compatibility. ----
        public Builder setStatusBarColor(String color) {
            return this;
        }

        public Builder setToolbarColor(String color) {
            return this;
        }

        public Builder setBackgroundColor(String color) {
            return this;
        }

        public Builder setFolderMode(boolean folderMode) {
            return this;
        }

        public Builder setFolderTitle(String title) {
            return this;
        }

        public Builder setShowNumberIndicator(boolean show) {
            return this;
        }

        public Builder setAlwaysShowDoneButton(boolean show) {
            return this;
        }

        public Builder setShowCamera(boolean show) {
            return this;
        }

        public Builder setLimitMessage(String message) {
            return this;
        }

        public void start() {
            boolean allowMultiple = multipleMode && maxSize > 1;
            Intent intent;
            if (isSystemPickerAvailable()) {
                intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
                intent.setType("image/*");
                if (allowMultiple) {
                    int limit = Math.min(maxSize, MediaStore.getPickImagesMaxLimit());
                    intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, limit);
                }
            } else {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
            }
            activity.startActivityForResult(intent, requestCode);
        }
    }
}
