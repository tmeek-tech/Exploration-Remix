package com.explorationremix.world.lava;

import com.explorationremix.world.lava.LavaCycle.Phase;
import com.explorationremix.world.terrain.Terrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The lava cycle's clock: game time to cycle tick, roll-overs, holding it, setting it and changing its length. */
class LavaClockTest {
    private static final int RISE = Terrain.FLOOD_RISE;
    private static final int[] STD = LavaCycle.scale(LavaCycle.DEFAULT_LENGTH, LavaCycle.DEFAULT_SHARES);
    /** A 2-minute cycle with the default shares. */
    private static final int[] SHORT = LavaCycle.scale(2400, LavaCycle.DEFAULT_SHARES);

    @Test
    void theClockCountsGameTimeFromTheStartOfItsCycle() {
        LavaClock c = new LavaClock(1000, STD);
        assertEquals(0, c.tick(1000));
        assertEquals(500, c.tick(1500));
        assertFalse(c.advance(1000 + 23999, STD));
        assertEquals(23999, c.tick(1000 + 23999));
        assertEquals(0, c.cycles());
        // a world already far on when the cycle first starts begins calm all the same, with its whole calm ahead
        LavaClock late = new LavaClock(5_000_000L, STD);
        LavaCycle.State s = late.cycle(RISE).at(late.tick(5_000_000L));
        assertEquals(Phase.CALM, s.phase());
        assertEquals(16200, s.left());
    }

    @Test
    void cyclesRollOverWhereTheLastEnded() {
        LavaClock c = new LavaClock(1000, STD);
        assertTrue(c.advance(1000 + 24000, STD), "a new cycle is saved");
        assertEquals(1, c.cycles());
        assertEquals(1000 + 24000, c.cycleStart);
        assertEquals(0, c.tick(1000 + 24000));
        assertFalse(c.advance(1000 + 24001, STD), "nothing to save in the middle of a cycle");
        // ticking through ten cycles one tick at a time counts each once
        long now = 1000 + 24000;
        int rollovers = 0;
        for (int k = 0; k < 10 * 24000; k++) if (c.advance(++now, STD)) rollovers++;
        assertEquals(10, rollovers);
        assertEquals(11, c.cycles());
        assertEquals(0, c.tick(now));
    }

    @Test
    void aChangedLengthWaitsForTheNextCycle() {
        LavaClock c = new LavaClock(0, STD);
        assertFalse(c.advance(100, SHORT));
        assertArrayEquals(STD, c.lengths(), "the running cycle keeps its lengths");
        assertTrue(c.advance(24000, SHORT));
        assertArrayEquals(SHORT, c.lengths());
        assertEquals(2400, c.cycle(RISE).length());
        assertEquals(0, c.tick(24000));
        assertEquals(100, c.tick(24100));
        // several short cycles gone by at once (saved data older than the level): the clock lands in the right one
        assertTrue(c.advance(24000 + 2400 * 3 + 50, SHORT));
        assertEquals(4, c.cycles());
        assertEquals(50, c.tick(24000 + 2400 * 3 + 50));
    }

    @Test
    void gameTimeGoingBackwardsStartsTheCycleOver() {
        LavaClock c = new LavaClock(50_000, STD);
        assertTrue(c.advance(40_000, STD));
        assertEquals(0, c.tick(40_000));
        assertEquals(10, c.tick(40_010));
        assertEquals(0, c.cycles());
    }

    @Test
    void aHeldClockKeepsItsTickAndRunsOnFromThere() {
        LavaClock c = new LavaClock(0, STD);
        c.freeze(18600);
        assertTrue(c.frozen());
        assertEquals(18600, c.tick(18600));
        assertFalse(c.advance(200_000, STD), "a held clock never rolls over");
        assertEquals(18600, c.tick(200_000));
        assertEquals(0, c.cycles());
        c.unfreeze(200_000);
        assertFalse(c.frozen());
        assertEquals(18600, c.tick(200_000));
        assertEquals(18601, c.tick(200_001));
        // holding twice keeps the first place; letting a running clock go changes nothing
        c.freeze(200_100);
        c.freeze(300_000);
        assertEquals(18700, c.tick(300_000));
        c.unfreeze(300_000);
        c.unfreeze(300_500);
        assertEquals(19200, c.tick(300_500));
    }

    @Test
    void settingTheClockMovesItOrMovesWhereItIsHeld() {
        LavaClock c = new LavaClock(0, STD);
        LavaCycle cycle = c.cycle(RISE);
        c.set(5000, cycle.tickOf(Phase.WARNING, 200));
        assertEquals(16400, c.tick(5000));
        assertEquals(Phase.WARNING, cycle.at(c.tick(5000)).phase());
        assertEquals(16410, c.tick(5010));
        // the next roll-over comes a whole cycle after the start the set implies
        assertFalse(c.advance(5000 + 24000 - 16400 - 1, STD));
        assertTrue(c.advance(5000 + 24000 - 16400, STD));
        assertEquals(0, c.tick(5000 + 24000 - 16400));
        // held, a set moves the held tick (wrapped round the cycle) and the clock stays held
        c.freeze(20_000);
        c.set(20_000, -1);
        assertTrue(c.frozen());
        assertEquals(23999, c.tick(99_999));
        assertEquals(1, cycle.at(c.tick(99_999)).level(), "the last tick of the drain");
    }

    @Test
    void aNewLengthKeepsThePlaceInThePhase() {
        LavaClock c = new LavaClock(0, STD);
        long now = 18000 + 600;                         // a quarter of the way through the 2-minute rise
        c.rescale(now, SHORT, 2400);
        assertArrayEquals(SHORT, c.lengths());
        assertEquals(2400, c.lengthOverride());
        LavaCycle.State s = c.cycle(RISE).at(c.tick(now));
        assertEquals(Phase.RISE, s.phase());
        assertEquals(60, s.into(), "a quarter of the 12-second rise");
        assertEquals(10, s.level());
        assertEquals(61, c.cycle(RISE).at(c.tick(now + 1)).into(), "and it runs on");
        // held, the place is kept in the held tick, and the clock stays held; 0 goes back to the config's length
        c.freeze(now);
        c.rescale(now + 1000, STD, 0);
        assertTrue(c.frozen());
        s = c.cycle(RISE).at(c.tick(now + 5000));
        assertEquals(Phase.RISE, s.phase());
        assertEquals(600, s.into());
        assertEquals(0, c.lengthOverride());
    }

    @Test
    void aClockReadBackFromBadDataFallsBackOnTheGivenLengths() {
        // a missing lengths array reads back as an empty one
        for (int[] bad : new int[][]{{}, {1, 2}, {0, 0, 0, 0, 0}, {100, -1, 100, 100, 100}}) {
            LavaClock c = new LavaClock(100, bad, true, 30_000, -5, -1, STD);
            assertArrayEquals(STD, c.lengths());
            assertEquals(6000, c.tick(0), "the held tick is wrapped round the cycle");
            assertEquals(0, c.cycles());
            assertEquals(0, c.lengthOverride());
        }
        LavaClock ok = new LavaClock(100, SHORT, false, 0, 7, 2400, STD);
        assertArrayEquals(SHORT, ok.lengths());
        assertEquals(7, ok.cycles());
        assertEquals(2400, ok.lengthOverride());
        assertEquals(50, ok.tick(150));
    }
}
