package com.explorationremix.creature.firefly;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A swarm's flock: each firefly's steering (apart, along, together, back to the centre), the loose cloud they make
 * round a drifting centre and how it widens with the swarm, a burst and the regrouping after it, keeping out of rock,
 * parting round a body running through and leaving room round one standing among them, settling on perches and lifting
 * off them, calmly or startled, and fireflies coming and going.
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

    /** A player's box (0.6 wide, 1.8 tall) with its feet at (x, feet, z): min x y z, max x y z. */
    private static double[] playerBox(double x, double feet, double z) {
        return new double[]{x - 0.3, feet, z - 0.3, x + 0.3, feet + 1.8, z + 0.3};
    }

    /** How far firefly i is from the box (0 inside it). */
    private static double gap(Flock f, int i, double[] b) {
        double dx = Math.max(Math.max(b[0] - f.x(i), 0.0), f.x(i) - b[3]);
        double dy = Math.max(Math.max(b[1] - f.y(i), 0.0), f.y(i) - b[4]);
        double dz = Math.max(Math.max(b[2] - f.z(i), 0.0), f.z(i) - b[5]);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void addBody(Flock f, double[] b, double mx, double my, double mz) {
        f.addBody(b[0], b[1], b[2], b[3], b[4], b[5], mx, my, mz);
    }

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
                    // no faster about the swarm than its limit, the swarm's own drift on top
                    for (int i = 0; i < f.size(); i++)
                        assertTrue(speed(f, i) <= Flock.MAX_SPEED + Flock.CATCH_UP + 0.05 + 1.0E-9, "too fast: " + speed(f, i));
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
    void aBiggerSwarmMakesAWiderCloudThatLooksNoThicker() {
        assertEquals(Flock.BASE_RADIUS, Flock.radius(1), 1.0E-12);
        assertEquals(Flock.BASE_RADIUS, Flock.radius(16), 1.0E-12);
        assertEquals(Flock.BASE_RADIUS * 2.0, Flock.radius(64), 1.0E-9, "four times as many, twice as wide");
        assertTrue(Flock.radius(Flock.MAX) <= Flock.MAX_RADIUS);
        int[] sizes = {16, 140};
        double[] spread = new double[2], seen = new double[2];
        for (int k = 0; k < 2; k++) {
            Flock f = flock(17, sizes[k], 0, 64, 0);
            int samples = 0;
            for (int t = 0; t < 1200; t++) {
                f.step(0, 64, 0, Flock.ANYWHERE);
                if (t < 400 || t % 10 != 0) continue;
                spread[k] += meanDist(f, 0, 64, 0);
                // seen from the side, how far each firefly looks from its nearest neighbour: the middle of those
                double[] apart = new double[f.size()];
                for (int i = 0; i < f.size(); i++) {
                    double best = Double.MAX_VALUE;
                    for (int j = 0; j < f.size(); j++)
                        if (j != i) best = Math.min(best, Math.sqrt(sq(f.x(i) - f.x(j)) + sq(f.y(i) - f.y(j))));
                    apart[i] = best;
                }
                Arrays.sort(apart);
                seen[k] += apart[apart.length / 2];
                samples++;
            }
            spread[k] /= samples;
            seen[k] /= samples;
        }
        assertTrue(spread[1] > spread[0] * 2.5, "a wider cloud: " + spread[0] + " for 16, " + spread[1] + " for 140");
        assertTrue(seen[1] > seen[0] * 0.85, "no thicker to look at: " + seen[0] + " apart for 16, " + seen[1] + " for 140");
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

    // ------------------------------------------------------------------------------------------------ bodies among them

    /**
     * Runs a player along +x through the middle of a cloud of {@code n} round (0, 64, 0), its eyes a little above the
     * middle, at {@code speed} blocks a tick from 12 blocks off to 12 past. Returns the least gap any firefly had to its
     * box, the mean gap of the five nearest as it crossed the middle, and the cloud's mean distance from its centre
     * before it came and three seconds after it left.
     */
    private static double[] runThrough(long seed, int n, double speed) {
        Flock f = flock(seed, n, 0, 64, 0);
        for (int t = 0; t < 400; t++) f.step(0, 64, 0, Flock.ANYWHERE);
        double before = meanDist(f, 0, 64, 0);
        double least = Double.MAX_VALUE, middle = 0;
        int middleN = 0;
        for (double px = -12; px < 12; px += speed) {
            double[] b = playerBox(px, 62.8, 0.15);
            f.clearBodies();
            addBody(f, b, speed, 0, 0);
            f.step(0, 64, 0, Flock.ANYWHERE);
            double[] g = new double[f.size()];
            for (int i = 0; i < f.size(); i++) {
                g[i] = gap(f, i, b);
                least = Math.min(least, g[i]);
            }
            if (Math.abs(px) < 0.6) {
                Arrays.sort(g);
                for (int k = 0; k < 5; k++) middle += g[k] / 5;
                middleN++;
            }
        }
        f.clearBodies();
        for (int t = 0; t < 60; t++) f.step(0, 64, 0, Flock.ANYWHERE);
        return new double[]{least, middle / middleN, before, meanDist(f, 0, 64, 0)};
    }

    @Test
    void aRunnerPartsTheCloudAndItClosesUpBehind() {
        for (long seed : new long[]{1, 2, 3}) {
            for (int n : new int[]{45, 160}) {
                double[] r = runThrough(seed, n, Flock.RUN);
                String at = "seed " + seed + ", " + n + " fireflies: ";
                assertTrue(r[0] > 0.9, at + "one came within " + r[0] + " of the runner");
                assertTrue(r[3] < r[2] * 1.15, at + "not together again: " + r[2] + " from the centre before, " + r[3] + " after");
            }
        }
    }

    @Test
    void theFasterTheBodyTheWiderTheGap() {
        double sneak = 0, walk = 0, sprint = 0;
        for (long seed : new long[]{1, 2, 3}) {
            sneak += runThrough(seed, 45, 0.065)[1] / 3;
            walk += runThrough(seed, 45, 0.216)[1] / 3;
            sprint += runThrough(seed, 45, Flock.RUN)[1] / 3;
        }
        String gaps = "sneaking " + sneak + ", walking " + walk + ", sprinting " + sprint;
        assertTrue(sneak < walk && walk < sprint, gaps);
        assertTrue(sneak > 0.8, "a block even sneaking: " + gaps);
        assertTrue(sprint > 1.3 && sprint < 2.2, "a block or two at a sprint: " + gaps);
    }

    @Test
    void nothingIsPushedAlongInFrontOfARunner() {
        for (int k = 0; k < 2; k++) {
            // one firefly (the first an even number, the second odd) hovering right on the runner's line
            Flock f = flock(18, k + 1, 0, 64.4, 0);
            int i = k;
            f.put(i, 0, 64.4, 0, 0, 0, 0);
            double least = Double.MAX_VALUE, furthest = 0;
            for (double px = -8; px < 6; px += Flock.RUN) {
                double[] b = playerBox(px, 62.8, 0);
                f.clearBodies();
                addBody(f, b, Flock.RUN, 0, 0);
                f.step(0, 64.4, 0, Flock.ANYWHERE);
                least = Math.min(least, gap(f, i, b));
                furthest = Math.max(furthest, f.x(i));
            }
            assertTrue(least > 0.7, "it got out of the way: came within " + least);
            assertTrue(furthest < 2.5, "not shoved along ahead: carried to x " + furthest);
            assertTrue(f.x(i) < 5.0, "the runner got past it");
        }
    }

    @Test
    void someoneStandingAmongThemGetsAboutABlockAndTheyDriftRound() {
        Flock f = flock(19, 45, 0, 64, 0);
        double[] b = playerBox(0, 62.8, 0);
        double least = Double.MAX_VALUE, nearest = 0, around = 0;
        int samples = 0;
        for (int t = 0; t < 900; t++) {
            f.clearBodies();
            if (t >= 100) addBody(f, b, 0, 0, 0);
            f.step(0, 64, 0, Flock.ANYWHERE);
            if (t < 300) continue;
            double n = Double.MAX_VALUE;
            for (int i = 0; i < f.size(); i++) {
                n = Math.min(n, gap(f, i, b));
                assertTrue(speed(f, i) >= Flock.MIN_SPEED - 1.0E-9, "they keep flying");
            }
            least = Math.min(least, n);
            nearest += n;
            around += meanDist(f, 0, 64, 0);
            samples++;
        }
        nearest /= samples;
        assertTrue(least > 0.6, "one came within " + least);
        assertTrue(nearest > 0.8 && nearest < 1.3, "about a block of room: the nearest is " + nearest + " off on average");
        assertTrue(around / samples < Flock.radius(45) + 0.5, "they stay round them rather than leave: " + around / samples);
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

    /** A landed swarm of {@code n} on a floor round (0.5, 64.25, 0.5). */
    private static Flock seated(long seed, int n) {
        Flock f = flock(seed, n, 0.5, 65, 0.5);
        List<Perches.Spot> spots = new ArrayList<>();
        for (int x = -7; x <= 7; x++)
            for (int z = -7; z <= 7; z++) Perches.face(x, 64, z, 0, -1, 0, seed, spots);
        int[] pick = Perches.pick(spots, 0.5, 64.25, 0.5, n, seed);
        for (int i = 0; i < f.size(); i++) f.seat(i, spots.get(pick[i]));
        return f;
    }

    @Test
    void startledTheyGoUpInAWaveFromTheThreatsSide() {
        for (int n : new int[]{45, 140}) {
            Flock f = seated(20, n);
            // the threat stands off to the west, at eye height
            double tx = -6.0, ty = 65.6, tz = 0.5;
            double[] dist = new double[f.size()];
            for (int i = 0; i < f.size(); i++) dist[i] = distTo(f, i, tx, ty, tz);
            f.flush(tx, ty, tz);
            assertEquals(1.0, f.hurry(), 1.0E-12);
            int[] liftedAt = new int[f.size()];
            Arrays.fill(liftedAt, -1);
            for (int t = 1; t <= 60; t++) {
                f.step(0.5, 65.5, 0.5, Flock.ANYWHERE);
                for (int i = 0; i < f.size(); i++)
                    if (liftedAt[i] < 0 && f.mode(i) == Flock.FLY) {
                        liftedAt[i] = t;
                        assertTrue(f.flare(i, 1.0) > 0.9, "it flares as it goes: " + f.flare(i, 1.0));
                        assertTrue(f.vx(i) > 0.0 && f.vy(i) > 0.05, "up and away from the threat");
                    }
            }
            double near = Double.MAX_VALUE, far = 0;
            int last = 0;
            for (int i = 0; i < f.size(); i++) {
                assertTrue(liftedAt[i] > 0, n + ": firefly " + i + " still sitting");
                near = Math.min(near, dist[i]);
                far = Math.max(far, dist[i]);
                last = Math.max(last, liftedAt[i]);
            }
            // a wave: the later the further from the threat, crossing the patch at the ripple's speed
            double r = correlation(dist, liftedAt);
            assertTrue(r > 0.85, n + ": not a wave from the threat's side, correlation " + r);
            assertTrue(last <= (far - near) / Flock.RIPPLE + Flock.RIPPLE_JITTER + 2, n + ": the last went up at tick " + last);
            assertTrue(last >= (far - near) / Flock.RIPPLE - Flock.RIPPLE_JITTER - 2, n + ": too quick, all up by tick " + last);
        }
    }

    private static double correlation(double[] a, int[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i] / a.length; mb += b[i] / (double) a.length; }
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < a.length; i++) {
            sab += (a[i] - ma) * (b[i] - mb);
            saa += sq(a[i] - ma);
            sbb += sq(b[i] - mb);
        }
        return sab / Math.sqrt(saa * sbb);
    }

    @Test
    void startledTheyKeepUpWithTheirSwarmAsItFlees() {
        Flock f = seated(21, 60);
        f.flush(-6.0, 65.6, 0.5);
        // the swarm's centre goes up off the floor and away east, as fast as a startled swarm flees
        double cx = 0.5, cy = 64.6, worst = 0, flare = 0;
        for (int t = 1; t <= Flock.HURRY_TICKS; t++) {
            cx += 0.2;
            cy = Math.min(67.0, cy + 0.05);
            f.step(cx, cy, 0.5, Flock.ANYWHERE);
            if (t > 30) worst = Math.max(worst, meanDist(f, cx, cy, 0.5));
            if (t == 30) for (int i = 0; i < f.size(); i++) flare = Math.max(flare, f.flare(i, 1.0));
        }
        assertEquals(0.0, f.hurry(), 1.0E-12, "the hurry is over");
        assertEquals(0.0, flare, 1.0E-12, "their flare has faded");
        assertTrue(worst < Flock.radius(60) + 1.0, "they kept up with it: " + worst + " from the centre on average");
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
