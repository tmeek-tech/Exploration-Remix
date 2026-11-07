package com.explorationremix.creature;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Skein's cadence: bursts, freezes and snaps whose lengths never settle into a rhythm, outings of 8 to 30 blocks,
 * long rests between them, and the moods that change all that.
 */
class SkeinGaitTest {
    /** Blocks a tick at a speed of 1 (its movement speed attribute). */
    private static final double PER_TICK = 0.3;

    private static DoubleSupplier rng(long seed) {
        Random r = new Random(seed);
        return r::nextDouble;
    }

    /** What a long run of ticks looked like. */
    private static final class Run {
        int ticks, rest, burst, pivots, doubleBacks, left, right, twitches, longRuns, shortFreezes, longFreezes, freezes;
        double minTurn = 1e9, maxTurn = 0, minSpeed = 1e9, maxSpeed = 0;
        int minBurst = 1000, maxBurst = 0, minFreeze = 1000, maxFreeze = 0;
        java.util.List<Double> outings = new java.util.ArrayList<>();
    }

    /** Runs the gait for {@code n} ticks, the creature moving its speed whenever it bursts. */
    private static Run run(SkeinGait g, DoubleSupplier r, int n) {
        Run out = new Run();
        double outing = 0;
        boolean wasOut = g.outing();
        for (int i = 0; i < n; i++) {
            double moved = g.phase() == SkeinGait.Phase.BURST ? g.speed() * PER_TICK : 0.0;
            if (g.phase() == SkeinGait.Phase.BURST) outing += moved;
            boolean changed = g.tick(moved, r);
            out.ticks++;
            if (g.phase() == SkeinGait.Phase.REST) out.rest++;
            if (g.phase() == SkeinGait.Phase.BURST) out.burst++;
            if (changed && g.phase() == SkeinGait.Phase.PIVOT) {
                out.pivots++;
                double deg = Math.toDegrees(Math.abs(g.turn()));
                out.minTurn = Math.min(out.minTurn, deg);
                out.maxTurn = Math.max(out.maxTurn, deg);
                if (deg >= 150) out.doubleBacks++;
                if (g.turn() < 0) out.right++; else out.left++;
                out.minBurst = Math.min(out.minBurst, g.burstTicks());
                out.maxBurst = Math.max(out.maxBurst, g.burstTicks());
                if (g.burstTicks() <= 6) out.twitches++;
                if (g.burstTicks() >= 19) out.longRuns++;
                out.minSpeed = Math.min(out.minSpeed, g.speed());
                out.maxSpeed = Math.max(out.maxSpeed, g.speed());
            }
            if (changed && g.phase() == SkeinGait.Phase.FREEZE) {
                out.freezes++;
                out.minFreeze = Math.min(out.minFreeze, g.left());
                out.maxFreeze = Math.max(out.maxFreeze, g.left());
                if (g.left() <= 12) out.shortFreezes++;
                if (g.left() >= 29) out.longFreezes++;
            }
            if (wasOut && !g.outing()) { out.outings.add(outing); outing = 0; }
            wasOut = g.outing();
        }
        return out;
    }

    @Test
    void burstsAreTwitchesDashesAndNowAndThenALongRun() {
        SkeinGait g = new SkeinGait();
        g.rest(1);
        Run run = run(g, rng(1), 400_000);
        assertTrue(run.pivots > 2000);
        assertTrue(run.minBurst >= 3 && run.maxBurst <= 30, run.minBurst + ".." + run.maxBurst);
        assertTrue(run.twitches > run.pivots * 0.2, "twitches " + run.twitches);
        assertTrue(run.longRuns > run.pivots * 0.1, "long runs " + run.longRuns);
        // a sprint, or a little faster
        assertTrue(run.minSpeed >= 0.95 && run.maxSpeed <= 1.2 + 1.0E-9);
    }

    @Test
    void freezesAreMostlyShortButSomeLast() {
        SkeinGait g = new SkeinGait();
        g.rest(1);
        Run run = run(g, rng(2), 400_000);
        assertTrue(run.minFreeze >= 4 && run.maxFreeze <= 40, run.minFreeze + ".." + run.maxFreeze);
        assertTrue(run.shortFreezes > run.freezes * 0.4, "short " + run.shortFreezes + " of " + run.freezes);
        assertTrue(run.longFreezes > run.freezes * 0.05, "long " + run.longFreezes + " of " + run.freezes);
    }

    @Test
    void snapsTurnItSharplyEitherWayAndSometimesDoubleItBack() {
        SkeinGait g = new SkeinGait();
        g.rest(1);
        Run run = run(g, rng(3), 400_000);
        assertTrue(run.minTurn >= 30 - 1.0E-9 && run.maxTurn <= 200 + 1.0E-9, run.minTurn + ".." + run.maxTurn);
        double back = run.doubleBacks / (double) run.pivots;
        assertTrue(back > 0.1 && back < 0.3, "doubling back " + back);
        assertTrue(run.left > run.pivots * 0.4 && run.right > run.pivots * 0.4);
    }

    @Test
    void anOutingCoversEightToThirtyBlocks() {
        SkeinGait g = new SkeinGait();
        g.rest(1);
        Run run = run(g, rng(4), 400_000);
        assertTrue(run.outings.size() > 100);
        for (double d : run.outings) assertTrue(d >= 8.0 && d <= 30.0 + 0.4, "outing " + d);
    }

    @Test
    void itLiesStillMostOfTheTime() {
        SkeinGait g = new SkeinGait();
        g.rest(1);
        Run run = run(g, rng(5), 1_000_000);
        double rest = run.rest / (double) run.ticks, burst = run.burst / (double) run.ticks;
        assertTrue(rest > 0.65 && rest < 0.92, "resting " + rest);
        assertTrue(burst < 0.15, "running " + burst);
        // and the rests are long: tens of seconds to minutes
        SkeinGait h = new SkeinGait();
        DoubleSupplier r = rng(6);
        for (int i = 0; i < 1000; i++) {
            h.rest(r);
            assertTrue(h.left() >= 240 && h.left() <= 240 + 1800, "rest " + h.left());
        }
    }

    @Test
    void boltingIsFastWithBriefFreezes() {
        SkeinGait g = new SkeinGait();
        DoubleSupplier r = rng(8);
        for (int i = 0; i < 300; i++) {
            g.startOuting(SkeinGait.Mood.BOLT, 5.0, r);
            assertEquals(SkeinGait.Mood.BOLT, g.mood());
            assertTrue(g.speed() >= 1.2, "bolt speed " + g.speed());
            assertTrue(g.burstTicks() >= 10 && g.burstTicks() <= 30);
            assertTrue(Math.toDegrees(Math.abs(g.turn())) <= 50 + 1.0E-9, "a bolt only jitters off its way");
            int guard = 0;
            while (g.phase() != SkeinGait.Phase.FREEZE && guard++ < 100) g.tick(g.phase() == SkeinGait.Phase.BURST ? g.speed() * PER_TICK : 0, r);
            assertTrue(g.left() >= 3 && g.left() <= 10, "bolt freeze " + g.left());
        }
        // done with its short way, it rests only a little
        int guard = 0;
        while (g.outing() && guard++ < 1000) g.tick(g.phase() == SkeinGait.Phase.BURST ? g.speed() * PER_TICK : 0, r);
        assertFalse(g.outing());
        assertTrue(g.left() >= 100 && g.left() <= 400, "rest after a bolt " + g.left());
    }

    @Test
    void stalkingCreepsWithLongFreezes() {
        SkeinGait g = new SkeinGait();
        DoubleSupplier r = rng(9);
        g.startOuting(SkeinGait.Mood.STALK, 40.0, r);
        int freezes = 0;
        for (int i = 0; i < 20_000 && g.outing(); i++) {
            if (g.phase() == SkeinGait.Phase.PIVOT) {
                assertTrue(g.speed() < 0.8);
                assertTrue(g.burstTicks() <= 12);
                assertTrue(Math.toDegrees(Math.abs(g.turn())) <= 35 + 1.0E-9);
            }
            boolean changed = g.tick(g.phase() == SkeinGait.Phase.BURST ? g.speed() * PER_TICK : 0, r);
            if (changed && g.phase() == SkeinGait.Phase.FREEZE) {
                freezes++;
                assertTrue(g.left() >= 20 && g.left() <= 60, "stalk freeze " + g.left());
            }
        }
        assertTrue(freezes > 5);
    }

    @Test
    void aJourneyRunsLongAndStraightWithBriefFreezesThenRestsLong() {
        SkeinGait g = new SkeinGait();
        DoubleSupplier r = rng(11);
        int[] seen = new int[4];    // freezes, pauses among them, ticks running, ticks frozen
        for (int j = 0; j < 40; j++) {
            journey(g, r, seen);
            // there: a long rest, as after roaming
            assertFalse(g.outing());
            assertTrue(g.left() >= 240 && g.left() <= 240 + 1800, "rest after a journey " + g.left());
        }
        double share = seen[1] / (double) seen[0];
        assertTrue(seen[0] > 200, "freezes " + seen[0]);
        assertTrue(share > 0.12 && share < 0.3, "now and then a longer pause: " + seen[1] + " of " + seen[0]);
        assertTrue(seen[2] > 2 * seen[3], "mostly on the move: running " + seen[2] + ", frozen " + seen[3]);
    }

    /** One journey of 150 blocks, start to rest, checking each run and freeze and counting them into {@code seen}. */
    private static void journey(SkeinGait g, DoubleSupplier r, int[] seen) {
        g.startOuting(SkeinGait.Mood.JOURNEY, 150.0, r);
        assertEquals(SkeinGait.Mood.JOURNEY, g.mood());
        for (int i = 0; i < 50_000 && g.outing(); i++) {
            if (g.phase() == SkeinGait.Phase.PIVOT) {
                assertTrue(g.burstTicks() >= 24 && g.burstTicks() <= 50, "journey burst " + g.burstTicks());
                assertTrue(g.speed() >= 0.95 && g.speed() <= 1.1 + 1.0E-9, "journey speed " + g.speed());
                assertTrue(Math.toDegrees(Math.abs(g.turn())) <= 12 + 1.0E-9, "a journey only jitters off its way");
            }
            if (g.phase() == SkeinGait.Phase.BURST) seen[2]++;
            if (g.phase() == SkeinGait.Phase.FREEZE) seen[3]++;
            boolean changed = g.tick(g.phase() == SkeinGait.Phase.BURST ? g.speed() * PER_TICK : 0, r);
            if (changed && g.phase() == SkeinGait.Phase.FREEZE) {
                seen[0]++;
                assertTrue(g.left() >= 2 && g.left() <= 45, "journey freeze " + g.left());
                if (g.left() >= 20) seen[1]++;
            }
        }
    }

    @Test
    void burstsThatGetNowhereEndTheOuting() {
        SkeinGait g = new SkeinGait();
        DoubleSupplier r = rng(10);
        g.startOuting(SkeinGait.Mood.ROAM, 30.0, r);
        for (int i = 0; i < SkeinGait.GIVE_UP; i++) {
            while (g.phase() != SkeinGait.Phase.BURST) g.tick(0, r);
            g.endBurst(true, r);
            assertEquals(SkeinGait.Phase.FREEZE, g.phase());
            if (i < SkeinGait.GIVE_UP - 1) { while (g.phase() == SkeinGait.Phase.FREEZE) g.tick(0, r); }
        }
        while (g.phase() == SkeinGait.Phase.FREEZE) g.tick(0, r);
        assertEquals(SkeinGait.Phase.REST, g.phase());
        assertTrue(g.gaveUp(), "it rests because it got nowhere");
    }

    @Test
    void aStalkThatGetsNowhereGivesUpForAShortRest() {
        SkeinGait g = new SkeinGait();
        DoubleSupplier r = rng(12);
        for (int k = 0; k < 200; k++) {
            g.startOuting(SkeinGait.Mood.STALK, 40.0, r);
            assertFalse(g.gaveUp());
            int guard = 0;
            while (g.outing() && guard++ < 10_000) {
                // no way to the swarm: every burst ends where it began
                if (g.phase() == SkeinGait.Phase.BURST) g.endBurst(true, r);
                else g.tick(0, r);
            }
            assertFalse(g.outing());
            assertTrue(g.gaveUp(), "a stalk with nowhere to go gives up");
            assertTrue(g.left() >= 100 && g.left() <= 400, "rest after a stalk " + g.left());
        }
    }

    @Test
    void anOutingThatGoesItsWayOrIsToldToRestHasNotGivenUp() {
        SkeinGait g = new SkeinGait();
        DoubleSupplier r = rng(13);
        for (int k = 0; k < 200; k++) {
            g.startOuting(k % 2 == 0 ? SkeinGait.Mood.STALK : SkeinGait.Mood.ROAM, 5.0, r);
            int guard = 0;
            while (g.outing() && guard++ < 10_000) g.tick(g.phase() == SkeinGait.Phase.BURST ? g.speed() * PER_TICK : 0, r);
            assertFalse(g.outing());
            assertFalse(g.gaveUp(), "it went its way");
        }
        // it gives up, then is told to rest (or sets out again): that is no giving up
        g.startOuting(SkeinGait.Mood.STALK, 40.0, r);
        while (g.outing()) { if (g.phase() == SkeinGait.Phase.BURST) g.endBurst(true, r); else g.tick(0, r); }
        assertTrue(g.gaveUp());
        g.rest(50);
        assertFalse(g.gaveUp());
        g.startOuting(SkeinGait.Mood.STALK, 40.0, r);
        while (g.outing()) { if (g.phase() == SkeinGait.Phase.BURST) g.endBurst(true, r); else g.tick(0, r); }
        assertTrue(g.gaveUp());
        g.startOuting(SkeinGait.Mood.STALK, 40.0, r);
        assertFalse(g.gaveUp());
    }

    @Test
    void aBurstThatArrivesFreezesAtOnce() {
        SkeinGait g = new SkeinGait();
        DoubleSupplier r = rng(11);
        g.startOuting(SkeinGait.Mood.ROAM, 30.0, r);
        while (g.phase() != SkeinGait.Phase.BURST) g.tick(0, r);
        g.endBurst(false, r);
        assertEquals(SkeinGait.Phase.FREEZE, g.phase());
        assertTrue(g.still());
        while (g.phase() == SkeinGait.Phase.FREEZE) g.tick(0, r);
        assertEquals(SkeinGait.Phase.PIVOT, g.phase(), "the outing goes on");
    }
}
