package com.explorationremix.world.lava;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The per-chunk record of what the Basalt Galleries looked like before they changed (0.5.1). */
class ChangeLogTest {

    @Test
    void keysHoldXZAndHeight() {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int dy : new int[]{0, 1, 17, 128, 255}) {
                    int k = ChangeLog.key(x, dy, z);
                    assertEquals(x, ChangeLog.x(k));
                    assertEquals(z, ChangeLog.z(k));
                    assertEquals(dy, ChangeLog.dy(k));
                    assertEquals(0, k >>> 16, "a key fits in 16 bits");
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ChangeLog.key(16, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ChangeLog.key(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ChangeLog.key(0, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> ChangeLog.key(0, -1, 0));
    }

    @Test
    void keepsOnlyTheFirstBlockOfAPosition() {
        ChangeLog log = new ChangeLog();
        int k = ChangeLog.key(3, 40, 9);
        assertFalse(log.has(k));
        assertEquals(ChangeLog.NONE, log.state(k));
        assertEquals(ChangeLog.NONE, log.gen(k));
        assertTrue(log.record(k, 77, 2));
        assertFalse(log.record(k, 12, 5), "a later change keeps the terrain's own block");
        assertEquals(77, log.state(k));
        assertEquals(2, log.gen(k));
        assertEquals(1, log.size());
        assertTrue(log.remove(k));
        assertFalse(log.remove(k));
        assertTrue(log.isEmpty());
        // block state 0 (air) is a block like any other
        assertTrue(log.record(k, 0, 0));
        assertEquals(0, log.state(k));
        assertThrows(IllegalArgumentException.class, () -> log.record(ChangeLog.key(0, 0, 0), -1, 0));
    }

    @Test
    void oldestGenerationFollowsRemovals() {
        ChangeLog log = new ChangeLog();
        assertEquals(Integer.MAX_VALUE, log.oldestGen());
        assertFalse(log.hasOlderThan(Integer.MAX_VALUE));
        log.record(ChangeLog.key(0, 0, 0), 1, 4);
        log.record(ChangeLog.key(1, 0, 0), 1, 2);
        log.record(ChangeLog.key(2, 0, 0), 1, 7);
        assertEquals(2, log.oldestGen());
        assertTrue(log.hasOlderThan(3));
        assertFalse(log.hasOlderThan(2), "a change of generation 2 waits for the reset of generation 3");
        assertEquals(1, log.countOlderThan(3));
        assertEquals(2, log.countOlderThan(5));
        assertEquals(3, log.countOlderThan(8));
        log.remove(ChangeLog.key(1, 0, 0));
        assertEquals(4, log.oldestGen());
        log.remove(ChangeLog.key(0, 0, 0));
        assertEquals(7, log.oldestGen());
        log.remove(ChangeLog.key(2, 0, 0));
        assertEquals(Integer.MAX_VALUE, log.oldestGen());
        log.record(ChangeLog.key(5, 5, 5), 3, 9);
        assertEquals(9, log.oldestGen());
        log.clear();
        assertEquals(Integer.MAX_VALUE, log.oldestGen());
        assertEquals(0, log.size());
    }

    @Test
    void olderChangesComeLowestFirst() {
        ChangeLog log = new ChangeLog();
        Random r = new Random(5);
        for (int n = 0; n < 2000; n++) log.record(ChangeLog.key(r.nextInt(16), r.nextInt(256), r.nextInt(16)), r.nextInt(100), r.nextInt(6));
        for (int gen = 0; gen <= 6; gen++) {
            int[] keys = log.olderThan(gen);
            assertEquals(log.countOlderThan(gen), keys.length);
            for (int k : keys) assertTrue(log.gen(k) < gen);
            for (int j = 1; j < keys.length; j++) {
                int a = keys[j - 1], b = keys[j];
                assertTrue(ChangeLog.dy(a) < ChangeLog.dy(b) || ChangeLog.dy(a) == ChangeLog.dy(b)
                        && (ChangeLog.z(a) < ChangeLog.z(b) || ChangeLog.z(a) == ChangeLog.z(b) && ChangeLog.x(a) < ChangeLog.x(b)),
                        "by height, then z, then x");
            }
        }
        int[] all = log.olderThan(Integer.MAX_VALUE);
        assertEquals(log.size(), all.length);
    }

    @Test
    void movesWithTheLayersBottom() {
        ChangeLog log = new ChangeLog();
        assertEquals(ChangeLog.NO_BASE, log.base());
        log.align(-800);
        assertEquals(-800, log.base());
        log.record(ChangeLog.key(1, 0, 1), 10, 0);      // y -800
        log.record(ChangeLog.key(2, 100, 2), 11, 1);    // y -700
        log.record(ChangeLog.key(3, 255, 3), 12, 2);    // y -545
        // the layer's bottom moved 50 blocks down (the config grew the layer): the same blocks sit 50 higher over it
        log.align(-850);
        assertEquals(-850, log.base());
        assertEquals(10, log.state(ChangeLog.key(1, 50, 1)));
        assertEquals(11, log.state(ChangeLog.key(2, 150, 2)));
        assertEquals(1, log.gen(ChangeLog.key(2, 150, 2)));
        assertFalse(log.has(ChangeLog.key(3, 255, 3)));
        assertEquals(2, log.size(), "the block that left the top of the layer is dropped");
        // and back up past the first one
        log.align(-790);
        assertEquals(1, log.size());
        assertEquals(11, log.state(ChangeLog.key(2, 90, 2)));
        assertEquals(1, log.oldestGen(), "the oldest generation is worked out again after a move");
        // aligning to the same bottom changes nothing
        log.align(-790);
        assertEquals(1, log.size());
    }

    @Test
    void savesAndLoadsItsEntries() {
        ChangeLog log = new ChangeLog();
        log.align(-832);
        Random r = new Random(11);
        for (int n = 0; n < 500; n++) log.record(ChangeLog.key(r.nextInt(16), r.nextInt(256), r.nextInt(16)), r.nextInt(30000), r.nextInt(50));
        int[][] a = log.toArrays();
        assertEquals(3, a.length);
        assertEquals(log.size(), a[0].length);
        ChangeLog back = new ChangeLog();
        back.load(log.base(), a[0], a[1], a[2]);
        assertEquals(log.size(), back.size());
        assertEquals(-832, back.base());
        log.forEach((k, s, g) -> {
            assertEquals(s, back.state(k));
            assertEquals(g, back.gen(k));
        });
        assertEquals(log.oldestGen(), back.oldestGen());
    }

    @Test
    void loadingSkipsWhatItCannotRead() {
        ChangeLog log = new ChangeLog();
        log.record(ChangeLog.key(9, 9, 9), 5, 5);
        // arrays of different lengths keep what they have in common; keys over 16 bits and negative states are skipped
        log.load(-700, new int[]{ChangeLog.key(1, 1, 1), 1 << 16, ChangeLog.key(2, 2, 2), ChangeLog.key(3, 3, 3)},
                new int[]{7, 8, -1, 9, 10}, new int[]{0, 1, 2});
        assertEquals(1, log.size(), "the old entries are replaced");
        assertEquals(7, log.state(ChangeLog.key(1, 1, 1)));
        assertFalse(log.has(ChangeLog.key(2, 2, 2)));
        assertFalse(log.has(ChangeLog.key(3, 3, 3)));
        assertFalse(log.has(ChangeLog.key(9, 9, 9)));
        assertEquals(-700, log.base());
        log.load(ChangeLog.NO_BASE, new int[0], new int[0], new int[0]);
        assertTrue(log.isEmpty());
        assertEquals(ChangeLog.NO_BASE, log.base());
    }

    /** Random records, removals, moves and reloads against a plain map. */
    @Test
    void matchesAPlainMap() {
        Random r = new Random(42);
        for (int round = 0; round < 20; round++) {
            ChangeLog log = new ChangeLog();
            Map<Integer, int[]> ref = new HashMap<>();
            int base = -800;
            log.align(base);
            for (int step = 0; step < 3000; step++) {
                int op = r.nextInt(100);
                if (op < 60) {
                    int k = ChangeLog.key(r.nextInt(16), r.nextInt(256), r.nextInt(16));
                    int s = r.nextInt(1000), g = r.nextInt(8);
                    boolean added = log.record(k, s, g);
                    assertEquals(!ref.containsKey(k), added);
                    ref.putIfAbsent(k, new int[]{s, g});
                } else if (op < 90) {
                    int k = ChangeLog.key(r.nextInt(16), r.nextInt(256), r.nextInt(16));
                    assertEquals(ref.remove(k) != null, log.remove(k));
                } else if (op < 95) {
                    // remove everything older than a generation, as a reset does
                    int gen = r.nextInt(9);
                    for (int k : log.olderThan(gen)) {
                        assertTrue(ref.get(k)[1] < gen);
                        log.remove(k);
                        ref.remove(k);
                    }
                    for (int[] v : ref.values()) assertTrue(v[1] >= gen);
                } else if (op < 98) {
                    int by = r.nextInt(41) - 20;
                    int nb = base + by;
                    log.align(nb);
                    Map<Integer, int[]> moved = new HashMap<>();
                    for (Map.Entry<Integer, int[]> e : ref.entrySet()) {
                        int k = e.getKey(), dy = ChangeLog.dy(k) + base - nb;
                        if (dy >= 0 && dy <= 255) moved.put(ChangeLog.key(ChangeLog.x(k), dy, ChangeLog.z(k)), e.getValue());
                    }
                    ref = moved;
                    base = nb;
                } else {
                    int[][] a = log.toArrays();
                    ChangeLog back = new ChangeLog();
                    back.load(log.base(), a[0], a[1], a[2]);
                    log = back;
                }
                if (step % 97 == 0 || op >= 90) check(log, ref);
            }
            check(log, ref);
        }
    }

    private static void check(ChangeLog log, Map<Integer, int[]> ref) {
        assertEquals(ref.size(), log.size());
        int oldest = Integer.MAX_VALUE;
        for (Map.Entry<Integer, int[]> e : ref.entrySet()) {
            assertEquals(e.getValue()[0], log.state(e.getKey()));
            assertEquals(e.getValue()[1], log.gen(e.getKey()));
            oldest = Math.min(oldest, e.getValue()[1]);
        }
        assertEquals(oldest, log.oldestGen());
        List<Integer> seen = new ArrayList<>();
        log.forEach((k, s, g) -> seen.add(k));
        assertEquals(ref.size(), seen.size());
    }
}
