package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Art-first home: a rotating painting, a quiet clock, three round buttons, the quote and a small dock. The
 * other apps sit in a panel that slides up over the art. One glass highlight springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Started by name: the gallery is built separately and may not be in every build. */
    private static final String GALLERY_ACTIVITY = "com.gathin.tvhome.gallery.GalleryActivity";
    private static final long PANEL_MS = 220;

    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options -> "Don't keep activities"); they only reset when the process dies.
    /** Dock tile to return to; after an app from the grid, the All apps tile. */
    private static String lastDockKey;
    /** Slot of the dock tile focused last; closing the panel returns there. */
    private static int lastDockSlot;
    /** Top button that last had focus, or 0 when a tile had it. */
    private static int lastButtonId;

    private String liveTvPkg;
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
    private PopupWindow inputsPopup;

    private GlassView glass;
    private SpringAnimation springX, springY;
    private float glassTargetY;
    private boolean glassShown;
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.5f);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        columns = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        wallpaper = findViewById(R.id.wallpaper);
        wallpaper.bindQuote(findViewById(R.id.quote), findViewById(R.id.quoteText), findViewById(R.id.quoteAuthor));
        topButtons = findViewById(R.id.topButtons);
        bottomBlock = findViewById(R.id.bottomBlock);
        gridPanel = findViewById(R.id.gridPanel);
        gridContent = findViewById(R.id.gridContent);

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
        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(320f).setDampingRatio(0.62f));
        }

        View btnGallery = findViewById(R.id.btnGallery);
        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnGallery.setOnClickListener(v -> open(new Intent().setClassName(getPackageName(), GALLERY_ACTIVITY)));
        btnInputs.setOnClickListener(v -> showInputs());
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
                    return v.getId() == R.id.btnGallery;
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
        for (View b : new View[]{btnGallery, btnInputs, btnSettings}) {
            b.setOnKeyListener(buttonKeys);
            b.setOnFocusChangeListener(buttonFocus);
        }

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF == null || newF.getId() != R.id.card) hideGlass();
        });
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
        glassShown = false;
        glass.setAlpha(0f);
        wallpaper.start();
        AppAdapter.Apps apps = AppAdapter.loadApps(this);
        List<AppAdapter.Entry> dockItems = new ArrayList<>(apps.dock);
        dockItems.add(AppAdapter.allAppsEntry(this));
        dockAdapter.setItems(dockItems);
        gridAdapter.setItems(apps.others);
        closeGrid(false);

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
        wallpaper.stop();
        if (inputsPopup != null) inputsPopup.dismiss();
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

    // ---- tiles -----------------------------------------------------------------------------------

    @Override
    public void onTileFocused(AppAdapter adapter, int position, View card) {
        lastButtonId = 0;
        if (adapter == dockAdapter) {
            lastDockKey = adapter.keyAt(position);
            lastDockSlot = position;
        } else {
            lastDockKey = AppAdapter.ALL_APPS_KEY;
            View item = (View) card.getParent();
            // Scroll just enough so the whole glass, not only the tile, is visible.
            Rect want = new Rect(0, 0, item.getWidth(), item.getHeight());
            grid.requestChildRectangleOnScreen(item, want, false);
        }
        moveGlass(card);
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
        // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
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

    // ---- glass highlight -------------------------------------------------------------------------

    private void moveGlass(View card) {
        // Tile rect in root coordinates, using layout positions (ignores the scale and panel animations).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = GlassView.GLOW_DP * density;
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.CARD_RADIUS_DP * density * AppAdapter.FOCUS_SCALE);
        }
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(150).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(150).start();
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

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: just wide enough for "HDMI 1" and a status.
        int width = (int) (160 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        // Hangs below the button, its right edge on the button row's right edge.
        View row = findViewById(R.id.topButtons);
        popup.showAsDropDown(anchor, row.getWidth() - anchor.getRight(), (int) (12 * density), Gravity.END);
        inputsPopup = popup;
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(ease).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
