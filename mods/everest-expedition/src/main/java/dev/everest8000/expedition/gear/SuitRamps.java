package dev.everest8000.expedition.gear;

/**
 * Turns a dye colour into the shading ramps a dyed down suit is painted with: six steps (outline, seam, shadow, base,
 * light, sheen) built in OKLCH, so highlights lift and turn toward yellow and shadows deepen and turn toward
 * blue-violet the way the hand-painted gold suit does. The second ramp is for the yoke, collar and cuffs: a deeper,
 * warmer variant of the colour, ramped the same way.
 *
 * <p>A port of ramp() and yoke_base() in art/suit3d_colours.py, which the previews use; SuitRampsTest checks the two
 * agree on the colours that script writes out.
 */
public final class SuitRamps {
    public static final int STEPS = 6;
    /** Ramp classes in the dye map: 0 the colour's own ramp, 1 the yoke's. */
    public static final int MAIN = 0, YOKE = 1;

    private static final double[] SHADOW_L = {0.47, 0.676, 0.855};
    private static final double[][] LIGHT_L = {{0.29, 0.07}, {0.61, 0.14}};
    private static final double[] CHROMA = {0.52, 0.73, 0.91, 1.0, 0.97, 0.64};
    private static final double[] HUE_SHIFT = {17.0, 11.8, 8.7, 0.0, 5.2, 10.6};
    private static final double DARK_POLE = 290.0, LIGHT_POLE = 100.0;
    private static final double YOKE_L = 0.80, YOKE_C = 0.84, YOKE_HUE = 20.6;

    private SuitRamps() {}

    /** [class][step] as 0xRRGGBB: the colour's ramp, then the yoke's. */
    public static int[][] ramps(int rgb) {
        return new int[][]{ramp(rgb), ramp(yokeBase(rgb))};
    }

    /** Six colours, outline to sheen; the dye colour itself is the base (step 3). */
    public static int[] ramp(int rgb) {
        double[] lch = oklch(rgb);
        double l = lch[0], c = lch[1], h = lch[2];
        int[] out = new int[STEPS];
        for (int i = 0; i < STEPS; i++) {
            double li, hi;
            if (i < 3) {
                li = l * SHADOW_L[i];
                hi = toward(h, DARK_POLE, HUE_SHIFT[i]);
            } else if (i == 3) {
                li = l;
                hi = h;
            } else {
                double[] k = LIGHT_L[i - 4];
                li = l + Math.min(k[0] * (1.0 - l), k[1]);
                hi = toward(h, LIGHT_POLE, HUE_SHIFT[i]);
            }
            out[i] = fromOklch(li, c * CHROMA[i], hi);
        }
        out[3] = rgb & 0xFFFFFF;
        return out;
    }

    /** The yoke's colour: darker, a little less saturated, turned toward the dark pole. */
    public static int yokeBase(int rgb) {
        double[] lch = oklch(rgb);
        return fromOklch(lch[0] * YOKE_L, lch[1] * YOKE_C, toward(lch[2], DARK_POLE, YOKE_HUE));
    }

    // ---- OKLab

    private static double mod360(double v) {
        double m = v % 360.0;
        return m < 0 ? m + 360.0 : m;
    }

    /** Turn hue h toward pole along the shorter way round, by at most amount, never past it. */
    private static double toward(double h, double pole, double amount) {
        double d = mod360(pole - h + 540.0) - 180.0;
        double step = Math.min(amount, Math.abs(d));
        return mod360(h + Math.copySign(step, d));
    }

    private static double toLinear(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double toSrgb(double c) {
        double v = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return v * 255.0;
    }

    private static double cbrt(double x) {
        return Math.copySign(Math.pow(Math.abs(x), 1.0 / 3.0), x);
    }

    /** {L, C, h in degrees}. */
    static double[] oklch(int rgb) {
        double r = toLinear((rgb >> 16) & 255), g = toLinear((rgb >> 8) & 255), b = toLinear(rgb & 255);
        double l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        double lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
        double a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        return new double[]{lightness, Math.hypot(a, bb), mod360(Math.toDegrees(Math.atan2(bb, a)))};
    }

    private static double[] linearRgb(double lightness, double chroma, double hue) {
        double a = chroma * Math.cos(Math.toRadians(hue));
        double b = chroma * Math.sin(Math.toRadians(hue));
        double l = lightness + 0.3963377774 * a + 0.2158037573 * b;
        double m = lightness - 0.1055613458 * a - 0.0638541728 * b;
        double s = lightness - 0.0894841775 * a - 1.2914855480 * b;
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        return new double[]{
                4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }

    private static boolean inGamut(double[] lin) {
        for (double v : lin) if (v < -1e-6 || v > 1 + 1e-6) return false;
        return true;
    }

    /** sRGB for an OKLCH colour, its chroma reduced at the same lightness and hue until it fits. */
    static int fromOklch(double lightness, double chroma, double hue) {
        lightness = Math.min(1.0, Math.max(0.0, lightness));
        double[] lin = linearRgb(lightness, chroma, hue);
        if (!inGamut(lin)) {
            double lo = 0.0, hi = chroma;
            for (int i = 0; i < 24; i++) {
                double mid = (lo + hi) / 2;
                if (inGamut(linearRgb(lightness, mid, hue))) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            lin = linearRgb(lightness, lo, hue);
        }
        int rgb = 0;
        for (double v : lin) {
            int c = (int) Math.floor(toSrgb(Math.min(1.0, Math.max(0.0, v))) + 0.5);
            rgb = (rgb << 8) | Math.min(255, Math.max(0, c));
        }
        return rgb;
    }
}
