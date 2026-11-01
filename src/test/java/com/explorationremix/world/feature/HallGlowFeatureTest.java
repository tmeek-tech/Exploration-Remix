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
}
