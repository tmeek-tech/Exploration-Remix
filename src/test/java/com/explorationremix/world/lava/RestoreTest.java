package com.explorationremix.world.lava;

import com.explorationremix.world.terrain.Terrain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.explorationremix.world.lava.Flood.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the terrain reset writes where it puts a block back (0.5.1), and a chunk's flood band living through risings,
 * drains, players' changes and resets: after the last reset and a drain it must be exactly as generated, with no lava
 * the drain will never take, no glow lost, and every lava fall's flowing lava and every fire back as it was (0.8.0).
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
                    else assertEquals(k == GLOW ? Restore.SEA_GLOW : k == AIR ? Restore.SEA : Restore.SEA_KEPT, d,
                            "kind " + k + " slice " + slice + " level " + level);
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
    void aFallsLavaOrAFireUnderTheSeaIsKeptForTheDrain() {
        Mask m = new Mask(RISE), g = new Mask(RISE);
        Kept k = new Kept(RISE);
        Restore.mark(Restore.SEA_KEPT, m, g, k, 5, 3, 4, 123);
        assertTrue(m.get(5, 3, 4));
        assertFalse(g.get(5, 3, 4));
        assertEquals(123, k.get(5, 3, 4), "the drain puts the fall's lava back exactly");
        Restore.mark(Restore.SEA_GLOW, m, g, k, 5, 3, 4, 7);
        assertTrue(g.get(5, 3, 4));
        assertEquals(-1, k.get(5, 3, 4), "a glow and a kept block in one place");
        Restore.mark(Restore.SEA_KEPT, m, g, k, 5, 3, 4, 123);
        assertFalse(g.get(5, 3, 4));
        Restore.mark(Restore.SEA, m, g, k, 5, 3, 4, 0);
        assertTrue(m.get(5, 3, 4));
        assertEquals(-1, k.get(5, 3, 4), "a sea block that was air gives back air");
        Restore.mark(Restore.SEA_KEPT, m, g, k, 5, 3, 4, 123);
        Restore.mark(Restore.ORIGINAL, m, g, k, 5, 3, 4, 123);
        assertFalse(m.get(5, 3, 4));
        assertFalse(g.get(5, 3, 4));
        assertTrue(k.isEmpty(), "a block put back as itself has nothing for the drain");
        // outside the band nothing happens
        Restore.mark(Restore.SEA_KEPT, m, g, k, -1, 0, 0, 5);
        Restore.mark(Restore.SEA_KEPT, m, g, k, RISE, 0, 0, 5);
        assertTrue(m.isEmpty());
        assertTrue(k.isEmpty());
        // the six-argument form, which has no kept record, still marks the lava
        Restore.mark(Restore.SEA_KEPT, m, g, 2, 1, 1);
        assertTrue(m.get(2, 1, 1));
        assertFalse(g.get(2, 1, 1));
    }

    @Test
    void theLogSeesThroughTheFlood() {
        assertEquals(AIR, Restore.seeThrough(LAVA, true, false));
        assertEquals(GLOW, Restore.seeThrough(LAVA, true, true));
        assertEquals(LAVA, Restore.seeThrough(LAVA, false, false), "lava the flood did not put there is the terrain's own");
        assertEquals(Restore.KEPT, Restore.seeThrough(LAVA, true, false, true), "the kept block, which the caller reads from the record");
        assertEquals(LAVA, Restore.seeThrough(LAVA, false, false, true), "lava the flood did not put there is the terrain's own");
        for (int k : new int[]{OTHER, AIR, FIRE, FLOWING, GLOW}) {
            assertEquals(k, Restore.seeThrough(k, true, false));
            assertEquals(k, Restore.seeThrough(k, false, false));
            assertEquals(k, Restore.seeThrough(k, true, false, true));
        }
    }

    // ================================================================================================ the band living on

    /**
     * Blocks of the fake band: air, the glow, a lava source, rock, two blocks players place, fire, a lava fall's flowing
     * lava (its front at level 1, its spill at level 2, its falling column), and a magma block a bomb's crater keeps.
     */
    private static final int S_AIR = 0, S_GLOW = 1, S_LAVA = 2, S_ROCK = 3, S_PLANK = 4, S_BRICK = 5, S_FIRE = 6,
            S_FRONT = 7, S_SPILL = 8, S_FALL = 9, S_MAGMA = 10;

    private static int kindOf(int s) {
        return switch (s) {
            case S_AIR -> AIR;
            case S_GLOW -> GLOW;
            case S_LAVA -> LAVA;
            case S_FIRE -> FIRE;
            case S_FRONT, S_SPILL, S_FALL -> FLOWING;
            default -> OTHER;
        };
    }

    /** One chunk's flood band [slice][z][x] with its flood record and change log, changed the way the game changes it. */
    private static final class Band {
        final int[][][] st = new int[RISE][16][16];
        final Mask mask = new Mask(RISE), glow = new Mask(RISE);
        final Kept kept = new Kept(RISE);
        final ChangeLog log = new ChangeLog();
        int level, gen;

        Slice slice(int i) {
            return new Slice() {
                @Override public int kind(int x, int z) { return kindOf(st[i][z][x]); }
                @Override public int state(int x, int z) {
                    int k = kindOf(st[i][z][x]);
                    assertTrue(takes(k) && k != AIR && k != GLOW, "state asked of " + st[i][z][x]);
                    return st[i][z][x];
                }
                @Override public void fill(int x, int z) { st[i][z][x] = S_LAVA; }
                @Override public void clear(int x, int z) { st[i][z][x] = S_AIR; }
                @Override public void glow(int x, int z) { st[i][z][x] = S_GLOW; }
                @Override public void put(int x, int z, int state) { st[i][z][x] = state; }
                @Override public void wake(int x, int z) {}
            };
        }

        void bring(int target) {
            while (level != target) {
                int i = nextSlice(level, target);
                if (level < target) {
                    rise(slice(i), mask, glow, kept, i);
                    level++;
                } else {
                    drain(slice(i), mask, glow, kept, i);
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
                int k = Restore.seeThrough(kindOf(cur), mask.get(i, x, z), glow.get(i, x, z), kept.get(i, x, z) >= 0);
                int first = k == AIR && kindOf(cur) != AIR ? S_AIR : k == GLOW && kindOf(cur) != GLOW ? S_GLOW
                        : k == Restore.KEPT ? kept.get(i, x, z) : cur;
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
                Restore.mark(what, mask, glow, kept, i, x, z, original);
                log.remove(key);
            }
        }

        /**
         * A quake gives the block at (i, x, z) back at once, whatever its generation ({@code GalleryChanges.giveBack},
         * 0.9.0): through the reset's own put-back of one entry ({@code TerrainReset.putBack}), whose records' side is
         * {@link Restore#putBack}. Nothing happens when the log does not hold the position. Returns true when it did.
         */
        boolean giveBack(int i, int x, int z) {
            int key = ChangeLog.key(x, i, z);
            if (!log.has(key)) return false;
            int original = log.state(key);
            int what = Restore.putBack(log, key, kindOf(original), original, i, level, mask, glow, kept);
            st[i][z][x] = what == Restore.ORIGINAL ? original : S_LAVA;
            return true;
        }

        /**
         * A quake topples the placed block at (i, x, z) (0.9.0, {@code world.quake.Topple}): it goes to air where it
         * stood (never its own fluid), and lands at (qi, qx, qz), an open cell (air, the glow, the sea's or a pool's lava
         * it sinks into), or is taken out ({@code qi} -1). Both are block changes the game records like any other.
         */
        void topple(int i, int x, int z, int qi, int qx, int qz) {
            int block = st[i][z][x];
            set(i, x, z, S_AIR);
            if (qi >= 0) set(qi, qx, qz, block);
        }

        /** True for a cell a falling block can come to rest in: air, the glow, or a lava source (the sea's or a pool's). */
        boolean landable(int i, int x, int z) {
            int k = kindOf(st[i][z][x]);
            return k == AIR || k == GLOW || k == LAVA;
        }

        /**
         * A ceiling block comes loose in a big quake (0.9.0, {@code world.quake.Debris}): the natural rock at (i, x, z)
         * goes to air where it hung (the falling block's {@code fall}) and lands at (qi, qx, qz), an open cell or a lava
         * source it sinks into (the sea's, a pool's), or vanishes ({@code qi} -1: it could not land, or broke on
         * someone). Both are block changes the game records like any other.
         */
        void debris(int i, int x, int z, int qi, int qx, int qz) {
            set(i, x, z, S_AIR);
            if (qi >= 0) set(qi, qx, qz, S_ROCK);
        }

        /**
         * A lava bomb bursts at (ci, cx, cz) (0.9.0, {@code world.eruption.Blast}, its partition {@code Crater}): within
         * {@code r} of it, the natural blocks (rock, the glow) and held rock go to air, but none with a fluid next to it
         * (the sea's lava, a pool, a fall's flowing lava); the lowest of the middle column to magma instead when
         * {@code magma}; players' other blocks are broken the game's way (to air); then up to 3 of the removed rocks are
         * thrown out and land on the open cells {@code lands} gives ({i, x, z}, or i -1: it vanished), as ceiling debris
         * does. All block changes the game records like any other. Returns how many blocks the crater took.
         */
        int crater(int ci, int cx, int cz, int r, boolean magma, List<int[]> lands) {
            List<int[]> taken = new ArrayList<>();
            for (int i = Math.max(0, ci - r); i <= Math.min(RISE - 1, ci + r); i++) {
                for (int x = Math.max(0, cx - r); x <= Math.min(15, cx + r); x++) {
                    for (int z = Math.max(0, cz - r); z <= Math.min(15, cz + r); z++) {
                        if ((i - ci) * (i - ci) + (x - cx) * (x - cx) + (z - cz) * (z - cz) > r * r) continue;
                        int s = st[i][z][x];
                        boolean held = log.has(ChangeLog.key(x, i, z));
                        boolean rock = s == S_ROCK || s == S_MAGMA;
                        if (s == S_PLANK || s == S_BRICK || held && (s == S_GLOW || s == S_FIRE)) {
                            // a player's block (or a held glow or fire): the game's blast breaks it
                            set(i, x, z, S_AIR);
                        } else if ((rock || s == S_GLOW || s == S_FIRE) && !fluidNear(i, x, z)) {
                            taken.add(new int[]{i, x, z});
                        }
                    }
                }
            }
            int low = -1;
            if (magma) {
                for (int k = 0; k < taken.size(); k++) {
                    int[] t = taken.get(k);
                    if (t[1] != cx || t[2] != cz) continue;
                    if (low < 0 || t[0] < taken.get(low)[0]) low = k;
                }
                if (low >= 0 && (taken.get(low)[0] == 0 || st[taken.get(low)[0] - 1][cz][cx] != S_ROCK)) low = -1;
            }
            int rocks = 0;
            for (int k = 0; k < taken.size(); k++) {
                int[] t = taken.get(k);
                if (st[t[0]][t[2]][t[1]] == S_ROCK && k != low) rocks++;
                set(t[0], t[1], t[2], k == low ? S_MAGMA : S_AIR);
            }
            for (int k = 0; k < Math.min(3, Math.min(rocks, lands.size())); k++) {
                int[] q = lands.get(k);
                if (q[0] >= 0 && landable(q[0], q[1], q[2])) set(q[0], q[1], q[2], S_ROCK);
            }
            return taken.size();
        }

        /** True when a fluid (a lava source, the sea, a fall's flowing lava) is next to (i, x, z) within the band. */
        boolean fluidNear(int i, int x, int z) {
            int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] o : d) {
                int ni = i + o[0], nx = x + o[1], nz = z + o[2];
                if (ni < 0 || ni >= RISE || nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                int k = kindOf(st[ni][nz][nx]);
                if (k == LAVA || k == FLOWING) return true;
            }
            return false;
        }

        /** True for natural rock, which ceiling debris comes from: rock the log holds nothing for. */
        boolean natural(int i, int x, int z) { return st[i][z][x] == S_ROCK && !log.has(ChangeLog.key(x, i, z)); }

        /** True for a block a quake topples: held in the log with an open first block, and solid now. */
        boolean placed(int i, int x, int z) {
            int key = ChangeLog.key(x, i, z);
            if (!log.has(key)) return false;
            int first = kindOf(log.state(key)), cur = st[i][z][x];
            return first != OTHER && (cur == S_PLANK || cur == S_BRICK || cur == S_ROCK);
        }

        /**
         * What the flood's own record must always hold: the glow mask and the kept blocks lie inside the lava mask, never
         * both in one place, and nothing is set at or over the lava's height.
         */
        void checkRecord() {
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (glow.get(i, x, z)) assertTrue(mask.get(i, x, z), "glow bit without its lava bit");
                        if (kept.get(i, x, z) >= 0) assertTrue(mask.get(i, x, z), "kept block without its lava bit");
                        if (glow.get(i, x, z)) assertEquals(-1, kept.get(i, x, z), "a glow and a kept block in one place");
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
                        int s = p < 50 ? S_AIR : p < 55 ? S_GLOW : p < 57 ? S_LAVA : p < 58 ? S_FIRE : p < 59 ? S_FRONT
                                : p < 61 ? S_SPILL : p < 63 ? S_FALL : S_ROCK;
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
                        int next = q < 30 ? S_AIR : q < 50 ? S_PLANK : q < 65 ? S_BRICK : q < 80 ? S_ROCK : q < 88 ? S_LAVA
                                : q < 93 ? S_SPILL : q < 96 ? S_FIRE : S_GLOW;
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
            assertTrue(b.kept.isEmpty(), "round " + round);
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
            for (int orig : new int[]{S_AIR, S_GLOW, S_LAVA, S_ROCK, S_FIRE, S_FRONT, S_FALL}) {
                for (int placed : new int[]{S_PLANK, S_AIR, S_LAVA, S_SPILL}) {
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
                    assertTrue(b.mask.isEmpty() && b.glow.isEmpty() && b.kept.isEmpty());
                }
            }
        }
    }
    // ================================================================================================ the give-back (0.9.0)

    @Test
    void thePutBackOfOneEntryIsTheResetsOwn() {
        // Restore.putBack, which TerrainReset.putBack (the reset's pass and the quakes' give-back) runs, decides and marks
        // exactly as the reset's loop did before it was moved out of it, and drops the entry
        int[] kinds = {OTHER, AIR, FIRE, LAVA, FLOWING, GLOW};
        for (int level = 0; level <= RISE; level += 5) {
            for (int slice = -1; slice <= RISE; slice += 3) {
                for (int k : kinds) {
                    Mask m = new Mask(RISE), g = new Mask(RISE), m2 = new Mask(RISE), g2 = new Mask(RISE);
                    Kept kp = new Kept(RISE), kp2 = new Kept(RISE);
                    int i = Math.min(slice, RISE - 1);
                    if (i >= 0) {
                        // records left by an earlier rise, which the put-back must set or clear as mark does
                        m.set(i, 3, 4);
                        g.set(i, 3, 4);
                        m2.set(i, 3, 4);
                        g2.set(i, 3, 4);
                    }
                    ChangeLog log = new ChangeLog();
                    int key = ChangeLog.key(3, Math.max(0, slice), 4);
                    log.record(key, 77, 5);
                    int d = Restore.putBack(log, key, k, 77, slice, level, m, g, kp);
                    int want = Restore.decide(k, slice, level);
                    Restore.mark(want, m2, g2, kp2, slice, 3, 4, 77);
                    String at = "kind " + k + " slice " + slice + " level " + level;
                    assertEquals(want, d, at);
                    assertFalse(log.has(key), at);
                    for (int j = 0; j < RISE; j++) {
                        assertEquals(m2.get(j, 3, 4), m.get(j, 3, 4), at);
                        assertEquals(g2.get(j, 3, 4), g.get(j, 3, 4), at);
                        assertEquals(kp2.get(j, 3, 4), kp.get(j, 3, 4), at);
                    }
                }
            }
        }
    }

    @Test
    void aBlockGivenBackUnderTheSeaIsTheSeaAgain() {
        // a rider's tower in the risen sea: its blocks come back as the sea with their mask bits, and the drain then
        // leaves the air and the glow that were there; entries of this generation go too (the give-back takes any)
        Band b = new Band();
        b.st[3][5][5] = S_GLOW;
        b.bring(20);
        for (int i = 0; i < 25; i++) b.set(i, 5, 5, S_PLANK);
        for (int i = 0; i < 20; i++) {
            assertTrue(b.giveBack(i, 5, 5));
            assertEquals(S_LAVA, b.st[i][5][5], "slice " + i);
            assertTrue(b.mask.get(i, 5, 5), "slice " + i + ": the sea's bit, so the drain takes it");
            assertEquals(i == 3, b.glow.get(i, 5, 5), "slice " + i);
        }
        assertFalse(b.giveBack(0, 5, 5), "nothing left to give back there");
        b.checkRecord();
        // over the sea the planks are still the player's until the reset (or a quake's fall) takes them
        for (int i = 20; i < 25; i++) assertEquals(S_PLANK, b.st[i][5][5]);
        for (int i = 20; i < 25; i++) b.set(i, 5, 5, S_AIR);
        assertTrue(b.log.isEmpty());
        b.bring(0);
        for (int i = 0; i < RISE; i++) assertEquals(i == 3 ? S_GLOW : S_AIR, b.st[i][5][5], "after the drain, slice " + i);
        assertTrue(b.mask.isEmpty() && b.glow.isEmpty() && b.kept.isEmpty());
    }

    @Test
    void aBlockGivenBackInAPoolIsThePoolsLava() {
        // a block put into a natural pool's source (above the sea, and under it once the sea has risen past it) comes back
        // as the pool's own lava, with no flood record: the drain leaves it alone
        for (int level : new int[]{0, 10, 35, RISE}) {
            Band b = new Band();
            b.st[30][2][9] = S_LAVA;
            b.bring(level);
            b.set(30, 9, 2, S_BRICK);
            assertEquals(S_LAVA, b.log.state(ChangeLog.key(9, 30, 2)), "the log holds the pool's lava, level " + level);
            assertTrue(b.giveBack(30, 9, 2));
            assertEquals(S_LAVA, b.st[30][2][9], "level " + level);
            assertFalse(b.mask.get(30, 9, 2), "level " + level);
            b.checkRecord();
            b.bring(0);
            assertEquals(S_LAVA, b.st[30][2][9], "the pool after the drain, level " + level);
            assertTrue(b.mask.isEmpty() && b.log.isEmpty());
        }
    }

    @Test
    void aBlockGivenBackInTheLakesRowsIsTheTablesLava() {
        // a bridge laid into the lake's top row (under the table, outside the flood band): its block comes back as the
        // lake's lava source at every height of the sea, and no flood record is touched
        for (int level = 0; level <= RISE; level += 10) {
            Mask m = new Mask(RISE), g = new Mask(RISE);
            Kept k = new Kept(RISE);
            ChangeLog log = new ChangeLog();
            int key = ChangeLog.key(6, 5, 7);
            log.record(key, S_LAVA, 0);
            assertEquals(Restore.ORIGINAL, Restore.putBack(log, key, LAVA, S_LAVA, -1, level, m, g, k), "level " + level);
            assertTrue(log.isEmpty());
            assertTrue(m.isEmpty() && g.isEmpty() && k.isEmpty());
        }
    }

    @Test
    void aToppledBlockGoesBackWhereverItLands() {
        // a plank placed in the air (and one in a glow spot) topples onto the floor, into a glow, into the sea and into a
        // pool's lava; after the reset at full lava and the drain the band is as generated, the flood holding nothing
        for (int level : new int[]{0, 12, RISE}) {
            for (int where = 0; where < 5; where++) {
                Band b = new Band();
                b.st[15][4][4] = S_GLOW;
                b.st[2][6][6] = S_GLOW;
                b.st[3][7][7] = S_LAVA;
                int[][][] generated = copy(b.st);
                b.bring(level);
                b.set(15, 4, 4, S_PLANK);
                b.set(16, 4, 4, S_BRICK);
                int[] q = switch (where) {
                    case 0 -> new int[]{30, 9, 9};      // the floor, over the sea (or in it at full)
                    case 1 -> new int[]{2, 6, 6};       // a glow (in the sea once it has risen)
                    case 2 -> new int[]{5, 1, 1};       // the sea, or the air at calm
                    case 3 -> new int[]{3, 7, 7};       // a pool's lava
                    default -> new int[]{-1, 0, 0};     // taken out
                };
                assertTrue(q[0] < 0 || b.landable(q[0], q[1], q[2]));
                assertTrue(b.placed(16, 4, 4));
                b.topple(16, 4, 4, q[0], q[1], q[2]);
                b.topple(15, 4, 4, q[0] < 0 ? -1 : q[0] + 1, q[1], q[2]);
                assertEquals(S_GLOW, b.log.state(ChangeLog.key(4, 15, 4)), "the glow spot's entry stays: the glow comes back");
                b.checkRecord();
                b.bring(RISE);
                b.gen++;
                b.reset(Integer.MAX_VALUE);
                assertTrue(b.log.isEmpty(), "level " + level + " where " + where);
                b.bring(0);
                assertTrue(b.mask.isEmpty() && b.glow.isEmpty() && b.kept.isEmpty(), "level " + level + " where " + where);
                for (int i = 0; i < RISE; i++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            assertEquals(generated[i][z][x], b.st[i][z][x], "level " + level + " where " + where + " slice " + i + " x " + x + " z " + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aCeilingBlockGoesBackWhereverItLands() {
        // a block of an overhang comes loose and lands on the floor, in a glow, in the sea, in a pool's lava, or vanishes;
        // after the reset at full lava and the drain the band is as generated: the hole filled, the landed block gone
        for (int level : new int[]{0, 12, RISE}) {
            for (int where = 0; where < 5; where++) {
                Band b = new Band();
                b.st[33][8][8] = S_ROCK;
                b.st[2][6][6] = S_GLOW;
                b.st[3][7][7] = S_LAVA;
                int[][][] generated = copy(b.st);
                b.bring(level);
                int[] q = switch (where) {
                    case 0 -> new int[]{30, 8, 8};      // the floor under it, over the sea (or in it at full)
                    case 1 -> new int[]{2, 6, 6};       // a glow (in the sea once it has risen)
                    case 2 -> new int[]{5, 1, 1};       // the sea, or the air at calm
                    case 3 -> new int[]{3, 7, 7};       // a pool's lava
                    default -> new int[]{-1, 0, 0};     // it vanished
                };
                assertTrue(b.natural(33, 8, 8));
                assertTrue(q[0] < 0 || b.landable(q[0], q[1], q[2]));
                b.debris(33, 8, 8, q[0], q[1], q[2]);
                assertEquals(S_ROCK, b.log.state(ChangeLog.key(8, 33, 8)), "the hole's entry holds the rock");
                b.checkRecord();
                b.bring(RISE);
                b.gen++;
                b.reset(Integer.MAX_VALUE);
                assertTrue(b.log.isEmpty(), "level " + level + " where " + where);
                b.bring(0);
                assertTrue(b.mask.isEmpty() && b.glow.isEmpty() && b.kept.isEmpty(), "level " + level + " where " + where);
                for (int i = 0; i < RISE; i++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            assertEquals(generated[i][z][x], b.st[i][z][x], "level " + level + " where " + where + " slice " + i + " x " + x + " z " + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aCeilingBlockInTheLakeGoesBackAsTheTablesLava() {
        // a ceiling block that sinks into the lake (the table's lava, under the flood band) replaces a source there: the
        // log holds that lava, and the reset puts it back raw at every height of the sea, touching no flood record
        for (int level = 0; level <= RISE; level += 5) {
            Mask m = new Mask(RISE), g = new Mask(RISE);
            Kept k = new Kept(RISE);
            ChangeLog log = new ChangeLog();
            int key = ChangeLog.key(4, 2, 9);
            log.record(key, S_LAVA, 0);
            assertEquals(Restore.ORIGINAL, Restore.decide(LAVA, -1, level), "level " + level);
            assertEquals(Restore.ORIGINAL, Restore.putBack(log, key, LAVA, S_LAVA, -1, level, m, g, k), "level " + level);
            assertTrue(log.isEmpty());
            assertTrue(m.isEmpty() && g.isEmpty() && k.isEmpty());
        }
    }

    private static int[][][] copy(int[][][] a) {
        int[][][] out = new int[a.length][][];
        for (int i = 0; i < a.length; i++) {
            out[i] = new int[a[i].length][];
            for (int z = 0; z < a[i].length; z++) out[i][z] = a[i][z].clone();
        }
        return out;
    }

    @Test
    void aBandWithQuakesComesBackAsGenerated() {
        // as aBandComesBackAsGenerated, with quakes toppling placed blocks (onto open cells, into the sea or a pool, or
        // taken out) and giving them back, at any time and whatever their generation, and ceiling rock coming loose
        Random r = new Random(9090);
        for (int round = 0; round < 40; round++) {
            Band b = new Band();
            int[][][] generated = new int[RISE][16][16];
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int p = r.nextInt(100);
                        int s = p < 50 ? S_AIR : p < 55 ? S_GLOW : p < 57 ? S_LAVA : p < 58 ? S_FIRE : p < 59 ? S_FRONT
                                : p < 61 ? S_SPILL : p < 63 ? S_FALL : S_ROCK;
                        generated[i][z][x] = s;
                        b.st[i][z][x] = s;
                    }
                }
            }
            int toppled = 0, given = 0, fell = 0;
            for (int step = 0; step < 400; step++) {
                int op = r.nextInt(100);
                if (op < 15) {
                    b.bring(r.nextInt(RISE + 1));
                } else if (op < 52) {
                    int n = 1 + r.nextInt(12);
                    for (int c = 0; c < n; c++) {
                        int q = r.nextInt(100);
                        int next = q < 30 ? S_AIR : q < 50 ? S_PLANK : q < 65 ? S_BRICK : q < 80 ? S_ROCK : q < 88 ? S_LAVA
                                : q < 93 ? S_SPILL : q < 96 ? S_FIRE : S_GLOW;
                        b.set(r.nextInt(RISE), r.nextInt(16), r.nextInt(16), next);
                    }
                } else if (op < 60) {
                    // a big quake: natural rock with open space under it comes loose and lands on an open cell (into the
                    // sea or a pool too), or vanishes
                    for (int c = 0; c < 8; c++) {
                        int i = 1 + r.nextInt(RISE - 1), x = r.nextInt(16), z = r.nextInt(16);
                        if (!b.natural(i, x, z) || !b.landable(i - 1, x, z) || kindOf(b.st[i - 1][z][x]) == LAVA) continue;
                        int qi = r.nextInt(i), qx = r.nextInt(16), qz = r.nextInt(16);
                        boolean gone = r.nextInt(5) == 0 || !b.landable(qi, qx, qz);
                        b.debris(i, x, z, gone ? -1 : qi, qx, qz);
                        fell++;
                    }
                } else if (op < 80) {
                    // a tremor: placed blocks topple, landing on open cells (into the sea or a pool too) or taken out
                    int[][] e = b.log.toArrays();
                    for (int k = 0; k < e[0].length; k++) {
                        int key = e[0][k], i = ChangeLog.dy(key), x = ChangeLog.x(key), z = ChangeLog.z(key);
                        if (!b.placed(i, x, z) || r.nextInt(3) != 0) continue;
                        int qi = r.nextInt(RISE), qx = r.nextInt(16), qz = r.nextInt(16);
                        boolean out = r.nextInt(4) == 0 || !b.landable(qi, qx, qz) || qi == i && qx == x && qz == z;
                        b.topple(i, x, z, out ? -1 : qi, qx, qz);
                        toppled++;
                    }
                } else if (op < 88) {
                    // under the lava (and an old entry with the reset on) a placed block is given back at once instead
                    int[][] e = b.log.toArrays();
                    for (int k = 0; k < e[0].length; k++) {
                        int key = e[0][k], i = ChangeLog.dy(key);
                        if ((i < b.level || r.nextInt(4) == 0) && b.giveBack(i, ChangeLog.x(key), ChangeLog.z(key))) given++;
                    }
                } else if (op < 96) {
                    b.bring(RISE);
                    b.gen++;
                    b.reset(r.nextInt(3) == 0 ? r.nextInt(200) : Integer.MAX_VALUE);
                } else {
                    b.reset(Integer.MAX_VALUE);
                }
                b.checkRecord();
            }
            b.bring(RISE);
            b.gen++;
            b.reset(Integer.MAX_VALUE);
            assertTrue(b.log.isEmpty());
            b.checkRecord();
            b.bring(0);
            assertTrue(b.mask.isEmpty() && b.glow.isEmpty() && b.kept.isEmpty(), "round " + round);
            assertTrue(toppled > 0 && given > 0 && fell > 0, "round " + round);
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(generated[i][z][x], b.st[i][z][x], "round " + round + " slice " + i + " x " + x + " z " + z);
                    }
                }
            }
        }
    }

    @Test
    void aBandWithGiveBacksComesBackAsGenerated() {
        // as aBandComesBackAsGenerated, with quakes giving placed blocks back at any time, whatever their generation
        Random r = new Random(4321);
        for (int round = 0; round < 40; round++) {
            Band b = new Band();
            int[][][] generated = new int[RISE][16][16];
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int p = r.nextInt(100);
                        int s = p < 50 ? S_AIR : p < 55 ? S_GLOW : p < 57 ? S_LAVA : p < 58 ? S_FIRE : p < 59 ? S_FRONT
                                : p < 61 ? S_SPILL : p < 63 ? S_FALL : S_ROCK;
                        generated[i][z][x] = s;
                        b.st[i][z][x] = s;
                    }
                }
            }
            int given = 0;
            for (int step = 0; step < 400; step++) {
                int op = r.nextInt(100);
                if (op < 15) {
                    b.bring(r.nextInt(RISE + 1));
                } else if (op < 70) {
                    int n = 1 + r.nextInt(12);
                    for (int c = 0; c < n; c++) {
                        int q = r.nextInt(100);
                        int next = q < 30 ? S_AIR : q < 50 ? S_PLANK : q < 65 ? S_BRICK : q < 80 ? S_ROCK : q < 88 ? S_LAVA
                                : q < 93 ? S_SPILL : q < 96 ? S_FIRE : S_GLOW;
                        b.set(r.nextInt(RISE), r.nextInt(16), r.nextInt(16), next);
                    }
                } else if (op < 85) {
                    // a tremor: placed blocks under the sea (and some others) are given back at once
                    int[][] e = b.log.toArrays();
                    for (int k = 0; k < e[0].length; k++) {
                        int key = e[0][k], i = ChangeLog.dy(key);
                        if ((i < b.level || r.nextInt(4) == 0) && b.giveBack(i, ChangeLog.x(key), ChangeLog.z(key))) given++;
                    }
                } else if (op < 95) {
                    b.bring(RISE);
                    b.gen++;
                    b.reset(r.nextInt(3) == 0 ? r.nextInt(200) : Integer.MAX_VALUE);
                } else {
                    b.reset(Integer.MAX_VALUE);
                }
                b.checkRecord();
            }
            b.bring(RISE);
            b.gen++;
            b.reset(Integer.MAX_VALUE);
            assertTrue(b.log.isEmpty());
            b.checkRecord();
            b.bring(0);
            assertTrue(b.mask.isEmpty() && b.glow.isEmpty() && b.kept.isEmpty(), "round " + round);
            assertTrue(given > 0);
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(generated[i][z][x], b.st[i][z][x], "round " + round + " slice " + i + " x " + x + " z " + z);
                    }
                }
            }
        }
    }

    @Test
    void aBandWithCratersComesBackAsGenerated() {
        // as aBandComesBackAsGenerated, with lava bombs blowing craters above the sea (natural rock, held rock and the
        // glow taken, players' blocks broken, magma at the bottom, ejecta landing round about) at any time
        Random r = new Random(5150);
        int magmas = 0;
        for (int round = 0; round < 40; round++) {
            Band b = new Band();
            int[][][] generated = new int[RISE][16][16];
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int p = r.nextInt(100);
                        int s = p < 40 ? S_AIR : p < 45 ? S_GLOW : p < 47 ? S_LAVA : p < 48 ? S_FIRE : p < 49 ? S_FRONT
                                : p < 51 ? S_SPILL : p < 53 ? S_FALL : S_ROCK;
                        generated[i][z][x] = s;
                        b.st[i][z][x] = s;
                    }
                }
            }
            int taken = 0;
            for (int step = 0; step < 400; step++) {
                int op = r.nextInt(100);
                if (op < 15) {
                    b.bring(r.nextInt(RISE + 1));
                } else if (op < 55) {
                    int n = 1 + r.nextInt(12);
                    for (int c = 0; c < n; c++) {
                        int q = r.nextInt(100);
                        int next = q < 30 ? S_AIR : q < 50 ? S_PLANK : q < 65 ? S_BRICK : q < 80 ? S_ROCK : q < 88 ? S_LAVA
                                : q < 93 ? S_SPILL : q < 96 ? S_FIRE : S_GLOW;
                        b.set(r.nextInt(RISE), r.nextInt(16), r.nextInt(16), next);
                    }
                } else if (op < 75) {
                    // a bomb bursts above the sea: its crater, and its ejecta landing on open cells (into the sea too)
                    if (b.level >= RISE) continue;
                    int ci = b.level + r.nextInt(RISE - b.level), cx = r.nextInt(16), cz = r.nextInt(16);
                    List<int[]> lands = new ArrayList<>();
                    for (int k = 0; k < 3; k++) lands.add(r.nextInt(5) == 0 ? new int[]{-1, 0, 0} : new int[]{r.nextInt(RISE), r.nextInt(16), r.nextInt(16)});
                    boolean magma = r.nextBoolean();
                    int[][][] before = copy(b.st);
                    taken += b.crater(ci, cx, cz, 1 + r.nextInt(2), magma, lands);
                    for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        if (b.st[i][z][x] == S_MAGMA && before[i][z][x] != S_MAGMA) magmas++;
                    }
                } else if (op < 95) {
                    b.bring(RISE);
                    b.gen++;
                    b.reset(r.nextInt(3) == 0 ? r.nextInt(200) : Integer.MAX_VALUE);
                } else {
                    b.reset(Integer.MAX_VALUE);
                }
                b.checkRecord();
            }
            b.bring(RISE);
            b.gen++;
            b.reset(Integer.MAX_VALUE);
            assertTrue(b.log.isEmpty());
            b.checkRecord();
            b.bring(0);
            assertTrue(b.mask.isEmpty() && b.glow.isEmpty() && b.kept.isEmpty(), "round " + round);
            assertTrue(taken > 0, "round " + round);
            for (int i = 0; i < RISE; i++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(generated[i][z][x], b.st[i][z][x], "round " + round + " slice " + i + " x " + x + " z " + z);
                    }
                }
            }
        }
        assertTrue(magmas > 0, "no crater kept its magma");
    }

    @Test
    void aCraterGoesBackWithItsMagma() {
        // a floor of rock with the glow over it; a bomb's crater takes rock and glow and keeps magma at its bottom; the
        // reset puts the rock back where the magma stood and the glow where it was
        Band b = new Band();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int i = 20; i <= 26; i++) b.st[i][z][x] = S_ROCK;
        for (int z = 6; z <= 10; z++) for (int x = 6; x <= 10; x++) b.st[27][z][x] = S_GLOW;
        int[][][] generated = copy(b.st);
        int taken = b.crater(26, 8, 8, 2, true, List.of(new int[]{27, 3, 3}, new int[]{27, 12, 12}, new int[]{-1, 0, 0}));
        assertTrue(taken >= 20, "taken " + taken);
        assertEquals(S_MAGMA, b.st[24][8][8], "magma at the bottom of the middle column");
        assertEquals(S_AIR, b.st[27][8][8], "the glow cleared");
        assertEquals(S_ROCK, b.st[27][3][3], "an ejected rock landed");
        assertEquals(S_ROCK, b.log.state(ChangeLog.key(8, 24, 8)), "the magma's entry holds the rock");
        b.bring(RISE);
        b.gen++;
        b.reset(Integer.MAX_VALUE);
        assertTrue(b.log.isEmpty());
        b.bring(0);
        assertTrue(b.mask.isEmpty() && b.glow.isEmpty() && b.kept.isEmpty());
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(generated[i][z][x], b.st[i][z][x], "slice " + i + " x " + x + " z " + z);
        }
        // a crater beside the sea's lava, or a pool, leaves the rock that holds it back
        Band w = new Band();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int i = 0; i <= 26; i++) w.st[i][z][x] = S_ROCK;
        w.st[26][8][9] = S_LAVA;
        w.crater(26, 8, 8, 2, false, List.of());
        assertEquals(S_ROCK, w.st[26][8][8], "the pool's wall stays");
        assertEquals(S_ROCK, w.st[25][8][9], "the pool's bed stays");
        assertEquals(S_LAVA, w.st[26][8][9]);
        assertEquals(S_AIR, w.st[26][6][8], "the rest of the crater is taken");
    }

    @Test
    void changesAfterTheBumpWaitForTheNextReset() {
        // the lava stands full and the generation goes up (the bump): what changes after it (an ejected rock sinking into
        // the risen sea at full, a crater as the lava drains) has the new generation, so that cycle's reset leaves it,
        // and the next cycle's puts it back (spec 3.4)
        Band b = new Band();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int i = 20; i <= 26; i++) b.st[i][z][x] = S_ROCK;
        int[][][] generated = copy(b.st);
        b.bring(RISE);
        b.gen++;
        b.reset(Integer.MAX_VALUE);
        // at full: a rock lands in the sea over the floor
        b.set(30, 4, 4, S_ROCK);
        // the drain begins; a bomb bursts on the floor over the sea
        b.bring(20);
        int taken = b.crater(26, 8, 8, 2, true, List.of());
        assertTrue(taken > 0);
        b.checkRecord();
        // that cycle's reset catches up: nothing it may put back
        b.reset(Integer.MAX_VALUE);
        assertFalse(b.log.isEmpty(), "the changes after the bump wait");
        assertEquals(S_AIR, b.st[26][8][8], "the crater stays open until the next full");
        assertEquals(S_MAGMA, b.st[24][8][8]);
        b.checkRecord();
        // the next cycle: calm, the rise, full, the bump, the reset
        b.bring(0);
        b.bring(RISE);
        b.gen++;
        b.reset(Integer.MAX_VALUE);
        assertTrue(b.log.isEmpty());
        b.bring(0);
        assertTrue(b.mask.isEmpty() && b.glow.isEmpty() && b.kept.isEmpty());
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(generated[i][z][x], b.st[i][z][x], "slice " + i + " x " + x + " z " + z);
        }
    }
}
