package com.explorationremix.creature.body;

import com.explorationremix.creature.EmberSpots;
import com.explorationremix.world.feature.EmberUrchinsFeature;
import com.explorationremix.world.terrain.WastelandBlocks;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ember urchin (0.23.7) walking the Wasteland's own tunnels, worked out offline (its noise, lava pools and hanging
 * columns) for two seeds: from eight tunnel floors each (as it is set down: ways no wider than 7 with a way through) it
 * walks a minute. It must get on (roam, not pace up and down a short stretch), never stick for good, leave its
 * ground (the Wasteland, out of the Ash Hollows) or put its ball in the rock; it must keep most of its spines braced;
 * and its spines must keep out of the rock: their bows, and their points as they step. And the share of the floors
 * it is set down on, near those it is (EmberUrchinsFeature.KEPT, which the chance of one is made up by).
 */
class EmberWastelandTest {
    static final long[] SEEDS = {8675309L, 3L};
    static final int WALKS = 8, TICKS = 1200;

    @Test
    void walksTheWastelandsTunnels() throws Exception {
        EmberRig rig = EmberRig.load(EmberSpec.GAME_SCALE);
        EmberClips.Stats all = new EmberClips.Stats();
        int walks = 0, stuck = 0, ballIn = 0, out = 0, paced = 0;
        double went = 0, braced = 0, far = 0;
        for (long seed : SEEDS) {
            List<EmberClips.Scene> scenes = EmberClips.wastelandWalks(seed, WALKS, TICKS, 24);
            assertEquals(WALKS, scenes.size(), "tunnel floors to start from on seed " + seed);
            for (EmberClips.Scene s : scenes) {
                EmberClips.Stats st = EmberClips.run(s, rig, null, null, null, 20);
                walks++;
                went += st.went;
                braced += st.braced();
                stuck += st.stuck;
                ballIn += st.ballIn;
                out += st.outOfGround;
                all.legTicks += st.legTicks;
                all.through += st.through;
                all.swingTicks += st.swingTicks;
                all.pointIn += st.pointIn;
                assertTrue(st.went > 10.0, s.name + " walked only " + st.went);
                far += st.farthest;
                if (st.farthest < 8.0) paced++;
            }
        }
        System.out.printf(Locale.ROOT, "%d walks: %.1f blocks a minute, at most %.1f from their start on average, %d kept within 8 blocks of it,"
                        + " %.1f of 30 braced, stuck %d, ball in rock %d, out of its ground %d, bows through rock %.2f%% of spine-ticks,"
                        + " stepping points in rock %.2f%% of stepping ticks%n",
                walks, went / walks, far / walks, paced, braced / walks, stuck, ballIn, out, 100.0 * all.throughShare(), 100.0 * all.pointInShare());
        assertTrue(paced <= 1, "walks that kept within 8 blocks of their start (pacing up and down a short stretch): " + paced);
        assertTrue(far / walks > 15.0, "on average at most " + far / walks + " from their start");
        assertEquals(0, ballIn, "ticks with its ball in the rock");
        assertEquals(0, out, "ticks out of its ground");
        assertTrue(stuck <= walks / 4, "times stuck: " + stuck);
        assertTrue(braced / walks > 20.0, "braced " + braced / walks);
        assertTrue(all.throughShare() < 0.01, "bows through rock " + all.throughShare());
        assertTrue(all.pointInShare() < 0.02, "stepping points in rock " + all.pointInShare());
    }

    /**
     * Of the tunnel floors (no wider than 7) near one it is set down on (within 12 blocks across, 3 up or down), the share
     * it would be set down on too: what {@link EmberUrchinsFeature#KEPT} makes up for, so that the ways it walks get
     * about one to each hundred blocks.
     */
    @Test
    void keptFloorsAsReckoned() {
        for (long seed : SEEDS) {
            WastelandBlocks w = WastelandBlocks.build(seed, 0, 0, 256, true);
            Predicate<Vector3d> allowed = p -> w.inLayer((int) Math.floor(p.y)) && !w.hollows((int) Math.floor(p.x), (int) Math.floor(p.z));
            EmberWalk.Ground g = EmberClips.ground(w::mat, allowed);
            List<double[]> floors = new ArrayList<>();
            for (int x = -100; x <= 100; x += 3)
                for (int z = -100; z <= 100; z += 3)
                    for (EmberSpots.Spot sp : EmberSpots.floors(g, x, z, w.top - 1, w.bottom + 1)) {
                        if (EmberSpots.width(g, sp.x(), sp.y(), sp.z()) > 7.0) continue;
                        boolean kept = EmberSpots.wayThrough(g, sp) && EmberSpots.roams(g, sp);
                        floors.add(new double[]{sp.x(), sp.y(), sp.z(), kept ? 1 : 0});
                    }
            int near = 0, kept = 0, keptAll = 0;
            for (double[] a : floors) {
                if (a[3] == 0) continue;
                keptAll++;
                for (double[] b : floors) {
                    double dx = a[0] - b[0], dz = a[2] - b[2];
                    if (a == b || Math.abs(a[1] - b[1]) > 3 || dx * dx + dz * dz > 144) continue;
                    near++;
                    if (b[3] == 1) kept++;
                }
            }
            double share = kept / (double) Math.max(1, near);
            System.out.printf(Locale.ROOT, "seed %d: %d tunnel floors, %d kept (%.2f); near a kept one, %d of %d kept (%.2f), KEPT %.2f%n",
                    seed, floors.size(), keptAll, keptAll / (double) Math.max(1, floors.size()), kept, near, share, EmberUrchinsFeature.KEPT);
            assertTrue(Math.abs(share - EmberUrchinsFeature.KEPT) < 0.15, "seed " + seed + ": near a kept floor " + share + " are kept");
        }
    }
}
