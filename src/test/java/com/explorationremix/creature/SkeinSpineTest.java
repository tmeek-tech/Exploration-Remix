package com.explorationremix.creature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The line the Skein's body is drawn along: on the trail where the head ran straight, a smooth curve round the head's
 * sharp turns (never bending more than its limit a link), over edges as the trail goes, never far off the trail, never
 * in the rock.
 */
class SkeinSpineTest {
    private static final double EPS = 1.0E-6;
    private static final int JOINTS = 48;

    /** A trail on the floor (y 0) of a head that came along +z for {@code first} blocks, then turned by {@code deg}. */
    private static SkeinTrail turned(double first, double deg, double after) {
        SkeinTrail t = new SkeinTrail(9.0);
        t.lay(0, 0, 0, 0, 0, -1, 0, 1, 0);
        int n = (int) Math.round(first / 0.05);
        for (int k = 1; k <= n; k++) t.record(0, 0, 0.05 * k, 0, 1, 0);
        double a = Math.toRadians(deg), dx = Math.sin(a), dz = Math.cos(a);
        int m = (int) Math.round(after / 0.05);
        for (int k = 1; k <= m; k++) t.record(dx * 0.05 * k, 0, first + dz * 0.05 * k, 0, 1, 0);
        return t;
    }

    /** The way joint i-1 runs to joint i. */
    private static double[] link(double[] d, int i) {
        double x = d[6 * i] - d[6 * (i - 1)], y = d[6 * i + 1] - d[6 * (i - 1) + 1], z = d[6 * i + 2] - d[6 * (i - 1) + 2];
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l, l};
    }

    /** The turn (radians) between the links into and out of joint i, along the rock there. */
    private static double bendAt(double[] d, int i) {
        double[] a = link(d, i), b = link(d, i + 1);
        double nx = d[6 * i + 3], ny = d[6 * i + 4], nz = d[6 * i + 5];
        double[] pa = flat(a, nx, ny, nz), pb = flat(b, nx, ny, nz);
        double c = pa[0] * pb[0] + pa[1] * pb[1] + pa[2] * pb[2];
        return Math.acos(Math.max(-1.0, Math.min(1.0, c)));
    }

    private static double[] flat(double[] v, double nx, double ny, double nz) {
        double d = v[0] * nx + v[1] * ny + v[2] * nz;
        double x = v[0] - nx * d, y = v[1] - ny * d, z = v[2] - nz * d, l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    /** How far joint i is from the nearest point of the trail. */
    private static double offTrail(SkeinSpine s, SkeinTrail t, int i) {
        double[] d = s.data(), r = new double[6];
        double best = Double.MAX_VALUE;
        for (double a = 0.0; a <= 9.0; a += 0.01) {
            t.sample(a, r);
            best = Math.min(best, sq(d[6 * i] - r[0]) + sq(d[6 * i + 1] - r[1]) + sq(d[6 * i + 2] - r[2]));
        }
        return Math.sqrt(best);
    }

    private static double sq(double v) { return v * v; }

    @Test
    void aStraightBodyLiesOnItsTrail() {
        SkeinTrail t = new SkeinTrail(9.0);
        t.lay(3, 10, -2, 1, 0, 0, 0, 1, 0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.build(t, 0.0, -1, 0, 0, null);
        for (int i = 0; i < JOINTS; i++) {
            assertEquals(3 + i * SkeinSpine.LINK, s.data()[6 * i], EPS);
            assertEquals(10, s.data()[6 * i + 1], EPS);
            assertEquals(-2, s.data()[6 * i + 2], EPS);
            assertEquals(1.0, s.data()[6 * i + 4], EPS, "the floor's normal");
        }
    }

    @Test
    void itsLinksKeepTheirLength() {
        SkeinTrail t = turned(4.0, 90, 2.0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.build(t, 0.0, 1, 0, 0, null);
        for (int i = 1; i < JOINTS; i++) assertEquals(SkeinSpine.LINK, link(s.data(), i)[3], 1.0E-3, "link " + i);
    }

    @Test
    void aSharpTurnOfTheHeadIsARoundedCurveInTheBody() {
        for (double deg : new double[]{60, 90, 135, 170}) {
            for (double after : new double[]{0.5, 1.5, 3.0}) {
                SkeinTrail t = turned(5.0, deg, after);
                double a = Math.toRadians(deg);
                SkeinSpine s = new SkeinSpine(JOINTS);
                s.build(t, 0.0, Math.sin(a), 0, Math.cos(a), null);
                double[] d = s.data();
                for (int i = 1; i < JOINTS - 1; i++) {
                    double most = i - 1 < SkeinSpine.NECK.length ? SkeinSpine.NECK[i - 1] : SkeinSpine.BEND;
                    // where the drift limit pulled the next joint back it may bend more
                    if (!s.held[i + 1])
                        assertTrue(bendAt(d, i) <= most + 1.0E-3, deg + " degrees, " + after + " on: bends " + Math.toDegrees(bendAt(d, i)) + " at " + i);
                    assertEquals(0.0, d[6 * i + 1], 1.0E-6, "on the floor");
                }
                // it comes back onto the trail behind the turn
                assertTrue(offTrail(s, t, JOINTS - 1) < 0.35, deg + " degrees: the tail is " + offTrail(s, t, JOINTS - 1) + " off");
            }
        }
    }

    @Test
    void itNeverStraysFarFromTheTrail() {
        SkeinTrail t = turned(5.0, 175, 1.0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.build(t, 0.0, Math.sin(Math.toRadians(175)), 0, Math.cos(Math.toRadians(175)), null);
        for (int i = 0; i < JOINTS; i++) assertTrue(offTrail(s, t, i) <= SkeinSpine.DRIFT + 0.4, "joint " + i);
        int held = 0;
        for (boolean h : s.held) if (h) held++;
        assertTrue(held < JOINTS / 4, "only round the turn itself is it held in: " + held);
    }

    @Test
    void theNeckBendsAfterTheHead() {
        // the head snapped round where it stands: the neck turns after it, the rest comes round behind
        SkeinTrail t = new SkeinTrail(9.0);
        t.lay(0, 0, 0, 0, 0, -1, 0, 1, 0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.build(t, 0.0, 1, 0, 0, null);
        double[] first = link(s.data(), 1);
        assertTrue(first[0] < -0.1, "the neck starts out from the way the head points");
        for (int i = SkeinSpine.NECK.length + 1; i < JOINTS - 1; i++) assertTrue(bendAt(s.data(), i) <= SkeinSpine.BEND + 1.0E-3);
        assertTrue(offTrail(s, t, JOINTS - 1) < 0.2, "the tail is still where it lay");
    }

    @Test
    void itPoursOverAnEdgeAsTheTrailDoes() {
        // along the floor (y 0) to a wall at x 2.5, then straight up it
        SkeinTrail t = new SkeinTrail(9.0);
        t.lay(0, 0, 0, -1, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 44; k++) t.record(0.05 * k, 0, 0, 0, 1, 0);
        for (int k = 1; k <= 60; k++) t.record(2.5, 0.05 * k + 0.2, 0, -1, 0, 0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.build(t, 0.0, 0, 1, 0, null);
        for (int i = 0; i < JOINTS; i++) assertTrue(offTrail(s, t, i) < 0.12, "joint " + i + " is " + offTrail(s, t, i) + " off");
    }

    @Test
    void itKeepsOutOfTheRock() {
        // rock beyond z 5.2: the head ran up to z 5 and doubled back; the body's loop must not swing out into it
        SkeinSpine.Solid wall = (x, y, z) -> z > 5.2;
        SkeinTrail t = turned(5.0, 170, 1.5);
        double a = Math.toRadians(170);
        SkeinSpine free = new SkeinSpine(JOINTS), kept = new SkeinSpine(JOINTS);
        free.build(t, 0.0, Math.sin(a), 0, Math.cos(a), null);
        kept.build(t, 0.0, Math.sin(a), 0, Math.cos(a), wall);
        double most = 0;
        for (int i = 0; i < JOINTS; i++) most = Math.max(most, free.data()[6 * i + 2]);
        assertTrue(most > 5.2, "without the check its loop swings out past the rock's face: " + most);
        for (int i = 0; i < JOINTS; i++)
            assertFalse(wall.at(kept.data()[6 * i], kept.data()[6 * i + 1] + SkeinSpine.CHECK_LIFT, kept.data()[6 * i + 2]), "joint " + i);
    }

    @Test
    void aGentleCornerIsCutOnItsInside() {
        // a right angle three blocks back: the body runs inside the corner, not out round it
        SkeinTrail t = turned(5.0, 90, 3.0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.build(t, 0.0, 1, 0, 0, null);
        double inside = 0, outside = 0;
        for (int i = 0; i < JOINTS; i++) {
            double x = s.data()[6 * i], z = s.data()[6 * i + 2];
            if (x > 0.02 && z < 4.98) inside = Math.max(inside, Math.min(x, 5.0 - z));
            if (x < -0.02 || z > 5.02) outside = Math.max(outside, Math.max(-x, z - 5.0));
        }
        assertTrue(inside > 0.1, "it cuts the corner: " + inside);
        assertTrue(outside < 0.35, "and swings out round it little: " + outside);
    }

    @Test
    void itIsLaidAfreshOnlyOnceTheTrailOrTheHeadMoves() {
        SkeinTrail t = turned(4.0, 90, 1.0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        int[] looks = new int[1];
        SkeinSpine.Solid counting = (x, y, z) -> { looks[0]++; return false; };
        s.build(t, 0.0, 1, 0, 0, counting);
        int first = looks[0];
        assertTrue(first >= JOINTS - 1, "the rock is looked at for every joint");
        double tail = s.data()[6 * (JOINTS - 1)];
        // the same again: kept as it is, the rock not looked at
        t.record(1.0, 0, 4.0, 0, 1, 0);
        s.build(t, 0.0, 1, 0, 0, counting);
        int second = looks[0];
        s.build(t, 0.0, 1, 0, 0, counting);
        assertEquals(second, looks[0], "nothing moved: laid as it was");
        // the head turns where it stands: laid afresh
        s.build(t, 0.0, 0, 0, 1, counting);
        assertTrue(looks[0] > second, "the head turned");
        // it walks on: laid afresh, along the new trail
        int third = looks[0];
        t.record(1.3, 0, 4.0, 0, 1, 0);
        s.build(t, 0.0, 1, 0, 0, counting);
        assertTrue(looks[0] > third, "it moved on");
        assertNotEquals(tail, s.data()[6 * (JOINTS - 1)], "and its tail followed");
    }

    @Test
    void samplesBetweenJoints() {
        SkeinTrail t = new SkeinTrail(9.0);
        t.lay(0, 0, 0, 0, 0, 1, 0, 1, 0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.build(t, 0.0, 0, 0, -1, null);
        double[] o = new double[12];
        s.sampleAll(new double[]{0.4, 9.0}, o);
        assertEquals(0.4, o[2], EPS);
        assertEquals(1.0, o[4], EPS);
        assertEquals(9.0, o[8], 1.0E-6, "past the last joint it goes on along the last link");
    }
}
