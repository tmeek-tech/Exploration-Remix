package com.explorationremix.client.render;

import org.junit.jupiter.api.Test;

import static com.explorationremix.client.render.FireflySwarmRenderer.*;
import static org.junit.jupiter.api.Assertions.*;

/** A firefly's halo: as it always was up close, growing with the distance so a far swarm keeps more of its size. */
class FireflySwarmRendererTest {

    @Test
    void theHaloKeepsItsSizeUpClose() {
        assertEquals(1.0F, haloGrowth(0.0));
        assertEquals(1.0F, haloGrowth(3.0));
        assertEquals(1.0F, haloGrowth(HALO_NEAR));
    }

    @Test
    void itGrowsToTwiceItsSizeAtTwentyBlocksAndNoMore() {
        assertEquals(2.0F, haloGrowth(HALO_FAR), 1.0E-6);
        assertEquals(2.0F, haloGrowth(60.0), 1.0E-6);
        assertEquals(1.5F, haloGrowth((HALO_NEAR + HALO_FAR) / 2.0), 1.0E-6);
        float last = 0.0F;
        for (double d = 0.0; d <= 40.0; d += 0.5) {
            float g = haloGrowth(d);
            assertTrue(g >= last, "never smaller further off");
            last = g;
        }
    }

    @Test
    void itHoldsItsApparentSizeBetterWithTheDistance() {
        // the halo's apparent size goes as its size over the distance: at 20 blocks it is at least half what it is at 6
        // (it would be under a third with no growth)
        double near = haloGrowth(HALO_NEAR) / HALO_NEAR, far = haloGrowth(HALO_FAR) / HALO_FAR;
        assertTrue(far >= 0.55 * near, "far " + far + " near " + near);
    }
}
