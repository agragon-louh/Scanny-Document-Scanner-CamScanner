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

- [ ] `ScanImageOps.deskew(Mat)`: threshold to an ink mask, estimate the dominant text angle
      (`minAreaRect` on the mask, Hough lines as a cross-check), clamp to ±15°, rotate with
      `warpAffine`, trim the border. Unit test with a synthetic rotated text image.
- [ ] Fix the output-size formula in `ScanImageOps.warp` (use real edge lengths, keep the aspect
      ratio of the longer edges).
- [ ] `DocumentStraightener` in the app: SmartCropper detection, fallback to
      `ScanImageOps.findCorners` when SmartCropper returns the full frame, warp, deskew. Runs on a
      background thread (not `AsyncTask`).
- [ ] Call it from `ScannerActivity.onPictureTaken` and from gallery import. The crop screen opens
      with the result applied; "full crop" restores the original.
- [ ] Settings toggle: Auto-straighten on/off.

Done when: 20 test photos (tilted, rotated, on dark and light desks) all come out within 1° of
upright and the user never needs the crop screen for a plain A4 page.

## Phase 2: enhancement filters (1 to 2 weeks)

Goal: the "Magic Color" result looks like a photocopy, not a photo.

- [ ] Background division: estimate the paper background with a large median or morphological
      close, divide the image by it, normalise. Removes shadows and uneven lighting.
- [ ] Adaptive threshold (`adaptiveThreshold`, Gaussian, block size tuned to image size) for the
      B&W mode instead of global Otsu.
- [ ] Unsharp mask on text after whitening.
- [ ] Colour-preserving "magic" mode: whiten the background, keep stamps and highlights in colour
      (saturation-weighted blend).
- [ ] Noise removal (`fastNlMeansDenoising` on low-light scans, gated on measured noise).
- [ ] Rewrite `ColorFilter` / `CurrentFilterActivity` previews to run on downscaled thumbnails so
      switching filters is instant; full-size filter only on save.

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
