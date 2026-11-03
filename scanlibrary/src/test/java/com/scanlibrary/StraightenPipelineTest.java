package com.scanlibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end check of auto-straighten ({@link ScanImageOps#straighten}) on synthetic phone photos.
 *
 * <p>The roadmap's phase 1 bar is "every page comes out within 1 degree of upright". Because the
 * scenes keep the exact page geometry, the residual tilt is computed exactly: the page's own
 * corners are pushed through the ground-truth placement, the detected warp and the deskew
 * rotation, and the angle of each resulting page edge is measured against the axes.</p>
 *
 * <p>This exercises the OpenCV detector. SmartCropper (used first on the device) is a native
 * TFLite model and cannot run on the JVM.</p>
 */
public class StraightenPipelineTest {

    private static final double MAX_RESIDUAL_DEGREES = 1.0;
    /** Page corners must land within this share of the output size of the output corners. */
    private static final double MAX_CROP_ERROR = 0.02;

    @BeforeClass
    public static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    public void straightensSyntheticPhotosWithinOneDegree() {
        List<String> failures = new ArrayList<>();
        int scenes = 0;
        double worst = 0;
        for (SyntheticScene.Desk desk : SyntheticScene.Desk.values()) {
            for (int seed = 0; seed < 10; seed++) {
                // Alternate portrait and landscape frames.
                boolean portrait = seed % 2 == 0;
                SyntheticScene s = SyntheticScene.create(seed * 31L + desk.ordinal(), desk,
                        portrait ? 900 : 1200, portrait ? 1200 : 900, 15);
                ScanImageOps.Straightened r = ScanImageOps.straighten(s.image, null);
                scenes++;
                String id = desk + " seed " + seed + String.format(" tilt %.1f", s.tiltDegrees);
                if (r.quad == null || r.image == null) {
                    failures.add(id + ": no page found");
                } else {
                    double residual = residualDegrees(s, r);
                    double crop = cropError(s, r);
                    worst = Math.max(worst, residual);
                    if (residual > MAX_RESIDUAL_DEGREES) {
                        failures.add(id + String.format(": residual %.2f deg", residual));
                    }
                    if (crop > MAX_CROP_ERROR) {
                        failures.add(id + String.format(": crop off by %.1f%%", crop * 100));
                    }
                    r.image.release();
                }
                s.release();
            }
        }
        System.out.printf("straighten: %d scenes, %d failures, worst residual %.2f deg%n",
                scenes, failures.size(), worst);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void plausibleHintIsUsedAsGiven() {
        SyntheticScene s = SyntheticScene.create(7, SyntheticScene.Desk.DARK, 900, 1200, 10);
        // Hint in scrambled order: straighten must reorder it.
        Point[] hint = {s.corners[2], s.corners[0], s.corners[3], s.corners[1]};
        ScanImageOps.Straightened r = ScanImageOps.straighten(s.image, hint);
        for (int i = 0; i < 4; i++) {
            assertEquals(s.corners[i].x, r.quad[i].x, 1e-6);
            assertEquals(s.corners[i].y, r.quad[i].y, 1e-6);
        }
        assertTrue(residualDegrees(s, r) <= MAX_RESIDUAL_DEGREES);
    }

    @Test
    public void fullFrameHintFallsBackToDetection() {
        SyntheticScene s = SyntheticScene.create(8, SyntheticScene.Desk.LIGHT, 900, 1200, 10);
        Point[] frame = {new Point(0, 0), new Point(900, 0), new Point(900, 1200), new Point(0, 1200)};
        ScanImageOps.Straightened r = ScanImageOps.straighten(s.image, frame);
        assertNotNull(r.quad);
        for (int i = 0; i < 4; i++) {
            assertTrue("corner " + i, Math.hypot(r.quad[i].x - s.corners[i].x, r.quad[i].y - s.corners[i].y) < 15);
        }
    }

    @Test
    public void blankFrameIsLeftAlone() {
        Mat gray = new Mat(800, 600, CvType.CV_8UC4, new Scalar(128, 128, 128, 255));
        ScanImageOps.Straightened r = ScanImageOps.straighten(gray, null);
        assertNull(r.quad);
        assertNull(r.image);
        assertEquals(0, r.skewDegrees, 0);
    }

    @Test
    public void pageFillingTheFrameIsOnlyDeskewed() {
        // An already-cropped scan (e.g. a gallery import): no desk around it, text tilted 5 deg.
        Mat page = new Mat(1100, 800, CvType.CV_8UC4, new Scalar(245, 245, 245, 255));
        for (int y = 80; y < 1020; y += 22) {
            for (int x = 60; x < 720; x += 40) {
                Imgproc.rectangle(page, new Rect(x, y, 32, 9), new Scalar(20, 20, 20, 255), -1);
            }
        }
        Mat m = Imgproc.getRotationMatrix2D(new Point(400, 550), 5, 1.0);
        Mat tilted = new Mat();
        Imgproc.warpAffine(page, tilted, m, page.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
        ScanImageOps.Straightened r = ScanImageOps.straighten(tilted, null);
        assertNull("no page outline to find", r.quad);
        assertNotNull(r.image);
        assertEquals(tilted.size(), r.image.size());
        assertEquals(-5, r.skewDegrees, 0.5);
    }

    @Test
    public void orderCornersHandlesRotatedAndShuffledQuads() {
        for (int deg = -40; deg <= 40; deg += 10) {
            double t = Math.toRadians(deg);
            Point[] expected = new Point[4];
            double[][] base = {{-100, -150}, {100, -150}, {100, 150}, {-100, 150}}; // TL, TR, BR, BL
            for (int i = 0; i < 4; i++) {
                expected[i] = new Point(500 + base[i][0] * Math.cos(t) - base[i][1] * Math.sin(t),
                        500 + base[i][0] * Math.sin(t) + base[i][1] * Math.cos(t));
            }
            Point[] ordered = ScanImageOps.orderCorners(new Point[]{expected[3], expected[1], expected[0], expected[2]});
            for (int i = 0; i < 4; i++) {
                assertEquals("deg " + deg + " corner " + i, expected[i], ordered[i]);
            }
        }
    }

    @Test
    public void implausibleQuadsAreRejected() {
        int w = 1000;
        int h = 1000;
        Point[] good = {new Point(100, 100), new Point(900, 120), new Point(880, 900), new Point(110, 880)};
        assertTrue(ScanImageOps.isPlausiblePage(good, w, h));
        assertFalse("null", ScanImageOps.isPlausiblePage(null, w, h));
        assertFalse("full frame", ScanImageOps.isPlausiblePage(
                new Point[]{new Point(0, 0), new Point(1000, 0), new Point(1000, 1000), new Point(5, 995)}, w, h));
        assertFalse("too small", ScanImageOps.isPlausiblePage(
                new Point[]{new Point(100, 100), new Point(400, 100), new Point(400, 400), new Point(100, 400)}, w, h));
        assertFalse("63 degree parallelogram", ScanImageOps.isPlausiblePage(
                new Point[]{new Point(100, 100), new Point(500, 100), new Point(900, 900), new Point(500, 900)}, w, h));
        assertFalse("edge along the frame border", ScanImageOps.isPlausiblePage(
                new Point[]{new Point(430, 72), new Point(1000, 0), new Point(1000, 900), new Point(434, 854)}, w, h));
        assertFalse("not convex", ScanImageOps.isPlausiblePage(
                new Point[]{new Point(100, 100), new Point(900, 100), new Point(500, 300), new Point(100, 900)}, w, h));
    }

    @Test
    public void findCornersKeepsWarpOrder() {
        SyntheticScene s = SyntheticScene.create(3, SyntheticScene.Desk.DARK, 900, 1200, 12);
        float[] c = ScanImageOps.findCorners(s.image);
        // [x1..x4, y1..y4] = TL, TR, BL, BR
        Point[] expected = {s.corners[0], s.corners[1], s.corners[3], s.corners[2]};
        for (int i = 0; i < 4; i++) {
            assertTrue("corner " + i, Math.hypot(c[i] - expected[i].x, c[i + 4] - expected[i].y) < 15);
        }
    }

    /** Page corners in the output image: ground-truth placement, then the detected warp, then deskew. */
    private static Point[] pageCornersInOutput(SyntheticScene s, ScanImageOps.Straightened r, boolean withDeskew) {
        int outW = r.image.cols();
        int outH = r.image.rows();
        Point[] q = r.quad;
        Mat warp = Imgproc.getPerspectiveTransform(
                new MatOfPoint2f(q[0], q[1], q[3], q[2]),
                new MatOfPoint2f(new Point(0, 0), new Point(outW - 1, 0), new Point(0, outH - 1), new Point(outW - 1, outH - 1)));
        Mat total = new Mat();
        Core.gemm(warp, s.pageToPhoto, 1, new Mat(), 0, total);
        MatOfPoint2f page = new MatOfPoint2f(new Point(0, 0), new Point(s.pageWidth, 0),
                new Point(s.pageWidth, s.pageHeight), new Point(0, s.pageHeight));
        MatOfPoint2f mapped = new MatOfPoint2f();
        Core.perspectiveTransform(page, mapped, total);
        Point[] p = mapped.toArray();
        if (withDeskew && r.skewDegrees != 0) {
            Mat rot = Imgproc.getRotationMatrix2D(new Point(outW / 2.0, outH / 2.0), r.skewDegrees, 1.0);
            for (int i = 0; i < 4; i++) {
                double x = rot.get(0, 0)[0] * p[i].x + rot.get(0, 1)[0] * p[i].y + rot.get(0, 2)[0];
                double y = rot.get(1, 0)[0] * p[i].x + rot.get(1, 1)[0] * p[i].y + rot.get(1, 2)[0];
                p[i] = new Point(x, y);
            }
        }
        return p;
    }

    /** Largest deviation, in degrees, of any page edge in the output from horizontal/vertical. */
    private static double residualDegrees(SyntheticScene s, ScanImageOps.Straightened r) {
        Point[] p = pageCornersInOutput(s, r, true);
        double worst = 0;
        for (int i = 0; i < 4; i++) {
            Point a = p[i];
            Point b = p[(i + 1) % 4];
            double deg = Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
            // Edges 0 and 2 are horizontal (0 / 180 deg), edges 1 and 3 vertical (90 / -90 deg).
            double target = new double[]{0, 90, 180, -90}[i];
            double d = Math.abs(((deg - target) % 360 + 540) % 360 - 180);
            worst = Math.max(worst, d);
        }
        return worst;
    }

    /** How far the page corners land from the output corners, as a share of the output size. */
    private static double cropError(SyntheticScene s, ScanImageOps.Straightened r) {
        Point[] p = pageCornersInOutput(s, r, false);
        double w = r.image.cols() - 1;
        double h = r.image.rows() - 1;
        Point[] target = {new Point(0, 0), new Point(w, 0), new Point(w, h), new Point(0, h)};
        double worst = 0;
        for (int i = 0; i < 4; i++) {
            worst = Math.max(worst, Math.max(Math.abs(p[i].x - target[i].x) / w, Math.abs(p[i].y - target[i].y) / h));
        }
        return worst;
    }
}
