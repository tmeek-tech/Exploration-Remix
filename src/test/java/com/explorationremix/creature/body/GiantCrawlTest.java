package com.explorationremix.creature.body;

import com.explorationremix.creature.GiantBody;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The giant Skein's head crawls over rough, terraced rock like a rift's wall (0.21.5): up the wall, over each ledge and
 * up the next wall, across the roof, round outer corners and into hollows. It never lets go, never goes into rock and
 * never turns sharply, and its thirty-block body, following, never lies in rock.
 */
class GiantCrawlTest {
    static final double SCALE = 4.0, KEEP = 29.4 + 9.6, CLEAR = 0.75;
    static final int JOINTS = 50;

    /** A world of whole blocks given by a rule, with no storage (big worlds are cheap). */
    interface Rule { boolean rock(int x, int y, int z); }

    static Terrain world(Rule r) {
        return (x, y, z, sink) -> { if (r.rock(x, y, z)) sink.box(x, y, z, x + 1, y + 1, z + 1); };
    }

    static int hash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h & 0x7fffffff;
    }

    /**
     * A rift's wall: rock on the low x side of a face that steps back 3 blocks every 10 up (terraces), with knobs and
     * dents of a block here and there; the rift's floor at y below 0 and its roof from y 48 up.
     */
    static boolean riftRock(int x, int y, int z) {
        if (y < 0 || y >= 48) return true;
        int face = -3 * (y / 10);
        int h = hash(Math.floorDiv(y, 2), 0, Math.floorDiv(z, 2)) % 100;
        if (h < 14) face += 1;           // a knob 2 x 2 blocks
        else if (h < 22) face -= 1;      // a dent
        return x < face;
    }

    /**
     * The trip over it, as a path's points along the rock: along the low wall, up over two ledges, along again, up over
     * two more to the roof, out across the roof and back, and down the terraces to the floor.
     */
    static List<double[]> riftTrip() {
        double[][] w = {
                {1.2, 5, 20}, {1.2, 6, 40}, {1.2, 9, 44}, {-1.5, 11.2, 46}, {-1.8, 15, 48}, {-4.5, 21.2, 50}, {-4.8, 24, 52},
                {-4.8, 25, 75}, {-4.8, 29, 78}, {-7.5, 31.2, 79}, {-7.8, 36, 80}, {-10.5, 41.2, 82}, {-10.8, 45, 84},
                {-10.6, 46.6, 86}, {-7, 46.8, 88}, {10, 46.8, 92}, {-2, 46.8, 100}, {-10.6, 46.6, 104}, {-10.8, 43, 106},
                {-10.5, 41.2, 107}, {-7.8, 38, 108}, {-7.8, 34, 110}, {-7.5, 31.2, 109}, {-4.8, 26, 108}, {-4.5, 21.2, 106},
                {-1.8, 16, 104}, {-1.5, 11.2, 102}, {1.2, 7, 101}, {1.2, 4, 100}};
        return new ArrayList<>(List.of(w));
    }

    /** Its body's half width at the head and at the tip of its tail, and the room its flanks keep from the rock. */
    static final double HALF = 1.05, TAIL = 0.45, ROOM = 0.25;

    record Run(int ticks, int lost, double worstGap, double mostUpTurn, double meanUpTurn, double mostTurn,
               int pushed, double mostPush, double meanPushed, int legs, double worstClear, double worstSide, double footJump,
               double headNear, double headFar, double footFloat) {
        @Override public String toString() {
            return String.format(Locale.ROOT, "%d ticks, %d legs, lost %d, gap off by %.3f, up turned at most %.1f (mean %.2f) deg/tick, "
                            + "heading at most %.1f deg/tick, body pushed %d joint-ticks (most %.2f, mean %.3f joints a tick), "
                            + "worst clearance after %.3f (flanks %.3f), foot jump %.2f, head %.2f..%.2f from rock, foot floats %.2f",
                    ticks, legs, lost, worstGap, mostUpTurn, meanUpTurn, mostTurn, pushed, mostPush, meanPushed, worstClear, worstSide,
                    footJump, headNear, headFar, footFloat);
        }
    }

    static Run crawl(Terrain t, double[] start, double[] heading, List<double[]> trip, double pace, int most) {
        return crawl(t, start, heading, trip, pace, most, null);
    }

    /**
     * Crawls the trip, its body first laid along {@code laid} (points on the rock behind its head, head first, each with
     * the rock's way out: six numbers a point; or straight back from its head, where null).
     */
    static Run crawl(Terrain t, double[] start, double[] heading, List<double[]> trip, double pace, int most, double[] laid) {
        GiantCrawl c = new GiantCrawl();
        c.place(start[0], start[1], start[2], heading[0], heading[1], heading[2]);
        assertTrue(c.settle(t), "set down on rock");
        GiantBody body = new GiantBody(SCALE, KEEP, JOINTS);
        Vector3d foot = new Vector3d(), n = new Vector3d();
        assertTrue(c.foot(t, foot, n));
        if (laid != null) {
            float[] d = new float[laid.length];
            for (int i = 0; i < laid.length; i++) d[i] = (float) (i % 6 < 3 ? laid[i] - (i % 6 == 0 ? foot.x : i % 6 == 1 ? foot.y : foot.z) : laid[i]);
            d[0] = 0; d[1] = 0; d[2] = 0;
            assertTrue(body.read(d, foot.x, foot.y, foot.z), "its body laid along the rock behind it");
        } else body.lay(foot.x, foot.y, foot.z, -c.heading.x, -c.heading.y, -c.heading.z, n.x, n.y, n.z);
        body.layBody();
        RockClearance rc = new RockClearance();
        int leg = 0, lost = 0, ticks = 0, pushed = 0;
        double worstGap = 0, mostUp = 0, sumUp = 0, mostTurn = 0, mostPush = 0, worstClear = Double.MAX_VALUE, worstSide = Double.MAX_VALUE;
        double jump = 0, near = Double.MAX_VALUE, far = 0, floats = 0;
        Vector3d lastFoot = new Vector3d(foot);
        double[] j = new double[6], k2 = new double[6], q = new double[4];
        while (leg < trip.size() && ticks < most) {
            double[] w = trip.get(leg);
            if (!c.step(t, w[0], w[1], w[2], pace)) { lost++; ticks++; continue; }
            ticks++;
            double gap = c.gap();
            worstGap = Math.max(worstGap, Math.abs(gap - GiantCrawl.RIDE));
            double up = Math.toDegrees(c.upTurned), tu = Math.toDegrees(c.turned);
            mostUp = Math.max(mostUp, up);
            sumUp += up;
            mostTurn = Math.max(mostTurn, tu);
            double hd = rc.nearest(t, c.pos.x, c.pos.y, c.pos.z, 4.0, q) ? q[0] : 4.0;
            near = Math.min(near, hd);
            far = Math.max(far, hd);
            if (c.foot(t, foot, n)) {
                jump = Math.max(jump, foot.distance(lastFoot));
                lastFoot.set(foot);
                floats = Math.max(floats, rc.nearest(t, foot.x, foot.y, foot.z, 4.0, q) ? q[0] : 4.0);
                body.record(foot.x, foot.y, foot.z, n.x, n.y, n.z);
            }
            body.follow(c.heading.x, c.heading.y, c.heading.z);
            int moved = body.keepOut(t, CLEAR, HALF, TAIL, ROOM);
            pushed += moved;
            mostPush = Math.max(mostPush, body.pushedMost());
            // what is left: every joint's line clear of rock, and its flanks
            for (int i = 0; i < body.joints(); i++) {
                body.joint(i, j);
                double cx = j[0] + j[3] * CLEAR, cy = j[1] + j[4] * CLEAR, cz = j[2] + j[5] * CLEAR;
                double cl = rc.nearest(t, cx, cy, cz, CLEAR + 0.5, q) ? q[0] : CLEAR + 0.5;
                worstClear = Math.min(worstClear, cl);
                body.joint(Math.max(0, i - 1), k2);
                double[] b2 = new double[6];
                body.joint(Math.min(body.joints() - 1, i + 1), b2);
                double tx = k2[0] - b2[0], ty = k2[1] - b2[1], tz = k2[2] - b2[2];
                double sx = ty * j[5] - tz * j[4], sy = tz * j[3] - tx * j[5], sz = tx * j[4] - ty * j[3];
                double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (sl < 1.0E-6) continue;
                double half = HALF + (TAIL - HALF) * i / (body.joints() - 1.0);
                for (int side = -1; side <= 1; side += 2) {
                    double px = cx + sx / sl * half * side, py = cy + sy / sl * half * side, pz = cz + sz / sl * half * side;
                    double sd = rc.nearest(t, px, py, pz, ROOM + 0.5, q) ? q[0] : ROOM + 0.5;
                    worstSide = Math.min(worstSide, sd);
                }
            }
            double dx = w[0] - c.pos.x, dy = w[1] - c.pos.y, dz = w[2] - c.pos.z;
            if (dx * dx + dy * dy + dz * dz < 2.5 * 2.5) leg++;
        }
        return new Run(ticks, lost, worstGap, mostUp, sumUp / Math.max(1, ticks), mostTurn, pushed, mostPush,
                (double) pushed / Math.max(1, ticks), leg, worstClear, worstSide, jump, near, far, floats);
    }

    @Test
    void itCrawlsARiftWallWithoutLettingGoOrGoingIntoRock() {
        Terrain t = world(GiantCrawlTest::riftRock);
        for (double pace : new double[]{0.078, 0.215, 0.28}) {
            Run r = crawl(t, new double[]{1.5, 5, 0}, new double[]{0, 0, 1}, riftTrip(), pace, 12000);
            System.out.println("[giant crawl] rift wall at " + pace + ": " + r);
            assertEquals(0, r.lost(), "it never lets go");
            assertEquals(riftTrip().size(), r.legs(), "it gets all the way round");
            assertTrue(r.worstGap() < 0.35, "it keeps its ride out from the rock: off by " + r.worstGap());
            assertTrue(r.mostUpTurn() < 30.0, "its way out never flips: " + r.mostUpTurn());
            assertTrue(r.mostTurn() < 9.0, "its heading never snaps round: " + r.mostTurn());
            assertTrue(r.headNear() > 1.0, "its head never comes into the rock: " + r.headNear());
            assertTrue(r.headFar() < 2.6, "nor floats far off it: " + r.headFar());
            assertTrue(r.footFloat() < 1.3, "the trail it lays lies on the rock or close over it: " + r.footFloat());
            assertTrue(r.worstClear() > CLEAR - 0.05, "its body never lies in rock: " + r.worstClear());
            assertTrue(r.worstSide() > 0.0, "nor its flanks: " + r.worstSide());
        }
    }

    @Test
    void itPoursOverAnOuterEdgeAndUpOutOfAHollow() {
        // a block-high step up and a wall: in over the floor, up the wall, over its top (an outer edge) and on
        Terrain t = world((x, y, z) -> y < 0 || (x >= 10 && y < 6) || (x >= 10 && x < 14 && y < 7 && z > 4 && z < 9));
        List<double[]> trip = List.of(new double[]{8.8, 2.5, 6}, new double[]{8.8, 5.5, 6}, new double[]{12, 8.2, 6}, new double[]{24, 7.2, 6});
        Run r = crawl(t, new double[]{2, 1.2, 6}, new double[]{1, 0, 0}, trip, 0.2, 3000);
        System.out.println("[giant crawl] edge and hollow: " + r);
        assertEquals(0, r.lost());
        assertEquals(trip.size(), r.legs());
        assertTrue(r.mostUpTurn() > 1.0, "it did turn up the wall: " + r.mostUpTurn());
        assertTrue(r.mostUpTurn() < 30.0, "it turns through the hollow and round the edge smoothly: " + r.mostUpTurn());
        assertTrue(r.worstClear() > CLEAR - 0.05, "its body never lies in rock: " + r.worstClear());
        assertTrue(r.worstSide() > 0.0, "nor its flanks: " + r.worstSide());
    }

    /** A winding, rising tube of rock about three times its body's width across. */
    static final double TUBE = 3.7;

    static boolean tube(int x, int y, int z) {
        double cx = 8 * Math.sin(z / 12.0), cy = 6 + 4 * Math.sin(z / 9.0);
        double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
        return dx * dx + dy * dy > TUBE * TUBE;
    }

    @Test
    void itWindsThroughATunnelWithoutItsBodyGoingIntoTheWalls() {
        Terrain t = world(GiantCrawlTest::tube);
        List<double[]> trip = new ArrayList<>();
        for (int z = 6; z <= 120; z += 6) trip.add(new double[]{8 * Math.sin(z / 12.0), 6 + 4 * Math.sin(z / 9.0) - TUBE + 1.4, z});
        // its body lying back along the tube's floor behind it
        int k = 0;
        double[] laid = new double[6 * 41];
        for (double z = 0.5; k < 41; z -= 1.0, k++) {
            laid[6 * k] = 8 * Math.sin(Math.floor(z) / 12.0);
            laid[6 * k + 1] = 6 + 4 * Math.sin(Math.floor(z) / 9.0) - TUBE + 0.8;
            laid[6 * k + 2] = z;
            laid[6 * k + 4] = 1.0;
        }
        Run r = crawl(t, new double[]{0, 6 - TUBE + 1.4, 0.5}, new double[]{0, 0, 1}, trip, 0.28, 4000, laid);
        System.out.println("[giant crawl] tunnel: " + r);
        assertEquals(0, r.lost());
        assertEquals(trip.size(), r.legs());
        assertTrue(r.worstClear() > CLEAR - 0.05, "its body never lies in rock: " + r.worstClear());
        assertTrue(r.worstSide() > 0.0, "nor its flanks: " + r.worstSide());
    }

    @Test
    void inALowSqueezeItNeverGoesIntoTheRockOverIt() {
        // a floor, and a roof coming down over it to two blocks: it goes in under it, its head never nearer
        // the rock than its squeeze, and its body following never in rock
        Terrain t = world((x, y, z) -> y < 0 || (z >= 12 && z < 30 && y >= 2) || y >= 9);
        List<double[]> trip = List.of(new double[]{0.5, 1.2, 20}, new double[]{0.5, 1.2, 40});
        Run r = crawl(t, new double[]{0.5, 1.2, 0.5}, new double[]{0, 0, 1}, trip, 0.28, 2000);
        System.out.println("[giant crawl] low squeeze: " + r);
        assertEquals(0, r.lost());
        assertTrue(r.headNear() > GiantCrawl.SQUEEZE - 0.02, "its head never comes nearer the rock than it may: " + r.headNear());
        assertEquals(trip.size(), r.legs(), "it gets through");
        assertTrue(r.worstClear() > CLEAR - 0.05, "its body never lies in rock: " + r.worstClear());
    }

    @Test
    void itGlidesOverSmallKnobsRatherThanClimbingEachOne() {
        // a floor studded with single-block knobs: its head eases over them, the trail it lays never jumping
        Terrain t = world((x, y, z) -> y < 0 || (y == 0 && hash(x, 0, z) % 100 < 18));
        GiantCrawl c = new GiantCrawl();
        c.place(0.5, 2.0, 0.5, 1, 0, 0);
        assertTrue(c.settle(t));
        Vector3d foot = new Vector3d(), n = new Vector3d(), last = new Vector3d();
        assertTrue(c.foot(t, last, n));
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, jump = 0, rise = 0, prev = c.pos.y;
        for (int i = 0; i < 600; i++) {
            assertTrue(c.step(t, 200, 1.2, 0.5, 0.2));
            assertTrue(c.foot(t, foot, n));
            if (i > 40) {
                lo = Math.min(lo, c.pos.y); hi = Math.max(hi, c.pos.y);
                jump = Math.max(jump, foot.distance(last));
                rise = Math.max(rise, Math.abs(c.pos.y - prev));
            }
            last.set(foot);
            prev = c.pos.y;
        }
        System.out.printf(Locale.ROOT, "[giant crawl] knobbly floor: its head rode from %.2f to %.2f, moving up or down at most %.2f a tick; its trail stepped at most %.2f%n",
                lo, hi, rise, jump);
        assertTrue(hi - lo < 1.6, "it rides over the knobs, not up and down every one: " + (hi - lo));
        assertTrue(rise < 0.3, "its head never jumps: " + rise);
        assertTrue(jump < 0.6, "nor the trail it lays: " + jump);
    }

    @Test
    void droppedItFallsAndLandsOnTheFloorBelow() {
        Terrain t = world((x, y, z) -> y < 0 || y >= 20);
        GiantCrawl c = new GiantCrawl();
        c.place(5.5, 15.0, 5.5, 1, 0, 0);
        c.fallVelocity.set(0, 0, 0);
        int ticks = 0;
        while (!c.fall(t) && ticks < 200) ticks++;
        assertTrue(ticks < 60, "it lands: " + ticks);
        assertEquals(GiantCrawl.RIDE, c.pos.y, 0.05, "on its ride over the floor");
        assertEquals(5.5, c.pos.x, 1.0E-6, "straight down");
        assertTrue(c.up.y > 0.99);
    }
}
