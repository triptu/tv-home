package com.gathin.tvhome;

import android.service.dreams.DreamService;

/** Screensaver: the home screen's slowly moving painting and its quote and caption, with no clock, buttons or dock. */
public class ArtDream extends DreamService {

    private WallpaperView wallpaper;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(R.layout.dream_art);
        wallpaper = findViewById(R.id.wallpaper);
        wallpaper.bindQuote(findViewById(R.id.quote), findViewById(R.id.quoteText), findViewById(R.id.quoteAuthor));
        wallpaper.bindCaption(findViewById(R.id.caption), findViewById(R.id.captionTitle), findViewById(R.id.captionByline));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        wallpaper.start();
    }

    @Override
    public void onDreamingStopped() {
        wallpaper.stop();
        super.onDreamingStopped();
    }
}
