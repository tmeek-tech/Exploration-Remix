package com.explorationremix.creature.body;

import com.explorationremix.creature.GiantBody;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dropped from a roof, the giant Skein's body comes down head first and pours after it like a heavy rope (0.21.5): the
 * head lands straight under where it let go, the rest peels off the roof and comes to rest on the floor in coils, never
 * in rock, every link its length; and a body laid along it afterwards lies where it fell.
 */
class GiantRopeTest {
    static final double LENGTH = 29.4;
    /** The giant's body's width either side of its line, at its head and its tip, and its sides' room from the rock. */
    static final double HALF = 1.05, TAIL = 0.45, ROOM = 0.25;

    /** Its body lying along the roof (facing down) back from its head at (hx, roof, hz) along +x, front {@code reared} hanging down. */
    static double[] onTheRoof(GiantRope r, double hx, double roof, double hz, double reared) {
        double[] at = new double[6 * r.beads()];
        for (int i = 0; i < r.beads(); i++) {
            double s = GiantRope.beadAt(i);
            int o = 6 * i;
            if (s < reared) {
                // hanging down from the roof towards the head
                at[o] = hx + reared * 0.3; at[o + 1] = roof - (reared - s); at[o + 2] = hz;
                at[o + 3] = -1; at[o + 4] = 0; at[o + 5] = 0;
            } else {
                at[o] = hx + reared * 0.3 + (s - reared); at[o + 1] = roof; at[o + 2] = hz;
                at[o + 3] = 0; at[o + 4] = -1; at[o + 5] = 0;
            }
        }
        return at;
    }

    @Test
    void itPoursDownAndComesToRestOnTheFloor() {
        Terrain t = GiantCrawlTest.world((x, y, z) -> y < 0 || y >= 20 || (y < 3 && x >= 30));
        GiantRope r = new GiantRope(LENGTH, HALF, TAIL, ROOM);
        r.start(onTheRoof(r, 4.0, 20.0, 4.5, 4.5), 4.5);
        RockClearance rc = new RockClearance();
        double[] o = new double[6], q = new double[4], from = new double[3];
        r.head(from);
        int firstFree = -1, up = -1;
        while (!r.settled() || (up < 0 && r.ticks() < 200)) {
            r.tick(t);
            if (firstFree < 0 && r.holding() == 0) firstFree = r.ticks();
            if (up < 0 && r.restful(40)) {
                // it may get up once dazed a while and its body has all come down after it
                up = r.ticks();
                assertTrue(r.headDown());
                assertEquals(0, r.holding(), "all of it off the roof when it gets up");
            }
            for (double s = 0; s <= LENGTH; s += 0.6) {
                r.sample(s, 1.0, o, 0);
                double cx = o[0] + o[3] * GiantRope.CLEAR, cy = o[1] + o[4] * GiantRope.CLEAR, cz = o[2] + o[5] * GiantRope.CLEAR;
                assertFalse(rc.inRock(t, cx, cy, cz), "tick " + r.ticks() + ": its line lies in rock at " + s);
            }
        }
        double[] head = new double[3];
        r.head(head);
        if (up < 0 && r.restful(40)) up = r.ticks();
        System.out.printf(Locale.ROOT, "[giant rope] all off the roof after %d ticks, up again after %d, at rest after %d; head at %.2f %.2f %.2f%n",
                firstFree, up, r.ticks(), head[0], head[1], head[2]);
        assertTrue(up > 0 && up <= r.ticks(), "it gets up again: " + up);
        assertTrue(r.headDown());
        assertTrue(r.ticks() < 140, "it comes to rest: " + r.ticks());
        assertEquals(from[0], head[0], 0.01, "the head came straight down");
        assertEquals(from[2], head[2], 0.01, "the head came straight down");
        assertEquals(GiantRope.CLEAR, head[1], 0.05, "onto the floor");
        assertTrue(firstFree > 0 && firstFree < 60, "it all peels off the roof: " + firstFree);
        for (double s = 0; s <= LENGTH; s += 0.6) {
            r.sample(s, 1.0, o, 0);
            assertTrue(o[1] > -0.05 && o[1] < 3.5, "lying on the floor (or the step) at " + s + ": " + o[1]);
            // lying on the floor or the step (not against the step's side), it has rolled over onto its belly
            if (o[1] < 0.3 || (o[1] > 2.7 && o[0] > 30.6)) assertTrue(o[4] > 0.5, "its back up at " + s + ": " + o[4]);
        }
        // the links kept their length
        double[] a = new double[6], b = new double[6];
        for (double s = 0; s + 0.6 <= LENGTH; s += 0.6) {
            r.sample(s, 1.0, a, 0);
            r.sample(s + 0.6, 1.0, b, 0);
            double cl = Math.sqrt(Math.pow(a[0] + a[3] * 0.5 - b[0] - b[3] * 0.5, 2) + Math.pow(a[1] + a[4] * 0.5 - b[1] - b[4] * 0.5, 2)
                    + Math.pow(a[2] + a[5] * 0.5 - b[2] - b[5] * 0.5, 2));
            assertTrue(cl < 0.75, "a link stretched to " + cl + " at " + s);
        }
        assertNotNull(rc);
        assertEquals(0, q[0], 1.0);
    }

    @Test
    void itLiesBesideAWallWithoutItsSidesInIt() {
        // dropped along a wall: its body lay on the roof close by the wall, and lands at its foot
        Terrain t = GiantCrawlTest.world((x, y, z) -> y < 0 || y >= 16 || z >= 5);
        GiantRope r = new GiantRope(LENGTH, HALF, TAIL, ROOM);
        r.start(onTheRoof(r, 2.0, 16.0, 4.2, 4.5), 4.5);
        RockClearance rc = new RockClearance();
        while (!r.restful(40)) r.tick(t);
        System.out.printf(Locale.ROOT, "[giant rope] by a wall: up again after %d ticks%n", r.ticks());
        assertTrue(r.ticks() < 140, "it comes to rest beside the wall: " + r.ticks());
        double[] o = new double[6];
        // its sides out of the wall it lies beside
        double[] p0 = new double[6], p1 = new double[6];
        for (double s = 0.6; s + 0.6 <= LENGTH; s += 0.6) {
            r.sample(s - 0.6, 1.0, p0, 0);
            r.sample(s + 0.6, 1.0, p1, 0);
            r.sample(s, 1.0, o, 0);
            double tx = p0[0] - p1[0], ty = p0[1] - p1[1], tz = p0[2] - p1[2];
            double sx = ty * o[5] - tz * o[4], sy = tz * o[3] - tx * o[5], sz = tx * o[4] - ty * o[3], sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1.0E-6) continue;
            double w = HALF + (TAIL - HALF) * s / LENGTH;
            double cx = o[0] + o[3] * GiantRope.CLEAR, cy = o[1] + o[4] * GiantRope.CLEAR, cz = o[2] + o[5] * GiantRope.CLEAR;
            for (int k = -1; k <= 1; k += 2)
                assertFalse(rc.inRock(t, cx + sx / sl * w * k, cy + sy / sl * w * k, cz + sz / sl * w * k), "its side lies in rock at " + s);
        }
    }

    @Test
    void aBodyLaidAlongItLiesWhereItFell() {
        Terrain t = GiantCrawlTest.world((x, y, z) -> y < 0 || y >= 16);
        GiantRope r = new GiantRope(LENGTH);
        r.start(onTheRoof(r, 0.0, 16.0, 0.5, 4.5), 4.5);
        while (!r.settled()) r.tick(t);
        GiantBody body = new GiantBody(4.0, LENGTH + 9.6, 50);
        // as the entity does: the body's pose from the rope, then its trail laid along it
        double[] joints = new double[6 * body.joints()], s = new double[6];
        for (int j = 0; j < body.joints(); j++) {
            r.sample(body.jointAt(j), 1.0, s, 0);
            System.arraycopy(s, 0, joints, 6 * j, 6);
        }
        double[] h = new double[6];
        r.sample(0, 1.0, h, 0);
        // a trail point every bead (as GiantSkeinEntity#layFromRope), straight on past its tail
        double len = (r.beads() - 1) * GiantRope.SPACE;
        double[] q0 = new double[6], q1 = new double[6];
        r.sample(len - GiantRope.SPACE, 1.0, q0, 0);
        r.sample(len, 1.0, q1, 0);
        double tx = q1[0] - q0[0], ty = q1[1] - q0[1], tz = q1[2] - q0[2], tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl; ty /= tl; tz /= tl;
        int keep = (int) Math.ceil((LENGTH + 9.6) / GiantRope.SPACE) + 1;
        float[] trail = new float[6 * keep];
        for (int k = 0; k < keep; k++) {
            double arc = k * GiantRope.SPACE, past = Math.max(0.0, arc - len);
            r.sample(Math.min(arc, len), 1.0, s, 0);
            trail[6 * k] = (float) (s[0] + tx * past - h[0]);
            trail[6 * k + 1] = (float) (s[1] + ty * past - h[1]);
            trail[6 * k + 2] = (float) (s[2] + tz * past - h[2]);
            for (int i = 3; i < 6; i++) trail[6 * k + i] = (float) s[i];
        }
        // it lay along the roof before it let go
        body.lay(h[0], 16.0 - GiantCrawl.RIDE, h[2], 1, 0, 0, 0, -1, 0);
        body.layBody();
        body.pose(joints);
        assertTrue(body.read(trail, h[0], h[1], h[2]));
        body.follow(1, 0, 0);
        double worst = 0;
        double[] a = new double[6];
        for (int j = 0; j < body.joints(); j++) {
            body.joint(j, a);
            r.sample(body.jointAt(j), 1.0, s, 0);
            worst = Math.max(worst, Math.sqrt(Math.pow(a[0] - s[0], 2) + Math.pow(a[1] - s[1], 2) + Math.pow(a[2] - s[2], 2)));
        }
        System.out.printf(Locale.ROOT, "[giant rope] the body laid along it lies at most %.2f from where it fell%n", worst);
        assertTrue(worst < 1.0, "it lies (nearly) where it fell, easing over from there: " + worst);
    }
}
