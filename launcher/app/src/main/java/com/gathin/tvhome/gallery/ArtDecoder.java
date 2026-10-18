package com.gathin.tvhome.gallery;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes an artwork straight to the size it will hang at: inSampleSize does most of the shrinking during
 * the decode, then one filtered scale lands on the exact size. Runs on a background thread.
 */
final class ArtDecoder {

    static final class Result {
        final Bitmap bitmap;
        /** Average colour of the artwork, for tinting the wall. */
        final int average;

        Result(Bitmap bitmap, int average) {
            this.bitmap = bitmap;
            this.average = average;
        }
    }

    private ArtDecoder() {}

    /** Decodes {@code path} to fit inside {@code maxW} x {@code maxH}, keeping its aspect ratio. Null on failure. */
    static Result decode(AssetManager assets, String path, int maxW, int maxH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = assets.open(path)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            float scale = Math.min((float) maxW / bounds.outWidth, (float) maxH / bounds.outHeight);
            int w = Math.max(1, Math.round(bounds.outWidth * scale));
            int h = Math.max(1, Math.round(bounds.outHeight * scale));

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= w
                    && bounds.outHeight / (opts.inSampleSize * 2) >= h) {
                opts.inSampleSize *= 2;
            }
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap sampled;
            try (InputStream in = assets.open(path)) {
                sampled = BitmapFactory.decodeStream(in, null, opts);
            }
            if (sampled == null) return null;

            Bitmap fitted = sampled;
            if (sampled.getWidth() != w || sampled.getHeight() != h) {
                fitted = Bitmap.createScaledBitmap(sampled, w, h, true);
                if (fitted != sampled) sampled.recycle();
            }
            return new Result(fitted, average(fitted));
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    private static int average(Bitmap b) {
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 16; x++) {
                int c = b.getPixel((2 * x + 1) * b.getWidth() / 32, (2 * y + 1) * b.getHeight() / 24);
                r += Color.red(c);
                g += Color.green(c);
                bl += Color.blue(c);
                n++;
            }
        }
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }
}
