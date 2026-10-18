package com.gathin.tvhome;

import java.util.Arrays;
import java.util.function.DoublePredicate;

/**
 * Chooses the ink for text laid straight on a painting, with no box, so it reads at the WCAG AA target of
 * 4.5:1. Input is the painting's pixels under the text as they will be on screen. Contrast is WCAG contrast
 * of relative luminance (linearised sRGB) against the 10th percentile of the area for dark ink and the 90th
 * for white ink, so a few stray highlights or shadows don't count. Each block of text (clock, date, quote,
 * author) has to reach it on its own, so a dark patch can't hide behind a light one elsewhere.
 * <ul>
 * <li>LIGHT_CALM: light and even, dark walnut ink with a faint light halo and no shade.
 * <li>DARK and BUSY: white ink over a corner shade just strong enough to reach the target. The shade
 * multiplies luminance by about (1 - alpha * weight) in linear light, weight falling from 1 near the corner
 * to 0 at its edge. It is drawn as black at overlayAlpha(alpha * weight) in a gradient of SHADE_STOPS and
 * judged exactly as drawn, since sRGB blending darkens mid-tones a little less than the linear model.
 * Alpha is capped at MAX_ALPHA. An area that misses even at the cap gets a tight dark text shadow.
 * <li>A mostly light but busy area that white ink would need a heavy shade for gets dark ink instead, over
 * a light veil (white, at most MAX_VEIL) just strong enough to lift its p10 to the target.
 * </ul>
 * Pure Java, so the JVM test proves the same rule the TV runs.
 */
final class InkRule {

    enum Mode { LIGHT_CALM, DARK, BUSY }

    static final double TARGET = 4.5;
    /** The rule aims a little higher than the target, for the screen's own rounding and dithering. */
    static final double AIM = 4.6;
    static final int DARK_INK = 0xFF231812;
    static final int LIGHT_INK = 0xF7FFFFFF;
    static final int LIGHT_HALO = 0x66FFFFFF;
    /** Most the shade may take off the luminance (an overlay of 54% black); more reads as a dark smudge. */
    static final double MAX_ALPHA = 0.82;
    /** Light and calm: p90 - p10 at most this, and p10 light enough for dark ink to reach AIM. */
    static final double CALM_SPREAD = 0.45;
    /** A region with a median this light that would need more shade than this for white ink gets dark ink. */
    static final double PREFER_DARK_ABOVE = 0.35, LIGHT_MEDIAN = 0.35;
    /** Most the light veil under dark ink may be, as the alpha of white over the painting. */
    static final double MAX_VEIL = 0.4;
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
        static final Ink DEFAULT = new Ink(Mode.DARK, 0.45, 0, LIGHT_INK, 0x66000000, 4, 0, false);

        final Mode mode;
        /** The dark shade, as the share of luminance it takes off, and the light veil, as white's alpha. */
        final double alpha, veil;
        final int color, shadowColor;
        final float shadowRadius, shadowDy;
        /** True when white ink at MAX_ALPHA still misses the target and leans on a tight dark shadow. */
        final boolean floor;

        Ink(Mode mode, double alpha, double veil, int color, int shadowColor, float shadowRadius, float shadowDy,
            boolean floor) {
            this.mode = mode;
            this.alpha = alpha;
            this.veil = veil;
            this.color = color;
            this.shadowColor = shadowColor;
            this.shadowRadius = shadowRadius;
            this.shadowDy = shadowDy;
            this.floor = floor;
        }

        boolean dark() {
            return color == DARK_INK;
        }

        boolean shaded() {
            return alpha > 0 || veil > 0;
        }
    }

    /** Pixels of a decoded picture. */
    interface Pixels {
        int argb(int x, int y);
    }

    /** Pixels under the text, where each sits in the shade (0 at the corner, 1 at its edge), and each block's samples. */
    static final class Samples {
        final int[] argb;
        final float[] reach;
        final int[][] blocks;

        Samples(int[] argb, float[] reach, int[][] blocks) {
            this.argb = argb;
            this.reach = reach;
            this.blocks = blocks;
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
        double[] lum = new double[n];
        for (int i = 0; i < n; i++) lum[i] = luminance(s.argb[i]);
        double p10 = worst(lum, s.blocks, 0.1), p90 = worst(lum, s.blocks, 0.9);
        double[] sorted = lum.clone();
        Arrays.sort(sorted);
        double p50 = percentile(sorted, 0.5), spread = 0;
        for (int[] b : s.blocks) {
            double[] l = pick(lum, b);
            Arrays.sort(l);
            if (l.length > 0) spread = Math.max(spread, percentile(l, 0.9) - percentile(l, 0.1));
        }
        double minLum = AIM * (luminance(DARK_INK) + 0.05) - 0.05;
        if (p10 >= minLum && spread <= CALM_SPREAD) return lightCalm();

        double maxLum = (inkLuminance(LIGHT_INK) + 0.05) / AIM - 0.05;
        Mode mode = p50 >= MID_TONE || p90 - p10 > BUSY_SPREAD ? Mode.BUSY : Mode.DARK;
        double alpha = least(a -> worst(drawn(s, a, 0), s.blocks, 0.9) <= maxLum, MAX_ALPHA);
        // Light but busy: dark ink over a light veil, rather than a heavy shade on a light painting.
        if (alpha > PREFER_DARK_ABOVE && p50 >= LIGHT_MEDIAN) {
            double veil = least(v -> worst(drawn(s, 0, v), s.blocks, 0.1) >= minLum, MAX_VEIL);
            if (veil <= MAX_VEIL) return new Ink(Mode.BUSY, 0, veil, DARK_INK, LIGHT_HALO, 3, 0, false);
        }
        if (alpha > MAX_ALPHA) return new Ink(mode, MAX_ALPHA, 0, LIGHT_INK, 0xCC000000, 2, 1, true);
        return new Ink(mode, alpha, 0, LIGHT_INK, 0x66000000, 4, 0, false);
    }

    /** The least amount in [0, max] that passes (passing is monotonic), or infinity if max doesn't. */
    private static double least(DoublePredicate passes, double max) {
        if (passes.test(0)) return 0;
        if (!passes.test(max)) return Double.POSITIVE_INFINITY;
        double lo = 0, hi = max;
        for (int i = 0; i < 20; i++) {
            double mid = (lo + hi) / 2;
            if (passes.test(mid)) hi = mid;
            else lo = mid;
        }
        return hi;
    }

    /** The block percentile furthest from the middle: the lowest p10, or the highest p90. */
    static double worst(double[] lum, int[][] blocks, double q) {
        double worst = q < 0.5 ? Double.MAX_VALUE : 0;
        for (int[] b : blocks) {
            if (b.length == 0) continue;
            double[] l = pick(lum, b);
            Arrays.sort(l);
            double p = percentile(l, q);
            worst = q < 0.5 ? Math.min(worst, p) : Math.max(worst, p);
        }
        return worst;
    }

    private static double[] pick(double[] values, int[] idx) {
        double[] out = new double[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = values[idx[i]];
        return out;
    }

    /** Luminance under the shade (alpha) or veil as drawn: black or white over the painting, blended in sRGB. */
    static double[] drawn(Samples s, double alpha, double veil) {
        int color = veil > 0 ? 0xFFFFFFFF : 0xFF000000;
        int[] stops = stopAlphas(alpha, veil);
        double[] out = new double[s.argb.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = luminance(blend(color, s.argb[i], (float) drawnAlpha(stops, s.reach[i])));
        }
        return out;
    }

    private static Ink lightCalm() {
        return new Ink(Mode.LIGHT_CALM, 0, 0, DARK_INK, LIGHT_HALO, 3, 0, false);
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

    /** The drawn shade's colour at each stop: black for white ink's shade, white for dark ink's veil. */
    static int[] shadeColors(Ink ink) {
        int[] colors = stopAlphas(ink.alpha, ink.veil);
        for (int i = 0; i < SHADE_STOPS; i++) colors[i] = colors[i] << 24 | (ink.veil > 0 ? 0xFFFFFF : 0);
        return colors;
    }

    /** Alpha (0-255) of the shade, or of the veil when there is one, at each stop. */
    static int[] stopAlphas(double alpha, double veil) {
        int[] out = new int[SHADE_STOPS];
        for (int i = 0; i < SHADE_STOPS; i++) {
            double w = weight((double) i / (SHADE_STOPS - 1));
            out[i] = (int) Math.round((veil > 0 ? veil * w : overlayAlpha(alpha * w)) * 255);
        }
        return out;
    }

    /** The drawn shade's alpha at a reach, interpolated between stops as the GPU does. */
    static double drawnAlpha(int[] stops, double reach) {
        if (reach >= 1) return 0;
        double pos = reach * (SHADE_STOPS - 1);
        int i = (int) Math.floor(pos);
        double a0 = stops[i], a1 = stops[Math.min(i + 1, SHADE_STOPS - 1)];
        return (a0 + (a1 - a0) * (pos - i)) / 255;
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

    /**
     * Clock, date, quote (3 lines at most, 340dp wide) and author, top left, as {l, t, r, b} in dp. The quote
     * is split into its lines and their halves, so a dark patch under a few words counts.
     */
    static final float[][] HOME_TOP = concat(new float[][]{{48, 32, 168, 80}, {48, 84, 270, 102}},
            quoteLines(120), new float[][]{{48, 214, 260, 234}});
    /** The screensaver's quote and author, top left, with no clock above them. */
    static final float[][] DREAM_TOP = concat(quoteLines(32), new float[][]{{48, 126, 260, 146}});

    private static float[][] quoteLines(float top) {
        float[][] out = new float[6][];
        for (int line = 0; line < 3; line++) {
            float t = top + line * 30, b = t + 30;
            out[line * 2] = new float[]{48, t, 218, b};
            out[line * 2 + 1] = new float[]{218, t, 388, b};
        }
        return out;
    }

    private static float[][] concat(float[][]... parts) {
        int n = 0;
        for (float[][] p : parts) n += p.length;
        float[][] out = new float[n][];
        int i = 0;
        for (float[][] p : parts) for (float[] r : p) out[i++] = r;
        return out;
    }

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
        int[] part = new int[argb.length];
        int n = 0;
        for (int j = 0; j < rows; j++) {
            float yDp = t + (b - t) * (j + 0.5f) / rows;
            for (int i = 0; i < cols; i++) {
                float xDp = l + (r - l) * (i + 0.5f) / cols;
                int block = blockOf(rects, xDp, yDp);
                if (block < 0) continue;
                float x = xDp * density, y = yDp * density;
                float dist = (float) Math.hypot(x / rx, (y - cy) / ry);
                for (int end = 0; end < 2; end++) {
                    float s = end == 0 ? 1 : zoom, tx = end == 0 ? 0 : panX, ty = end == 0 ? 0 : panY;
                    // Undo the view transform (scale about the centre, then translate), then the crop.
                    float vx = vw / 2f + (x - tx - vw / 2f) / s, vy = vh / 2f + (y - ty - vh / 2f) / s;
                    int sx = clamp((int) ((vx - offX) / crop), bw), sy = clamp((int) ((vy - offY) / crop), bh);
                    argb[n] = px.argb(sx, sy);
                    part[n] = block;
                    reach[n++] = dist;
                }
            }
        }
        int[][] blocks = new int[rects.length][];
        for (int k = 0; k < rects.length; k++) {
            int[] idx = new int[n];
            int m = 0;
            for (int i = 0; i < n; i++) if (part[i] == k) idx[m++] = i;
            blocks[k] = Arrays.copyOf(idx, m);
        }
        return new Samples(Arrays.copyOf(argb, n), Arrays.copyOf(reach, n), blocks);
    }

    /** Index of the rect (x, y) falls in, or -1. */
    private static int blockOf(float[][] rects, float x, float y) {
        for (int k = 0; k < rects.length; k++) {
            float[] rc = rects[k];
            if (x >= rc[0] && x <= rc[2] && y >= rc[1] && y <= rc[3]) return k;
        }
        return -1;
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }
}
