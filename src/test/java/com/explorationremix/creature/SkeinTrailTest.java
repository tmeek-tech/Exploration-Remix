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

    /** Over a ledge's edge (rock where x < ox + 5 and y < 0) and three blocks down its face, far out in the world. */
    private static SkeinTrail overALedge(double ox, double oz) {
        SkeinTrail t = new SkeinTrail(6.0);
        t.lay(ox, 0, oz, -1, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 24; k++) t.record(ox + 0.2 * k + 0.1, 0, oz, 0, 1, 0);
        for (int k = 1; k <= 10; k++) t.record(ox + 5, -0.3 * k, oz, 1, 0, 0);
        return t;
    }

    private static double[] halfBlocks(double to) {
        double[] arcs = new double[(int) Math.round(to / 0.5) + 1];
        for (int k = 0; k < arcs.length; k++) arcs[k] = 0.5 * k;
        return arcs;
    }

    @Test
    void writtenAndReadBackTheBodyLiesWhereItLay() {
        double ox = 2.9E7, oz = -2.9E7;
        SkeinTrail t = overALedge(ox, oz);
        double[] arcs = halfBlocks(6.0);                                          // the edge itself is 3.0 back
        float[] kept = t.write(arcs, ox + 5, -3, oz);
        assertEquals(6 * arcs.length, kept.length);
        SkeinTrail r = new SkeinTrail(6.0);
        assertTrue(r.read(kept, ox + 5, -3, oz));
        assertTrue(r.laid());
        assertEquals(0.0, r.lastStep(), EPS);
        // floats relative to a point by the head keep it to a hair, thirty million blocks out
        for (double s = 0; s <= 6.0; s += 0.05) {
            double[] a = at(t, s), b = at(r, s);
            assertAt(b, a[0], a[1], a[2], 1.0E-5);
            assertFalse(b[0] < ox + 5 - 1.0E-5 && b[1] < -1.0E-5, "inside the rock at " + s);
        }
        for (double s : arcs) {
            double[] a = at(t, s), b = at(r, s);
            assertArrayEquals(new double[]{a[3], a[4], a[5]}, new double[]{b[3], b[4], b[5]}, 1.0E-6, "the normal at " + s);
        }
    }

    @Test
    void aTrailReadBackWalksOnFromWhereItLay() {
        SkeinTrail t = overALedge(0, 0);
        SkeinTrail r = new SkeinTrail(6.0);
        assertTrue(r.read(t.write(halfBlocks(6.0), 5, -3, 0), 5, -3, 0));
        for (int k = 1; k <= 10; k++) r.record(5, -3 - 0.2 * k, 0, 1, 0, 0);       // on down the face
        assertAt(at(r, 0), 5, -5, 0, EPS);
        assertAt(at(r, 2.0), 5, -3, 0, 1.0E-5);                                   // where its head lay
        assertAt(at(r, 5.0), 5, 0, 0, 1.0E-5);                                    // and the edge
        assertEquals(0.2, r.lastStep(), 1.0E-6);
    }

    @Test
    void whatIsNoTrailIsNotRead() {
        SkeinTrail t = new SkeinTrail(5.0);
        assertEquals(0, t.write(halfBlocks(5.0), 0, 0, 0).length, "nothing laid, nothing written");
        float[] one = {0, 0, 0, 0, 1, 0};
        float[] ragged = {0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0};
        float[] nan = {0, 0, 0, 0, 1, 0, Float.NaN, 0, 1, 0, 1, 0};
        float[] noNormal = {0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        for (float[] bad : new float[][]{new float[0], one, ragged, nan, noNormal}) {
            assertFalse(t.read(bad, 0, 0, 0));
            assertFalse(t.laid());
        }
        t.lay(0, 0, 0, 0, 0, 1, 0, 1, 0);
        assertFalse(t.read(nan, 10, 0, 0));
        assertAt(at(t, 2.0), 0, 0, 2, EPS);                                       // left as it was
    }

    @Test
    void aStretchUpAWallHasABoxThinAcrossTheRockAndWideAcrossTheBody() {
        double[] box = new double[6];
        // a wall facing +x at x 5, the body running straight up it
        SkeinTrail.stretchBox(new double[]{5, 0, 0, 1, 0, 0, 5, 1, 0, 1, 0, 0}, 0, 6, 1.2, 0.5, box);
        assertArrayEquals(new double[]{5, 0, -0.6, 5.5, 1, 0.6}, box, EPS);
        // along the floor
        SkeinTrail.stretchBox(new double[]{0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0}, 0, 6, 1.2, 0.5, box);
        assertArrayEquals(new double[]{-0.6, 0, 0, 0.6, 0.5, 1}, box, EPS);
        // across the ceiling, the slab under it
        SkeinTrail.stretchBox(new double[]{0, 3, 0, 0, -1, 0, 1, 3, 0, 0, -1, 0}, 0, 6, 1.2, 0.5, box);
        assertArrayEquals(new double[]{0, 2.5, -0.6, 1, 3, 0.6}, box, EPS);
    }

    @Test
    void aStretchBoxHoldsTheSlabWhicheverWayItRuns() {
        double[] box = new double[6];
        // aslant across the floor: the slab's corners are inside the box, and the box no bigger than they make it
        double c = Math.sqrt(0.5), w = 0.6;
        SkeinTrail.stretchBox(new double[]{0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 1, 0}, 0, 6, 2 * w, 0.5, box);
        assertArrayEquals(new double[]{-w * c, 0, -w * c, 1 + w * c, 0.5, 1 + w * c}, box, EPS);
        // over an edge, from the top onto the face: both ends lifted off their own rock
        SkeinTrail.stretchBox(new double[]{4.6, 0, 0, 0, 1, 0, 5, -0.4, 0, 1, 0, 0}, 0, 6, 1.2, 0.5, box);
        assertTrue(box[0] <= 4.6 && box[3] >= 5.25 && box[1] <= -0.4 && box[4] >= 0.25, java.util.Arrays.toString(box));
        assertTrue(box[2] <= -0.6 + EPS && box[5] >= 0.6 - EPS);
        // no stretch at all: as wide every way along the rock
        SkeinTrail.stretchBox(new double[]{0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0}, 0, 6, 1.2, 0.5, box);
        assertArrayEquals(new double[]{-0.6, 0, -0.6, 0.6, 0.5, 0.6}, box, EPS);
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
