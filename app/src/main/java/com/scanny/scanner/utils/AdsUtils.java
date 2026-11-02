package com.scanny.scanner.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.scanny.scanner.R;
import com.scanny.scanner.main_utils.Constant;

public class AdsUtils {

    private static InterstitialAd mInterstitialAd;

    public static void showGoogleBannerAd(Context context, AdView adView) {
        adView.setVisibility(View.VISIBLE);
        MobileAds.initialize(context, (OnInitializationCompleteListener) new OnInitializationCompleteListener() {
            public void onInitializationComplete(InitializationStatus initializationStatus) {
            }
        });
        adView.loadAd(new AdRequest.Builder().build());
    }

    public static void loadGoogleInterstitialAd(Context context, Activity activity) {
        MobileAds.initialize(context, (OnInitializationCompleteListener) new OnInitializationCompleteListener() {
            public void onInitializationComplete(InitializationStatus initializationStatus) {
            }
        });

        InterstitialAd.load(
                context,
                context.getResources().getString(R.string.admob_interstitial_id),
                new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd interstitialAd) {
                        mInterstitialAd = interstitialAd;

                        interstitialAd.setFullScreenContentCallback(
                                new FullScreenContentCallback() {
                                    @Override
                                    public void onAdDismissedFullScreenContent() {
                                        // Called when fullscreen content is dismissed.
                                        // Make sure to set your reference to null so you don't
                                        // show it a second time.
                                        mInterstitialAd = null;
                                        jumpNextActivity(activity);
                                        Log.d("TAG", "The ad was dismissed.");
                                    }

                                    @Override
                                    public void onAdFailedToShowFullScreenContent(AdError adError) {
                                        // Called when fullscreen content failed to show.
                                        // Make sure to set your reference to null so you don't
                                        // show it a second time.
                                        mInterstitialAd = null;
                                        Log.d("TAG", "The ad failed to show.");
                                    }

                                    @Override
                                    public void onAdShowedFullScreenContent() {
                                        // Called when fullscreen content is shown.
                                        Log.d("TAG", "The ad was shown.");
                                    }
                                });
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        super.onAdFailedToLoad(loadAdError);
                        mInterstitialAd = null;
                    }
                });
    }

    public static void showGoogleInterstitialAd(Activity activity, boolean isShowAd) {
        if (isShowAd) {
            if (mInterstitialAd != null) {
                mInterstitialAd.show(activity);
            } else {
                jumpNextActivity(activity);
            }
            loadGoogleInterstitialAd(activity, activity);
        } else {
            jumpNextActivity(activity);
        }

    }

    public static void jumpNextActivity(Activity activity) {
        if (Constant.IdentifyActivity.equals("MainActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".MainActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("PrivacyPolicyActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".PrivacyPolicyActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("QRGenerateActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".QRGenerateActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("QRReaderActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".QRReaderActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("MainGalleryActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".MainGalleryActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("ScannerActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".ScannerActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("GroupDocumentActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".GroupDocumentActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("CropDocumentActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".CropDocumentActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("ScannerGalleryActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".ScannerGalleryActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("CropDocumentActivity2")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".CropDocumentActivity2").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("DocumentEditorActivity_Crop")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".DocumentEditorActivity_Crop").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("CurrentFilterActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".CurrentFilterActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("ScannerActivity_Retake")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".ScannerActivity_Retake").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("SavedDocumentActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".SavedDocumentActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("ScannerActivity_Retake2")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".ScannerActivity_Retake2").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("DocumentEditorActivity_Saved")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".DocumentEditorActivity_Saved").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("SavedEditDocumentActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".SavedEditDocumentActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("DocumentEditorActivity_SavedEdit")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".DocumentEditorActivity_SavedEdit").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("DocumentEditorActivity_SavedEdit2")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".DocumentEditorActivity_SavedEdit2").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("PDFViewerActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".PDFViewerActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("NoteActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".NoteActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("ImageToTextActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".ImageToTextActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("PDFViewerActivity2")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".PDFViewerActivity2").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("DocumentGalleryActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".DocumentGalleryActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("CropDocumentActivity4")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".CropDocumentActivity4").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("ScannerActivity2")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".ScannerActivity2").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("SavedDocumentPreviewActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".SavedDocumentPreviewActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("IDCardPreviewActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".IDCardPreviewActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("SavedEditDocumentActivity3")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".SavedEditDocumentActivity3").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("UcropActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".UcropActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("DocumentEditorActivity_Scanner")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".DocumentEditorActivity_Scanner").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("IDCardPreviewActivity2")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".IDCardPreviewActivity2").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("DocumentEditorActivity_IDCard")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".DocumentEditorActivity_IDCard").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("IDCardGalleryActivity")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".IDCardGalleryActivity").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("DocumentEditorActivity_SavedPreview")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".DocumentEditorActivity_SavedPreview").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("PDFViewerActivity_Preview")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".PDFViewerActivity_Preview").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("NoteActivity_Preview")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".NoteActivity_Preview").setPackage(activity.getPackageName()));
        } else if (Constant.IdentifyActivity.equals("ImageToTextActivity_Preview")) {
            activity.sendBroadcast(new Intent(activity.getPackageName() + ".ImageToTextActivity_Preview").setPackage(activity.getPackageName()));
        }
    }
}
