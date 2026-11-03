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
 * Input Mats are 4-channel images in Android bitmap memory order (RGBA). The enhancement
 * filters (Magic Color, Gray, B&amp;W) moved to {@link ScanFilters} in phase 2.</p>
 *
 * <p>Page detection ({@link #detectPage}) and auto-straighten ({@link #straighten}) are new; the
 * native single-channel contour search they replace missed pages with clutter on the edges.</p>
 *
 * <p>Plain OpenCV Java API only (no Android classes), so it can be unit-tested on the JVM.</p>
 */
public final class ScanImageOps {

    private ScanImageOps() {
    }

    /**
     * Single-channel gray image (BGR weights applied to RGBA data, as the native code did). Used
     * for skew estimation; the user-facing Gray filter is {@link ScanFilters#gray}.
     */
    public static Mat gray(Mat rgba) {
        Mat dst = new Mat();
        Imgproc.cvtColor(rgba, dst, Imgproc.COLOR_BGRA2GRAY);
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
        return rotate(rgba, angle);
    }

    /** Rotates about the centre by {@code angle} degrees (counter-clockwise), same size, edges replicated. */
    private static Mat rotate(Mat rgba, double angle) {
        Point centre = new Point(rgba.cols() / 2.0, rgba.rows() / 2.0);
        Mat m = Imgproc.getRotationMatrix2D(centre, angle, 1.0);
        Mat dst = new Mat();
        Imgproc.warpAffine(rgba, dst, m, rgba.size(), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE);
        m.release();
        return dst;
    }

    /** Detection runs on a copy whose longer side is this many pixels (speed, and less noise). */
    private static final int DETECT_SIDE = 500;
    /** A page quad must cover at least this share of the frame (smaller ones are false positives). */
    public static final double MIN_PAGE_AREA_FRACTION = 0.20;
    /** Quad corners this close (share of the frame size) to a frame border count as on the border. */
    private static final double FULL_FRAME_TOLERANCE = 0.02;
    /** Largest |cos| allowed at a corner: 0.4 accepts corner angles between about 66 and 114 degrees. */
    private static final double MAX_CORNER_COSINE = 0.4;

    /**
     * Finds the document. Returns {@code [x1, x2, x3, x4, y1, y2, y3, y4]} for the corners
     * top-left, top-right, bottom-left, bottom-right (the order {@link #warp} takes), or the whole
     * image when no page is found.
     */
    public static float[] findCorners(Mat rgba) {
        Point[] q = detectPage(rgba);
        if (q == null) {
            int w = rgba.cols();
            int h = rgba.rows();
            q = new Point[]{new Point(0, 0), new Point(w, 0), new Point(w, h), new Point(0, h)};
        }
        // q is TL, TR, BR, BL; the float layout is TL, TR, BL, BR.
        Point[] warpOrder = {q[0], q[1], q[3], q[2]};
        float[] result = new float[8];
        for (int i = 0; i < 4; i++) {
            result[i] = (float) warpOrder[i].x;
            result[i + 4] = (float) warpOrder[i].y;
        }
        return result;
    }

    /**
     * Finds the page quadrilateral, ordered top-left, top-right, bottom-right, bottom-left, in the
     * coordinates of {@code rgba}; null when no plausible page is visible.
     *
     * <p>Works on a downscaled luminance copy. A morphological close first wipes out dark detail
     * narrower than the kernel (text, pens, cables) so the page becomes a flat bright region; then
     * several edge maps are searched for the largest convex four-sided contour. Each contour is
     * replaced by its convex hull before polygon fitting, so an object overlapping the page edge
     * (a notch in the outline) does not break the quad.</p>
     */
    public static Point[] detectPage(Mat rgba) {
        int w = rgba.cols();
        int h = rgba.rows();
        if (w < 8 || h < 8) {
            return null;
        }
        double scale = Math.min(1.0, DETECT_SIDE / (double) Math.max(w, h));
        Mat small = new Mat();
        if (scale < 1) {
            Imgproc.resize(rgba, small, new Size(), scale, scale, Imgproc.INTER_AREA);
        } else {
            rgba.copyTo(small);
        }
        Mat gray = new Mat();
        Imgproc.cvtColor(small, gray, small.channels() == 4 ? Imgproc.COLOR_RGBA2GRAY : Imgproc.COLOR_RGB2GRAY);
        small.release();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
        Imgproc.morphologyEx(gray, gray, Imgproc.MORPH_CLOSE, kernel);
        Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

        List<Mat> edgeMaps = new ArrayList<>();
        // Low thresholds find low-contrast pages (white paper on a light desk); higher ones keep
        // a textured desk from swallowing the page outline.
        for (double[] t : new double[][]{{10, 30}, {30, 90}}) {
            Mat e = new Mat();
            Imgproc.Canny(gray, e, t[0], t[1]);
            Imgproc.dilate(e, e, kernel.submat(0, 3, 0, 3));
            edgeMaps.add(e);
        }
        Mat otsu = new Mat();
        Imgproc.threshold(gray, otsu, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        edgeMaps.add(otsu);
        kernel.release();
        gray.release();

        int sw = (int) Math.round(w * scale);
        int sh = (int) Math.round(h * scale);
        Point[] best = null;
        double bestArea = 0;
        for (Mat edges : edgeMaps) {
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            hierarchy.release();
            edges.release();
            for (MatOfPoint contour : contours) {
                Point[] quad = quadFromContour(contour);
                contour.release();
                if (quad == null) {
                    continue;
                }
                double area = area(quad);
                if (area > bestArea && isPlausiblePage(quad, sw, sh)) {
                    bestArea = area;
                    best = quad;
                }
            }
        }
        if (best == null) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            best[i] = new Point(clamp(best[i].x / scale, 0, w), clamp(best[i].y / scale, 0, h));
        }
        return best;
    }

    /** Convex hull, then polygon fit; the four corners (ordered) if the result is a quad, else null. */
    private static Point[] quadFromContour(MatOfPoint contour) {
        if (contour.total() < 4) {
            return null;
        }
        MatOfInt hullIdx = new MatOfInt();
        Imgproc.convexHull(contour, hullIdx);
        Point[] pts = contour.toArray();
        int[] idx = hullIdx.toArray();
        hullIdx.release();
        Point[] hull = new Point[idx.length];
        for (int i = 0; i < idx.length; i++) {
            hull[i] = pts[idx[i]];
        }
        MatOfPoint2f hull2f = new MatOfPoint2f(hull);
        double perimeter = Imgproc.arcLength(hull2f, true);
        if (perimeter < 40) {
            hull2f.release();
            return null;
        }
        MatOfPoint2f approx = new MatOfPoint2f();
        Imgproc.approxPolyDP(hull2f, approx, perimeter * 0.02, true);
        hull2f.release();
        Point[] quad = approx.total() == 4 ? approx.toArray() : null;
        approx.release();
        return quad == null ? null : orderCorners(quad);
    }

    /**
     * True when {@code quad} (any order) looks like a page in a {@code w} x {@code h} frame: convex,
     * corners not too far from right angles, at least {@link #MIN_PAGE_AREA_FRACTION} of the frame,
     * and not simply the frame itself.
     */
    public static boolean isPlausiblePage(Point[] quad, int w, int h) {
        if (quad == null || quad.length != 4 || w <= 0 || h <= 0) {
            return false;
        }
        Point[] q = orderCorners(quad);
        MatOfPoint contour = new MatOfPoint(q);
        boolean convex = Imgproc.isContourConvex(contour);
        contour.release();
        if (!convex) {
            return false;
        }
        if (area(q) < MIN_PAGE_AREA_FRACTION * w * h) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (Math.abs(angle(q[(i + 1) % 4], q[(i + 3) % 4], q[i])) > MAX_CORNER_COSINE) {
                return false;
            }
        }
        double tx = FULL_FRAME_TOLERANCE * w;
        double ty = FULL_FRAME_TOLERANCE * h;
        // An edge running along the frame border is the frame, not the page: a shadow or the
        // desk split by a threshold, or a page cut off by the frame (left to the user for now).
        for (int i = 0; i < 4; i++) {
            Point a = q[i];
            Point b = q[(i + 1) % 4];
            if ((a.x <= tx && b.x <= tx) || (a.x >= w - tx && b.x >= w - tx)
                    || (a.y <= ty && b.y <= ty) || (a.y >= h - ty && b.y >= h - ty)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Orders four corners as top-left, top-right, bottom-right, bottom-left (clockwise on screen).
     * Sorts by angle around the centroid, then starts at the corner with the smallest x + y, so it
     * also works for strongly rotated quads.
     */
    public static Point[] orderCorners(Point[] pts) {
        final double cx = (pts[0].x + pts[1].x + pts[2].x + pts[3].x) / 4;
        final double cy = (pts[0].y + pts[1].y + pts[2].y + pts[3].y) / 4;
        Point[] sorted = pts.clone();
        java.util.Arrays.sort(sorted, (a, b) -> Double.compare(
                Math.atan2(a.y - cy, a.x - cx), Math.atan2(b.y - cy, b.x - cx)));
        // atan2 with y pointing down sorts clockwise on screen. Rotate so TL comes first.
        int start = 0;
        for (int i = 1; i < 4; i++) {
            if (sorted[i].x + sorted[i].y < sorted[start].x + sorted[start].y) {
                start = i;
            }
        }
        Point[] out = new Point[4];
        for (int i = 0; i < 4; i++) {
            out[i] = sorted[(start + i) % 4];
        }
        return out;
    }

    /** What {@link #straighten} did. */
    public static final class Straightened {
        /** The straightened page, or null when nothing was changed (no page found, no skew). */
        public final Mat image;
        /** Page corners used for the warp (TL, TR, BR, BL in input coordinates), or null if none. */
        public final Point[] quad;
        /** Deskew rotation applied after the warp, in degrees (0 when none). */
        public final double skewDegrees;

        Straightened(Mat image, Point[] quad, double skewDegrees) {
            this.image = image;
            this.quad = quad;
            this.skewDegrees = skewDegrees;
        }
    }

    /**
     * Auto-straighten: finds the page, perspective-warps it to a rectangle, then removes remaining
     * text-line skew. {@code hint} is an optional quad from another detector (e.g. SmartCropper);
     * it is used when {@link #isPlausiblePage plausible}, otherwise {@link #detectPage} runs. When
     * no page is found the whole image is only deskewed.
     */
    public static Straightened straighten(Mat rgba, Point[] hint) {
        Point[] quad = isPlausiblePage(hint, rgba.cols(), rgba.rows()) ? orderCorners(hint) : detectPage(rgba);
        Mat page = rgba;
        if (quad != null) {
            page = warp(rgba, (float) quad[0].x, (float) quad[0].y, (float) quad[1].x, (float) quad[1].y,
                    (float) quad[3].x, (float) quad[3].y, (float) quad[2].x, (float) quad[2].y);
        }
        double angle = estimateSkew(page);
        if (Math.abs(angle) < MIN_DESKEW_DEGREES) {
            angle = 0;
        }
        Mat out = page;
        if (angle != 0) {
            out = rotate(page, angle);
            if (page != rgba) {
                page.release();
            }
        }
        return new Straightened(out == rgba ? null : out, quad, angle);
    }

    private static double area(Point[] q) {
        double s = 0;
        for (int i = 0; i < q.length; i++) {
            Point p = q[i];
            Point n = q[(i + 1) % q.length];
            s += p.x * n.y - n.x * p.y;
        }
        return Math.abs(s) / 2;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Cosine of the angle at {@code pt0} between the rays to {@code pt1} and {@code pt2}. */
    private static double angle(Point pt1, Point pt2, Point pt0) {
        double dx1 = pt1.x - pt0.x;
        double dy1 = pt1.y - pt0.y;
        double dx2 = pt2.x - pt0.x;
        double dy2 = pt2.y - pt0.y;
        return (dx1 * dx2 + dy1 * dy2) / Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10);
    }
}
