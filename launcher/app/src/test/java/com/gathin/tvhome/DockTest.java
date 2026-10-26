package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DockTest {

    private static final List<String> THREE = Arrays.asList("a", "b", "c");

    @Test
    public void pinAppendsOnce() {
        assertEquals(Arrays.asList("a", "b", "c", "d"), Dock.pin(THREE, "d"));
        assertEquals(THREE, Dock.pin(THREE, "b"));
        assertEquals(Collections.singletonList("a"), Dock.pin(Collections.emptyList(), "a"));
    }

    @Test
    public void pinStopsAtMax() {
        List<String> full = Arrays.asList("1", "2", "3", "4", "5");
        assertEquals(Dock.MAX, full.size());
        assertEquals(full, Dock.pin(full, "6"));
    }

    @Test
    public void unpinRemoves() {
        assertEquals(Arrays.asList("a", "c"), Dock.unpin(THREE, "b"));
        assertEquals(THREE, Dock.unpin(THREE, "zz"));
    }

    @Test
    public void moveSwapsNeighboursAndStopsAtTheEnds() {
        assertEquals(Arrays.asList("b", "a", "c"), Dock.move(THREE, "b", -1));
        assertEquals(Arrays.asList("a", "c", "b"), Dock.move(THREE, "b", +1));
        assertEquals(THREE, Dock.move(THREE, "a", -1));
        assertEquals(THREE, Dock.move(THREE, "c", +1));
        assertEquals(THREE, Dock.move(THREE, "zz", +1));
    }

    @Test
    public void parseIgnoresSpacesEmptiesAndRepeats() {
        assertEquals(THREE, Dock.parse(" a, b ,,c,a"));
        assertEquals(Collections.emptyList(), Dock.parse(""));
    }
}
