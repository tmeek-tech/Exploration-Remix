package com.explorationremix.rune.cast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reel's pull, steered on the pulled player's own game: each tick the speed set at the start of the tick is the move
 * the player makes in it. It gets there and stops there, over an edge onto a top, hangs its time at a wall with no
 * top, never goes faster than full pull, and gives up when something is in the way.
 */
class PullTest {
    /**
     * How a pull went: ticks until it was over, where they ended, how far past the last point they ever got, the fastest
     * tick, and how many ticks it held them still.
     */
    record Run(int ticks, double[] at, double past, double fastest, int still) {}

    /** Pulls feet from the origin along {@code way}, moving them by each tick's speed; speeds left alone carry on as they were. */
    static Run pull(double[][] way, boolean stand) {
        double[] last = way[way.length - 1];
        double len = 0.0;
        double[] from = {0, 0, 0};
        for (double[] w : way) {
            len += Math.sqrt(sq(w[0] - from[0]) + sq(w[1] - from[1]) + sq(w[2] - from[2]));
            from = w;
        }
        double[] prev = way.length > 1 ? way[way.length - 2] : new double[]{0, 0, 0};
        double ux = last[0] - prev[0], uy = last[1] - prev[1], uz = last[2] - prev[2];
        double un = Math.sqrt(ux * ux + uy * uy + uz * uz);
        Pull.Steer s = new Pull.Steer(1, way, stand, Pull.SPEED, Pull.ticks(len, Pull.SPEED));
        double x = 0, y = 0, z = 0, past = Double.NEGATIVE_INFINITY, fastest = 0;
        int ticks = 0, still = 0;
        double[] v = {0, 0, 0};
        while (!s.over() && ticks < 1000) {
            double[] set = s.tick(x, y, z);
            if (set != null) v = set;
            x += v[0];
            y += v[1];
            z += v[2];
            double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            fastest = Math.max(fastest, speed);
            if (set != null && speed == 0.0) still++;
            if (un > 0) past = Math.max(past, ((x - last[0]) * ux + (y - last[1]) * uy + (z - last[2]) * uz) / un);
            ticks++;
        }
        return new Run(ticks, new double[]{x, y, z}, past, fastest, still);
    }

    static double sq(double d) { return d * d; }

    static double off(Run r, double[] p) { return Math.sqrt(sq(r.at()[0] - p[0]) + sq(r.at()[1] - p[1]) + sq(r.at()[2] - p[2])); }

    static final double[][] POINTS = {{6, 0, 0}, {0, 0, -9}, {5, 4, 3}, {3, 8, 0}, {20, 6, 5}, {4, -3, 4}, {0, 12, 1}, {-17, 2, 17}, {2, 0, 0}, {0, 3, 0}};

    @Test
    void itGetsThereAndStopsThere() {
        for (double[] d : POINTS) {
            Run r = pull(new double[][]{d}, true);
            String where = "to " + d[0] + ", " + d[1] + ", " + d[2];
            assertTrue(r.ticks() < Pull.ticks(Math.sqrt(sq(d[0]) + sq(d[1]) + sq(d[2])), Pull.SPEED), where + ": took " + r.ticks() + " ticks");
            assertTrue(off(r, d) < Pull.ARRIVE, where + ": stopped " + off(r, d) + " off");
            assertTrue(r.past() < 0.1, where + ": went " + r.past() + " past");
        }
    }

    @Test
    void upToTheEdgeThenOntoTheTop() {
        // a wall 16 blocks off, its top 3 above the feet: in front of the face just above the top, then onto it
        double[][] way = {{0, 3.25, 15.64}, {0, 3.08, 16.8}};
        Run r = pull(way, true);
        assertTrue(off(r, way[1]) < Pull.ARRIVE, "stopped " + off(r, way[1]) + " off the top");
        assertTrue(r.past() < 0.1, "went " + r.past() + " past");
    }

    @Test
    void anEdgeIsClearedNotClipped() {
        // coming straight up the face from below: they make for the top only once they are up level with the edge
        double[][] way = {{0, 6.25, 0.36}, {0, 6.08, -0.8}};
        Pull.Steer s = new Pull.Steer(1, way, true, Pull.SPEED, 100);
        double x = 0, y = 0, z = 0.36;
        boolean wentIn = false;
        for (int t = 0; t < 100 && !s.over(); t++) {
            double[] v = s.tick(x, y, z);
            if (v == null) break;
            // anything below the top's edge can't go in over it: the face is at z = 0
            if (y + v[1] < 6.0 && z + v[2] < 0.3) wentIn = true;
            x += v[0];
            y += v[1];
            z += v[2];
        }
        assertTrue(!wentIn, "cut in below the edge");
        assertTrue(s.over());
        assertTrue(Math.abs(z - -0.8) < Pull.ARRIVE && y > 6.0, "ended at " + y + ", " + z);
    }

    @Test
    void atAWallWithNoTopTheyHangThenDrop() {
        double[] at = {0, 2, 9};
        Run r = pull(new double[][]{at}, false);
        assertTrue(off(r, at) < Pull.ARRIVE, "hung " + off(r, at) + " off");
        assertEquals(Pull.HANG_TICKS, r.still(), "held still for");
        // after the hang the game lets them fall: the pull sets nothing more
        Pull.Steer s = new Pull.Steer(1, new double[][]{{0, 0, 0.1}}, false, Pull.SPEED, 50);
        for (int t = 0; t < Pull.HANG_TICKS; t++) assertNotNull(s.tick(0, 0, 0));
        assertNull(s.tick(0, 0, 0));
        assertTrue(s.over());
    }

    @Test
    void neverFasterThanFullPullAndItBuildsUp() {
        assertTrue(pull(new double[][]{{30, 10, -12}}, true).fastest() <= Pull.SPEED + 1.0E-9);
        assertEquals(Pull.START, Pull.speed(Pull.SPEED, 0, 50), 1.0E-9);
        assertTrue(Pull.speed(Pull.SPEED, 1, 50) > Pull.speed(Pull.SPEED, 0, 50));
        assertEquals(Pull.SPEED, Pull.speed(Pull.SPEED, 10, 50), 1.0E-9);
        // easing off near the point
        assertTrue(Pull.speed(Pull.SPEED, 10, 0.5) < 0.6);
        double[] v = Pull.step(0, 0, 0, Pull.SPEED, 5);
        assertEquals(0.0, v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Test
    void theLongestReelHasTimeToArrive() {
        double far = Instants.GRAPPLE_RANGE + 3.0;
        double[] slant = {far * 0.6, far * 0.5, far * 0.62};
        assertTrue(off(pull(new double[][]{slant}, true), slant) < Pull.ARRIVE);
        double[] down = {0, -far, 0.5};
        assertTrue(off(pull(new double[][]{down}, true), down) < Pull.ARRIVE);
    }

    @Test
    void somethingInTheWayStopsThePull() {
        // the game won't let them move: no nearer for a few ticks, then the pull lets go and sets nothing more
        Pull.Steer s = new Pull.Steer(1, new double[][]{{10, 0, 0}}, true, Pull.SPEED, 100);
        int ticks = 0;
        while (s.tick(0, 0, 0) != null && ticks < 100) ticks++;
        assertEquals(Pull.STALL_TICKS + 1, ticks);
        assertTrue(s.over());
        assertNull(s.tick(0, 0, 0));

        Pull.Progress p = new Pull.Progress();
        assertTrue(p.update(10.0));
        assertTrue(p.update(9.0));
        int n = 0;
        while (p.update(9.0) && n < 100) n++;
        assertEquals(Pull.STALL_TICKS, n);
        // a new point starts afresh
        p.reset();
        assertTrue(p.update(9.0));
        assertTrue(p.update(8.9));
        assertTrue(p.update(8.0));
    }

    @Test
    void takingTooLongLetsGo() {
        // held back to half its pull (a current, say): still getting nearer, but it lets go once its time is up
        Pull.Steer s = new Pull.Steer(1, new double[][]{{50, 0, 0}}, true, Pull.SPEED, 20);
        double x = 0;
        int ticks = 0;
        while (!s.over() && ticks < 200) {
            double[] v = s.tick(x, 0, 0);
            if (v != null) x += v[0] * 0.5;
            ticks++;
        }
        assertTrue(s.over());
        assertTrue(ticks <= 22, "let go after " + ticks);
    }
}
