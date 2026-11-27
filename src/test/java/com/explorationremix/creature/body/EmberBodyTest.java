package com.explorationremix.creature.body;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ember urchin's body and steering (0.23.7) in made-up tunnels: braced on the rock all round when it stands in a
 * tunnel's middle, each braced spine bowed to its own length and its point on the rock; walking it keeps to the
 * tunnel's middle at its height, its spines stepping as it goes; something in its way holds it, and held long it turns
 * back; it keeps out of drops and off ground it may not walk; its spines and ball are found where they are for pricks.
 */
class EmberBodyTest {
    static final EmberRig RIG = EmberRig.load(EmberSpec.GAME_SCALE);
    static final EmberSpec SPEC = RIG.spec;

    /** Rock round an open box from x0 to x1, z -halfW to halfW, rows 0 to height - 1 (a floor's top at 0). */
    static BlockWorld tunnel(int x0, int x1, int halfW, int height) {
        BlockWorld w = new BlockWorld();
        for (int x = x0 - 3; x <= x1 + 3; x++)
            for (int y = -3; y <= height + 2; y++)
                for (int z = -halfW - 3; z <= halfW + 3; z++)
                    if (x < x0 || x > x1 || y < 0 || y >= height || z < -halfW || z > halfW) w.block(x, y, z);
        return w;
    }

    static EmberWalk.Ground ground(BlockWorld w, Predicate<Vector3d> allowed) {
        return new EmberWalk.Ground() {
            @Override
            public boolean solid(int x, int y, int z) { return w.has(x, y, z); }

            @Override
            public boolean allowed(double x, double y, double z) { return allowed == null || allowed.test(new Vector3d(x, y, z)); }
        };
    }

    /** A body and its rock, ticked with its middle where it is told. */
    static final class Run {
        final BlockWorld w;
        final EmberBody body = new EmberBody(SPEC);
        final TerrainPatch rock = new TerrainPatch();
        final EmberBody.Drive drive = new EmberBody.Drive();
        final int[] bounds = new int[6];
        int steps;

        Run(BlockWorld w) {
            this.w = w;
            body.setSeed(7);
            body.setFootfalls((leg, speed) -> steps++);
        }

        void tick(Vector3d mid) {
            drive.x = mid.x;
            drive.y = mid.y;
            drive.z = mid.z;
            body.bounds(drive, bounds);
            rock.build(w, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            body.tick(rock, drive);
        }
    }

    @Test
    void measuredFromItsShape() {
        assertEquals(30, SPEC.legs);
        assertEquals(0.625, SPEC.radius, 1.0E-9);
        assertTrue(SPEC.coat >= 15 && SPEC.coat <= 40, "coat " + SPEC.coat);
        for (int i = 0; i < SPEC.legs; i++) {
            assertEquals(2.875, SPEC.length[i], 1.0E-6);
            assertTrue(SPEC.neighbours[i].length >= 2 && SPEC.neighbours[i].length <= 8, "spine " + i + " neighbours " + SPEC.neighbours[i].length);
        }
        assertTrue(SPEC.span() > 3.3 && SPEC.span() < 3.6, "span " + SPEC.span());
        // the rig: the ball's faces gridded, every walking spine's corners on its rows
        assertEquals(6 * EmberRig.BALL_GRID * EmberRig.BALL_GRID, countKind(EmberRig.BALL) / 4);
        for (int v = 0; v < RIG.vertexCount; v++) if (RIG.vKind[v] == EmberRig.SPINE) assertTrue(RIG.vRow[v] >= 0);
        assertTrue(RIG.glowQuads.length > 6 * EmberRig.BALL_GRID * EmberRig.BALL_GRID);
    }

    private static int countKind(byte k) {
        int n = 0;
        for (int v = 0; v < RIG.vertexCount; v++) if (RIG.vKind[v] == k) n++;
        return n;
    }

    @Test
    void bracedAllRoundInATunnel() {
        BlockWorld w = tunnel(-20, 20, 2, 6);
        Run r = new Run(w);
        Vector3d mid = new Vector3d(0.5, 3.0, 0.5);
        r.tick(mid);
        int planted = r.body.plantedCount();
        assertTrue(planted >= 16, "only " + planted + " spines braced");
        int floor = 0, roof = 0, left = 0, right = 0;
        EmberBody.Bow bow = new EmberBody.Bow();
        Vector3d p = new Vector3d();
        for (EmberBody.Leg lg : r.body.legs) {
            if (!lg.planted) continue;
            // its point on the rock: just inside it rock, just out of it air
            assertTrue(r.rock.solid(lg.at.x - lg.nrm.x * 0.06, lg.at.y - lg.nrm.y * 0.06, lg.at.z - lg.nrm.z * 0.06), "spine " + lg.index + " point off the rock");
            assertTrue(!r.rock.solid(lg.at.x + lg.nrm.x * 0.06, lg.at.y + lg.nrm.y * 0.06, lg.at.z + lg.nrm.z * 0.06), "spine " + lg.index + " point in the rock");
            if (lg.nrm.y > 0.7) floor++;
            else if (lg.nrm.y < -0.7) roof++;
            else if (lg.nrm.z > 0.7) left++;
            else if (lg.nrm.z < -0.7) right++;
            // bowed to its own length, its rod clear of the rock but at its point
            r.body.bowOf(lg.index, bow);
            assertTrue(bow.length >= bow.chord - 1.0E-6, "spine " + lg.index + " stretched");
            for (int k = 1; k < 10; k++) {
                bow.point(bow.length * k / 12.0, p);
                assertTrue(!r.rock.solid(p.x, p.y, p.z), "spine " + lg.index + " runs through the rock at " + k);
            }
        }
        assertTrue(floor >= 3 && roof >= 2 && left >= 2 && right >= 2, "floor " + floor + " roof " + roof + " left " + left + " right " + right);
    }

    @Test
    void walksTheMiddleOfATunnel() {
        BlockWorld w = tunnel(-40, 40, 2, 6);
        Run r = new Run(w);
        EmberWalk walk = new EmberWalk();
        walk.setSeed(3);
        walk.face(1, 0);
        EmberWalk.Ground g = ground(w, null);
        Vector3d mid = new Vector3d(-30.5, 3.0, 0.5);
        double worstOff = 0, lowest = 9, highest = 0, x0 = mid.x;
        int plantedSum = 0;
        int n = 600;
        for (int t = 0; t < n; t++) {
            Vector3d v = walk.tick(g, mid, List.of(), false);
            mid.add(v);
            r.tick(mid);
            plantedSum += r.body.plantedCount();
            if (t > 60) {
                worstOff = Math.max(worstOff, Math.abs(mid.z - 0.5));
                lowest = Math.min(lowest, mid.y);
                highest = Math.max(highest, mid.y);
            }
            assertTrue(EmberWalk.clear(g, mid.x, mid.y, mid.z, SPEC.radius), "its ball in the rock at tick " + t);
        }
        double went = Math.abs(mid.x - x0);
        System.out.printf("tunnel walk: went %.1f in %d ticks, off the middle %.2f at worst, height %.2f to %.2f, %.1f of 30 braced, %d steps, %s%n",
                went, n, worstOff, lowest, highest, plantedSum / (double) n, r.steps, walk.describe());
        assertTrue(went > 10.0, "went only " + went);
        assertTrue(worstOff < 0.45, "off the middle by " + worstOff);
        assertTrue(lowest > 2.8 && highest < 3.2, "height " + lowest + " to " + highest);
        assertTrue(plantedSum / (double) n >= 14.0, "braced " + plantedSum / (double) n);
        assertTrue(r.steps > 20, "only " + r.steps + " steps");
    }

    @Test
    void heldBySomeoneInItsWayThenTurnsBack() {
        BlockWorld w = tunnel(-40, 40, 2, 6);
        EmberWalk walk = new EmberWalk();
        walk.setSeed(5);
        walk.face(1, 0);
        EmberWalk.Ground g = ground(w, null);
        Vector3d mid = new Vector3d(0.5, 2.6, 0.5);
        for (int t = 0; t < 60; t++) mid.add(walk.tick(g, mid, List.of(), false));
        assertTrue(walk.heading.x > 0.5);
        double x = mid.x;
        for (int t = 0; t < 40; t++) mid.add(walk.tick(g, mid, List.of(), true));
        assertEquals(EmberWalk.State.HOLD, walk.state);
        assertTrue(Math.abs(mid.x - x) < 0.6, "walked on " + (mid.x - x) + " held");
        for (int t = 0; t < 40; t++) mid.add(walk.tick(g, mid, List.of(), true));
        assertTrue(walk.heading.x < -0.5, "still heading " + walk.heading.x + " after being held long");
        assertEquals(1, walk.turnedBack);
    }

    @Test
    void keepsOffGroundItMayNotWalkAndOutOfDrops() {
        // its ground ends at x = 10
        BlockWorld w = tunnel(-40, 40, 2, 6);
        EmberWalk walk = new EmberWalk();
        walk.setSeed(9);
        walk.face(1, 0);
        EmberWalk.Ground g = ground(w, p -> p.x < 10.0);
        Vector3d mid = new Vector3d(0.5, 2.6, 0.5);
        double most = mid.x;
        for (int t = 0; t < 900; t++) {
            mid.add(walk.tick(g, mid, List.of(), false));
            most = Math.max(most, mid.x);
        }
        assertTrue(most < 10.0, "walked to " + most);
        // a pit eight deep across the tunnel at x 12 to 15
        BlockWorld pit = tunnel(-40, 40, 2, 6);
        for (int x = 12; x <= 15; x++) for (int z = -2; z <= 2; z++) for (int y = -9; y <= -1; y++) pit.clear(x, y, z);
        EmberWalk walk2 = new EmberWalk();
        walk2.setSeed(11);
        walk2.face(1, 0);
        EmberWalk.Ground g2 = ground(pit, null);
        Vector3d mid2 = new Vector3d(0.5, 2.6, 0.5);
        double most2 = mid2.x, low2 = mid2.y;
        for (int t = 0; t < 900; t++) {
            mid2.add(walk2.tick(g2, mid2, List.of(), false));
            most2 = Math.max(most2, mid2.x);
            low2 = Math.min(low2, mid2.y);
        }
        assertTrue(most2 < 12.6, "walked out over the pit to " + most2);
        assertTrue(low2 > 2.0, "went down to " + low2);
    }

    @Test
    void pricksWhereItsSpinesAre() {
        BlockWorld w = tunnel(-20, 20, 2, 6);
        Run r = new Run(w);
        Vector3d mid = new Vector3d(0.5, 3.0, 0.5);
        r.tick(mid);
        Vector3d at = new Vector3d();
        // a player's box against its ball
        assertEquals(-1, r.body.touches(mid.x + 0.4, mid.y - 0.9, mid.z - 0.3, mid.x + 1.0, mid.y + 0.9, mid.z + 0.3, at));
        // a box well clear of it
        assertEquals(-2, r.body.touches(mid.x + 8, mid.y - 0.9, mid.z - 0.3, mid.x + 8.6, mid.y + 0.9, mid.z + 0.3, at));
        // a box round the middle of a braced spine
        EmberBody.Bow bow = new EmberBody.Bow();
        Vector3d p = new Vector3d();
        int found = 0, tried = 0;
        for (EmberBody.Leg lg : r.body.legs) {
            if (!lg.planted) continue;
            r.body.bowOf(lg.index, bow);
            bow.point(bow.length * 0.6, p);
            tried++;
            if (r.body.touches(p.x - 0.3, p.y - 0.3, p.z - 0.3, p.x + 0.3, p.y + 0.3, p.z + 0.3, at) >= 0) found++;
        }
        assertEquals(tried, found, "spines missed");
    }

    @Test
    void aTickIsCheap() {
        BlockWorld w = tunnel(-60, 60, 2, 6);
        Run r = new Run(w);
        EmberWalk walk = new EmberWalk();
        walk.face(1, 0);
        EmberWalk.Ground g = ground(w, null);
        Vector3d mid = new Vector3d(-50.5, 2.6, 0.5);
        List<Vector3d> none = new ArrayList<>();
        for (int t = 0; t < 200; t++) { mid.add(walk.tick(g, mid, none, false)); r.tick(mid); }
        EmberRock kept = new EmberRock();
        long walkNs = 0, patchNs = 0, bodyNs = 0;
        int n = 1000;
        for (int t = 0; t < n; t++) {
            long a = System.nanoTime();
            mid.add(walk.tick(g, mid, none, false));
            long b = System.nanoTime();
            r.drive.x = mid.x; r.drive.y = mid.y; r.drive.z = mid.z;
            TerrainPatch patch = kept.around(r.body, r.drive, w);
            long c = System.nanoTime();
            r.body.tick(patch, r.drive);
            long d = System.nanoTime();
            walkNs += b - a; patchNs += c - b; bodyNs += d - c;
        }
        double us = (walkNs + patchNs + bodyNs) / 1000.0 / n;
        System.out.printf("a tick: %.1f microseconds (walk %.1f, rock %.1f read %d times, body %.1f)%n", us, walkNs / 1000.0 / n,
                patchNs / 1000.0 / n, kept.reads, bodyNs / 1000.0 / n);
        assertTrue(kept.reads < n / 5, "the rock read " + kept.reads + " times");
        assertTrue(us < 150.0, "a tick takes " + us + " us");
    }
}
