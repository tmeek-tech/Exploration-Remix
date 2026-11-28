package com.explorationremix.creature.body;

import com.explorationremix.creature.EmberUrchinEntity;
import com.explorationremix.world.terrain.WastelandBlocks;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ember urchin chasing (0.24.3, Taran: "Can you make it chase the player at the player's walking speed and keep
 * this as its meandering speed?"), worked out offline: the rules of when it chases and gives up ({@link EmberChase});
 * in made-up tunnels, that it catches someone standing, keeps up with someone walking away, loses someone sprinting,
 * follows round a bend and up between storeys, and stops at the edge of ground it may not walk; and through the
 * Wasteland's own tunnels for two seeds, after someone walking away along them, that it keeps up and its body holds
 * together at the run: its ball out of the rock, most of its spines braced, their bows and stepping points out of it.
 */
class EmberChaseTest {
    static final EmberRig RIG = EmberRig.load(EmberSpec.GAME_SCALE);
    static final double WALK = EmberWalk.CHASE_SPEED, SPRINT = 0.2806;

    @Test
    void walksAtAQuarterAndChasesAtAPlayersWalk() {
        assertEquals(4.317, EmberWalk.CHASE_SPEED * 20.0, 0.01, "a player's walk, blocks a second");
        assertEquals(EmberWalk.CHASE_SPEED / 4.0, EmberWalk.SPEED, 0.001, "its own walk a quarter of it");
        assertTrue(SPRINT > EmberWalk.CHASE_SPEED * 1.25, "a sprint gets away");
    }

    @Test
    void theRulesOfTheChase() {
        EmberChase c = new EmberChase();
        Vector3d at = new Vector3d(5, 0, 0);
        assertTrue(c.free());
        c.start(7, at);
        assertEquals(EmberChase.Phase.ROUSE, c.phase);
        for (int t = 0; t < EmberChase.ROUSE - 1; t++) assertTrue(c.tick(true, true, at, 5, true));
        assertEquals(EmberChase.Phase.ROUSE, c.phase, "still rousing");
        assertTrue(c.tick(true, true, at, 5, true));
        assertEquals(EmberChase.Phase.CHASE, c.phase, "set off after its warning");
        // out of sight: it makes for where it last saw them, and gives up after LOST
        Vector3d gone = new Vector3d(9, 0, 3);
        for (int t = 0; t < EmberChase.LOST - 1; t++) assertTrue(c.tick(true, false, gone, 8, true));
        assertEquals(at, c.seenAt, "makes for where it last saw them");
        assertFalse(c.tick(true, false, gone, 8, true));
        assertEquals("lost", c.gaveUp);
        assertFalse(c.free(), "rests after giving up");
        for (int t = 0; t < EmberChase.REST; t++) c.tick(true, null, at, 5, true);
        assertTrue(c.free(), "notices again after its rest");
        // seen again before LOST: the count starts over
        c.start(7, at);
        for (int t = 0; t < EmberChase.ROUSE; t++) c.tick(true, true, at, 5, true);
        for (int t = 0; t < EmberChase.LOST - 10; t++) c.tick(true, false, gone, 8, true);
        assertTrue(c.tick(true, true, gone, 8, true));
        for (int t = 0; t < EmberChase.LOST - 10; t++) assertTrue(c.tick(true, false, at, 8, true));
        assertEquals(gone, c.seenAt);
        // a look skipped keeps the last one
        assertTrue(c.tick(true, null, at, 8, true));
        // too far, gone, or no way to them
        assertFalse(c.tick(true, true, at, EmberChase.FAR + 0.1, true));
        assertEquals("far", c.gaveUp);
        restart(c, at);
        assertFalse(c.tick(false, true, at, 5, true));
        assertEquals("gone", c.gaveUp);
        restart(c, at);
        for (int t = 0; t < EmberChase.NO_WAY - 1; t++) assertTrue(c.tick(true, true, at, 5, false));
        assertFalse(c.tick(true, true, at, 5, false));
        assertEquals("no way", c.gaveUp);
        // set after the same one again while chasing: it goes on, without rousing again
        restart(c, at);
        c.start(7, gone);
        assertEquals(EmberChase.Phase.CHASE, c.phase);
        assertEquals(gone, c.seenAt);
    }

    private static void restart(EmberChase c, Vector3d at) {
        for (int t = 0; t < EmberChase.REST; t++) c.tick(true, null, at, 5, true);
        c.start(7, at);
        for (int t = 0; t < EmberChase.ROUSE; t++) c.tick(true, true, at, 5, true);
        assertEquals(EmberChase.Phase.CHASE, c.phase);
    }

    // ------------------------------------------------------------------------------------------------ made-up tunnels

    /** A straight tunnel five wide and six tall along x, from x0 to x1 (its floor's top at 0). */
    static EmberClips.Blocks straight(int x0, int x1) { return EmberClips.carved(new int[]{x0, 0, -2, x1, 5, 2}); }

    /** Feet along a straight line from a to b, a block apart. */
    static List<Vector3d> line(List<Vector3d> way, Vector3d a, Vector3d b) {
        int n = (int) Math.ceil(a.distance(b));
        for (int k = way.isEmpty() ? 0 : 1; k <= n; k++) way.add(new Vector3d(a).lerp(b, (double) k / n));
        return way;
    }

    static EmberClips.Scene scene(String name, EmberClips.Blocks b, Vector3d start, List<Vector3d> way, double speed, int ticks) {
        EmberClips.Scene s = new EmberClips.Scene(name, b, start, 0);
        s.runner = way;
        s.runnerSpeed = speed;
        s.ticks = ticks;
        return s;
    }

    static EmberClips.Stats run(EmberClips.Scene s) throws Exception { return EmberClips.run(s, RIG, null, null, null, 20); }

    @Test
    void catchesSomeoneStanding() throws Exception {
        // twelve blocks down the tunnel, standing: it sees them, rouses, comes at a walk, presses in and pricks
        EmberClips.Scene s = scene("catch", straight(-60, 120), new Vector3d(-20.5, 3.0, 0.5),
                line(new ArrayList<>(), new Vector3d(-8.5, 0, 0.5), new Vector3d(100.5, 0, 0.5)), 0.0, 240);
        EmberClips.Stats st = run(s);
        assertTrue(st.firstCaught > EmberChase.ROUSE + 20 && st.firstCaught < 90, "first pressed in at " + st.firstCaught);
        assertTrue(st.pricks >= 4, "pricks " + st.pricks);
        assertTrue(st.gaveUpAt < 0, "gave up " + st.gaveUp);
        assertBodyHeld(st, 0.01, 0.02);
    }

    @Test
    void keepsUpWithSomeoneWalkingAway() throws Exception {
        EmberClips.Scene s = scene("walkaway", straight(-60, 140), new Vector3d(-20.5, 3.0, 0.5),
                line(new ArrayList<>(), new Vector3d(-10.5, 0, 0.5), new Vector3d(130.5, 0, 0.5)), WALK, 400);
        EmberClips.Stats st = run(s);
        assertTrue(st.gaveUpAt < 0, "gave up at " + st.gaveUpAt + " (" + st.gaveUp + ")");
        assertTrue(st.chaseTicks > 380, "chased " + st.chaseTicks);
        assertTrue(st.chaseDistMost < 14.0, "fell " + st.chaseDistMost + " behind");
        assertBodyHeld(st, 0.01, 0.02);
    }

    @Test
    void loosesSomeoneSprinting() throws Exception {
        EmberClips.Scene s = scene("sprint", straight(-60, 260), new Vector3d(-20.5, 3.0, 0.5),
                line(new ArrayList<>(), new Vector3d(-10.5, 0, 0.5), new Vector3d(250.5, 0, 0.5)), SPRINT, 700);
        EmberClips.Stats st = run(s);
        assertEquals("far", st.gaveUp);
        assertTrue(st.gaveUpAt > 200 && st.gaveUpAt < 600, "gave up at " + st.gaveUpAt);
    }

    @Test
    void followsRoundABend() throws Exception {
        // along x, then round a corner along z; they walk on slowly round it and out of its sight
        EmberClips.Blocks b = EmberClips.carved(new int[]{-40, 0, -2, 2, 5, 2}, new int[]{-2, 0, -2, 2, 5, 60});
        List<Vector3d> way = line(new ArrayList<>(), new Vector3d(-12.5, 0, 0.5), new Vector3d(0.5, 0, 0.5));
        line(way, new Vector3d(0.5, 0, 0.5), new Vector3d(0.5, 0, 50.5));
        EmberClips.Scene s = scene("bend", b, new Vector3d(-24.5, 3.0, 0.5), way, 0.12, 400);
        EmberClips.Stats st = run(s);
        assertTrue(st.firstCaught > 0, "never caught them");
        assertTrue(st.gaveUpAt < 0, "gave up " + st.gaveUp);
        assertBodyHeld(st, 0.01, 0.02);
    }

    @Test
    void climbsAfterThemBetweenStoreys() throws Exception {
        // a lower tunnel, a ramp up eight blocks over sixteen, an upper tunnel; they stand up there
        List<int[]> open = new ArrayList<>();
        open.add(new int[]{-30, 0, -2, 9, 5, 2});
        for (int x = 10; x <= 26; x++) {
            int f = (x - 10) / 2;
            open.add(new int[]{x, f, -2, x, f + 5, 2});
        }
        open.add(new int[]{27, 8, -2, 60, 13, 2});
        EmberClips.Blocks b = EmberClips.carved(open.toArray(new int[0][]));
        List<Vector3d> way = line(new ArrayList<>(), new Vector3d(30.5, 8, 0.5), new Vector3d(56.5, 8, 0.5));
        EmberClips.Scene s = scene("storeys", b, new Vector3d(4.5, 3.0, 0.5), way, 0.0, 400);
        s.chaseNow = true;
        EmberClips.Stats st = run(s);
        assertTrue(st.firstCaught > 0, "never caught them up there");
        assertTrue(st.highest > 10.0, "climbed to " + st.highest);
        assertBodyHeld(st, 0.01, 0.02);
    }

    @Test
    void stopsAtTheEdgeOfGroundItMayNotWalk() throws Exception {
        // they stand in ground it keeps out of (as the Ash Hollows): it comes to the edge, waits, and gives up
        EmberClips.Scene s = scene("hollows", straight(-60, 60), new Vector3d(-14.5, 3.0, 0.5),
                line(new ArrayList<>(), new Vector3d(6.5, 0, 0.5), new Vector3d(50.5, 0, 0.5)), 0.0, 300);
        s.allowed = p -> p.x < 0.0;
        s.chaseNow = true;
        EmberClips.Stats st = run(s);
        assertEquals("no way", st.gaveUp);
        assertTrue(st.gaveUpAt < 160, "gave up at " + st.gaveUpAt);
        assertEquals(0, st.outOfGround, "ticks out of its ground");
        assertTrue(st.firstCaught < 0, "reached them in ground it keeps out of");
    }

    /** Its body held together: its ball out of the rock, most spines braced, few bows or stepping points in rock. */
    static void assertBodyHeld(EmberClips.Stats st, double through, double pointIn) {
        assertEquals(0, st.ballIn, "ticks with its ball in the rock");
        assertTrue(st.braced() > 16.0, "braced " + st.braced());
        assertTrue(st.throughShare() < through, "bows through rock " + st.throughShare());
        assertTrue(st.pointInShare() < pointIn, "stepping points in rock " + st.pointInShare());
    }

    // ------------------------------------------------------------------------------------------------ the Wasteland

    static final int CHASES = 12, CHASE_TICKS = 500;

    /**
     * Through the Wasteland's own tunnels for two seeds: from twelve tunnel floors each, someone eight steps along a way
     * from it (a way it could follow, squeezing through where it must, as far as one goes on to 90 steps) walks on along
     * it at a player's walk, stopping at its end, and it is set after them at once.
     */
    @Test
    void chasesThroughTheWastelandsTunnels() throws Exception {
        EmberClips.Stats all = new EmberClips.Stats();
        int chases = 0, kept = 0, ballIn = 0, out = 0;
        double braced = 0, dist = 0;
        long routeCells = 0, routes = 0;
        int routeMost = 0;
        for (long seed : EmberWastelandTest.SEEDS) {
            WastelandBlocks w = WastelandBlocks.build(seed, 0, 0, 256, true);
            Predicate<Vector3d> allowed = p -> w.inLayer((int) Math.floor(p.y)) && !w.hollows((int) Math.floor(p.x), (int) Math.floor(p.z));
            EmberWalk.Ground g = EmberClips.ground(w::mat, allowed);
            List<EmberClips.Scene> starts = EmberClips.wastelandWalks(seed, CHASES, CHASE_TICKS, 24);
            for (EmberClips.Scene s : starts) {
                List<Vector3d> way = EmberClips.runnerWay(g, s.start, 90, seed + chases);
                // a pocket with no long way on from it (a stretch of tunnel shut in by squeezes it cannot pass): none to chase along
                if (way.size() < 24) { System.out.println(s.name + ": no way on long enough (" + way.size() + " places)"); continue; }
                s.name = s.name.replace("waste", "chase");
                s.runner = way;
                s.runnerAt = 8;
                s.runnerSpeed = WALK;
                s.chaseNow = true;
                s.ticks = CHASE_TICKS;
                EmberClips.Stats st = EmberClips.run(s, RIG, null, null, null, 20);
                chases++;
                if (st.gaveUpAt < 0 || st.gaveUpAt > CHASE_TICKS * 0.6) kept++;
                braced += st.braced();
                dist += st.chaseDist / Math.max(1, st.chaseTicks);
                ballIn += st.ballIn;
                out += st.outOfGround;
                all.legTicks += st.legTicks;
                all.through += st.through;
                all.swingTicks += st.swingTicks;
                all.pointIn += st.pointIn;
                routes += st.routes;
                routeMost = Math.max(routeMost, st.routeMost);
            }
        }
        System.out.printf(Locale.ROOT, "%d chases: %d kept up most of the way, %.1f from them on average, %.1f of 30 braced, ball in rock %d, out of its ground %d,"
                        + " bows through rock %.2f%% of spine-ticks, stepping points in rock %.2f%% of stepping ticks, %d ways found (at most %d places looked through for one)%n",
                chases, kept, dist / chases, braced / chases, ballIn, out, 100.0 * all.throughShare(), 100.0 * all.pointInShare(), routes, routeMost);
        assertTrue(chases >= EmberWastelandTest.SEEDS.length * CHASES / 2, "chases run " + chases);
        assertEquals(0, ballIn, "ticks with its ball in the rock");
        assertEquals(0, out, "ticks out of its ground");
        assertTrue(kept >= chases * 3 / 4, "kept up in " + kept + " of " + chases);
        assertTrue(braced / chases > 15.0, "braced " + braced / chases);
        assertTrue(all.throughShare() < 0.02, "bows through rock " + all.throughShare());
        assertTrue(all.pointInShare() < 0.03, "stepping points in rock " + all.pointInShare());
        assertTrue(routeMost <= EmberWalk.ROUTE_CELLS, "places looked through " + routeMost);
    }
}
