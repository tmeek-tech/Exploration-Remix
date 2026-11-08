package com.explorationremix.world.eruption;

import com.explorationremix.world.lava.LavaCycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When the Caldera erupts (0.9.0, spec 5.2): the gaps by the lava's phase, the wake as the lava rises, a Caldera's first
 * eruption, the phases with none, the alternatives of spec 1.3 over the cycle's real phase lengths, the vents' weights
 * and the woken scale.
 */
class EruptionScheduleTest {
    /** The pick's numbers: 3 to 7 minutes calm, 1 to 2 rising, waking within 40 s, a 4 s warning, a 10 s burst. */
    static final EruptionSchedule.Settings PICK = new EruptionSchedule.Settings(3, 7, 1, 2, 40, 4, 10, 48, 1.5);
    /** "One huge eruption per cycle as the lava rises". */
    static final EruptionSchedule.Settings ONE = new EruptionSchedule.Settings(0, 7, 3, 3, 20, 4, 30, 48, 2.0);
    /** "Frequent and small". */
    static final EruptionSchedule.Settings FREQUENT = new EruptionSchedule.Settings(1, 2, 0.5, 1, 40, 4, 6, 48, 1.0);

    /** The cycle of the pick: 20 minutes of calm 16200, warning 1800, rise 2400, full 1200 and drain 2400 ticks. */
    static final LavaCycle CYCLE = LavaCycle.standard(40);

    @Test
    void theCycleIsTheOneTheSpecCounts() {
        assertArrayEquals(new int[]{16200, 1800, 2400, 1200, 2400}, CYCLE.lengths());
    }

    @Test
    void gapsFollowThePhase() {
        EruptionSchedule s = new EruptionSchedule(1);
        for (int phase = EruptionSchedule.CALM; phase <= EruptionSchedule.DRAIN; phase++) {
            long lo = Long.MAX_VALUE, hi = 0;
            for (int i = 0; i < 10_000; i++) {
                long g = s.gap(phase, PICK);
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
            boolean rising = phase == EruptionSchedule.RISE || phase == EruptionSchedule.FULL;
            // warning and drain use the calm gaps
            long min = rising ? 1200 : 3600, max = rising ? 2400 : 8400;
            assertTrue(lo >= min && hi <= max, "phase " + phase + ": " + lo + ".." + hi);
            assertTrue(lo < min + 60 && hi > max - 60, "phase " + phase + " spans its bounds: " + lo + ".." + hi);
        }
        // the larger bound counts as at least the smaller
        EruptionSchedule.Settings odd = new EruptionSchedule.Settings(5, 2, 1, 2, 40, 4, 10, 48, 1.5);
        for (int i = 0; i < 100; i++) assertEquals(6000, s.gap(EruptionSchedule.CALM, odd));
    }

    @Test
    void aCalderaFirstSeenEruptsWithinAGap() {
        EruptionSchedule s = new EruptionSchedule(2);
        long lo = Long.MAX_VALUE, hi = 0;
        for (int i = 0; i < 10_000; i++) {
            EruptionSchedule.Clock c = s.firstSeen(1000, EruptionSchedule.CALM, PICK);
            lo = Math.min(lo, c.nextAt() - 1000);
            hi = Math.max(hi, c.nextAt() - 1000);
        }
        // 0.3 to 1 of a 3 to 7 minute gap: 1080 to 8400 ticks, not at once
        assertTrue(lo >= 1080 && hi <= 8400, lo + ".." + hi);
        assertTrue(lo < 1300 && hi > 8000, lo + ".." + hi);
        // seen while rising: 0.3 to 1 of a 1 to 2 minute gap
        EruptionSchedule.Clock c = s.firstSeen(0, EruptionSchedule.FULL, PICK);
        assertTrue(c.nextAt() >= 360 && c.nextAt() <= 2400, String.valueOf(c.nextAt()));
        // seen in a phase with none: stopped
        assertEquals(EruptionSchedule.NEVER, s.firstSeen(0, EruptionSchedule.CALM, ONE).nextAt());
    }

    @Test
    void theRiseWakesTheCaldera() {
        EruptionSchedule s = new EruptionSchedule(3);
        int soon = 0;
        for (int i = 0; i < 2000; i++) {
            EruptionSchedule.Clock c = s.firstSeen(0, EruptionSchedule.WARNING, PICK);
            long was = c.nextAt();
            s.phase(c, EruptionSchedule.RISE, 500, PICK);
            assertTrue(c.nextAt() <= 500 + 800, "within 40 s of the rise: " + c.nextAt());
            assertTrue(c.nextAt() <= was, "never later than it was due");
            if (was < 1300) soon++;
        }
        assertTrue(soon > 0);
        // the rise wakes a Caldera that slept through the calm (none then) as well
        EruptionSchedule.Clock c = s.firstSeen(0, EruptionSchedule.CALM, ONE);
        s.phase(c, EruptionSchedule.WARNING, 100, ONE);
        assertEquals(EruptionSchedule.NEVER, c.nextAt());
        s.phase(c, EruptionSchedule.RISE, 200, ONE);
        assertTrue(c.nextAt() >= 200 && c.nextAt() <= 600, String.valueOf(c.nextAt()));
        // a phase with none stops the clock, and the same phase again changes nothing
        s.phase(c, EruptionSchedule.DRAIN, 4000, ONE);
        assertEquals(EruptionSchedule.NEVER, c.nextAt());
        s.phase(c, EruptionSchedule.DRAIN, 4001, ONE);
        assertEquals(EruptionSchedule.NEVER, c.nextAt());
    }

    @Test
    void noneInTheCalmWithMinMinutesZero() {
        EruptionSchedule.Settings none = new EruptionSchedule.Settings(0, 7, 1, 2, 40, 4, 10, 48, 1.5);
        assertEquals(0, run(new EruptionSchedule(4), none, 50, EruptionSchedule.CALM));
        assertTrue(run(new EruptionSchedule(4), none, 50, EruptionSchedule.RISE) > 0);
        EruptionSchedule.Settings noneRising = new EruptionSchedule.Settings(3, 7, 0, 2, 40, 4, 10, 48, 1.5);
        assertEquals(0, run(new EruptionSchedule(4), noneRising, 50, EruptionSchedule.RISE) + run(new EruptionSchedule(4), noneRising, 50, EruptionSchedule.FULL));
    }

    @Test
    void thePickOverCycles() {
        // 3-4 eruptions over the 17 calm minutes (calm, warning, drain) and 2-3 over the rise and the full lava, on average
        int[] perPhase = new int[5];
        int cycles = 200;
        runCycles(new EruptionSchedule(5), PICK, cycles, perPhase, null);
        double calm = (perPhase[0] + perPhase[1] + perPhase[4]) / (double) cycles, rising = (perPhase[2] + perPhase[3]) / (double) cycles;
        assertTrue(calm > 3.0 && calm < 4.5, "calm " + calm);
        assertTrue(rising > 2.0 && rising < 3.5, "rising " + rising);
    }

    @Test
    void oneHugeEruptionPerCycle() {
        // exactly one per Caldera per cycle over 50 cycles, as the lava rises
        int[] perCycle = new int[50];
        int[] perPhase = new int[5];
        runCycles(new EruptionSchedule(6), ONE, 50, perPhase, perCycle);
        for (int i = 0; i < 50; i++) assertEquals(1, perCycle[i], "cycle " + i);
        assertEquals(50, perPhase[EruptionSchedule.RISE], "each in the rise's first 20 s");
    }

    @Test
    void frequentAndSmall() {
        EruptionSchedule s = new EruptionSchedule(7);
        for (int i = 0; i < 2000; i++) {
            long g = s.gap(EruptionSchedule.CALM, FREQUENT), r = s.gap(EruptionSchedule.FULL, FREQUENT);
            assertTrue(g >= 1200 && g <= 2400, String.valueOf(g));
            assertTrue(r >= 600 && r <= 1200, String.valueOf(r));
        }
        int[] perPhase = new int[5];
        runCycles(new EruptionSchedule(8), FREQUENT, 50, perPhase, null);
        double perCycle = (perPhase[0] + perPhase[1] + perPhase[2] + perPhase[3] + perPhase[4]) / 50.0;
        // 17 calm minutes at 1.5 and 3 rising minutes at 0.75: about 11 + 4
        assertTrue(perCycle > 12 && perCycle < 18, String.valueOf(perCycle));
    }

    @Test
    void ventsNearerThePlayersEruptMoreOften() {
        assertEquals(1, EruptionSchedule.weight(0, 48), 1e-12);
        assertEquals(0.5, EruptionSchedule.weight(48, 48), 1e-12);
        assertEquals(0.2, EruptionSchedule.weight(96, 48), 1e-12);
        assertEquals(1, EruptionSchedule.weight(150, 0), 1e-12);
        EruptionSchedule s = new EruptionSchedule(9);
        double[] d = {0, 48, 96};
        int[] n = new int[3];
        for (int i = 0; i < 100_000; i++) n[s.pick(d, 48)]++;
        // 1 : 0.5 : 0.2 of 1.7
        assertEquals(100_000 / 1.7, n[0], 1000);
        assertEquals(50_000 / 1.7, n[1], 1000);
        assertEquals(20_000 / 1.7, n[2], 1000);
        int[] u = new int[3];
        for (int i = 0; i < 90_000; i++) u[s.pick(d, 0)]++;
        for (int k = 0; k < 3; k++) assertEquals(30_000, u[k], 900, "uniform with bias 0");
        assertEquals(-1, s.pick(new double[0], 48));
    }

    @Test
    void theWokenShowIsBigger() {
        assertEquals(1F, EruptionSchedule.scale(EruptionSchedule.CALM, 1.5));
        assertEquals(1F, EruptionSchedule.scale(EruptionSchedule.WARNING, 1.5));
        assertEquals(1.5F, EruptionSchedule.scale(EruptionSchedule.RISE, 1.5));
        assertEquals(1.5F, EruptionSchedule.scale(EruptionSchedule.FULL, 1.5));
        assertEquals(1F, EruptionSchedule.scale(EruptionSchedule.DRAIN, 1.5));
    }

    @Test
    void theEruptionsOwnDraws() {
        EruptionSchedule s = new EruptionSchedule(10);
        assertEquals(80, EruptionSchedule.pre(PICK));
        for (int i = 0; i < 5000; i++) {
            float st = s.drawStrength();
            int b = s.drawBurst(PICK);
            assertTrue(st >= 0.8F && st <= 1.2F);
            assertTrue(b >= 150 && b <= 250, String.valueOf(b));
        }
        // seeded and repeatable
        EruptionSchedule a = new EruptionSchedule(42), b = new EruptionSchedule(42);
        for (int i = 0; i < 100; i++) {
            assertEquals(a.gap(EruptionSchedule.CALM, PICK), b.gap(EruptionSchedule.CALM, PICK));
            assertEquals(a.drawSeed(), b.drawSeed());
        }
    }

    /** Eruptions in {@code cycles} cycles' worth of ticks spent in one phase only, the Caldera seen at tick 0. */
    private static int run(EruptionSchedule s, EruptionSchedule.Settings set, int cycles, int phase) {
        EruptionSchedule.Clock c = s.firstSeen(0, phase, set);
        int n = 0;
        for (long now = 0; now < (long) cycles * CYCLE.length(); now++) {
            s.phase(c, phase, now, set);
            if (EruptionSchedule.due(c, now)) {
                n++;
                s.started(c, now, set);
            }
        }
        return n;
    }

    /**
     * Runs one Caldera through {@code cycles} cycles of the real phase lengths, seen at tick 0 in the calm, counting its
     * eruptions by the phase they start in and (if given) by cycle.
     */
    private static void runCycles(EruptionSchedule s, EruptionSchedule.Settings set, int cycles, int[] perPhase, int[] perCycle) {
        EruptionSchedule.Clock c = s.firstSeen(0, EruptionSchedule.CALM, set);
        int len = CYCLE.length();
        for (long now = 0; now < (long) cycles * len; now++) {
            int phase = CYCLE.at(now).phase().ordinal();
            s.phase(c, phase, now, set);
            if (EruptionSchedule.due(c, now)) {
                perPhase[phase]++;
                if (perCycle != null) perCycle[(int) (now / len)]++;
                s.started(c, now, set);
                if (perCycle != null && phase == EruptionSchedule.RISE) assertTrue(CYCLE.at(now).into() <= 400, "within 20 s of the rise");
            }
        }
    }
}
