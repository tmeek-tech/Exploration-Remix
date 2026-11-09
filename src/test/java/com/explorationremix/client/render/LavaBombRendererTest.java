package com.explorationremix.client.render;

import org.junit.jupiter.api.Test;

import static com.explorationremix.client.render.LavaBombRenderer.*;
import static org.junit.jupiter.api.Assertions.*;

/** A lava bomb's look: its halo as it is up close and growing with the distance, and its own tumbling axis. */
class LavaBombRendererTest {

    @Test
    void theHaloGrowsWithTheDistanceToThreeTimesItsSize() {
        assertEquals(1.0F, haloGrowth(0.0));
        assertEquals(1.0F, haloGrowth(HALO_NEAR));
        assertEquals(3.0F, haloGrowth(HALO_FAR), 1.0E-6);
        assertEquals(3.0F, haloGrowth(150.0), 1.0E-6);
        assertEquals(2.0F, haloGrowth((HALO_NEAR + HALO_FAR) / 2.0), 1.0E-6);
        float last = 0.0F;
        for (double d = 0.0; d <= 160.0; d += 0.5) {
            float g = haloGrowth(d);
            assertTrue(g >= last, "never smaller further off");
            last = g;
        }
        // at 96 blocks the halo is still about a degree and a half across (it would be half a degree with no growth)
        double degrees = Math.toDegrees(2 * Math.atan(HALO_HALF * haloGrowth(HALO_FAR) / HALO_FAR));
        assertTrue(degrees > 1.0 && degrees < 3.0, "the far halo is " + degrees + " degrees across");
    }

    @Test
    void eachBombTumblesAboutItsOwnAxis() {
        float[] first = axis(1);
        boolean differs = false;
        for (int id = -50; id < 500; id++) {
            float[] a = axis(id);
            assertEquals(1.0, Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), 1.0E-5, "a unit axis");
            assertTrue(a[3] >= 0 && a[3] < 360, "a start angle in degrees");
            assertArrayEquals(a, axis(id), "the same on every client and every frame");
            if (id != 1 && Math.abs(a[0] - first[0]) + Math.abs(a[1] - first[1]) + Math.abs(a[2] - first[2]) > 0.1) differs = true;
        }
        assertTrue(differs, "bombs do not all tumble alike");
    }
}
