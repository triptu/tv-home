package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picture backdrop: the landscape works in assets/art in shuffled order, each drifting in a slow Ken Burns
 * zoom and crossfading to the next, with a quote that changes with the picture. Images are decoded off the
 * main thread at view size, and at most two are in memory: the one shown and the one fading in over it.
 * Each picture comes with its ink: InkRule looks at the pixels under the text once, after decoding, and
 * picks the text colour and the strength of a corner shade, which crossfade with the picture.
 */
public class WallpaperView extends FrameLayout {

    private static final String DIR = "art";
    /** Saved override for the time each picture stays, in seconds; see {@link #setIntervalSeconds}. */
    private static final String PREFS = "wallpaper", KEY_INTERVAL = "art_interval_seconds";
    private static final long FADE_MS = 2_000L;
    private static final long FIRST_FADE_MS = 250L;
    private static final float ZOOM = 1.08f;
    /** Pan per axis as a fraction of the view, inside the (ZOOM - 1) / 2 margin the zoom leaves. */
    private static final float PAN = 0.03f;
    private static final float MIN_ASPECT = 1.3f;
    /** Ink opacity of the author and caption title, and of the date and byline. */
    private static final float AUTHOR_OPACITY = 0.75f, TITLE_OPACITY = 0.93f, SECONDARY_OPACITY = 0.72f;
    /** Longer quotes would need a fourth line. */
    private static final int MAX_QUOTE_CHARS = 140;
    /** Sample grid over the text, per end of the motion. */
    private static final int SAMPLE_COLS = 48, SAMPLE_ROWS = 32;
    private static final float[] SHADE_STOPS = InkRule.shadeStops();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "wallpaper"));

    // Static like MainActivity.lastFocused: if the activity is recreated the rotation carries on where it was.
    /** {file, title, artist, year} per picture. */
    private static List<String[]> order;
    private static List<String[]> quotes;
    private static int index;
    private static int quoteIndex;

    private final ImageView[] layers = new ImageView[2];
    private final Bitmap[] bitmaps = new Bitmap[2];
    private final ObjectAnimator[] motion = new ObjectAnimator[2];
    /** Per layer: the ink for the text top left and for the caption, and the shades they come with. */
    private final InkRule.Ink[] topInk = new InkRule.Ink[2];
    private final InkRule.Ink[] captionInk = new InkRule.Ink[2];
    private final Paint[] topShade = {new Paint(Paint.DITHER_FLAG), new Paint(Paint.DITHER_FLAG)};
    private final Paint[] captionShade = {new Paint(Paint.DITHER_FLAG), new Paint(Paint.DITHER_FLAG)};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = () -> load(true);
    private final Random random = new Random();
    private View quoteBox;
    private TextView quoteText, quoteAuthor;
    private View captionBox;
    private TextView captionTitle, captionByline;
    private TextView clock, date;
    private int front;
    private boolean running;
    private ValueAnimator fade;
    /** Progress of the crossfade to the front layer; 1 when none is running. */
    private float fadeT = 1f;
    private boolean zoomIn = true;
    /** Uptime of the next scheduled advance, or 0 while none is scheduled. */
    private long nextAt;
    private long remainingMs = -1;
    /** Bumped on stop and per load, so a decode that finishes late is dropped. */
    private int request;

    public WallpaperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(context);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            layers[i] = v;
        }
        if (order == null) {
            AssetManager assets = context.getAssets();
            order = readCatalog(assets);
            Collections.shuffle(order);
            quotes = readQuotes(assets);
            Collections.shuffle(quotes);
        }
    }

    /** How long each picture stays: the saved override if there is one, otherwise config.xml. */
    long intervalMs() {
        int s = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INTERVAL, 0);
        if (s <= 0) s = getResources().getInteger(R.integer.art_interval_seconds);
        return s * 1000L;
    }

    /** Saves how long each picture stays, for the home screen and the screensaver; 0 or less resets it. */
    static void setIntervalSeconds(Context context, int seconds) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_INTERVAL, Math.max(0, seconds)).apply();
    }

    /** The quote shown with each picture: box is faded with it, text and author are filled in. */
    void bindQuote(View box, TextView text, TextView author) {
        quoteBox = box;
        quoteText = text;
        quoteAuthor = author;
        box.setAlpha(0f);
    }

    /** Home only: the clock and date take the quote's ink, and the area they cover counts toward it. */
    void bindClock(TextView clock, TextView date) {
        this.clock = clock;
        this.date = date;
    }

    /** The caption for each picture: box is faded with it, title and "Artist, year" byline are filled in. */
    void bindCaption(View box, TextView title, TextView byline) {
        captionBox = box;
        captionTitle = title;
        captionByline = byline;
        box.setAlpha(0f);
    }

    /** Asset file name (in art/) of the picture shown, or null before the first one. */
    String currentFile() {
        return order.isEmpty() ? null : order.get(index)[0];
    }

    /** Shows the current picture (decoding it first if this view has none yet) and resumes the rotation. */
    void start() {
        if (running || order.isEmpty()) return;
        running = true;
        if (bitmaps[front] == null) {
            load(false);
        } else {
            if (motion[front] != null) motion[front].resume();
            schedule(remainingMs >= 0 ? remainingMs : intervalMs());
        }
    }

    /** Pauses the rotation, keeping the shown picture so the next start() has no black frame. */
    void stop() {
        if (!running) return;
        running = false;
        request++;
        handler.removeCallbacks(advance);
        remainingMs = nextAt > 0 ? Math.max(0, nextAt - SystemClock.uptimeMillis()) : intervalMs();
        nextAt = 0;
        if (fade != null) {
            fade.removeAllListeners();
            fade.cancel();
            endFade();
        }
        if (motion[front] != null) motion[front].pause();
        if (quoteBox != null && !quotes.isEmpty() && bitmaps[front] != null) {
            quoteBox.animate().cancel();
            setQuote(quoteIndex, topInk[front]);
            quoteBox.setAlpha(1f);
        }
        if (captionBox != null && bitmaps[front] != null) {
            captionBox.animate().cancel();
            setCaption(index, captionInk[front]);
            captionBox.setAlpha(1f);
        }
    }

    private void schedule(long delayMs) {
        nextAt = SystemClock.uptimeMillis() + delayMs;
        handler.postDelayed(advance, delayMs);
    }

    private void load(boolean next) {
        nextAt = 0;
        final int token = ++request;
        final int target = (index + (next ? 1 : 0)) % order.size();
        final String path = DIR + "/" + order.get(target)[0];
        final int w = viewWidth(), h = viewHeight();
        final float density = getResources().getDisplayMetrics().density;
        // The pan is picked now so the ink can be judged over the whole motion.
        float angle = random.nextFloat() * (float) (2 * Math.PI);
        final float dx = (float) Math.cos(angle) * PAN * w, dy = (float) Math.sin(angle) * PAN * h;
        final float[][] topRects = quoteBox == null ? null : clock != null ? InkRule.HOME_TOP : InkRule.DREAM_TOP;
        final float[][] captionRects = captionBox == null ? null
                : InkRule.captionRegion(order.get(target)[1], h / density);
        final AssetManager assets = getContext().getAssets();
        DECODER.execute(() -> {
            // Decoded at full zoom so the Ken Burns drift never upscales.
            Bitmap bmp = decode(assets, path, Math.round(w * ZOOM), Math.round(h * ZOOM));
            InkRule.Ink top = bmp == null ? null : ink(bmp, w, h, density, topRects, false, dx, dy);
            InkRule.Ink caption = bmp == null ? null : ink(bmp, w, h, density, captionRects, true, dx, dy);
            handler.post(() -> {
                if (token != request || !running) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                index = target;
                if (bmp == null) {
                    schedule(intervalMs());
                    return;
                }
                long fadeMs = next ? FADE_MS : FIRST_FADE_MS;
                show(bmp, top, caption, dx, dy, fadeMs);
                if (next) quoteIndex++;
                if (quoteBox != null && !quotes.isEmpty()) {
                    int q = quoteIndex;
                    swap(quoteBox, next, fadeMs, () -> setQuote(q, top));
                }
                if (captionBox != null) swap(captionBox, next, fadeMs, () -> setCaption(target, caption));
            });
        });
    }

    /** The ink for rects (dp) on this picture, or null when there is no such text. */
    private static InkRule.Ink ink(Bitmap bmp, int w, int h, float density, float[][] rects, boolean bottom,
                                   float dx, float dy) {
        if (rects == null) return null;
        return InkRule.decide(InkRule.sample(bmp::getPixel, bmp.getWidth(), bmp.getHeight(), w, h, density, rects,
                bottom, ZOOM, dx, dy, SAMPLE_COLS, SAMPLE_ROWS));
    }

    /** Fades the picture in over the last one, its shades with it, and moves the clock's ink across. */
    private void show(Bitmap bmp, InkRule.Ink top, InkRule.Ink caption, float dx, float dy, long fadeMs) {
        InkRule.Ink from = topInk[front] != null ? topInk[front] : top;
        front = 1 - front;
        ImageView in = layers[front];
        bitmaps[front] = bmp;
        topInk[front] = top;
        captionInk[front] = caption;
        buildShades(front);
        in.setImageBitmap(bmp);
        in.setAlpha(0f);
        in.bringToFront();
        startMotion(front, dx, dy);
        fadeT = 0f;
        fade = ValueAnimator.ofFloat(0f, 1f).setDuration(fadeMs);
        fade.addUpdateListener(a -> {
            fadeT = (float) a.getAnimatedValue();
            in.setAlpha(fadeT);
            if (from != null && top != null) inkClock(from, top, fadeT);
            invalidate();
        });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                endFade();
                if (running) schedule(intervalMs());
            }
        });
        fade.start();
    }

    /**
     * Zooms between 1 and ZOOM around the centre while panning by (dx, dy), alternating zoom in and out.
     * Only view properties change, so the GPU redraws the same bitmap with a new transform.
     */
    private void startMotion(int layer, float dx, float dy) {
        float s0 = zoomIn ? 1f : ZOOM, s1 = zoomIn ? ZOOM : 1f;
        float x0 = zoomIn ? 0f : dx, x1 = zoomIn ? dx : 0f;
        float y0 = zoomIn ? 0f : dy, y1 = zoomIn ? dy : 0f;
        zoomIn = !zoomIn;
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(layers[layer],
                PropertyValuesHolder.ofFloat(View.SCALE_X, s0, s1),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, s0, s1),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, x0, x1),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, y0, y1));
        // Each picture is on screen while fading in, for the interval, and while the next fades in over it.
        a.setDuration(FADE_MS + intervalMs() + FADE_MS);
        a.setInterpolator(new LinearInterpolator());
        a.start();
        motion[layer] = a;
    }

    /** Settles the incoming picture and drops the one it covered. */
    private void endFade() {
        fade = null;
        fadeT = 1f;
        layers[front].setAlpha(1f);
        if (topInk[front] != null) inkClock(topInk[front], topInk[front], 1f);
        release(1 - front);
        invalidate();
    }

    private void release(int layer) {
        if (motion[layer] != null) {
            motion[layer].cancel();
            motion[layer] = null;
        }
        ImageView v = layers[layer];
        v.setImageDrawable(null);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        if (bitmaps[layer] != null) {
            bitmaps[layer].recycle();
            bitmaps[layer] = null;
        }
        topInk[layer] = null;
        captionInk[layer] = null;
        topShade[layer].setShader(null);
        captionShade[layer].setShader(null);
    }

    /** Fades box out over the first part of the crossfade, refills it, and fades it in over the rest. */
    private static void swap(View box, boolean next, long fadeMs, Runnable fill) {
        box.animate().cancel();
        if (!next || box.getAlpha() == 0f) {
            fill.run();
            box.animate().alpha(1f).setDuration(fadeMs * 2).start();
            return;
        }
        box.animate().alpha(0f).setDuration(fadeMs * 2 / 5).withEndAction(() -> {
            fill.run();
            box.animate().alpha(1f).setDuration(fadeMs * 3 / 5).start();
        }).start();
    }

    private void setQuote(int i, InkRule.Ink ink) {
        String[] q = quotes.get(i % quotes.size());
        quoteText.setText(q[0]);
        quoteAuthor.setText("\u2014 " + q[1]);
        quoteAuthor.setVisibility(q[1].isEmpty() ? GONE : VISIBLE);
        if (ink != null) {
            applyInk(quoteText, ink, ink, 1f, 1f);
            applyInk(quoteAuthor, ink, ink, 1f, AUTHOR_OPACITY);
        }
    }

    /** Title, then "Artist, year" (or whichever of the two the catalog has). */
    private void setCaption(int i, InkRule.Ink ink) {
        if (ink != null) {
            applyInk(captionTitle, ink, ink, 1f, TITLE_OPACITY);
            applyInk(captionByline, ink, ink, 1f, SECONDARY_OPACITY);
        }
        String[] a = order.get(i);
        String byline = a[2].isEmpty() || a[3].isEmpty() ? a[2] + a[3] : a[2] + ", " + a[3];
        captionTitle.setText(a[1]);
        captionTitle.setVisibility(a[1].isEmpty() ? GONE : VISIBLE);
        captionByline.setText(byline);
        captionByline.setVisibility(byline.isEmpty() ? GONE : VISIBLE);
    }

    /** The clock and date part way from one ink to the next. */
    private void inkClock(InkRule.Ink from, InkRule.Ink to, float t) {
        if (clock == null) return;
        applyInk(clock, from, to, t, 1f);
        applyInk(date, from, to, t, SECONDARY_OPACITY);
    }

    private static void applyInk(TextView v, InkRule.Ink from, InkRule.Ink to, float t, float opacity) {
        int color = mix(from.color, to.color, t);
        int alpha = Math.round((color >>> 24) * opacity);
        v.setTextColor(alpha << 24 | color & 0xFFFFFF);
        v.setShadowLayer(from.shadowRadius + (to.shadowRadius - from.shadowRadius) * t,
                0, from.shadowDy + (to.shadowDy - from.shadowDy) * t, mix(from.shadowColor, to.shadowColor, t));
    }

    private static int mix(int a, int b, float t) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    /** The layer's shades: ellipses from the top-left and bottom-left corners, as its inks call for. */
    private void buildShades(int layer) {
        int w = viewWidth(), h = viewHeight();
        float density = getResources().getDisplayMetrics().density;
        topShade[layer].setShader(shade(topInk[layer], 0, InkRule.TOP_RX_DP * density, InkRule.TOP_RY_DP * density));
        captionShade[layer].setShader(shade(captionInk[layer], h,
                InkRule.CAPTION_RX_DP * density, InkRule.CAPTION_RY_DP * density));
    }

    private static Shader shade(InkRule.Ink ink, float cy, float rx, float ry) {
        if (ink == null || !ink.shaded()) return null;
        RadialGradient g = new RadialGradient(0, cy, rx, InkRule.shadeColors(ink), SHADE_STOPS, Shader.TileMode.CLAMP);
        Matrix squash = new Matrix();
        squash.setScale(1f, ry / rx, 0, cy);
        g.setLocalMatrix(squash);
        return g;
    }

    private int viewWidth() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return getWidth() > 0 ? getWidth() : dm.widthPixels;
    }

    private int viewHeight() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return getHeight() > 0 ? getHeight() : dm.heightPixels;
    }

    /** Landscape entries of art/catalog.json, or every JPEG in art/ while there is no catalog. */
    private static List<String[]> readCatalog(AssetManager assets) {
        List<String[]> files = new ArrayList<>();
        try {
            JSONArray catalog = new JSONArray(readAsset(assets, DIR + "/catalog.json"));
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject e = catalog.getJSONObject(i);
                String file = e.optString("file");
                double width = e.optDouble("width", 0), height = e.optDouble("height", 0);
                if (!file.isEmpty() && height > 0 && width / height >= MIN_ASPECT) {
                    files.add(new String[]{file, e.optString("title").trim(), e.optString("artist").trim(),
                            e.optString("year").trim()});
                }
            }
            return files;
        } catch (IOException | JSONException e) {
            files.clear();
        }
        try {
            String[] names = assets.list(DIR);
            if (names != null) {
                for (String n : names) {
                    String lower = n.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) files.add(new String[]{n, "", "", ""});
                }
            }
        } catch (IOException ignored) {
        }
        return files;
    }

    /** {text, author} pairs from quotes.json that fit in three lines. */
    private static List<String[]> readQuotes(AssetManager assets) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(readAsset(assets, "quotes.json"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject q = all.getJSONObject(i);
                String text = q.optString("text").trim();
                if (!text.isEmpty() && text.length() <= MAX_QUOTE_CHARS) {
                    out.add(new String[]{text, q.optString("author").trim()});
                }
            }
        } catch (IOException | JSONException ignored) {
        }
        return out;
    }

    private static String readAsset(AssetManager assets, String path) throws IOException {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Decodes an asset just large enough to cover w x h, never upscaled. */
    private static Bitmap decode(AssetManager assets, String path, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = assets.open(path)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sw = o.outWidth, sh = o.outHeight;
            if (sw <= 0 || sh <= 0) return null;
            int sample = 1;
            while (sw / (sample * 2) >= w && sh / (sample * 2) >= h) sample *= 2;
            sw /= sample;
            sh /= sample;

            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            if (sw > w && sh > h) {
                boolean byWidth = (float) w / sw >= (float) h / sh;
                o.inScaled = true;
                o.inDensity = byWidth ? sw : sh;
                o.inTargetDensity = byWidth ? w : h;
            }
            Bitmap bmp;
            try (InputStream in = assets.open(path)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            // The density trick above would otherwise make the drawable's intrinsic size wrong.
            if (bmp != null) bmp.setDensity(Bitmap.DENSITY_NONE);
            return bmp;
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        for (int i = 0; i < 2; i++) buildShades(i);
    }

    /** Each layer's shades over the pictures, the outgoing one fading out as the incoming one fades in. */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float topW = InkRule.TOP_RX_DP * density, topH = InkRule.TOP_RY_DP * density;
        float capW = InkRule.CAPTION_RX_DP * density, capH = InkRule.CAPTION_RY_DP * density;
        int h = getHeight();
        for (int i = 0; i < 2; i++) {
            float a = i == front ? fadeT : 1 - fadeT;
            if (bitmaps[i] == null || a <= 0) continue;
            drawShade(canvas, topShade[i], a, 0, 0, topW, topH);
            drawShade(canvas, captionShade[i], a, 0, h - capH, capW, h);
        }
    }

    private static void drawShade(Canvas canvas, Paint p, float alpha, float l, float t, float r, float b) {
        if (p.getShader() == null) return;
        p.setAlpha(Math.round(alpha * 255));
        canvas.drawRect(l, t, r, b, p);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
        release(0);
        release(1);
    }
}
