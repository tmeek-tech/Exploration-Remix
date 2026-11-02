package com.explorationremix.world.lava;

import com.explorationremix.world.terrain.Terrain;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static com.explorationremix.world.lava.Flood.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the terrain reset writes where it puts a block back (0.5.1), and a chunk's flood band living through risings,
 * drains, players' changes and resets: after the last reset and a drain it must be exactly as generated, with no lava
 * the drain will never take and no glow lost.
 */
class RestoreTest {
    private static final int RISE = Terrain.FLOOD_RISE;

    @Test
    void underTheSeaOpenBlocksComeBackAsTheSea() {
        int[] kinds = {OTHER, AIR, FIRE, LAVA, FLOWING, GLOW};
        for (int level = 0; level <= RISE; level++) {
            for (int slice = -1; slice <= RISE; slice++) {
                for (int k : kinds) {
                    int d = Restore.decide(k, slice, level);
                    boolean sea = slice >= 0 && slice < level && takes(k);
                    if (!sea) assertEquals(Restore.ORIGINAL, d, "kind " + k + " slice " + slice + " level " + level);
                    else assertEquals(k == GLOW ? Restore.SEA_GLOW : Restore.SEA, d, "kind " + k + " slice " + slice + " level " + level);
                }
            }
        }
        // rock, natural lava pools and anything else come back as themselves even under the sea
        assertEquals(Restore.ORIGINAL, Restore.decide(OTHER, 0, RISE));
        assertEquals(Restore.ORIGINAL, Restore.decide(LAVA, 0, RISE));
    }

    @Test
    void marksFollowTheDecision() {
        Mask m = new Mask(RISE), g = new Mask(RISE);
        Restore.mark(Restore.SEA_GLOW, m, g, 5, 3, 4);
        assertTrue(m.get(5, 3, 4));
        assertTrue(g.get(5, 3, 4));
        Restore.mark(Restore.SEA, m, g, 5, 3, 4);
        assertTrue(m.get(5, 3, 4));
        assertFalse(g.get(5, 3, 4), "a sea block that was air has no glow to give back");
        Restore.mark(Restore.SEA_GLOW, m, g, 5, 3, 4);
        Restore.mark(Restore.ORIGINAL, m, g, 5, 3, 4);
        assertFalse(m.get(5, 3, 4));
        assertFalse(g.get(5, 3, 4));
        // outside the band nothing happens
        Restore.mark(Restore.SEA, m, g, -1, 0, 0);
        Restore.mark(Restore.SEA, m, g, RISE, 0, 0);
        assertTrue(m.isEmpty());
        assertTrue(g.isEmpty());
    }

    @Test
    void theLogSeesThroughTheFlood() {
        assertEquals(AIR, Restore.seeThrough(LAVA, true, false));
        assertEquals(GLOW, Restore.seeThrough(LAVA, true, true));
        assertEquals(LAVA, Restore.seeThrough(LAVA, false, false), "lava the flood did not put there is the terrain's own");
        for (int k : new int[]{OTHER, AIR, FIRE, FLOWING, GLOW}) {
            assertEquals(k, Restore.seeThrough(k, true, false));
            assertEquals(k, Restore.seeThrough(k, false, false));
        }
    }

    // ================================================================================================ the band living on

    /** Blocks of the fake band: air, the glow, a lava source, rock and two blocks players place. */
    private static final int S_AIR = 0, S_GLOW = 1, S_LAVA = 2, S_ROCK = 3, S_PLANK = 4, S_BRICK = 5;

    private static int kindOf(int s) {
        return switch (s) {
            case S_AIR -> AIR;
            case S_GLOW -> GLOW;
            case S_LAVA -> LAVA;
            default -> OTHER;
        };
    }

    /** One chunk's flood band [slice][z][x] with its flood record and change log, changed the way the game changes it. */
    private static final class Band {
        final int[][][] st = new int[RISE][16][16];
        final Mask mask = new Mask(RISE), glow = new Mask(RISE);
        final ChangeLog log = new ChangeLog();
        int level, gen;

        Slice slice(int i) {
            return new Slice() {
                @Override public int kind(int x, int z) { return kindOf(st[i][z][x]); }
                @Override public void fill(int x, int z) { st[i][z][x] = S_LAVA; }
                @Override public void clear(int x, int z) { st[i][z][x] = S_AIR; }
                @Override public void glow(int x, int z) { st[i][z][x] = S_GLOW; }
                @Override public void wake(int x, int z) {}
            };
        }

        void bring(int target) {
            while (level != target) {
                int i = nextSlice(level, target);
                if (level < target) {
                    rise(slice(i), mask, glow, i);
                    level++;
                } else {
                    drain(slice(i), mask, glow, i);
                    level--;
                }
            }
        }

        /** A change by a player, a quake or an eruption: recorded as GalleryChanges records it. */
        void set(int i, int x, int z, int next) {
            int cur = st[i][z][x];
            if (cur == next) return;
            int key = ChangeLog.key(x, i, z);
            if (log.has(key)) {
                if (log.state(key) == next) log.remove(key);
            } else {
                int k = Restore.seeThrough(kindOf(cur), mask.get(i, x, z), glow.get(i, x, z));
                int first = k == AIR && kindOf(cur) != AIR ? S_AIR : k == GLOW && kindOf(cur) != GLOW ? S_GLOW : cur;
                if (first != next) log.record(key, first, gen);
            }
            st[i][z][x] = next;
        }

        /** Puts back the changes older than the generation, as TerrainReset does, at most {@code budget} of them. */
        void reset(int budget) {
            for (int key : log.olderThan(gen)) {
                if (budget-- <= 0) return;
                int i = ChangeLog.dy(key), x = ChangeLog.x(key), z = ChangeLog.z(key);
                int original = log.state(key);
                int what = Restore.decide(kindOf(original), i, level);
                st[i][z][x] = what == Restore.ORIGINAL ? original : S_LAVA;
                Restore.mark(what, mask, glow, i, x, z);
                log.remove(key);
            }
        }

        /** What the flood's own record must always hold: the glow mask lies inside the lava mask, and nothing is set at or over the lava's height. */
        void checkRecord() {
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (glow.get(i, x, z)) assertTrue(mask.get(i, x, z), "glow bit without its lava bit");
                        if (i >= level) assertFalse(mask.get(i, x, z), "a lava bit over the lava's height");
                    }
                }
            }
        }
    }

    @Test
    void aBandComesBackAsGenerated() {
        Random r = new Random(1234);
        for (int round = 0; round < 60; round++) {
            Band b = new Band();
            int[][][] generated = new int[RISE][16][16];
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int p = r.nextInt(100);
                        int s = p < 55 ? S_AIR : p < 60 ? S_GLOW : p < 62 ? S_LAVA : S_ROCK;
                        generated[i][z][x] = s;
                        b.st[i][z][x] = s;
                    }
                }
            }
            int bumps = 0;
            for (int step = 0; step < 400; step++) {
                int op = r.nextInt(100);
                if (op < 15) {
                    b.bring(r.nextInt(RISE + 1));
                } else if (op < 85) {
                    // players dig, build, pour lava and scoop it up; quakes and eruptions do the same kinds of things
                    int n = 1 + r.nextInt(12);
                    for (int c = 0; c < n; c++) {
                        int i = r.nextInt(RISE), x = r.nextInt(16), z = r.nextInt(16);
                        int q = r.nextInt(100);
                        int next = q < 30 ? S_AIR : q < 55 ? S_PLANK : q < 70 ? S_BRICK : q < 85 ? S_ROCK : q < 95 ? S_LAVA : S_GLOW;
                        b.set(i, x, z, next);
                    }
                } else if (op < 95) {
                    // the lava stands full: the generation goes up and the reset puts back what it can before more changes
                    b.bring(RISE);
                    b.gen++;
                    bumps++;
                    b.reset(r.nextInt(3) == 0 ? r.nextInt(200) : Integer.MAX_VALUE);
                } else {
                    // a reset that catches up at some other height (a chunk nobody was near), or finishes a held-back one
                    b.reset(Integer.MAX_VALUE);
                }
                b.checkRecord();
            }
            // the last reset, then the lava drains to its table: the band is as generated, and the flood holds nothing
            b.bring(RISE);
            b.gen++;
            b.reset(Integer.MAX_VALUE);
            assertTrue(b.log.isEmpty());
            b.checkRecord();
            b.bring(0);
            assertTrue(b.mask.isEmpty(), "round " + round);
            assertTrue(b.glow.isEmpty(), "round " + round);
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(generated[i][z][x], b.st[i][z][x], "round " + round + " (" + bumps + " resets) slice " + i + " x " + x + " z " + z);
                    }
                }
            }
        }
    }

    @Test
    void aResetAtAnyHeightLeavesTheDrainRight() {
        // one block of each kind changed under, at and over the lava's height; the reset runs at every height
        for (int level = 0; level <= RISE; level++) {
            for (int orig : new int[]{S_AIR, S_GLOW, S_LAVA, S_ROCK}) {
                for (int placed : new int[]{S_PLANK, S_AIR, S_LAVA}) {
                    if (placed == orig) continue;
                    Band b = new Band();
                    for (int i = 0; i < RISE; i++) b.st[i][7][7] = orig;
                    b.bring(level);
                    for (int i = 0; i < RISE; i++) b.set(i, 7, 7, placed);
                    b.gen++;
                    b.reset(Integer.MAX_VALUE);
                    b.checkRecord();
                    for (int i = 0; i < RISE; i++) {
                        int want = i < level && takes(kindOf(orig)) ? S_LAVA : orig;
                        assertEquals(want, b.st[i][7][7], "orig " + orig + " placed " + placed + " level " + level + " slice " + i);
                    }
                    b.bring(0);
                    for (int i = 0; i < RISE; i++) assertEquals(orig, b.st[i][7][7], "after the drain: orig " + orig + " placed " + placed + " level " + level);
                }
            }
        }
    }
}
