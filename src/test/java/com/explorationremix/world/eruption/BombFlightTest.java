package com.explorationremix.world.eruption;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the lava bombs go (0.9.0, spec 5.4.2): flights flown here with the projectile's own order and constants
 * ({@code ThrowableProjectile.tick}: the hit test on pos..pos+v, then {@code next = pos + v}, {@code v *= 0.99F},
 * {@code v.y -= getGravity()}, {@code pos = next}) land where they were aimed, rise to their apex, clear their stack,
 * whistle long enough before they land and stay within what the packets carry; near bombs come down near their player or
 * not at all; the refuge test on voxel ground; and in a fluid the bomb fizzles before the rock under it.
 */
class BombFlightTest {
    /** The game's own numbers, as the projectile's floats widen: its drag, and the bomb's gravity. */
    static final double DRAG = 0.99F, GRAVITY = 0.05F;
    /** A crater's radius for the launch offsets (the field's craters are 2 to 4 across the radius). */
    static final double CRATER_R = 3;

    /**
     * Flies a bomb from (ox, 0, oz) over level ground {@code h} blocks under the launch point: {x, z, ticks with the
     * fraction of the last, apex}, or null when it does not come down within 400 ticks.
     */
    static double[] land(double ox, double oz, BombFlight.Flight f, double h) {
        double x = ox, y = 0, z = oz, vx = f.vx(), vy = f.vy(), vz = f.vz(), apex = 0;
        for (int k = 0; k < 400; k++) {
            double nx = x + vx, ny = y + vy, nz = z + vz;
            if (vy < 0 && ny <= -h) {
                double q = (y + h) / (y - ny);
                return new double[]{x + q * vx, z + q * vz, k + q, apex};
            }
            x = nx;
            y = ny;
            z = nz;
            apex = Math.max(apex, y);
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            vz *= DRAG;
        }
        return null;
    }

    /** A launch offset within {@code 0.6 r} of the axis, as Eruptions draws it. */
    static double[] offset(Random r) {
        double a = r.nextDouble() * Math.PI * 2, d = 0.6 * CRATER_R * Math.sqrt(r.nextDouble());
        return new double[]{d * Math.cos(a), d * Math.sin(a)};
    }

    @Test
    void theConstantsAreTheGames() {
        assertEquals(DRAG, BombFlight.DRAG);
        assertEquals(GRAVITY, BombFlight.GRAVITY);
        assertEquals(0.05F, (float) BombFlight.GRAVITY);
    }

    @Test
    void aSolvedFlightLandsWhereItWasAimedAndPeaksAtItsApex() {
        Random r = new Random(5);
        double worst = 0, worstApex = 0, minT = 1e9, maxT = 0, maxV = 0;
        int n = 0;
        for (double d = 11; d <= 40; d += 1.5) {
            for (double h = 0; h <= 60; h += 5) {
                for (double a = 12; a <= 30; a += 3) {
                    double[] o = offset(r);
                    double ang = r.nextDouble() * Math.PI * 2, tx = d * Math.cos(ang), tz = d * Math.sin(ang);
                    BombFlight.Flight f = BombFlight.solve(o[0], o[1], tx, tz, h, a);
                    assertNotNull(f, "d " + d + " h " + h + " a " + a);
                    double[] l = land(o[0], o[1], f, h);
                    assertNotNull(l);
                    double miss = Math.hypot(l[0] - tx, l[1] - tz);
                    worst = Math.max(worst, miss);
                    assertTrue(miss <= 1.5, "d " + d + " h " + h + " a " + a + ": lands " + miss + " off");
                    assertEquals(Math.hypot(l[0], l[1]), d, 1.5);
                    worstApex = Math.max(worstApex, Math.abs(l[3] - a));
                    assertEquals(a, l[3], 1.0, "apex");
                    assertEquals(f.ticks(), l[2], 0.05, "the solver's time is the flight's");
                    minT = Math.min(minT, l[2]);
                    maxT = Math.max(maxT, l[2]);
                    // the add packet carries each part of the motion clamped to 3.9 blocks a tick
                    double v = Math.max(Math.abs(f.vx()), Math.max(Math.abs(f.vy()), Math.abs(f.vz())));
                    maxV = Math.max(maxV, f.speed());
                    assertTrue(v < 3.9, "a part of the motion over 3.9: " + f);
                    n++;
                }
            }
        }
        System.out.printf("BombFlightTest: %d flights land within %.4f of their aim, apex within %.3f; %.0f to %.0f ticks, at most %.2f blocks a tick%n",
                n, worst, worstApex, minT, maxT, maxV);
        assertTrue(worst < 0.05, "the drag's sum puts them on the spot: " + worst);
    }

    @Test
    void ringBombsClearTheirStackAndNeverLandOnIt() {
        Random r = new Random(9);
        int n = 0, moved = 0;
        for (double stackR = 5; stackR <= 9; stackR += 1) {
            for (int i = 0; i < 400; i++) {
                double[] o = offset(r);
                double a = BombFlight.apex(0.8 + r.nextDouble() * 0.4, 80, r.nextDouble());
                double h = r.nextDouble() * 60;
                double d = BombFlight.ringDistance(stackR, r.nextDouble()), ang = r.nextDouble() * Math.PI * 2;
                assertTrue(d >= stackR + 6 && d <= 40, "ring distance " + d);
                double hh = h;
                BombFlight.Flight f = BombFlight.aim(o[0], o[1], d * Math.cos(ang), d * Math.sin(ang), (x, z) -> hh, a, stackR, 2.5);
                assertNotNull(f, "stack " + stackR + " apex " + a + " drop " + h);
                if (Math.hypot(f.tx(), f.tz()) > d + 0.5) moved++;
                // coming back down through the rim's row plus 3 (2.5 over the launch point) it is clear of the stack
                double clear = clearAt(o[0], o[1], f, 2.5);
                assertTrue(clear >= stackR + BombFlight.CLEAR - 1e-9, "stack " + stackR + ": " + clear + " from the axis at the rim");
                double[] l = land(o[0], o[1], f, h);
                assertNotNull(l);
                assertTrue(Math.hypot(l[0], l[1]) > stackR, "landed on its own stack");
                n++;
            }
        }
        System.out.printf("BombFlightTest: %d ring bombs clear their stacks (%d moved out to)%n", n, moved);
        // a target too close for a high, deep flight: it comes back down past the rim over the stack, so the target moves
        // out a block at a time until it clears; with too few tries left it is not thrown
        BombFlight.Flight f = BombFlight.aim(0, 0, 10, 0, (x, z) -> 60, 30, 9, 2.5);
        assertNotNull(f);
        assertTrue(f.tx() > 10.5 && f.tx() <= 20 + 1e-9 && Math.abs(f.tz()) < 1e-9, "moved out along its line: " + f.tx());
        assertTrue(clearAt(0, 0, f, 2.5) >= 9 + BombFlight.CLEAR, "clears once moved");
        BombFlight.Flight once = BombFlight.solve(0, 0, 10, 0, 60, 30);
        assertTrue(clearAt(0, 0, once, 2.5) < 9 + BombFlight.CLEAR, "the first aim did not clear");
        assertNull(BombFlight.aim(0, 0, 1, 0, (x, z) -> 60, 30, 30, 2.5), "a stack too wide to clear in 10 moves");
    }

    /** How far from the axis the flight is as it comes back down through {@code y} over the launch point (flown). */
    static double clearAt(double ox, double oz, BombFlight.Flight f, double y) {
        double x = ox, py = 0, z = oz, vx = f.vx(), vy = f.vy(), vz = f.vz();
        for (int k = 0; k < 400; k++) {
            double nx = x + vx, ny = py + vy, nz = z + vz;
            if (vy < 0 && py >= y && ny < y) {
                double q = (py - y) / (py - ny);
                return Math.hypot(x + q * vx, z + q * vz);
            }
            x = nx;
            py = ny;
            z = nz;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            vz *= DRAG;
        }
        return Double.POSITIVE_INFINITY;
    }

    @Test
    void aBombIsHeardLongEnoughBeforeItLands() {
        Random r = new Random(13);
        double least = 1e9;
        for (double h = 20; h <= 60; h += 2.5) {
            for (double a = 12; a <= 30; a += 2) {
                for (double d = 11; d <= 40; d += 7) {
                    double[] o = offset(r);
                    BombFlight.Flight f = BombFlight.solve(o[0], o[1], d, 0, h, a);
                    assertNotNull(f);
                    double[] l = land(o[0], o[1], f, h);
                    // the ticks before the impact while within 96 blocks of the landing spot (the whistle's reach)
                    double x = o[0], y = 0, z = o[1], vx = f.vx(), vy = f.vy(), vz = f.vz();
                    int within = 0;
                    for (int k = 0; k < (int) l[2]; k++) {
                        double dx = x - l[0], dy = y + h, dz = z - l[1];
                        if (dx * dx + dy * dy + dz * dz <= 96 * 96) within++;
                        x += vx;
                        y += vy;
                        z += vz;
                        vx *= DRAG;
                        vy = vy * DRAG - GRAVITY;
                        vz *= DRAG;
                    }
                    least = Math.min(least, within);
                    assertTrue(within >= 50, "h " + h + " a " + a + " d " + d + ": heard " + within + " ticks");
                }
            }
        }
        System.out.printf("BombFlightTest: heard at least %.0f ticks before landing (drop 20 to 60)%n", least);
    }

    @Test
    void theApexIsCappedByTheRoomAndRefusedUnder12() {
        assertEquals(16, BombFlight.apex(1, 80, 0), 1e-9);
        assertEquals(30, BombFlight.apex(1, 80, 1), 1e-9);
        assertEquals(30 * Math.sqrt(1.2), BombFlight.apex(1.2, 80, 1), 1e-9);
        assertEquals(20, BombFlight.apex(1, 24, 1), 1e-9);
        assertEquals(12, BombFlight.apex(1, 16, 1), 1e-9);
        assertTrue(Double.isNaN(BombFlight.apex(1, 15, 1)), "too little room: not thrown");
        assertTrue(Double.isNaN(BombFlight.apex(0.25, 80, 0.5)), "a weak eruption's apex under 12: not thrown");
        // the bisection's speed gives the apex
        for (double a = 12; a <= 40; a += 4) assertEquals(a, BombFlight.apexOf(BombFlight.vyForApex(a)), 1e-6);
    }

    @Test
    void nearBombsComeDownNearTheirPlayerOrNotAtAll() {
        Random r = new Random(21);
        int near = 0, fell = 0;
        for (int i = 0; i < 2000; i++) {
            double stackR = 5 + r.nextInt(5);
            double pd = 8 + r.nextDouble() * 40, pa = r.nextDouble() * Math.PI * 2;
            double px = pd * Math.cos(pa), pz = pd * Math.sin(pa);
            double[] o = offset(r);
            double a = BombFlight.apex(1, 80, r.nextDouble()), h = r.nextDouble() * 40;
            // a hill on one side: the drop depends on where the bomb lands
            BombFlight.Drop drop = (x, z) -> h + (x > 0 ? 6 : 0);
            BombFlight.Flight f = BombFlight.near(o[0], o[1], px, pz, 3, 8, stackR, drop, (x, z) -> true, a, 2.5, r);
            if (f == null) {
                fell++;
                continue;
            }
            near++;
            double td = Math.hypot(f.tx() - px, f.tz() - pz);
            assertTrue(td >= 3 - 1e-9 && td <= 8 + 1e-9, "the target is " + td + " from the player");
            assertTrue(Math.hypot(f.tx(), f.tz()) >= stackR + 6 - 1e-9 && Math.hypot(f.tx(), f.tz()) <= 40 + 1e-9 + BombFlight.CLEAR_TRIES,
                    "the target is in the ring");
            double[] l = land(o[0], o[1], f, drop.at(f.tx(), f.tz()));
            double ld = Math.hypot(l[0] - px, l[1] - pz);
            assertTrue(ld >= 2.9 && ld <= 8.1, "it lands " + ld + " from the player");
        }
        System.out.printf("BombFlightTest: %d near bombs, %d fell back to ring bombs%n", near, fell);
        assertTrue(near > 800, "most players in the ring take their near bombs: " + near);
        // a player beside the vent (all the ring 3-8 round them inside the stack's band) takes none
        assertNull(BombFlight.near(0, 0, 0, 0, 3, 8, 7, (x, z) -> 10, (x, z) -> true, 20, 2.5, r));
        // nor one whose ground does not tick
        assertNull(BombFlight.near(0, 0, 25, 0, 3, 8, 7, (x, z) -> 10, (x, z) -> false, 20, 2.5, r));
    }

    @Test
    void theBombsGoMostlyInTheFirstSecondAtMostTwoATick() {
        Random r = new Random(3);
        for (int i = 0; i < 500; i++) {
            int n = 1 + r.nextInt(14), burst = 150 + r.nextInt(101);
            int[] p = BombFlight.launches(n, burst, r);
            assertEquals(n, p.length);
            int head = 0;
            for (int k = 0; k < p.length; k++) {
                assertTrue(p[k] >= 0 && p[k] < burst);
                if (k > 0) assertTrue(p[k] >= p[k - 1], "sorted");
                if (k > 1) assertTrue(p[k] > p[k - 2], "at most two a tick");
                if (p[k] < 20) head++;
            }
            assertTrue(head >= Math.round(n * 0.4), "40 % in the first second: " + head + " of " + n);
        }
        // more bombs than a short burst has room for: two a tick, the rest not thrown
        int[] p = BombFlight.launches(100, 30, r);
        assertEquals(60, p.length);
        assertEquals(0, BombFlight.launches(0, 200, r).length);
        assertEquals(10, BombFlight.launches(10, 12, r).length);
    }

    // ================================================================================================ the refuge test

    /** Voxel ground round a player's feet: {@code ground[dx + 3][dz + 3]} the top solid row, relative to the feet. */
    static BombFlight.Cells ground(int[][] top, int[][] lavaTop) {
        return (dx, dy, dz) -> {
            if (Math.abs(dx) > 3 || Math.abs(dz) > 3) return BombFlight.SOLID;
            int t = top[dx + 3][dz + 3], l = lavaTop == null ? Integer.MIN_VALUE : lavaTop[dx + 3][dz + 3];
            if (dy <= t) return BombFlight.SOLID;
            if (dy <= l) return BombFlight.LAVA;
            return BombFlight.OPEN;
        };
    }

    static int[][] flat(int t) {
        int[][] g = new int[7][7];
        for (int[] row : g) java.util.Arrays.fill(row, t);
        return g;
    }

    @Test
    void theRefugeTest() {
        // flat ground: the feet on the row over it
        assertFalse(BombFlight.refuge(ground(flat(-1), null)), "flat ground is no refuge");
        // a step of 2 and a wall: still no refuge
        int[][] steps = flat(-1);
        for (int z = 0; z < 7; z++) {
            steps[0][z] = -3;
            steps[6][z] = 4;
        }
        assertFalse(BombFlight.refuge(ground(steps, null)));
        // a stack top: the player on a 3 x 3 top, a long fall all round
        int[][] stack = flat(-30);
        for (int x = 2; x <= 4; x++) for (int z = 2; z <= 4; z++) stack[x][z] = -1;
        assertTrue(BombFlight.refuge(ground(stack, null)), "a stack top");
        // a ledge: 5 rows down on one side within 3 blocks, refused; 4 rows, not
        int[][] ledge = flat(-1);
        for (int z = 0; z < 7; z++) ledge[6][z] = -6;
        assertTrue(BombFlight.refuge(ground(ledge, null)), "a ledge with a 5-row drop");
        for (int z = 0; z < 7; z++) ledge[6][z] = -5;
        assertFalse(BombFlight.refuge(ground(ledge, null)), "a drop of 4 rows is not a ledge");
        // a shore strip: lava 2 blocks off at the feet's row less 1
        int[][] shore = flat(-1), lava = new int[7][7];
        for (int[] row : lava) java.util.Arrays.fill(row, Integer.MIN_VALUE);
        for (int z = 0; z < 7; z++) {
            shore[5][z] = -3;
            lava[5][z] = -1;
            shore[6][z] = -3;
            lava[6][z] = -1;
        }
        assertTrue(BombFlight.refuge(ground(shore, lava)), "a strip of shore by the lava");
        // lava 4 rows under the feet, 3 blocks off over a bank of 4 rows: still the shore
        int[][] bank = flat(-1), deep = new int[7][7];
        for (int[] row : deep) java.util.Arrays.fill(row, Integer.MIN_VALUE);
        for (int z = 0; z < 7; z++) {
            bank[6][z] = -5;
            deep[6][z] = -4;
        }
        assertTrue(BombFlight.refuge(ground(bank, deep)), "lava 4 rows down");
        // the same bank dry is no refuge
        assertFalse(BombFlight.refuge(ground(bank, null)));
    }

    // ================================================================================================ into the lava

    @Test
    void inAFluidTheBombFizzlesBeforeTheRockUnderIt() {
        // rock up to row 9, a pool of lava one row deep (row 10) over it, open over that
        BombFlight.Grid pool = (x, y, z) -> y <= 9 ? BombFlight.SOLID : y == 10 && x >= 0 ? BombFlight.LAVA : BombFlight.OPEN;
        int[] cell = new int[3];
        // a fast bomb whose last segment passes through the pool and into the rock under it in one tick
        int out = BombFlight.fly(pool, new double[]{5.5, 12.2, 0.5}, new double[]{0.3, -2.9, 0}, 10, cell);
        assertEquals(BombFlight.FIZZLE, out, "the fluid stops it before the rock");
        assertEquals(10, cell[1]);
        // the same over dry rock (x < 0) bursts on it
        out = BombFlight.fly(pool, new double[]{-5.5, 12.2, 0.5}, new double[]{0.3, -2.9, 0}, 10, cell);
        assertEquals(BombFlight.BURST, out);
        assertEquals(9, cell[1]);
        // a whole flight into a deep lake fizzles at its top, never bursting on the bed
        BombFlight.Grid lake = (x, y, z) -> y <= 0 ? BombFlight.SOLID : y <= 20 && x >= 20 ? BombFlight.LAVA : y <= 20 ? BombFlight.SOLID : BombFlight.OPEN;
        // launched at row 25.5, the lake's top at 21: 4.5 blocks down
        BombFlight.Flight f = BombFlight.solve(0, 0, 30, 0, 4.5, 20);
        assertNotNull(f);
        out = BombFlight.fly(lake, new double[]{0.5, 25.5, 0.5}, new double[]{f.vx(), f.vy(), f.vz()}, 400, cell);
        assertEquals(BombFlight.FIZZLE, out);
        assertEquals(20, cell[1]);
        // open air all the way: still flying
        assertEquals(BombFlight.FLYING, BombFlight.fly((x, y, z) -> BombFlight.OPEN, new double[]{0, 0, 0}, new double[]{1, 1, 0}, 50, cell));
    }
}
