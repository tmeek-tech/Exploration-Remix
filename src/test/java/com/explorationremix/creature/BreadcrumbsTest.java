package com.explorationremix.creature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A roamer's trail home: crumbs a walk apart, loops cut as they close, at most MAX of them, saved and read back. */
class BreadcrumbsTest {

    /** Walks from (x0, z0) to (x1, z1) one block at a time, dropping crumbs as a lizard does. */
    private static void walk(Breadcrumbs b, int x0, int z0, int x1, int z1) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        for (int i = 1; i <= n; i++) b.record(x0 + (x1 - x0) * i / n, 64, z0 + (z1 - z0) * i / n);
    }

    @Test
    void crumbsLieAWalkApart() {
        Breadcrumbs b = new Breadcrumbs();
        b.start(0, 64, 0);
        walk(b, 0, 0, 100, 0);
        assertEquals(11, b.size(), "one at the den and one every ten blocks");
        for (int i = 1; i < b.size(); i++) assertEquals(10 * i, b.x(i));
        assertFalse(b.record(104, 64, 0), "too near the last crumb for a new one");
        assertEquals(11, b.size());
        assertEquals(100.0, b.length(), 1.0E-9);
    }

    @Test
    void aLoopIsCutWhenItCloses() {
        Breadcrumbs b = new Breadcrumbs();
        b.start(0, 64, 0);
        walk(b, 0, 0, 40, 0);            // out along x: crumbs at 0, 10, 20, 30, 40
        walk(b, 40, 0, 40, 30);          // round a cave: up z ...
        walk(b, 40, 30, 20, 30);         // ... back along x ...
        int before = b.size();
        assertTrue(before > 5);
        walk(b, 20, 30, 20, 0);          // ... and back down to the way out, onto the crumb at (20, 0)
        // the circle is gone: the trail runs straight back from (20, 0) to the den
        int last = b.size() - 1;
        assertTrue(b.dist(last, 20, 64, 0) < Breadcrumbs.LOOP, "ends at the crumb the loop came back to");
        for (int i = 0; i <= last; i++) assertEquals(0, b.z(i), "nothing of the loop is left");
        assertEquals(3, b.size());
    }

    @Test
    void neverTwoCrumbsTooCloseAfterALoop() {
        Breadcrumbs b = new Breadcrumbs();
        b.start(0, 64, 0);
        // a figure of eight that crosses itself: every closing cuts the trail back, never leaving near neighbours
        walk(b, 0, 0, 30, 30);
        walk(b, 30, 30, 30, 0);
        walk(b, 30, 0, 0, 30);
        walk(b, 0, 30, 0, 60);
        for (int i = 0; i < b.size(); i++)
            for (int j = i + 1; j < b.size(); j++)
                assertTrue(b.dist(i, b.x(j), b.y(j), b.z(j)) >= Breadcrumbs.LOOP, "crumbs " + i + " and " + j + " too close");
    }

    @Test
    void aLongWalkKeepsAtMostMaxEvenCrumbs() {
        Breadcrumbs b = new Breadcrumbs();
        b.start(0, 64, 0);
        walk(b, 0, 0, 600, 0);
        assertEquals(Breadcrumbs.MAX, b.size());
        assertEquals(0, b.x(0), "the den stays first");
        assertTrue(b.x(b.size() - 1) >= 590, "the newest crumb is where it is now");
        double longest = 0;
        for (int i = 1; i < b.size(); i++) longest = Math.max(longest, b.dist(i - 1, b.x(i), b.y(i), b.z(i)));
        // 600 blocks in 31 gaps: thinning where it is densest keeps them even (none much over twice the mean)
        assertTrue(longest <= 2.0 * 600.0 / (Breadcrumbs.MAX - 1) + Breadcrumbs.SPACING, "longest gap " + longest);
    }

    @Test
    void walkingHomeDropsWhatIsBehind() {
        Breadcrumbs b = new Breadcrumbs();
        b.start(0, 64, 0);
        walk(b, 0, 0, 50, 0);            // 0, 10, 20, 30, 40, 50
        assertFalse(b.reached(25, 64, 0, 3.0), "between crumbs: none reached");
        assertTrue(b.reached(41, 64, 0, 3.0));
        assertEquals(4, b.size(), "40 and 50 are behind it");
        // a short cut: standing by the crumb at 10, everything after it is behind it too
        assertTrue(b.reached(10, 65, 1, 3.0));
        assertEquals(1, b.size());
        b.pop();
        assertTrue(b.isEmpty());
        b.pop();
        assertTrue(b.isEmpty(), "popping an empty trail does nothing");
    }

    @Test
    void savedAndReadBack() {
        Breadcrumbs b = new Breadcrumbs();
        b.start(-5, 60, 7);
        walk(b, -5, 7, 60, -20);
        int[] saved = b.toArray();
        assertEquals(3 * b.size(), saved.length);
        Breadcrumbs c = new Breadcrumbs();
        c.fromArray(saved);
        assertEquals(b.size(), c.size());
        for (int i = 0; i < b.size(); i++) {
            assertEquals(b.x(i), c.x(i));
            assertEquals(b.y(i), c.y(i));
            assertEquals(b.z(i), c.z(i));
        }
        c.fromArray(new int[0]);
        assertTrue(c.isEmpty());
        c.fromArray(new int[]{1, 2});    // a broken save: no half crumbs
        assertTrue(c.isEmpty());
    }
}
