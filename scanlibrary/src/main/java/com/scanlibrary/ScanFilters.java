package com.scanlibrary;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Document enhancement filters: make a photographed page look like a photocopy.
 *
 * <p>All three filters start with <b>background division</b>: the paper's brightness is estimated
 * at every point (a downscaled morphological close erases the ink, then a median and Gaussian
 * smooth it) and the image is divided by it. Paper becomes white everywhere, so shadows, uneven
 * lighting and a colour cast from the light disappear while ink keeps its contrast to the paper.
 * Low-light photos are denoised first when their measured noise is high.</p>
 *
 * <ul>
 *     <li>{@link #magicColor}: whitened page, neutral ink pushed to crisp grey/black, coloured
 *     marks (stamps, highlighter, signatures in blue pen) kept in colour, then sharpened.</li>
 *     <li>{@link #gray}: whitened, tone-mapped and sharpened luminance.</li>
 *     <li>{@link #blackAndWhite}: adaptive (local) threshold of the whitened luminance, so a shadow
 *     no longer turns into a black block as it did with one global Otsu threshold.</li>
 * </ul>
 *
 * <p>Inputs are RGBA {@link Mat}s (Android bitmap order). Plain OpenCV only, so everything here is
 * unit-tested on the JVM.</p>
 */
public final class ScanFilters {

    private ScanFilters() {
    }

    /** The background is estimated on a copy whose longer side is this many pixels. */
    private static final int BACKGROUND_SIDE = 256;
    /**
     * The background estimate is never darker than this share of the paper level. Dark areas larger
     * than the closing kernel (a photo or a black banner on the page) would otherwise be taken as
     * "background" and washed out to white; shadows rarely darken paper below about half.
     */
    private static final double MIN_BACKGROUND_FRACTION = 0.4;
    /** Background darker than this share of the paper level is printed, not shadow (see fillDarkAreas). */
    private static final double DARK_AREA_FRACTION = 0.4;
    /** Paper within this many noise standard deviations of its background level maps to pure white. */
    private static final double WHITE_POINT_SIGMAS = 3.0;
    /** Minimum headroom below the background level that still maps to white. */
    private static final double MIN_WHITE_HEADROOM = 0.06;
    /** Measured noise (8-bit levels) above which a page is denoised before whitening. */
    static final double DENOISE_ABOVE_SIGMA = 4.0;
    /** Chroma (max - min channel, 0..255) below which a pixel is treated as neutral ink... */
    private static final double NEUTRAL_CHROMA = 28;
    /** ...and above which its colour is kept in full. Linear blend in between. */
    private static final double COLOUR_CHROMA = 60;
    /** Unsharp-mask amount applied after whitening. */
    private static final double SHARPEN_AMOUNT = 0.7;

    /** Tone-curve gamma after whitening: deepens ink, keeps paper at 255. */
    private static final double TONE_GAMMA = 1.5;
    /** Built on first use: creating a Mat before the OpenCV native library is loaded would fail. */
    private static Mat toneLut;

    // ----------------------------------------------------------------------------------------
    // Filters

    /** Colour scan: white paper, crisp neutral text, coloured marks kept. Returns RGBA. */
    public static Mat magicColor(Mat rgba) {
        Mat rgb = toRgb(rgba);
        denoiseIfNoisy(rgb);
        Mat white = divideByBackground(rgb, noiseSigma(rgb), null);
        rgb.release();
        Core.LUT(white, toneLut(), white);

        // Neutral version of every pixel: its luminance, as grey.
        Mat lum = new Mat();
        Imgproc.cvtColor(white, lum, Imgproc.COLOR_RGB2GRAY);
        Mat neutral = new Mat();
        Imgproc.cvtColor(lum, neutral, Imgproc.COLOR_GRAY2RGB);
        lum.release();

        // Weight of the coloured version: 0 for neutral pixels, 1 for clearly coloured ones.
        // Chroma (max - min) is used rather than HSV saturation, which is unstable for dark ink.
        Mat weight = chromaWeight(white);
        Mat out = blend(white, neutral, weight);
        white.release();
        neutral.release();
        weight.release();

        sharpen(out);
        Mat result = new Mat();
        Imgproc.cvtColor(out, result, Imgproc.COLOR_RGB2RGBA);
        out.release();
        return result;
    }

    /** Grey scan: whitened, tone-mapped and sharpened luminance. Returns a single-channel Mat. */
    public static Mat gray(Mat rgba) {
        Mat lum = whitenedLuminance(rgba, null);
        Core.LUT(lum, toneLut(), lum);
        sharpen(lum);
        return lum;
    }

    /** Black-and-white scan: every pixel is 0 or 255. Returns a single-channel Mat. */
    public static Mat blackAndWhite(Mat rgba) {
        double[] noise = new double[1];
        Mat lum = whitenedLuminance(rgba, noise);
        // Block size about a text line or two. C (how much darker than its surroundings a pixel
        // must be to count as ink) grows with the noise left after whitening, which amplifies the
        // noise of dim photos; a fixed C leaves such pages speckled.
        int block = odd(Math.max(15, Math.max(lum.cols(), lum.rows()) / 40));
        double c = Math.min(60, 12 + 3 * noise[0]);
        Mat bw = new Mat();
        Imgproc.adaptiveThreshold(lum, bw, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, c);
        // The adaptive threshold alone hollows out strokes wider than a few pixels (inside them the
        // local mean is dark too). After background division paper is uniformly 255, so a global
        // threshold just below the paper noise is safe and keeps wide or faint strokes solid.
        double global = 255 - Math.max(30, 4 * noise[0]);
        Mat dark = new Mat();
        Imgproc.threshold(lum, dark, global, 255, Imgproc.THRESH_BINARY);
        Core.bitwise_and(bw, dark, bw);
        lum.release();
        dark.release();
        return bw;
    }

    // ----------------------------------------------------------------------------------------
    // Building blocks (package-private for tests)

    /**
     * Luminance divided by its own background (paper = 255), denoised first when noisy.
     *
     * @param noiseOut if not null, receives the noise standard deviation on paper after whitening
     */
    static Mat whitenedLuminance(Mat rgba, double[] noiseOut) {
        Mat lum = new Mat();
        Imgproc.cvtColor(rgba, lum, rgba.channels() == 4 ? Imgproc.COLOR_RGBA2GRAY : Imgproc.COLOR_RGB2GRAY);
        double sigma = noiseSigma(lum);
        if (sigma > DENOISE_ABOVE_SIGMA) {
            Photo.fastNlMeansDenoising(lum, lum, (float) (1.2 * sigma), 5, 11);
            sigma = noiseSigma(lum);
        }
        Mat white = divideByBackground(lum, sigma, noiseOut);
        lum.release();
        return white;
    }

    /**
     * Per-channel paper brightness at every pixel, same size and type as {@code img} (8-bit, 1 or 3
     * channels). See the class comment.
     */
    static Mat estimateBackground(Mat img) {
        int w = img.cols();
        int h = img.rows();
        double scale = Math.min(1.0, BACKGROUND_SIDE / (double) Math.max(w, h));
        Mat small = new Mat();
        Imgproc.resize(img, small, new Size(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))),
                0, 0, Imgproc.INTER_AREA);
        // Kernel wider than text strokes (body text and headings at this scale), smaller than the
        // shadows to keep.
        int k = odd(Math.max(3, Math.max(small.cols(), small.rows()) / 25));
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(k, k));
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel);
        kernel.release();
        fillDarkAreas(small, k);
        Imgproc.medianBlur(small, small, Math.min(k, 255));
        // Light smoothing only: a wider blur spreads bright paper into the edge of a shadow and
        // leaves a grey band along it.
        Imgproc.GaussianBlur(small, small, new Size(0, 0), k / 4.0);

        // Floor at a share of the paper level (per channel), see MIN_BACKGROUND_FRACTION.
        List<Mat> planes = new ArrayList<>();
        Core.split(small, planes);
        for (Mat p : planes) {
            double paper = percentile(p, 0.9);
            Core.max(p, new Scalar(Math.max(1, MIN_BACKGROUND_FRACTION * paper)), p);
        }
        Core.merge(planes, small);
        for (Mat p : planes) {
            p.release();
        }
        Mat bg = new Mat();
        Imgproc.resize(small, bg, img.size(), 0, 0, Imgproc.INTER_LINEAR);
        small.release();
        return bg;
    }

    /**
     * Replaces, in a closed background estimate, areas darker than {@link #DARK_AREA_FRACTION} of
     * the paper level with paper interpolated from around them. Such areas are printed (a photo, a
     * black banner, a dense table header) and larger than the closing kernel; dividing by them
     * would wash them out to white with a dark rim. Shadows are lighter than this and are kept.
     */
    private static void fillDarkAreas(Mat small, int k) {
        Mat lum = small;
        if (small.channels() > 1) {
            lum = new Mat();
            Imgproc.cvtColor(small, lum, Imgproc.COLOR_RGB2GRAY);
        }
        double paper = percentile(lum, 0.9);
        Mat mask = new Mat();
        Imgproc.threshold(lum, mask, DARK_AREA_FRACTION * paper, 255, Imgproc.THRESH_BINARY_INV);
        if (lum != small) {
            lum.release();
        }
        int dark = Core.countNonZero(mask);
        // Nothing to do, or most of the frame is dark (not a page on paper): leave it alone.
        if (dark == 0 || dark > 0.5 * mask.total()) {
            mask.release();
            return;
        }
        // Grow the mask over the transition where the close mixed paper and dark area.
        Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(k, k)));
        Photo.inpaint(small, mask, small, k / 2.0, Photo.INPAINT_TELEA);
        mask.release();
    }

    /**
     * {@code img / background}, scaled so paper is 255. The white point sits a little below the
     * background level, by {@link #WHITE_POINT_SIGMAS} times the measured noise, so paper grain and
     * sensor noise clip to clean white instead of leaving a speckled page.
     */
    static Mat divideByBackground(Mat img, double noiseSigma, double[] noiseOut) {
        Mat bg = estimateBackground(img);
        Mat bgLum = bg;
        if (bg.channels() > 1) {
            bgLum = new Mat();
            Imgproc.cvtColor(bg, bgLum, Imgproc.COLOR_RGB2GRAY);
        }
        double paper = Math.max(1, percentile(bgLum, 0.5));
        if (bgLum != bg) {
            bgLum.release();
        }
        double headroom = Math.min(0.3, Math.max(MIN_WHITE_HEADROOM, WHITE_POINT_SIGMAS * noiseSigma / paper));
        if (noiseOut != null) {
            // Paper is scaled by about 255 / ((1 - headroom) * paper), and so is its noise.
            noiseOut[0] = noiseSigma * 255.0 / ((1 - headroom) * paper);
        }
        Mat out = new Mat();
        Core.divide(img, bg, out, 255.0 / (1 - headroom), img.depth());
        bg.release();
        return out;
    }

    /** Noise is measured on blocks of this many pixels square. */
    private static final int NOISE_BLOCK = 16;

    /**
     * Standard deviation of the visible noise in 8-bit levels, measured on paper.
     *
     * <p>The residual after a Gaussian blur (sigma 3) is mostly noise in flat areas, including the
     * blotchy mid-frequency noise JPEG leaves behind (a per-pixel Laplacian estimator misses that).
     * Its standard deviation is taken per {@link #NOISE_BLOCK}-pixel block on a central crop; only
     * the brighter half of the blocks count (paper: in dark ink the noise is clipped at black and
     * flattened by JPEG), and of those the 25th percentile, which skips blocks containing edges.</p>
     */
    static double noiseSigma(Mat img) {
        Mat lum = img;
        if (img.channels() > 1) {
            lum = new Mat();
            Imgproc.cvtColor(img, lum, img.channels() == 4 ? Imgproc.COLOR_RGBA2GRAY : Imgproc.COLOR_RGB2GRAY);
        }
        int cw = Math.min(lum.cols(), 1024) / NOISE_BLOCK * NOISE_BLOCK;
        int ch = Math.min(lum.rows(), 1024) / NOISE_BLOCK * NOISE_BLOCK;
        if (cw == 0 || ch == 0) {
            if (lum != img) {
                lum.release();
            }
            return 0;
        }
        Mat crop = new Mat();
        lum.submat(new Rect((lum.cols() - cw) / 2, (lum.rows() - ch) / 2, cw, ch)).convertTo(crop, CvType.CV_32F);
        if (lum != img) {
            lum.release();
        }
        Mat blur = new Mat();
        Imgproc.GaussianBlur(crop, blur, new Size(0, 0), 3);
        Mat res2 = new Mat();
        Core.subtract(crop, blur, res2);
        Core.multiply(res2, res2, res2);
        // Block means of brightness and of squared residual (INTER_AREA averages whole blocks).
        Size blocks = new Size(cw / NOISE_BLOCK, ch / NOISE_BLOCK);
        Mat bright = new Mat();
        Mat var = new Mat();
        Imgproc.resize(blur, bright, blocks, 0, 0, Imgproc.INTER_AREA);
        Imgproc.resize(res2, var, blocks, 0, 0, Imgproc.INTER_AREA);
        float[] b = new float[(int) bright.total()];
        float[] v = new float[(int) var.total()];
        bright.get(0, 0, b);
        var.get(0, 0, v);
        crop.release();
        blur.release();
        res2.release();
        bright.release();
        var.release();

        float[] sortedB = b.clone();
        Arrays.sort(sortedB);
        float median = sortedB[sortedB.length / 2];
        float[] sd = new float[v.length];
        int n = 0;
        for (int i = 0; i < v.length; i++) {
            // Brighter half, but not clipped white (no measurable noise there).
            if (b[i] >= median && b[i] < 250) {
                sd[n++] = (float) Math.sqrt(v[i]);
            }
        }
        if (n == 0) {
            return 0;
        }
        Arrays.sort(sd, 0, n);
        return sd[n / 4];
    }

    /** Denoises an RGB image in place when its noise is above {@link #DENOISE_ABOVE_SIGMA}. */
    static void denoiseIfNoisy(Mat rgb) {
        double sigma = noiseSigma(rgb);
        if (sigma <= DENOISE_ABOVE_SIGMA) {
            return;
        }
        // Luminance gets non-local means; chroma noise is coarse and invisible after whitening,
        // so a cheap blur is enough there (fastNlMeansDenoisingColored is several times slower).
        Mat ycc = new Mat();
        Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb);
        List<Mat> planes = new ArrayList<>();
        Core.split(ycc, planes);
        Photo.fastNlMeansDenoising(planes.get(0), planes.get(0), (float) (1.2 * sigma), 5, 11);
        Imgproc.medianBlur(planes.get(1), planes.get(1), 5);
        Imgproc.medianBlur(planes.get(2), planes.get(2), 5);
        Core.merge(planes, ycc);
        Imgproc.cvtColor(ycc, rgb, Imgproc.COLOR_YCrCb2RGB);
        for (Mat p : planes) {
            p.release();
        }
        ycc.release();
    }

    /** Unsharp mask in place; the blur radius grows with the image so the look is size-independent. */
    static void sharpen(Mat img) {
        double sigma = Math.max(1.0, Math.max(img.cols(), img.rows()) / 1500.0);
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(img, blurred, new Size(0, 0), sigma);
        Core.addWeighted(img, 1 + SHARPEN_AMOUNT, blurred, -SHARPEN_AMOUNT, 0, img);
        blurred.release();
    }

    // ----------------------------------------------------------------------------------------
    // Helpers

    private static Mat toRgb(Mat rgba) {
        Mat rgb = new Mat();
        if (rgba.channels() == 4) {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
        } else if (rgba.channels() == 1) {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_GRAY2RGB);
        } else {
            rgba.copyTo(rgb);
        }
        return rgb;
    }

    /** 0..1 float weight per pixel from its chroma, lightly smoothed to avoid colour fringes. */
    private static Mat chromaWeight(Mat rgb) {
        List<Mat> c = new ArrayList<>();
        Core.split(rgb, c);
        Mat max = new Mat();
        Mat min = new Mat();
        Core.max(c.get(0), c.get(1), max);
        Core.max(max, c.get(2), max);
        Core.min(c.get(0), c.get(1), min);
        Core.min(min, c.get(2), min);
        Mat chroma = new Mat();
        Core.subtract(max, min, chroma);
        Mat weight = new Mat();
        chroma.convertTo(weight, CvType.CV_32F, 1.0 / (COLOUR_CHROMA - NEUTRAL_CHROMA),
                -NEUTRAL_CHROMA / (COLOUR_CHROMA - NEUTRAL_CHROMA));
        Core.min(weight, new Scalar(1), weight);
        Core.max(weight, new Scalar(0), weight);
        Imgproc.GaussianBlur(weight, weight, new Size(3, 3), 0);
        for (Mat m : c) {
            m.release();
        }
        max.release();
        min.release();
        chroma.release();
        return weight;
    }

    /** {@code weight * a + (1 - weight) * b} for 3-channel 8-bit images and a 1-channel float weight. */
    private static Mat blend(Mat a, Mat b, Mat weight) {
        Mat fa = new Mat();
        Mat fb = new Mat();
        a.convertTo(fa, CvType.CV_32FC3);
        b.convertTo(fb, CvType.CV_32FC3);
        Mat w3 = new Mat();
        Core.merge(Arrays.asList(weight, weight, weight), w3);
        Core.subtract(fa, fb, fa);        // a - b
        Core.multiply(fa, w3, fa);        // w (a - b)
        Core.add(fb, fa, fb);             // b + w (a - b)
        Mat out = new Mat();
        fb.convertTo(out, CvType.CV_8UC3);
        fa.release();
        fb.release();
        w3.release();
        return out;
    }

    private static synchronized Mat toneLut() {
        if (toneLut == null) {
            Mat lut = new Mat(1, 256, CvType.CV_8U);
            byte[] v = new byte[256];
            for (int i = 0; i < 256; i++) {
                v[i] = (byte) Math.round(255 * Math.pow(i / 255.0, TONE_GAMMA));
            }
            lut.put(0, 0, v);
            toneLut = lut;
        }
        return toneLut;
    }

    /** Value below which {@code p} (0..1) of the pixels of an 8-bit single-channel Mat lie. */
    private static double percentile(Mat gray8, double p) {
        byte[] data = new byte[(int) gray8.total()];
        Mat cont = gray8.isContinuous() ? gray8 : gray8.clone();
        cont.get(0, 0, data);
        if (cont != gray8) {
            cont.release();
        }
        int[] hist = new int[256];
        for (byte b : data) {
            hist[b & 0xff]++;
        }
        long target = (long) Math.ceil(p * data.length);
        long acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= target) {
                return i;
            }
        }
        return 255;
    }

    private static int odd(int v) {
        return v % 2 == 0 ? v + 1 : v;
    }
}
