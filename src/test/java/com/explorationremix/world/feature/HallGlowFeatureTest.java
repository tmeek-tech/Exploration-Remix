package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Perlin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The Galleries' lava glow: how bright it is at each height over the flood band. */
class HallGlowFeatureTest {
    // only HallGlow and the config record: HallGlowFeature itself is a Feature, which needs the game's registries
    private static final HallGlowFeature.Config C = new HallGlowFeature.Config(4, 4, 15, 11, 60, 2, 9);

    @Test
    void brightestOverTheLavaFadingUpward() {
        assertEquals(15, HallGlow.levelAt(C, 0, null, 0, 0, 0), "just over the flood band");
        assertEquals(13, HallGlow.levelAt(C, 30, null, 0, 0, 0));
        assertEquals(11, HallGlow.levelAt(C, 60, null, 0, 0, 0), "under the vaults");
        assertEquals(11, HallGlow.levelAt(C, 200, null, 0, 0, 0), "no dimmer above the fade");
        int prev = 16;
        for (int over = 0; over <= 100; over++) {
            int lv = HallGlow.levelAt(C, over, null, 0, 0, 0);
            assertTrue(lv <= prev, "never brighter higher up, at " + over);
            prev = lv;
        }
    }

    @Test
    void patchesMoveItAtMostTwoEitherWayAndStayALight() {
        Perlin n = new Perlin(42);
        int lo = 16, hi = 0;
        for (int x = 0; x < 400; x += 3) for (int y = -780; y < -690; y += 5) {
            int over = y + 782;
            int plain = HallGlow.levelAt(C, over, null, x, y, 7);
            int lv = HallGlow.levelAt(C, over, n, x, y, 7);
            assertTrue(Math.abs(lv - plain) <= 2, "patch moved " + plain + " to " + lv);
            assertTrue(lv >= 1 && lv <= 15);
            lo = Math.min(lo, lv - plain);
            hi = Math.max(hi, lv - plain);
        }
        assertTrue(lo < 0 && hi > 0, "the patches both dim and brighten (" + lo + " to " + hi + ")");
    }

    @Test
    void theBandsGlowIsFullBright() {
        assertEquals(15, HallGlow.BAND_LEVEL);
    }

    @Test
    void theShippedFadeKeepsTheVaultsLit() {
        // tools/worldgen.py: a face gets 3 to 4 levels under its glow, so the vaults need 14 to read lit under the shader
        HallGlowFeature.Config shipped = new HallGlowFeature.Config(4, 4, 15, 14, 60, 1, 9);
        assertEquals(15, HallGlow.levelAt(shipped, 0, null, 0, 0, 0));
        assertEquals(14, HallGlow.levelAt(shipped, 60, null, 0, 0, 0));
        assertEquals(14, HallGlow.levelAt(shipped, 200, null, 0, 0, 0));
        Perlin n = new Perlin(42);
        for (int x = 0; x < 300; x += 7) for (int over = 0; over < 120; over += 3) {
            int lv = HallGlow.levelAt(shipped, over, n, x, over, 3);
            assertTrue(lv >= 13 && lv <= 15, "level " + lv + " at " + over + " over the band");
        }
    }

    @Test
    void spotsOfNeighbouringCubesAreIndependent() {
        // Hashed once with Hashing.mix, cubes next to each other along z kept the same x offset 38% of the time and
        // never shared a spot, which lined the glow up in rows along the walls.
        int n = 4;
        int[][] steps = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int[] st : steps) {
            long pairs = 0, sameX = 0, sameY = 0, sameZ = 0, sameSpot = 0;
            for (long seed : new long[]{1, 8675309, 2000023})
                for (int cx = -30; cx < 30; cx++) for (int cy = -206; cy < -172; cy++) for (int cz = -30; cz < 30; cz++) {
                    long a = HallGlow.spotHash(seed, cx, cy, cz), b = HallGlow.spotHash(seed, cx + st[0], cy + st[1], cz + st[2]);
                    boolean x = Long.remainderUnsigned(a, n) == Long.remainderUnsigned(b, n);
                    boolean y = Long.remainderUnsigned(a >>> 16, n) == Long.remainderUnsigned(b >>> 16, n);
                    boolean z = Long.remainderUnsigned(a >>> 32, n) == Long.remainderUnsigned(b >>> 32, n);
                    pairs++;
                    if (x) sameX++;
                    if (y) sameY++;
                    if (z) sameZ++;
                    if (x && y && z) sameSpot++;
                }
            String along = st[0] == 1 ? "x" : st[1] == 1 ? "y" : "z";
            for (long same : new long[]{sameX, sameY, sameZ}) {
                double f = same / (double) pairs;
                assertTrue(f > 0.24 && f < 0.26, "neighbours along " + along + " share an offset " + f + " of the time");
            }
            double spot = sameSpot / (double) pairs;
            assertTrue(spot > 0.0135 && spot < 0.0178, "neighbours along " + along + " share the spot " + spot + " of the time");
        }
    }
}
