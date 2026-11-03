package com.scanlibrary;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Document filters and edge detection on OpenCV {@link Mat}s.
 *
 * <p>Java port of the former {@code jni/scan.cpp} (built against a 2017 OpenCV 3 prebuilt).
 * The operations, parameters and quirks are kept as they were so scans look the same:</p>
 * <ul>
 *     <li>Input Mats are 4-channel images in Android bitmap memory order (RGBA).</li>
 *     <li>The gray and black-and-white filters convert with BGR weights, exactly like the
 *     native code did on RGBA data.</li>
 *     <li>The magic-colour contrast stretch also applies to the alpha channel.</li>
 *     <li>Edge detection only examines the first colour channel.</li>
 * </ul>
 *
 * <p>Plain OpenCV Java API only (no Android classes), so it can be unit-tested on the JVM.</p>
 */
public final class ScanImageOps {

    private ScanImageOps() {
    }

    /** Contrast stretch: {@code dst = saturate(1.9 * src - 80)} on every channel. */
    public static Mat magicColor(Mat rgba) {
        Mat dst = new Mat();
        rgba.convertTo(dst, -1, 1.9, -80);
        return dst;
    }

    /** Single-channel gray image (BGR weights applied to RGBA data, as before). */
    public static Mat gray(Mat rgba) {
        Mat dst = new Mat();
        Imgproc.cvtColor(rgba, dst, Imgproc.COLOR_BGRA2GRAY);
        return dst;
    }

    /** {@link #gray} followed by an Otsu threshold: every pixel is 0 or 255. */
    public static Mat blackAndWhite(Mat rgba) {
        Mat dst = gray(rgba);
        Imgproc.threshold(dst, dst, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        return dst;
    }

    /**
     * Perspective-corrects the quadrilateral with corners (x1,y1) top-left, (x2,y2) top-right,
     * (x3,y3) bottom-left, (x4,y4) bottom-right. Sub-pixel corners are used as given.
     */
    public static Mat warp(Mat rgba, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
        // Output size from the real edge lengths (the native code used a doubled-term formula that
        // inflated the output by sqrt(2)). The longer of each pair of opposite edges is kept so no
        // resolution is lost; +1 makes the corner pixels (w-1, h-1) map exactly onto the corners.
        double topW = Math.hypot(x2 - x1, y2 - y1);
        double bottomW = Math.hypot(x4 - x3, y4 - y3);
        double leftH = Math.hypot(x3 - x1, y3 - y1);
        double rightH = Math.hypot(x4 - x2, y4 - y2);
        int outWidth = Math.max(1, (int) Math.round(Math.max(topW, bottomW)) + 1);
        int outHeight = Math.max(1, (int) Math.round(Math.max(leftH, rightH)) + 1);

        MatOfPoint2f dstPts = new MatOfPoint2f(
                new Point(0, 0),
                new Point(outWidth - 1, 0),
                new Point(0, outHeight - 1),
                new Point(outWidth - 1, outHeight - 1));
        MatOfPoint2f imgPts = new MatOfPoint2f(
                new Point(x1, y1),
                new Point(x2, y2),
                new Point(x3, y3),
                new Point(x4, y4));
        Mat transform = Imgproc.getPerspectiveTransform(imgPts, dstPts);
        Mat dst = new Mat();
        Imgproc.warpPerspective(rgba, dst, transform, new Size(outWidth, outHeight));
        transform.release();
        dstPts.release();
        imgPts.release();
        return dst;
    }

    /** Largest skew corrected by {@link #deskew}, in degrees either way. */
    public static final double MAX_DESKEW_DEGREES = 15;
    /** Skews smaller than this are left alone (rotating would only blur the text). */
    private static final double MIN_DESKEW_DEGREES = 0.3;

    /**
     * Estimates how far the text lines of a page are tilted, as the rotation in degrees (positive =
     * counter-clockwise, OpenCV convention) that makes them horizontal. Uses the projection-profile
     * method: the correct angle is the one where ink piles up into the sharpest row peaks.
     * Returns 0 for blank or featureless pages. Result is within +/-{@link #MAX_DESKEW_DEGREES}.
     */
    public static double estimateSkew(Mat rgba) {
        Mat gray = gray(rgba);
        // Work on a small copy: skew is a global property and this keeps the search cheap.
        double scale = 600.0 / Math.max(gray.cols(), gray.rows());
        if (scale < 1) {
            Imgproc.resize(gray, gray, new Size(), scale, scale, Imgproc.INTER_AREA);
        }
        Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);
        Mat ink = new Mat();
        // Inverted Otsu: dark ink (and anything dark) becomes 255.
        Imgproc.threshold(gray, ink, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
        gray.release();
        // A page with almost no ink (or almost all ink, e.g. a photo) has no usable lines.
        double fill = Core.countNonZero(ink) / (double) ink.total();
        if (fill < 0.005 || fill > 0.6) {
            ink.release();
            return 0;
        }
        double best = 0;
        double bestScore = profileScore(ink, 0);
        double zeroScore = bestScore;
        for (double a = -MAX_DESKEW_DEGREES; a <= MAX_DESKEW_DEGREES; a += 1) {
            double sc = profileScore(ink, a);
            if (sc > bestScore) {
                bestScore = sc;
                best = a;
            }
        }
        double centre = best;
        for (double a = centre - 1; a <= centre + 1; a += 0.25) {
            if (Math.abs(a) > MAX_DESKEW_DEGREES) {
                continue;
            }
            double sc = profileScore(ink, a);
            if (sc > bestScore) {
                bestScore = sc;
                best = a;
            }
        }
        ink.release();
        // Only trust a clear win over "no rotation".
        return bestScore > zeroScore * 1.02 ? best : 0;
    }

    /** Variance of the row sums after rotating by {@code angle}: high when lines are horizontal. */
    private static double profileScore(Mat ink, double angle) {
        Point centre = new Point(ink.cols() / 2.0, ink.rows() / 2.0);
        Mat m = Imgproc.getRotationMatrix2D(centre, angle, 1.0);
        Mat rotated = new Mat();
        Imgproc.warpAffine(ink, rotated, m, ink.size(), Imgproc.INTER_NEAREST);
        Mat rows = new Mat();
        Core.reduce(rotated, rows, 1, Core.REDUCE_SUM, CvType.CV_32S);
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        Core.meanStdDev(rows, mean, std);
        double sd = std.get(0, 0)[0];
        m.release();
        rotated.release();
        rows.release();
        mean.release();
        std.release();
        return sd * sd;
    }

    /**
     * Rotates the page so its text lines are horizontal. Corrects up to
     * {@link #MAX_DESKEW_DEGREES}; tiny or undetectable skews return an unchanged copy. The size is
     * kept and newly exposed corners are filled by edge replication (no black wedges).
     */
    public static Mat deskew(Mat rgba) {
        double angle = estimateSkew(rgba);
        if (Math.abs(angle) < MIN_DESKEW_DEGREES) {
            return rgba.clone();
        }
        Point centre = new Point(rgba.cols() / 2.0, rgba.rows() / 2.0);
        Mat m = Imgproc.getRotationMatrix2D(centre, angle, 1.0);
        Mat dst = new Mat();
        Imgproc.warpAffine(rgba, dst, m, rgba.size(), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE);
        m.release();
        return dst;
    }

    /**
     * Finds the largest roughly rectangular contour (the document). Returns
     * {@code [x1, x2, x3, x4, y1, y2, y3, y4]}; the whole image when nothing is found.
     */
    public static float[] findCorners(Mat rgba) {
        int width = rgba.cols();
        int height = rgba.rows();
        Mat blurred = new Mat();
        Imgproc.medianBlur(rgba, blurred, 9);

        Mat gray0 = new Mat(blurred.size(), CvType.CV_8U);
        Mat gray = new Mat();
        List<MatOfPoint> squares = new ArrayList<>();
        final int thresholdLevel = 2;

        // The native loop over colour planes returned after the first plane; so does this.
        int channel = 0;
        // Collections.singletonList, not List.of: minSdk 23 has no Java 9 collection factories.
        Core.mixChannels(Collections.singletonList(blurred), Collections.singletonList(gray0), new MatOfInt(channel, 0));
        for (int l = 0; l < thresholdLevel; l++) {
            if (l == 0) {
                // Canny catches squares with gradient shading; dilate closes gaps between edges.
                Imgproc.Canny(gray0, gray, 10, 20, 3, false);
                Imgproc.dilate(gray, gray, new Mat(), new Point(-1, -1));
            } else {
                Core.compare(gray0, new Scalar((l + 1) * 255 / thresholdLevel), gray, Core.CMP_GE);
            }
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(gray, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            hierarchy.release();
            for (MatOfPoint contour : contours) {
                MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
                MatOfPoint2f approx2f = new MatOfPoint2f();
                Imgproc.approxPolyDP(contour2f, approx2f, Imgproc.arcLength(contour2f, true) * 0.02, true);
                MatOfPoint approx = toIntPoints(approx2f);
                contour2f.release();
                approx2f.release();
                if (approx.total() == 4
                        && Math.abs(Imgproc.contourArea(approx)) > 1000
                        && Imgproc.isContourConvex(approx)) {
                    Point[] p = approx.toArray();
                    double maxCosine = 0;
                    for (int j = 2; j < 5; j++) {
                        maxCosine = Math.max(maxCosine, Math.abs(angle(p[j % 4], p[j - 2], p[j - 1])));
                    }
                    if (maxCosine < 0.3) {
                        squares.add(approx);
                        continue;
                    }
                }
                approx.release();
            }
        }
        blurred.release();
        gray0.release();
        gray.release();

        Point[] points;
        if (squares.isEmpty()) {
            points = new Point[]{new Point(0, 0), new Point(width, 0), new Point(0, height), new Point(width, height)};
        } else {
            double largestArea = -1;
            int largest = 0;
            for (int i = 0; i < squares.size(); i++) {
                double area = Imgproc.contourArea(squares.get(i), false);
                if (area > largestArea) {
                    largestArea = area;
                    largest = i;
                }
            }
            points = squares.get(largest).toArray();
        }
        for (MatOfPoint square : squares) {
            square.release();
        }
        float[] result = new float[8];
        for (int i = 0; i < 4; i++) {
            result[i] = (float) points[i].x;
            result[i + 4] = (float) points[i].y;
        }
        return result;
    }

    /** approxPolyDP on int contours yields int points in C++; round the same way here. */
    private static MatOfPoint toIntPoints(MatOfPoint2f points) {
        Point[] src = points.toArray();
        Point[] dst = new Point[src.length];
        for (int i = 0; i < src.length; i++) {
            dst[i] = new Point(Math.round(src[i].x), Math.round(src[i].y));
        }
        return new MatOfPoint(dst);
    }

    private static double angle(Point pt1, Point pt2, Point pt0) {
        double dx1 = pt1.x - pt0.x;
        double dy1 = pt1.y - pt0.y;
        double dx2 = pt2.x - pt0.x;
        double dy2 = pt2.y - pt0.y;
        return (dx1 * dx2 + dy1 * dy2) / Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10);
    }
}
