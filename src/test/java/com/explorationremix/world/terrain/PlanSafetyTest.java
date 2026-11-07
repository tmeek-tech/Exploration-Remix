package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.Way;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The planner's safety net ({@link NetPlanner#plan}): a plan that throws is made again with its layout salted, and a
 * cell-layer whose every attempt throws is solid rock round its landings, so a fault in a stage cannot stop the world's
 * generation where a player goes. The faults are forced through a hook, on seeds no other test plans. And a sweep over
 * plans no other test makes, where no plan may need the net: the other plan tests hold the same for theirs
 * (NetworkTest, TerrainTest), so the net never hides a fault from the suite.
 */
class PlanSafetyTest {
    static final long ROCK_SEED = 7_720_501L, SALT_SEED = 7_720_502L;

    @AfterEach
    void unhook() { NetPlanner.failHook = null; }

    /**
     * Every attempt of every planned layer's plan in cell 0,0 throws: each plan is its landings and nothing else, the
     * fault is recorded, the neighbours plan as ever, and every query of the terrain over the cell and its eight
     * neighbours answers, in every layer (density, fine density, fluid, biome, the materials' and features' queries, the
     * walking lines, climbs, vents, and the dev tools' places and descriptions). In the cell, every block of a planned
     * layer sampled away from the landings and the gates is rock. The noise layers (the Wasteland, the Galleries) have no
     * plan to fail, no walking lines and no climbs.
     */
    @Test
    void aCellWhosePlansAllThrowIsRock() {
        NetPlanner.failHook = (seed, li, cx, cz, attempt) -> seed == ROCK_SEED && cx == 0 && cz == 0;
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, ROCK_SEED);
        Network w = t.network;
        int cell = (int) w.cell, layers = t.shape.layerCount;
        List<String> fails = new ArrayList<>();
        for (int li = 0; li < layers; li++) {
            if (!Terrain.planned(li)) continue;
            Net n = w.net(li, 0, 0);
            String tg = "layer " + li + " cell 0,0";
            if (!n.failed || n.attempts != NetPlanner.ATTEMPTS) fails.add(tg + ": failed " + n.failed + " after " + n.attempts + " attempts");
            if (!NetPlanner.FAULTS.contains(ROCK_SEED + "/" + li + "/0/0")) fails.add(tg + ": the fault is not recorded");
            // the two landings, and in the Blue Reaches perhaps the other den gates' lower landings too
            int most = li == Terrain.BLUE ? t.plans.denGates(0, 0).size() + 1 : 2;
            if (n.ch.size() < 2 || n.ch.size() > most || n.entry < 0 || n.exit < 0 || !n.ch.get(n.entry).landing() || !n.ch.get(n.exit).landing()
                    || !n.ch.stream().allMatch(Chamber::landing))
                fails.add(tg + ": " + n.ch.size() + " chambers, not just its landings");
            if (!n.edges.isEmpty() || !n.ways.isEmpty() || !n.shafts.isEmpty()
                    || !n.canyons.isEmpty() || n.dark != null || !n.portals.isEmpty())
                fails.add(tg + ": the plan is not empty");
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Net m = w.net(li, dx, dz);
                if (m.failed || m.attempts != 0) fails.add("layer " + li + " cell " + dx + "," + dz + ": a neighbour needed " + m.attempts + " attempts");
            }
        }
        long samples = 0, open = 0;
        // the cell and the half of each neighbour next to it (the queries that reach round a column stay in the nine)
        for (int x = -cell / 2; x < cell + cell / 2; x += 23) for (int z = -cell / 2; z < cell + cell / 2; z += 29) {
            boolean inCell = x >= 0 && x < cell && z >= 0 && z < cell;
            for (int li = 0; li < layers; li++) {
                int lines = t.wayLinesAt(li, x, z, 24).n + t.carvedLinesAt(li, x, z, 24).n;
                if (!Terrain.planned(li) && lines != 0) fails.add("walking lines in noise layer " + li + " at " + x + " " + z);
                for (int y = t.shape.bottom(li); y <= t.shape.top(li); y += 7) {
                    double d = t.density(x, y, z), f = t.fine(x, y, z, d);
                    t.fluidAt(x, y, z);
                    if (t.biomeAt(x, y, z) == null) fails.add("no biome at " + x + " " + y + " " + z);
                    t.geodeShellDepth(x, y, z);
                    t.tubeShellDepth(x, y, z);
                    t.looks.tube(x, y, z);
                    t.looks.hang(x, y, z);
                    t.looks.lavaKept(x, y, z);
                    t.citySite(x, y, z);
                    samples++;
                    if (!inCell || f > 0 || !Terrain.planned(li)) continue;
                    open++;
                    if (!nearLanding(t, w.net(li, 0, 0), li, x, z) && fails.size() < 40) fails.add("open at " + x + " " + y + " " + z + " in the failed cell (layer " + li + ")");
                }
            }
            t.ventsNear(x, z, 48);
        }
        for (int li = 0; li < layers; li++) {
            List<Net.Shaft> shafts = t.shaftsIn(-16, -16, cell + 15, cell + 15, li);
            if (!Terrain.planned(li) && !shafts.isEmpty()) fails.add("climbs in noise layer " + li);
            for (String line : t.describe(cell / 2, t.shape.waterY(li) + 10, cell / 2)) assertNotNull(line);
        }
        for (DeepBiome b : DeepBiome.values()) t.places(b, cell / 2.0, cell / 2.0);
        // the noise layers have no plan: asking for one is a mistake (it throws under the tests, strict)
        boolean was = Network.strict;
        Network.strict = true;
        try {
            for (int li = 0; li < layers; li++) {
                int l = li;
                if (!Terrain.planned(li)) assertThrows(IllegalArgumentException.class, () -> t.network(l, 0, 0));
            }
        } finally {
            Network.strict = was;
        }
        System.out.printf("aCellWhosePlansAllThrowIsRock: %d samples, %d open in the failed cell (at its landings)%n", samples, open);
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails));
    }

    /**
     * Near one of the failed cell's landings or the gates' passages, which are carved whatever the plan: band 1's are
     * every den gate's ({@link Plans#denGates}).
     */
    static boolean nearLanding(Terrain t, Net n, int li, int x, int z) {
        for (Chamber c : n.ch)
            if (c.landing() && Math.hypot(x - c.x, z - c.z) < c.rx + 12) return true;
        for (int band = li + 1; band <= Math.min(li + 2, t.shape.layerCount); band++)
            for (Plans.Gate g : band == 1 ? t.plans.denGates(0, 0) : List.of(t.plans.gate(band, 0, 0)))
                if (g.overlaps(x, z, x, z, 8)) return true;
        return false;
    }

    /**
     * Attempt 0 of every planned layer's plan in cell 0,0 throws, and attempt 1 is kept: its layout moves (another plan
     * hash), and what the cell shares with its neighbours stays where it was: the portal vestibules (place, floor, size,
     * look), their stubs across the seams, and the landings. In the Deep Gardens no block within {@link #SEAM_SAME} of
     * the cell's edges changes either (the cell's own tunnels start at the vestibules' inner doors). Two things may
     * change at a seam, as they may in any plan: a vestibule that no tunnel of the salted plan reaches goes with its stub,
     * and the stub ends blind at the seam (its blocks are not compared); and the Blue Reaches' deep dark, faded by the
     * rift's geodes and closed where the plan did not join it, both the cell's own layout, may move a few blocks at the
     * seams there (its blocks are not compared).
     */
    @Test
    void aPlanThatThrowsIsMadeAgainWithWhatItSharesInPlace() {
        Terrain plain = new Terrain(TerrainSettings.defaults(), -64, SALT_SEED);
        int layers = Terrain.WASTELAND;   // the planned layers
        Net[] a = new Net[layers], b = new Net[layers];
        for (int li = 0; li < layers; li++) a[li] = plain.network.net(li, 0, 0);
        NetPlanner.failHook = (seed, li, cx, cz, attempt) -> seed == SALT_SEED && cx == 0 && cz == 0 && attempt == 0;
        Terrain salted = new Terrain(TerrainSettings.defaults(), -64, SALT_SEED);
        for (int li = 0; li < layers; li++) b[li] = salted.network.net(li, 0, 0);
        NetPlanner.failHook = null;
        List<String> fails = new ArrayList<>();
        List<double[]> dropped = new ArrayList<>();
        for (int li = 0; li < layers; li++) {
            String tg = "layer " + li;
            if (a[li].attempts != 0 || b[li].attempts != 1 || b[li].failed) fails.add(tg + ": attempts " + a[li].attempts + " and " + b[li].attempts + ", failed " + b[li].failed);
            if (NetworkTest.planHash(a[li]) == NetworkTest.planHash(b[li])) fails.add(tg + ": the salted plan is the same plan");
            if (a[li].portals.size() != b[li].portals.size()) { fails.add(tg + ": " + a[li].portals.size() + " portals against " + b[li].portals.size()); continue; }
            for (int k = 0; k < a[li].portals.size(); k++) {
                Chamber p = a[li].ch.get(a[li].portals.get(k)), q = b[li].ch.get(b[li].portals.get(k));
                if (p.x != q.x || p.z != q.z || p.f0 != q.f0 || p.rx != q.rx || p.rz != q.rz || p.h != q.h || p.rise != q.rise || p.warp != q.warp || p.hash != q.hash)
                    fails.add(tg + ": portal vestibule " + k + " moved");
                // a vestibule no tunnel of the plan reaches goes, with its stub, in any plan (droppedPortals)
                if (!p.keep || !q.keep) {
                    dropped.add(new double[]{li, p.x, p.z, p.R()});
                    continue;
                }
                Way s = NetworkTest.portalStub(a[li], p.id), r = NetworkTest.portalStub(b[li], q.id);
                if (s == null || r == null || s.n != r.n) { fails.add(tg + ": the stub of portal vestibule " + k + " differs"); continue; }
                for (int i = 0; i < s.n; i++)
                    if (s.x[i] != r.x[i] || s.z[i] != r.z[i] || s.f[i] != r.f[i] || s.w[i] != r.w[i] || s.hgt[i] != r.hgt[i] || s.slab[i] != r.slab[i]) {
                        fails.add(tg + ": the stub of portal vestibule " + k + " moved");
                        break;
                    }
            }
            for (int end = 0; end < 2; end++) {
                Chamber p = a[li].ch.get(end == 0 ? a[li].entry : a[li].exit), q = b[li].ch.get(end == 0 ? b[li].entry : b[li].exit);
                if (p.kind != q.kind || p.x != q.x || p.z != q.z || p.f0 != q.f0 || p.rx != q.rx || p.h != q.h) fails.add(tg + ": the " + p.kind + " landing moved");
            }
        }
        int cell = (int) plain.network.cell;
        long samples = 0;
        for (int li = Terrain.GARDENS; li < layers; li++) {
            for (int d = 0; d < SEAM_SAME; d += 3) for (int s = 0; s < cell; s += 4) {
                int[][] cols = {{d, s}, {cell - 1 - d, s}, {s, d}, {s, cell - 1 - d}};
                for (int[] c : cols) for (int y = plain.shape.bottom(li); y <= plain.shape.top(li); y += 2) {
                    if (byDropped(dropped, li, c[0], c[1])) continue;
                    samples++;
                    boolean rockA = plain.fine(c[0], y, c[1], plain.density(c[0], y, c[1])) > 0, rockB = salted.fine(c[0], y, c[1], salted.density(c[0], y, c[1])) > 0;
                    int fa = rockA ? -1 : plain.fluidAt(c[0], y, c[1]), fb = rockB ? -1 : salted.fluidAt(c[0], y, c[1]);
                    if ((rockA != rockB || fa != fb) && fails.size() < 40) fails.add("layer " + li + ": the block at " + c[0] + " " + y + " " + c[1] + ", " + d + " in from the edge, changed");
                }
            }
        }
        System.out.printf("aPlanThatThrowsIsMadeAgainWithWhatItSharesInPlace: %d blocks by the seams compared; %d vestibules dropped by one of the plans%n", samples, dropped.size());
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails));
    }

    /** How far in from a cell's edges a salted plan leaves every block as it was (blocks; see above). */
    static final int SEAM_SAME = 16;

    /** Within 16 blocks of the footprint of a vestibule one of the two plans dropped ({li, x, z, reach}). */
    static boolean byDropped(List<double[]> dropped, int li, int x, int z) {
        for (double[] v : dropped) if (v[0] == li && Math.hypot(x - v[1], z - v[2]) < v[3] + 16) return true;
        return false;
    }

    /** Seeds and cells the sweep plans: no other test plans them. */
    static final long[] SWEEP_SEEDS = {900, 901, 902, 903, 904, 905, 906, 907, 908, 909, 910, 911, 912, 913, 914, 915, 916, 917, 918, 919};
    static final int[][] SWEEP_CELLS = {{1, 2}, {-4, 0}, {6, -3}};

    /**
     * No plan of the planned layers (the Blue Reaches and the Deep Gardens) throws, over 60 cells each that no other test
     * plans: every one is made at the first attempt. (The Wasteland and the Basalt Galleries, whose plans threw, are
     * noise fields now and have no plan.)
     */
    @Test
    void sweepFindsNoFault() {
        List<String> fails = new ArrayList<>();
        long t0 = System.nanoTime();
        int plans = 0;
        for (long seed : SWEEP_SEEDS) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            for (int[] c : SWEEP_CELLS) for (int li = 0; li < t.shape.layerCount && Terrain.planned(li); li++) {
                plans++;
                firstAttempt(NetPlanner.plan(t.network, li, c[0], c[1]), NetworkTest.tag(seed, li, c[0], c[1]), fails);
            }
        }
        System.out.printf("sweepFindsNoFault: %d plans in %.1f s%n", plans, (System.nanoTime() - t0) / 1e9);
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /** A plan made at its first attempt, as every plan of the tests must be. */
    static void firstAttempt(Net n, String tag, List<String> fails) {
        if (n.attempts != 0 || n.failed) fails.add(tag + ": the plan threw " + n.attempts + " times" + (n.failed ? " and is left as rock" : " and was made again"));
    }
}
