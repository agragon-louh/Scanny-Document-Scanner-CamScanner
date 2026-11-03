# Scanny roadmap: reaching CamScanner-level scanning

Written 3 Nov 2026. Ordered by what moves scan quality most per unit of work. Each phase ships on
its own; later phases build on earlier ones. Open device-testing and housekeeping items live in
`FABLE_REVIEW.md` and are not repeated here.

## Where the app is today

| Area | Scanny today | CamScanner |
|---|---|---|
| Corner detection | SmartCropper (small TFLite model + native), after capture only | Trained edge model, live in viewfinder, auto-capture |
| Perspective warp | SmartCropper `crop()` on the crop screen, user must confirm | Automatic, with manual adjust |
| Deskew | None | Yes |
| Curved pages | None | Mesh unwarp for books |
| Enhancement | Fixed contrast stretch, Otsu B&W, gray | Adaptive background whitening, shadow removal, sharpening |
| OCR | Mobile Vision (deprecated) | On-device + cloud OCR, searchable PDF |
| Batch | Multi-page documents | Batch mode with auto-capture per page |
| Cloud | None | Sync, share links, collaboration |

Everything in phases 1 to 3 uses OpenCV 4, which `scanlibrary` already depends on. No new native
libraries are needed until phase 4.

## Phase 1: auto-straighten (1 to 2 weeks)

Goal: a captured page comes out flat, upright and cropped without the user touching the crop screen.

- [x] `ScanImageOps.deskew(Mat)`: ink mask (inverted Otsu), projection-profile angle search
      (coarse 1° then 0.25° refine, clamped to ±15°), `warpAffine` with edge replication instead of
      trimming. Skips blank/photo-like pages and skews under 0.3°. Unit tests with synthetic rotated
      text pages.
- [x] Fix the output-size formula in `ScanImageOps.warp` (use real edge lengths, keep the aspect
      ratio of the longer edges).
- [x] `DocumentStraightener` in the app: SmartCropper detection, fallback to the OpenCV detector
      when SmartCropper returns the full frame, warp, deskew. Runs on a background thread (not
      `AsyncTask`).
- [x] Call it from `ScannerActivity.onPictureTaken` and from gallery import. The crop screen opens
      with the result applied; "full crop" restores the original.
- [x] Settings toggle: Auto-straighten on/off (drawer row, default on).
- [x] Straighten pipeline moved into `ScanImageOps.straighten` (JVM-testable): SmartCropper's quad
      is a hint, checked by `isPlausiblePage` (convex, >= 20% of frame, corners 66-114 degrees, no
      edge on the frame border); otherwise `detectPage` runs. The warp is `ScanImageOps.warp`.
- [x] `detectPage` replaces the native-port contour search: 500 px luminance copy, grey closing to
      erase text and pens, Canny at two thresholds plus Otsu, convex hull before polygon fitting.
      About 30x faster than the old full-resolution search (desktop: 9 vs 266 ms), and finds pages the old one missed
      when clutter crosses the page edge. (The old second pass thresholded at 255 and never fired.)
- [x] Crop screen opens with the whole straightened page selected (SmartCropper's auto-scan no
      longer re-crops inside it); the full-crop button first undoes the straighten.
- [x] `StraightenPipelineTest`: 40 synthetic phone photos (dark, light, near-white desk with
      shadow, clutter over the page edges; +/-15 degree tilt plus keystone), residual tilt
      measured exactly from the ground-truth geometry. All within 1 degree (worst 0.63); a
      400-photo sweep at camera resolution also passed (worst 0.74, mean 0.23).
- [x] `RealPhotoBenchmarkTest` + `testdata/README.md`: runs annotated real photos from
      `testdata/pages/` (skipped while empty).

Status: implemented and passing on the JVM. Not yet built with Gradle or run on a device: the
environment this was written in has no access to the Android SDK or Google Maven. `scanlibrary`
and `DocumentStraightener` were compiled against the Android 17 framework classes; the activity
changes were reviewed but not compiled. Open: `./gradlew :scanlibrary:testDebugUnitTest
:app:assembleDebug`, then the on-device check below with real photos in `testdata/pages/`.

Done when: 20 test photos (tilted, rotated, on dark and light desks) all come out within 1° of
upright and the user never needs the crop screen for a plain A4 page.

## Phase 2: enhancement filters (1 to 2 weeks)

Goal: the "Magic Color" result looks like a photocopy, not a photo.

All in `ScanFilters` (scanlibrary), plain OpenCV, unit-tested on the JVM.

- [x] Background division: paper brightness estimated on a 256 px copy (grey close wider than
      text strokes, median, light Gaussian), image divided by it. Removes shadows, uneven light and
      the colour cast of the light. Dark printed areas larger than the kernel (photos, banners) are
      detected (background below 40% of paper level) and inpainted from the surrounding paper
      first, so they are not washed out. The white point sits 3 noise-sigmas below paper level.
- [x] B&W: Gaussian adaptive threshold (block ~1/40 of the page) on the whitened luminance, with
      the margin C growing with the remaining noise, OR a global threshold just below white (safe
      after division; keeps wide and faint strokes solid instead of hollow).
- [x] Unsharp mask after whitening (radius scales with image size) in Magic Color and Gray.
- [x] Colour-preserving Magic Color: neutral pixels take their luminance (crisp, no colour
      fringes), coloured ones (stamps, highlighter, pen) keep their colour; blend weight from chroma
      (max - min channel; HSV saturation is unstable for dark ink).
- [x] Noise removal: noise measured on paper blocks (blur residual, robust to JPEG); above 4 levels,
      non-local means on luminance and a median on chroma.
- [x] `CurrentFilterActivity` previews run on a <=1600 px copy; the selected filter is applied to the
      full page once, while saving. The editor's filter strip (`ColorFilterAdapter`) renders cached
      256 px thumbnails instead of 12 full-size bitmaps per refresh, and `ColorFilter` releases its
      RenderScript objects. The crop screen still filters at full size (it crops the filtered
      bitmap).
- [x] `ScanFiltersTest` + `DegradedPage`: synthetic page with text, heading, red stamp,
      highlighter, blue signature and a dark photo, photographed with a soft shadow, fall-off, warm
      cast, noise and JPEG; scored against its ground-truth masks. Normal and low-light pages pass
      (paper >= 250 including in the shadow, text <= 40, colours kept, photo stays dark, B&W paper
      and text errors < 0.1%); a 60-page sweep with random exposure, shadow, cast, noise and JPEG
      quality also passes. The old filters fail the same page (Otsu turns the shadow black).

Status: implemented and passing on the JVM; app changes compiled where possible (DocumentFilter,
ColorFilter) and parse-checked otherwise. Not built with Gradle or run on a device (no Android SDK
or Google Maven access where this was written). Full-size Magic Color takes ~0.2 s on a desktop
(~0.4-0.7 s with denoise); expect several times that on a phone.

Known limits: a shadow darker than 40% of the paper level is treated as print and not removed;
a uniformly coloured sheet (sticky note) comes out white.

Done when: side-by-side with CamScanner on the same five receipts and five printed pages, a
reviewer cannot pick which is which more than 6 times out of 10.

## Phase 3: live detection and auto-capture (2 to 3 weeks)

Goal: the viewfinder shows the page outline and takes the photo by itself.

- [ ] CameraView `FrameProcessor`: run detection on every Nth preview frame at 320px wide.
      SmartCropper first; if too slow, `ScanImageOps.findCorners` on the downscaled frame.
- [ ] Overlay view drawing the quad, green when stable, with a fill pulse on capture.
- [ ] Stability logic: capture when the quad has moved less than 2% for 600 ms and covers more than
      25% of the frame. Debounce after capture.
- [ ] Batch mode: auto-capture keeps going, each page is straightened in the background, a counter
      shows pages captured, one tap finishes the document.
- [ ] Map preview-frame corners to the full-resolution photo so the phase 1 warp reuses them
      instead of re-detecting.
- [ ] Migrate from the archived `cameraview` library to CameraX while touching this code
      (ImageAnalysis use case is a direct fit).

Done when: a page held steady for under a second is captured with its quad already found, and a
10-page batch takes under 30 s.

## Phase 4: better detection model (2 to 4 weeks)

Goal: find the page on cluttered desks, low contrast, hands in frame, a corner cut off.

- [ ] Replace SmartCropper's model with a segmentation model (DocUNet / HED-style edge model or a
      small U-Net) exported to TFLite with 16 KB-aligned native libs. Candidates: `DocTR` document
      segmentation, `MIDV` trained edge models, or train on `SmartDoc 2015` + own captures.
- [ ] Post-process the mask: largest contour, `approxPolyDP`, corner refinement with sub-pixel
      line fitting along each edge.
- [ ] Partial page: when a corner lies outside the frame, extrapolate from the two visible edges
      and warn the user.
- [ ] Keep SmartCropper and OpenCV as fallbacks; pick by confidence score.
- [ ] Drop `SmartCropper` and Jetifier once the new model is in.

Done when: detection succeeds on 95% of a 200-image set with mixed backgrounds, versus the
SmartCropper baseline measured first.

## Phase 5: curved-page unwarp for Book mode (3 to 5 weeks, research-grade)

Goal: book spreads come out flat, like CamScanner's book mode.

- [ ] Start with a geometric approach: detect the top and bottom text-line curves, fit
      polynomials, build a mesh, `remap` to flatten. Works for single-axis curl, which covers
      most books.
- [ ] Split a spread into two pages at the spine (vertical intensity valley).
- [ ] If the geometric result is not good enough: a learned unwarp model (DocUNet, DewarpNet,
      DocTr) in TFLite. These are heavy (100 MB+); ship as an on-demand download.

Done when: text lines in a scanned paperback spread are straight to within 2 px over the page width.

## Phase 6: OCR and searchable PDF (2 weeks)

- [ ] Migrate `ImageToTextActivity` from Mobile Vision to ML Kit Text Recognition v2 (on device,
      Latin + Devanagari + CJK script packs).
- [ ] Searchable PDF: write an invisible text layer under each page image using PDFBox (already
      a dependency). This also starts the move off AGPL iText 5.
- [ ] Full-text search in `MainActivity` over the recognised text, stored in `DBHelper`.
- [ ] Auto-title documents from the first line of OCR text.

## Phase 7: product polish (ongoing)

- [ ] Auto-rotate pages by text orientation (ML Kit returns it; fall back to projection profile).
- [ ] Smart file naming, tags, folders with drag and drop (folders exist; drag and drop does not).
- [ ] Export quality presets (file size vs. quality), A4 / Letter / Auto page sizes.
- [ ] Share as link: needs a backend and accounts. Out of scope until phases 1 to 6 ship.
- [ ] Replace `AsyncTask`, `ProgressDialog` and `startActivityForResult` as each screen is
      touched; CameraX in phase 3 is the first.

## Measuring progress

Build a fixed test set before phase 1: 50 photos covering receipts, A4 printouts, ID cards, book
spreads, dark desks, shadows, and hands in frame. Keep it in a `testdata/` folder outside the
APK and add a JVM test that runs detection and reports success rate and mean corner error. Every
phase above is judged against that set, not by eye.

## Suggested order and effort

| Phase | Effort | Impact on "feels like CamScanner" |
|---|---|---|
| 1 Auto-straighten | 1 to 2 weeks | High: the core complaint |
| 2 Enhancement | 1 to 2 weeks | High: visible in every scan |
| 3 Live detection | 2 to 3 weeks | High: the capture experience |
| 6 OCR / searchable PDF | 2 weeks | Medium: also removes two deprecated libraries |
| 4 Detection model | 2 to 4 weeks | Medium: fixes the hard 10% of photos |
| 5 Curved unwarp | 3 to 5 weeks | Medium: only Book mode |
| 7 Polish | ongoing | Low each, adds up |

Phases 1, 2 and 6 are independent and can run in parallel. Phase 3 should land before 4 so the
model swap plugs into the live pipeline once.
