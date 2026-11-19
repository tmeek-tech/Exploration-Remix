package com.explorationremix.creature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The giant Skein's strike: its front rising off the rock, coiling, shooting its jaws out at what it strikes at as far
 * as its reach lets it and drawing back to where it lay, without a jump anywhere between its phases; and its reared
 * neck one smooth curve from the rock to its head, its pieces spread evenly along it.
 */
class GiantStrikeTest {
    private static final double EPS = 1.0E-9;
    // on a wall facing +x, its body running along -z from the anchor to its head
    private static final double[] A = {10, 40, 10}, N = {1, 0, 0}, D = {0, 0, -1};
    private static final double[] H0 = {10, 40, 10 - GiantStrike.NECK};

    private static double[] head(double[] t, int phase, double k) {
        double[] out = new double[6];
        GiantStrike.head(A, N, D, H0, t, phase, k, out);
        return out;
    }

    private static double dist(double[] a, int ao, double[] b, int bo) {
        return Math.sqrt(Math.pow(a[ao] - b[bo], 2) + Math.pow(a[ao + 1] - b[bo + 1], 2) + Math.pow(a[ao + 2] - b[bo + 2], 2));
    }

    @Test
    void itsFrontRisesStrikesAndSinksBack() {
        assertEquals(0.0, GiantStrike.weight(GiantStrike.NONE, 0.5), EPS);
        assertEquals(0.0, GiantStrike.weight(GiantStrike.COIL, 0.0), EPS);
        assertEquals(1.0, GiantStrike.weight(GiantStrike.COIL, 1.0), EPS);
        assertEquals(1.0, GiantStrike.weight(GiantStrike.OUT, 0.3), EPS);
        assertEquals(1.0, GiantStrike.weight(GiantStrike.BACK, 0.0), EPS);
        assertEquals(0.0, GiantStrike.weight(GiantStrike.BACK, 1.0), EPS);
        for (double k = 0.0; k < 1.0; k += 0.05)
            assertTrue(GiantStrike.weight(GiantStrike.COIL, k + 0.05) >= GiantStrike.weight(GiantStrike.COIL, k));
    }

    @Test
    void noJumpBetweenItsPhases() {
        double[] t = {16, 42, 2};
        double[] lying = head(t, GiantStrike.NONE, 0.0), coilStart = head(t, GiantStrike.COIL, 0.0);
        assertEquals(0.0, dist(lying, 0, coilStart, 0), 1.0E-6, "it starts to coil from where its head lay");
        assertEquals(0.0, dist(lying, 3, coilStart, 3), 1.0E-6);
        double[] coiled = head(t, GiantStrike.COIL, 1.0), out = head(t, GiantStrike.OUT, 0.0);
        assertEquals(0.0, dist(coiled, 0, out, 0), 1.0E-6, "the strike starts where it coiled");
        assertEquals(0.0, dist(coiled, 3, out, 3), 1.0E-6);
        double[] struck = head(t, GiantStrike.OUT, 1.0), back = head(t, GiantStrike.BACK, 0.0);
        assertEquals(0.0, dist(struck, 0, back, 0), 1.0E-6, "it draws back from where it struck");
        assertEquals(0.0, dist(struck, 3, back, 3), 1.0E-6);
        double[] home = head(t, GiantStrike.BACK, 1.0);
        assertEquals(0.0, dist(home, 0, lying, 0), 1.0E-6, "and its head comes back down where it lay");
        assertEquals(0.0, dist(home, 3, lying, 3), 1.0E-6);
    }

    @Test
    void coiledItsHeadIsUpOffTheRock() {
        double[] t = {16, 42, 2};
        double[] c = head(t, GiantStrike.COIL, 1.0);
        assertEquals(A[0] + GiantStrike.COIL_RISE, c[0], 1.0E-6, "risen off the wall by COIL_RISE");
        // and pointing at what it strikes at
        double[] to = {t[0] - c[0], t[1] - c[1], t[2] - c[2]};
        double l = Math.sqrt(to[0] * to[0] + to[1] * to[1] + to[2] * to[2]);
        assertEquals(1.0, (to[0] * c[3] + to[1] * c[4] + to[2] * c[5]) / l, 1.0E-6);
    }

    @Test
    void itsJawsCloseOnWhatIsInReach() {
        double[] t = {15, 41, 1};
        double[] s = head(t, GiantStrike.OUT, GiantStrike.SNAP), j = new double[3];
        GiantStrike.jaws(s, j);
        assertTrue(dist(j, 0, t, 0) < 0.8, "its jaws close on it: " + dist(j, 0, t, 0));
        // it holds there for the rest of the strike out
        double[] later = head(t, GiantStrike.OUT, 0.9);
        assertEquals(0.0, dist(s, 0, later, 0), 1.0E-6);
    }

    @Test
    void itReachesNoFurtherThanItsReach() {
        double[] t = {10 + 40, 40, 10};
        double[] s = head(t, GiantStrike.OUT, 1.0);
        assertEquals(GiantStrike.REACH, dist(s, 0, A, 0), 1.0E-6);
        // straight on past its head as it lay, a few blocks
        double[] ahead = {10, 40, 10 - GiantStrike.NECK - 3.0 - GiantStrike.JAWS};
        double[] sa = head(ahead, GiantStrike.OUT, 1.0), ja = new double[3];
        GiantStrike.jaws(sa, ja);
        assertTrue(dist(ja, 0, ahead, 0) < 0.5, "three blocks on past its jaws it still reaches");
    }

    @Test
    void itsNeckIsOneSmoothCurveFromTheRockToItsHead() {
        double[] t = {16, 44, 4};
        double[] h = head(t, GiantStrike.OUT, 1.0), o = new double[6 * 21];
        for (int i = 0; i <= 20; i++) GiantStrike.neck(A, N, D, h, i / 20.0, o, 6 * i);
        assertEquals(0.0, dist(o, 0, h, 0), 1.0E-6, "fraction 0 is the head");
        assertEquals(0.0, dist(o, 6 * 20, A, 0), 1.0E-6, "fraction 1 is the anchor");
        // spread evenly along it, by length
        double first = dist(o, 0, o, 6);
        for (int i = 1; i < 20; i++) assertEquals(first, dist(o, 6 * i, o, 6 * (i + 1)), first * 0.12, "piece " + i);
        // the way it runs bends gently from one piece to the next, never a hinge
        for (int i = 0; i < 20; i++) {
            double c = o[6 * i + 3] * o[6 * (i + 1) + 3] + o[6 * i + 4] * o[6 * (i + 1) + 4] + o[6 * i + 5] * o[6 * (i + 1) + 5];
            assertTrue(c > Math.cos(Math.toRadians(25)), "bend at " + i + ": " + Math.toDegrees(Math.acos(Math.min(1, c))));
        }
        // leaving the rock the way its body runs on to its head, and coming into the head the way the head points
        assertTrue(o[6 * 20 + 3] * D[0] + o[6 * 20 + 4] * D[1] + o[6 * 20 + 5] * D[2] > 0.5);
        assertTrue(o[3] * h[3] + o[4] * h[4] + o[5] * h[5] > 0.9);
    }
}
