package com.scanlibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.Locale;

/**
 * Phase 2 enhancement filters, scored against the ground truth of {@link DegradedPage}: a page
 * photographed with a soft shadow, light fall-off, a warm colour cast, noise and JPEG artefacts.
 *
 * <p>"Paper" values are luminance on bare paper, "ink" on the interior of black text. The bars
 * are the roadmap's goal made measurable: paper uniformly white including inside the shadow,
 * black text, colours of stamps / highlighter / pen kept in Magic Color, no speckle in low light,
 * and a large dark photo on the page not washed out.</p>
 */
public class ScanFiltersTest {

    private static DegradedPage normal;
    private static DegradedPage lowLight;

    @BeforeClass
    public static void setUp() {
        nu.pattern.OpenCV.loadLocally();
        normal = DegradedPage.create(1, 1000, 1400, new DegradedPage.Lighting());
        DegradedPage.Lighting dim = new DegradedPage.Lighting();
        dim.exposure = 0.5;
        dim.noiseSigma = 9;
        lowLight = DegradedPage.create(2, 1000, 1400, dim);
    }

    @AfterClass
    public static void report() {
        // Prints a comparison table (useful when tuning); assertions are in the tests below.
        for (DegradedPage p : new DegradedPage[]{normal, lowLight}) {
            String which = p == normal ? "normal" : "low light";
            print(which + " input", p.image, p);
            print(which + " old magic", legacyMagicColor(p.image), p);
            print(which + " old b&w", legacyBlackAndWhite(p.image), p);
            print(which + " magic", ScanFilters.magicColor(p.image), p);
            print(which + " gray", ScanFilters.gray(p.image), p);
            print(which + " b&w", ScanFilters.blackAndWhite(p.image), p);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Magic Color

    @Test
    public void magicColorWhitensPaperIncludingTheShadow() {
        for (DegradedPage p : new DegradedPage[]{normal, lowLight}) {
            Mat out = ScanFilters.magicColor(p.image);
            assertEquals(CvType.CV_8UC4, out.type());
            Mat lum = luminance(out);
            assertTrue("paper mean " + mean(lum, p.paper), mean(lum, p.paper) >= 250);
            assertTrue("paper std " + std(lum, p.paper), std(lum, p.paper) <= 4);
            assertTrue("shadowed paper " + mean(lum, p.shadowedPaper), mean(lum, p.shadowedPaper) >= 248);
            assertTrue("1st percentile of paper (bands, specks) " + percentile(lum, p.paper, 0.01),
                    percentile(lum, p.paper, 0.01) >= 225);
            // The warm cast is gone: paper is neutral.
            assertTrue("paper chroma " + chroma(out, p.paper), chroma(out, p.paper) <= 3);
        }
    }

    @Test
    public void magicColorKeepsTextBlackAndColoursInColour() {
        for (DegradedPage p : new DegradedPage[]{normal, lowLight}) {
            Mat out = ScanFilters.magicColor(p.image);
            Mat lum = luminance(out);
            assertTrue("ink " + mean(lum, p.ink), mean(lum, p.ink) <= 40);
            assertTrue("black text has no colour fringe: chroma " + chroma(out, p.ink), chroma(out, p.ink) <= 8);
            Scalar stamp = Core.mean(out, p.stamp);
            assertTrue("stamp stays red " + stamp, stamp.val[0] > 120 && stamp.val[0] > 3 * stamp.val[1] && stamp.val[0] > 3 * stamp.val[2]);
            Scalar hl = Core.mean(out, p.highlight);
            assertTrue("highlighter stays yellow " + hl, hl.val[0] > 220 && hl.val[1] > 200 && hl.val[2] < 120);
            Scalar sig = Core.mean(out, p.signature);
            assertTrue("signature stays blue " + sig, sig.val[2] > 100 && sig.val[2] > 2.5 * sig.val[0]);
        }
    }

    @Test
    public void largeDarkAreasAreNotWashedOut() {
        // A photo bigger than the background kernel must not be divided by itself (-> white).
        for (DegradedPage p : new DegradedPage[]{normal, lowLight}) {
            assertTrue("magic photo " + mean(luminance(ScanFilters.magicColor(p.image)), p.photo),
                    mean(luminance(ScanFilters.magicColor(p.image)), p.photo) <= 80);
            assertTrue("b&w photo " + mean(ScanFilters.blackAndWhite(p.image), p.photo),
                    mean(ScanFilters.blackAndWhite(p.image), p.photo) <= 60);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Gray and B&W

    @Test
    public void grayIsWhitePaperAndBlackText() {
        for (DegradedPage p : new DegradedPage[]{normal, lowLight}) {
            Mat g = ScanFilters.gray(p.image);
            assertEquals(CvType.CV_8UC1, g.type());
            assertTrue("paper " + mean(g, p.paper), mean(g, p.paper) >= 250);
            assertTrue("shadowed paper " + mean(g, p.shadowedPaper), mean(g, p.shadowedPaper) >= 248);
            assertTrue("ink " + mean(g, p.ink), mean(g, p.ink) <= 40);
        }
    }

    @Test
    public void blackAndWhiteSurvivesShadowAndLowLight() {
        for (DegradedPage p : new DegradedPage[]{normal, lowLight}) {
            Mat bw = ScanFilters.blackAndWhite(p.image);
            assertEquals(CvType.CV_8UC1, bw.type());
            assertTrue("only 0 and 255", countValues(bw, 0) + countValues(bw, 255) == bw.total());
            double paperBlack = share(bw, p.paper, false);
            double inkWhite = share(bw, p.ink, true);
            assertTrue(String.format(Locale.ROOT, "paper turned black: %.3f%%", 100 * paperBlack), paperBlack <= 0.001);
            assertTrue(String.format(Locale.ROOT, "text turned white: %.3f%%", 100 * inkWhite), inkWhite <= 0.005);
            assertTrue("shadowed paper black share " + share(bw, p.shadowedPaper, false),
                    share(bw, p.shadowedPaper, false) <= 0.001);
        }
    }

    @Test
    public void faintPrintSurvivesBlackAndWhite() {
        // Light-grey print (faded toner, pencil) on slightly noisy paper must not drop out now that
        // the threshold margin grows with noise.
        Mat page = new Mat(1200, 900, CvType.CV_8UC4, new Scalar(210, 205, 195, 255));
        Mat faint = Mat.zeros(page.size(), CvType.CV_8U);
        for (int y = 100; y < 1100; y += 40) {
            for (int x = 80; x < 820; x += 60) {
                org.opencv.core.Rect r = new org.opencv.core.Rect(x, y, 44, 12);
                Imgproc.rectangle(page, r, new Scalar(150, 146, 140, 255), -1);
                Imgproc.rectangle(faint, new org.opencv.core.Rect(x + 2, y + 2, 40, 8), new Scalar(255), -1);
            }
        }
        Mat noise = new Mat(page.size(), CvType.CV_8UC4);
        Core.randn(noise, 0, 3);
        Core.add(page, noise, page);
        Mat bw = ScanFilters.blackAndWhite(page);
        assertTrue("faint print turned white: " + share(bw, faint, true), share(bw, faint, true) <= 0.05);
    }

    @Test
    public void oldFiltersFailTheSamePage() {
        // Documents what changed: the global Otsu threshold blackens the shadow, and the old
        // contrast stretch leaves the shadow and the colour cast in.
        Mat oldBw = legacyBlackAndWhite(normal.image);
        assertTrue(share(oldBw, normal.shadowedPaper, false) > 0.5);
        Mat oldMagic = luminance(legacyMagicColor(normal.image));
        assertTrue(mean(oldMagic, normal.shadowedPaper) < 150);
    }

    // ---------------------------------------------------------------------------------------
    // Building blocks

    @Test
    public void noiseEstimateTracksSensorNoiseThroughJpeg() {
        for (double sigma : new double[]{2, 5, 9}) {
            Mat flat = new Mat(600, 600, CvType.CV_32F, new Scalar(150));
            Mat noise = new Mat(flat.size(), flat.type());
            Core.randn(noise, 0, sigma);
            Core.add(flat, noise, flat);
            Mat img = new Mat();
            flat.convertTo(img, CvType.CV_8U);
            assertEquals("raw sigma " + sigma, sigma, ScanFilters.noiseSigma(img), 0.15 * sigma + 0.3);
            MatOfByte jpg = new MatOfByte();
            Imgcodecs.imencode(".jpg", img, jpg, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85));
            Mat decoded = Imgcodecs.imdecode(jpg, Imgcodecs.IMREAD_GRAYSCALE);
            // JPEG removes some noise; the estimate must still see most of what remains visible.
            assertEquals("jpeg sigma " + sigma, sigma, ScanFilters.noiseSigma(decoded), 0.35 * sigma + 0.5);
        }
        Mat clean = new Mat(300, 300, CvType.CV_8U, new Scalar(200));
        assertEquals(0, ScanFilters.noiseSigma(clean), 0.01);
    }

    @Test
    public void noiseEstimateIgnoresText() {
        // The normal page has noise ~3 before JPEG; text edges must not inflate the estimate.
        assertTrue(ScanFilters.noiseSigma(normal.image) < ScanFilters.DENOISE_ABOVE_SIGMA);
        assertTrue(ScanFilters.noiseSigma(lowLight.image) > ScanFilters.DENOISE_ABOVE_SIGMA);
    }

    @Test
    public void filtersAcceptTinyAndBlankImages() {
        Mat tiny = new Mat(3, 2, CvType.CV_8UC4, new Scalar(200, 190, 180, 255));
        assertEquals(tiny.size(), ScanFilters.magicColor(tiny).size());
        assertEquals(tiny.size(), ScanFilters.gray(tiny).size());
        assertEquals(tiny.size(), ScanFilters.blackAndWhite(tiny).size());
        Mat blank = new Mat(400, 300, CvType.CV_8UC4, new Scalar(180, 170, 150, 255));
        assertEquals(255, Core.mean(ScanFilters.gray(blank)).val[0], 1);
        assertEquals(255, Core.mean(ScanFilters.blackAndWhite(blank)).val[0], 0);
    }

    // ---------------------------------------------------------------------------------------
    // The filters as they were before phase 2 (ported scan.cpp), for comparison.

    private static Mat legacyMagicColor(Mat rgba) {
        Mat dst = new Mat();
        rgba.convertTo(dst, -1, 1.9, -80);
        return dst;
    }

    private static Mat legacyBlackAndWhite(Mat rgba) {
        Mat g = new Mat();
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_BGRA2GRAY);
        Imgproc.threshold(g, g, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        return g;
    }

    // ---------------------------------------------------------------------------------------
    // Measurements

    private static Mat luminance(Mat m) {
        if (m.channels() == 1) {
            return m;
        }
        Mat g = new Mat();
        Imgproc.cvtColor(m, g, m.channels() == 4 ? Imgproc.COLOR_RGBA2GRAY : Imgproc.COLOR_RGB2GRAY);
        return g;
    }

    private static double mean(Mat gray, Mat mask) {
        return Core.mean(gray, mask).val[0];
    }

    private static double std(Mat gray, Mat mask) {
        MatOfDouble mu = new MatOfDouble();
        MatOfDouble sd = new MatOfDouble();
        Core.meanStdDev(gray, mu, sd, mask);
        return sd.get(0, 0)[0];
    }

    /** Mean of (max - min channel) over the mask. */
    private static double chroma(Mat rgba, Mat mask) {
        java.util.List<Mat> c = new java.util.ArrayList<>();
        Core.split(rgba, c);
        Mat max = new Mat();
        Mat min = new Mat();
        Core.max(c.get(0), c.get(1), max);
        Core.max(max, c.get(2), max);
        Core.min(c.get(0), c.get(1), min);
        Core.min(min, c.get(2), min);
        Mat d = new Mat();
        Core.subtract(max, min, d);
        return Core.mean(d, mask).val[0];
    }

    private static double percentile(Mat gray, Mat mask, double q) {
        byte[] v = new byte[(int) gray.total()];
        byte[] m = new byte[(int) mask.total()];
        gray.get(0, 0, v);
        mask.get(0, 0, m);
        int[] hist = new int[256];
        long n = 0;
        for (int i = 0; i < v.length; i++) {
            if (m[i] != 0) {
                hist[v[i] & 0xff]++;
                n++;
            }
        }
        long acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= q * n) {
                return i;
            }
        }
        return 255;
    }

    /** Share of masked pixels that are white ({@code white}) or black. */
    private static double share(Mat bw, Mat mask, boolean white) {
        Mat sel = new Mat();
        Imgproc.threshold(bw, sel, 127, 255, white ? Imgproc.THRESH_BINARY : Imgproc.THRESH_BINARY_INV);
        Core.bitwise_and(sel, mask, sel);
        return Core.countNonZero(sel) / (double) Core.countNonZero(mask);
    }

    private static long countValues(Mat gray, int value) {
        Mat eq = new Mat();
        Core.compare(gray, new Scalar(value), eq, Core.CMP_EQ);
        return Core.countNonZero(eq);
    }

    private static void print(String name, Mat out, DegradedPage p) {
        Mat lum = luminance(out);
        System.out.printf(Locale.ROOT, "%-20s paper %5.1f (shadow %5.1f, p1 %3.0f)  ink %5.1f  photo %5.1f  paper chroma %4.1f%n",
                name, mean(lum, p.paper), mean(lum, p.shadowedPaper), percentile(lum, p.paper, 0.01),
                mean(lum, p.ink), mean(lum, p.photo), out.channels() >= 3 ? chroma(out, p.paper) : 0);
    }
}
