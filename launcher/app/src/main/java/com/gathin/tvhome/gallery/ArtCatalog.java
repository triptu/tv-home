package com.gathin.tvhome.gallery;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The art and quotes bundled in assets: art/catalog.json, art/*.jpg and quotes.json.
 * Missing or broken files give empty lists; catalog entries whose image is missing are dropped.
 */
final class ArtCatalog {

    static final String ART_DIR = "art";

    static final class Artwork {
        final String path, title, artist, year, credit;
        final boolean space;

        Artwork(String path, String title, String artist, String year, String credit, boolean space) {
            this.path = path;
            this.title = title;
            this.artist = artist;
            this.year = year;
            this.credit = credit;
            this.space = space;
        }
    }

    static final class Quote {
        final String text, author;

        Quote(String text, String author) {
            this.text = text;
            this.author = author;
        }
    }

    private ArtCatalog() {}

    static List<Artwork> loadArt(Context ctx) {
        AssetManager assets = ctx.getAssets();
        Set<String> files = new HashSet<>();
        try {
            String[] listed = assets.list(ART_DIR);
            if (listed != null) files.addAll(Arrays.asList(listed));
        } catch (IOException ignored) {
        }
        List<Artwork> art = new ArrayList<>();
        JSONArray entries = readArray(assets, ART_DIR + "/catalog.json");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject o = entries.optJSONObject(i);
                if (o == null) continue;
                String file = o.optString("file").trim();
                if (file.isEmpty() || !files.contains(file)) continue;
                art.add(new Artwork(ART_DIR + "/" + file, o.optString("title").trim(),
                        o.optString("artist").trim(), o.optString("year").trim(),
                        o.optString("credit").trim(), "space".equals(o.optString("kind"))));
            }
            return art;
        }
        // No catalog: show whatever images are there, titled after their file names.
        for (String file : files) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png")) continue;
            art.add(new Artwork(ART_DIR + "/" + file, titleFromFile(file), "", "", "", false));
        }
        return art;
    }

    static List<Quote> loadQuotes(Context ctx) {
        List<Quote> quotes = new ArrayList<>();
        JSONArray entries = readArray(ctx.getAssets(), "quotes.json");
        if (entries == null) return quotes;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject o = entries.optJSONObject(i);
            if (o == null) continue;
            String text = o.optString("text").trim();
            if (!text.isEmpty()) quotes.add(new Quote(text, o.optString("author").trim()));
        }
        return quotes;
    }

    private static JSONArray readArray(AssetManager assets, String path) {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new JSONArray(out.toString(StandardCharsets.UTF_8.name()));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static String titleFromFile(String file) {
        String name = file.substring(0, file.lastIndexOf('.')).replace('_', ' ').replace('-', ' ').trim();
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
