package com.explorationremix.client;

import java.util.PriorityQueue;

/**
 * How many puffs of volcanic smoke are alive, so the plumes stay within the client's cost: each puff is counted until
 * the game time its life ends (the particle engine never says when it drops one, so a puff it dropped early still
 * counts until then). One client thread only.
 */
final class SmokeBudget {
    /** At most this many puffs at once, at the "All" particle setting (they share the translucent sheet, 16384). */
    static final int MAX = 1800;

    private static final PriorityQueue<Long> ENDS = new PriorityQueue<>();

    private SmokeBudget() {}

    static void add(long end) { ENDS.add(end); }

    /** Puffs alive at game time {@code now}. */
    static int alive(long now) {
        while (!ENDS.isEmpty() && ENDS.peek() <= now) ENDS.poll();
        return ENDS.size();
    }

    /** A new level: its particles are gone and its clock is another. */
    static void clear() { ENDS.clear(); }
}
