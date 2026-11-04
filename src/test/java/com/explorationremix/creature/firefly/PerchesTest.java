package com.explorationremix.creature.firefly;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Where landed fireflies sit: points on real faces, spread over a patch round the anchor, never out of reach. */
class PerchesTest {

    /** The floor faces of a square of open blocks at y 64, (2r + 1) blocks across round (0, 64, 0). */
    private static List<Perches.Spot> floor(int r, long seed) {
        List<Perches.Spot> out = new ArrayList<>();
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) Perches.face(x, 64, z, 0, -1, 0, seed, out);
        return out;
    }

    private static double dist(Perches.Spot a, Perches.Spot b) {
        return Math.sqrt(a.dist2(b.x(), b.y(), b.z()));
    }

    @Test
    void aFaceGivesFourPointsOnItJustOffTheRock() {
        // the open block (3, 10, -2); its rock is the wall to the east (+x)
        List<Perches.Spot> out = new ArrayList<>();
        Perches.face(3, 10, -2, 1, 0, 0, 77, out);
        assertEquals(4, out.size());
        for (Perches.Spot s : out) {
            assertEquals(4.0 - Perches.LIFT, s.x(), 1.0E-9, "on the face, lifted off it into the open block");
            assertTrue(s.y() > 10.1 && s.y() < 10.9, "inside the face, not at its edge: y " + s.y());
            assertTrue(s.z() > -1.9 && s.z() < -1.1, "inside the face, not at its edge: z " + s.z());
            assertEquals(-1.0, s.nx());
            assertEquals(0.0, s.ny());
            assertEquals(0.0, s.nz());
        }
        for (int i = 0; i < 4; i++)
            for (int j = i + 1; j < 4; j++) assertTrue(dist(out.get(i), out.get(j)) > 0.25, "spread over the face");
        // a floor and a ceiling
        out.clear();
        Perches.face(0, 64, 0, 0, -1, 0, 1, out);
        Perches.face(0, 64, 0, 0, 1, 0, 1, out);
        for (int i = 0; i < 4; i++) {
            assertEquals(64.0 + Perches.LIFT, out.get(i).y(), 1.0E-9);
            assertEquals(1.0, out.get(i).ny());
        }
        for (int i = 4; i < 8; i++) {
            assertEquals(65.0 - Perches.LIFT, out.get(i).y(), 1.0E-9);
            assertEquals(-1.0, out.get(i).ny());
        }
    }

    @Test
    void theSwarmSpreadsOverAPatchRoundTheAnchor() {
        for (long seed : new long[]{0, 3, 99, -12345}) {
            List<Perches.Spot> spots = floor(3, seed);
            int n = 24;
            int[] pick = Perches.pick(spots, 0.5, 64.25, 0.5, n, seed);
            assertEquals(n, pick.length);
            Set<Integer> distinct = new HashSet<>();
            double mean = 0;
            for (int k : pick) {
                assertTrue(distinct.add(k), "two fireflies on one point");
                double d = Math.sqrt(spots.get(k).dist2(0.5, 64.25, 0.5));
                assertTrue(d <= Perches.REACH, "out of reach: " + d);
                mean += d / n;
            }
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++)
                    assertTrue(dist(spots.get(pick[i]), spots.get(pick[j])) > 0.3, "seed " + seed + ": two fireflies heaped together");
            // nearer the anchor than the faces round it are on the whole: a patch, not scattered to the edges
            double all = 0;
            int usable = 0;
            for (Perches.Spot s : spots) {
                double d = Math.sqrt(s.dist2(0.5, 64.25, 0.5));
                if (d <= Perches.REACH) { all += d; usable++; }
            }
            assertTrue(mean < all / usable, "mean " + mean + " against " + all / usable);
        }
    }

    @Test
    void theFirstSitsNearestTheAnchor() {
        List<Perches.Spot> spots = floor(2, 5);
        int[] pick = Perches.pick(spots, 1.2, 64.25, -0.7, 8, 5);
        double first = Math.sqrt(spots.get(pick[0]).dist2(1.2, 64.25, -0.7));
        for (Perches.Spot s : spots) assertTrue(first <= Math.sqrt(s.dist2(1.2, 64.25, -0.7)) + 0.05, "a nearer point was left");
    }

    @Test
    void pointsOutOfReachAreNeverChosen() {
        List<Perches.Spot> spots = floor(6, 1);
        int[] pick = Perches.pick(spots, 0.5, 64.25, 0.5, 32, 1);
        for (int k : pick) assertTrue(Math.sqrt(spots.get(k).dist2(0.5, 64.25, 0.5)) <= Perches.REACH);
        // a lone ledge far off gives nothing
        List<Perches.Spot> far = new ArrayList<>();
        Perches.face(10, 64, 10, 0, -1, 0, 1, far);
        assertEquals(0, Perches.pick(far, 0.5, 64.25, 0.5, 8, 1).length);
        assertEquals(0, Perches.pick(new ArrayList<>(), 0.5, 64.25, 0.5, 8, 1).length);
    }

    @Test
    void withTooFewPointsTheyDoubleUp() {
        List<Perches.Spot> one = new ArrayList<>();
        Perches.face(0, 64, 0, 0, -1, 0, 2, one);          // four points
        int[] pick = Perches.pick(one, 0.5, 64.25, 0.5, 10, 2);
        assertEquals(10, pick.length, "every firefly gets a point");
        Set<Integer> distinct = new HashSet<>();
        for (int k : pick) distinct.add(k);
        assertEquals(4, distinct.size(), "all four used before any is used twice");
        for (int i = 0; i < 4; i++) assertEquals(pick[i], pick[i + 4], "then round again in the same order");
    }

    @Test
    void theSameSeedGivesTheSamePatch() {
        List<Perches.Spot> a = floor(3, 8), b = floor(3, 8);
        assertArrayEquals(Perches.pick(a, 0.5, 64.25, 0.5, 20, 8), Perches.pick(b, 0.5, 64.25, 0.5, 20, 8));
    }
}
