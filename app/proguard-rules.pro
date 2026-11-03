# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class me.pqpo.smartcropperlib.**{*;}
-keep class com.shockwave.**
-keep class com.android.vending.billing.**

# ---------------------------------------------------------------------------
# Security: strip android.util.Log calls from release builds. The app logged
# document names, SQL statements, QR-code contents and PDF metadata.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# iText 5 references optional classes (BouncyCastle CMS, AWT) not present on Android.
-dontwarn com.itextpdf.**
-dontwarn org.bouncycastle.**
-keep class com.itextpdf.** { *; }
-keep class org.bouncycastle.** { *; }

# jp.wasabeef RichEditor uses a JavaScript interface.
-keepclassmembers class jp.wasabeef.richeditor.RichEditor$* {
    @android.webkit.JavascriptInterface <methods>;
}

# OpenCV JNI bridge in :scanlibrary (native code looks these up by name).
-keep class com.scanlibrary.** { *; }
-keep class org.opencv.** { *; }

# jsoup references the optional re2j regex engine, which is not bundled.
-dontwarn com.google.re2j.**

# PDFBox-Android (PDF password protection, see PdfProtector).
# JPEG 2000 decoding is an optional extra library that the app does not ship.
-dontwarn com.gemalto.jp2.**
# SecurityHandlerFactory creates the AES security handlers reflectively.
-keep class com.tom_roush.pdfbox.pdmodel.encryption.** { *; }
