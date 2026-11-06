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

    /**
     * A chunk's flood band as kinds of block, [slice][z][x], moved the way LavaFlood moves a real one. Each block also has
     * a variant (a fall's flowing lava: its level; fire: its age; rock: which rock), so a block's exact state is
     * {@code kind * 100 + variant}, the id the slice gives the rise to keep.
     */
    private static final class FakeChunk {
        final int[][][] kind = new int[RISE][16][16];
        final int[][][] variant = new int[RISE][16][16];
        final Mask mask = new Mask(RISE);
        /** The blocks of the mask that held the halls' glow (LavaChunk.glow). */
        final Mask glow = new Mask(RISE);
        /** The other blocks the lava took, exactly (LavaChunk.kept). */
        final Kept kept = new Kept(RISE);
        /** False: the rise and drain are called without a Kept record, as callers before 0.8.0 called them. */
        boolean keeps = true;
        int level;
        /** The blocks the drain asked to wake round, as {slice, x, z}. */
        final List<int[]> wakes = new ArrayList<>();

        Slice slice(int i) {
            return new Slice() {
                @Override public int kind(int x, int z) { return kind[i][z][x]; }
                @Override public int state(int x, int z) {
                    assertTrue(takes(kind[i][z][x]) && kind[i][z][x] != AIR && kind[i][z][x] != GLOW, "state asked of " + kind[i][z][x]);
                    return kind[i][z][x] * 100 + variant[i][z][x];
                }
                @Override public void fill(int x, int z) {
                    assertTrue(takes(kind[i][z][x]), "lava put on " + kind[i][z][x]);
                    kind[i][z][x] = LAVA;
                    variant[i][z][x] = 0;
                }
                @Override public void clear(int x, int z) {
                    assertTrue(leaves(kind[i][z][x]), "lava taken from " + kind[i][z][x]);
                    kind[i][z][x] = AIR;
                    variant[i][z][x] = 0;
                }
                @Override public void glow(int x, int z) {
                    assertTrue(leaves(kind[i][z][x]), "glow put back on " + kind[i][z][x]);
                    kind[i][z][x] = GLOW;
                    variant[i][z][x] = 0;
                }
                @Override public void put(int x, int z, int state) {
                    assertTrue(leaves(kind[i][z][x]), "kept block put back on " + kind[i][z][x]);
                    assertTrue(takes(state / 100) && state / 100 != AIR && state / 100 != GLOW, "kept block of kind " + state / 100);
                    kind[i][z][x] = state / 100;
                    variant[i][z][x] = state % 100;
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
                n = keeps ? rise(slice(i), mask, glow, kept, i) : rise(slice(i), mask, glow, i);
                level++;
            } else {
                n = keeps ? drain(slice(i), mask, glow, kept, i) : drain(slice(i), mask, glow, i);
                level--;
            }
            return n;
        }

        int bring(int target) {
            int n = 0;
            while (level != target) n += step(target);
            return n;
        }

        void set(int i, int x, int z, int k, int v) {
            kind[i][z][x] = k;
            variant[i][z][x] = v;
        }

        int[][][] copy() {
            int[][][] c = new int[RISE][16][];
            for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) c[i][z] = kind[i][z].clone();
            return c;
        }

        /** Every block's exact state, kind * 100 + variant. */
        int[][][] states() {
            int[][][] c = new int[RISE][16][16];
            for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) c[i][z][x] = kind[i][z][x] * 100 + variant[i][z][x];
            return c;
        }

        /** What the flood's own record must always hold: glow and kept blocks lie inside the lava mask, none at or over the height. */
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

    /**
     * A chunk of Galleries: mostly rock and air, with the halls' glow along the rock, a little fire, a lava fall's flowing
     * lava and a natural pool.
     */
    private static FakeChunk randomChunk(long seed) {
        Random r = new Random(seed);
        FakeChunk c = new FakeChunk();
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            double p = r.nextDouble();
            int k = p < 0.45 ? OTHER : p < 0.88 ? AIR : p < 0.9 ? GLOW : p < 0.93 ? FIRE : p < 0.97 ? FLOWING : LAVA;
            // a fall's flowing lava has a level (1 to 15), fire an age, rock a kind of rock
            int v = k == FLOWING ? 1 + r.nextInt(15) : k == FIRE ? r.nextInt(16) : k == OTHER ? r.nextInt(5) : 0;
            c.set(i, x, z, k, v);
        }
        return c;
    }

    /**
     * What a block that was {@code was} is after the lava came and went without a Kept record (callers before 0.8.0):
     * fire and a fall's flowing lava are gone.
     */
    private static int afterFloodUnkept(int was) { return was == GLOW ? GLOW : takes(was) ? AIR : was; }

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
            int[][][] before = c.states();
            int filled = 0;
            for (int h = 1; h <= RISE; h++) {
                filled += c.step(h);
                c.checkRecord();
                // under the lava's height nothing is left open; over it nothing has changed
                for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (i < h) assertFalse(takes(c.kind[i][z][x]), "open block under the lava at slice " + i + ", height " + h);
                    else assertEquals(before[i][z][x], c.kind[i][z][x] * 100 + c.variant[i][z][x]);
                }
            }
            // at full the record keeps every fall's flowing lava and fire exactly, and nothing else
            for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int was = before[i][z][x], k = was / 100;
                assertEquals(k == FLOWING || k == FIRE ? was : -1, c.kept.get(i, x, z), "kept at " + i + " " + x + " " + z);
            }
            int drained = 0;
            for (int h = RISE - 1; h >= 0; h--) {
                drained += c.step(h);
                c.checkRecord();
            }
            assertEquals(filled, drained, "the drain takes away what the rise put there");
            assertTrue(c.mask.isEmpty());
            assertTrue(c.glow.isEmpty());
            assertTrue(c.kept.isEmpty());
            // every block is exactly as it was: air, the glow, the rock, the pools, and the falls' flowing lava and fire,
            // level for level (the drain gives a fall back as the terrain made it, never pours it again)
            for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                assertEquals(before[i][z][x], c.kind[i][z][x] * 100 + c.variant[i][z][x], "seed " + seed + " at " + i + " " + x + " " + z);
            }
            assertEquals(drained, c.wakes.size());
        }
        // a caller with no Kept record (before 0.8.0) gets fire and flowing lava back as air
        for (long seed = 1; seed <= 5; seed++) {
            FakeChunk c = randomChunk(seed);
            c.keeps = false;
            int[][][] before = c.copy();
            c.bring(RISE);
            c.bring(0);
            for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                assertEquals(afterFloodUnkept(before[i][z][x]), c.kind[i][z][x]);
            }
        }
    }

    /**
     * A lava fall through the whole band, as FluidFallFeature lays it: a source in the wall, its front one block out
     * (flowing, level 1), the falling column (level 8) down to a sump (a source in the floor), and a spill beside the
     * foot (levels 2, 4, 6) and the glow beside the column. A rise to full and a drain to the table give it back block for
     * block, never as air for a fluid tick to pour again.
     */
    @Test
    void aLavaFallComesBackAsTheTerrainMadeIt() {
        FakeChunk c = new FakeChunk();
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) c.set(i, x, z, AIR, 0);
        int top = RISE - 1;
        c.set(top, 3, 7, LAVA, 0);                         // the source in the wall
        for (int i = 0; i < RISE; i++) c.set(i, 2, 7, OTHER, 1);   // the wall under it
        c.set(top, 3, 7, LAVA, 0);
        c.set(top, 4, 7, FLOWING, 1);                      // the front, flowing out of the wall
        for (int i = 1; i < top; i++) c.set(i, 4, 7, FLOWING, 8);  // the falling column
        c.set(0, 4, 7, LAVA, 0);                           // the sump under its foot
        c.set(0, 5, 7, FLOWING, 2);                        // a spill beside the foot
        c.set(0, 6, 7, FLOWING, 4);
        c.set(0, 7, 7, FLOWING, 6);
        for (int i = 5; i < 30; i += 3) c.set(i, 5, 8, GLOW, 0);   // the glow beside the column
        c.set(12, 9, 9, FIRE, 3);
        int[][][] before = c.states();
        c.bring(RISE);
        c.checkRecord();
        assertEquals(top - 1 + 1 + 3 + 1, c.kept.size(), "the column, the front, the spill and the fire are kept");
        assertEquals(1 * 0 + FLOWING * 100 + 8, c.kept.get(10, 4, 7));
        assertEquals(FLOWING * 100 + 1, c.kept.get(top, 4, 7));
        assertEquals(-1, c.kept.get(0, 4, 7), "the sump is the terrain's own source: not taken, not kept");
        assertEquals(LAVA, c.kind[0][4][7]);
        // while the lava stands full nothing changes; then it drains
        c.bring(0);
        assertTrue(c.kept.isEmpty());
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(before[i][z][x], c.kind[i][z][x] * 100 + c.variant[i][z][x], "at " + i + " " + x + " " + z);
        }
        // a second cycle the same
        c.bring(RISE);
        c.bring(17);
        c.bring(RISE);
        c.bring(0);
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(before[i][z][x], c.kind[i][z][x] * 100 + c.variant[i][z][x], "second cycle at " + i + " " + x + " " + z);
        }
    }

    /** A kept block a player replaced while the lava stood (a block built into the sea) stays the player's; its record goes. */
    @Test
    void aKeptBlockAPlayerBuiltOverStaysThePlayers() {
        FakeChunk c = new FakeChunk();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) c.set(0, x, z, AIR, 0);
        c.set(0, 4, 4, FLOWING, 8);
        c.set(0, 5, 5, FLOWING, 8);
        c.bring(1);
        assertEquals(2, c.kept.count(0));
        c.set(0, 4, 4, OTHER, 3);                          // a block built where the fall's lava was
        c.set(0, 5, 5, AIR, 0);                            // lava scooped up with a bucket
        assertEquals(254, c.step(0));
        assertTrue(c.kept.isEmpty());
        assertEquals(OTHER * 100 + 3, c.kind[0][4][4] * 100 + c.variant[0][4][4]);
        assertEquals(AIR, c.kind[0][5][5], "a place a player emptied stays empty");
    }

    @Test
    void keptBlocksSaveShiftAndClear() {
        Kept k = new Kept(RISE);
        assertTrue(k.isEmpty());
        assertEquals(-1, k.get(0, 0, 0));
        assertEquals(-1, k.remove(0, 0, 0));
        k.put(0, 0, 0, 7);
        k.put(0, 15, 15, 0);
        k.put(17, 3, 9, 4242);
        k.put(RISE - 1, 8, 2, 31);
        k.put(17, 3, 9, 4243);                             // in place of the one kept there
        assertEquals(4, k.size());
        assertEquals(2, k.count(0));
        assertEquals(4243, k.get(17, 3, 9));
        assertEquals(0, k.get(0, 15, 15), "state id 0 is a block like any other");
        assertEquals(-1, k.get(RISE, 0, 0));
        assertEquals(-1, k.get(-1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> k.put(RISE, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> k.put(0, 1, 1, -1));
        int[][] a = k.toArrays();
        Kept back = new Kept(RISE);
        back.put(5, 5, 5, 5);
        back.fromArrays(a[0], a[1]);
        assertEquals(4, back.size(), "what was kept before is replaced");
        assertEquals(-1, back.get(5, 5, 5));
        for (int i = 0; i < RISE; i++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) assertEquals(k.get(i, x, z), back.get(i, x, z));
        // the table moved 3 blocks down: every block moves 3 slices up, and the top one leaves the band
        back.shift(3);
        assertEquals(7, back.get(3, 0, 0));
        assertEquals(4243, back.get(20, 3, 9));
        assertEquals(-1, back.get(0, 0, 0));
        assertEquals(3, back.size());
        back.shift(-23);
        assertTrue(back.isEmpty(), "every block shifted out of the band");
        // a slice cleared at once
        k.clear(0);
        assertEquals(0, k.count(0));
        assertEquals(2, k.size());
        // reading back skips keys outside the band and negative states, and keeps what the shorter array allows
        Kept small = new Kept(4);
        small.fromArrays(new int[]{0, 1 << 8 | 2, 17 << 8, 3 << 8 | 255, -1}, new int[]{9, -1, 9, 11});
        assertEquals(2, small.size());
        assertEquals(9, small.get(0, 0, 0));
        assertEquals(11, small.get(3, 15, 15));
        k.clear();
        assertTrue(k.isEmpty());
    }

    @Test
    void aChunkLeftBehindCatchesUpInOneGo() {
        FakeChunk a = randomChunk(7), b = randomChunk(7);
        int[][][] before = b.states();
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
            assertEquals(a.kept.get(i, x, z), b.kept.get(i, x, z));
            int was = before[i][z][x];
            int sa = a.kind[i][z][x] * 100 + a.variant[i][z][x], sb = b.kind[i][z][x] * 100 + b.variant[i][z][x];
            if (i < 10) {
                assertEquals(sa, sb, "under the lava both are flooded alike");
                assertEquals(takes(was / 100) ? LAVA * 100 : was, sb);
            } else {
                // over it, where the lava went and left again, everything is back exactly
                assertEquals(was, sb);
                assertEquals(was, sa);
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
