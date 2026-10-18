package com.gathin.tvhome.gallery;

import com.gathin.tvhome.gallery.ArtCatalog.Artwork;
import com.gathin.tvhome.gallery.ArtCatalog.Quote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * What hangs next: paintings, the odd space image and, optionally, a quote, each drawn from its own
 * shuffled bag so nothing repeats until its whole set has been shown. Keeps a short history for "previous".
 * Slides are {@link Artwork} or {@link Quote} objects.
 */
final class ArtPlaylist {

    /** Share of art slides that are space images, when there are both. */
    private static final float SPACE_SHARE = 0.2f;
    private static final int HISTORY = 40;

    private final Random random = new Random();
    private final Bag<Artwork> paintings = new Bag<>();
    private final Bag<Artwork> space = new Bag<>();
    private final Bag<Quote> quotes = new Bag<>();
    private final float quoteChance;
    private final List<Object> history = new ArrayList<>();
    private int position = -1;
    /** Shown before the first pick, then the shuffle carries on as usual. */
    private Artwork first;

    ArtPlaylist(List<Artwork> art, List<Quote> quoteList, float quoteChance) {
        for (Artwork a : art) (a.space ? space : paintings).items.add(a);
        if (quoteChance > 0) quotes.items.addAll(quoteList);
        this.quoteChance = quoteChance;
    }

    /** Hangs the piece with this asset file name (e.g. "hokusai_great_wave.jpg") first, if there is one. */
    void startWith(String file) {
        if (file == null) return;
        List<Artwork> all = new ArrayList<>(paintings.items);
        all.addAll(space.items);
        for (Artwork a : all) {
            if (a.path.endsWith("/" + file)) {
                first = a;
                return;
            }
        }
    }

    boolean isEmpty() {
        return paintings.items.isEmpty() && space.items.isEmpty() && quotes.items.isEmpty();
    }

    /** The next slide: forward through history first, then a fresh pick. Null when there is nothing. */
    Object next() {
        if (position < history.size() - 1) return history.get(++position);
        Object slide = first != null ? first : pick();
        first = null;
        if (slide == null) return null;
        history.add(slide);
        if (history.size() > HISTORY) history.remove(0);
        position = history.size() - 1;
        return slide;
    }

    /** The slide before the current one, or null at the start of history. */
    Object previous() {
        return position > 0 ? history.get(--position) : null;
    }

    private Object pick() {
        boolean hasArt = !paintings.items.isEmpty() || !space.items.isEmpty();
        if (!quotes.items.isEmpty() && (!hasArt || random.nextFloat() < quoteChance)) {
            return quotes.next(random);
        }
        if (space.items.isEmpty()) return paintings.next(random);
        if (paintings.items.isEmpty() || random.nextFloat() < SPACE_SHARE) return space.next(random);
        return paintings.next(random);
    }

    /** Shuffle without repeats; a refill never starts with the item that ended the previous round. */
    private static final class Bag<T> {
        final List<T> items = new ArrayList<>();
        private final ArrayDeque<T> queue = new ArrayDeque<>();
        private T last;

        T next(Random random) {
            if (items.isEmpty()) return null;
            if (queue.isEmpty()) {
                List<T> round = new ArrayList<>(items);
                Collections.shuffle(round, random);
                if (round.size() > 1 && round.get(0) == last) Collections.swap(round, 0, round.size() - 1);
                queue.addAll(round);
            }
            last = queue.poll();
            return last;
        }
    }
}
