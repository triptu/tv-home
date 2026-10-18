package com.gathin.tvhome.gallery;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gathin.tvhome.R;
import com.gathin.tvhome.gallery.ArtCatalog.Artwork;
import com.gathin.tvhome.gallery.ArtCatalog.Quote;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The gallery wall shared by {@link GalleryActivity} and {@link ArtDream}: a softly lit walnut-brown wall
 * with one framed artwork (or a quote) at a time, a museum plaque under it, and slow crossfades between pieces.
 * Images decode off the main thread; at most two are alive, the one hanging and the one arriving.
 */
public final class ArtWallView extends FrameLayout {

    static final long CROSSFADE_MS = 2500L;
    private static final long MANUAL_FADE_MS = 900L;
    private static final long PLAQUE_HOLD_MS = 8000L;
    private static final long PLAQUE_IN_MS = 1200L;
    private static final long PLAQUE_OUT_MS = 1500L;
    private static final long PLAQUE_QUICK_MS = 500L;
    private static final int MAX_FAILURES = 5;

    /** The wall under the gallery light, and in the corners of the room. */
    private static final int WALL_LIT = 0xFF33241B;
    private static final int WALL_EDGE = 0xFF231812;
    /** How much of the artwork's own colour (at wall brightness) tints the light on the wall. */
    private static final float TINT = 0.25f;
    /** The frame hangs centred in this part of the wall, leaving room for the plaque below. */
    private static final float AREA_TOP = 0.05f, AREA_HEIGHT = 0.71f, AREA_WIDTH = 0.84f;

    private final long advanceMs;
    private final ArtPlaylist playlist;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Paint wallPaint = new Paint(Paint.DITHER_FLAG);
    private final Typeface outfit;
    private final float density;
    private final LinearLayout plaque;
    private final TextView plaqueTitle, plaqueArtist, plaqueCredit;
    private TextView emptyNote;

    private View current;
    private Object currentSlide;
    private int frameRight, frameBottom;
    private ValueAnimator fade;
    private boolean loading, running, released, plaqueShown, plaquePinned;
    private int pendingStep, loadToken, failures;
    private long pendingFadeMs;
    private int wallTint = WALL_LIT;

    private final Runnable advance = () -> show(1, CROSSFADE_MS);
    private final Runnable hidePlaque = () -> setPlaqueShown(false, PLAQUE_OUT_MS);

    /**
     * @param advanceMs   how long each piece hangs
     * @param quoteChance chance that a slide is a quote card instead of art; 0 for none
     */
    public ArtWallView(Context ctx, long advanceMs, float quoteChance) {
        super(ctx);
        this.advanceMs = advanceMs;
        playlist = new ArtPlaylist(ArtCatalog.loadArt(ctx), ArtCatalog.loadQuotes(ctx), quoteChance);
        outfit = ctx.getResources().getFont(R.font.outfit);
        density = ctx.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);

        plaque = new LinearLayout(ctx);
        plaque.setOrientation(LinearLayout.VERTICAL);
        plaque.setPadding(dp(14), dp(10), dp(16), dp(11));
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xFFF1EAD2);
        card.setCornerRadius(dp(2));
        plaque.setBackground(card);
        plaque.setAlpha(0f);
        plaqueTitle = text(14, 500, 0xFF2E2018, 0.01f);
        plaqueTitle.setMaxLines(2);
        plaqueTitle.setEllipsize(TextUtils.TruncateAt.END);
        plaqueArtist = text(12, 400, 0xFF5A4434, 0.01f);
        plaqueCredit = text(9.5f, 400, 0xFF8A7360, 0.02f);
        plaqueCredit.setMaxLines(1);
        plaqueCredit.setEllipsize(TextUtils.TruncateAt.END);
        plaque.addView(plaqueTitle);
        plaque.addView(plaqueArtist, spaced(dp(4)));
        plaque.addView(plaqueCredit, spaced(dp(6)));
        addView(plaque, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
    }

    /** Hangs the first piece, or resumes the timer. */
    public void start() {
        running = true;
        if (playlist.isEmpty()) {
            showEmpty();
        } else if (current == null && !loading && fade == null) {
            show(1, CROSSFADE_MS);
        } else {
            scheduleAdvance();
        }
    }

    /** Pauses the timer; the current piece stays up. */
    public void stop() {
        running = false;
        handler.removeCallbacks(advance);
    }

    public void next() {
        show(1, MANUAL_FADE_MS);
    }

    public void previous() {
        show(-1, MANUAL_FADE_MS);
    }

    /** Shows the plaque until toggled off (and for the pieces after), or hides it. */
    public void togglePlaque() {
        plaquePinned = !plaqueShown;
        if (fade != null || !(currentSlide instanceof Artwork)) return;
        setPlaqueShown(plaquePinned, PLAQUE_QUICK_MS);
    }

    /** Stops everything and frees the bitmaps. The view is unusable afterwards. */
    public void release() {
        released = true;
        running = false;
        loadToken++;
        handler.removeCallbacksAndMessages(null);
        if (fade != null) {
            fade.removeAllListeners();
            fade.cancel();
            fade = null;
        }
        decoder.shutdownNow();
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            removeViewAt(i);
            recycle(child);
        }
        current = null;
    }

    private void show(int step, long fadeMs) {
        handler.removeCallbacks(advance);
        if (released) return;
        if (fade != null) {
            setPending(step, fadeMs);
            fade.end();   // finishing the fade runs the pending step
            return;
        }
        if (loading || getWidth() == 0) {
            setPending(step, fadeMs);
            return;
        }
        Object slide = step > 0 ? playlist.next() : playlist.previous();
        if (slide == null) {
            scheduleAdvance();
        } else if (slide instanceof Quote) {
            View card = quoteCard((Quote) slide);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            lp.bottomMargin = Math.round(getHeight() * 0.04f);
            crossfade(card, lp, slide, WALL_LIT, fadeMs);
        } else {
            load((Artwork) slide, step, fadeMs);
        }
    }

    private void load(Artwork art, int step, long fadeMs) {
        FramedArtView.Geometry geometry = new FramedArtView.Geometry(getHeight(), !art.space);
        int maxW = Math.round(getWidth() * AREA_WIDTH) - 2 * geometry.border();
        int maxH = Math.round(getHeight() * AREA_HEIGHT) - 2 * geometry.border();
        AssetManager assets = getContext().getAssets();
        int token = ++loadToken;
        loading = true;
        decoder.execute(() -> {
            ArtDecoder.Result result = ArtDecoder.decode(assets, art.path, maxW, maxH);
            int tint = result == null ? WALL_LIT : wallTintFor(result.average);
            handler.post(() -> onDecoded(token, art, geometry, result, tint, step, fadeMs));
        });
    }

    private void onDecoded(int token, Artwork art, FramedArtView.Geometry geometry, ArtDecoder.Result result,
                           int tint, int step, long fadeMs) {
        if (token != loadToken || released) {
            if (result != null) result.bitmap.recycle();
            return;
        }
        loading = false;
        if (result == null) {
            if (++failures < MAX_FAILURES) {
                int retry = pendingStep != 0 ? pendingStep : step;
                pendingStep = 0;
                show(retry, fadeMs);
            } else {
                failures = 0;
                scheduleAdvance();
            }
            return;
        }
        failures = 0;
        FramedArtView view = new FramedArtView(getContext(), result.bitmap, geometry, !art.space);
        int frameW = result.bitmap.getWidth() + 2 * geometry.border();
        int frameH = result.bitmap.getHeight() + 2 * geometry.border();
        int left = (getWidth() - frameW) / 2;
        int top = Math.round(getHeight() * AREA_TOP + (getHeight() * AREA_HEIGHT - frameH) / 2f);
        LayoutParams lp = new LayoutParams(view.viewWidth(), view.viewHeight(), Gravity.TOP | Gravity.START);
        lp.leftMargin = left - geometry.shadow;
        lp.topMargin = top - geometry.shadow;
        crossfade(view, lp, art, tint, fadeMs);
        frameRight = left + frameW;
        frameBottom = top + frameH;
    }

    private void crossfade(View incoming, LayoutParams lp, Object slide, int tint, long fadeMs) {
        View outgoing = current;
        current = incoming;
        currentSlide = slide;
        if (emptyNote != null) {
            removeView(emptyNote);
            emptyNote = null;
        }
        if (plaqueShown) setPlaqueShown(false, PLAQUE_QUICK_MS);
        incoming.setAlpha(0f);
        addView(incoming, indexOfChild(plaque), lp);

        // Fade through hardware layers so each piece is composited, not redrawn, every frame.
        incoming.setLayerType(LAYER_TYPE_HARDWARE, null);
        if (outgoing != null) outgoing.setLayerType(LAYER_TYPE_HARDWARE, null);
        int fromTint = wallTint;
        fade = ValueAnimator.ofFloat(0f, 1f).setDuration(fadeMs);
        fade.setInterpolator(new AccelerateDecelerateInterpolator());
        fade.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            incoming.setAlpha(f);
            if (outgoing != null) outgoing.setAlpha(1f - f);
            setWallTint(blend(fromTint, tint, f));
        });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                fade = null;
                incoming.setAlpha(1f);
                incoming.setLayerType(LAYER_TYPE_NONE, null);
                setWallTint(tint);
                if (outgoing != null) {
                    removeView(outgoing);
                    recycle(outgoing);
                }
                onSlideShown();
            }
        });
        fade.start();
    }

    private void onSlideShown() {
        if (pendingStep != 0) {
            int step = pendingStep;
            pendingStep = 0;
            show(step, pendingFadeMs);
            return;
        }
        if (currentSlide instanceof Artwork) {
            fillPlaque((Artwork) currentSlide);
            setPlaqueShown(true, PLAQUE_IN_MS);
        }
        scheduleAdvance();
    }

    private void setPending(int step, long fadeMs) {
        pendingStep = step;
        pendingFadeMs = fadeMs;
    }

    private void scheduleAdvance() {
        handler.removeCallbacks(advance);
        if (running && !released && !playlist.isEmpty()) handler.postDelayed(advance, advanceMs);
    }

    private void setPlaqueShown(boolean show, long ms) {
        plaqueShown = show;
        handler.removeCallbacks(hidePlaque);
        plaque.animate().cancel();
        plaque.animate().alpha(show ? 1f : 0f).setDuration(ms).withLayer().start();
        if (show && !plaquePinned) handler.postDelayed(hidePlaque, PLAQUE_HOLD_MS);
    }

    /** Fills in the plaque and hangs it under the frame's right edge, or in the corner if there's no room. */
    private void fillPlaque(Artwork art) {
        plaqueTitle.setText(art.title.isEmpty() ? "Untitled" : art.title);
        String byline = art.artist.isEmpty() ? art.year
                : art.year.isEmpty() ? art.artist : art.artist + ", " + art.year;
        setLine(plaqueArtist, byline);
        setLine(plaqueCredit, art.credit);

        int edge = dp(24);
        plaque.measure(MeasureSpec.makeMeasureSpec(Math.min(dp(400), getWidth() * 2 / 5), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int w = plaque.getMeasuredWidth();
        int h = plaque.getMeasuredHeight();
        int left = frameRight - w;
        int top = frameBottom + dp(16);
        if (top + h > getHeight() - edge) {
            left = getWidth() - edge - w;
            top = getHeight() - edge - h;
        }
        LayoutParams lp = (LayoutParams) plaque.getLayoutParams();
        lp.leftMargin = Math.max(edge, left);
        lp.topMargin = top;
        plaque.setLayoutParams(lp);
    }

    private View quoteCard(Quote quote) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        String body = quote.text;
        if (!body.startsWith("“") && !body.startsWith("\"")) body = "“" + body + "”";
        float size = body.length() > 260 ? 22 : body.length() > 160 ? 26 : 32;
        TextView text = text(size, 300, 0xFFF1EAD2, 0f);
        text.setText(body);
        text.setGravity(Gravity.CENTER);
        text.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        text.setLineSpacing(0, 1.25f);
        text.setMaxWidth(Math.round(getWidth() * 0.62f));
        card.addView(text);

        if (!quote.author.isEmpty()) {
            View rule = new View(getContext());
            rule.setBackgroundColor(0x80C9B48F);
            LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(dp(36), Math.max(1, dp(1)));
            ruleLp.topMargin = dp(28);
            ruleLp.bottomMargin = dp(18);
            card.addView(rule, ruleLp);
            TextView author = text(13, 500, 0xFFC9B48F, 0.2f);
            author.setAllCaps(true);
            author.setText(quote.author);
            card.addView(author);
        }
        return card;
    }

    private void showEmpty() {
        if (emptyNote != null) return;
        emptyNote = text(15, 400, 0x99F1EAD2, 0.12f);
        emptyNote.setAllCaps(true);
        emptyNote.setText("The gallery is empty");
        addView(emptyNote, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateWallShader();
        if (pendingStep != 0 && !loading && fade == null) {
            post(() -> {
                int step = pendingStep;
                pendingStep = 0;
                if (step != 0) show(step, pendingFadeMs);
            });
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), wallPaint);
    }

    private void setWallTint(int tint) {
        if (tint == wallTint) return;
        wallTint = tint;
        updateWallShader();
    }

    /** A soft pool of light on the wall just above centre, falling off to the room's corners. */
    private void updateWallShader() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        wallPaint.setShader(new RadialGradient(w / 2f, h * 0.42f, Math.max(w, h) * 0.85f,
                new int[]{wallTint, blend(wallTint, WALL_EDGE, 0.55f), WALL_EDGE},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        invalidate();
    }

    /** The wall's lit colour nudged toward the artwork's hue, at the wall's own brightness. */
    private static int wallTintFor(int average) {
        float luma = luma(average);
        if (luma < 1f) return WALL_LIT;
        float k = luma(WALL_LIT) / luma;
        int matched = Color.rgb(Math.min(255, Math.round(Color.red(average) * k)),
                Math.min(255, Math.round(Color.green(average) * k)),
                Math.min(255, Math.round(Color.blue(average) * k)));
        return blend(WALL_LIT, matched, TINT);
    }

    private static float luma(int c) {
        return 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c);
    }

    private static int blend(int from, int to, float t) {
        return Color.rgb(Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    private static void recycle(View view) {
        if (view instanceof FramedArtView) ((FramedArtView) view).bitmap().recycle();
    }

    private static void setLine(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text.isEmpty() ? GONE : VISIBLE);
    }

    private TextView text(float sp, int weight, int color, float tracking) {
        TextView t = new TextView(getContext());
        t.setTypeface(outfit);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLetterSpacing(tracking);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
