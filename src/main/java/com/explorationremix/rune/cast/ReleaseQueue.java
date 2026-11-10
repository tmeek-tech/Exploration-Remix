package com.explorationremix.rune.cast;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * One player's actions waiting to go, first in, first out. At most {@code budget} go in one game tick and the rest
 * wait for the next; at most {@code cap} wait at once (one more is refused, and its caller lets it fizzle). Letting go
 * is never re-entered: an action let go while the queue drains (a burst's payload) joins the back, and goes this tick
 * if the budget leaves room. So a cast can never recurse, and never let more than {@code budget} actions go in a tick.
 */
final class ReleaseQueue<T> {
    private final ArrayDeque<T> waiting = new ArrayDeque<>();
    private boolean draining;
    private long tick = Long.MIN_VALUE;
    private int released;

    /** Adds {@code t} at the back; false (and nothing added) if {@code cap} are already waiting. */
    boolean offer(T t, int cap) {
        if (waiting.size() >= cap) return false;
        waiting.addLast(t);
        return true;
    }

    /** Lets go, in order, as many as the budget for tick {@code now} still allows. Returns how many went. */
    int drain(long now, int budget, Consumer<T> fire) {
        if (draining) return 0;
        if (tick != now) {
            tick = now;
            released = 0;
        }
        int went = 0;
        draining = true;
        try {
            while (!waiting.isEmpty() && released < budget) {
                T t = waiting.pollFirst();
                released++;
                went++;
                fire.accept(t);
            }
        } finally {
            draining = false;
        }
        return went;
    }

    boolean isEmpty() { return waiting.isEmpty(); }

    int size() { return waiting.size(); }

    void clear() { waiting.clear(); }
}
