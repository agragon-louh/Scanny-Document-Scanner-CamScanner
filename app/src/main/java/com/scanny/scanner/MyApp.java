package com.scanny.scanner;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.scanny.scanner.main_utils.Constant;

import me.pqpo.smartcropperlib.SmartCropper;

public class MyApp extends Application {
    private static MyApp instance;

    public static MyApp getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 如果使用机器学习代替 Canny 算子，请初始化 ImageDetector
        SmartCropper.buildImageDetector(this);

        SharedPreferences sharedPreferences = getSharedPreferences(Constant.PREFS_NAME, Context.MODE_PRIVATE);
        sharedPreferences.edit().putInt(Constant.KEY_THEME, Constant.THEME_DARK).apply();

        // The OneSignal push SDK was removed: it was never initialised (placeholder app id), but
        // its manifest still exported receivers/activities and added boot and wake-lock
        // permissions. Add a push provider back only together with a real app id.

        initTheme();
    }

    private void initTheme() {
        int savedTheme = getSavedTheme();
        if (savedTheme == Constant.THEME_LIGHT) {
            setTheme(AppCompatDelegate.MODE_NIGHT_NO, Constant.THEME_LIGHT);
        } else if (savedTheme == Constant.THEME_DARK) {
            setTheme(AppCompatDelegate.MODE_NIGHT_YES, Constant.THEME_DARK);
        }
    }

    private int getSavedTheme() {
        SharedPreferences sharedPreferences = getApplicationContext().getSharedPreferences(Constant.PREFS_NAME, Context.MODE_PRIVATE);
        return sharedPreferences.getInt(Constant.KEY_THEME, Constant.THEME_UNDEFINED);
    }

    public void saveTheme(int theme) {
        SharedPreferences sharedPreferences = getApplicationContext().getSharedPreferences(Constant.PREFS_NAME, Context.MODE_PRIVATE);
        sharedPreferences.edit().putInt(Constant.KEY_THEME, theme).apply();
    }

    private void setTheme(int themeMode, int prefsMode) {
        AppCompatDelegate.setDefaultNightMode(themeMode);
        saveTheme(prefsMode);
    }
}
