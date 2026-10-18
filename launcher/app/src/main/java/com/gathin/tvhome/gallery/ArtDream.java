package com.gathin.tvhome.gallery;

import android.service.dreams.DreamService;

/**
 * Screensaver version of the gallery: the same wall, a new piece every minute and now and then a quote.
 * Non-interactive, so any key wakes the TV.
 */
public class ArtDream extends DreamService {

    private static final long ADVANCE_MS = 60_000L;
    private static final float QUOTE_CHANCE = 1f / 6f;

    private ArtWallView wall;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        wall = new ArtWallView(this, ADVANCE_MS, QUOTE_CHANCE);
        setContentView(wall);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        wall.start();
    }

    @Override
    public void onDreamingStopped() {
        wall.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (wall != null) wall.release();
        super.onDetachedFromWindow();
    }
}
