package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Art-first home: a rotating painting, a quiet clock, round buttons, the quote and a small dock. The
 * other apps sit in a panel that slides up over the art. The focused tile lifts; nothing travels between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Started by name: the gallery is built separately and may not be in every build. */
    private static final String GALLERY_ACTIVITY = "com.gathin.tvhome.gallery.GalleryActivity";
    /** GalleryActivity.EXTRA_START_FILE: the painting to hang first. */
    private static final String EXTRA_START_FILE = "start_file";
    private static final long PANEL_MS = 220;
    /** With no key pressed for this long, the UI fades back and the art takes over. */
    private static final long IDLE_MS = 8_000;
    private static final long IDLE_FADE_MS = 1_200;
    private static final long WAKE_MS = 250;
    private static final float IDLE_ALPHA = 0f, IDLE_CLOCK_ALPHA = 0.5f;

    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options -> "Don't keep activities"); they only reset when the process dies.
    /** Dock tile to return to; after an app from the grid, the All apps tile. */
    private static String lastDockKey;
    /** Slot of the dock tile focused last; closing the panel returns there. */
    private static int lastDockSlot;
    /** Top button that last had focus, or 0 when a tile had it. */
    private static int lastButtonId;

    private int columns;
    private float density;

    private FrameLayout root;
    private WallpaperView wallpaper;
    private View topButtons;
    private View bottomBlock;
    private RecyclerView dock;
    private RecyclerView grid;
    private View gridPanel;
    private View gridContent;
    private AppAdapter dockAdapter;
    private AppAdapter gridAdapter;
    private boolean gridOpen;
    private boolean stopped = true;

    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.5f);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable goIdle = this::fadeToIdle;
    /** Faded back: the next key only wakes the screen. */
    private boolean idle;
    /** The key that woke the screen; its repeats and release are swallowed too. */
    private int wakeKey = KeyEvent.KEYCODE_UNKNOWN;
    /** Faded to IDLE_ALPHA when idle. */
    private View[] idleFaint;
    /** Faded to IDLE_CLOCK_ALPHA when idle. */
    private View[] idleDim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        columns = Math.max(1, getResources().getInteger(R.integer.columns));

        root = findViewById(R.id.root);
        wallpaper = findViewById(R.id.wallpaper);
        wallpaper.bindQuote(findViewById(R.id.quote), findViewById(R.id.quoteText), findViewById(R.id.quoteAuthor));
        topButtons = findViewById(R.id.topButtons);
        bottomBlock = findViewById(R.id.bottomBlock);
        gridPanel = findViewById(R.id.gridPanel);
        gridContent = findViewById(R.id.gridContent);
        idleFaint = new View[]{bottomBlock, topButtons};
        idleDim = new View[]{findViewById(R.id.clock), findViewById(R.id.date)};

        dock = findViewById(R.id.dock);
        dock.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        dock.setItemAnimator(null);
        dockAdapter = new AppAdapter(this, this);
        dock.setAdapter(dockAdapter);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setItemAnimator(null);
        gridAdapter = new AppAdapter(this, this);
        grid.setAdapter(gridAdapter);
        View btnArt = findViewById(R.id.btnArt);
        View btnSettings = findViewById(R.id.btnSettings);
        // Art mode opens on the painting the home screen is showing, fresh even if an old one was left open.
        btnArt.setOnClickListener(v -> open(new Intent().setClassName(getPackageName(), GALLERY_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_START_FILE, wallpaper.currentFile())));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));

        // Keep focus on the buttons at their ends and at the top; Down goes back to the dock tile focus came from.
        View.OnKeyListener buttonKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focusDock(dockAdapter.positionOf(lastDockKey) >= 0 ? dockAdapter.positionOf(lastDockKey) : lastDockSlot);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return v.getId() == R.id.btnArt;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return v.getId() == R.id.btnSettings;
                default:
                    return false;
            }
        };
        View.OnFocusChangeListener buttonFocus = (v, focused) -> {
            float s = focused ? 1.1f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(150).setInterpolator(ease).start();
            if (focused) lastButtonId = v.getId();
        };
        for (View b : new View[]{btnArt, btnSettings}) {
            b.setOnKeyListener(buttonKeys);
            b.setOnFocusChangeListener(buttonFocus);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while home is already showing: start over from the first dock app.
        if (!stopped) {
            lastDockKey = null;
            lastDockSlot = 0;
            lastButtonId = 0;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopped = false;
        wallpaper.start();
        AppAdapter.Apps apps = AppAdapter.loadApps(this);
        List<AppAdapter.Entry> dockItems = new ArrayList<>(apps.dock);
        dockItems.add(AppAdapter.allAppsEntry(this));
        dockAdapter.setItems(dockItems);
        gridAdapter.setItems(apps.others);
        closeGrid(false);
        wake(false);

        // Back where we left: the button or dock tile, or All apps after an app from the grid.
        final int buttonId = lastButtonId;
        final int pos = dockAdapter.positionOf(lastDockKey);
        final int slot = pos >= 0 ? pos : lastDockSlot;
        dock.post(() -> {
            View button = buttonId != 0 ? findViewById(buttonId) : null;
            if (button != null) button.requestFocus();
            else focusDock(slot);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(goIdle);
        wallpaper.stop();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back only closes the app panel.
        if (gridOpen) closeGrid(true);
    }

    // ---- idle fade -------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (isSystemKey(code)) return super.dispatchKeyEvent(event);
        if (code == wakeKey) {
            // The press that woke the screen: swallow its repeats and release so focus stays put.
            if (event.getAction() == KeyEvent.ACTION_UP) wakeKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (idle) {
                wakeKey = code;
                wake(true);
                return true;
            }
            scheduleIdle();
        }
        return super.dispatchKeyEvent(event);
    }

    /** Volume and power keys pass straight through and neither wake nor keep the screen awake. */
    private static boolean isSystemKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER;
    }

    private void scheduleIdle() {
        handler.removeCallbacks(goIdle);
        handler.postDelayed(goIdle, IDLE_MS);
    }

    private void fadeToIdle() {
        if (gridOpen) {   // never while the app panel is open
            scheduleIdle();
            return;
        }
        idle = true;
        AccelerateDecelerateInterpolator inOut = new AccelerateDecelerateInterpolator();
        for (View v : idleFaint) v.animate().alpha(IDLE_ALPHA).setDuration(IDLE_FADE_MS).setInterpolator(inOut).start();
        for (View v : idleDim) v.animate().alpha(IDLE_CLOCK_ALPHA).setDuration(IDLE_FADE_MS).setInterpolator(inOut).start();
    }

    /** Brings the UI back, animated or at once, and restarts the idle timer. */
    private void wake(boolean animate) {
        idle = false;
        for (View[] group : new View[][]{idleFaint, idleDim}) {
            for (View v : group) {
                v.animate().cancel();
                if (animate) v.animate().alpha(1f).setDuration(WAKE_MS).setInterpolator(ease).start();
                else v.setAlpha(1f);
            }
        }
        scheduleIdle();
    }

    // ---- tiles -----------------------------------------------------------------------------------

    @Override
    public void onTileFocused(AppAdapter adapter, int position, View card) {
        lastButtonId = 0;
        if (adapter == dockAdapter) {
            lastDockKey = adapter.keyAt(position);
            lastDockSlot = position;
        } else {
            lastDockKey = AppAdapter.ALL_APPS_KEY;
        }
    }

    @Override
    public boolean onTileKey(AppAdapter adapter, int position, int keyCode) {
        int count = adapter.getItemCount();
        if (adapter == dockAdapter) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    openGrid();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return position == 0;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return position == count - 1;
                default:
                    return false;   // Up: to the top buttons
            }
        }
        int col = position % columns;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (position < columns) closeGrid(true);
                else focusGrid(position - columns);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                int lastRowStart = ((count - 1) / columns) * columns;
                if (position < lastRowStart) focusGrid(Math.min(position + columns, count - 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return col == 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return col == columns - 1 || position == count - 1;
            default:
                return false;
        }
    }

    @Override
    public void onAllApps() {
        openGrid();
    }

    private void focusDock(int position) {
        int n = dockAdapter.getItemCount();
        if (n == 0) return;
        RecyclerView.ViewHolder vh = dock.findViewHolderForAdapterPosition(Math.max(0, Math.min(position, n - 1)));
        if (vh != null) focusCard(dockAdapter, vh);
        else dock.requestFocus();
    }

    private void focusGrid(int position) {
        int n = gridAdapter.getItemCount();
        if (n == 0) return;
        final int pos = Math.max(0, Math.min(position, n - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            focusCard(gridAdapter, vh);
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                if (h != null) focusCard(gridAdapter, h);
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    private void focusCard(AppAdapter adapter, RecyclerView.ViewHolder vh) {
        View card = vh.itemView.findViewById(R.id.card);
        // Already focused (e.g. back from an app): no focus change fires, so record it by hand.
        if (card.isFocused()) onTileFocused(adapter, vh.getBindingAdapterPosition(), card);
        else card.requestFocus();
    }

    // ---- app panel -------------------------------------------------------------------------------

    private void openGrid() {
        if (gridOpen || gridAdapter.getItemCount() == 0) return;
        gridOpen = true;
        gridPanel.setVisibility(View.VISIBLE);
        gridPanel.setAlpha(0f);
        gridContent.setTranslationY(24 * density);
        gridPanel.animate().alpha(1f).setDuration(PANEL_MS).withLayer().start();
        gridContent.animate().translationY(0f).setDuration(PANEL_MS).setInterpolator(ease).start();
        bottomBlock.animate().alpha(0f).setDuration(PANEL_MS).start();
        grid.scrollToPosition(0);
        grid.post(() -> {
            focusGrid(0);
            // The panel covers them: keep focus from wandering back underneath.
            setBlocked(dock, true);
            setBlocked(topButtons, true);
        });
    }

    private void closeGrid(boolean animate) {
        if (!gridOpen) return;
        gridOpen = false;
        setBlocked(dock, false);
        setBlocked(topButtons, false);
        if (animate) focusDock(lastDockSlot);   // the dock tile the panel was opened from
        gridPanel.animate().cancel();
        gridContent.animate().cancel();
        bottomBlock.animate().cancel();
        if (!animate) {
            gridPanel.setVisibility(View.GONE);
            bottomBlock.setAlpha(1f);
            return;
        }
        bottomBlock.animate().alpha(1f).setDuration(PANEL_MS).start();
        gridContent.animate().translationY(24 * density).setDuration(PANEL_MS).setInterpolator(ease).start();
        gridPanel.animate().alpha(0f).setDuration(PANEL_MS).withLayer()
                .withEndAction(() -> gridPanel.setVisibility(View.GONE)).start();
    }

    private static void setBlocked(View v, boolean blocked) {
        ((ViewGroup) v).setDescendantFocusability(
                blocked ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    // ---- top buttons -----------------------------------------------------------------------------

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
