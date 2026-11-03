package com.explorationremix.creature.firefly;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A swarm's flock: each firefly's steering (apart, along, together, back to the centre), the loose cloud they make
 * round a drifting centre, a burst and the regrouping after it, keeping out of rock, settling on perches and lifting
 * off them, and fireflies coming and going.
 */
class FlockTest {

    private static Flock flock(long seed, int n, double cx, double cy, double cz) {
        Flock f = new Flock(seed);
        for (int i = 0; i < n; i++) f.add(cx, cy, cz);
        return f;
    }

    private static double distTo(Flock f, int i, double cx, double cy, double cz) {
        return Math.sqrt(sq(f.x(i) - cx) + sq(f.y(i) - cy) + sq(f.z(i) - cz));
    }

    private static double meanDist(Flock f, double cx, double cy, double cz) {
        double m = 0;
        for (int i = 0; i < f.size(); i++) m += distTo(f, i, cx, cy, cz) / f.size();
        return m;
    }

    private static double speed(Flock f, int i) {
        return Math.sqrt(sq(f.vx(i)) + sq(f.vy(i)) + sq(f.vz(i)));
    }

    private static double sq(double v) { return v * v; }

    // ------------------------------------------------------------------------------------------------ steering

    @Test
    void firefliesTooCloseArePushedApart() {
        Flock f = flock(1, 2, 0, 64, 0);
        f.put(0, -0.1, 64, 0, 0, 0, 0);
        f.put(1, 0.1, 64, 0, 0, 0, 0);
        double[] s = f.steer(0, 0, 64, 0, 1.0);
        assertTrue(s[0] < 0, "the left one is pushed further left, got " + s[0]);
        assertEquals(0.0, s[1], 1.0E-12);
        s = f.steer(1, 0, 64, 0, 1.0);
        assertTrue(s[0] > 0, "the right one further right, got " + s[0]);
    }

    @Test
    void aFireflyTurnsTheWayItsNeighboursFly() {
        Flock f = flock(1, 4, 0, 64, 0);
        // three neighbours a block off (too far to push it, near enough to follow) all flying +x; it hangs still
        f.put(0, 0, 64, 0, 0, 0, 0);
        f.put(1, 0, 64, 0.9, 0.1, 0, 0);
        f.put(2, 0, 64.9, 0, 0.1, 0, 0);
        f.put(3, 0, 63.1, 0, 0.1, 0, 0);
        double[] s = f.steer(0, 0, 64, 0, 1.0);
        assertTrue(s[0] > 0.002, "it turns along +x with them, got " + s[0]);
    }

    @Test
    void aFireflyDriftsToItsNeighboursMiddle() {
        Flock f = flock(1, 3, 0, 64, 0);
        f.put(0, 0, 64, 0, 0, 0, 0);
        f.put(1, 1.0, 64, 0.2, 0, 0, 0);
        f.put(2, 1.0, 64, -0.2, 0, 0, 0);
        double[] s = f.steer(0, 0, 64, 0, 1.0);
        assertTrue(s[0] > 0, "towards them, got " + s[0]);
        assertEquals(0.0, s[2], 1.0E-12, "their middle is straight ahead");
    }

    @Test
    void theCentrePullsAStrayHarderFurtherOut() {
        Flock f = flock(1, 1, 0, 64, 0);
        f.put(0, 1.0, 64, 0, 0, 0, 0);
        double near = -f.steer(0, 0, 64, 0, 1.0)[0];
        f.put(0, 3.0, 64, 0, 0, 0, 0);
        double far = -f.steer(0, 0, 64, 0, 1.0)[0];
        assertTrue(near > 0 && far > 0, "both pulled back to the centre");
        assertTrue(far > 3.0 * near * 1.5, "past the swarm's edge the pull grows faster than the distance: " + near + " " + far);
        assertEquals(0.0, f.steer(0, 0, 64, 0, 0.0)[0], 1.0E-12, "no pull while the burst has it slack");
    }

    // ------------------------------------------------------------------------------------------------ the flock as a whole

    @Test
    void theFlockIsALooseCloudThatFollowsItsCentre() {
        for (long seed : new long[]{1, 2, 3}) {
            for (int n : new int[]{8, 16, 24}) {
                Flock f = flock(seed, n, 6, 64, 0);
                double sum = 0, worst = 0, closest = 0;
                int samples = 0;
                for (int t = 0; t < 2400; t++) {
                    // the centre drifts round a circle at a block a second
                    double cx = 6 * Math.cos(t * 0.05 / 6), cz = 6 * Math.sin(t * 0.05 / 6);
                    f.step(cx, 64, cz, Flock.ANYWHERE);
                    for (int i = 0; i < f.size(); i++)
                        assertTrue(speed(f, i) <= Flock.MAX_SPEED + Flock.CATCH_UP + 1.0E-9, "too fast: " + speed(f, i));
                    if (t < 400) continue;
                    double mean = meanDist(f, cx, 64, cz);
                    sum += mean;
                    samples++;
                    for (int i = 0; i < f.size(); i++) worst = Math.max(worst, distTo(f, i, cx, 64, cz));
                    double minPair = Double.MAX_VALUE;
                    for (int i = 0; i < f.size(); i++)
                        for (int j = i + 1; j < f.size(); j++)
                            minPair = Math.min(minPair, Math.sqrt(sq(f.x(i) - f.x(j)) + sq(f.y(i) - f.y(j)) + sq(f.z(i) - f.z(j))));
                    closest += minPair;
                }
                double mean = sum / samples;
                String at = "seed " + seed + ", " + n + " fireflies: ";
                assertTrue(mean > 0.6 && mean < 2.0, at + "mean distance from the centre " + mean);
                assertTrue(worst < 3.5, at + "one strayed " + worst + " blocks off");
                assertTrue(closest / samples > 0.2, at + "heaped together: nearest pair " + closest / samples + " apart on average");
            }
        }
    }

    @Test
    void theyNeverStopFlying() {
        Flock f = flock(4, 12, 0, 64, 0);
        for (int t = 0; t < 600; t++) {
            f.step(0, 64, 0, Flock.ANYWHERE);
            for (int i = 0; i < f.size(); i++) assertTrue(speed(f, i) >= Flock.MIN_SPEED - 1.0E-9);
        }
    }

    @Test
    void aBurstBlowsThemApartAndTheyGatherAgain() {
        Flock f = flock(5, 16, 0, 64, 0);
        for (int t = 0; t < 400; t++) f.step(0, 64, 0, Flock.ANYWHERE);
        double before = meanDist(f, 0, 64, 0);
        // struck from the east: they fly off westwards, mostly
        f.burst(3.0, 64, 0);
        assertEquals(1.0, f.scattering(), 1.0E-12);
        double west = 0;
        for (int i = 0; i < f.size(); i++) west += -f.vx(i) / f.size();
        assertTrue(west > 0.1, "away from the strike, got a mean westward speed of " + west);
        double widest = 0;
        for (int t = 0; t < 20; t++) {
            f.step(0, 64, 0, Flock.ANYWHERE);
            widest = Math.max(widest, meanDist(f, 0, 64, 0));
        }
        assertTrue(widest > before + 1.5, "blown apart: " + before + " then " + widest);
        for (int t = 0; t < 100; t++) f.step(0, 64, 0, Flock.ANYWHERE);
        assertEquals(0.0, f.scattering(), 1.0E-12);
        assertTrue(meanDist(f, 0, 64, 0) < 2.0, "together again five seconds later: " + meanDist(f, 0, 64, 0));
    }

    @Test
    void theyKeepOutOfRock() {
        // a low tunnel: rock below y 64 and from y 66.5 up, and a wall at x 2
        Flock.Space tunnel = (x, y, z) -> y >= 64 && y < 66.5 && x < 2;
        Flock f = flock(6, 16, 0, 65, 0);
        for (int t = 0; t < 2000; t++) {
            // the centre wanders along the tunnel, now and then right up against the wall
            double cx = -2 + 3.5 * Math.sin(t * 0.01);
            f.step(cx, 65.2, 0, tunnel);
            if (t == 900) f.burst(cx - 1, 65.2, 0);           // a burst flings them at the wall
            for (int i = 0; i < f.size(); i++)
                assertTrue(tunnel.open(f.x(i), f.y(i), f.z(i)), "tick " + t + ": a firefly in the rock at " + f.x(i) + " " + f.y(i) + " " + f.z(i));
        }
    }

    @Test
    void aFireflyLostBehindAWallComesBack() {
        // a wall at x 1 to 2; the firefly ends up behind it, far from the centre
        Flock.Space wall = (x, y, z) -> x < 1 || x >= 2;
        Flock f = flock(7, 1, 0, 64, 0);
        f.put(0, 6.0, 64, 0, 0, 0, 0);
        for (int t = 0; t < 400; t++) f.step(-1, 64, 0, wall);
        assertTrue(distTo(f, 0, -1, 64, 0) < 2.5, "back with the swarm: " + distTo(f, 0, -1, 64, 0));
    }

    // ------------------------------------------------------------------------------------------------ landing

    private static List<Perches.Spot> floorSpots(long seed) {
        List<Perches.Spot> spots = new ArrayList<>();
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) Perches.face(x, 64, z, 0, -1, 0, seed, spots);
        return spots;
    }

    @Test
    void landedTheySitOnTheirOwnPointsAndSettleIntoTheirBreath() {
        Flock f = flock(8, 16, 0.5, 65, 0.5);
        for (int t = 0; t < 100; t++) f.step(0.5, 65, 0.5, Flock.ANYWHERE);
        List<Perches.Spot> spots = floorSpots(8);
        int[] pick = Perches.pick(spots, 0.5, 64.25, 0.5, f.size(), 8);
        for (int i = 0; i < f.size(); i++) f.settleOn(i, spots.get(pick[i]), i * 2);
        for (int t = 0; t < 300; t++) f.step(0.5, 64.25, 0.5, Flock.ANYWHERE);
        Set<String> seats = new HashSet<>();
        for (int i = 0; i < f.size(); i++) {
            Perches.Spot s = spots.get(pick[i]);
            assertEquals(Flock.PERCH, f.mode(i), "firefly " + i + " never got there");
            assertEquals(s.x(), f.x(i), 1.0E-12);
            assertEquals(s.y(), f.y(i), 1.0E-12);
            assertEquals(s.z(), f.z(i), 1.0E-12);
            assertEquals(1.0, f.settledness(i, 1.0), 1.0E-12, "fully into its breathing");
            assertEquals(0.0, speed(f, i), 1.0E-12, "sitting still");
            seats.add(f.x(i) + " " + f.z(i));
        }
        assertEquals(f.size(), seats.size(), "each on a point of its own");
    }

    @Test
    void theirGlowEasesRatherThanSnaps() {
        Flock f = flock(9, 1, 0.5, 64.5, 0.5);
        List<Perches.Spot> spots = floorSpots(9);
        f.seat(0, spots.get(Perches.pick(spots, 0.5, 64.25, 0.5, 1, 9)[0]));
        assertEquals(1.0, f.settledness(0, 0.0), 1.0E-12, "seated at once is settled at once");
        f.takeOff();
        double last = 1.0;
        boolean flying = false;
        for (int t = 0; t < 40; t++) {
            f.step(0.5, 65, 0.5, Flock.ANYWHERE);
            double s = f.settledness(0, 1.0);
            assertTrue(s <= last + 1.0E-12 && last - s <= Flock.WAKE_EASE + 1.0E-12, "an even fade, not a jump");
            last = s;
            flying |= f.mode(0) == Flock.FLY;
        }
        assertTrue(flying);
        assertEquals(0.0, last, 1.0E-12, "back to its steady flying glow");
    }

    @Test
    void takingOffTheyLiftAwayFromTheirFacesOneAfterAnother() {
        Flock f = flock(10, 16, 0.5, 65, 0.5);
        List<Perches.Spot> spots = floorSpots(10);
        int[] pick = Perches.pick(spots, 0.5, 64.25, 0.5, f.size(), 10);
        for (int i = 0; i < f.size(); i++) f.seat(i, spots.get(pick[i]));
        f.takeOff();
        Set<Integer> liftedAt = new HashSet<>();
        double[] y0 = new double[f.size()];
        for (int i = 0; i < f.size(); i++) y0[i] = f.y(i);
        boolean[] up = new boolean[f.size()];
        for (int t = 0; t <= Flock.LIFT_WAIT + 2; t++) {
            f.step(0.5, 65, 0.5, Flock.ANYWHERE);
            for (int i = 0; i < f.size(); i++)
                if (!up[i] && f.mode(i) == Flock.FLY) {
                    up[i] = true;
                    liftedAt.add(t);
                    assertTrue(f.vy(i) > 0, "off a floor it rises");
                }
        }
        for (int i = 0; i < f.size(); i++) assertTrue(up[i], "firefly " + i + " still sitting");
        assertTrue(liftedAt.size() > 3, "not all at once: lifted in " + liftedAt.size() + " different ticks");
        for (int t = 0; t < 10; t++) f.step(0.5, 65, 0.5, Flock.ANYWHERE);
        for (int i = 0; i < f.size(); i++) assertTrue(f.y(i) > y0[i], "away from the floor");
    }

    @Test
    void aBurstKnocksThemOffTheirPerches() {
        Flock f = flock(11, 8, 0.5, 65, 0.5);
        List<Perches.Spot> spots = floorSpots(11);
        int[] pick = Perches.pick(spots, 0.5, 64.25, 0.5, f.size(), 11);
        for (int i = 0; i < f.size(); i++) f.seat(i, spots.get(pick[i]));
        f.burst(0.5, 64.5, 2.5);
        for (int i = 0; i < f.size(); i++) {
            assertEquals(Flock.FLY, f.mode(i));
            assertTrue(speed(f, i) > 0.1);
        }
    }

    // ------------------------------------------------------------------------------------------------ comings and goings

    @Test
    void theEatenAreTheNearestAndTheRestKeepTheirNumbers() {
        Flock f = flock(12, 10, 0, 64, 0);
        for (int i = 0; i < 10; i++) f.put(i, i, 64, 0, 0, 0, 0);    // in a row along x
        int[] before = new int[10];
        for (int i = 0; i < 10; i++) before[i] = f.id(i);
        f.removeNearest(-0.2, 64, 0, 3);
        assertEquals(7, f.size());
        Set<Integer> left = new HashSet<>();
        for (int i = 0; i < f.size(); i++) {
            assertTrue(f.x(i) > 2.5, "one of the three nearest the jaws is still there: x " + f.x(i));
            left.add(f.id(i));
            // whatever index it now has, a firefly is still the one it was (its pulse never jumps)
            assertEquals(before[(int) Math.round(f.x(i))], f.id(i));
        }
        assertEquals(7, left.size());
        int i = f.add(0, 64, 0);
        assertEquals(7, i);
        assertFalse(left.contains(f.id(i)), "a newcomer has a number of its own");
    }

    @Test
    void aFlockHoldsAtMostMax() {
        Flock f = new Flock(13);
        for (int i = 0; i < Flock.MAX; i++) assertEquals(i, f.add(0, 64, 0));
        assertEquals(-1, f.add(0, 64, 0));
        assertEquals(Flock.MAX, f.size());
    }

    @Test
    void theSameSeedFliesTheSameWay() {
        Flock a = flock(14, 12, 0, 64, 0), b = flock(14, 12, 0, 64, 0);
        for (int t = 0; t < 300; t++) {
            a.step(Math.sin(t * 0.02), 64, 0, Flock.ANYWHERE);
            b.step(Math.sin(t * 0.02), 64, 0, Flock.ANYWHERE);
        }
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.x(i), b.x(i), 1.0E-12);
            assertEquals(a.y(i), b.y(i), 1.0E-12);
            assertEquals(a.z(i), b.z(i), 1.0E-12);
        }
    }
}
