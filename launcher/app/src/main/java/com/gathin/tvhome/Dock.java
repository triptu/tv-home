package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which apps sit in the dock, in order. The launcher ships with {@code dock_apps} from config.xml; pinning an app
 * from the TV (hold OK on a tile) or over adb saves a list that replaces it until it is cleared again.
 */
final class Dock {

    /** Tiles the dock holds, plus All apps: the same width as a row of the grid. */
    static final int MAX = 5;

    private static final String PREFS = "dock";
    private static final String KEY = "apps";

    private Dock() {
    }

    /** The pinned package names: the saved list if there is one, else config.xml. */
    static List<String> pinned(Context ctx) {
        String saved = prefs(ctx).getString(KEY, null);
        if (saved != null) return parse(saved);
        return Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
    }

    /** Saves [apps] as the dock; null goes back to config.xml. */
    static void save(Context ctx, List<String> apps) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (apps == null) e.remove(KEY);
        else e.putString(KEY, String.join(",", apps));
        e.apply();
    }

    /** "a,b,c" (spaces and empty items ignored) to a list; "" to an empty dock. */
    static List<String> parse(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String p = s.trim();
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /** [pkg] at the end of the dock, once; unchanged when the dock is full. */
    static List<String> pin(List<String> dock, String pkg) {
        List<String> out = new ArrayList<>(dock);
        if (out.contains(pkg) || out.size() >= MAX) return out;
        out.add(pkg);
        return out;
    }

    static List<String> unpin(List<String> dock, String pkg) {
        List<String> out = new ArrayList<>(dock);
        out.remove(pkg);
        return out;
    }

    /** [pkg] one slot left ([delta] -1) or right (+1); unchanged at either end. */
    static List<String> move(List<String> dock, String pkg, int delta) {
        List<String> out = new ArrayList<>(dock);
        int from = out.indexOf(pkg), to = from + delta;
        if (from < 0 || to < 0 || to >= out.size()) return out;
        out.remove(from);
        out.add(to, pkg);
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
