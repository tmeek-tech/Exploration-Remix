package com.explorationremix.creature.firefly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A firefly's light: steady in flight, a slow breath when settled, the swarm's breaths never falling together. */
class PulseTest {
    private static final long[] SEEDS = {0, 1, 42, -7, 123456789L, 5, 6, 7, 8, 9};

    @Test
    void aBreathTakesTwoToFourSeconds() {
        for (long seed = -500; seed < 500; seed++) {
            double p = Pulse.period(seed);
            assertTrue(p >= 40.0 && p < 80.0, "period " + p + " ticks");
            assertEquals(p, Pulse.period(seed), "a swarm always breathes the same way");
        }
    }

    @Test
    void everyFireflyBreathesOncePerPeriod() {
        for (long seed : SEEDS) {
            double p = Pulse.period(seed);
            for (int id = 0; id < 24; id++)
                for (double t = 0; t < 200; t += 3.7)
                    assertEquals(Pulse.settled(seed, id, t), Pulse.settled(seed, id, t + p), 1.0E-9);
        }
    }

    @Test
    void aSettledFireflySwellsToFullAndFadesToItsRest() {
        for (long seed : SEEDS)
            for (int id = 0; id < 8; id++) {
                double p = Pulse.period(seed), lo = 1, hi = 0;
                for (double t = 0; t < p; t += 0.25) {
                    double b = Pulse.settled(seed, id, t);
                    assertTrue(b >= Pulse.LOW - 1.0E-9 && b <= 1.0 + 1.0E-9, "brightness " + b);
                    lo = Math.min(lo, b);
                    hi = Math.max(hi, b);
                }
                assertEquals(Pulse.LOW, lo, 1.0E-6, "it rests dim between breaths");
                assertTrue(hi > 0.98, "each breath reaches full brightness, got " + hi);
                double sw = Pulse.swell(seed, id);
                assertTrue(sw >= Pulse.MIN_SWELL && sw < Pulse.MAX_SWELL);
            }
        // the breath itself: dark at the start, bright in the middle of the swell, dark through the rest
        assertEquals(0.0, Pulse.breath(0.0, 0.6), 1.0E-9);
        assertEquals(1.0, Pulse.breath(0.3, 0.6), 1.0E-9);
        assertEquals(0.0, Pulse.breath(0.8, 0.6), 1.0E-9);
        assertEquals(Pulse.breath(0.25, 0.6), Pulse.breath(3.25, 0.6), 1.0E-9, "a cycle is a cycle");
    }

    @Test
    void inFlightTheGlowIsSteady() {
        for (int id = 0; id < 8; id++)
            for (double t = 0; t < 400; t += 1.3)
                assertEquals(Pulse.GLOW, Pulse.brightness(7, id, t, 0.0), 1.0E-12);
    }

    @Test
    void landingEasesFromTheGlowIntoTheBreath() {
        long seed = 5;
        for (double t = 0; t < 100; t += 2.5) {
            double half = Pulse.brightness(seed, 3, t, 0.5);
            double settled = Pulse.settled(seed, 3, t);
            assertEquals((Pulse.GLOW + settled) / 2, half, 1.0E-9, "half settled is half way");
            assertEquals(settled, Pulse.brightness(seed, 3, t, 1.0), 1.0E-12);
            assertEquals(settled, Pulse.brightness(seed, 3, t, 7.0), 1.0E-12, "clamped");
        }
    }

    @Test
    void aSettledSwarmNeverPulsesTogether() {
        for (int n : new int[]{8, 16, 24})
            for (long seed : SEEDS)
                for (int t = 0; t < 20 * 60 * 5; t++) {       // five minutes
                    int bright = 0, dim = 0;
                    double mean = 0;
                    for (int id = 0; id < n; id++) {
                        double b = Pulse.settled(seed, id, t);
                        if (b > 0.6) bright++;
                        if (b < 0.2) dim++;
                        mean += b / n;
                    }
                    String at = n + " fireflies, seed " + seed + ", tick " + t + ": ";
                    assertTrue(bright <= n / 2, at + bright + " bright at once");
                    assertTrue(dim < n, at + "the whole swarm dark at once");
                    // the patch as a whole neither flares nor goes out: its light only wanders
                    assertTrue(mean > 0.2 && mean < 0.6, at + "mean brightness " + mean);
                }
    }

    @Test
    void neighboursByNumberBreatheFarApart() {
        for (long seed = 0; seed < 50; seed++)
            for (int id = 0; id + 1 < 24; id++) {
                double d = Math.abs(Pulse.phase(seed, id) - Pulse.phase(seed, id + 1));
                d = Math.min(d, 1.0 - d);
                assertTrue(d > 0.25, "seed " + seed + ": fireflies " + id + " and " + (id + 1) + " breathe " + d + " of a cycle apart");
            }
    }
}
