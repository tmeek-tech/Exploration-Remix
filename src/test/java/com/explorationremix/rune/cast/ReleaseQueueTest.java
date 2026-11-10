package com.explorationremix.rune.cast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-player queue every action goes through: in order, a budget a tick, a cap, never re-entered. */
class ReleaseQueueTest {
    @Test
    void actionsGoInOrderUpToTheBudgetAndTheRestWaitForTheNextTick() {
        ReleaseQueue<Integer> q = new ReleaseQueue<>();
        for (int i = 0; i < 40; i++) assertTrue(q.offer(i, 256));
        List<Integer> went = new ArrayList<>();
        assertEquals(16, q.drain(100, 16, went::add));
        assertEquals(24, q.size());
        assertEquals(0, q.drain(100, 16, went::add), "the same tick's budget is spent");
        assertEquals(16, q.drain(101, 16, went::add));
        assertEquals(8, q.drain(102, 16, went::add));
        assertTrue(q.isEmpty());
        for (int i = 0; i < 40; i++) assertEquals(i, went.get(i));
    }

    @Test
    void theBudgetCountsEverythingLetGoInATickAcrossDrains() {
        ReleaseQueue<Integer> q = new ReleaseQueue<>();
        q.offer(1, 256);
        assertEquals(1, q.drain(5, 2, x -> {}));
        q.offer(2, 256);
        q.offer(3, 256);
        assertEquals(1, q.drain(5, 2, x -> {}), "one left this tick");
        assertEquals(1, q.size());
    }

    @Test
    void aFullQueueRefusesMore() {
        ReleaseQueue<Integer> q = new ReleaseQueue<>();
        for (int i = 0; i < 4; i++) assertTrue(q.offer(i, 4));
        assertFalse(q.offer(4, 4));
        assertEquals(4, q.size());
    }

    @Test
    void anActionLetGoWhileDrainingWaitsItsTurnInsteadOfRecursing() {
        ReleaseQueue<Integer> q = new ReleaseQueue<>();
        List<Integer> went = new ArrayList<>();
        int[] depth = {0}, deepest = {0};
        q.offer(0, 256);
        // each action lets go two more (a fan of bursts, each with a payload), and tries to drain at once
        q.drain(1, 16, new java.util.function.Consumer<Integer>() {
            @Override
            public void accept(Integer x) {
                depth[0]++;
                deepest[0] = Math.max(deepest[0], depth[0]);
                went.add(x);
                q.offer(x * 2 + 1, 256);
                q.offer(x * 2 + 2, 256);
                assertEquals(0, q.drain(1, 16, this), "never re-entered");
                depth[0]--;
            }
        });
        assertEquals(1, deepest[0], "no action ran inside another");
        assertEquals(16, went.size(), "the budget still holds");
        for (int i = 0; i < 16; i++) assertEquals(i, went.get(i), "breadth first, in order");
        assertEquals(17, q.size(), "the rest wait for the next tick");
    }

    @Test
    void anActionThatThrowsLeavesTheQueueUsable() {
        ReleaseQueue<Integer> q = new ReleaseQueue<>();
        q.offer(1, 256);
        q.offer(2, 256);
        try {
            q.drain(1, 16, x -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException expected) {
            // the world threw while an action went off
        }
        List<Integer> went = new ArrayList<>();
        assertEquals(1, q.drain(2, 16, went::add));
        assertEquals(List.of(2), went);
    }
}
