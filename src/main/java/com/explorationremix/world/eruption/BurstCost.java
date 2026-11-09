package com.explorationremix.world.eruption;

/**
 * What an eruption's burst costs the server (0.9.0, spec 5.6 and 5.7), for the status's "the last burst cost" line, kept
 * apart from the game so a test can follow the sums: the nanoseconds spent on it in each game tick (its launches and vent
 * burn, its bombs' flights and blasts) added up tick by tick, the mean over the ticks that counted anything and the most
 * any one of them cost. The tick under way counts as it stands. Server thread only.
 */
final class BurstCost {
    /** The game tick being counted (-1 before the first) and what it has cost so far, in nanoseconds. */
    private long tick = -1, now;
    /** The ticks counted before it: their sum, the most one of them cost, and how many there were. */
    private long all, most;
    private int ticks;

    /** Counts {@code nanos} of the server's time in game tick {@code gameTick}. */
    void add(long gameTick, long nanos) {
        if (gameTick != tick) {
            if (tick >= 0) {
                all += now;
                most = Math.max(most, now);
                ticks++;
            }
            tick = gameTick;
            now = 0;
        }
        now += nanos;
    }

    /** The mean a tick over the ticks that counted anything, the one under way included, in milliseconds (0 before any). */
    double meanMs() { return tick < 0 ? 0 : (all + now) / 1.0e6 / (ticks + 1); }

    /** The most one tick has cost, the one under way included, in milliseconds. */
    double mostMs() { return Math.max(most, now) / 1.0e6; }
}
