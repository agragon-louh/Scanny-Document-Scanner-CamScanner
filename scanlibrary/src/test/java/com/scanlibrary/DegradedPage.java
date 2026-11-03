package com.scanlibrary;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A straightened page photographed badly, with ground-truth masks of what is printed where.
 *
 * <p>The clean page has body text, a heading, a red stamp, a yellow highlighter stroke over a
 * line of text, a blue-pen signature and a dark photo. It is then lit unevenly (a soft phone
 * shadow, light fall-off, a warm colour cast), blurred, given sensor noise and JPEG-compressed.
 * Deterministic for a given seed.</p>
 */
final class DegradedPage {

    /** The degraded photo, RGBA. */
    final Mat image;
    /** Interior of black text (eroded so edge pixels, legitimately grey, are not scored). */
    final Mat ink;
    /** Paper well away from anything printed. */
    final Mat paper;
    /** Interior of the red stamp ring. */
    final Mat stamp;
    /** Highlighted paper (yellow, no text on it). */
    final Mat highlight;
    /** Interior of the blue signature. */
    final Mat signature;
    /** Interior of the dark photo. */
    final Mat photo;
    /** Paper inside the shadow (subset of {@link #paper}). */
    final Mat shadowedPaper;

    private DegradedPage(Mat image, Mat ink, Mat paper, Mat stamp, Mat highlight, Mat signature, Mat photo, Mat shadowedPaper) {
        this.image = image;
        this.ink = ink;
        this.paper = paper;
        this.stamp = stamp;
        this.highlight = highlight;
        this.signature = signature;
        this.photo = photo;
        this.shadowedPaper = shadowedPaper;
    }

    static final class Lighting {
        double exposure = 0.85;
        /** How much the shadow darkens the paper (0 = no shadow). */
        double shadow = 0.45;
        /** Channel gains of the light: warm indoor light by default. */
        double[] cast = {1.0, 0.92, 0.78};
        double noiseSigma = 3;
        int jpegQuality = 85;
    }

    static DegradedPage create(long seed, int width, int height, Lighting light) {
        Random rnd = new Random(seed);
        Mat clean = new Mat(height, width, CvType.CV_8UC3, new Scalar(255, 255, 255));
        Mat inkMask = Mat.zeros(height, width, CvType.CV_8U);
        Mat printed = Mat.zeros(height, width, CvType.CV_8U); // everything that is not bare paper

        int margin = width / 12;
        int lineH = Math.max(6, width / 90);
        // Heading.
        Rect heading = new Rect(margin, margin, width / 2, lineH * 3);
        fill(clean, inkMask, printed, heading, new Scalar(20, 20, 20));
        // Body text, with a yellow highlighter stroke under line 6 (drawn first, text over it).
        int y = margin + lineH * 6;
        int line = 0;
        Mat highlightMask = Mat.zeros(height, width, CvType.CV_8U);
        while (y < height * 0.62) {
            if (line == 6) {
                Rect band = new Rect(margin - lineH / 2, y - lineH / 2, width - 2 * margin + lineH, lineH * 2);
                Imgproc.rectangle(clean, band, new Scalar(255, 238, 70), -1);
                Imgproc.rectangle(printed, band, new Scalar(255), -1);
                Imgproc.rectangle(highlightMask, band, new Scalar(255), -1);
            }
            int x = margin;
            int end = width - margin - (rnd.nextInt(5) == 0 ? rnd.nextInt(width / 3) : 0);
            while (x < end) {
                int ww = lineH + rnd.nextInt(lineH * 6);
                if (x + ww > end) {
                    break;
                }
                fill(clean, inkMask, printed, new Rect(x, y, ww, lineH), new Scalar(25, 25, 28));
                x += ww + lineH;
            }
            y += lineH * 2 + (rnd.nextInt(7) == 0 ? lineH * 2 : 0);
            line++;
        }
        // Highlight mask: highlighted paper only, not the text on it.
        Core.subtract(highlightMask, dilate(inkMask, 5), highlightMask);

        // Red stamp ring, lower right.
        Point stampCentre = new Point(width * 0.72, height * 0.78);
        int r = width / 9;
        int ring = Math.max(6, width / 80);
        Imgproc.circle(clean, stampCentre, r, new Scalar(200, 30, 40), ring);
        Imgproc.circle(printed, stampCentre, r, new Scalar(255), ring);
        Mat stampMask = Mat.zeros(height, width, CvType.CV_8U);
        Imgproc.circle(stampMask, stampCentre, r, new Scalar(255), Math.max(2, ring / 3));

        // Blue signature, lower left: a wavy thick polyline.
        List<Point> sig = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            double t = i / 40.0;
            sig.add(new Point(width * (0.12 + 0.3 * t), height * 0.8 + Math.sin(t * 12) * height * 0.02));
        }
        MatOfPoint sigPts = new MatOfPoint(sig.toArray(new Point[0]));
        int pen = Math.max(4, width / 150);
        Imgproc.polylines(clean, Collections.singletonList(sigPts), false, new Scalar(30, 50, 170), pen);
        Imgproc.polylines(printed, Collections.singletonList(sigPts), false, new Scalar(255), pen);
        Mat sigMask = Mat.zeros(height, width, CvType.CV_8U);
        Imgproc.polylines(sigMask, Collections.singletonList(sigPts), false, new Scalar(255), Math.max(1, pen / 3));

        // Dark photo, mid right.
        Rect photoRect = new Rect((int) (width * 0.55), (int) (height * 0.64), width / 4, height / 12);
        Imgproc.rectangle(clean, photoRect, new Scalar(60, 72, 85), -1);
        Imgproc.rectangle(printed, photoRect, new Scalar(255), -1);
        Mat photoMask = Mat.zeros(height, width, CvType.CV_8U);
        Imgproc.rectangle(photoMask, shrink(photoRect, 6), new Scalar(255), -1);

        Mat paperMask = new Mat();
        Core.bitwise_not(dilate(printed, Math.max(5, lineH)), paperMask);
        Mat inkInterior = erode(inkMask, 3);

        // Lighting field: gentle gradient, plus a soft-edged shadow over the lower-left part.
        Mat field = new Mat(height, width, CvType.CV_32F);
        Mat shadowMask = Mat.zeros(height, width, CvType.CV_8U);
        float[] row = new float[width];
        double sx = width * (0.45 + 0.1 * rnd.nextDouble());
        double sy = height * (0.35 + 0.1 * rnd.nextDouble());
        double soft = width * 0.06;
        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                double grad = 0.85 + 0.15 * (xx / (double) width) * (1 - 0.3 * yy / (double) height);
                // Distance into the shadow region (x < sx and y > sy), smoothed at the edge.
                double d = Math.min(sx - xx, yy - sy);
                double s = 1 / (1 + Math.exp(-d / (soft / 4)));
                row[xx] = (float) (light.exposure * grad * (1 - light.shadow * s));
            }
            field.put(yy, 0, row);
        }
        Imgproc.rectangle(shadowMask, new Point(0, sy + soft), new Point(sx - soft, height), new Scalar(255), -1);
        Mat shadowedPaper = new Mat();
        Core.bitwise_and(paperMask, shadowMask, shadowedPaper);

        Mat f = new Mat();
        clean.convertTo(f, CvType.CV_32FC3);
        List<Mat> planes = new ArrayList<>();
        Core.split(f, planes);
        for (int c = 0; c < 3; c++) {
            Core.multiply(planes.get(c), field, planes.get(c), light.cast[c]);
        }
        Core.merge(planes, f);
        Imgproc.GaussianBlur(f, f, new Size(0, 0), 1.0);
        Mat noise = new Mat(f.size(), CvType.CV_32FC3);
        Core.randn(noise, 0, light.noiseSigma);
        Core.add(f, noise, f);
        Mat photo8 = new Mat();
        f.convertTo(photo8, CvType.CV_8UC3);
        // JPEG round trip (the camera saves JPEG). imencode expects BGR; channel order is
        // irrelevant for the round trip as long as it is undone.
        MatOfByte jpg = new MatOfByte();
        Imgcodecs.imencode(".jpg", photo8, jpg, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, light.jpegQuality));
        Mat decoded = Imgcodecs.imdecode(jpg, Imgcodecs.IMREAD_COLOR);
        Mat rgba = new Mat();
        Imgproc.cvtColor(decoded, rgba, Imgproc.COLOR_RGB2RGBA);

        for (Mat m : planes) {
            m.release();
        }
        clean.release();
        printed.release();
        field.release();
        f.release();
        noise.release();
        photo8.release();
        decoded.release();
        shadowMask.release();
        return new DegradedPage(rgba, inkInterior, paperMask, stampMask, highlightMask, sigMask, photoMask, shadowedPaper);
    }

    private static void fill(Mat clean, Mat ink, Mat printed, Rect r, Scalar colour) {
        Imgproc.rectangle(clean, r, colour, -1);
        Imgproc.rectangle(ink, r, new Scalar(255), -1);
        Imgproc.rectangle(printed, r, new Scalar(255), -1);
    }

    private static Rect shrink(Rect r, int by) {
        return new Rect(r.x + by, r.y + by, Math.max(1, r.width - 2 * by), Math.max(1, r.height - 2 * by));
    }

    private static Mat dilate(Mat m, int k) {
        Mat out = new Mat();
        Imgproc.dilate(m, out, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(k, k)));
        return out;
    }

    private static Mat erode(Mat m, int k) {
        Mat out = new Mat();
        Imgproc.erode(m, out, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(k, k)));
        return out;
    }
}
