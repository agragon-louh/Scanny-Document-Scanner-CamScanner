package com.scanlibrary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

/** Runs the Java port of the former scan.cpp on desktop OpenCV (openpnp). */
public class ScanImageOpsTest {

    @BeforeClass
    public static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Mat pixel(double r, double g, double b, double a) {
        return new Mat(1, 1, CvType.CV_8UC4, new Scalar(r, g, b, a));
    }

    @Test
    public void magicColorIsSaturatedContrastStretchOnAllChannels() {
        Mat out = ScanImageOps.magicColor(pixel(100, 10, 200, 255));
        assertEquals(CvType.CV_8UC4, out.type());
        // 1.9 * v - 80, saturated to [0, 255]; alpha included, as in the native code.
        assertArrayEquals(new double[]{110, 0, 255, 255}, out.get(0, 0), 0);
    }

    @Test
    public void grayUsesBgrWeightsOnRgbaData() {
        // The native code ran BGR2GRAY on RGBA bitmaps, so red gets the blue weight (0.114).
        Mat red = ScanImageOps.gray(pixel(255, 0, 0, 255));
        Mat blue = ScanImageOps.gray(pixel(0, 0, 255, 255));
        assertEquals(CvType.CV_8UC1, red.type());
        assertEquals(29, red.get(0, 0)[0], 1);
        assertEquals(76, blue.get(0, 0)[0], 1);
    }

    @Test
    public void blackAndWhiteIsBinary() {
        Mat img = new Mat(40, 40, CvType.CV_8UC4, new Scalar(40, 40, 40, 255));
        Imgproc.rectangle(img, new Rect(0, 0, 20, 40), new Scalar(220, 220, 220, 255), -1);
        Mat bw = ScanImageOps.blackAndWhite(img);
        assertEquals(CvType.CV_8UC1, bw.type());
        assertEquals(255, bw.get(10, 5)[0], 0);
        assertEquals(0, bw.get(10, 35)[0], 0);
        for (int y = 0; y < bw.rows(); y++) {
            for (int x = 0; x < bw.cols(); x++) {
                double v = bw.get(y, x)[0];
                assertTrue(v == 0 || v == 255);
            }
        }
    }

    @Test
    public void findsDocumentCorners() {
        Mat img = new Mat(300, 400, CvType.CV_8UC4, new Scalar(30, 30, 30, 255));
        Imgproc.rectangle(img, new Point(50, 40), new Point(350, 260), new Scalar(235, 235, 235, 255), -1);
        float[] c = ScanImageOps.findCorners(img);
        Point[] expected = {new Point(50, 40), new Point(350, 40), new Point(50, 260), new Point(350, 260)};
        for (Point e : expected) {
            boolean found = false;
            for (int i = 0; i < 4; i++) {
                if (Math.abs(c[i] - e.x) <= 6 && Math.abs(c[i + 4] - e.y) <= 6) {
                    found = true;
                }
            }
            assertTrue("corner near " + e + " in " + java.util.Arrays.toString(c), found);
        }
    }

    @Test
    public void fallsBackToWholeImage() {
        Mat img = new Mat(300, 400, CvType.CV_8UC4, new Scalar(128, 128, 128, 255));
        assertArrayEquals(new float[]{0, 400, 0, 400, 0, 0, 300, 300}, ScanImageOps.findCorners(img), 0);
    }

    @Test
    public void warpKeepsNativeOutputSize() {
        Mat img = new Mat(300, 400, CvType.CV_8UC4, new Scalar(10, 20, 30, 255));
        Mat out = ScanImageOps.warp(img, 0, 0, 399, 0, 0, 299, 399, 299);
        // sqrt(2 * 399^2) x sqrt(2 * 299^2), truncated: the original's sizing formula.
        assertEquals(564, out.cols());
        assertEquals(422, out.rows());
        assertEquals(CvType.CV_8UC4, out.type());
        assertArrayEquals(new double[]{10, 20, 30, 255}, out.get(200, 280), 0);
    }
}
