package com.scanlibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Book mode (phase 5) on synthetic open books with known geometry ({@link SyntheticSpread}).
 *
 * <p>The roadmap's bar: "text lines in a scanned paperback spread are straight to within 2 px over
 * the page width". Straightness is computed exactly: each true text line's curve in the photo is
 * pushed through the dewarp model (source column and {@code y_src = a + b * y_out} per output
 * column), and its largest deviation from a straight line is measured. The squash near the spine is
 * measured as the spread of page units per output pixel across the text.</p>
 */
public class BookScanTest {

    private static final double MAX_LINE_DEVIATION = 2.0;
    private static final double MAX_SPINE_ERROR = 0.015; // share of the spread width
    /**
     * Squash near the spine after correction (max / min page units per pixel across the text).
     * Photos in the sweep range up to ~1.4. What remains is mostly within ~20 px of the text
     * edge next to the spine, where the curl is steepest and the fit is one-sided.
     */
    private static final double MAX_SQUASH_RATIO = 1.15;
    private static final double MAX_SQUASH_RATIO_UNKNOWN_FOCAL = 1.20;
    /** The correction must never make a page noticeably worse than the photo. */
    private static final double MAX_SQUASH_WORSENING = 0.02;

    @BeforeClass
    public static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    public void flattensCurledPagesToWithinTwoPixels() {
        // Focal length known, as the app reads it from the photo's EXIF data.
        sweep(true, MAX_SQUASH_RATIO);
    }

    @Test
    public void unknownFocalLengthStillStraightensAndReducesTheSquash() {
        // No EXIF focal length: the default (0.8 x the long side) is assumed while the cameras in
        // the sweep range from 0.65 to 1.0. Lines must still be straight, and the squash near
        // the spine must be smaller than in the photo.
        sweep(false, MAX_SQUASH_RATIO_UNKNOWN_FOCAL);
    }

    private static void sweep(boolean focalKnown, double maxSquash) {
        Random rnd = new Random(5);
        List<String> failures = new ArrayList<>();
        double worstIn = 0;
        double worstOut = 0;
        double worstSquash = 0;
        double worstSquashIn = 0;
        int pages = 0;
        for (int i = 0; i < 12; i++) {
            SyntheticSpread.Params p = new SyntheticSpread.Params();
            p.lift = 70 + 90 * rnd.nextDouble();
            p.rise = 80 + 120 * rnd.nextDouble();
            p.sag = 0.5 * rnd.nextDouble();
            // The dewarp assumes 0.8; real cameras differ.
            p.focalFactor = 0.65 + 0.35 * rnd.nextDouble();
            p.shadow = 0.15 + 0.3 * rnd.nextDouble();
            p.noise = 1 + 4 * rnd.nextDouble();
            SyntheticSpread s = SyntheticSpread.create(p, 100 + i);
            int spine = BookScan.findSpine(s.image);
            String id = String.format(Locale.ROOT, "spread %d (lift %.0f rise %.0f focal %.2f)", i, p.lift, p.rise, p.focalFactor);
            if (Math.abs(spine - s.spine) > MAX_SPINE_ERROR * s.image.cols()) {
                failures.add(id + ": spine at " + spine + ", true " + s.spine);
            }
            for (boolean rightPage : new boolean[]{false, true}) {
                Mat page = rightPage
                        ? s.image.submat(new Rect(spine, 0, s.image.cols() - spine, s.image.rows())).clone()
                        : s.image.submat(new Rect(0, 0, spine, s.image.rows())).clone();
                int offset = rightPage ? spine : 0;
                double cx = s.image.cols() / 2.0 - offset;
                double f = focalKnown ? s.f : BookScan.DEFAULT_FOCAL_FACTOR * Math.max(s.image.cols(), s.image.rows());
                BookScan.Dewarped d = BookScan.dewarpPage(page, rightPage, f, cx, s.cy);
                pages++;
                double in = inputDeviation(s, rightPage);
                worstIn = Math.max(worstIn, in);
                if (d.image == null) {
                    failures.add(id + (rightPage ? " right" : " left") + ": not dewarped (" + d.lines + " lines)");
                    continue;
                }
                double out = outputDeviation(s, d, offset, rightPage);
                double squash = squashRatio(s, d, offset, rightPage);
                double squashIn = inputSquashRatio(s, rightPage);
                worstOut = Math.max(worstOut, out);
                worstSquash = Math.max(worstSquash, squash);
                if (out > MAX_LINE_DEVIATION) {
                    failures.add(String.format(Locale.ROOT, "%s %s: lines off straight by %.2f px (input %.1f)", id, rightPage ? "right" : "left", out, in));
                }
                if (squash > maxSquash || squash > squashIn + MAX_SQUASH_WORSENING) {
                    failures.add(String.format(Locale.ROOT, "%s %s: squash ratio %.3f (input %.3f)", id, rightPage ? "right" : "left", squash, squashIn));
                }
                worstSquashIn = Math.max(worstSquashIn, squashIn);
                d.image.release();
                page.release();
            }
            s.release();
        }
        System.out.printf(Locale.ROOT, "book (focal %s): %d pages, worst line deviation %.1f -> %.2f px, worst squash ratio %.3f -> %.3f%n",
                focalKnown ? "known" : "assumed", pages, worstIn, worstOut, worstSquashIn, worstSquash);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void splitSpreadReturnsTwoFlattenedPages() {
        SyntheticSpread s = SyntheticSpread.create(new SyntheticSpread.Params(), 7);
        Mat[] pages = BookScan.splitSpread(s.image, true, 0);
        assertEquals(2, pages.length);
        for (Mat p : pages) {
            assertEquals(CvType.CV_8UC4, p.type());
            assertEquals(s.image.rows(), p.rows());
            assertTrue(p.cols() > 0.4 * s.image.cols() && p.cols() < 0.65 * s.image.cols());
        }
        Mat[] raw = BookScan.splitSpread(s.image, false, 0);
        assertEquals(s.image.cols(), raw[0].cols() + raw[1].cols());
    }

    @Test
    public void capturedSpreadComesBackUprightWithTheLeftPageFirst() {
        // Book mode's viewfinder has the book turned a quarter turn clockwise (page A on top).
        SyntheticSpread s = SyntheticSpread.create(new SyntheticSpread.Params(), 13);
        Mat captured = new Mat();
        org.opencv.core.Core.rotate(s.image, captured, org.opencv.core.Core.ROTATE_90_CLOCKWISE);
        Mat[] fromCapture = BookScan.splitCaptured(captured, false, 0);
        Mat[] fromUpright = BookScan.splitSpread(s.image, false, 0);
        for (int i = 0; i < 2; i++) {
            assertEquals(fromUpright[i].size(), fromCapture[i].size());
            Mat diff = new Mat();
            org.opencv.core.Core.absdiff(fromUpright[i], fromCapture[i], diff);
            assertEquals("page " + i, 0, org.opencv.core.Core.norm(diff, org.opencv.core.Core.NORM_INF), 0);
        }
    }

    @Test
    public void spineIsFoundWithoutAGutterShadow() {
        SyntheticSpread.Params p = new SyntheticSpread.Params();
        p.shadow = 0;
        SyntheticSpread s = SyntheticSpread.create(p, 9);
        int spine = BookScan.findSpine(s.image);
        assertTrue("spine " + spine + " true " + s.spine, Math.abs(spine - s.spine) <= MAX_SPINE_ERROR * s.image.cols());
    }

    @Test
    public void flatBookIsLeftAlone() {
        SyntheticSpread.Params p = new SyntheticSpread.Params();
        p.lift = 0;
        p.sag = 0;
        SyntheticSpread s = SyntheticSpread.create(p, 11);
        Mat right = s.image.submat(new Rect(s.spine, 0, s.image.cols() - s.spine, s.image.rows())).clone();
        BookScan.Dewarped d = BookScan.dewarpPage(right, true, s.f, s.cx - s.spine, s.cy);
        // Either untouched, or a sub-pixel correction that keeps lines straight.
        if (d.image != null) {
            assertTrue(outputDeviation(s, d, s.spine, true) <= 1.0);
            assertEquals(right.cols(), d.image.cols(), 0.02 * right.cols());
        }
    }

    @Test
    public void pageWithoutTextIsLeftAlone() {
        Mat blank = new Mat(900, 600, CvType.CV_8UC4, new Scalar(240, 238, 232, 255));
        BookScan.Dewarped d = BookScan.dewarpPage(blank, true, 1000, 0, 450);
        assertNull(d.image);
        assertNotNull(BookScan.splitSpread(blank, true, 0)[0]);
    }

    @Test
    public void localQuadraticFollowsACurveAndSkipsGaps() {
        double[] v = new double[60];
        double[] w = new double[60];
        for (int i = 0; i < 60; i++) {
            v[i] = 10 + 0.5 * i + 0.02 * i * i;
            w[i] = 1;
        }
        v[20] = Double.NaN;
        double[] s = BookScan.localQuadratic(v, w, 4);
        for (int i = 0; i < 60; i++) {
            if (i == 20) {
                assertTrue(Double.isNaN(s[i]));
            } else {
                assertEquals(10 + 0.5 * i + 0.02 * i * i, s[i], 1e-6);
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private static List<double[]> lines(SyntheticSpread s, boolean rightPage) {
        return rightPage ? s.rightLines : s.leftLines;
    }

    /** Photo column belongs to the page being measured (with a pixel of margin at the spine). */
    private static boolean onPage(SyntheticSpread s, double x, boolean rightPage) {
        return rightPage ? x > s.spine + 1 : x < s.spine - 1;
    }

    /** Worst deviation from straight of the true text lines in the photo. */
    private static double inputDeviation(SyntheticSpread s, boolean rightPage) {
        double worst = 0;
        for (double[] line : lines(s, rightPage)) {
            List<double[]> pts = new ArrayList<>();
            for (int x = 0; x < s.image.cols(); x++) {
                if (!onPage(s, x, rightPage)) {
                    continue;
                }
                double pc = s.pageColumn(x);
                if (Double.isNaN(pc) || pc < line[1] || pc > line[2]) {
                    continue;
                }
                pts.add(new double[]{x, s.photoY(x, line[0])});
            }
            worst = Math.max(worst, deviationFromLine(pts));
        }
        return worst;
    }

    /** Worst deviation from straight of the true text lines after dewarping. */
    private static double outputDeviation(SyntheticSpread s, BookScan.Dewarped d, int offset, boolean rightPage) {
        double worst = 0;
        for (double[] line : lines(s, rightPage)) {
            List<double[]> pts = new ArrayList<>();
            for (int xo = 0; xo < d.srcX.length; xo++) {
                double x = d.srcX[xo] + offset;
                if (!onPage(s, x, rightPage)) {
                    continue;
                }
                double pc = s.pageColumn(x);
                if (Double.isNaN(pc) || pc < line[1] || pc > line[2]) {
                    continue;
                }
                pts.add(new double[]{xo, (s.photoY(x, line[0]) - d.a[xo]) / d.b[xo]});
            }
            worst = Math.max(worst, deviationFromLine(pts));
        }
        return worst;
    }

    /** Max / min of page units per output pixel over the text columns (1 = evenly spaced). */
    private static double squashRatio(SyntheticSpread s, BookScan.Dewarped d, int offset, boolean rightPage) {
        int margin = s.pageWidth / 10;
        double min = Double.MAX_VALUE;
        double max = 0;
        int k = 5;
        for (int xo = k; xo < d.srcX.length - k; xo += k) {
            double xa = d.srcX[xo - k] + offset;
            double xb = d.srcX[xo + k] + offset;
            if (!onPage(s, xa, rightPage) || !onPage(s, xb, rightPage)) {
                continue;
            }
            double pa = s.pageColumn(xa);
            double pb = s.pageColumn(xb);
            if (Double.isNaN(pa) || Double.isNaN(pb) || Math.min(pa, pb) < margin || Math.max(pa, pb) > s.pageWidth - margin) {
                continue;
            }
            double r = Math.abs(pb - pa) / (2.0 * k);
            min = Math.min(min, r);
            max = Math.max(max, r);
        }
        return max / min;
    }

    /** Max / min of page units per photo pixel over the text columns, before dewarping. */
    private static double inputSquashRatio(SyntheticSpread s, boolean rightPage) {
        int margin = s.pageWidth / 10;
        double min = Double.MAX_VALUE;
        double max = 0;
        int k = 5;
        for (int x = k; x < s.image.cols() - k; x += k) {
            if (!onPage(s, x - k, rightPage) || !onPage(s, x + k, rightPage)) {
                continue;
            }
            double pa = s.pageColumn(x - k);
            double pb = s.pageColumn(x + k);
            if (Double.isNaN(pa) || Double.isNaN(pb) || Math.min(pa, pb) < margin || Math.max(pa, pb) > s.pageWidth - margin) {
                continue;
            }
            double r = Math.abs(pb - pa) / (2.0 * k);
            min = Math.min(min, r);
            max = Math.max(max, r);
        }
        return max / min;
    }

    /** Largest distance of the points from their least-squares straight line. */
    private static double deviationFromLine(List<double[]> pts) {
        int n = pts.size();
        if (n < 3) {
            return 0;
        }
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (double[] p : pts) {
            sx += p[0];
            sy += p[1];
            sxx += p[0] * p[0];
            sxy += p[0] * p[1];
        }
        double b = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        double a = (sy - b * sx) / n;
        double worst = 0;
        for (double[] p : pts) {
            worst = Math.max(worst, Math.abs(p[1] - a - b * p[0]));
        }
        return worst;
    }
}
