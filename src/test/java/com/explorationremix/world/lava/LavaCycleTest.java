package com.explorationremix.world.lava;

import com.explorationremix.world.lava.LavaCycle.Phase;
import com.explorationremix.world.terrain.Terrain;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** The lava cycle's phases and heights as a function of the cycle tick (Taran's 20-minute cycle, 2026-11-01). */
class LavaCycleTest {
    private static final int RISE = Terrain.FLOOD_RISE;
    private static final LavaCycle STD = LavaCycle.standard(RISE);

    @Test
    void theDefaultCycleIsTaransTwentyMinutes() {
        assertEquals(24000, STD.length());
        assertEquals(40, STD.rise());
        // 13.5 calm minutes, a minute and a half of warnings, a 2-minute rise, a minute at full, a 2-minute drain
        assertArrayEquals(new int[]{16200, 1800, 2400, 1200, 2400}, STD.lengths());
        assertEquals(0, STD.start(Phase.CALM));
        assertEquals(16200, STD.start(Phase.WARNING));
        assertEquals(18000, STD.start(Phase.RISE));
        assertEquals(20400, STD.start(Phase.FULL));
        assertEquals(21600, STD.start(Phase.DRAIN));
    }

    @Test
    void phasesChangeExactlyAtTheirBoundaries() {
        int[][] cases = {
                {0, 0, 0}, {16199, 0, 0},                  // calm: the lava at its table
                {16200, 1, 0}, {17999, 1, 0},              // warning: still at its table
                {18000, 2, 0}, {18059, 2, 0}, {18060, 2, 1}, {20399, 2, 39},
                {20400, 3, 40}, {21599, 3, 40},            // full
                {21600, 4, 40}, {21659, 4, 40}, {21660, 4, 39}, {23999, 4, 1},
                {24000, 0, 0}, {-1, 4, 1},                 // wrapped round the cycle both ways
        };
        for (int[] c : cases) {
            LavaCycle.State s = STD.at(c[0]);
            assertEquals(Phase.values()[c[1]], s.phase(), "phase at " + c[0]);
            assertEquals(c[2], s.level(), "level at " + c[0]);
            assertEquals(STD.level(c[0]), s.level());
        }
        LavaCycle.State s = STD.at(18000 + 600);
        assertEquals(600, s.into());
        assertEquals(2400, s.phaseLength());
        assertEquals(1800, s.left());
        assertEquals(0.25, s.progress(), 1e-9);
        assertEquals(18600, s.tick());
    }

    @Test
    void theLavaRisesAndFallsOneBlockAtATimeAtAnEvenPace() {
        int[] held = new int[RISE + 1];
        int last = 0;
        for (int t = STD.start(Phase.RISE); t < STD.start(Phase.FULL); t++) {
            int h = STD.level(t);
            assertTrue(h == last || h == last + 1, "rise jumps from " + last + " to " + h + " at " + t);
            last = h;
            held[h]++;
        }
        for (int h = 0; h < RISE; h++) assertEquals(60, held[h], "ticks at height " + h + " while rising (2 minutes over 40 blocks)");
        last = RISE;
        Arrays.fill(held, 0);
        for (int t = STD.start(Phase.DRAIN); t < STD.length(); t++) {
            int h = STD.level(t);
            assertTrue(h == last || h == last - 1, "drain jumps from " + last + " to " + h + " at " + t);
            last = h;
            held[h]++;
        }
        for (int h = 1; h <= RISE; h++) assertEquals(60, held[h], "ticks at height " + h + " while draining");
        assertEquals(1, last, "the last step of the drain lands on the table at the next calm");
        assertEquals(0, STD.level(STD.length()));
    }

    @Test
    void scalingSharesOutTheWholeCycle() {
        for (int total : new int[]{1, 7, 1200, 24000, 24001, 36000, 287999}) {
            int[] l = LavaCycle.scale(total, LavaCycle.DEFAULT_SHARES);
            assertEquals(total, Arrays.stream(l).sum(), "sum for " + total);
            for (int i = 0; i < l.length; i++) {
                double exact = total * LavaCycle.DEFAULT_SHARES[i] / 20.0;
                assertTrue(Math.abs(l[i] - exact) < 1.0, "phase " + i + " of " + total + ": " + l[i] + " for " + exact);
            }
        }
        // a 2-minute test cycle keeps the proportions
        assertArrayEquals(new int[]{1620, 180, 240, 120, 240}, LavaCycle.scale(2400, LavaCycle.DEFAULT_SHARES));
        // bad shares fall back on the default ones
        assertArrayEquals(LavaCycle.scale(24000, LavaCycle.DEFAULT_SHARES), LavaCycle.scale(24000, new double[]{0, 0, 0, 0, 0}));
        assertArrayEquals(LavaCycle.scale(24000, LavaCycle.DEFAULT_SHARES), LavaCycle.scale(24000, new double[]{1, 2}));
        assertArrayEquals(LavaCycle.scale(24000, LavaCycle.DEFAULT_SHARES), LavaCycle.scale(24000, new double[]{1, -1, 1, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> LavaCycle.scale(0, LavaCycle.DEFAULT_SHARES));
    }

    @Test
    void phasesOfNoLengthAreSkipped() {
        // no warning and no full phase: calm straight into the rise, the rise straight into the drain
        LavaCycle c = new LavaCycle(new int[]{100, 0, 40, 0, 40}, RISE);
        assertEquals(Phase.CALM, c.at(99).phase());
        assertEquals(Phase.RISE, c.at(100).phase());
        assertEquals(0, c.at(100).level());
        assertEquals(39, c.at(139).level());
        assertEquals(Phase.DRAIN, c.at(140).phase());
        assertEquals(40, c.at(140).level());
        for (int t = 0; t < c.length(); t++) {
            LavaCycle.State s = c.at(t);
            assertTrue(s.phaseLength() > 0, "an empty phase was reported at " + t);
            assertTrue(s.into() >= 0 && s.into() < s.phaseLength());
        }
        // an empty first phase
        LavaCycle d = new LavaCycle(new int[]{0, 10, 10, 10, 10}, RISE);
        assertEquals(Phase.WARNING, d.at(0).phase());
        assertThrows(IllegalArgumentException.class, () -> new LavaCycle(new int[]{0, 0, 0, 0, 0}, RISE));
        assertThrows(IllegalArgumentException.class, () -> new LavaCycle(new int[]{1, 2, 3}, RISE));
        assertThrows(IllegalArgumentException.class, () -> new LavaCycle(new int[]{10, -1, 10, 10, 10}, RISE));
    }

    @Test
    void ticksForPhasesAndHeightsRoundTrip() {
        for (Phase p : Phase.values()) {
            assertEquals(p, STD.at(STD.tickOf(p, 0)).phase());
            assertEquals(0, STD.at(STD.tickOf(p, 0)).into());
            assertEquals(p, STD.at(STD.tickOf(p, 1_000_000)).phase(), "into is held inside the phase");
            assertEquals(STD.length(p) - 1, STD.at(STD.tickOf(p, 1_000_000)).into());
            assertEquals(p, LavaCycle.Phase.byId(p.id()));
        }
        assertNull(LavaCycle.Phase.byId("flood"));
        for (int h = 0; h <= RISE; h++) {
            int t = STD.tickForLevel(h);
            assertEquals(h, STD.level(t), "height " + h);
            if (h > 0) assertNotEquals(h, STD.level(t - 1), "the first tick at height " + h);
        }
        assertEquals(Phase.CALM, STD.at(STD.tickForLevel(0)).phase());
        assertEquals(Phase.FULL, STD.at(STD.tickForLevel(RISE)).phase());
        assertEquals(Phase.FULL, STD.at(STD.tickForLevel(RISE + 5)).phase());
        // a rise too short for every height: the nearest height under it
        LavaCycle quick = new LavaCycle(new int[]{100, 10, 20, 10, 20}, RISE);
        for (int h = 0; h <= RISE; h++) {
            int got = quick.level(quick.tickForLevel(h));
            assertTrue(got <= h && got >= h - 2, "height " + h + " in a 20-tick rise: " + got);
        }
    }

    @Test
    void heightsAreFoundInTheDrainWhenTheRiseIsEmpty() {
        // a config may give the rise (or the rise and the full phase) no share: /remix lava level must still find each
        // height, in the drain
        for (double[] shares : new double[][]{{13.5, 1.5, 0, 1, 2}, {13.5, 1.5, 0, 0, 2}}) {
            LavaCycle c = new LavaCycle(LavaCycle.scale(24000, shares), RISE);
            for (int h = 0; h <= RISE; h++) assertEquals(h, c.level(c.tickForLevel(h)), "height " + h + " with shares " + Arrays.toString(shares));
        }
        // a 1-tick rise skips every height between 0 and full; the drain has them
        LavaCycle blink = new LavaCycle(new int[]{17999, 2000, 1, 1333, 2667}, RISE);
        for (int h = 0; h <= RISE; h++) assertEquals(h, blink.level(blink.tickForLevel(h)), "height " + h + " after a 1-tick rise");
    }
}
