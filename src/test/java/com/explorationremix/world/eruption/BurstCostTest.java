package com.explorationremix.world.eruption;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a burst costs the server, as the status gives it (BurstCost): what is spent in one game tick is added up as one
 * tick, the mean is over the ticks that counted anything with the one under way among them, and the most is the dearest
 * single tick.
 */
class BurstCostTest {
    private static final long MS = 1_000_000;

    @Test
    void nothingBeforeTheFirstTick() {
        BurstCost c = new BurstCost();
        assertEquals(0, c.meanMs(), 0);
        assertEquals(0, c.mostMs(), 0);
    }

    @Test
    void oneTickAddsUpItsLaunchesAndItsBombs() {
        BurstCost c = new BurstCost();
        // the launch and burn, then three bombs' ticks, all in game tick 100
        c.add(100, MS / 2);
        c.add(100, MS);
        c.add(100, MS);
        c.add(100, MS / 2);
        assertEquals(3, c.meanMs(), 1e-9);
        assertEquals(3, c.mostMs(), 1e-9);
    }

    @Test
    void theMeanIsOverTheTicksThatCountedAndTheMostIsTheDearestTick() {
        BurstCost c = new BurstCost();
        c.add(10, 2 * MS);
        c.add(11, MS);
        c.add(11, 3 * MS);
        // ticks 12 to 14 count nothing (no bomb in the air, the burst held): they are not in the mean
        c.add(15, 3 * MS);
        assertEquals((2 + 4 + 3) / 3.0, c.meanMs(), 1e-9);
        assertEquals(4, c.mostMs(), 1e-9);
        // the tick under way counts as it stands, and grows as more of it is spent
        c.add(15, 2 * MS);
        assertEquals((2 + 4 + 5) / 3.0, c.meanMs(), 1e-9);
        assertEquals(5, c.mostMs(), 1e-9);
    }
}
