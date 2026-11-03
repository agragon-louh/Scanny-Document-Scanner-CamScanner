package com.scanlibrary;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Book mode: split a photographed two-page spread at the spine and flatten each page's curl.
 *
 * <p>Input is an upright spread (spine vertical, text lines roughly horizontal), RGBA.</p>
 *
 * <h3>Curl model</h3>
 * A book page bends along one axis only: its height above the desk {@code z} depends on the
 * distance from the spine, not on the height on the page. Seen from above by a perspective camera,
 * a lifted column of the page is closer and so magnified, by a factor that depends on that column
 * only. A text line at height {@code Y} on the flat page therefore appears at
 * <pre>    y(x) = a(x) + b(x) * Y</pre>
 * ({@code a} absorbs the magnification about the optical centre). Text lines are detected and
 * fitted with polynomials; {@code a} and {@code b} are fitted per column across all lines and
 * smoothed; then every column is remapped so the lines become straight and horizontal, normalised
 * where the page is flattest.
 *
 * <p>The same magnification also squashes the page horizontally near the spine (the surface tilts
 * away). From {@code b(x)} and an assumed focal length the page's lift {@code z(x)} follows, and with
 * it the true distance along the surface; columns are resampled by that arc length.</p>
 *
 * <p>Plain OpenCV only, so it is unit-tested on the JVM (see {@code BookScanTest}).</p>
 */
public final class BookScan {

    private BookScan() {
    }

    /** Fewer detected text lines than this: the page is left as it is. */
    static final int MIN_LINES = 4;
    /** Assumed focal length as a share of the photo's longer side (typical phone main camera). */
    public static final double DEFAULT_FOCAL_FACTOR = 0.8;
    /** Largest horizontal stretch applied when undoing the squash near the spine. */
    private static final double MAX_STRETCH = 3.0;

    // ------------------------------------------------------------------------------------------
    // Spread -> pages

    /**
     * Splits an upright spread at the spine and flattens both pages.
     *
     * @param focalPx focal length in pixels of the photo the spread came from, or {@code <= 0} for
     *                {@link #DEFAULT_FOCAL_FACTOR} times the spread's longer side
     * @return {@code [left, right]} pages, RGBA; each flattened when {@code dewarp} and enough text
     * was found, otherwise just cropped
     */
    public static Mat[] splitSpread(Mat rgba, boolean dewarp, double focalPx) {
        int spine = findSpine(rgba);
        Mat left = rgba.submat(new Rect(0, 0, spine, rgba.rows())).clone();
        Mat right = rgba.submat(new Rect(spine, 0, rgba.cols() - spine, rgba.rows())).clone();
        if (!dewarp) {
            return new Mat[]{left, right};
        }
        double f = focalPx > 0 ? focalPx : DEFAULT_FOCAL_FACTOR * Math.max(rgba.cols(), rgba.rows());
        // The optical centre is the centre of the spread photo; in page coordinates it sits at
        // (cols/2 - offset, rows/2).
        double cy = rgba.rows() / 2.0;
        double cxLeft = rgba.cols() / 2.0;
        double cxRight = rgba.cols() / 2.0 - spine;
        Dewarped l = dewarpPage(left, false, f, cxLeft, cy);
        Dewarped r = dewarpPage(right, true, f, cxRight, cy);
        Mat[] out = {l.image != null ? l.image : left, r.image != null ? r.image : right};
        if (l.image != null) {
            left.release();
        }
        if (r.image != null) {
            right.release();
        }
        return out;
    }

    /**
     * {@link #splitSpread} for a spread as Book mode captures it: the phone upright and the book
     * turned a quarter turn clockwise (the viewfinder guide shows page A on top, page B below, both
     * rotated), so the spine runs across the middle of the photo. The photo is turned back a quarter
     * turn anticlockwise first, which puts page A on the left.
     */
    public static Mat[] splitCaptured(Mat captured, boolean dewarp, double focalPx) {
        Mat upright = new Mat();
        Core.rotate(captured, upright, Core.ROTATE_90_COUNTERCLOCKWISE);
        try {
            return splitSpread(upright, dewarp, focalPx);
        } finally {
            upright.release();
        }
    }

    /**
     * Column of the spine in an upright spread. The gutter is the widest text-free band near the
     * middle; within it the spine is the darkest column of the paper background (the gutter
     * shadow), with a small pull towards the band's centre when there is no shadow. Falls back to
     * the darkest background column, then to the middle.
     */
    public static int findSpine(Mat rgba) {
        int w = rgba.cols();
        int h = rgba.rows();
        if (w < 16 || h < 16) {
            return w / 2;
        }
        double scale = Math.min(1.0, 600.0 / w);
        Mat small = new Mat();
        Imgproc.resize(rgba, small, new Size(Math.max(16, Math.round(w * scale)), Math.max(16, Math.round(h * scale))), 0, 0, Imgproc.INTER_AREA);
        Mat lum = new Mat();
        Imgproc.cvtColor(small, lum, small.channels() == 4 ? Imgproc.COLOR_RGBA2GRAY : Imgproc.COLOR_RGB2GRAY);
        small.release();
        int sw = lum.cols();
        int sh = lum.rows();
        Mat bg = ScanFilters.estimateBackground(lum);
        // Ink = pixels much darker than their brightest neighbour a few pixels away (black-hat).
        // Text strokes respond strongly; the broad gutter shadow does not (an adaptive threshold
        // marks its gradient as ink and hides the gutter).
        Mat ink = new Mat();
        int hat = odd(Math.max(5, sw / 70));
        Imgproc.morphologyEx(lum, ink, Imgproc.MORPH_BLACKHAT, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(hat, hat)));
        Imgproc.threshold(ink, ink, 35, 1, Imgproc.THRESH_BINARY);

        int r0 = (int) (0.15 * sh);
        int r1 = (int) (0.85 * sh);
        // Paper brightness per column. Inside the text-free gutter the raw luminance is paper; the
        // background estimate (a morphological close) would fill in the narrow gutter shadow.
        double[] raw = columnMeans(lum, r0, r1);
        double[] paper = columnMeans(bg, r0, r1);
        double[] density = columnMeans(ink, r0, r1);
        bg.release();
        ink.release();
        lum.release();
        int k = Math.max(1, sw / 100);
        raw = boxSmooth(raw, Math.max(1, k / 2));
        paper = boxSmooth(paper, k);
        density = boxSmooth(density, k);

        int lo = (int) (0.3 * sw);
        int hi = (int) (0.7 * sw);
        double[] inner = Arrays.copyOfRange(density, (int) (0.1 * sw), (int) (0.9 * sw));
        Arrays.sort(inner);
        double typical = inner[(int) (inner.length * 0.75)]; // density of a text column
        double gapThreshold = Math.max(0.004, 0.2 * typical);
        // Longest run of text-free columns in the middle band. The dark line where the pages meet
        // registers as "ink" too; short ink runs between free columns are bridged so it does not
        // split the gutter in two.
        boolean[] free = new boolean[sw];
        for (int x = lo; x < hi; x++) {
            free[x] = density[x] < gapThreshold;
        }
        int bridge = Math.max(2, (int) (0.03 * sw));
        for (int x = lo; x < hi; x++) {
            if (!free[x] && x > lo && free[x - 1]) {
                int e = x;
                while (e < hi && !free[e]) {
                    e++;
                }
                if (e < hi && e - x <= bridge) {
                    for (int i = x; i < e; i++) {
                        free[i] = true;
                    }
                }
                x = e - 1;
            }
        }
        int bestStart = -1;
        int bestLen = 0;
        for (int x = lo; x < hi; x++) {
            if (free[x]) {
                int s = x;
                while (x < hi && free[x]) {
                    x++;
                }
                if (x - s > bestLen) {
                    bestLen = x - s;
                    bestStart = s;
                }
            }
        }
        int spine;
        if (bestLen >= Math.max(2, sw / 100)) {
            double centre = bestStart + bestLen / 2.0;
            // Look at the inner part of the gutter only: at its edges blurred text darkens the paper.
            int innerFrom = bestStart + (int) (0.15 * bestLen);
            int innerTo = bestStart + bestLen - (int) (0.15 * bestLen);
            double maxPaper = 0;
            double minPaper = Double.MAX_VALUE;
            for (int x = innerFrom; x < innerTo; x++) {
                maxPaper = Math.max(maxPaper, raw[x]);
                minPaper = Math.min(minPaper, raw[x]);
            }
            // No real shadow in the gutter: its darkest column is noise, take its centre.
            boolean shadowed = maxPaper > 0 && (maxPaper - minPaper) / maxPaper >= 0.03;
            double best = Double.MAX_VALUE;
            spine = (int) centre;
            for (int x = innerFrom; shadowed && x < innerTo; x++) {
                double score = raw[x] / Math.max(1, maxPaper) + 0.03 * Math.abs(x - centre) / (bestLen / 2.0);
                if (score < best) {
                    best = score;
                    spine = x;
                }
            }
        } else {
            spine = sw / 2;
            double best = Double.MAX_VALUE;
            double median = median(Arrays.copyOfRange(paper, lo, hi));
            for (int x = lo; x < hi; x++) {
                if (paper[x] < best) {
                    best = paper[x];
                    spine = x;
                }
            }
            if (best > 0.95 * median) {
                spine = sw / 2; // no clear valley either
            }
        }
        int result = (int) Math.round(spine / scale);
        return Math.max(1, Math.min(w - 1, result));
    }

    // ------------------------------------------------------------------------------------------
    // Page flattening

    /** Result of {@link #dewarpPage}. */
    public static final class Dewarped {
        /** The flattened page, or null when nothing was changed (too little text, no curl). */
        public final Mat image;
        /** Number of text lines the model was fitted on. */
        public final int lines;
        /**
         * For every output column: the source column it was sampled from, and the vertical model
         * {@code y_src = a + b * y_out}. Null when {@link #image} is null.
         */
        public final double[] srcX;
        public final double[] a;
        public final double[] b;

        Dewarped(Mat image, int lines, double[] srcX, double[] a, double[] b) {
            this.image = image;
            this.lines = lines;
            this.srcX = srcX;
            this.a = a;
            this.b = b;
        }
    }

    /**
     * Flattens the curl of one page.
     *
     * @param spineOnLeft true for a right-hand page (spine at its left edge)
     * @param focalPx     camera focal length in pixels (see {@link #splitSpread})
     * @param cx          optical centre x in this page's coordinates (may lie outside the page)
     * @param cy          optical centre y
     */
    public static Dewarped dewarpPage(Mat rgba, boolean spineOnLeft, double focalPx, double cx, double cy) {
        int w = rgba.cols();
        int h = rgba.rows();
        if (w < 32 || h < 32) {
            return new Dewarped(null, 0, null, null, null);
        }
        int step = Math.max(2, w / 200);
        int bins = (w + step - 1) / step;
        // Centre line of every text line, per column bin (NaN where the line is absent).
        List<double[]> tracks = detectTextLines(rgba, step);
        if (tracks.size() < MIN_LINES) {
            return new Dewarped(null, tracks.size(), null, null, null);
        }
        int[] coverage = new int[bins];
        for (double[] t : tracks) {
            for (int j = 0; j < bins; j++) {
                if (!Double.isNaN(t[j])) {
                    coverage[j]++;
                }
            }
        }
        int ref = 0;
        for (int j = 1; j < bins; j++) {
            if (coverage[j] > coverage[ref]) {
                ref = j;
            }
        }
        // Per column bin: y = a + b * Y, with Y the line's height at the reference bin. The scale b
        // is only identifiable where the lines covering a bin span a good part of the page height;
        // elsewhere (ragged line ends, a short last paragraph) only the offset a is fitted, with b
        // taken from the smoothed fit of the well-covered bins.
        double yMinAll = Double.MAX_VALUE;
        double yMaxAll = -Double.MAX_VALUE;
        for (double[] t : tracks) {
            if (!Double.isNaN(t[ref])) {
                yMinAll = Math.min(yMinAll, t[ref]);
                yMaxAll = Math.max(yMaxAll, t[ref]);
            }
        }
        double minSpread = 0.3 * (yMaxAll - yMinAll);
        double[] aBin = new double[bins];
        double[] bBin = new double[bins];
        double[] nBin = new double[bins];
        int fitted = 0;
        for (int j = 0; j < bins; j++) {
            double sY = 0;
            double sy = 0;
            double sYY = 0;
            double sYy = 0;
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            int n = 0;
            for (double[] t : tracks) {
                double Y = t[ref];
                double y = t[j];
                if (Double.isNaN(Y) || Double.isNaN(y)) {
                    continue;
                }
                n++;
                sY += Y;
                sy += y;
                sYY += Y * Y;
                sYy += Y * y;
                lo = Math.min(lo, Y);
                hi = Math.max(hi, Y);
            }
            double det = n * sYY - sY * sY;
            if (n < 3 || hi - lo < minSpread || Math.abs(det) < 1e-6) {
                aBin[j] = Double.NaN;
                bBin[j] = Double.NaN;
                continue;
            }
            bBin[j] = (n * sYy - sY * sy) / det;
            aBin[j] = (sy - bBin[j] * sY) / n;
            nBin[j] = n;
            fitted++;
        }
        if (fitted < 8) {
            return new Dewarped(null, tracks.size(), null, null, null);
        }
        // Smooth b along x with a local quadratic (follows the steep curl at the spine, which a
        // global polynomial cannot), extend linearly beyond the well-covered bins.
        int radius = Math.max(3, bins / 30);
        double[] bS = localQuadratic(bBin, nBin, radius);
        int firstB = -1;
        int lastB = -1;
        for (int j = 0; j < bins; j++) {
            if (!Double.isNaN(bS[j])) {
                if (firstB < 0) {
                    firstB = j;
                }
                lastB = j;
            }
        }
        extendLinearly(bS, firstB, lastB, radius);
        // Offset a in every bin any line covers, given b: mean of y - b * Y.
        double[] aRaw = new double[bins];
        double[] aW = new double[bins];
        for (int j = 0; j < bins; j++) {
            double sum = 0;
            int n = 0;
            for (double[] t : tracks) {
                if (!Double.isNaN(t[ref]) && !Double.isNaN(t[j])) {
                    sum += t[j] - bS[j] * t[ref];
                    n++;
                }
            }
            aRaw[j] = n > 0 ? sum / n : Double.NaN;
            aW[j] = n;
        }
        double[] aS = localQuadratic(aRaw, aW, radius);
        int first = -1;
        int last = -1;
        for (int j = 0; j < bins; j++) {
            if (!Double.isNaN(aS[j])) {
                if (first < 0) {
                    first = j;
                }
                last = j;
            }
        }
        extendLinearly(aS, first, last, radius);

        // Normalise where the page is flattest: smallest change of a and b, in the outer part of
        // the covered range (the curl is at the spine).
        int span = last - first;
        int outerFrom = spineOnLeft ? first + (int) (0.3 * span) : first;
        int outerTo = spineOnLeft ? last : last - (int) (0.3 * span);
        int flat = outerFrom;
        double bestSlope = Double.MAX_VALUE;
        for (int j = Math.max(outerFrom, 1); j < Math.min(outerTo, bins - 1); j++) {
            double slope = Math.abs(aS[j + 1] - aS[j - 1]) + Math.abs(bS[j + 1] - bS[j - 1]) * h;
            if (slope < bestSlope) {
                bestSlope = slope;
                flat = j;
            }
        }
        double aF = aS[flat];
        double bF = bS[flat];
        if (!(bF > 0)) {
            return new Dewarped(null, tracks.size(), null, null, null);
        }
        // Per source column (pixel), interpolated between bin centres and normalised at the flat
        // column: y = a + b*Y, Y = (Yf - aF)/bF  =>  y = (a - b*aF/bF) + (b/bF) * Yf.
        double[] aCol = new double[w];
        double[] bCol = new double[w];
        double maxShift = 0;
        for (int x = 0; x < w; x++) {
            double jf = (x - step / 2.0) / step;
            int j0 = (int) Math.floor(jf);
            double t = jf - j0;
            int jl = Math.max(0, Math.min(bins - 1, j0));
            int jr = Math.max(0, Math.min(bins - 1, j0 + 1));
            double a = aS[jl] * (1 - t) + aS[jr] * t;
            double b = bS[jl] * (1 - t) + bS[jr] * t;
            b = Math.max(0.5 * bF, Math.min(2.0 * bF, b));
            aCol[x] = a - b * aF / bF;
            bCol[x] = b / bF;
            maxShift = Math.max(maxShift, Math.max(Math.abs(aCol[x]), Math.abs(aCol[x] + (bCol[x] - 1) * h)));
        }
        if (maxShift < 1.0) {
            return new Dewarped(null, tracks.size(), null, null, null); // flat already
        }

        // Horizontal: arc length along the surface, from the lift z = f (1 - 1/b) (perspective
        // magnification b relative to the flat part) and the de-magnified position.
        double[] srcX = arcLengthResample(bCol, focalPx, cx, flat * step + step / 2.0, spineOnLeft);
        int outW = srcX.length;
        double[] aOut = new double[outW];
        double[] bOut = new double[outW];
        for (int xo = 0; xo < outW; xo++) {
            double sx = srcX[xo];
            int x0 = (int) Math.floor(sx);
            int x1 = Math.min(w - 1, x0 + 1);
            x0 = Math.max(0, Math.min(w - 1, x0));
            double t = sx - Math.floor(sx);
            aOut[xo] = aCol[x0] * (1 - t) + aCol[x1] * t;
            bOut[xo] = bCol[x0] * (1 - t) + bCol[x1] * t;
        }
        Mat mapX = new Mat(h, outW, CvType.CV_32F);
        Mat mapY = new Mat(h, outW, CvType.CV_32F);
        float[] rowX = new float[outW];
        float[] rowY = new float[outW];
        for (int xo = 0; xo < outW; xo++) {
            rowX[xo] = (float) srcX[xo];
        }
        for (int y = 0; y < h; y++) {
            for (int xo = 0; xo < outW; xo++) {
                rowY[xo] = (float) (aOut[xo] + bOut[xo] * y);
            }
            mapX.put(y, 0, rowX);
            mapY.put(y, 0, rowY);
        }
        Mat out = new Mat();
        Imgproc.remap(rgba, out, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
        mapX.release();
        mapY.release();
        return new Dewarped(out, tracks.size(), srcX, aOut, bOut);
    }

    /**
     * Source column for every output column, so that output columns are evenly spaced along the
     * page surface between the spine and the flattest column {@code flatX}; beyond it (the outer,
     * nearly level part of the page) spacing is left as it is, where a correction would only add
     * noise from the end of the fit. Never stretches more than {@link #MAX_STRETCH}. Falls back to
     * the identity if the geometry is implausible.
     */
    static double[] arcLengthResample(double[] b, double f, double cx, double flatX, boolean spineOnLeft) {
        int w = b.length;
        double[] identity = new double[w];
        for (int x = 0; x < w; x++) {
            identity[x] = x;
        }
        if (f <= 0) {
            return identity;
        }
        // The lift comes from the slope of b times the focal length (~1000 px), so a small kink in
        // b (where its fit ends and the linear extension starts) would become a visible local
        // squash. Smooth b over ~1/80 of the page first; the curl itself is much wider.
        b = boxSmooth(b, Math.max(2, w / 80));
        // Surface point seen at column x (units: pixels of the flat part): horizontal position
        // X = cx + (x - cx) / b, lift z = f (1 - 1/b).
        double[] u = new double[w];
        double prevX = cx + (0 - cx) / b[0];
        double prevZ = f * (1 - 1 / b[0]);
        u[0] = 0;
        for (int x = 1; x < w; x++) {
            double X = cx + (x - cx) / b[x];
            double z = f * (1 - 1 / b[x]);
            boolean spineSide = spineOnLeft ? x <= flatX : x >= flatX;
            double ds = spineSide ? Math.hypot(X - prevX, z - prevZ) : 1;
            if (spineSide && X - prevX <= 0) {
                return identity; // folded: not a plausible single-axis curl
            }
            ds = Math.min(ds, MAX_STRETCH);
            u[x] = u[x - 1] + ds;
            prevX = X;
            prevZ = z;
        }
        int outW = (int) Math.round(u[w - 1]) + 1;
        if (outW < w * 0.8 || outW > w * MAX_STRETCH) {
            return identity;
        }
        double[] src = new double[outW];
        int x = 0;
        for (int xo = 0; xo < outW; xo++) {
            while (x < w - 2 && u[x + 1] < xo) {
                x++;
            }
            double span = u[x + 1] - u[x];
            double t = span > 1e-9 ? (xo - u[x]) / span : 0;
            src[xo] = x + Math.max(0, Math.min(1, t));
        }
        return src;
    }

    /**
     * Text lines of a page as centre-line tracks: for every column bin of width {@code step}, the
     * line's centre row, NaN where the line is absent. Ink is merged horizontally into line blobs,
     * long thin blobs are kept, the centre per bin is smoothed with a local quadratic fit and gaps
     * between words are interpolated. Blobs that are two merged lines or a figure (centres that
     * scatter) are dropped.
     */
    static List<double[]> detectTextLines(Mat rgba, int step) {
        int w = rgba.cols();
        int h = rgba.rows();
        Mat lum = ScanFilters.whitenedLuminance(rgba, null);
        Mat ink = new Mat();
        Imgproc.threshold(lum, ink, 170, 255, Imgproc.THRESH_BINARY_INV);
        lum.release();
        // Remove tall structures (the other page's edge, the gutter line, the desk, a finger)
        // before words are merged into lines: one next to the text would join every line into a
        // single blob. Nothing in a line of text is an eighth of the page tall.
        Mat tall = new Mat();
        Imgproc.morphologyEx(ink, tall, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, Math.max(9, h / 8))));
        Imgproc.dilate(tall, tall, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 1)));
        Core.subtract(ink, tall, ink);
        tall.release();
        int kw = odd(Math.max(9, w / 40));
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kw, 1)));
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(odd(Math.max(3, kw / 3)), 1)));
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        int n = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S);
        ink.release();
        centroids.release();

        int bins = (w + step - 1) / step;
        int[] candidate = new int[n]; // label -> candidate index + 1
        List<Integer> heights = new ArrayList<>();
        for (int l = 1; l < n; l++) {
            int bw = (int) stats.get(l, Imgproc.CC_STAT_WIDTH)[0];
            int bh = (int) stats.get(l, Imgproc.CC_STAT_HEIGHT)[0];
            int top = (int) stats.get(l, Imgproc.CC_STAT_TOP)[0];
            // Bands touching the top or bottom of the frame are desk around the page, not text:
            // their centre lies between the curved page edge and the straight frame edge.
            boolean touchesFrame = top <= 0 || top + bh >= h;
            if (!touchesFrame && bw >= 0.2 * w && bh <= 0.15 * h && bh <= 0.35 * bw) {
                heights.add(bh);
                candidate[l] = heights.size();
            }
        }
        stats.release();
        List<double[]> out = new ArrayList<>();
        int count = heights.size();
        if (count == 0) {
            labels.release();
            return out;
        }
        double[][] sumY = new double[count][bins];
        int[][] cnt = new int[count][bins];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            labels.get(y, 0, row);
            for (int x = 0; x < w; x++) {
                int c = candidate[row[x]];
                if (c != 0) {
                    sumY[c - 1][x / step] += y;
                    cnt[c - 1][x / step]++;
                }
            }
        }
        labels.release();
        // Thickness of each candidate (its largest per-bin pixel count); text lines share one, so
        // much thicker bands (a figure edge, a dark border) are dropped.
        int[] thickness = new int[count];
        for (int c = 0; c < count; c++) {
            for (int j = 0; j < bins; j++) {
                thickness[c] = Math.max(thickness[c], cnt[c][j]);
            }
        }
        int[] sortedThickness = thickness.clone();
        Arrays.sort(sortedThickness);
        int typicalThickness = sortedThickness[count / 2];
        for (int c = 0; c < count; c++) {
            int full = thickness[c];
            if (full > 2.5 * typicalThickness) {
                continue;
            }
            double[] raw = new double[bins];
            double[] wt = new double[bins];
            int pts = 0;
            for (int j = 0; j < bins; j++) {
                // Only well-filled bins (a full-height slice of the line) give a reliable centre.
                if (cnt[c][j] >= Math.max(2, full / 3)) {
                    raw[j] = sumY[c][j] / cnt[c][j];
                    wt[j] = 1;
                    pts++;
                } else {
                    raw[j] = Double.NaN;
                }
            }
            if (pts < 8) {
                continue;
            }
            // Local quadratic: a local line would flatten the track where it curves most, at the
            // line end next to the spine, where the window is one-sided.
            double[] smooth = localQuadratic(raw, wt, 4);
            // Scatter of the raw centres about the smooth track: a single line is tight.
            double ss = 0;
            int m = 0;
            int firstBin = -1;
            int lastBin = -1;
            for (int j = 0; j < bins; j++) {
                if (!Double.isNaN(raw[j]) && !Double.isNaN(smooth[j])) {
                    double d = raw[j] - smooth[j];
                    ss += d * d;
                    m++;
                    if (firstBin < 0) {
                        firstBin = j;
                    }
                    lastBin = j;
                }
            }
            if (m < 8 || Math.sqrt(ss / m) > Math.max(1.5, 0.25 * heights.get(c))) {
                continue; // two lines merged, or a figure
            }
            // Fill word gaps inside the line's extent by interpolation; NaN outside it.
            double[] track = new double[bins];
            Arrays.fill(track, Double.NaN);
            int prev = -1;
            for (int j = firstBin; j <= lastBin; j++) {
                if (!Double.isNaN(raw[j])) {
                    track[j] = smooth[j];
                    if (prev >= 0 && j - prev > 1) {
                        for (int k = prev + 1; k < j; k++) {
                            double t = (k - prev) / (double) (j - prev);
                            track[k] = track[prev] * (1 - t) + track[j] * t;
                        }
                    }
                    prev = j;
                }
            }
            out.add(track);
        }
        return out;
    }

    /** Weighted local linear fit at every index (window +/- r) over non-NaN values; NaN if too few. */
    static double[] localLinear(double[] v, double[] wt, int r) {
        return localPoly(v, wt, r, 1);
    }

    /** Weighted local quadratic fit at every index (window +/- r); NaN if too few values. */
    static double[] localQuadratic(double[] v, double[] wt, int r) {
        return localPoly(v, wt, r, 2);
    }

    private static double[] localPoly(double[] v, double[] wt, int r, int degree) {
        int n = v.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - r);
            int hi = Math.min(n - 1, i + r);
            int used = 0;
            for (int k = lo; k <= hi; k++) {
                if (!Double.isNaN(v[k]) && wt[k] > 0) {
                    used++;
                }
            }
            if (Double.isNaN(v[i]) || used < degree + 2) {
                out[i] = Double.NaN;
                continue;
            }
            double[] xs = new double[used];
            double[] ys = new double[used];
            double[] ws = new double[used];
            int u = 0;
            for (int k = lo; k <= hi; k++) {
                if (!Double.isNaN(v[k]) && wt[k] > 0) {
                    xs[u] = (k - i) / (double) (r + 1);
                    ys[u] = v[k];
                    // Tricube weights: nearby bins count most.
                    double d = Math.abs(k - i) / (double) (r + 1);
                    double tri = 1 - d * d * d;
                    ws[u] = wt[k] * tri * tri * tri;
                    u++;
                }
            }
            double[] c = polyfit(xs, ys, ws, degree);
            out[i] = c == null ? v[i] : c[0];
        }
        return out;
    }

    /** Fills NaN entries before {@code first} and after {@code last} by a straight line fitted to the ends. */
    private static void extendLinearly(double[] v, int first, int last, int radius) {
        int n = v.length;
        int k = Math.max(3, radius);
        for (int side = 0; side < 2; side++) {
            int from = side == 0 ? first : Math.max(first, last - k);
            int to = side == 0 ? Math.min(last, first + k) : last;
            double sx = 0;
            double sy = 0;
            double sxx = 0;
            double sxy = 0;
            int m = 0;
            for (int j = from; j <= to; j++) {
                if (!Double.isNaN(v[j])) {
                    sx += j;
                    sy += v[j];
                    sxx += (double) j * j;
                    sxy += j * v[j];
                    m++;
                }
            }
            double det = m * sxx - sx * sx;
            double slope = m >= 2 && Math.abs(det) > 1e-9 ? (m * sxy - sx * sy) / det : 0;
            int anchor = side == 0 ? first : last;
            if (side == 0) {
                for (int j = 0; j < first; j++) {
                    v[j] = v[anchor] + slope * (j - anchor);
                }
            } else {
                for (int j = last + 1; j < n; j++) {
                    v[j] = v[anchor] + slope * (j - anchor);
                }
            }
        }
        // Interior holes (bins with too few lines): interpolate.
        int prev = first;
        for (int j = first + 1; j <= last; j++) {
            if (!Double.isNaN(v[j])) {
                for (int k2 = prev + 1; k2 < j; k2++) {
                    double t = (k2 - prev) / (double) (j - prev);
                    v[k2] = v[prev] * (1 - t) + v[j] * t;
                }
                prev = j;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Small numeric helpers

    /** Weighted least squares via normal equations (x is expected in about [0, 1]). */
    static double[] polyfit(double[] x, double[] y, double[] wt, int degree) {
        int m = degree + 1;
        double[][] ata = new double[m][m + 1];
        int used = 0;
        for (int i = 0; i < x.length; i++) {
            if (wt[i] <= 0) {
                continue;
            }
            used++;
            double[] p = new double[m];
            p[0] = 1;
            for (int k = 1; k < m; k++) {
                p[k] = p[k - 1] * x[i];
            }
            for (int r = 0; r < m; r++) {
                for (int c = 0; c < m; c++) {
                    ata[r][c] += wt[i] * p[r] * p[c];
                }
                ata[r][m] += wt[i] * p[r] * y[i];
            }
        }
        if (used < m) {
            return null;
        }
        // Gaussian elimination with partial pivoting.
        for (int col = 0; col < m; col++) {
            int piv = col;
            for (int r = col + 1; r < m; r++) {
                if (Math.abs(ata[r][col]) > Math.abs(ata[piv][col])) {
                    piv = r;
                }
            }
            double[] tmp = ata[col];
            ata[col] = ata[piv];
            ata[piv] = tmp;
            if (Math.abs(ata[col][col]) < 1e-12) {
                return null;
            }
            for (int r = 0; r < m; r++) {
                if (r != col) {
                    double f = ata[r][col] / ata[col][col];
                    for (int c = col; c <= m; c++) {
                        ata[r][c] -= f * ata[col][c];
                    }
                }
            }
        }
        double[] coef = new double[m];
        for (int r = 0; r < m; r++) {
            coef[r] = ata[r][m] / ata[r][r];
        }
        return coef;
    }

    private static double[] columnMeans(Mat m, int r0, int r1) {
        Mat band = m.submat(r0, Math.max(r0 + 1, r1), 0, m.cols());
        Mat red = new Mat();
        Core.reduce(band, red, 0, Core.REDUCE_AVG, CvType.CV_64F);
        double[] v = new double[m.cols()];
        red.get(0, 0, v);
        red.release();
        return v;
    }

    private static double[] boxSmooth(double[] v, int radius) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            double s = 0;
            int n = 0;
            for (int j = Math.max(0, i - radius); j <= Math.min(v.length - 1, i + radius); j++) {
                s += v[j];
                n++;
            }
            out[i] = s / n;
        }
        return out;
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s.length == 0 ? 0 : s[s.length / 2];
    }

    private static int odd(int v) {
        return v % 2 == 0 ? v + 1 : v;
    }
}
