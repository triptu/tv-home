package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
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
 * zoom and crossfading to the next, with a quote that changes with the picture and a scrim that keeps the
 * clock, the buttons and the dock legible. Images are decoded off the main thread at view size, and at most two are
 * in memory: the one shown and the one fading in over it.
 */
public class WallpaperView extends FrameLayout {

    private static final String DIR = "art";
    private static final long INTERVAL_MS = 45_000L;
    private static final long FADE_MS = 2_000L;
    private static final long FIRST_FADE_MS = 250L;
    /** Each picture is on screen while fading in, for the interval, and while the next fades in over it. */
    private static final long MOTION_MS = FADE_MS + INTERVAL_MS + FADE_MS;
    private static final float ZOOM = 1.08f;
    /** Pan per axis as a fraction of the view, inside the (ZOOM - 1) / 2 margin the zoom leaves. */
    private static final float PAN = 0.03f;
    private static final float MIN_ASPECT = 1.3f;
    /** Longer quotes would need a fourth line. */
    private static final int MAX_QUOTE_CHARS = 140;
    /** A light fade at the top for the buttons, clear through the middle, and a gentle one under the dock. */
    private static final int[] SCRIM_COLORS = {
            0x4D000000, 0x1F000000, 0x00000000,
            0x00000000, 0x26000000, 0x59000000};
    private static final float[] SCRIM_STOPS = {
            0f, 0.12f, 0.24f,
            0.70f, 0.85f, 1f};
    /** Extra shade behind the clock: an ellipse from the top-left corner, this share of the width and height. */
    private static final int[] CLOCK_SCRIM_COLORS = {0x73000000, 0x3D000000, 0x00000000};
    private static final float[] CLOCK_SCRIM_STOPS = {0f, 0.5f, 1f};
    private static final float CLOCK_SCRIM_W = 0.55f, CLOCK_SCRIM_H = 0.45f;
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "wallpaper"));

    // Static like MainActivity.lastFocused: if the activity is recreated the rotation carries on where it was.
    private static List<String> order;
    private static List<String[]> quotes;
    private static int index;
    private static int quoteIndex;

    private final ImageView[] layers = new ImageView[2];
    private final Bitmap[] bitmaps = new Bitmap[2];
    private final ObjectAnimator[] motion = new ObjectAnimator[2];
    private final Paint scrim = new Paint(Paint.DITHER_FLAG);
    private final Paint clockScrim = new Paint(Paint.DITHER_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = () -> load(true);
    private final Random random = new Random();
    private View quoteBox;
    private TextView quoteText, quoteAuthor;
    private int front;
    private boolean running;
    private boolean fading;
    private boolean zoomIn = true;
    /** Uptime of the next scheduled advance, or 0 while none is scheduled. */
    private long nextAt;
    private long remainingMs = INTERVAL_MS;
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

    /** The quote shown with each picture: box is faded with it, text and author are filled in. */
    void bindQuote(View box, TextView text, TextView author) {
        quoteBox = box;
        quoteText = text;
        quoteAuthor = author;
        box.setAlpha(0f);
    }

    /** Asset file name (in art/) of the picture shown, or null before the first one. */
    String currentFile() {
        return order.isEmpty() ? null : order.get(index);
    }

    /** Shows the current picture (decoding it first if this view has none yet) and resumes the rotation. */
    void start() {
        if (running || order.isEmpty()) return;
        running = true;
        if (bitmaps[front] == null) {
            load(false);
        } else {
            if (motion[front] != null) motion[front].resume();
            schedule(remainingMs);
        }
    }

    /** Pauses the rotation, keeping the shown picture so the next start() has no black frame. */
    void stop() {
        if (!running) return;
        running = false;
        request++;
        handler.removeCallbacks(advance);
        remainingMs = nextAt > 0 ? Math.max(0, nextAt - SystemClock.uptimeMillis()) : INTERVAL_MS;
        nextAt = 0;
        if (fading) {
            layers[front].animate().cancel();
            endFade();
        }
        if (motion[front] != null) motion[front].pause();
        if (quoteBox != null && !quotes.isEmpty() && bitmaps[front] != null) {
            quoteBox.animate().cancel();
            setQuote(quoteIndex);
            quoteBox.setAlpha(1f);
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
        final String path = DIR + "/" + order.get(target);
        final int w = viewWidth(), h = viewHeight();
        final AssetManager assets = getContext().getAssets();
        DECODER.execute(() -> {
            // Decoded at full zoom so the Ken Burns drift never upscales.
            Bitmap bmp = decode(assets, path, Math.round(w * ZOOM), Math.round(h * ZOOM));
            handler.post(() -> {
                if (token != request || !running) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                index = target;
                if (bmp == null) {
                    schedule(INTERVAL_MS);
                    return;
                }
                long fadeMs = next ? FADE_MS : FIRST_FADE_MS;
                show(bmp, fadeMs);
                if (next) quoteIndex++;
                showQuote(next, fadeMs);
            });
        });
    }

    private void show(Bitmap bmp, long fadeMs) {
        front = 1 - front;
        ImageView in = layers[front];
        bitmaps[front] = bmp;
        in.setImageBitmap(bmp);
        in.setAlpha(0f);
        in.bringToFront();
        startMotion(front);
        fading = true;
        in.animate().alpha(1f).setDuration(fadeMs).withEndAction(() -> {
            endFade();
            if (running) schedule(INTERVAL_MS);
        }).start();
    }

    /**
     * Zooms between 1 and ZOOM around the centre while panning in a random direction, alternating zoom in
     * and out. Only view properties change, so the GPU redraws the same bitmap with a new transform.
     */
    private void startMotion(int layer) {
        float angle = random.nextFloat() * (float) (2 * Math.PI);
        float dx = (float) Math.cos(angle) * PAN * viewWidth();
        float dy = (float) Math.sin(angle) * PAN * viewHeight();
        float s0 = zoomIn ? 1f : ZOOM, s1 = zoomIn ? ZOOM : 1f;
        float x0 = zoomIn ? 0f : dx, x1 = zoomIn ? dx : 0f;
        float y0 = zoomIn ? 0f : dy, y1 = zoomIn ? dy : 0f;
        zoomIn = !zoomIn;
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(layers[layer],
                PropertyValuesHolder.ofFloat(View.SCALE_X, s0, s1),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, s0, s1),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, x0, x1),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, y0, y1));
        a.setDuration(MOTION_MS);
        a.setInterpolator(new LinearInterpolator());
        a.start();
        motion[layer] = a;
    }

    /** Settles the incoming picture and drops the one it covered. */
    private void endFade() {
        fading = false;
        layers[front].setAlpha(1f);
        release(1 - front);
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
    }

    /** Fades the quote out over the first part of the crossfade and the new one in over the rest. */
    private void showQuote(boolean next, long fadeMs) {
        if (quoteBox == null || quotes.isEmpty()) return;
        int q = quoteIndex;
        quoteBox.animate().cancel();
        if (!next || quoteBox.getAlpha() == 0f) {
            setQuote(q);
            quoteBox.animate().alpha(1f).setDuration(fadeMs * 2).start();
            return;
        }
        quoteBox.animate().alpha(0f).setDuration(fadeMs * 2 / 5).withEndAction(() -> {
            setQuote(q);
            quoteBox.animate().alpha(1f).setDuration(fadeMs * 3 / 5).start();
        }).start();
    }

    private void setQuote(int i) {
        String[] q = quotes.get(i % quotes.size());
        quoteText.setText(q[0]);
        quoteAuthor.setText(q[1]);
        quoteAuthor.setVisibility(q[1].isEmpty() ? GONE : VISIBLE);
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
    private static List<String> readCatalog(AssetManager assets) {
        List<String> files = new ArrayList<>();
        try {
            JSONArray catalog = new JSONArray(readAsset(assets, DIR + "/catalog.json"));
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject e = catalog.getJSONObject(i);
                String file = e.optString("file");
                double width = e.optDouble("width", 0), height = e.optDouble("height", 0);
                if (!file.isEmpty() && height > 0 && width / height >= MIN_ASPECT) files.add(file);
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
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) files.add(n);
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
        scrim.setShader(new LinearGradient(0, 0, 0, h, SCRIM_COLORS, SCRIM_STOPS, Shader.TileMode.CLAMP));
        float rw = w * CLOCK_SCRIM_W, rh = h * CLOCK_SCRIM_H;
        RadialGradient clock = new RadialGradient(0, 0, rw, CLOCK_SCRIM_COLORS, CLOCK_SCRIM_STOPS, Shader.TileMode.CLAMP);
        Matrix squash = new Matrix();
        squash.setScale(1f, rh / rw);
        clock.setLocalMatrix(squash);
        clockScrim.setShader(clock);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrim);
        canvas.drawRect(0, 0, getWidth() * CLOCK_SCRIM_W, getHeight() * CLOCK_SCRIM_H, clockScrim);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
        release(0);
        release(1);
    }
}
