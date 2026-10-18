package com.gathin.tvhome;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * Proves InkRule on every landscape picture the home screen shows: at 1280x720 and density 1.33, over the
 * Ken Burns motion in eight pan directions, the quote reaches 4.5:1 after the shade as it is composited.
 * Prints a table; with -PinkSheet=path it also renders a contact sheet of every home screen.
 */
public class InkRuleTest {

    private static final int W = 1280, H = 720;
    private static final float DENSITY = 4 / 3f, ZOOM = 1.08f, PAN = 0.03f;
    private static final File ASSETS = new File("src/main/assets");
    private static final File FONTS = new File("src/main/res/font");

    @Test
    public void everyLandscapePictureReadsAtTarget() throws IOException {
        List<String[]> pictures = landscapePictures();
        assertTrue("catalog has landscape pictures", pictures.size() >= 31);
        StringBuilder table = new StringBuilder(String.format(Locale.ROOT, "%-44s %-10s %5s %6s | %-10s %5s %6s | %-10s %5s %6s%n",
                "file", "home", "alpha", "ratio", "saver", "alpha", "ratio", "caption", "alpha", "ratio"));
        List<String> failures = new ArrayList<>();
        double maxAlpha = 0;
        List<Thumb> thumbs = new ArrayList<>();
        for (String[] p : pictures) {
            BufferedImage img = ImageIO.read(new File(ASSETS, "art/" + p[0]));
            Result home = worst(img, InkRule.HOME_TOP, false);
            Result saver = worst(img, InkRule.DREAM_TOP, false);
            Result caption = worst(img, InkRule.captionRegion(p[1], H / DENSITY), true);
            for (Result r : new Result[]{home, saver, caption}) maxAlpha = Math.max(maxAlpha, r.ink.alpha);
            table.append(String.format(Locale.ROOT, "%-44s %s | %s | %s%n", p[0], home, saver, caption));
            if (home.ratio < InkRule.TARGET) failures.add(p[0] + " home " + home);
            if (saver.ratio < InkRule.TARGET) failures.add(p[0] + " screensaver " + saver);
            if (caption.ratio < InkRule.TARGET) failures.add(p[0] + " caption " + caption);
            thumbs.add(new Thumb(p[0], img, home));
        }
        table.append(String.format(Locale.ROOT, "max alpha %.3f (cap %.2f, overlay alpha %.3f)%n",
                maxAlpha, InkRule.MAX_ALPHA, InkRule.overlayAlpha(maxAlpha)));
        System.out.print(table);
        Files.write(new File("build/ink-report.txt").toPath(), table.toString().getBytes(StandardCharsets.UTF_8));

        String sheet = System.getProperty("inkSheet", "");
        if (!sheet.isEmpty()) writeSheet(thumbs, new File(sheet));
        assertTrue("below " + InkRule.TARGET + ":1: " + failures, failures.isEmpty());
    }

    /** The decision and the contrast it reaches, for the pan direction that comes out worst. */
    private static Result worst(BufferedImage img, float[][] rects, boolean bottom) {
        Result worst = null;
        for (int k = 0; k < 8; k++) {
            double angle = k * Math.PI / 4;
            InkRule.Samples s = InkRule.sample(img::getRGB, img.getWidth(), img.getHeight(), W, H, DENSITY, rects,
                    bottom, ZOOM, (float) (Math.cos(angle) * PAN * W), (float) (Math.sin(angle) * PAN * H), 48, 32);
            InkRule.Ink ink = InkRule.decide(s);
            Result r = new Result(ink, achieved(s, ink));
            if (worst == null || r.ratio < worst.ratio) worst = r;
        }
        return worst;
    }

    /** Contrast of the ink against the worst relevant percentile once the drawn shade is composited. */
    static double achieved(InkRule.Samples s, InkRule.Ink ink) {
        double[] lum = new double[s.argb.length];
        for (int i = 0; i < lum.length; i++) {
            float a = (float) InkRule.shadeAlphaAt(ink.alpha, s.reach[i]);
            lum[i] = InkRule.luminance(InkRule.blend(0xFF000000, s.argb[i], a));
        }
        Arrays.sort(lum);
        return ink.dark()
                ? InkRule.contrast(InkRule.percentile(lum, 0.1), InkRule.luminance(InkRule.DARK_INK))
                : InkRule.contrast(InkRule.percentile(lum, 0.9), InkRule.inkLuminance(InkRule.LIGHT_INK));
    }

    private static final class Result {
        final InkRule.Ink ink;
        final double ratio;

        Result(InkRule.Ink ink, double ratio) {
            this.ink = ink;
            this.ratio = ratio;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%-10s %5.2f %6.2f%s", ink.mode, ink.alpha, ratio, ink.floor ? " floor" : "");
        }
    }

    /** {file, title} of catalog entries at least 1.3 wide, as WallpaperView picks them. */
    private static List<String[]> landscapePictures() throws IOException {
        String json = new String(Files.readAllBytes(new File(ASSETS, "art/catalog.json").toPath()), StandardCharsets.UTF_8);
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*}").matcher(json);
        while (m.find()) {
            String e = m.group();
            double w = number(e, "width"), h = number(e, "height");
            if (h > 0 && w / h >= 1.3) out.add(new String[]{string(e, "file"), string(e, "title")});
        }
        return out;
    }

    private static String string(String entry, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(entry);
        return m.find() ? m.group(1).trim() : "";
    }

    private static double number(String entry, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.]+)").matcher(entry);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    // ---- contact sheet -------------------------------------------------------------------------------

    private static final int TW = 640, TH = 360, COLS = 4;
    /** Screen px to thumbnail px. */
    private static final float K = (float) TW / W;

    private static final class Thumb {
        final String file;
        final BufferedImage img;
        final Result home;

        Thumb(String file, BufferedImage img, Result home) {
            this.file = file;
            this.img = img;
            this.home = home;
        }
    }

    private static void writeSheet(List<Thumb> thumbs, File out) throws IOException {
        Font outfit, serif;
        try {
            outfit = Font.createFont(Font.TRUETYPE_FONT, new File(FONTS, "outfit.ttf"));
            serif = Font.createFont(Font.TRUETYPE_FONT, new File(FONTS, "cormorant_italic.ttf"));
        } catch (java.awt.FontFormatException e) {
            throw new IOException(e);
        }
        int rows = (thumbs.size() + COLS - 1) / COLS;
        BufferedImage sheet = new BufferedImage(TW * COLS, TH * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        for (int i = 0; i < thumbs.size(); i++) {
            g.drawImage(thumb(thumbs.get(i), outfit, serif), (i % COLS) * TW, (i / COLS) * TH, null);
        }
        g.dispose();
        out.getParentFile().mkdirs();
        ImageIO.write(sheet, "png", out);
    }

    /** The home screen at rest, half size: the picture, the shade, the clock, date and a two-line quote. */
    private static BufferedImage thumb(Thumb t, Font outfit, Font serif) {
        BufferedImage img = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        float crop = Math.max((float) TW / t.img.getWidth(), (float) TH / t.img.getHeight());
        int dw = Math.round(t.img.getWidth() * crop), dh = Math.round(t.img.getHeight() * crop);
        g.drawImage(t.img, (TW - dw) / 2, (TH - dh) / 2, dw, dh, null);
        InkRule.Ink ink = t.home.ink;
        float rx = InkRule.TOP_RX_DP * DENSITY, ry = InkRule.TOP_RY_DP * DENSITY;
        for (int y = 0; y < TH; y++) {
            for (int x = 0; x < TW; x++) {
                double reach = Math.hypot(x / K / rx, y / K / ry);
                if (reach >= 1) continue;
                img.setRGB(x, y, InkRule.blend(0xFF000000, img.getRGB(x, y), (float) InkRule.shadeAlphaAt(ink.alpha, reach)));
            }
        }

        BufferedImage text = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = text.createGraphics();
        tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        tg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        float sp = DENSITY * K;
        float left = 48 * DENSITY * K;
        Font clock = outfit.deriveFont(Map.of(TextAttribute.SIZE, 36 * sp, TextAttribute.TRACKING, -0.02f));
        Font date = outfit.deriveFont(Map.of(TextAttribute.SIZE, 12 * sp, TextAttribute.TRACKING, 0.16f));
        Font quote = serif.deriveFont(Map.of(TextAttribute.SIZE, 22 * sp, TextAttribute.WEIGHT, TextAttribute.WEIGHT_MEDIUM));
        Font author = outfit.deriveFont(11 * sp);
        float y = 32 * sp;
        y += 36 * sp * 0.85f;
        drawText(tg, clock, "11:27", left, y);
        y += 6 * sp + 12 * sp * 1.1f;
        drawText(tg, date, "SATURDAY  18 OCTOBER", left, y);
        y += 22 * sp + 22 * sp * 0.95f;
        drawText(tg, quote, "How we spend our days is, of course,", left, y);
        y += 22 * sp * 1.2f;
        drawText(tg, quote, "how we spend our lives.", left, y);
        y += 8 * sp + 11 * sp * 1.1f;
        tg.setComposite(AlphaComposite.SrcOver.derive(0.75f));
        drawText(tg, author, "— Annie Dillard", left, y);
        tg.dispose();

        // Shadow or halo: the text's shape in the shadow colour, blurred, under the text.
        BufferedImage shadow = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
        int sc = ink.shadowColor;
        for (int yy = 0; yy < TH; yy++) {
            for (int xx = 0; xx < TW; xx++) {
                int a = (text.getRGB(xx, yy) >>> 24) * (sc >>> 24) / 255;
                shadow.setRGB(xx, yy, (a << 24) | (sc & 0xFFFFFF));
            }
        }
        int k = Math.max(1, Math.round(ink.shadowRadius * K)) * 2 + 1;
        float[] box = new float[k * k];
        Arrays.fill(box, 1f / box.length);
        shadow = new ConvolveOp(new Kernel(k, k, box), ConvolveOp.EDGE_NO_OP, null).filter(shadow, null);
        g.drawImage(shadow, 0, Math.round(ink.shadowDy * K), null);

        BufferedImage inked = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
        for (int yy = 0; yy < TH; yy++) {
            for (int xx = 0; xx < TW; xx++) {
                int a = (text.getRGB(xx, yy) >>> 24) * (ink.color >>> 24) / 255;
                inked.setRGB(xx, yy, (a << 24) | (ink.color & 0xFFFFFF));
            }
        }
        g.drawImage(inked, 0, 0, null);

        String label = String.format(Locale.ROOT, "%s  %s  a=%.2f  %.2f:1%s", t.file, ink.mode, ink.alpha, t.home.ratio,
                ink.floor ? " floor" : "");
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, TH - 16, TW, 16);
        g.setColor(Color.WHITE);
        g.drawString(label, 6, TH - 4);
        g.dispose();
        return img;
    }

    private static void drawText(Graphics2D g, Font font, String s, float x, float y) {
        g.setFont(font);
        g.setColor(Color.WHITE);
        g.drawString(s, x, y);
    }
}
