package com.explorationremix.creature;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Skein's body, tick by tick as its head walks about: straight on its trail where the head ran straight; round
 * the head's sharp turns in curves no tighter than the lead turns, cutting each corner on its inside; looping round
 * one way where the head turned back; over edges on the rock; and never jerking: behind the neck it only ever slides
 * forwards along where the lead went, and it lies dead still while the head does.
 */
class SkeinSpineTest {
    private static final double EPS = 1.0E-6;
    /** As the Skein: its joints, and how much trail it keeps. */
    private static final int JOINTS = 50;
    private static final double KEEP = 9.75;
    private static final int NECK = SkeinSpine.NECK_JOINTS;

    /** A head walking about on rock, its trail, and the body after it, each tick as the Skein records and follows. */
    private static final class Walk {
        final SkeinTrail trail = new SkeinTrail(KEEP);
        final SkeinSpine spine = new SkeinSpine(JOINTS);
        double x, y, z, fx, fy, fz, nx = 0, ny = 1, nz = 0;
        /** Behind the neck, in any tick: the furthest a joint went back along the body, and moved further than the head. */
        double back, beyond;
        /** How many times a joint behind the neck moved back the way it had moved the tick before. */
        int reversals;
        private double[] lastMove;

        /** On the floor at (x, y, z), pointing along (fx, 0, fz), its body laid straight out behind. */
        Walk(double x, double y, double z, double fx, double fz) {
            this.x = x; this.y = y; this.z = z;
            double l = Math.hypot(fx, fz);
            this.fx = fx / l; this.fz = fz / l;
            trail.lay(x, y, z, -this.fx, 0, -this.fz, 0, 1, 0);
            spine.lay(trail);
        }

        void tick() {
            double[] was = spine.data().clone();
            trail.record(x, y, z, nx, ny, nz);
            spine.follow(trail, fx, fy, fz);
            double[] d = spine.data();
            double step = trail.lastStep();
            double[] move = new double[3 * JOINTS];
            for (int j = 0; j < JOINTS; j++) {
                double mx = d[6 * j] - was[6 * j], my = d[6 * j + 1] - was[6 * j + 1], mz = d[6 * j + 2] - was[6 * j + 2];
                move[3 * j] = mx; move[3 * j + 1] = my; move[3 * j + 2] = mz;
                if (j <= NECK) continue;
                double m = Math.sqrt(mx * mx + my * my + mz * mz);
                beyond = Math.max(beyond, m - step);
                // against the way on to the joint before it (towards the head), as it lay
                double tx = was[6 * (j - 1)] - was[6 * j], ty = was[6 * (j - 1) + 1] - was[6 * j + 1], tz = was[6 * (j - 1) + 2] - was[6 * j + 2];
                double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tl > 1.0E-9) back = Math.max(back, -(mx * tx + my * ty + mz * tz) / tl);
                if (lastMove != null) {
                    double ax = lastMove[3 * j], ay = lastMove[3 * j + 1], az = lastMove[3 * j + 2];
                    double al = Math.sqrt(ax * ax + ay * ay + az * az);
                    if (m > 0.02 && al > 0.02 && mx * ax + my * ay + mz * az < -0.3 * m * al) reversals++;
                }
            }
            lastMove = move;
        }

        /** On along the way it points, {@code speed} a tick. */
        void walk(double dist, double speed) {
            int n = (int) Math.ceil(dist / speed - 1.0E-9);
            for (int i = 0; i < n; i++) {
                x += fx * speed; y += fy * speed; z += fz * speed;
                tick();
            }
        }

        /** Its head turns where it stands, on the floor, {@code deg} (from +z towards +x) over {@code over} ticks. */
        void turn(double deg, int over) {
            double a0 = Math.atan2(fx, fz), a1 = a0 + Math.toRadians(deg);
            for (int i = 1; i <= over; i++) {
                double a = a0 + (a1 - a0) * i / over;
                fx = Math.sin(a); fy = 0; fz = Math.cos(a);
                tick();
            }
        }

        void still(int ticks) {
            for (int i = 0; i < ticks; i++) tick();
        }

        /** How far joint j is from the trail (from the straights between places on it a fiftieth of a block apart). */
        double offTrail(int j) {
            double[] d = spine.data(), a = new double[6], b = new double[6];
            double best = Double.MAX_VALUE, h = trail.headAlong();
            int n = (int) Math.ceil(KEEP / 0.02);
            trail.at(h, a, 0);
            for (int k = 1; k <= n; k++) {
                trail.at(h - KEEP * k / n, b, 0);
                double sx = b[0] - a[0], sy = b[1] - a[1], sz = b[2] - a[2], ss = sx * sx + sy * sy + sz * sz;
                double t = ss < 1.0E-12 ? 0.0 : ((d[6 * j] - a[0]) * sx + (d[6 * j + 1] - a[1]) * sy + (d[6 * j + 2] - a[2]) * sz) / ss;
                t = Math.max(0.0, Math.min(1.0, t));
                best = Math.min(best, sq(d[6 * j] - a[0] - sx * t) + sq(d[6 * j + 1] - a[1] - sy * t) + sq(d[6 * j + 2] - a[2] - sz * t));
                System.arraycopy(b, 0, a, 0, 6);
            }
            return Math.sqrt(best);
        }
    }

    private static double dist(double[] a, int i, double[] b, int j) {
        return Math.sqrt(sq(a[6 * i] - b[6 * j]) + sq(a[6 * i + 1] - b[6 * j + 1]) + sq(a[6 * i + 2] - b[6 * j + 2]));
    }

    private static double sq(double v) { return v * v; }

    @Test
    void laidStraightItLiesOnItsTrail() {
        SkeinTrail t = new SkeinTrail(KEEP);
        t.lay(3, 10, -2, 1, 0, 0, 0, 1, 0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.lay(t);
        assertTrue(s.on(t));
        for (int i = 0; i < JOINTS; i++) {
            assertEquals(3 + i * SkeinSpine.LINK, s.data()[6 * i], EPS, "joint " + i);
            assertEquals(10, s.data()[6 * i + 1], EPS);
            assertEquals(-2, s.data()[6 * i + 2], EPS);
            assertEquals(1.0, s.data()[6 * i + 4], EPS, "the floor's normal");
        }
        assertArrayEquals(s.data(), s.before(), "laid, it was where it is");
    }

    @Test
    void itsLinksKeepTheirLength() {
        Walk w = new Walk(0, 0, 0, 0, 1);
        w.walk(4, 0.25);
        w.turn(90, 1);
        w.walk(3, 0.25);
        double[] d = w.spine.data();
        for (int i = NECK + 1; i < JOINTS; i++) assertEquals(SkeinSpine.LINK, dist(d, i - 1, d, i), 0.003, "link " + i);
        double neck = 0;
        for (int i = 1; i <= NECK; i++) neck += dist(d, i - 1, d, i);
        assertEquals(SkeinSpine.NECK_LAG, neck, 0.03, "the neck");
    }

    @Test
    void aSharpTurnOfTheHeadIsARoundCurveInTheBody() {
        for (double deg : new double[]{60, 90, 135, 170}) {
            for (int over : new int[]{1, 3}) {
                Walk w = new Walk(0, 0, 0, 0, 1);
                w.walk(5, 0.25);
                w.turn(deg, over);
                w.walk(4, 0.25);
                double[] d = w.spine.data();
                for (int i = NECK + 1; i < JOINTS - 1; i++) {
                    double bend = SkeinSpine.bendAt(d, i);
                    assertTrue(bend <= SkeinSpine.TURN * SkeinSpine.LINK * 1.2,
                            deg + " degrees over " + over + ": bends " + Math.toDegrees(bend) + " degrees at joint " + i);
                    assertEquals(0.0, d[6 * i + 1], 1.0E-9, "on the floor");
                }
                assertTrue(w.offTrail(JOINTS - 1) < 0.02, deg + " degrees: the tail is " + w.offTrail(JOINTS - 1) + " off the trail");
            }
        }
    }

    @Test
    void aCornerIsCutOnItsInside() {
        // up +z to z 5, then off along +x: inside the corner is x > 0, z < 5
        Walk w = new Walk(0, 0, 0, 0, 1);
        w.walk(5, 0.25);
        w.turn(90, 1);
        w.walk(3, 0.25);
        double inside = 0, outside = 0;
        double[] d = w.spine.data();
        for (int i = 0; i < JOINTS; i++) {
            double x = d[6 * i], z = d[6 * i + 2];
            if (x > 0 && z < 5) inside = Math.max(inside, Math.min(x, 5 - z));
            else outside = Math.max(outside, Math.max(-x, z - 5));
        }
        assertTrue(inside > 0.1, "it cuts the corner: " + inside);
        assertTrue(outside < 0.05, "and never swings out round it: " + outside);
    }

    @Test
    void whereTheHeadTurnsBackTheBodyLoopsRoundOneWay() {
        // straight back over its own way: the head turns where it stands, so its neck swings out the other way from
        // its turn, and the body loops round after the neck, on that side all the while
        for (double deg : new double[]{180, -180}) {
            Walk w = new Walk(0, 0, 0, 0, 1);
            w.walk(5, 0.25);
            w.turn(deg, 2);
            double side = -Math.signum(deg), most = 0;
            for (int k = 0; k < 24; k++) {
                w.walk(0.25, 0.25);
                double[] d = w.spine.data();
                for (int i = 0; i < JOINTS; i++) {
                    double x = d[6 * i] * side;
                    assertTrue(x > -0.05, deg + " degrees, " + k + " ticks on: joint " + i + " is " + x + " out the other way");
                    most = Math.max(most, x);
                }
            }
            assertTrue(most > 0.3, deg + " degrees: it loops out to one side: " + most);
            assertEquals(0.0, w.back, 1.0E-9, "behind the neck it never goes back");
        }
        // back at a slant: the body behind the neck loops round one way, and stays that way round
        for (double deg : new double[]{170, -170, 160, -200}) {
            Walk w = new Walk(0, 0, 0, 0, 1);
            w.walk(5, 0.25);
            w.turn(deg, 2);
            double side = 0;
            for (int k = 0; k < 30; k++) {
                w.walk(0.25, 0.25);
                double[] d = w.spine.data();
                for (int i = NECK + 1; i < JOINTS; i++) {
                    double x = d[6 * i];
                    if (side == 0 && Math.abs(x) > 0.2) side = Math.signum(x);
                    assertTrue(side == 0 || x * side > -0.05, deg + " degrees, " + k + " ticks on: joint " + i + " is " + x * side + " out the other way");
                }
            }
            assertNotEquals(0.0, side, deg + " degrees: it loops round");
            assertEquals(0.0, w.back, 1.0E-9, "behind the neck it never goes back");
        }
    }

    @Test
    void whileTheHeadIsStillTheBodyLiesStill() {
        Walk w = new Walk(0, 0, 0, 0, 1);
        w.walk(3, 0.25);
        w.turn(120, 1);
        w.walk(1.5, 0.25);
        w.turn(170, 2);
        w.still(20);
        double[] at = w.spine.data().clone();
        w.still(40);
        assertArrayEquals(at, w.spine.data(), 1.0E-9);
    }

    @Test
    void whenTheHeadSnapsRightRoundTheBodyBehindItsNeckStaysPut() {
        Walk w = new Walk(0, 0, 0, 0, 1);
        w.walk(4, 0.25);
        w.still(10);
        double[] at = w.spine.data().clone();
        w.turn(180, 1);
        double[] d = w.spine.data();
        for (int i = 6 * (NECK + 1); i < at.length; i++) assertEquals(at[i], d[i], 1.0E-9);
        // the neck swings out to the side, the other way from the head's turn (its back went round that way), to reach
        // round from the back of the head to the body
        double out = 0;
        for (int j = 1; j < NECK; j++) out = Math.max(out, -d[6 * j]);
        assertTrue(out > 0.05, "the neck swings out: " + out);
        // standing so, the lead catches up a little (forwards) to fit the neck in
        w.still(10);
        assertEquals(0.0, w.back, 1.0E-9);
        assertTrue(w.spine.neckLength() <= SkeinSpine.NECK_LAG + 1.0E-6, "the neck is its length again: " + w.spine.neckLength());
    }

    @Test
    void theRockFlickeringUnderAStillHeadOnlyStirsItsNeck() {
        Walk w = new Walk(0, 0, 0, 0, 1);
        w.walk(4, 0.25);
        w.still(20);
        double[] at = null;
        for (int k = 0; k < 32; k++) {
            // (the first time its head tips, its lead may settle a hair, as the neck lies a little differently)
            if (k == 2) at = w.spine.data().clone();
            boolean tilted = k % 2 == 0;
            w.ny = tilted ? Math.sqrt(0.5) : 1.0;
            w.nz = tilted ? -Math.sqrt(0.5) : 0.0;
            w.tick();
        }
        double[] d = w.spine.data();
        for (int j = NECK + 1; j < JOINTS; j++)
            for (int k = 0; k < 3; k++) assertEquals(at[6 * j + k], d[6 * j + k], 1.0E-9, "joint " + j);
    }

    @Test
    void itGoesUpAWallFromTheFloorOnTheRock() {
        // along the floor (y 0) to the foot of a wall at x 2.5, then straight up its face (facing -x)
        Walk w = new Walk(0, 0, 0, 1, 0);
        w.walk(2.25, 0.25);
        w.x = 2.5; w.y = 0.25; w.nx = -1; w.ny = 0; w.fx = 0; w.fy = 1;
        w.tick();
        w.walk(3.5, 0.25);
        double[] d = w.spine.data();
        for (int i = 0; i < JOINTS; i++) {
            double x = d[6 * i], y = d[6 * i + 1];
            assertTrue(x <= 2.5 + 0.02 && y >= -0.02, "joint " + i + " is in the rock at " + x + " " + y);
            double off = x < 2.45 ? Math.abs(y) : y > 0.05 ? Math.abs(2.5 - x) : Math.min(Math.abs(y), Math.abs(2.5 - x));
            assertTrue(off < 0.05, "joint " + i + " is " + off + " off the rock");
        }
        assertEquals(0.0, w.back, 1.0E-9);
    }

    @Test
    void itGoesOverAnEdgeAndDownTheRockOnTheRock() {
        // along the floor (y 0) to its edge at z 5, then down the face under it (facing +z)
        Walk w = new Walk(0, 0, 0, 0, 1);
        w.walk(5, 0.25);
        w.ny = 0; w.nz = 1; w.fz = 0; w.fy = -1;
        w.walk(3.5, 0.25);
        double[] d = w.spine.data();
        for (int i = 0; i < JOINTS; i++) {
            double y = d[6 * i + 1], z = d[6 * i + 2];
            assertFalse(z < 5 - 0.02 && y < -0.02, "joint " + i + " is in the rock at y " + y + " z " + z);
            double off = z < 4.95 ? Math.abs(y) : y < -0.05 ? Math.abs(z - 5) : Math.min(Math.abs(y), Math.abs(z - 5));
            assertTrue(off < 0.05, "joint " + i + " is " + off + " off the rock");
        }
        assertEquals(0.0, w.back, 1.0E-9);
    }

    @Test
    void walkingAboutItsBodyNeverJerks() {
        // the Skein's gait: its head snaps round, it darts off, it stops dead
        Random r = new Random(7);
        Walk w = new Walk(0, 0, 0, 0, 1);
        for (int outing = 0; outing < 80; outing++) {
            double deg = (r.nextBoolean() ? 1 : -1) * (r.nextDouble() < 0.18 ? 150 + r.nextDouble() * 50 : 30 + r.nextDouble() * 90);
            w.turn(deg, 1 + r.nextInt(3));
            w.walk((3 + r.nextInt(28)) * 0.28, 0.25 + r.nextDouble() * 0.08);
            w.still(4 + r.nextInt(36));
        }
        assertEquals(0.0, w.back, 1.0E-9, "behind the neck it never slides back");
        assertTrue(w.beyond <= SkeinSpine.CATCH + 1.0E-6, "nor further on than the lead may go: " + w.beyond);
        assertEquals(0, w.reversals, "nor to and fro");
        for (double c : w.spine.data()) assertTrue(Double.isFinite(c));
    }

    @Test
    void betweenTicksItIsDrawnPartWay() {
        // straight along +z, a quarter a tick: the head at z 3, joint j at 3 - 0.15 j, a tick ago a quarter back
        Walk w = new Walk(0, 0, 0, 0, 1);
        w.walk(3, 0.25);
        double[] o = new double[6];
        w.spine.sample(2.0, 0.5, o, 0);
        assertEquals(0.0, o[0], EPS);
        assertEquals(3.0 - 0.125 - 2.0, o[2], EPS);
        assertEquals(1.0, o[4], EPS, "its back to the floor");
        // past the last joint it goes on along the last link
        w.spine.sample(w.spine.length() + 0.5, 1.0, o, 0);
        assertEquals(3.0 - w.spine.length() - 0.5, o[2], EPS);
    }

    @Test
    void writtenAndReadBackItLiesAsItLay() {
        Walk w = new Walk(0, 0, 0, 0, 1);
        w.walk(3, 0.25);
        w.turn(70, 1);
        w.walk(2, 0.25);
        w.turn(-100, 2);
        w.walk(2.5, 0.25);
        double[] arcs = new double[34];
        for (int k = 0; k < arcs.length; k++) arcs[k] = Math.min(KEEP, k * 0.3);
        float[] kept = w.spine.write(arcs, w.x, w.y, w.z);
        SkeinTrail t = new SkeinTrail(KEEP);
        assertTrue(t.read(kept, w.x, w.y, w.z));
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.lay(t);
        for (int i = 0; i < JOINTS; i++) {
            double off = dist(s.data(), i, w.spine.data(), i);
            assertTrue(off < (i <= NECK ? 0.15 : 0.05), "joint " + i + " is " + off + " from where it lay");
        }
    }

    @Test
    void aTrailLaidAfreshLaysTheBodyAfresh() {
        Walk w = new Walk(0, 0, 0, 0, 1);
        w.walk(3, 0.25);
        w.turn(90, 1);
        w.walk(2, 0.25);
        // a teleport: the trail is laid afresh, and the body along it at once
        w.x = 40; w.y = 5; w.z = -20;
        w.tick();
        assertTrue(w.spine.on(w.trail));
        for (int i = 0; i < JOINTS; i++) assertTrue(w.offTrail(i) < 1.0E-3, "joint " + i + " is " + w.offTrail(i) + " off the trail");
        assertArrayEquals(w.spine.data(), w.spine.before(), "and is not drawn sweeping there from where it was");
    }

    @Test
    void itNeverLiesNowhere() {
        // a head pointing into its rock, standing still on a trail laid no way at all
        SkeinTrail t = new SkeinTrail(KEEP);
        t.lay(0, 0, 0, 0, 0, 0, 0, 1, 0);
        SkeinSpine s = new SkeinSpine(JOINTS);
        s.lay(t);
        for (int k = 0; k < 20; k++) {
            t.record(0, 0, 0, 0, 1, 0);
            s.follow(t, 0, 1, 0);
        }
        for (double c : s.data()) assertTrue(Double.isFinite(c));
    }
}
