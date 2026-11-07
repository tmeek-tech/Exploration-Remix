package com.explorationremix.world.quake;

import com.explorationremix.world.lava.LavaCycle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When the quakes come (0.9.0, spec 4.1): the tremors' and the big quakes' gaps, tremors kept clear of big quakes, the
 * quiet window of the lava's warning, the gates that skip or hold them, and the same quakes from the same seed.
 */
class QuakeScheduleTest {
    /** The pick's numbers: tremors every 30 to 60 s, big quakes every 3 to 7 minutes lasting 10 s. */
    private static final QuakeSchedule.Settings PICK = new QuakeSchedule.Settings(true, 30, 60, 3, 7, 10);

    /** A quake the schedule started, and when. */
    private record Got(long at, QuakeSchedule.Start start) {
        long end() { return at + start.duration(); }
    }

    /**
     * Runs a schedule from tick 0 until {@code count} quakes of {@code kind} have started (or {@code until}), visiting
     * each tick something is due at (and the next tick after a start, as the game does), with no quiet window, nothing
     * held and players there.
     */
    private static List<Got> run(QuakeSchedule q, QuakeSchedule.Settings s, int kind, int count, long until) {
        List<Got> out = new ArrayList<>();
        long now = 0;
        int n = 0;
        while (n < count && now < until) {
            QuakeSchedule.Start st = q.tick(now, s, 0, false, true);
            if (st != null) {
                out.add(new Got(now, st));
                if (st.kind() == kind) n++;
            }
            long next = s.tremors() ? Math.min(q.nextTremor(), q.nextBig()) : q.nextBig();
            now = Math.max(now + 1, next);
        }
        return out;
    }

    @Test
    void tremorGapsAndBigGapsAreThePicks() {
        // tremors alone (the big quakes four hours off): 30 to 60 s apart, 30 to 50 ticks long, strength 0.15 to 0.35
        QuakeSchedule q = new QuakeSchedule(1);
        List<Got> got = run(q, new QuakeSchedule.Settings(true, 30, 60, 240, 240, 10), QuakeSchedule.TREMOR, 10_000, Long.MAX_VALUE);
        long min = Long.MAX_VALUE, max = 0;
        Got prev = null;
        for (Got g : got) {
            if (g.start().kind() != QuakeSchedule.TREMOR) {
                // a big quake puts the tremors around it off: the gap across it is not a tremor gap
                prev = null;
                continue;
            }
            assertTrue(g.start().duration() >= 30 && g.start().duration() <= 50);
            assertTrue(g.start().strength() >= 0.15F && g.start().strength() <= 0.35F);
            assertFalse(g.start().silent());
            if (prev != null) {
                long gap = g.at() - prev.at();
                min = Math.min(min, gap);
                max = Math.max(max, gap);
            }
            prev = g;
        }
        assertTrue(min >= 600 && max <= 1200, "tremor gaps " + min + " to " + max + " ticks");
        assertTrue(min < 610 && max > 1190, "the gaps fill the range: " + min + " to " + max);
        // big quakes alone: 3 to 7 minutes from one's end to the next's start, 7.5 to 12.5 s long, strength 0.5 to 1
        q = new QuakeSchedule(2);
        got = run(q, new QuakeSchedule.Settings(false, 30, 60, 3, 7, 10), QuakeSchedule.BIG, 10_000, Long.MAX_VALUE);
        assertEquals(10_000, got.size());
        min = Long.MAX_VALUE;
        max = 0;
        for (int i = 0; i < got.size(); i++) {
            QuakeSchedule.Start s = got.get(i).start();
            assertEquals(QuakeSchedule.BIG, s.kind());
            assertTrue(s.duration() >= 150 && s.duration() <= 250, "duration " + s.duration());
            assertTrue(s.strength() >= 0.5F && s.strength() <= 1F);
            if (i > 0) {
                long gap = got.get(i).at() - got.get(i - 1).end();
                min = Math.min(min, gap);
                max = Math.max(max, gap);
            }
        }
        assertTrue(min >= 3600 && max <= 8400, "big gaps " + min + " to " + max + " ticks");
        assertTrue(min < 3700 && max > 8300, "the gaps fill the range: " + min + " to " + max);
        // the larger bound counts as at least the smaller
        q = new QuakeSchedule(3);
        got = run(q, new QuakeSchedule.Settings(false, 30, 60, 5, 1, 10), QuakeSchedule.BIG, 50, Long.MAX_VALUE);
        for (int i = 1; i < got.size(); i++) assertEquals(6000, got.get(i).at() - got.get(i - 1).end());
    }

    @Test
    void noTremorComesNearABigQuake() {
        QuakeSchedule q = new QuakeSchedule(4);
        List<Got> got = run(q, PICK, QuakeSchedule.BIG, 400, Long.MAX_VALUE);
        List<Got> bigs = new ArrayList<>(), tremors = new ArrayList<>();
        for (Got g : got) (g.start().kind() == QuakeSchedule.BIG ? bigs : tremors).add(g);
        assertTrue(tremors.size() > 2000);
        for (Got t : tremors) {
            for (Got b : bigs) {
                assertTrue(t.at() < b.at() - QuakeSchedule.AROUND_BIG || t.at() >= b.end() + QuakeSchedule.AROUND_BIG,
                        "a tremor at " + t.at() + " by a big quake from " + b.at() + " to " + b.end());
            }
        }
        // the tremors still come: none of the gaps outside a big quake's reach is longer than the pick's
        for (int i = 1; i < tremors.size(); i++) {
            long a = tremors.get(i - 1).at(), b = tremors.get(i).at();
            boolean big = false;
            for (Got g : bigs) if (g.at() < b + QuakeSchedule.AROUND_BIG && g.end() + QuakeSchedule.AROUND_BIG > a) big = true;
            if (!big) assertTrue(b - a <= 1200, "a gap of " + (b - a));
        }
    }

    @Test
    void withNobodyThereQuakesAreSkipped() {
        QuakeSchedule q = new QuakeSchedule(5);
        long firstBig = -1;
        for (long now = 0; now < 200_000; now++) {
            assertNull(q.tick(now, PICK, 0, false, false));
            if (now == 0) firstBig = q.nextBig();
        }
        assertTrue(q.nextBig() > 200_000 - 8400 && q.nextBig() != firstBig, "skipped quakes are drawn again, not left due");
        assertTrue(q.nextTremor() > 200_000 - 1200);
        // a player comes: the next one due starts as usual
        long now = 200_000;
        QuakeSchedule.Start s = null;
        while (s == null) s = q.tick(now++, PICK, 0, false, true);
        assertTrue(now - 200_000 <= 8400);
    }

    @Test
    void nothingStartsWhileHeldAndTheDueOneStartsAtTheRelease() {
        QuakeSchedule q = new QuakeSchedule(6);
        for (long now = 0; now < 20_000; now++) assertNull(q.tick(now, PICK, 0, true, true));
        assertTrue(q.nextBig() < 20_000, "the clock moved on: the big quake fell due while held");
        QuakeSchedule.Start s = q.tick(20_000, PICK, 0, false, true);
        assertNotNull(s);
        assertEquals(QuakeSchedule.BIG, s.kind(), "the big quake due starts at the release");
        // the tremor that fell due too keeps clear of it
        long end = 20_000 + s.duration();
        assertTrue(q.nextTremor() >= end + QuakeSchedule.AROUND_BIG);
        for (long now = 20_001; now < end + QuakeSchedule.AROUND_BIG; now++) assertNull(q.tick(now, PICK, 0, false, true));
    }

    @Test
    void tremorsOff() {
        QuakeSchedule q = new QuakeSchedule(7);
        List<Got> got = run(q, new QuakeSchedule.Settings(false, 30, 60, 3, 7, 10), QuakeSchedule.BIG, 100, Long.MAX_VALUE);
        assertEquals(100, got.size());
        for (Got g : got) assertEquals(QuakeSchedule.BIG, g.start().kind());
    }

    @Test
    void theLavasWarningIsKeptQuiet() {
        // the window: the whole warning, then the first 600 ticks of the rise; none with the cycle off
        assertEquals(100 + 600, QuakeSchedule.quietLeft(true, true, false, 1700, 100));
        assertEquals(600, QuakeSchedule.quietLeft(true, false, true, 0, 2400));
        assertEquals(1, QuakeSchedule.quietLeft(true, false, true, 599, 1801));
        assertEquals(0, QuakeSchedule.quietLeft(true, false, true, 600, 1800));
        assertEquals(0, QuakeSchedule.quietLeft(true, false, false, 0, 1200), "full");
        assertEquals(0, QuakeSchedule.quietLeft(false, true, false, 10, 100), "the cycle off: no window");
        assertEquals(0, QuakeSchedule.quietLeft(false, false, true, 10, 100));
        // Taran's cycle, run for twenty cycles: no big quake starts in the window, and one that fell due in it comes 200
        // to 600 ticks after the window ends; a tremor that falls in it is silent, and only then
        LavaCycle cycle = LavaCycle.standard(40);
        QuakeSchedule q = new QuakeSchedule(8);
        int putOff = 0, silent = 0, loud = 0;
        long dueInWindow = -1, windowEnd = -1;
        for (long now = 0; now < 20L * cycle.length(); now++) {
            LavaCycle.State st = cycle.at(now);
            int quiet = QuakeSchedule.quietLeft(true, st.phase() == LavaCycle.Phase.WARNING, st.phase() == LavaCycle.Phase.RISE, st.into(), st.left());
            if (quiet > 0 && q.begun() && q.nextBig() <= now && dueInWindow < 0) {
                dueInWindow = now;
                windowEnd = now + quiet;
            }
            QuakeSchedule.Start s = q.tick(now, PICK, quiet, false, true);
            if (s == null) continue;
            if (s.kind() == QuakeSchedule.BIG) {
                assertEquals(0, quiet, "a big quake in the window at " + now);
                if (dueInWindow >= 0) {
                    assertTrue(now >= windowEnd + 200 && now <= windowEnd + 600, "put off to " + (now - windowEnd) + " after the window");
                    putOff++;
                    dueInWindow = -1;
                }
            } else {
                assertEquals(quiet > 0, s.silent(), "a tremor at " + now);
                if (s.silent()) silent++;
                else loud++;
            }
        }
        assertTrue(putOff > 0, "some big quakes fell due in the window");
        assertTrue(silent > 0 && loud > silent);
    }

    @Test
    void theSameSeedGivesTheSameQuakes() {
        List<Got> a = run(new QuakeSchedule(42), PICK, QuakeSchedule.BIG, 50, Long.MAX_VALUE);
        List<Got> b = run(new QuakeSchedule(42), PICK, QuakeSchedule.BIG, 50, Long.MAX_VALUE);
        List<Got> c = run(new QuakeSchedule(43), PICK, QuakeSchedule.BIG, 50, Long.MAX_VALUE);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void aBigQuakeHeldAndReleasedKeepsItsRest() {
        // a big quake held by command (for a picture) runs on past the end the schedule drew it to. At the release Quakes
        // tells the schedule its moved start (bigStarted again), so the tremor and the big quake that fell due while it
        // was held do not start at the release, which would cut the rest of it short
        QuakeSchedule q = new QuakeSchedule(10);
        long now = 0;
        QuakeSchedule.Start big = null;
        while (big == null) {
            QuakeSchedule.Start s = q.tick(now, PICK, 0, false, true);
            if (s != null && s.kind() == QuakeSchedule.BIG) big = s;
            else now++;
        }
        long held = 40, release = now + held + 20_000;
        for (long t = now + 1; t < release; t++) assertNull(q.tick(t, PICK, 0, true, true));
        assertTrue(q.nextTremor() < release && q.nextBig() < release, "both fell due while it was held");
        long start = release - held;
        q.bigStarted(start, big.duration(), PICK);
        long end = start + big.duration();
        for (long t = release; t < end + QuakeSchedule.AROUND_BIG; t++) {
            assertNull(q.tick(t, PICK, 0, false, true), "at " + t + ": the released big quake runs to " + end);
        }
        assertTrue(q.nextBig() >= end + 3600 && q.nextBig() <= end + 8400, "the next big quake is drawn from its real end");
    }

    @Test
    void aBigQuakeFromACommandMovesTheSchedule() {
        QuakeSchedule q = new QuakeSchedule(9);
        assertNull(q.tick(0, PICK, 0, false, true));
        q.bigStarted(100, 200, PICK);
        assertTrue(q.nextBig() >= 300 + 3600 && q.nextBig() <= 300 + 8400, "the next big quake is drawn from its end");
        assertTrue(q.nextTremor() >= 300 + QuakeSchedule.AROUND_BIG);
    }
}
