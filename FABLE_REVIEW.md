# Scanny: security review, Android 17 upgrade, and hand-off for Fable

This is a blind review: I had no prior context about the app. It covers:

1. Security problems I found, and which ones are fixed.
2. The upgrade to the latest Android: **Android 17 / API 37**, AGP 9.5.0, Gradle 9.6.0.
3. **Work I could not finish or verify.** Fable should start with section 4.

> **The project has NOT been compiled.** The sandbox's network policy blocked
> `dl.google.com` (the Android SDK and Google Maven) and `jitpack.io`, and Maven Central
> returned HTTP 429 most of the time. No Android SDK was installed. I checked every changed
> Java file with a Java parser (all parse) and every changed XML file with an XML parser,
> but nothing has been type-checked, built, or run on a device.

---

## 1. Security findings

| # | Severity | Finding | Status |
|---|----------|---------|--------|
| S1 | High | **SQL injection through folder/document names.** Each folder name became a table name that was concatenated into SQL (`CREATE TABLE`, `SELECT * FROM <name>`, `ALTER TABLE ... RENAME`, `DROP TABLE`). `getSingleNote()` also concatenated the image name into a `WHERE` clause. A name containing `'`, `"`, `;` and so on crashed the app or could drop tables, including `alldocs`. | **Fixed** in `db/DBHelper.java`: identifiers are quoted and escaped (`quoteIdentifier`), values are bound as `?` parameters, and the new `DBHelper.isValidGroupName()` is enforced in the create and rename dialogs. Cursors are now closed and SQL is no longer logged. |
| S2 | High | **37 dynamically registered broadcast receivers were exported.** They were registered without flags, so any app could send `com.templatemela.Scanny.*` actions and drive the app's navigation and ad flow. On API 34+ this also **crashes** (`SecurityException`). | **Fixed**: `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`. Broadcasts in `AdsUtils` now call `setPackage(getPackageName())`. |
| S3 | High | **FileProvider exposed all of shared storage.** It used `external-path path="."` in the app config, the scanlibrary config, and the ImagePicker override. Any URI the app granted could name any file on shared storage. | **Fixed**: `res/xml/provider_paths.xml` now exposes only the app's external-files dir, `Download/Scanny/`, `Download/scanSample/`, and cache. The scanlibrary provider is removed (the manifest merger was replacing it anyway, and its hard-coded authority `com.scanlibrary.provider` would block installing any other app that uses the library). |
| S4 | Medium | **Cleartext HTTP allowed globally** (`usesCleartextTraffic="true"` and `cleartextTrafficPermitted="true"`). | **Fixed**: HTTPS only. |
| S5 | Medium | **`allowBackup="true"`**: scanned IDs, signatures and notes went into cloud backups. | **Fixed**: `allowBackup="false"` plus `data_extraction_rules.xml`, which excludes everything from cloud backup. |
| S6 | Medium | **Weak PDF password protection.** It used AES-128, and the owner password equalled the user password, so anyone who could open the file could also remove its restrictions. | **Fixed** in `BaseActivity`: AES-256 with a random 192-bit owner password, printing allowed. |
| S7 | Medium | **Path traversal in exported PDF names.** A user-typed name like `../../x` was written outside `Download/Scanny`. | **Fixed**: `BaseActivity.pdfFileInside()` checks the canonical path. Group names are also validated (S1). |
| S8 | Medium | **Sensitive data in logcat**: SQL statements, QR-code contents, PDF metadata and bookmarks, file paths. OneSignal was forced to `VERBOSE`. | **Fixed**: those logs are removed, OneSignal verbose logging is removed, and the release build strips all `android.util.Log` calls (`proguard-rules.pro`). |
| S9 | Medium | **Vulnerable or unmaintained dependencies**: BouncyCastle 1.64 (CVE-2020-15522, CVE-2023-33201, CVE-2024-29857, CVE-2024-30171, ...); unused `cz.msebera httpclient 4.4.1.1` and `loopj async-http 1.4.9`; a checked-in iText jar. | **Fixed**: `bcprov-jdk15to18:1.78.1`, both HTTP libraries removed, iText now comes from Maven Central (`5.5.13.4`). |
| S10 | Medium | **Build supply chain**: the OneSignal Gradle plugin used a dynamic range `[0.12.10, 0.99.99]`; JitPack was unrestricted; JCenter is dead. | **Fixed**: plugin removed, `jcenter()` removed, JitPack limited to 4 explicit groups in `settings.gradle`. **Not done:** wrapper `distributionSha256Sum` (see 4.1). |
| S11 | Low | Unneeded permissions: `BILLING`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `ACCESS_WIFI_STATE`, and storage permissions with no `maxSdkVersion`. | **Fixed**: removed, or scoped with `maxSdkVersion`. |
| S12 | Low | The privacy-policy WebView did not disable file and content access. | **Fixed**: explicitly disabled, and JavaScript is off. |
| S13 | Low | The QR "search" button hard-coded the Google app's class name and crashed when that app was missing. | **Fixed**: uses a generic `ACTION_WEB_SEARCH` and catches `ActivityNotFoundException`. |
| S14 | Low | **Self-XSS in notes.** `NoteActivity` passes stored note HTML to a JavaScript-enabled WebView (`RichEditor.setHtml`). Only the user's own input reaches it today. | **Open**: see 4.6. |
| S15 | Medium | **Data at rest is unencrypted.** Scans live in app-specific *external* storage (`Android/data/<pkg>/files`). Shared PDFs and images are written in plaintext to public `Download/Scanny/`, including the un-protected copy made when sharing a PDF. | **Open**: needs a design decision (see 4.6). |
| S16 | Info | AdMob IDs in `strings.xml` are **Google test IDs**, and the OneSignal app ID is a placeholder. OneSignal init is now skipped while the ID contains `#`. | **Open**: replace before release. |

---

## 2. Android upgrade (what changed)

### Build
- AGP **4.1.3 → 9.5.0**, Gradle **6.5 → 9.6.0** (wrapper scripts and jar regenerated).
  `compileSdk`/`targetSdk` **33 → 37**, `minSdk` **21 → 23**, Java **8 → 17**.
- Added `namespace` to all 3 modules and removed `package=` from the manifests.
- Replaced old DSL: `lintOptions`→`lint`, `aaptOptions`→`androidResources`, removed `buildToolsVersion`, the broken `jni.srcDirs` and `ndk.moduleName`, the Windows-style assets path, and `proguard-android.txt` (rejected by AGP 9).
- Repositories moved to `settings.gradle` (`FAIL_ON_PROJECT_REPOS`).
- `gradle.properties` keeps AGP 8.x behaviour for R classes. The code uses `switch (id) { case R.id.x: }`, which needs final R fields:
  `android.enableAppCompileTimeRClass=false`, `android.nonFinalResIds=false`, `android.nonTransitiveRClass=false`, `android.enableJetifier=true`.
- Removed the dead `armeabi` and `mips` prebuilt `.so` folders.

### Dependencies that no longer exist
JCenter now redirects to Maven Central, and these artifacts are **not there**, so the
original project could no longer build at all. I replaced them with small local
implementations or platform APIs:

| Removed library | Replacement |
|---|---|
| `com.divyanshu.colorseekbar` | `widget/ColorSeekBar.java` (attr `app:csb_colorSeeds`) |
| `com.kyanogen.signatureview` | `widget/SignatureView.java` (attrs `app:sv_*`) |
| `com.takwolf.android:aspectratio` | `widget/AspectRatioLayout.java` |
| `com.huangyz0918:androidwm-light` | `widget/WatermarkUtil.java` |
| `com.infideap:drawer-behavior` (3D drawer) | plain `androidx.drawerlayout.DrawerLayout` (the 3D effect is gone) |
| `com.github.barteksc:android-pdf-viewer` | `PDFViewerActivity` rewritten on `android.graphics.pdf.PdfRenderer` + `RecyclerView` |
| `com.github.nguyenhoanglam:ImagePicker` | `picker/ImagePicker.java`: same call-site API, backed by the **system photo picker** (`ACTION_PICK_IMAGES`, or `ACTION_GET_CONTENT` on older devices) |
| `androidx.lifecycle:lifecycle-extensions` + the decompiled `AppOpenManager_LifecycleAdapter` | `lifecycle-process` + `DefaultLifecycleObserver` |

Other version bumps: appcompat 1.7.1, material 1.13.0, core 1.16.0, activity 1.10.1,
constraintlayout 2.2.1, play-services-ads 20.2.0→24.4.0 (`AppOpenAd.load` signature updated),
OneSignal 4.4.0→5.1.38 (`initWithContext(ctx, appId)`), glide 4.16.0, zxing 3.5.3,
lottie 6.6.7, cameraview 2.7.2, ucrop 2.2.6→2.2.10.

### Runtime behaviour changes (API 30 → 37)
- **Storage permissions (API 30+/33+).** The app re-requested `READ`+`WRITE_EXTERNAL_STORAGE` until both were granted. They never are on modern Android, so gallery import, scan, and the QR reader and generator were **unreachable, and the request looped forever.** Fixed with `utils/PermissionUtils.java`: CAMERA, plus legacy storage permissions only on API ≤ 32. The callback no longer re-requests.
- **Gallery import** uses the system photo picker and needs no permission.
- **`android:exported`** is set on every activity, as required for API 31+. The build failed without it.
- **Edge-to-edge (enforced on API 35+).** `BaseActivity` calls `EdgeToEdge.enable()` and pads `android.R.id.content` for system bars and display cutouts. It also pads for the IME, except in `adjustPan`/`adjustNothing` windows.
- **Predictive back (API 36+).** Overridden `onBackPressed()` is no longer called on back gestures. `BaseActivity` registers an `OnBackPressedCallback` that calls `handleBack()`, and the 4 overriding activities (Main, Scanner, DocumentEditor, IDCardPreview) now override `handleBack()`.
- **Context-registered receivers (API 34+).** Now `RECEIVER_NOT_EXPORTED` (see S2).
- **`MediaStore` `_data` insert (API 29+).** Saving images to Download/Scanny/Images threw `IllegalArgumentException` inside an AsyncTask, crashing the app. The insert now only runs on API < 29; the media scanner handles newer versions.
- **`PopupMenu.setForceShowIcon`** uses the public API on 29+ instead of reflecting into a non-SDK field.
- **Android 17 specifics checked:**
  - No `static final` reflection or `MessageQueue` reflection.
  - Native libraries load with `loadLibrary` from the APK, so the "safer native DCL" change is fine.
  - No widgets, SMS, Bluetooth or local-network use.
  - Certificate transparency is now on by default; this is fine for AdMob, OneSignal and the privacy URL.

---

## 3. Files touched
- **Build:** `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/wrapper/*`, `gradlew*`, `app/build.gradle`, `scanlibrary/build.gradle`, `sticker/build.gradle`, `app/proguard-rules.pro`.
- **Manifests:** all 3 modules.
- **`res/xml`:** `provider_paths.xml`, `network_security_config.xml`, `data_extraction_rules.xml` (new). `imagepicker_file_paths.xml` and the scanlibrary `provider_paths.xml` were deleted.
- **New Java:** `picker/*`, `widget/*`, `utils/PermissionUtils.java`.
- **Edited Java:** `DBHelper`, `BaseActivity`, `MainActivity`, `GroupDocumentActivity`, `PDFViewerActivity` (rewritten), `QRReaderActivity`, `PrivacyPolicyActivity`, `DocumentEditorActivity`, `IDCardPreviewActivity`, `ScannerActivity`, `AppOpenManager`, `MyApp`, `AdsUtils`, plus the receiver registration in every activity that has one. Also scanlibrary `PickImageFragment` and sticker `StickerView`.

---

## 4. For Fable: unfinished, unverified, or needs a decision

### 4.1 Build verification (blocking)
- [ ] Run `./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug` with Android SDK 37 and Build-Tools 36+ installed. **Nothing has been compiled.**
- [ ] Confirm these versions resolve. I could not reach Google Maven or JitPack, so I picked versions from documentation and memory:
  - **Confirmed to exist:**
    - AGP 9.5.0 (from developer.android.com).
    - Gradle 9.6.0 (distribution URL answers).
    - On Maven Central: OneSignal 5.1.38, bcprov-jdk15to18 1.78.1, itextpdf 5.5.13.4, glide 4.16.0, lottie 6.6.7, cameraview 2.7.2, sdp 1.1.1, zxing 3.5.3, edmodo cropper 1.0.1, photoeditor 1.1.1, roundedimageview 2.3.0.
  - **Not verified:**
    - Google Maven: appcompat 1.7.1, activity 1.10.1, core 1.16.0, constraintlayout 2.2.1, recyclerview 1.4.0, drawerlayout 1.2.0, annotation 1.9.1, lifecycle-process 2.8.7, material 1.13.0, play-services-ads 24.4.0, play-services-vision 20.1.3, androidx.test ext 1.2.1, espresso 3.6.1.
    - JitPack: `com.github.yalantis:ucrop:2.2.10`, `com.github.pqpo:SmartCropper:v2.1.3`, `com.github.chrisbanes:PhotoView:2.3.0`, `com.github.QuadFlask:colorpicker:0.0.15`.
    - Bumping the AndroidX versions to the newest is fine once you can see the repo.
- [ ] Add `distributionSha256Sum` to `gradle/wrapper/gradle-wrapper.properties`. `downloads.gradle.org` was blocked, so I could not fetch the checksum.
- [ ] Likely compile issues to watch for:
  - `EdgeToEdge.enable(this)` from Java (should be `androidx.activity.EdgeToEdge`).
  - `OneSignal.initWithContext(Context, String)` from Java.
  - Glide `SimpleTarget` (deprecated, still in 4.16).
  - `MediaStore.getPickImagesMaxLimit()` (API 33+). It is called only when `isSystemPickerAvailable()` returns true. **On API 30–32 with SDK extension R ≥ 2 this may need a guard.** Lint will flag it; if so, use a constant (for example 100) on those versions.
  - The `tools:replace="android:maxSdkVersion"` attributes on `uses-permission`.
- [ ] Jetifier: check whether any remaining library still needs it (`./gradlew checkJetifier` on AGP 8, or remove the flag and build). AGP 10 removes Jetifier.
- [ ] R8 full mode is stricter in AGP 9. Test the **release** build: iText, BouncyCastle, RichEditor's JS interface, SmartCropper (`-keep` already present) and OpenCV JNI.

### 4.2 16 KB page size (Google Play requirement for apps targeting API 35+)
- `scanlibrary/src/main/libs/arm64-v8a/*.so` has 64 KB-aligned LOAD segments, which is OK.
- `scanlibrary/src/main/libs/x86_64/*.so` (`libScanner.so`, `libopencv_java3.so`) has **4 KB alignment, which fails**. Rebuild from `scanlibrary/src/main/jni` (Android.mk) with NDK r28+ (or `-Wl,-z,max-page-size=16384`), ideally against **OpenCV 4.10+**, since OpenCV 3 is very old.
- [ ] Check the native libraries in SmartCropper (`libsmart_cropper.so`) and uCrop the same way. Use `zipalign -c -P 16 -v 4 app.apk` and `readelf -lW` on the arm64-v8a and x86_64 libs inside the built APK.

### 4.3 Functional QA on a device (Android 13, 14, 15, 16 and 17, plus a tablet or foldable)
- [ ] **Edge-to-edge:** check every screen for content under the status bar, navigation bar or cutout, and for keyboard overlap in Note, rename and password dialogs. `setStatusBarGradiant()` and other `setStatusBarColor` calls are ignored on API 35+, so status-bar icon contrast in the forced dark theme may need `WindowInsetsControllerCompat.setAppearanceLightStatusBars`.
- [ ] **Back handling:** Main (drawer, search), Scanner (crop → filter → camera, exit dialog), DocumentEditor (tool panels), and IDCardPreview (exit dialog). Every other activity now just `finish()`es on back, so the predictive back-to-home animation is lost for them. That is acceptable, but confirm.
- [ ] **Photo picker shim** (`picker/ImagePicker.java`): check each of the 5 call sites (Main, Scanner, GroupDocument, IDCardPreview, DocumentEditor) and their `setMaxSize` / `setMultipleMode` behaviour. Images now arrive as `content://` URIs. `Image.getPath()` returns the URI string, which works with Glide; check any code that treats it as a file path.
- [ ] **Replaced widgets:** compare visually against the old libraries. Watermark density, size and opacity; signature smoothness, `isBitmapEmpty`, and transparent background in the saved PNG; ColorSeekBar look and thumb; AspectRatioLayout on the ID-card screen; the plain DrawerLayout scrim.
- [ ] **PDF viewer:** `PdfRenderer` **cannot open password-protected PDFs**; it shows a toast and closes. If preview of protected PDFs is needed, use the API 35+ `PdfRenderer(ParcelFileDescriptor, LoadParams)` with a password, or `androidx.pdf`. Large PDFs render on the UI thread; move rendering to a background thread with a bitmap cache if it stutters.
- [ ] **Large screens:** on API 37 `screenOrientation="portrait"` and fixed aspect ratios are **ignored** on sw ≥ 600dp, and there is no opt-out. The layouts are portrait-only and the camera screen especially needs testing in landscape and multi-window. Consider `configChanges` or saving state, since `Constant.*` static state may get lost on recreation.
- [ ] **Storage on Android 10:** writing to `Download/Scanny` relies on `requestLegacyExternalStorage`, which is still honoured on Android 10 devices. On 11+ the app writes its own files directly. Confirm export, share and "save to gallery" on API 29, 30 and 33+.
- [ ] **Encrypted PDF:** create one, then open it in Chrome, Adobe Reader and macOS Preview to confirm AES-256 (iText 5 + BouncyCastle 1.78) works.
- [ ] **Old databases:** previously `createDocTable()` kept spaces in table names while every other query stripped them, so folders whose names contained spaces never worked. Tables are now always created without spaces (`CREATE TABLE IF NOT EXISTS`). Upgrading users may have orphaned tables; consider a cleanup migration (bump `DATABASE_VERSION`, and do **not** use the current `onUpgrade`, which drops `alldocs`).

### 4.4 Deprecated or end-of-life components (not migrated)
- [ ] **Mobile Vision OCR** (`play-services-vision`, `ImageToTextActivity`) is deprecated. Migrate to ML Kit Text Recognition v2 (`com.google.mlkit:text-recognition`).
- [ ] **iText 5** is EOL and **AGPL-licensed**. Distributing the app under AGPL obligations, or buying a commercial licence, is a legal decision. Alternatives: `android.graphics.pdf.PdfDocument` (no encryption) or PDFBox-Android.
- [ ] Other unmaintained libraries: `photoeditor 1.1.1` (current major is 3.x, with API changes), `edmodo cropper 1.0.1` (2014), `SmartCropper` (2019), `QuadFlask colorpicker`, `richeditor-android 2.0.0`, `roundedimageview`, `cameraview` (archived; consider CameraX).
- [ ] `AsyncTask`, `ProgressDialog`, `startActivityForResult` and `Handler()` are all deprecated but still work. Migrate when convenient.
- [ ] Notifications: OneSignal 5 on API 33+ needs `POST_NOTIFICATIONS` to be requested (`OneSignal.getNotifications().requestPermission(...)`). This is not wired up, because the app ID is a placeholder.

### 4.5 Release blockers that are not code
- [ ] Replace the AdMob **test** IDs in `res/values/strings.xml` and the GAM test native ID `/6499/example/native`.
- [ ] Set the real OneSignal app ID in `MyApp.ONESIGNAL_APP_ID`.
- [ ] Add a user-messaging-platform (UMP) consent flow for AdMob in GDPR regions.
- [ ] Bump `versionCode` and `versionName` (set to 2 / 1.1).

### 4.6 Security items that need a decision
- [ ] **Data at rest (S15):** move working files from `getExternalFilesDir()` to `getFilesDir()` (internal storage), optionally encrypted. Write share-only PDFs to `getCacheDir()` (the FileProvider already exposes `cache-path`) instead of public Downloads, and delete them after sharing.
- [ ] **Notes WebView (S14):** sanitize HTML before `setHtml()` (for example with a jsoup Safelist), or store notes as plain text.
- [ ] **Screen capture:** scanned IDs and signatures are sensitive. Consider `FLAG_SECURE` on the editor, preview and ID-card screens. On API 37, `setContentCaptureEnabled(false)` no longer works.
- [ ] `Scanny.docx` in the repo root was not reviewed. It is a binary file, so check it for anything that should not be public.
