# Scan test set

Real photos used to measure auto-straighten. Nothing here is packaged into the APK.

`RealPhotoBenchmarkTest` (in `scanlibrary`) runs every annotated photo in `pages/` through
`ScanImageOps.straighten` and reports, for each photo:

- whether a page was found,
- corner error: the largest distance from a detected corner to the true corner, as a share of the
  page diagonal,
- residual tilt: how far the true page edges are from horizontal or vertical after straightening.

A photo passes when the residual tilt is 1 degree or less and every corner is within 2%. The test
fails when fewer than 95% of the photos pass. It is skipped while `pages/` is empty.

```
./gradlew :scanlibrary:testDebugUnitTest --tests com.scanlibrary.RealPhotoBenchmarkTest -i
```

## Adding a photo

1. Put the photo in `pages/`, e.g. `pages/a4-dark-desk-01.jpg`. Use what the camera saves; the
   app decodes camera frames at half size, but any size works.
2. Next to it, add `pages/a4-dark-desk-01.txt` with the four page corners in pixels, in the order
   top-left, top-right, bottom-right, bottom-left (as the page reads, not as the photo is rotated):

   ```
   # x y
   412 300
   2610 355
   2700 3420
   350 3380
   ```

   Coordinates are in the photo as displayed, after EXIF rotation. Any image editor that shows
   cursor coordinates works.

## What to cover

The roadmap asks for 50 photos: receipts, A4 printouts, ID cards, book spreads, dark desks, light
desks, shadows, hands in frame, and tilted (up to about 15 degrees) and rotated pages. Pages
cut off by the frame are out of scope for phase 1 (they are left to the crop screen).
