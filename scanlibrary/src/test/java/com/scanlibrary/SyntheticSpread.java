package com.scanlibrary;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A photographed open book with exactly known geometry.
 *
 * <p>Two text pages lie on a desk, bent along the spine: each page's height above the desk is
 * {@code z(u) = lift * (1 - exp(-u / rise)) - sag * lift * (u / pageWidth)^2}, where {@code u} is
 * the distance from the spine along the paper. A perspective camera at focal length {@code f}
 * (pixels) looks straight down at the centre of the spread. Rendering inverts the projection
 * exactly: every photo column corresponds to one {@code u} (the curl is single-axis), so each
 * photo pixel maps back to one point on the flat page.</p>
 */
final class SyntheticSpread {

    final Mat image;
    /** Spine column in the photo. */
    final int spine;
    final int pageWidth;
    final int pageHeight;
    /** Centre rows of the text lines on the flat page, and their text extent (page columns), per page. */
    final List<double[]> leftLines = new ArrayList<>();
    final List<double[]> rightLines = new ArrayList<>();
    final double f;
    final double cx;
    final double cy;
    final double pageTop;
    private final double[] uTable;
    private final double[] xLeftTable;
    private final double[] xRightTable;
    private final double lift;
    private final double rise;
    private final double sag;

    static final class Params {
        int pageWidth = 700;
        int pageHeight = 1000;
        double lift = 110;    // pixels
        double rise = 120;    // pixels
        double sag = 0.3;
        double focalFactor = 0.8; // f = factor * photo long side
        double shadow = 0.35;
        double noise = 3;
        int jpegQuality = 88;
    }

    private SyntheticSpread(Params p, long seed) {
        Random rnd = new Random(seed);
        pageWidth = p.pageWidth;
        pageHeight = p.pageHeight;
        lift = p.lift;
        rise = p.rise;
        sag = p.sag;
        int margin = (int) (0.06 * pageWidth);
        int width = 2 * pageWidth + 2 * margin;
        int height = pageHeight + 2 * margin;
        cx = width / 2.0;
        cy = height / 2.0;
        f = p.focalFactor * Math.max(width, height);
        pageTop = margin;
        spine = (int) Math.round(cx);

        // u -> photo x for each side, on a fine table.
        int n = pageWidth * 4 + 1;
        uTable = new double[n];
        xLeftTable = new double[n];
        xRightTable = new double[n];
        double X = 0;
        double prevZ = z(0);
        for (int i = 0; i < n; i++) {
            double u = i / 4.0;
            if (i > 0) {
                double zz = z(u);
                double dz = zz - prevZ;
                X += Math.sqrt(Math.max(0, 0.25 * 0.25 - dz * dz));
                prevZ = zz;
            }
            double s = f / (f - z(u));
            uTable[i] = u;
            xLeftTable[i] = cx + ((spine - X) - cx) * s;
            xRightTable[i] = cx + ((spine + X) - cx) * s;
        }

        // Flat pages.
        Mat left = textPage(rnd, leftLines);
        Mat right = textPage(rnd, rightLines);

        // Inverse map photo -> page.
        Mat mapX = new Mat(height, width, CvType.CV_32F);
        Mat mapY = new Mat(height, width, CvType.CV_32F);
        Mat side = new Mat(height, width, CvType.CV_8U);
        Mat shade = new Mat(height, width, CvType.CV_32F);
        float[] rx = new float[width];
        float[] ry = new float[width];
        byte[] rs = new byte[width];
        float[] rsh = new float[width];
        double[] uOf = new double[width];
        boolean[] isLeft = new boolean[width];
        for (int x = 0; x < width; x++) {
            isLeft[x] = x < spine;
            uOf[x] = isLeft[x] ? invert(xLeftTable, x, true) : invert(xRightTable, x, false);
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double u = uOf[x];
                double s = f / (f - z(u));
                double Y = cy + (y - cy) / s;
                double v = Y - pageTop;
                double pcol = isLeft[x] ? pageWidth - u : u;
                boolean inside = !Double.isNaN(u) && u >= 0 && u <= pageWidth && v >= 0 && v < pageHeight;
                rx[x] = inside ? (float) pcol : -1;
                ry[x] = inside ? (float) v : -1;
                rs[x] = (byte) (inside ? (isLeft[x] ? 1 : 2) : 0);
                rsh[x] = (float) (1 - p.shadow * Math.exp(-(Double.isNaN(u) ? 1e9 : u) / (0.05 * pageWidth)));
            }
            mapX.put(y, 0, rx);
            mapY.put(y, 0, ry);
            side.put(y, 0, rs);
            shade.put(y, 0, rsh);
        }
        Mat photo = new Mat(height, width, CvType.CV_8UC3, new Scalar(70, 60, 55)); // desk
        for (int s = 1; s <= 2; s++) {
            Mat warped = new Mat();
            Imgproc.remap(s == 1 ? left : right, warped, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, new Scalar(70, 60, 55));
            Mat mask = new Mat();
            Core.compare(side, new Scalar(s), mask, Core.CMP_EQ);
            warped.copyTo(photo, mask);
            warped.release();
            mask.release();
        }
        left.release();
        right.release();
        mapX.release();
        mapY.release();
        side.release();
        // Gutter shadow and a gentle light gradient.
        Mat fimg = new Mat();
        photo.convertTo(fimg, CvType.CV_32FC3);
        List<Mat> planes = new ArrayList<>();
        Core.split(fimg, planes);
        for (Mat pl : planes) {
            Core.multiply(pl, shade, pl);
        }
        Core.merge(planes, fimg);
        Imgproc.GaussianBlur(fimg, fimg, new Size(0, 0), 0.8);
        Mat noise = new Mat(fimg.size(), CvType.CV_32FC3);
        Core.randn(noise, 0, p.noise);
        Core.add(fimg, noise, fimg);
        fimg.convertTo(photo, CvType.CV_8UC3);
        MatOfByte jpg = new MatOfByte();
        Imgcodecs.imencode(".jpg", photo, jpg, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, p.jpegQuality));
        Mat decoded = Imgcodecs.imdecode(jpg, Imgcodecs.IMREAD_COLOR);
        image = new Mat();
        Imgproc.cvtColor(decoded, image, Imgproc.COLOR_RGB2RGBA);
        for (Mat m : planes) {
            m.release();
        }
        fimg.release();
        noise.release();
        photo.release();
        decoded.release();
        shade.release();
    }

    static SyntheticSpread create(Params p, long seed) {
        return new SyntheticSpread(p, seed);
    }

    void release() {
        image.release();
    }

    /** Lift of the paper at distance {@code u} from the spine. */
    double z(double u) {
        double t = u / pageWidth;
        return lift * (1 - Math.exp(-u / rise)) - sag * lift * t * t;
    }

    /** Distance from the spine (along the paper) seen at photo column {@code x}; NaN off the page. */
    double uAt(double x) {
        boolean leftSide = x < spine;
        return leftSide ? invert(xLeftTable, x, true) : invert(xRightTable, x, false);
    }

    /** Photo row of a point on the page at page row {@code v}, seen at photo column {@code x}. */
    double photoY(double x, double v) {
        double u = uAt(x);
        double s = f / (f - z(u));
        return cy + (pageTop + v - cy) * s;
    }

    /** Page column (on its own page) seen at photo column {@code x}. */
    double pageColumn(double x) {
        double u = uAt(x);
        return x < spine ? pageWidth - u : u;
    }

    /** Linear inverse of a monotonic table (decreasing for the left side). */
    private double invert(double[] xs, double x, boolean decreasing) {
        int n = xs.length;
        int lo = 0;
        int hi = n - 1;
        double first = xs[0];
        double last = xs[n - 1];
        if (decreasing ? (x > first || x < last) : (x < first || x > last)) {
            return Double.NaN;
        }
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            boolean before = decreasing ? xs[mid] > x : xs[mid] < x;
            if (before) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double t = (x - xs[lo]) / (xs[hi] - xs[lo]);
        return uTable[lo] + t * (uTable[hi] - uTable[lo]);
    }

    private Mat textPage(Random rnd, List<double[]> lines) {
        Mat p = new Mat(pageHeight, pageWidth, CvType.CV_8UC3, new Scalar(246, 244, 238));
        int lineH = Math.max(6, pageWidth / 55);
        int margin = pageWidth / 10;
        int y = pageHeight / 12;
        while (y < pageHeight - pageHeight / 12) {
            int x = margin;
            int end = pageWidth - margin - (rnd.nextInt(6) == 0 ? rnd.nextInt(pageWidth / 3) : 0);
            int last = x;
            while (x < end) {
                int ww = lineH + rnd.nextInt(lineH * 4);
                if (x + ww > end) {
                    break;
                }
                Imgproc.rectangle(p, new Rect(x, y, ww, lineH), new Scalar(30, 30, 35), -1);
                last = x + ww;
                x += ww + lineH * 2 / 3;
            }
            lines.add(new double[]{y + lineH / 2.0, margin, last});
            y += rnd.nextInt(9) == 0 ? lineH * 4 : (int) (lineH * 1.9);
        }
        return p;
    }
}
