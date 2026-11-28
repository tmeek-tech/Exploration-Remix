package com.explorationremix.world.feature;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Ivory Root Caverns' moss patches (0.24.4, Taran, 2026-11-28: "replace about half of the moss in Ivory Root Caverns
 * with limestone"): half of the patches' ground bare (limestone) and half moss, the patches together as big as vanilla's
 * square patches were, the moss and limestone in pieces a few blocks across, and the same floors for the same seed.
 */
class SplitPatchTest {
    private static final long SEED = 8675309L;

    /** The bare share the patch is given is the share of the world's columns it makes bare, on any seed. */
    @Test
    void theBareShareHolds() {
        for (long seed : new long[]{SEED, 12345L, -77L}) for (double share : new double[]{0.25, 0.5, 0.75}) {
            SplitPatch sp = new SplitPatch(seed, share, 9);
            int n = 0, bare = 0;
            for (int x = -2000; x < 2000; x += 7) for (int z = -2000; z < 2000; z += 7) {
                n++;
                if (sp.bare(x, z)) bare++;
            }
            assertEquals(share, (double) bare / n, 0.02, "seed " + seed + ", share " + share);
        }
        assertFalse(new SplitPatch(SEED, 0, 9).bare(10, 10), "no share: never bare");
        assertTrue(new SplitPatch(SEED, 1, 9).bare(10, 10), "all of it: always bare");
    }

    /**
     * Laid as the halls lay them (3 a chunk, half widths 3 to 6) over a flat floor, the patches cover as much ground as
     * vanilla's vegetation_patch did with the same draws (its squares, corners cut, each edge column kept at 35 %), and
     * the moss on it is about half what that put down.
     */
    @Test
    void halfTheMossOfVanillasPatches() {
        int W = 320;
        boolean[][] old = new boolean[W][W];
        int[][] now = new int[W][W];   // 0 floor, 1 moss, 2 bare
        SplitPatch sp = new SplitPatch(SEED, 0.5, 9);
        Random r = new Random(5);
        for (int cx = -1; cx <= W / 16; cx++) for (int cz = -1; cz <= W / 16; cz++) for (int k = 0; k < 3; k++) {
            int ox = cx * 16 + r.nextInt(16), oz = cz * 16 + r.nextInt(16);
            int rx = 3 + r.nextInt(4), rz = 3 + r.nextInt(4);
            for (int i = -rx; i <= rx; i++) for (int j = -rz; j <= rz; j++) {
                boolean ex = i == -rx || i == rx, ez = j == -rz || j == rz;
                if (ex && ez || (ex || ez) && r.nextFloat() > 0.35F) continue;
                int x = ox + i, z = oz + j;
                if (x >= 0 && x < W && z >= 0 && z < W) old[x][z] = true;
            }
            double ax = rx + 0.5, az = rz + 0.5;
            int reach = SplitPatch.reach(Math.max(ax, az));
            for (int i = -reach; i <= reach; i++) for (int j = -reach; j <= reach; j++) {
                int x = ox + i, z = oz + j;
                if (x < 0 || x >= W || z < 0 || z >= W || !sp.inside(x, z, i, j, ax, az)) continue;
                now[x][z] = sp.bare(x, z) ? 2 : 1;
            }
        }
        int oldMoss = 0, moss = 0, bare = 0;
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) {
            if (old[x][z]) oldMoss++;
            if (now[x][z] == 1) moss++;
            if (now[x][z] == 2) bare++;
        }
        double cover = (double) (moss + bare) / oldMoss, kept = (double) moss / oldMoss;
        assertEquals(1.0, cover, 0.08, "the patches' ground against vanilla's");
        assertEquals(0.5, kept, 0.06, "the moss left against vanilla's");
    }

    /** The moss and the limestone lie in pieces a few blocks across: runs along x of 7 blocks or so, rarely one alone. */
    @Test
    void piecesAFewBlocksAcross() {
        SplitPatch sp = new SplitPatch(SEED, 0.5, 9);
        long runs = 0, total = 0, single = 0;
        for (int z = -3000; z < 3000; z += 37) {
            boolean cur = sp.bare(-3000, z);
            int run = 0;
            for (int x = -3000; x < 3000; x++) {
                boolean b = sp.bare(x, z);
                if (b == cur) run++;
                else {
                    runs++;
                    total += run;
                    if (run == 1) single++;
                    cur = b;
                    run = 1;
                }
            }
        }
        double mean = (double) total / runs;
        assertTrue(mean > 5 && mean < 11, "mean run " + mean);
        assertTrue((double) single / runs < 0.15, "single blocks " + (double) single / runs);
    }

    /** A patch's outline reaches no further than its reach, and covers its middle whatever the noise. */
    @Test
    void outlinesStayInReach() {
        SplitPatch sp = new SplitPatch(SEED, 0.5, 9);
        Random r = new Random(9);
        for (int k = 0; k < 2000; k++) {
            int ox = r.nextInt(20000) - 10000, oz = r.nextInt(20000) - 10000;
            double ax = 3.5 + r.nextInt(4), az = 3.5 + r.nextInt(4);
            int reach = SplitPatch.reach(Math.max(ax, az));
            assertTrue(sp.inside(ox, oz, 0, 0, ax, az), "the middle");
            for (int i = -reach - 3; i <= reach + 3; i++) for (int j = -reach - 3; j <= reach + 3; j++) {
                if (Math.abs(i) <= reach && Math.abs(j) <= reach) continue;
                assertFalse(sp.inside(ox + i, oz + j, i, j, ax, az), "outside the reach at " + i + ", " + j);
            }
        }
    }

    /** The same seed gives the same floors; another seed others. */
    @Test
    void theSeedDecides() {
        SplitPatch a = new SplitPatch(SEED, 0.5, 9), b = SplitPatch.of(SEED, 0.5, 9), c = new SplitPatch(SEED + 1, 0.5, 9);
        int differ = 0;
        for (int x = 0; x < 200; x++) for (int z = 0; z < 200; z++) {
            assertEquals(a.bare(x, z), b.bare(x, z));
            assertEquals(a.edge(x, z), b.edge(x, z));
            if (a.bare(x, z) != c.bare(x, z)) differ++;
        }
        assertTrue(differ > 8000, "another seed, " + differ + " of 40000 columns differ");
    }
}
