package com.scanny.scanner.utils;

import android.app.Activity;
import android.content.Intent;

import com.scanny.scanner.main_utils.Constant;

public class NavigationUtils {

    /**
     * Opens the screen named by {@link Constant#IdentifyActivity}. Each activity registers a
     * receiver for "&lt;package&gt;.&lt;IdentifyActivity&gt;" and starts the next screen from it.
     */
    public static void jumpNextActivity(Activity activity) {
        if (Constant.IdentifyActivity == null || Constant.IdentifyActivity.isEmpty()) {
            return;
        }
        String packageName = activity.getPackageName();
        activity.sendBroadcast(new Intent(packageName + "." + Constant.IdentifyActivity).setPackage(packageName));
    }
}
