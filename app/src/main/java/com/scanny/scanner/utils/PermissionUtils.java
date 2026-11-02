package com.scanny.scanner.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime permissions needed by the scan / import / export flows, per Android version.
 *
 * <p>The app used to always request READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE + CAMERA
 * and re-request until all were granted. WRITE_EXTERNAL_STORAGE is never granted on API 30+
 * and READ_EXTERNAL_STORAGE is never granted on API 33+, so every feature behind that check
 * was unreachable on modern devices (and the request loop never ended).</p>
 *
 * <ul>
 *     <li>Gallery import uses the system photo picker and needs no permission.</li>
 *     <li>Writing to Download/Scanny needs WRITE_EXTERNAL_STORAGE only up to API 29.</li>
 *     <li>READ_EXTERNAL_STORAGE is still requested on API 30-32 so files created by a previous
 *     install remain readable; from API 33 the app only reads files it created itself.</li>
 * </ul>
 */
public final class PermissionUtils {

    private PermissionUtils() {
    }

    public static String[] storageAndCameraPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return permissions.toArray(new String[0]);
    }

    public static boolean hasStorageAndCamera(Context context) {
        for (String permission : storageAndCameraPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
