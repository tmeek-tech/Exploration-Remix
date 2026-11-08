package com.explorationremix.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An eruption as the client plays it (Fountain): the fountain throws in full for the burst's first second, then gusts
 * between 0.6 and 1 the same way on every client and every frame of a held moment, and dies away over its last 30
 * ticks; its spatter climbs 10 to 27 blocks under its own physics (15 to 40 for a woken eruption); the roar swells and
 * dies with it; the boom and the roar keep a floor of loudness to the eruption's range; far particles thin with the
 * Particles option and the distance and the spatter grows; the vent's rumble starts only in time, a short warning's too;
 * a bomb's jolt and whistle follow their rules.
 */
class FountainTest {
    private static final long[] SEEDS = {0, 1, 7, -42, 8675309, Long.MIN_VALUE, Long.MAX_VALUE};

    @Test
    void theFountainThrowsInFullThenGustsAndDiesAway() {
        int burst = 200;
        for (long seed : SEEDS) {
            for (int t = 0; t < Fountain.FULL_TICKS; t++) assertEquals(1, Fountain.intensity(t, burst, seed), 0, "full at first");
            double prev = 1, lo = 1;
            for (double t = Fountain.FULL_TICKS; t < burst - Fountain.FADE_TICKS; t += 0.5) {
                double k = Fountain.intensity(t, burst, seed);
                assertTrue(k >= Fountain.GUST_LOW - 1e-9 && k <= 1 + 1e-9, "gusts between 0.6 and 1: " + k);
                assertTrue(Math.abs(k - prev) < 0.05, "no jump in a half tick");
                prev = k;
                lo = Math.min(lo, k);
            }
            assertTrue(lo < 0.85, "it does gust, seed " + seed);
            assertEquals(0, Fountain.intensity(burst, burst, seed), 0);
            assertEquals(0, Fountain.intensity(-1, burst, seed), 0);
            double before = Fountain.intensity(burst - Fountain.FADE_TICKS - 0.01, burst, seed);
            assertTrue(Fountain.intensity(burst - Fountain.FADE_TICKS / 2.0, burst, seed) <= 0.5 + 1e-9, "half gone half way through its fade");
            assertTrue(Fountain.intensity(burst - 1, burst, seed) < before / 10, "nearly gone at its end");
            // every client, and every frame of a held moment, see the same
            assertEquals(Fountain.intensity(87.25, burst, seed), Fountain.intensity(87.25, burst, seed), 0);
        }
    }

    @Test
    void theSpatterClimbsTenToTwentySevenBlocksAndHalfAgainWoken() {
        double low = Fountain.apex(Fountain.speed(0, 1)), high = Fountain.apex(Fountain.speed(1, 1));
        assertTrue(low >= 9.5 && low <= 11, "lowest " + low);
        assertTrue(high >= 25 && high <= 27.5, "highest " + high);
        double wokenLow = Fountain.apex(Fountain.speed(0, 1.5)), wokenHigh = Fountain.apex(Fountain.speed(1, 1.5));
        assertTrue(wokenLow >= 13.5 && wokenLow <= 16, "woken lowest " + wokenLow);
        assertTrue(wokenHigh >= 34 && wokenHigh <= 40, "woken highest " + wokenHigh);
        // the apex comes within the spatter's shortest life (30 ticks) for nearly all of them, so the column reaches its top
        double v = Fountain.speed(1, 1), y = 0;
        for (int t = 0; t < 30; t++) {
            v -= 0.04 * Fountain.SPATTER_GRAVITY;
            y += v;
            v *= Fountain.SPATTER_FRICTION;
        }
        assertTrue(y > 0.95 * high, "at 30 ticks the fastest is at " + y + " of " + high);
    }

    @Test
    void theRoarSwellsAndDiesWithTheFountain() {
        int burst = 180;
        assertEquals(0, Fountain.roarEnvelope(0, burst), 0);
        assertEquals(0.5, Fountain.roarEnvelope(5, burst), 1e-12);
        assertEquals(1, Fountain.roarEnvelope(Fountain.ROAR_RISE, burst), 1e-12);
        assertEquals(1, Fountain.roarEnvelope(burst - Fountain.ROAR_FADE, burst), 1e-12);
        assertEquals(0.5, Fountain.roarEnvelope(burst - Fountain.ROAR_FADE / 2.0, burst), 1e-12);
        assertEquals(0, Fountain.roarEnvelope(burst, burst), 0);
        assertEquals(0, Fountain.roarEnvelope(-3, burst), 0);
    }

    @Test
    void theBoomAndTheRoarAreHeardToTheEruptionsRange() {
        assertEquals(1, Fountain.boomVolume(0, 256), 0);
        assertEquals(1, Fountain.boomVolume(51.2, 256), 1e-12);
        assertEquals(0.7, Fountain.boomVolume(128, 256), 1e-12);
        assertEquals(0.2, Fountain.boomVolume(256, 256), 1e-12);
        assertEquals(0.15, Fountain.boomVolume(1000, 256), 0);
        assertEquals(0.1, Fountain.roarVolume(300, 256), 0);
        assertEquals(0.7, Fountain.roarVolume(256, 512), 1e-12);
        for (double d = 0; d < 600; d += 7) {
            assertTrue(Fountain.boomVolume(d, 256) >= Fountain.roarVolume(d, 256), "the boom never quieter than the roar");
        }
    }

    @Test
    void farParticlesThinAndTheSpatterGrows() {
        assertEquals(1, Fountain.farShare(10, 0.1), 0, "near: the game thins them itself");
        assertEquals(1, Fountain.farShare(32, 0.4), 0);
        assertEquals(1, Fountain.farShare(40, 1), 0);
        assertEquals(0.4, Fountain.farShare(48, 0.4), 1e-12);
        assertEquals(0.5, Fountain.farShare(96, 1), 1e-12);
        assertEquals(0.1 * 48 / 200.0, Fountain.farShare(200, 0.1), 1e-12);
        assertEquals(1, Fountain.farSize(10), 0);
        assertEquals(1, Fountain.farSize(32), 0);
        assertEquals(4, Fountain.farSize(128), 1e-12);
        assertEquals(5, Fountain.plumeEvery(1));
        assertEquals(3, Fountain.plumeEvery(1.5));
        assertEquals(2, Fountain.plumeEvery(3));
        assertEquals(1, Fountain.plumeEvery(100));
    }

    @Test
    void aShareOfOneShowsNowAndThen() {
        assertEquals(0, Fountain.count(0, 0));
        assertEquals(0, Fountain.count(-2, 0));
        assertEquals(3, Fountain.count(3, 0.99));
        assertEquals(4, Fountain.count(3.25, 0.2));
        assertEquals(3, Fountain.count(3.25, 0.3));
        int n = 0;
        for (int i = 0; i < 1000; i++) n += Fountain.count(0.25, (i + 0.5) / 1000);
        assertEquals(250, n, "on average the share asked for");
    }

    @Test
    void theVentRumblesWhenItsWarningIsHeardInTime() {
        // the default warning (4 s): only with 20 ticks or more of it to come
        assertTrue(Fountain.rumbleInTime(0, 80));
        assertTrue(Fountain.rumbleInTime(60, 80));
        assertTrue(!Fountain.rumbleInTime(60.5, 80), "too late with under 20 ticks left");
        assertTrue(!Fountain.rumbleInTime(80, 80), "never once the burst has begun");
        // a short warning (warnSeconds 1, 0.5, 0.1) still rumbles when it is heard from its start, with half of it to come
        for (int pre : new int[]{20, 10, 2}) {
            assertTrue(Fountain.rumbleInTime(0, pre), "a warning of " + pre + " ticks heard from its start");
            assertTrue(Fountain.rumbleInTime(1, pre) == (pre - 1 >= pre / 2.0), "a warning of " + pre + " ticks, one tick in");
            assertTrue(!Fountain.rumbleInTime(pre, pre));
        }
        assertTrue(!Fountain.rumbleInTime(0, 0), "no warning, no rumble");
    }

    @Test
    void aBombsJoltAndWhistle() {
        assertEquals(0.4, Fountain.bombJolt(0), 1e-12);
        assertEquals(0.2, Fountain.bombJolt(16), 1e-12);
        assertEquals(0, Fountain.bombJolt(32), 0);
        assertEquals(0, Fountain.bombJolt(60), 0);
        assertEquals(0.95, Fountain.whistlePitch(0), 1e-12);
        assertEquals(1.10, Fountain.whistlePitch(1.6), 1e-12);
        assertEquals(0.80, Fountain.whistlePitch(-2), 1e-12);
        assertTrue(Fountain.whistlePitch(0.5) > Fountain.whistlePitch(-0.5), "higher climbing than falling");
    }
}
