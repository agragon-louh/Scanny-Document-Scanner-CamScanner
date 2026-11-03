package com.scanlibrary;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A synthetic phone photo of a printed page, with the exact page geometry kept as ground truth.
 *
 * <p>A text page (word-sized dark bars on off-white paper) is placed on a desk with a random
 * rotation of up to +/-{@code maxTiltDegrees}, a random keystone (perspective) and offset, then
 * the photo gets lighting, blur and sensor noise. Deterministic for a given seed.</p>
 */
final class SyntheticScene {

    enum Desk {
        /** Dark desk: high page contrast. */
        DARK,
        /** Mid-grey desk. */
        LIGHT,
        /** Near-white desk: low page contrast, plus a shadow ramp. */
        WHITE,
        /** Mid desk with pens/cables overlapping the page edges, plus a shadow ramp. */
        CLUTTER
    }

    final Mat image;
    /** Page size before placement. */
    final int pageWidth;
    final int pageHeight;
    /** Page -> photo homography (3x3, CV_64F). */
    final Mat pageToPhoto;
    /** Page corners in the photo, TL, TR, BR, BL. */
    final Point[] corners;
    /** In-plane rotation used to place the page, degrees. */
    final double tiltDegrees;

    private SyntheticScene(Mat image, int pageWidth, int pageHeight, Mat pageToPhoto, Point[] corners, double tiltDegrees) {
        this.image = image;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.pageToPhoto = pageToPhoto;
        this.corners = corners;
        this.tiltDegrees = tiltDegrees;
    }

    void release() {
        image.release();
        pageToPhoto.release();
    }

    static SyntheticScene create(long seed, Desk desk, int width, int height, double maxTiltDegrees) {
        Random rnd = new Random(seed);
        int pw = (int) (width * 0.7);
        int ph = (int) (pw * 1.414); // A4
        if (ph > height * 0.85) {
            ph = (int) (height * 0.85);
            pw = (int) (ph / 1.414);
        }
        Mat page = textPage(rnd, pw, ph);

        Mat photo = new Mat(height, width, CvType.CV_8UC4);
        double bg;
        switch (desk) {
            case DARK:
                bg = 45 + rnd.nextInt(30);
                break;
            case LIGHT:
                bg = 170 + rnd.nextInt(40);
                break;
            case WHITE:
                bg = 215 + rnd.nextInt(15);
                break;
            default:
                bg = 120 + rnd.nextInt(30);
                break;
        }
        Core.randn(photo, bg, 12);
        Imgproc.GaussianBlur(photo, photo, new Size(0, 0), 3); // desk texture
        setAlphaOpaque(photo);

        double tilt = (rnd.nextDouble() * 2 - 1) * maxTiltDegrees;
        double t = Math.toRadians(tilt);
        double scale = 0.85 + rnd.nextDouble() * 0.2;
        double cx = width / 2.0 + (rnd.nextDouble() - 0.5) * width * 0.08;
        double cy = height / 2.0 + (rnd.nextDouble() - 0.5) * height * 0.06;
        double keystone = 0.025 * width;
        double[] jitter = new double[8];
        for (int i = 0; i < 8; i++) {
            jitter[i] = (rnd.nextDouble() - 0.5) * 2 * keystone;
        }
        Point[] src = {new Point(0, 0), new Point(pw, 0), new Point(pw, ph), new Point(0, ph)};
        Point[] dst = new Point[4];
        // The whole page stays in the frame (3% margin): pages cut off by the frame are out of scope.
        double margin = 0.03 * Math.min(width, height);
        for (boolean inside = false; !inside; scale *= 0.97) {
            inside = true;
            for (int i = 0; i < 4; i++) {
                double x = (src[i].x - pw / 2.0) * scale;
                double y = (src[i].y - ph / 2.0) * scale;
                dst[i] = new Point(cx + x * Math.cos(t) - y * Math.sin(t) + jitter[2 * i],
                        cy + x * Math.sin(t) + y * Math.cos(t) + jitter[2 * i + 1]);
                inside &= dst[i].x >= margin && dst[i].x <= width - margin
                        && dst[i].y >= margin && dst[i].y <= height - margin;
            }
        }
        Mat h = Imgproc.getPerspectiveTransform(new MatOfPoint2f(src), new MatOfPoint2f(dst));
        Mat placed = new Mat();
        Imgproc.warpPerspective(page, placed, h, photo.size(), Imgproc.INTER_LINEAR);
        Mat mask = new Mat(ph, pw, CvType.CV_8U, new Scalar(255));
        Mat placedMask = new Mat();
        Imgproc.warpPerspective(mask, placedMask, h, photo.size(), Imgproc.INTER_NEAREST);
        placed.copyTo(photo, placedMask);
        page.release();
        placed.release();
        mask.release();
        placedMask.release();

        if (desk == Desk.CLUTTER) {
            for (int k = 0; k < 6; k++) {
                Point p1 = new Point(rnd.nextInt(width), rnd.nextInt(height));
                Point p2 = new Point(p1.x + rnd.nextInt(width / 3) - width / 6.0, p1.y + rnd.nextInt(width / 3) - width / 6.0);
                int thickness = Math.max(3, width / 150 + rnd.nextInt(Math.max(1, width / 85)));
                Imgproc.line(photo, p1, p2, new Scalar(rnd.nextInt(80), rnd.nextInt(80), rnd.nextInt(120), 255), thickness);
            }
        }
        if (desk == Desk.WHITE || desk == Desk.CLUTTER) {
            shadowRamp(photo);
        }
        Imgproc.GaussianBlur(photo, photo, new Size(0, 0), 1.0);
        Mat noise = new Mat(photo.size(), CvType.CV_8UC4);
        Core.randn(noise, 0, 4);
        Core.add(photo, noise, photo);
        noise.release();
        setAlphaOpaque(photo);
        return new SyntheticScene(photo, pw, ph, h, dst, tilt);
    }

    /** Word-like dark bars in lines, with ragged line ends and paragraph gaps. */
    private static Mat textPage(Random rnd, int w, int h) {
        Mat p = new Mat(h, w, CvType.CV_8UC4, new Scalar(245, 245, 242, 255));
        int margin = w / 12;
        int lineH = Math.max(6, w / 65);
        int pitch = lineH * 2;
        int y = margin;
        while (y < h - margin) {
            int x = margin;
            int end = w - margin - (rnd.nextInt(5) == 0 ? rnd.nextInt(w / 2) : 0);
            while (x < end) {
                int ww = lineH + rnd.nextInt(lineH * 5);
                if (x + ww > end) {
                    break;
                }
                Imgproc.rectangle(p, new Rect(x, y, ww, lineH), new Scalar(25, 25, 30, 255), -1);
                x += ww + lineH * 2 / 3;
            }
            y += rnd.nextInt(8) == 0 ? pitch * 2 : pitch;
        }
        return p;
    }

    /** Darkens the left side of the photo smoothly (to 60%), like a shadow cast by the phone. */
    private static void shadowRamp(Mat photo) {
        Mat ramp = new Mat(1, photo.cols(), CvType.CV_32F);
        for (int x = 0; x < photo.cols(); x++) {
            ramp.put(0, x, 0.6 + 0.4 * x / (double) photo.cols());
        }
        Mat full = new Mat();
        Core.repeat(ramp, photo.rows(), 1, full);
        Mat f = new Mat();
        photo.convertTo(f, CvType.CV_32FC4);
        List<Mat> planes = new ArrayList<>();
        Core.split(f, planes);
        for (int c = 0; c < 3; c++) {
            Core.multiply(planes.get(c), full, planes.get(c));
        }
        Core.merge(planes, f);
        f.convertTo(photo, CvType.CV_8UC4);
        for (Mat m : planes) {
            m.release();
        }
        ramp.release();
        full.release();
        f.release();
    }

    private static void setAlphaOpaque(Mat rgba) {
        List<Mat> planes = new ArrayList<>();
        Core.split(rgba, planes);
        planes.get(3).setTo(new Scalar(255));
        Core.merge(planes, rgba);
        for (Mat m : planes) {
            m.release();
        }
    }
}
