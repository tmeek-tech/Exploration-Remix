package com.explorationremix.world.lava;

import com.explorationremix.world.lava.LavaCycle.Phase;
import com.explorationremix.world.terrain.Terrain;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lava cycle's phases and heights as a function of the cycle tick (Taran's 20-minute cycle, 2026-11-01), with
 * 0.21.6's smooth plane: four climbs of 10 blocks with breaks between them, and one quick fall.
 */
class LavaCycleTest {
    private static final int RISE = Terrain.FLOOD_RISE;
    private static final LavaCycle STD = LavaCycle.standard(RISE);
    private static final int R0 = 20100, F0 = 22500, D0 = 23700;

    @Test
    void theDefaultCycleIsTaransTwentyMinutes() {
        assertEquals(24000, STD.length());
        assertEquals(40, STD.rise());
        // 15.25 calm minutes, a minute and a half of warnings, a 2-minute rise, a minute at full, a 15-second fall
        assertArrayEquals(new int[]{18300, 1800, 2400, 1200, 300}, STD.lengths());
        assertEquals(0, STD.start(Phase.CALM));
        assertEquals(18300, STD.start(Phase.WARNING));
        assertEquals(R0, STD.start(Phase.RISE));
        assertEquals(F0, STD.start(Phase.FULL));
        assertEquals(D0, STD.start(Phase.DRAIN));
        // four climbs of 15 seconds with 20-second breaks between them
        assertEquals(4, STD.stages());
        assertEquals(0.5, STD.breakShare(), 1e-12);
        LavaCycle.Stage first = STD.stage(0, 2400), brk = STD.stage(300, 2400), last = STD.stage(2399, 2400);
        assertEquals(new LavaCycle.Stage(0, true, 0, 300), first);
        assertEquals(new LavaCycle.Stage(0, false, 0, 400), brk);
        assertEquals(3, last.index());
        assertTrue(last.climbing(), "no break after the last climb: the full lava follows");
        assertEquals(300, last.length(), 1e-9);
    }

    @Test
    void phasesChangeExactlyAtTheirBoundaries() {
        // {tick, phase, rows of the band under the plane}
        int[][] cases = {
                {0, 0, 0}, {18299, 0, 0},                   // calm: the lava at its table
                {18300, 1, 0}, {20099, 1, 0},               // warning: still at its table
                {R0, 2, 0}, {R0 + 150, 2, 5},               // halfway up the first climb
                {R0 + 300, 2, 10}, {R0 + 699, 2, 10},       // the first break, 10 blocks up
                {R0 + 700, 2, 10}, {R0 + 1000, 2, 20}, {R0 + 1400, 2, 20}, {R0 + 1700, 2, 30}, {R0 + 2099, 2, 30},
                {F0 - 1, 2, 40},                            // the end of the last climb
                {F0, 3, 40}, {D0 - 1, 3, 40},               // full
                {D0, 4, 40}, {D0 + 150, 4, 20}, {23999, 4, 0},  // the fall: halfway down in 7.5 seconds
                {24000, 0, 0}, {-1, 4, 0},                  // wrapped round the cycle both ways
        };
        for (int[] c : cases) {
            LavaCycle.State s = STD.at(c[0]);
            assertEquals(Phase.values()[c[1]], s.phase(), "phase at " + c[0]);
            assertEquals(c[2], s.level(), "rows at " + c[0]);
            assertEquals(STD.level(c[0]), s.level());
            assertEquals(STD.height(c[0]), s.height(), 1e-12, "height at " + c[0]);
        }
        assertEquals(5.0, STD.at(R0 + 150).height(), 1e-9, "a climb eases in and out: halfway in time is halfway up");
        assertEquals(20.0, STD.at(D0 + 150).height(), 1e-9);
        LavaCycle.State s = STD.at(R0 + 600);
        assertEquals(600, s.into());
        assertEquals(2400, s.phaseLength());
        assertEquals(1800, s.left());
        assertEquals(0.25, s.progress(), 1e-9);
        assertEquals(R0 + 600, s.tick());
    }

    @Test
    void theLavaClimbsInFourStagesWithBreaksBetweenAndFallsAtOnce() {
        double last = 0;
        int lastRows = 0;
        int[] climbTicks = new int[4], breakTicks = new int[3];
        boolean[] reached = new boolean[RISE + 1];
        for (int t = R0; t < F0; t++) {
            double h = STD.height(t);
            int rows = STD.level(t);
            assertTrue(h >= last - 1e-12, "the rise never goes down: " + last + " to " + h + " at " + t);
            // smooth: a climb of 10 blocks in 300 ticks moves at most 1.5 times its mean speed (smoothstep's steepest)
            assertTrue(h - last <= 10 * 1.5 / 300 + 1e-9, "a jump of " + (h - last) + " at " + t);
            assertTrue(rows == lastRows || rows == lastRows + 1, "rows jump from " + lastRows + " to " + rows + " at " + t);
            reached[rows] = true;
            LavaCycle.Stage st = STD.stage(STD.at(t));
            if (st.climbing()) climbTicks[st.index()]++;
            else {
                breakTicks[st.index()]++;
                assertEquals(10.0 * (st.index() + 1), h, 1e-12, "the lava holds still in the break after climb " + (st.index() + 1));
            }
            last = h;
            lastRows = rows;
        }
        assertArrayEquals(new int[]{300, 300, 300, 300}, climbTicks);
        assertArrayEquals(new int[]{400, 400, 400}, breakTicks);
        for (int h = 0; h <= RISE; h++) assertTrue(reached[h], "the rise passes " + h + " rows");
        assertEquals(RISE, STD.height(F0), 1e-12);
        // the fall: one smooth drop, never back up, quicker in the middle than at its ends, down at its table for the calm
        last = RISE;
        for (int t = D0; t < STD.length(); t++) {
            double h = STD.height(t);
            assertTrue(h <= last + 1e-12, "the fall never climbs: " + last + " to " + h + " at " + t);
            assertTrue(last - h <= RISE * 1.5 / 300 + 1e-9, "a drop of " + (last - h) + " at " + t);
            last = h;
        }
        assertTrue(STD.height(23999) < 0.01, "all but down on the fall's last tick: " + STD.height(23999));
        assertEquals(0, STD.height(24000), 0);
        assertNull(STD.stage(STD.at(D0)), "no climb outside the rise");
    }

    @Test
    void theHeightIsSmoothBetweenTicks() {
        // a client draws the plane between ticks: the height there lies between the heights at the ticks either side
        for (int t = R0 - 2; t < 24000 + 2; t += 7) {
            double a = STD.height(t), b = STD.height(t + 1), m = STD.height(t + 0.5);
            assertTrue(m >= Math.min(a, b) - 1e-12 && m <= Math.max(a, b) + 1e-12, "between " + t + " and " + (t + 1) + ": " + a + ", " + m + ", " + b);
        }
        assertEquals(0, STD.height(Double.NaN), 0, "a tick that is not a number reads as the calm");
        assertEquals(STD.height(R0 + 150), STD.height(R0 + 150 + 24000L * 1000), 1e-6, "the cycle repeats");
    }

    @Test
    void rowsCountTheBlocksWhoseMiddleIsUnderTheSurface() {
        // the plane's surface stands 1/9 under the table's Y plus its height; row i is under once i + 0.5 is
        assertEquals(0, STD.rows(0));
        assertEquals(0, STD.rows(0.5 + LavaCycle.SURFACE_DROP - 1e-9));
        assertEquals(1, STD.rows(0.5 + LavaCycle.SURFACE_DROP + 1e-9));
        assertEquals(10, STD.rows(10));
        assertEquals(39, STD.rows(39.5));
        assertEquals(40, STD.rows(40));
        assertEquals(40, STD.rows(100), "never more than the rise");
        assertEquals(0, STD.rows(-3));
        assertEquals(0, STD.rows(Double.NaN));
    }

    @Test
    void theClimbsAndBreaksFollowTheirSettings() {
        // Taran's other picks: three climbs with 40-second breaks, six with 12-second ones
        int[] lengths = STD.lengths();
        LavaCycle three = new LavaCycle(lengths, RISE, 3, 80.0 / 120);
        assertEquals(800.0 / 3, three.stage(0, 2400).length(), 1e-6, "40 seconds of climbing shared by three");
        assertEquals(800, three.stage(900, 2400).length(), 1e-6, "40-second breaks");
        assertFalse(three.stage(900, 2400).climbing());
        LavaCycle six = new LavaCycle(lengths, RISE, 6, 0.5);
        assertEquals(200, six.stage(0, 2400).length(), 1e-6);
        assertEquals(240, six.stage(200, 2400).length(), 1e-6, "12-second breaks");
        assertEquals(RISE / 6.0, six.height(R0 + 200), 1e-9, "a sixth of the way up after the first climb");
        // one climb is the whole rise, with no breaks
        LavaCycle one = new LavaCycle(lengths, RISE, 1, 0.5);
        assertEquals(0, one.breakShare(), 0);
        assertEquals(RISE / 2.0, one.height(R0 + 1200), 1e-9);
        assertTrue(one.stage(1200, 2400).climbing());
        // settings out of range are held in range
        assertEquals(1, new LavaCycle(lengths, RISE, 0, 0.5).stages());
        assertEquals(LavaCycle.MAX_STAGES, new LavaCycle(lengths, RISE, 1000, 0.5).stages());
        assertEquals(LavaCycle.MAX_BREAK_SHARE, new LavaCycle(lengths, RISE, 4, 5).breakShare(), 0);
        assertEquals(0, new LavaCycle(lengths, RISE, 4, -1).breakShare(), 0);
        assertEquals(0, new LavaCycle(lengths, RISE, 4, Double.NaN).breakShare(), 0);
        // with no breaks the climbs follow one another straight away, each easing in and out
        LavaCycle steady = new LavaCycle(lengths, RISE, 4, 0);
        assertEquals(10, steady.height(R0 + 600), 1e-9);
        assertEquals(5, steady.height(R0 + 300), 1e-9);
    }

    @Test
    void scalingSharesOutTheWholeCycle() {
        double sum = Arrays.stream(LavaCycle.DEFAULT_SHARES).sum();
        assertEquals(20.0, sum, 1e-12, "the default shares are the 20 minutes");
        for (int total : new int[]{1, 7, 1200, 24000, 24001, 36000, 287999}) {
            int[] l = LavaCycle.scale(total, LavaCycle.DEFAULT_SHARES);
            assertEquals(total, Arrays.stream(l).sum(), "sum for " + total);
            for (int i = 0; i < l.length; i++) {
                double exact = total * LavaCycle.DEFAULT_SHARES[i] / 20.0;
                assertTrue(Math.abs(l[i] - exact) < 1.0, "phase " + i + " of " + total + ": " + l[i] + " for " + exact);
            }
        }
        // a 2-minute test cycle keeps the proportions
        assertArrayEquals(new int[]{1830, 180, 240, 120, 30}, LavaCycle.scale(2400, LavaCycle.DEFAULT_SHARES));
        // bad shares fall back on the default ones
        assertArrayEquals(LavaCycle.scale(24000, LavaCycle.DEFAULT_SHARES), LavaCycle.scale(24000, new double[]{0, 0, 0, 0, 0}));
        assertArrayEquals(LavaCycle.scale(24000, LavaCycle.DEFAULT_SHARES), LavaCycle.scale(24000, new double[]{1, 2}));
        assertArrayEquals(LavaCycle.scale(24000, LavaCycle.DEFAULT_SHARES), LavaCycle.scale(24000, new double[]{1, -1, 1, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> LavaCycle.scale(0, LavaCycle.DEFAULT_SHARES));
    }

    @Test
    void phasesOfNoLengthAreSkipped() {
        // no warning and no full phase: calm straight into the rise, the rise straight into the fall
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
            // the whole rise is the full lava's first tick (the last climb's end already has every row under)
            if (h > 0 && h < RISE) assertNotEquals(h, STD.level(t - 1), "the first tick at height " + h);
        }
        assertEquals(Phase.CALM, STD.at(STD.tickForLevel(0)).phase());
        assertEquals(Phase.FULL, STD.at(STD.tickForLevel(RISE)).phase());
        assertEquals(Phase.FULL, STD.at(STD.tickForLevel(RISE + 5)).phase());
        // a rise too short for every height: the nearest height under it, from the rise or the fall
        LavaCycle quick = new LavaCycle(new int[]{100, 10, 20, 10, 20}, RISE);
        for (int h = 0; h <= RISE; h++) {
            int got = quick.level(quick.tickForLevel(h));
            assertTrue(got <= h && got >= h - 3, "height " + h + " in a 20-tick rise: " + got);
        }
    }

    @Test
    void heightsAreFoundInTheFallWhenTheRiseIsEmpty() {
        // a config may give the rise (or the rise and the full phase) no share: /remix lava level must still find each
        // height, in the fall
        for (double[] shares : new double[][]{{13.5, 1.5, 0, 1, 2}, {13.5, 1.5, 0, 0, 2}}) {
            LavaCycle c = new LavaCycle(LavaCycle.scale(24000, shares), RISE);
            for (int h = 0; h <= RISE; h++) assertEquals(h, c.level(c.tickForLevel(h)), "height " + h + " with shares " + Arrays.toString(shares));
        }
        // a 1-tick rise skips every height between 0 and full; the fall has them
        LavaCycle blink = new LavaCycle(new int[]{17999, 2000, 1, 1333, 2667}, RISE);
        for (int h = 0; h <= RISE; h++) assertEquals(h, blink.level(blink.tickForLevel(h)), "height " + h + " after a 1-tick rise");
    }
}
