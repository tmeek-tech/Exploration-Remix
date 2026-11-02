package com.explorationremix.world.lava;

import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.explorationremix.world.lava.Flood.*;
import static org.junit.jupiter.api.Assertions.*;

/** What the rising and falling lava does to a chunk's blocks, slice by slice, given a fake chunk of block kinds. */
class FloodTest {
    private static final int RISE = Terrain.FLOOD_RISE;

    /** A chunk's flood band as kinds of block, [slice][z][x], moved the way LavaFlood moves a real one. */
    private static final class FakeChunk {
        final int[][][] kind = new int[RISE][16][16];
        final Mask mask = new Mask(RISE);
        /** The blocks of the mask that held the halls' glow (LavaChunk.glow). */
        final Mask glow = new Mask(RISE);
        int level;
        /** The blocks the drain asked to wake round, as {slice, x, z}. */
        final List<int[]> wakes = new ArrayList<>();

        Slice slice(int i) {
            return new Slice() {
                @Override public int kind(int x, int z) { return kind[i][z][x]; }
                @Override public void fill(int x, int z) {
                    assertTrue(takes(kind[i][z][x]), "lava put on " + kind[i][z][x]);
                    kind[i][z][x] = LAVA;
                }
                @Override public void clear(int x, int z) {
                    assertTrue(leaves(kind[i][z][x]), "lava taken from " + kind[i][z][x]);
                    kind[i][z][x] = AIR;
                }
                @Override public void glow(int x, int z) {
                    assertTrue(leaves(kind[i][z][x]), "glow put back on " + kind[i][z][x]);
                    kind[i][z][x] = GLOW;
                }
                @Override public void wake(int x, int z) {
                    // the whole slice is dry by the first wake: none of the sea's lava is left in it to flow out
                    for (int zz = 0; zz < 16; zz++) for (int xx = 0; xx < 16; xx++) assertFalse(mask.get(i, xx, zz));
                    wakes.add(new int[]{i, x, z});
                }
            };
        }

        /** Moves the lava one slice toward {@code target}; returns how many blocks changed. */
        int step(int target) {
            int i = nextSlice(level, target);
            assertTrue(i >= 0 && i < RISE, "slice " + i + " outside the band");
            int n;
            if (level < target) {
                n = rise(slice(i), mask, glow, i);
                level++;
            } else {
                n = drain(slice(i), mask, glow, i);
                level--;
            }
            return n;
        }

        int bring(int target) {
            int n = 0;
            while (level != target) n += step(target);
            return n;
        }

        int[][][] copy() {
            int[][][] c = new int[RISE][16][];
            for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) c[i][z] = kind[i][z].clone();
            return c;
        }
    }

    /**
     * A chunk of Galleries: mostly rock and air, with the halls' glow along the rock, a little fire, a lava fall's flowing
     * lava and a natural pool.
     */
    private static FakeChunk randomChunk(long seed) {
        Random r = new Random(seed);
        FakeChunk c = new FakeChunk();
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            double p = r.nextDouble();
            c.kind[i][z][x] = p < 0.45 ? OTHER : p < 0.88 ? AIR : p < 0.9 ? GLOW : p < 0.93 ? FIRE : p < 0.97 ? FLOWING : LAVA;
        }
        return c;
    }

    /** What a block that was {@code was} is after the lava came and went: fire and a fall's flowing lava are gone. */
    private static int afterFlood(int was) { return was == GLOW ? GLOW : takes(was) ? AIR : was; }

    @Test
    void risingTakesOnlyAirFireAndFlowingLava() {
        FakeChunk c = new FakeChunk();
        int[] row = {OTHER, AIR, FIRE, LAVA, FLOWING};
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) c.kind[0][z][x] = row[(x + z) % row.length];
        int open = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (takes(c.kind[0][z][x])) open++;
        int[][][] before = c.copy();
        assertEquals(open, c.step(1));
        assertEquals(1, c.level);
        assertEquals(open, c.mask.count(0));
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int was = before[0][z][x];
            if (takes(was)) {
                assertEquals(LAVA, c.kind[0][z][x]);
                assertTrue(c.mask.get(0, x, z));
            } else {
                assertEquals(was, c.kind[0][z][x], "rock, water and a natural pool stay as they were");
                assertFalse(c.mask.get(0, x, z));
            }
        }
        // the slices over the lava's height are not touched
        for (int i = 1; i < RISE; i++) assertArrayEquals(before[i], c.kind[i]);
        assertFalse(c.mask.any(1));
    }

    @Test
    void drainingLeavesOnlyWhatTheLavaPutThere() {
        FakeChunk c = new FakeChunk();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) c.kind[0][z][x] = AIR;
        c.kind[0][0][0] = LAVA;                    // a natural pool
        c.kind[0][0][1] = OTHER;                   // rock
        c.bring(1);
        assertEquals(254, c.mask.count(0));
        // while the sea is up, players change some of its blocks
        c.kind[0][5][5] = OTHER;                   // a block built in the lava
        c.kind[0][6][6] = AIR;                     // lava scooped up with a bucket
        c.kind[0][7][7] = FLOWING;                 // a source turned to flowing lava
        assertEquals(252, c.step(0));
        assertEquals(0, c.level);
        assertTrue(c.mask.isEmpty(), "the drained slice has no record left");
        assertEquals(LAVA, c.kind[0][0][0], "the natural pool stays");
        assertEquals(OTHER, c.kind[0][0][1]);
        assertEquals(OTHER, c.kind[0][5][5], "what a player built stays");
        assertEquals(AIR, c.kind[0][6][6]);
        assertEquals(AIR, c.kind[0][7][7]);
        assertEquals(AIR, c.kind[0][15][15]);
        // each block the lava left is woken round, once
        assertEquals(252, c.wakes.size());
        Set<Integer> woken = new HashSet<>();
        for (int[] w : c.wakes) assertTrue(woken.add(w[0] << 8 | w[2] << 4 | w[1]));
        assertFalse(woken.contains(5 << 4 | 5));
        assertFalse(woken.contains(1));
        // a slice with nothing of the lava's costs nothing and wakes nothing
        c.wakes.clear();
        assertEquals(0, drain(c.slice(3), c.mask, 3));
        assertTrue(c.wakes.isEmpty());
    }

    @Test
    void theGlowGoesUnderTheLavaAndComesBack() {
        FakeChunk c = new FakeChunk();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) c.kind[0][z][x] = (x + z) % 4 == 0 ? GLOW : AIR;
        c.kind[0][0][1] = OTHER;                   // rock
        int glows = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (c.kind[0][z][x] == GLOW) glows++;
        assertEquals(255, c.step(1), "the glow is taken with the air");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            boolean was = (x + z) % 4 == 0;
            assertEquals(was, c.glow.get(0, x, z), "only the glow's blocks are marked");
            if (x == 1 && z == 0) assertEquals(OTHER, c.kind[0][z][x]);
            else assertEquals(LAVA, c.kind[0][z][x]);
        }
        assertEquals(glows, c.glow.count(0));
        // while the sea is up, a player builds over one glow's place and scoops the lava from another
        c.kind[0][0][4] = OTHER;
        c.kind[0][4][0] = AIR;
        assertEquals(253, c.step(0));
        assertTrue(c.mask.isEmpty());
        assertTrue(c.glow.isEmpty(), "the drained slice keeps no glow marks");
        assertEquals(OTHER, c.kind[0][0][4], "what a player built stays");
        assertEquals(AIR, c.kind[0][4][0], "a place a player emptied stays empty");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (x == 1 && z == 0 || x == 4 && z == 0 || x == 0 && z == 4) continue;
            assertEquals((x + z) % 4 == 0 ? GLOW : AIR, c.kind[0][z][x], "at " + x + " " + z);
        }
        // without a glow mask (an older caller) the glow's places drain to air
        FakeChunk d = new FakeChunk();
        d.kind[0][3][3] = GLOW;
        assertEquals(1, rise(d.slice(0), d.mask, 0));
        assertEquals(1, drain(d.slice(0), d.mask, 0));
        assertEquals(AIR, d.kind[0][3][3]);
    }

    @Test
    void aFullRiseAndDrainGivesTheChunkBackAsItWas() {
        for (long seed = 1; seed <= 20; seed++) {
            FakeChunk c = randomChunk(seed);
            int[][][] before = c.copy();
            int filled = 0;
            for (int h = 1; h <= RISE; h++) {
                filled += c.step(h);
                // under the lava's height nothing is left open; over it nothing has changed
                for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (i < h) assertFalse(takes(c.kind[i][z][x]), "open block under the lava at slice " + i + ", height " + h);
                    else assertEquals(before[i][z][x], c.kind[i][z][x]);
                }
            }
            int drained = 0;
            for (int h = RISE - 1; h >= 0; h--) drained += c.step(h);
            assertEquals(filled, drained, "the drain takes away what the rise put there");
            assertTrue(c.mask.isEmpty());
            assertTrue(c.glow.isEmpty());
            for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int was = before[i][z][x], now = c.kind[i][z][x];
                // fire burnt out and a fall's flowing lava swallowed come back as air (the fall flows again when woken),
                // and the glow comes back where it was
                assertEquals(afterFlood(was), now, "seed " + seed + " at " + i + " " + x + " " + z);
            }
            assertEquals(drained, c.wakes.size());
        }
    }

    @Test
    void aChunkLeftBehindCatchesUpInOneGo() {
        FakeChunk a = randomChunk(7), b = randomChunk(7);
        int[][][] before = b.copy();
        // one chunk follows every step of a rise that turns back half way; the other, unloaded meanwhile, goes
        // straight to the height when a player comes near again
        for (int h = 1; h <= 25; h++) a.step(h);
        for (int h = 24; h >= 10; h--) a.step(h);
        b.bring(10);
        assertEquals(10, a.level);
        assertEquals(10, b.level);
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(a.mask.get(i, x, z), b.mask.get(i, x, z));
            assertEquals(a.glow.get(i, x, z), b.glow.get(i, x, z));
            int was = before[i][z][x];
            if (i < 10) {
                assertEquals(a.kind[i][z][x], b.kind[i][z][x], "under the lava both are flooded alike");
                assertEquals(takes(was) ? LAVA : was, b.kind[i][z][x]);
            } else {
                // over it, where the lava went and left again, fire is burnt out and flowing lava swallowed
                assertEquals(was, b.kind[i][z][x]);
                assertEquals(i < 25 ? afterFlood(was) : was, a.kind[i][z][x]);
                assertFalse(b.mask.get(i, x, z));
            }
        }
    }

    @Test
    void theNextSliceNeverLeavesTheBand() {
        for (int applied = 0; applied <= RISE; applied++) {
            for (int target = 0; target <= RISE; target++) {
                int i = nextSlice(applied, target);
                if (applied == target) assertEquals(-1, i);
                else assertTrue(i >= 0 && i < RISE, applied + " to " + target + ": slice " + i);
            }
        }
        assertEquals(0, nextSlice(0, 1));
        assertEquals(0, nextSlice(1, 0));
        assertEquals(RISE - 1, nextSlice(RISE, 0));
        Mask m = new Mask(RISE);
        assertThrows(IndexOutOfBoundsException.class, () -> m.set(RISE, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> m.set(-1, 0, 0));
    }

    @Test
    void masksSaveShiftAndStayEmptyUntilUsed() {
        Mask m = new Mask(RISE);
        assertTrue(m.isEmpty());
        assertNull(m.toArray(), "a chunk the lava never reached saves nothing");
        assertFalse(m.get(0, 0, 0));
        m.unset(3, 1, 1);
        m.set(0, 0, 0);
        m.set(0, 15, 15);
        m.set(17, 3, 9);
        m.set(RISE - 1, 8, 2);
        assertEquals(2, m.count(0));
        assertEquals(1, m.count(17));
        assertTrue(m.any(RISE - 1));
        assertFalse(m.any(1));
        long[] saved = m.toArray();
        assertEquals(RISE * 4, saved.length);
        Mask back = new Mask(RISE);
        back.fromArray(saved);
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) assertEquals(m.get(i, x, z), back.get(i, x, z));
        // the table moved 3 blocks down: every bit moves 3 slices up, and the top one leaves the band
        back.shift(3);
        assertTrue(back.get(3, 0, 0));
        assertTrue(back.get(3, 15, 15));
        assertTrue(back.get(20, 3, 9));
        assertFalse(back.get(0, 0, 0));
        assertEquals(3, back.count(3) + back.count(20));
        assertFalse(back.any(RISE - 1));
        back.shift(-23);
        assertTrue(back.isEmpty(), "every bit shifted out of the band");
        assertNull(back.toArray());
        // a record from a band of another height keeps what fits
        Mask small = new Mask(4);
        small.fromArray(saved);
        assertEquals(2, small.count(0));
        assertFalse(small.get(17, 3, 9));
        small.fromArray(new long[RISE * 4]);
        assertTrue(small.isEmpty());
        m.unset(0, 0, 0);
        m.unset(0, 15, 15);
        m.unset(17, 3, 9);
        m.unset(RISE - 1, 8, 2);
        assertTrue(m.isEmpty());
    }

    @Test
    void ringsCoverTheSquareOnceNearestFirst() {
        int radius = 13;
        Set<Long> seen = new HashSet<>();
        int[] off = new int[2];
        for (int r = 0; r <= radius; r++) {
            assertEquals(r == 0 ? 1 : 8 * r, ringSize(r));
            for (int k = 0; k < ringSize(r); k++) {
                ring(r, k, off);
                assertEquals(r, Math.max(Math.abs(off[0]), Math.abs(off[1])), "ring " + r + " chunk " + k);
                assertTrue(seen.add((long) off[0] << 32 | (off[1] & 0xffffffffL)), "visited twice: " + off[0] + " " + off[1]);
            }
        }
        assertEquals((2 * radius + 1) * (2 * radius + 1), seen.size());
    }

    @Test
    void theBandLiesInsideTheGalleries() {
        Shape s = new Shape(TerrainSettings.defaults(), -64);
        int g = Terrain.GALLERIES;
        assertTrue(s.lava(g));
        assertEquals(RISE, Terrain.surge(g));
        assertEquals(0, Terrain.surge(Terrain.WASTELAND));
        int table = s.waterY(g);
        assertEquals(-822, table);
        for (int y = table; y < table + RISE; y++) assertEquals(g, s.layerAt(y), "band y " + y);
        // the lava's top block at full height, -783, is far under the Galleries' roof: it never reaches the Wasteland
        assertTrue(table + RISE - 1 < s.top(g) - 64);
        assertTrue(table + RISE - 1 < s.bottom(Terrain.WASTELAND));
        // players count as near the Galleries a little way up into the Wasteland (its two lowest sheets), as in 0.5.0
        int near = s.top(g) + LavaFlood.NEAR;
        assertEquals(-641, near);
        assertEquals(Terrain.WASTELAND, s.layerAt(near));
        assertTrue(near > s.top(g));
    }
}
