package com.scanlibrary;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Auto-straighten on the real photo set in {@code testdata/pages} (see {@code testdata/README.md}).
 *
 * <p>Skipped when the folder has no annotated photos. Otherwise prints, per photo and overall, the
 * corner error and the residual tilt of the true page after straightening, and fails if fewer
 * than {@link #MIN_SUCCESS_RATE} of the photos come out within 1 degree of upright with all
 * corners within 2% of the page size.</p>
 */
public class RealPhotoBenchmarkTest {

    private static final double MIN_SUCCESS_RATE = 0.95;
    private static final double MAX_RESIDUAL_DEGREES = 1.0;
    private static final double MAX_CORNER_ERROR = 0.02;

    @BeforeClass
    public static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static File dataDir() {
        String override = System.getProperty("scanny.testdata");
        if (override != null) {
            return new File(override);
        }
        // Gradle runs unit tests from the module directory; the set lives at the repository root.
        File fromModule = new File("../testdata/pages");
        return fromModule.isDirectory() ? fromModule : new File("testdata/pages");
    }

    @Test
    public void realPhotos() throws IOException {
        File dir = dataDir();
        File[] photos = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png)$")
                && new File(d, name.replaceFirst("\\.[^.]+$", ".txt")).isFile());
        assumeTrue("no annotated photos in " + dir.getAbsolutePath(), photos != null && photos.length > 0);
        Arrays.sort(photos);

        int ok = 0;
        int found = 0;
        double sumCorner = 0;
        List<String> failures = new ArrayList<>();
        for (File photo : photos) {
            Point[] truth = readCorners(new File(dir, photo.getName().replaceFirst("\\.[^.]+$", ".txt")));
            Mat bgr = Imgcodecs.imread(photo.getPath()); // applies EXIF orientation
            Mat rgba = new Mat();
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA);
            bgr.release();
            long t0 = System.nanoTime();
            ScanImageOps.Straightened r = ScanImageOps.straighten(rgba, null);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String line;
            if (r.quad == null) {
                line = "no page found";
                failures.add(photo.getName() + ": " + line);
            } else {
                found++;
                double diag = Math.hypot(truth[2].x - truth[0].x, truth[2].y - truth[0].y);
                double corner = 0;
                for (int i = 0; i < 4; i++) {
                    corner = Math.max(corner, Math.hypot(r.quad[i].x - truth[i].x, r.quad[i].y - truth[i].y) / diag);
                }
                sumCorner += corner;
                double residual = residualDegrees(truth, r);
                boolean pass = residual <= MAX_RESIDUAL_DEGREES && corner <= MAX_CORNER_ERROR;
                if (pass) {
                    ok++;
                } else {
                    failures.add(photo.getName());
                }
                line = String.format(Locale.ROOT, "corner %.1f%% residual %.2f deg %s", corner * 100, residual, pass ? "" : "FAIL");
            }
            System.out.printf(Locale.ROOT, "%-32s %5d ms  %s%n", photo.getName(), ms, line);
            if (r.image != null) {
                r.image.release();
            }
            rgba.release();
        }
        System.out.printf(Locale.ROOT, "real photos: %d, page found %d, within 1 deg and 2%% %d (%.0f%%), mean corner error %.2f%%%n",
                photos.length, found, ok, 100.0 * ok / photos.length, found == 0 ? 0 : 100 * sumCorner / found);
        assertTrue(String.join("\n", failures), ok >= MIN_SUCCESS_RATE * photos.length);
    }

    /** {@code x y} per line (or all on one line): top-left, top-right, bottom-right, bottom-left. */
    private static Point[] readCorners(File txt) throws IOException {
        String s = new String(Files.readAllBytes(txt.toPath()), StandardCharsets.UTF_8);
        String[] tok = s.replaceAll("#.*", "").trim().split("[\\s,]+");
        if (tok.length != 8) {
            throw new IOException(txt + ": expected 8 numbers, got " + tok.length);
        }
        Point[] p = new Point[4];
        for (int i = 0; i < 4; i++) {
            p[i] = new Point(Double.parseDouble(tok[2 * i]), Double.parseDouble(tok[2 * i + 1]));
        }
        return p;
    }

    /** Tilt of the true page edges after the detected warp and the deskew rotation. */
    private static double residualDegrees(Point[] truth, ScanImageOps.Straightened r) {
        int w = r.image.cols();
        int h = r.image.rows();
        Point[] q = r.quad;
        Mat warp = Imgproc.getPerspectiveTransform(new MatOfPoint2f(q[0], q[1], q[3], q[2]),
                new MatOfPoint2f(new Point(0, 0), new Point(w - 1, 0), new Point(0, h - 1), new Point(w - 1, h - 1)));
        MatOfPoint2f mapped = new MatOfPoint2f();
        Core.perspectiveTransform(new MatOfPoint2f(truth), mapped, warp);
        Point[] p = mapped.toArray();
        Mat rot = Imgproc.getRotationMatrix2D(new Point(w / 2.0, h / 2.0), r.skewDegrees, 1.0);
        double worst = 0;
        for (int i = 0; i < 4; i++) {
            Point a = rotate(rot, p[i]);
            Point b = rotate(rot, p[(i + 1) % 4]);
            double deg = Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
            double target = new double[]{0, 90, 180, -90}[i];
            worst = Math.max(worst, Math.abs(((deg - target) % 360 + 540) % 360 - 180));
        }
        return worst;
    }

    private static Point rotate(Mat m, Point p) {
        return new Point(m.get(0, 0)[0] * p.x + m.get(0, 1)[0] * p.y + m.get(0, 2)[0],
                m.get(1, 0)[0] * p.x + m.get(1, 1)[0] * p.y + m.get(1, 2)[0]);
    }
}
