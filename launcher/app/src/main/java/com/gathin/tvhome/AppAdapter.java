package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles, used twice: the dock (the pinned apps plus an "All apps" tile) and the grid of every other app.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(AppAdapter adapter, int position, View card);

        /** Called for D-pad keys on a tile. Return true when handled. */
        boolean onTileKey(AppAdapter adapter, int position, int keyCode);

        /** The "All apps" tile was selected. */
        void onAllApps();
    }

    static final class Entry {
        final String label;
        /** Null for the "All apps" tile. */
        final ComponentName component;
        final Drawable banner;
        final Drawable icon;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.banner = banner;
            this.icon = icon;
        }

        String key() {
            return component == null ? ALL_APPS_KEY : component.flattenToShortString();
        }
    }

    /** The two halves of the installed apps: the dock in config order, and the rest alphabetically. */
    static final class Apps {
        final List<Entry> dock = new ArrayList<>();
        final List<Entry> others = new ArrayList<>();
    }

    static final float FOCUS_SCALE = 1.10f;
    static final long FOCUS_MS = 150;
    /** Unfocused cards are muted so the art leads. */
    static final float REST_ALPHA = 0.6f, REST_SATURATION = 0.25f, FOCUS_ALPHA = 0.85f;
    static final float REST_ELEVATION_DP = 3f, FOCUS_ELEVATION_DP = 12f;
    static final float CARD_RADIUS_DP = 10f;
    static final String ALL_APPS_KEY = "all-apps";

    private final Context ctx;
    private final TileListener listener;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener) {
        this.ctx = ctx;
        this.listener = listener;
        // Tiles keep their identity across reloads, so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /** Installed TV apps, split into the dock (res/values/config.xml, dock_apps) and the rest. */
    static Apps loadApps(Context ctx) {
        List<String> favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        Set<String> hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Apps apps = new Apps();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Entry e = new Entry(String.valueOf(ri.loadLabel(pm)), new ComponentName(pkg, ri.activityInfo.name),
                    banner, ri.loadIcon(pm));
            (favourites.contains(pkg) ? apps.dock : apps.others).add(e);
        }
        Collections.sort(apps.dock, (a, b) -> Integer.compare(
                favourites.indexOf(a.component.getPackageName()), favourites.indexOf(b.component.getPackageName())));
        Collections.sort(apps.others, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    static Entry allAppsEntry(Context ctx) {
        return new Entry(ctx.getString(R.string.all_apps), null, null, ctx.getDrawable(R.drawable.ic_all_apps));
    }

    void setItems(List<Entry> entries) {
        items.clear();
        items.addAll(entries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, this);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.snapTo(h.card.isFocused());   // a rebind after a reload can hit the focused tile
        h.card.setContentDescription(e.label);
        boolean allApps = e.component == null;
        h.card.setBackgroundResource(allApps ? R.drawable.tile_glass_bg : R.drawable.tile_bg);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(allApps ? View.GONE : View.VISIBLE);
            h.icon.setTranslationY(allApps ? 0f : -6 * ctx.getResources().getDisplayMetrics().density);
        }
        h.card.setOnClickListener(v -> {
            if (allApps) listener.onAllApps();
            else launch(e);
        });
        h.card.setOnLongClickListener(allApps ? null : v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + e.component.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).key().hashCode();
    }

    /** Stable key for the tile at a position, or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key() : null;
    }

    /** Position of the tile with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(key)) return i;
        }
        return -1;
    }

    /**
     * One tile. At rest it is muted (translucent, mostly grey) and sits low; focused it lifts like tvOS:
     * full colour, larger, a deeper shadow and a white ring. One animator drives all of it, and the colour
     * change is a paint on the card's hardware layer, so nothing is redrawn or re-decoded per frame.
     */
    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        private final Paint layerPaint = new Paint();
        private final ColorMatrix matrix = new ColorMatrix();
        private final ValueAnimator lift = ValueAnimator.ofFloat(0f, 1f);
        private final float restZ, liftZ;
        private float level = -1f;

        Holder(View v, AppAdapter adapter) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * CARD_RADIUS_DP;
            restZ = REST_ELEVATION_DP * density;
            liftZ = FOCUS_ELEVATION_DP * density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            setLevel(0f);

            lift.setDuration(FOCUS_MS);
            lift.setInterpolator(new DecelerateInterpolator(1.5f));
            lift.addUpdateListener(a -> setLevel((float) a.getAnimatedValue()));

            TileListener listener = adapter.listener;
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return pos != RecyclerView.NO_POSITION && listener.onTileKey(adapter, pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                lift.cancel();
                lift.setFloatValues(level, focused ? 1f : 0f);
                lift.start();
                int pos = getBindingAdapterPosition();
                if (focused && pos != RecyclerView.NO_POSITION) listener.onTileFocused(adapter, pos, view);
            });
        }

        /** Jumps to the resting or focused look, e.g. when a rebind hits the focused tile. */
        void snapTo(boolean focused) {
            lift.cancel();
            setLevel(focused ? 1f : 0f);
        }

        /** 0 is the muted resting card, 1 the lifted focused one. */
        private void setLevel(float f) {
            if (f == level) return;
            level = f;
            float scale = 1f + (FOCUS_SCALE - 1f) * f;
            card.setScaleX(scale);
            card.setScaleY(scale);
            card.setTranslationZ((liftZ - restZ) * f);
            card.setElevation(restZ);
            card.setAlpha(REST_ALPHA + (FOCUS_ALPHA - REST_ALPHA) * f);
            matrix.setSaturation(REST_SATURATION + (1f - REST_SATURATION) * f);
            layerPaint.setColorFilter(f >= 1f ? null : new ColorMatrixColorFilter(matrix));
            card.setLayerPaint(layerPaint);
        }
    }
}
