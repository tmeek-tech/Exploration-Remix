package com.explorationremix.world.quake;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ground answering a climb (0.9.0, spec 4.3.7): when a placement brings an answer, the cooldown that puts one off
 * without losing it, the caps, and a player towering up at 2 blocks a second who never gets far.
 */
class ClimbAnswersTest {
    private static final UUID A = new UUID(1, 1), B = new UUID(2, 2);

    @Test
    void aPlacementThatFallsBringsOneAnswerSoon() {
        for (int seed = 0; seed < 200; seed++) {
            ClimbAnswers c = new ClimbAnswers(seed);
            ClimbAnswers.Answer a = c.placed(A, 77L, 1000, true, false, false);
            assertNotNull(a);
            assertTrue(a.due() >= 1000 + ClimbAnswers.MIN_DELAY && a.due() <= 1000 + ClimbAnswers.MAX_DELAY, "due " + a.due());
            assertTrue(a.duration() >= 30 && a.duration() <= 50);
            assertTrue(a.strength() >= 0.25F && a.strength() <= 0.4F);
            assertEquals(77L, a.pos());
            assertTrue(c.due(a.due() - 1).isEmpty(), "not before it is due");
            List<ClimbAnswers.Answer> got = c.due(a.due());
            assertEquals(List.of(a), got);
            assertEquals(0, c.pending());
        }
        // a block that stands brings none
        assertNull(new ClimbAnswers(1).placed(A, 77L, 1000, false, false, false));
    }

    @Test
    void oneAnswerAtATimeForEachPlacer() {
        ClimbAnswers c = new ClimbAnswers(3);
        assertNotNull(c.placed(A, 1L, 0, true, false, false));
        assertNull(c.placed(A, 2L, 5, true, false, false), "the pending one covers it: its find reads the new block");
        assertTrue(c.pendingFor(A));
        assertNotNull(c.placed(B, 3L, 5, true, false, false), "another placer has its own");
        assertEquals(2, c.pending());
    }

    @Test
    void theCooldownPutsAnAnswerOffButNeverLosesIt() {
        for (int seed = 0; seed < 100; seed++) {
            ClimbAnswers c = new ClimbAnswers(seed);
            ClimbAnswers.Answer first = c.placed(A, 1L, 0, true, false, false);
            assertNotNull(first);
            assertEquals(1, c.due(first.due()).size());
            long find = first.due();
            c.found(A, find);
            // placed again 10 ticks after the find: due no sooner than the cooldown's end
            ClimbAnswers.Answer next = c.placed(A, 2L, find + 10, true, false, false);
            assertNotNull(next, "put off, not lost");
            assertTrue(next.due() >= find + ClimbAnswers.COOLDOWN, "due " + next.due() + " after the find at " + find);
            assertTrue(next.due() <= Math.max(find + ClimbAnswers.COOLDOWN, find + 10 + ClimbAnswers.MAX_DELAY));
            // long after the cooldown the usual delay holds again
            assertEquals(1, c.due(next.due()).size());
            c.found(A, next.due());
            ClimbAnswers.Answer late = c.placed(A, 3L, next.due() + 500, true, false, false);
            assertNotNull(late);
            assertTrue(late.due() - (next.due() + 500) <= ClimbAnswers.MAX_DELAY);
        }
    }

    @Test
    void noneWhileAJobTakesTheColumnOrABigQuakeRuns() {
        ClimbAnswers c = new ClimbAnswers(4);
        assertNull(c.placed(A, 1L, 0, true, false, true), "a lowering job already takes that column down");
        assertNull(c.placed(A, 1L, 0, true, true, false), "a big quake takes the block anyway");
        assertEquals(0, c.pending());
    }

    @Test
    void atMostEightPending() {
        ClimbAnswers c = new ClimbAnswers(5);
        for (int i = 0; i < 8; i++) assertNotNull(c.placed(new UUID(9, i), i, 0, true, false, false));
        assertNull(c.placed(new UUID(9, 8), 8, 0, true, false, false), "a ninth placer waits for the next tremor's pass");
        assertEquals(ClimbAnswers.MAX_PENDING, c.pending());
        c.clear();
        assertEquals(0, c.pending());
    }

    @Test
    void theSameAnswersFromTheSameSeed() {
        ClimbAnswers a = new ClimbAnswers(42), b = new ClimbAnswers(42);
        for (int i = 0; i < 8; i++) {
            assertEquals(a.placed(new UUID(0, i), i, i * 7, true, false, false), b.placed(new UUID(0, i), i, i * 7, true, false, false));
        }
    }

    /**
     * A player towers up at 2 blocks a second (one every 10 ticks) on rock, standing on the top of the tower, and keeps
     * placing whatever happens: the answer's find takes the tower's blocks over 2, the column under them creaks and
     * then sinks a block every 6 ticks (spec 4.3.5) until it is down to 2, and they tower up again. The tower never
     * passes 13 blocks.
     */
    @Test
    void aTowerNeverGetsFar() {
        for (int seed = 0; seed < 300; seed++) {
            ClimbAnswers c = new ClimbAnswers(seed);
            Random r = new Random(seed);
            int height = 0, most = 0, answers = 0;
            long jobAt = -1, nextStep = -1;
            int offset = r.nextInt(10);
            for (long now = 0; now < 6000; now++) {
                // the lowering job: from 20 ticks after the creak, a block every 6 ticks while the block under the feet falls
                if (jobAt >= 0 && now >= jobAt && now >= nextStep) {
                    if (!Support.lowersOn(height > 2, true, 0)) {
                        jobAt = -1;
                    } else {
                        height--;
                        nextStep = now + Support.lowerTicks(6);
                    }
                }
                // a block placed under the feet every 10 ticks; the third over rock and up fall by the rule
                if ((now + offset) % 10 == 0) {
                    height++;
                    c.placed(A, 0L, now, height > 2, false, jobAt >= 0);
                }
                most = Math.max(most, height);
                for (ClimbAnswers.Answer a : c.due(now)) {
                    // the find is done within the tick: the column under the player creaks and comes down 20 ticks on
                    answers++;
                    c.found(a.placer(), now);
                    if (height > 2 && jobAt < 0) {
                        jobAt = now + Quakes.LOWER_AFTER;
                        nextStep = jobAt;
                    }
                }
            }
            assertTrue(answers > 10, "seed " + seed);
            assertTrue(most <= 13, "seed " + seed + ": the tower reached " + most);
        }
    }

    @Test
    void theAnswersArePure() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/explorationremix/world/quake/ClimbAnswers.java"));
        assertFalse(src.contains("import net.minecraft") || src.contains("import com.mojang") || src.contains("import net.minecraftforge"),
                "ClimbAnswers must stay free of game types: it is tested offline");
    }
}
