# Scanny security review (read-only), 3 Nov 2026

Scope: the current working tree (commit 1908da3 plus uncommitted changes), the built debug and
release APKs in `app/build/outputs`, the merged manifests in `app/build/intermediates`, and
online checks against the OSV vulnerability database, Maven Central, Google Maven and
services.gradle.org. No files other than this report were changed.

## 1. Verdict

The earlier hardening (FABLE_REVIEW.md) holds up: every "Fixed" item there was re-checked in
the code and, where possible, in the built release APK. No secrets, keystores or API keys are
in the tree or in git history. The remaining issues are two out-of-date libraries with
published advisories, a weak PDF password scheme, and leftover push-notification attack
surface. Nothing found is remotely exploitable without user interaction.

## 1a. Resolution (branch `fix/security-review-findings`)

| # | Status | What changed |
|---|--------|--------------|
| N1 | Fixed | jsoup 1.18.3 → 1.23.2 (no OSV advisories). |
| N2 | Fixed | bcprov 1.78.1 → 1.86; bcpkix/bcutil (pulled in by PDFBox) constrained to 1.86. No OSV advisories for any of the three. |
| N3 | Fixed | Protected PDFs are rendered by iText unencrypted into the private cache, then encrypted by `PdfProtector` (PDFBox-Android 2.0.27.0) with AES-256 **revision 6**; the temp file is deleted in a `finally`. All four password dialogs require at least 8 characters. `PdfProtectorTest` (6 JVM tests) checks V5/R6/256-bit, print-only user permissions, owner access, rejection of a missing or wrong password, and iText → PDFBox interoperability. |
| N4 | Fixed | OneSignal removed. The merged release manifest now exports only `SplashActivity` and AndroidX's `ProfileInstallReceiver` (guarded by `android.permission.DUMP`); permissions dropped from 27 to 6. |
| N5 | Fixed | `checkReleaseBuilds true`, `abortOnError true`, with `app/lint-baseline.xml` recording the 1,010 pre-existing issues. `lintRelease` passes with no new issues. The baseline's only security entries are `TrustAllX509TrustManager` inside the bcpkix jar (a BouncyCastle utility class the app never calls) and three API-level warnings in `ImagePicker` that are guarded at runtime. |
| N6 | Fixed | `requestLegacyExternalStorage` removed. Exports on Android 10 go through `MediaStore.Downloads` (`ExportStorage`); Android 9 and older use WRITE_EXTERNAL_STORAGE, now `maxSdkVersion 28`; Android 11+ writes its own files directly as before. Image exports no longer overwrite a file with the same timestamp. |
| N8 | Hardened | Added `android:fullBackupContent="false"` so pre-Android 12 devices are covered explicitly; device-to-device transfer left as designed. |
| N9 | Fixed | The five native functions of `libScanner.so` were ported to the OpenCV 4 Java API (`ScanImageOps`, same parameters and quirks; `ScanActivity` keeps its method signatures). OpenCV now comes from Maven Central (`org.opencv:opencv:4.14.0`, no OSV advisories). The checked-in OpenCV 3 SDK, `scan.cpp` and all prebuilt `.so` files (680 files, ~335 MB) are deleted. All arm64 libraries in the release APK are 16 KB-aligned. `ScanImageOpsTest` (6 JVM tests on desktop OpenCV) covers every filter and the corner detection. Cost: the release APK grew from 52 to 73 MB because `libopencv_java4.so` is larger than the old OpenCV 3 build. |
| N7 | Fixed | The in-app policy no longer loads `scanny.flycricket.io`: that page was written for another developer, carried a sponsored ad, still described AdMob and log collection, and listed admin@admin.com as the contact. An accurate policy is bundled (`res/raw/privacy_policy.html`) and shown in a WebView with JavaScript, file/content access and network loads disabled; links open in the browser. With nothing else using the network, `INTERNET` and `ACCESS_NETWORK_STATE` were removed, and no library adds them back. **Before release:** set `privacy_contact_email` in `strings.xml` (otherwise the policy points to the Play listing contact), and host the same HTML at a public URL for the Play Console. |
| N10 | Won't fix (owner decision, 3 Nov 2026) | Removing `Scanny-debug.apk` from history would mean rewriting every commit since `acb177f` and force-pushing `main`. It holds no secrets, so history is left as is. |
| N11 | No change | Superseded by N7: the remote page is no longer used. |

Not verified on a device: no emulator or phone was attached. These have only been compiled, unit-tested on the JVM, and inspected in the release APK, not run on Android:

- the Android 10 MediaStore export path;
- the R8-shrunk PDFBox encryption;
- the OpenCV 4 filters on real camera images (compare Magic Color, B&W and Gray output with the previous build);
- the bundled privacy-policy screen.

## 2. Findings

| # | Severity | Finding | Evidence | Recommendation |
|---|----------|---------|----------|----------------|
| N1 | Medium | **jsoup 1.18.3 has two open advisories**, one of them in the exact API the app uses. CVE-2026-71497: `Cleaner` with a *custom* `Safelist` can emit active markup for malformed tag names ending in a control character (fixed 1.23.1). CVE-2026-75140: `XmlTreeBuilder` memory exhaustion (fixed 1.23.2). | `app/build.gradle` (`org.jsoup:jsoup:1.18.3`); `main_utils/NoteSanitizer.java` builds a custom `Safelist` and runs `new Cleaner(SAFELIST).clean(...)`. The safelist does not allow raw-text elements (`script`, `style`, `textarea`, `title`), which the advisory names as the precondition, and note HTML only ever comes from the user's own editor, so practical impact is self-XSS at most. | Upgrade to `org.jsoup:jsoup:1.23.2` (latest on Maven Central). |
| N2 | Medium | **BouncyCastle 1.78.1 has five open advisories**: CVE-2025-14813 (GOST CTR keystream reuse), CVE-2026-8763 (name-constraints bypass), CVE-2026-0636 (LDAP injection), CVE-2026-5598 (FrodoKEM timing channel), CVE-2026-13506 (ASN.1 nesting-depth guard reset, DoS). All fixed by 1.85; latest is 1.86. | `app/build.gradle` (`org.bouncycastle:bcprov-jdk15to18:1.78.1`). BC is only used by iText for AES PDF encryption; none of the affected code paths (GOST, X.509 path validation, LDAP cert store, FrodoKEM, parsing untrusted ASN.1) are reached. Real exposure is low, but every dependency scanner will flag it. | Upgrade to `org.bouncycastle:bcprov-jdk15to18:1.86`. |
| N3 | Low | **Password-protected PDFs use iText 5's AES-256 "revision 5"** (AESV3 with a single SHA-256 key check). R5 was withdrawn from ISO 32000-2 because the single hash lets offline crackers test billions of passwords per second; iText 5 cannot write the hardened R6 scheme. The app enforces no minimum password length, so a short PIN-style password gives almost no protection to an exported ID scan. | `BaseActivity.writePdf()` calls `setEncryption(..., PdfWriter.ENCRYPTION_AES_256)`; the iText 5.5.13.4 jar's `PdfEncryption` class contains `AESV3`/`SHA-256` and no R6 code. Password dialogs in `GroupDocumentActivity`, `MainActivity`, `SavedDocumentPreviewActivity`, `SavedEditDocumentActivity` only check non-empty and match. | Short term: require a minimum length (8+) and show guidance in the dialog. Longer term: a library that writes R6 (PDFBox-Android or iText 7) if protected exports matter. |
| N4 | Low | **OneSignal SDK is dead weight that still widens the attack surface.** `MyApp` skips `OneSignal.initWithContext` because the app ID is the placeholder, but the SDK's manifest is still merged in. The merged release manifest contains 8 exported components from OneSignal/Firebase (`FCMBroadcastReceiver`, `BootUpReceiver`, `UpgradeReceiver`, `NotificationDismissReceiver`, two `NotificationOpenedActivity` variants, `NotificationOpenedActivityHMS`, `FirebaseInstanceIdReceiver`) and re-adds `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` plus 17 OEM launcher-badge permissions. The app manifest comment says these permissions were removed, which is no longer true in the merged output. The pinned version 5.1.38 is also well behind (latest 5.11.0). | `app/build/intermediates/merged_manifests/release/.../AndroidManifest.xml`; `app/build.gradle` (`com.onesignal:OneSignal:5.1.38`). | If push is not planned, remove the dependency. If it is, upgrade to 5.11.0, set the real app ID and request `POST_NOTIFICATIONS`. |
| N5 | Low | **Release lint is disabled** (`checkReleaseBuilds false`, `abortOnError false`), which turns off the security lint checks (`ExportedReceiver`, `SetJavaScriptEnabled`, `UnsafeProtectedBroadcastReceiver`, `TrustAllX509TrustManager`, ...). | `app/build.gradle` `lint {}` block. | Re-enable and fix or baseline the warnings. |
| N6 | Low | **`requestLegacyExternalStorage="true"` remains.** On Android 10 this gives the app unscoped access to all of shared storage. It is only there so `Download/Scanny` can be written with the `File` API on API 29. | `app/src/main/AndroidManifest.xml`. | Acceptable for now; `MediaStore.Downloads` on API 29+ would remove the need. |
| N7 | Info | **User exports are intentionally world-readable.** Plain and password-protected PDFs go to `Download/Scanny/` and `Download/Scanny/Download/`; "save to gallery" images go to `Download/Scanny/Images/`. Any app with media/file access can read them. Working copies and share-only files are now in internal storage/cache, so this only affects what the user explicitly exports. | `BaseActivity.createPDFfromBitmap()` / `createProtectedPDFfromBitmap()`. | Document it in the privacy policy; no code change required. |
| N8 | Info | `data_extraction_rules.xml` excludes cloud backup only; device-to-device transfer still carries all scans. The comment says this is deliberate. Transfers are encrypted end to end by the platform. | `app/src/main/res/xml/data_extraction_rules.xml`. | Keep, or add a `<device-transfer>` block if scans should never leave the device. |
| N9 | Info | Prebuilt **OpenCV 3** (`libopencv_java3.so`, 2017-era) and `libScanner.so` in `scanlibrary/src/main/libs`. OpenCV 3 has many image-codec CVEs, but the app only passes already-decoded in-memory bitmaps to it, never untrusted files, so none of the codec paths are reachable. The `x86`/`x86_64` folders are unused (ABI filter) but still in the tree. | `scanlibrary/build.gradle` jniLibs. | Rebuild on OpenCV 4.x when the 16 KB alignment work is done; delete the unused ABI folders. |
| N10 | Info | **Repository contents.** `Scanny.docx` is the template seller's setup guide; it contains the original author's personal e-mail but no credentials. `Scanny-debug.apk` was deleted in commit acb177f but is still in git history (a signed debug build of the pre-hardening app). | `git log --all --name-only`. | Nothing secret; only matters if the history is ever rewritten for size. |
| N11 | Info | The privacy-policy URL (`https://scanny.flycricket.io/privacy.html`) 302-redirects to `https://pages.flycricket.io/scanny/privacy.html`; HTTPS end to end. The page embeds Google Analytics, which cannot run because the WebView has JavaScript off. | `PrivacyPolicyActivity`. | None. |

## 3. Earlier findings re-verified (FABLE_REVIEW.md S1-S16)

| Item | Status now | How it was checked |
|------|-----------|--------------------|
| S1 SQL injection | Fixed | `DBHelper`: table names are generated (`"group <id>"`), identifiers go through `quote()`, all values are bound `?` parameters. `isValidGroupName` is enforced in both create dialogs and the rename path. |
| S2 Exported receivers | Fixed | Every `registerReceiver` uses `ContextCompat.RECEIVER_NOT_EXPORTED`; `NavigationUtils` sends with `setPackage()`. Merged manifest: the only exported app component is `SplashActivity`. |
| S3 FileProvider scope | Fixed | `provider_paths.xml` now lists `files/Documents`, `files/Signature`, the old external-files dir, `Download/Scanny/`, `Download/scanSample/` and cache. The scanlibrary provider is gone. |
| S4 Cleartext | Fixed | `usesCleartextTraffic="false"` and `network_security_config.xml` with `cleartextTrafficPermitted="false"`, system trust anchors only. |
| S5 Backup | Fixed | `allowBackup="false"` + `dataExtractionRules`. |
| S6 PDF encryption | Partly | AES-256 with a random 192-bit owner password (`SecureRandom`). See N3 for the revision-5 weakness. |
| S7 Path traversal | Fixed | `pdfFileInside()` compares canonical paths; group names reject `..`, separators and control characters. |
| S8 Logcat leakage | Fixed | Verified in the built release APK: the `onPageSelected:` / `doInBackground:` / `Make Folder to PDF` strings present in the debug dex are absent from the release dex. |
| S9/S10 Dependencies and supply chain | Partly | Gradle wrapper SHA-256 matches services.gradle.org (`bbaeb2fe...9a01`); AGP 9.5.0 exists on Google Maven; JitPack restricted to four groups; no dynamic versions. jsoup and BouncyCastle need bumps (N1, N2). iText 5.5.13.4 has no OSV entry but is end-of-life and AGPL. |
| S11 Permissions | Partly | App manifest is minimal, but OneSignal merges permissions back in (N4). `AD_ID` is successfully removed from the merged manifest. |
| S12 WebView | Fixed | JavaScript, file and content access disabled on the privacy-policy WebView. |
| S14 Note sanitizer | Fixed, library outdated | Sanitized on load and save; `onclick` restricted to `RE.setTodo('...')`; `style` with `url(`/`expression`/`@`/`\` stripped. RichEditor passes HTML through `URLEncoder.encode` into `RE.setHtml`, so no JS injection through the wrapper. See N1. |
| S15 Data at rest | Fixed | Page images and signatures in `getFilesDir()`; captures and share files in cache with 24 h cleanup; migration from the old external dir on splash. |
| S16 Ad/OneSignal IDs | Ads removed | No `ca-app-pub` strings remain; `AD_ID` permission removed. OneSignal still placeholder (N4). |
| FLAG_SECURE (was open) | Done | `blockScreenCapture()` is called in 11 screens that show scans, including Note, PDF viewer, OCR, editor and ID-card preview. |
| Build verification (was open) | Done | Debug and release APKs exist in `app/build/outputs`, ARM ABIs only; release is unsigned. |

## 4. Online checks performed

- OSV (`api.osv.dev`) for all 17 declared Maven coordinates at their pinned versions: only jsoup and BouncyCastle returned advisories.
- Maven Central `maven-metadata.xml`: latest jsoup 1.23.2, bcprov-jdk15to18 1.86, OneSignal 5.11.0, itextpdf 5.5.13.4 (final 5.x), glide 5.0.9 (app pins 4.16.0, no advisories).
- Google Maven: AGP 9.5.0 is a published release; play-services-vision 20.1.3 is the last version ever published (deprecated API, no newer release).
- services.gradle.org: Gradle 9.6.0 SHA-256 matches the wrapper's `distributionSha256Sum`.
- Privacy policy URL resolves over HTTPS (after one redirect).
