package com.scanlibrary;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
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
     * (x3,y3) bottom-left, (x4,y4) bottom-right. Corner coordinates are truncated to whole
     * pixels, as the native code did.
     */
    public static Mat warp(Mat rgba, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
        // The original sized the output with these (doubled-term) formulas; kept as is.
        float w1 = (float) Math.sqrt(Math.pow(x4 - x3, 2) + Math.pow(x4 - x3, 2));
        float w2 = (float) Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(x2 - x1, 2));
        float h1 = (float) Math.sqrt(Math.pow(y2 - y4, 2) + Math.pow(y2 - y4, 2));
        float h2 = (float) Math.sqrt(Math.pow(y1 - y3, 2) + Math.pow(y1 - y3, 2));
        float maxWidth = Math.min(w1, w2);
        float maxHeight = Math.min(h1, h2);

        MatOfPoint2f dstPts = new MatOfPoint2f(
                new Point(0, 0),
                new Point((int) (maxWidth - 1), 0),
                new Point(0, (int) (maxHeight - 1)),
                new Point((int) (maxWidth - 1), (int) (maxHeight - 1)));
        MatOfPoint2f imgPts = new MatOfPoint2f(
                new Point((int) x1, (int) y1),
                new Point((int) x2, (int) y2),
                new Point((int) x3, (int) y3),
                new Point((int) x4, (int) y4));
        Mat transform = Imgproc.getPerspectiveTransform(imgPts, dstPts);
        Mat dst = new Mat();
        Imgproc.warpPerspective(rgba, dst, transform, new Size((int) maxWidth, (int) maxHeight));
        transform.release();
        dstPts.release();
        imgPts.release();
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
