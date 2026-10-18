package com.gathin.tvhome;

import java.util.Arrays;

/**
 * Chooses the ink for text laid straight on a painting, with no box, so it reads at the WCAG AA target of
 * 4.5:1. Input is the painting's pixels under the text as they will be on screen. Contrast is WCAG contrast
 * of relative luminance (linearised sRGB) against the 10th percentile of the area for dark ink and the 90th
 * for white ink, so a few stray highlights or shadows don't count.
 * <ul>
 * <li>LIGHT_CALM: light and even, dark walnut ink with a faint light halo and no shade.
 * <li>DARK and BUSY: white ink over a corner shade just strong enough to reach the target. The shade
 * multiplies luminance by (1 - alpha * weight) in linear light, weight falling from 1 near the corner to 0
 * at its edge; alpha is capped at MAX_ALPHA. A light area that would need a heavy shade gets dark ink
 * instead when that reads, and an area that misses even at the cap gets a tight dark text shadow.
 * </ul>
 * Pure Java, so the JVM test proves the same rule the TV runs.
 */
final class InkRule {

    enum Mode { LIGHT_CALM, DARK, BUSY }

    static final double TARGET = 4.5;
    /** The rule aims a little higher: the drawn gradient and sRGB blending only approximate the model. */
    static final double AIM = 4.7;
    static final int DARK_INK = 0xFF231812;
    static final int LIGHT_INK = 0xF7FFFFFF;
    static final int LIGHT_HALO = 0x66FFFFFF;
    /** Most the shade may take off the luminance (an overlay of 58% black); more reads as a dark smudge. */
    static final double MAX_ALPHA = 0.85;
    /** Light and calm: p90 - p10 at most this, and p10 light enough for dark ink to reach AIM. */
    static final double CALM_SPREAD = 0.45;
    /** A light region that would need more shade than this for white ink gets dark ink if it reads. */
    static final double PREFER_DARK_ABOVE = 0.35;
    /** A median at least this light, or a spread this wide, makes a DARK region BUSY (just a label). */
    static final double MID_TONE = 0.18, BUSY_SPREAD = 0.35;

    /** The shade: an ellipse from a corner of the screen, solid out to INNER of its radius, then feathered. */
    static final float SHADE_INNER = 0.55f;
    static final float TOP_RX_DP = 760, TOP_RY_DP = 520;
    static final float CAPTION_RX_DP = 900, CAPTION_RY_DP = 240;
    /** Stops in the drawn gradient. */
    static final int SHADE_STOPS = 12;

    /** One decision: the ink, its text shadow, and the shade alpha behind it. */
    static final class Ink {
        static final Ink DEFAULT = new Ink(Mode.DARK, 0.45, LIGHT_INK, 0x66000000, 4, 0, false);

        final Mode mode;
        final double alpha;
        final int color, shadowColor;
        final float shadowRadius, shadowDy;
        /** True when white ink at MAX_ALPHA still misses the target and leans on a tight dark shadow. */
        final boolean floor;

        Ink(Mode mode, double alpha, int color, int shadowColor, float shadowRadius, float shadowDy, boolean floor) {
            this.mode = mode;
            this.alpha = alpha;
            this.color = color;
            this.shadowColor = shadowColor;
            this.shadowRadius = shadowRadius;
            this.shadowDy = shadowDy;
            this.floor = floor;
        }

        boolean dark() {
            return color == DARK_INK;
        }
    }

    /** Pixels of a decoded picture. */
    interface Pixels {
        int argb(int x, int y);
    }

    /** Pixels under the text and where each sits in the shade (0 at the corner, 1 at its edge). */
    static final class Samples {
        final int[] argb;
        final float[] reach;

        Samples(int[] argb, float[] reach) {
            this.argb = argb;
            this.reach = reach;
        }
    }

    private static final double[] LINEAR = new double[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LINEAR[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    private InkRule() {
    }

    static Ink decide(Samples s) {
        int n = s.argb.length;
        if (n == 0) return Ink.DEFAULT;
        double[] lum = new double[n], weight = new double[n];
        for (int i = 0; i < n; i++) {
            lum[i] = luminance(s.argb[i]);
            weight[i] = weight(s.reach[i]);
        }
        double[] sorted = lum.clone();
        Arrays.sort(sorted);
        double p10 = percentile(sorted, 0.1), p50 = percentile(sorted, 0.5), p90 = percentile(sorted, 0.9);
        double spread = p90 - p10;
        boolean darkInkReads = contrast(p10, luminance(DARK_INK)) >= AIM;
        if (darkInkReads && spread <= CALM_SPREAD) return lightCalm();

        double maxLum = (inkLuminance(LIGHT_INK) + 0.05) / AIM - 0.05;
        Mode mode = p50 >= MID_TONE || spread > BUSY_SPREAD ? Mode.BUSY : Mode.DARK;
        double alpha = whiteAlpha(lum, weight, maxLum);
        // Light but busy: dark ink when it reads, rather than a heavy shade on a light painting.
        if (darkInkReads && alpha > PREFER_DARK_ABOVE) return new Ink(Mode.BUSY, 0, DARK_INK, LIGHT_HALO, 3, 0, false);
        if (alpha > MAX_ALPHA) return new Ink(mode, MAX_ALPHA, LIGHT_INK, 0xCC000000, 2, 1, true);
        return new Ink(mode, alpha, LIGHT_INK, 0x66000000, 4, 0, false);
    }

    /** The least shade that brings the p90 under maxLum, or more than MAX_ALPHA if none within it does. */
    private static double whiteAlpha(double[] lum, double[] weight, double maxLum) {
        if (shadedP90(lum, weight, 0) <= maxLum) return 0;
        if (shadedP90(lum, weight, MAX_ALPHA) > maxLum) return Double.POSITIVE_INFINITY;
        double lo = 0, hi = MAX_ALPHA;
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) / 2;
            if (shadedP90(lum, weight, mid) > maxLum) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    private static Ink lightCalm() {
        return new Ink(Mode.LIGHT_CALM, 0, DARK_INK, LIGHT_HALO, 3, 0, false);
    }

    private static double shadedP90(double[] lum, double[] weight, double alpha) {
        double[] shaded = new double[lum.length];
        for (int i = 0; i < lum.length; i++) shaded[i] = lum[i] * (1 - alpha * weight[i]);
        Arrays.sort(shaded);
        return percentile(shaded, 0.9);
    }

    static double percentile(double[] sorted, double q) {
        return sorted[(int) Math.floor(q * (sorted.length - 1))];
    }

    /** WCAG relative luminance of an opaque colour. */
    static double luminance(int argb) {
        return 0.2126 * LINEAR[(argb >> 16) & 0xFF] + 0.7152 * LINEAR[(argb >> 8) & 0xFF] + 0.0722 * LINEAR[argb & 0xFF];
    }

    /** Luminance of a translucent ink, taken over black so it never flatters white ink. */
    static double inkLuminance(int argb) {
        return luminance(blend(argb & 0xFFFFFF | 0xFF000000, 0xFF000000, ((argb >>> 24) / 255f)));
    }

    static double contrast(double a, double b) {
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** Shade weight at a reach from the corner: 1 inside SHADE_INNER, then a smoothstep down to 0 at 1. */
    static double weight(double reach) {
        if (reach <= SHADE_INNER) return 1;
        if (reach >= 1) return 0;
        double t = (reach - SHADE_INNER) / (1 - SHADE_INNER);
        return 1 - t * t * (3 - 2 * t);
    }

    /**
     * Alpha of a black overlay that cuts linear luminance by about the given share; sRGB blending works on
     * gamma-encoded values, so a 34% black takes off about 60%.
     */
    static double overlayAlpha(double cut) {
        return 1 - Math.pow(1 - cut, 1 / 2.2);
    }

    /** Positions for the drawn shade's gradient stops. */
    static float[] shadeStops() {
        float[] stops = new float[SHADE_STOPS];
        for (int i = 0; i < SHADE_STOPS; i++) stops[i] = (float) i / (SHADE_STOPS - 1);
        return stops;
    }

    /** Black at each stop of the drawn shade for a given alpha. */
    static int[] shadeColors(double alpha) {
        int[] colors = new int[SHADE_STOPS];
        for (int i = 0; i < SHADE_STOPS; i++) {
            double a = overlayAlpha(alpha * weight((double) i / (SHADE_STOPS - 1)));
            colors[i] = (int) Math.round(a * 255) << 24;
        }
        return colors;
    }

    /** The overlay alpha the drawn shade has at a reach, interpolated between stops as the GPU does. */
    static double shadeAlphaAt(double alpha, double reach) {
        if (reach >= 1) return 0;
        int[] colors = shadeColors(alpha);
        double pos = reach * (SHADE_STOPS - 1);
        int i = (int) Math.floor(pos);
        double f = pos - i;
        double a0 = (colors[i] >>> 24) / 255.0, a1 = (colors[Math.min(i + 1, SHADE_STOPS - 1)] >>> 24) / 255.0;
        return a0 + (a1 - a0) * f;
    }

    /** src over dst with an extra alpha, per channel in sRGB as Android composites. */
    static int blend(int src, int dst, float alpha) {
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            float c = ((src >> shift) & 0xFF) * alpha + ((dst >> shift) & 0xFF) * (1 - alpha);
            out |= Math.round(c) << shift;
        }
        return out;
    }

    // ---- where the text is ---------------------------------------------------------------------------

    /** Clock, date, quote (3 lines at most, 340dp wide) and author, top left, as {l, t, r, b} in dp. */
    static final float[][] HOME_TOP = {
            {48, 32, 168, 80}, {48, 84, 270, 102}, {48, 120, 388, 210}, {48, 214, 260, 234}};
    /** The screensaver's quote and author, top left, with no clock above them. */
    static final float[][] DREAM_TOP = {{48, 32, 388, 122}, {48, 126, 260, 146}};

    /** Title and "Artist, year", bottom left, the title as wide as its text (Outfit 15sp, about 8dp a letter). */
    static float[][] captionRegion(String title, float heightDp) {
        float width = Math.min(520, Math.max(160, title.length() * 8f));
        float bottom = heightDp - 32;
        return new float[][]{{48, bottom - 40, 48 + width, bottom}};
    }

    /**
     * Samples the picture under rects (dp) the way it appears through a centre crop into the view and the
     * Ken Burns motion, at both ends of it: at rest, and zoomed by zoom and panned by (panX, panY) px. A
     * cols x rows grid over the rects' bounds per end. Corner gives the shade's centre: 0 top, 1 bottom.
     */
    static Samples sample(Pixels px, int bw, int bh, int vw, int vh, float density, float[][] rects,
                          boolean bottomCorner, float zoom, float panX, float panY, int cols, int rows) {
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = 0, b = 0;
        for (float[] rc : rects) {
            l = Math.min(l, rc[0]);
            t = Math.min(t, rc[1]);
            r = Math.max(r, rc[2]);
            b = Math.max(b, rc[3]);
        }
        float crop = Math.max((float) vw / bw, (float) vh / bh);
        float offX = (vw - bw * crop) / 2, offY = (vh - bh * crop) / 2;
        float rx = (bottomCorner ? CAPTION_RX_DP : TOP_RX_DP) * density;
        float ry = (bottomCorner ? CAPTION_RY_DP : TOP_RY_DP) * density;
        float cy = bottomCorner ? vh : 0;
        int[] argb = new int[cols * rows * 2];
        float[] reach = new float[argb.length];
        int n = 0;
        for (int j = 0; j < rows; j++) {
            float yDp = t + (b - t) * (j + 0.5f) / rows;
            for (int i = 0; i < cols; i++) {
                float xDp = l + (r - l) * (i + 0.5f) / cols;
                if (!inside(rects, xDp, yDp)) continue;
                float x = xDp * density, y = yDp * density;
                float dist = (float) Math.hypot(x / rx, (y - cy) / ry);
                for (int end = 0; end < 2; end++) {
                    float s = end == 0 ? 1 : zoom, tx = end == 0 ? 0 : panX, ty = end == 0 ? 0 : panY;
                    // Undo the view transform (scale about the centre, then translate), then the crop.
                    float vx = vw / 2f + (x - tx - vw / 2f) / s, vy = vh / 2f + (y - ty - vh / 2f) / s;
                    int sx = clamp((int) ((vx - offX) / crop), bw), sy = clamp((int) ((vy - offY) / crop), bh);
                    argb[n] = px.argb(sx, sy);
                    reach[n++] = dist;
                }
            }
        }
        return new Samples(Arrays.copyOf(argb, n), Arrays.copyOf(reach, n));
    }

    private static boolean inside(float[][] rects, float x, float y) {
        for (float[] rc : rects) if (x >= rc[0] && x <= rc[2] && y >= rc[1] && y <= rc[3]) return true;
        return false;
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }
}
