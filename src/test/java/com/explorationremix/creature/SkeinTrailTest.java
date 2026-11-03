package com.explorationremix.creature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The Skein's trail: points by arc length back from the head, corners where it went onto other rock, its length kept. */
class SkeinTrailTest {
    private static final double EPS = 1.0E-6;

    private static double[] at(SkeinTrail t, double s) {
        double[] out = new double[6];
        t.sample(s, out);
        return out;
    }

    private static void assertAt(double[] p, double x, double y, double z, double tol) {
        assertEquals(x, p[0], tol, "x");
        assertEquals(y, p[1], tol, "y");
        assertEquals(z, p[2], tol, "z");
    }

    @Test
    void laidStraightBehindTheHead() {
        SkeinTrail t = new SkeinTrail(5.0);
        t.lay(0, 64, 0, 0, 0, 2, 0, 1, 0);
        assertAt(at(t, 0), 0, 64, 0, EPS);
        double[] p = at(t, 2.0);
        assertAt(p, 0, 64, 2, EPS);
        assertEquals(1.0, p[4], EPS, "the floor's normal");
        assertTrue(t.length() >= 5.0);
    }

    @Test
    void theBodyFollowsTheHeadsPathRoundATurn() {
        SkeinTrail t = new SkeinTrail(6.0);
        t.lay(0, 0, 0, 0, 0, -1, 0, 1, 0);
        for (int k = 1; k <= 40; k++) t.record(0, 0, 0.25 * k, 0, 1, 0);          // ten blocks along +z
        assertAt(at(t, 1.0), 0, 0, 9, EPS);
        assertAt(at(t, 3.0), 0, 0, 7, EPS);
        for (int k = 1; k <= 12; k++) t.record(0.25 * k, 0, 10, 0, 1, 0);         // then three along +x
        assertAt(at(t, 1.0), 2, 0, 10, EPS);
        assertAt(at(t, 3.0), 0, 0, 10, EPS);                                      // the turn itself
        assertAt(at(t, 4.0), 0, 0, 9, EPS);                                       // and back along the first leg
    }

    @Test
    void smallStepsStillMoveTheHead() {
        SkeinTrail t = new SkeinTrail(5.0);
        t.lay(0, 0, 0, -1, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 30; k++) {
            t.record(0.05 * k, 0, 0, 0, 1, 0);                                    // less than a point's spacing
            assertAt(at(t, 0), 0.05 * k, 0, 0, EPS);
            assertAt(at(t, 1.0), 0.05 * k - 1.0, 0, 0, EPS);
            assertEquals(0.05, t.lastStep(), EPS);
        }
        assertTrue(t.size() < 30, "points only every spacing");
    }

    @Test
    void anOuterEdgeGetsItsCornerAndTheBodyNeverCutsThroughTheRock() {
        // a ledge: rock where x < 5 and y < 0; the head runs along its top (+x), over the edge and down its face
        SkeinTrail t = new SkeinTrail(6.0);
        t.lay(0, 0, 0, -1, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 24; k++) t.record(0.2 * k + 0.1, 0, 0, 0, 1, 0);     // up to x 4.9 on the top
        for (int k = 1; k <= 10; k++) t.record(5, -0.3 * k, 0, 1, 0, 0);          // down the face to y -3
        double[] corner = at(t, 3.0);
        assertAt(corner, 5, 0, 0, EPS);
        assertEquals(Math.sqrt(0.5), corner[3], EPS, "the edge's normal is half way between the top's and the face's");
        assertEquals(Math.sqrt(0.5), corner[4], EPS);
        for (double s = 0; s <= 6.0; s += 0.05) {
            double[] p = at(t, s);
            assertFalse(p[0] < 5 - EPS && p[1] < -EPS, "inside the rock at " + s + ": " + p[0] + " " + p[1]);
        }
        assertAt(at(t, 4.0), 4, 0, 0, 1.0E-6);
    }

    @Test
    void anInnerCornerGetsItsCornerToo() {
        // a wall where x > 5: the head runs along the floor (+x) and up the wall
        SkeinTrail t = new SkeinTrail(6.0);
        t.lay(0, 0, 0, -1, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 24; k++) t.record(0.2 * k + 0.1, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 10; k++) t.record(5, 0.3 * k, 0, -1, 0, 0);
        assertAt(at(t, 3.0), 5, 0, 0, EPS);
        for (double s = 0; s <= 6.0; s += 0.05) {
            double[] p = at(t, s);
            assertFalse(p[0] > 5 + EPS || p[1] < -EPS, "inside the rock at " + s);
        }
    }

    @Test
    void parallelRockHasNoCorner() {
        assertNull(SkeinTrail.corner(0, 0, 0, 0, 1, 0, 1, 0, 0, 0, -1, 0));
        assertNull(SkeinTrail.corner(0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0), "the same point");
        double[] c = SkeinTrail.corner(4.9, 0, 0, 0, 1, 0, 5, -0.3, 0, 1, 0, 0);
        assertNotNull(c);
        assertArrayEquals(new double[]{5, 0, 0}, c, EPS);
    }

    @Test
    void itKeepsItsLengthAndNoMore() {
        SkeinTrail t = new SkeinTrail(5.3);
        t.lay(0, 0, 0, -1, 0, 0, 0, 1, 0);
        double x = 0, z = 0, a = 0;
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 4000; i++) {
            a += (r.nextDouble() - 0.5) * 0.6;
            double step = r.nextDouble() * 0.35;
            x += Math.cos(a) * step;
            z += Math.sin(a) * step;
            t.record(x, 0, z, 0, 1, 0);
            double len = t.length();
            assertTrue(len >= 5.3 - EPS, "too short: " + len);
            assertTrue(len <= 5.3 + 1.5, "too long: " + len);
            assertTrue(t.size() <= SkeinTrail.CAP);
        }
    }

    @Test
    void aTeleportLaysItAfresh() {
        SkeinTrail t = new SkeinTrail(5.0);
        t.lay(0, 0, 0, -1, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 10; k++) t.record(0.3 * k, 0, 0, 0, 1, 0);
        t.record(50, 10, 50, 0, 1, 0);
        assertAt(at(t, 0), 50, 10, 50, EPS);
        assertAt(at(t, 2.0), 48, 10, 50, EPS);                                    // behind it, the way it was going
        assertTrue(t.length() >= 5.0);
        assertEquals(0.0, t.lastStep(), EPS);
    }

    @Test
    void pastItsOldestPointItGoesOnStraight() {
        SkeinTrail t = new SkeinTrail(2.0);
        t.lay(0, 0, 0, 0, 0, 1, 0, 1, 0);
        assertAt(at(t, 10.0), 0, 0, 10, EPS);
        double[] all = new double[18];
        t.sampleAll(new double[]{0.5, 1.5, 9.0}, all);
        assertEquals(0.5, all[2], EPS);
        assertEquals(1.5, all[8], EPS);
        assertEquals(9.0, all[14], EPS);
    }

    @Test
    void sampledNormalsAreUnitLength() {
        SkeinTrail t = new SkeinTrail(6.0);
        t.lay(0, 0, 0, -1, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 24; k++) t.record(0.2 * k + 0.1, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 10; k++) t.record(5, -0.3 * k, 0, 1, 0, 0);
        for (double s = 0; s <= 8.0; s += 0.1) {
            double[] p = at(t, s);
            assertEquals(1.0, Math.sqrt(p[3] * p[3] + p[4] * p[4] + p[5] * p[5]), EPS);
        }
    }
}
