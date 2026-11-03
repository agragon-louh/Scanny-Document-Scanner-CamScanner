# Scanny: open items

Everything from the earlier hand-off note that is now done has been removed: the security
fixes S1–S15, the Android 17 upgrade notes, the build and lint verification, the 16 KB alignment
work, FLAG_SECURE, the Scanny.docx review, and the release blockers that no longer apply
(AdMob, OneSignal and UMP were removed from the app). See `SECURITY_REVIEW_2026-11-03.md` for
the full record. What is left needs a device, or is a decision for the owner.

## 1. Needs a real device (nothing has been run on Android yet)
Test on Android 10, 13, 15 and 17, plus a tablet or foldable.

- [ ] **Android 10 export:** `MediaStore.Downloads` path (`ExportStorage`). Check PDF export, share, "save to gallery", and that nothing is overwritten. Also check Android 11+ and Android 9 or older.
- [ ] **Encrypted PDF:** the PDFBox AES-256 output is unit-tested. Still open it in Chrome, Adobe Reader and macOS Preview, and test it with the **release** (R8-shrunk) build.
- [ ] **OpenCV 4 filters:** compare Magic Color, B&W, Gray and edge detection on real camera images with the previous build.
- [ ] **Privacy policy screen:** renders, links open in the browser, dark mode. Also fill in `privacy_contact_email` in `strings.xml`.
- [ ] **Edge-to-edge:** content under the status bar, navigation bar or cutout; keyboard overlap in the Note, rename and password dialogs. `setStatusBarColor` is ignored on API 35+, so status-bar icon contrast may need `WindowInsetsControllerCompat.setAppearanceLightStatusBars`.
- [ ] **Back handling:** Main (drawer, search), Scanner (crop → filter → camera, exit dialog), DocumentEditor (tool panels), IDCardPreview (exit dialog).
- [ ] **Photo picker shim** (`picker/ImagePicker.java`): the 5 call sites and their `setMaxSize` / `setMultipleMode` behaviour. `Image.getPath()` returns a `content://` URI string; check any code that treats it as a file path.
- [ ] **Replaced widgets:** compare with the old libraries: watermark, signature (including transparent background in the saved PNG), ColorSeekBar, AspectRatioLayout, plain DrawerLayout.
- [ ] **PDF viewer:** `PdfRenderer` cannot open password-protected PDFs (shows a toast and closes). Use the API 35+ `PdfRenderer(ParcelFileDescriptor, LoadParams)` with a password, or `androidx.pdf`, if previews of protected PDFs are needed. Large PDFs render on the UI thread.
- [ ] **Large screens:** on API 37, `screenOrientation="portrait"` is ignored on sw ≥ 600dp. Test landscape and multi-window; `Constant.*` static state may be lost on recreation.
- [ ] **Old databases:** folder tables whose names contained spaces were never reachable and may be orphaned. Consider a cleanup migration (bump `DATABASE_VERSION`; do not use the current `onUpgrade`, which drops `alldocs`).

## 2. Build housekeeping
- [ ] **Jetifier** is still on (`android.enableJetifier=true`) for SmartCropper, QuadFlask colorpicker, edmodo cropper and roundedimageview. Check whether it can be removed; AGP 10 removes it.
- [ ] **R8 resource shrinking:** `android.r8.optimizedResourceShrinking=false` and `nonFinalResIds=false` stay until the `switch (R.id.x)` code is converted to `if/else`.

## 3. Deprecated or end-of-life components (not migrated)
- [ ] **Mobile Vision OCR** (`play-services-vision`, `ImageToTextActivity`) is deprecated. Migrate to ML Kit Text Recognition v2.
- [ ] **iText 5** is end-of-life and **AGPL-licensed**. Distributing the app under AGPL obligations, or buying a commercial licence, is a legal decision. PDFBox-Android (Apache 2.0) is already a dependency and could replace it for page rendering too.
- [ ] Unmaintained libraries: `photoeditor 1.1.1`, `edmodo cropper`, `SmartCropper`, `QuadFlask colorpicker`, `richeditor-android`, `roundedimageview`, `cameraview` (archived; consider CameraX).
- [ ] `AsyncTask`, `ProgressDialog`, `startActivityForResult` and `Handler()` are deprecated but still work.

## 4. Owner decisions
- Git history was left as is (N10 in the security review): `Scanny-debug.apk` is still in old commits. It holds no secrets, and history was not rewritten, by decision on 3 Nov 2026.
