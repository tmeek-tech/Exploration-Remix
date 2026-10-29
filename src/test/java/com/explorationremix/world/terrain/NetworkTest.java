package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.EK;
import com.explorationremix.world.terrain.Net.Edge;
import com.explorationremix.world.terrain.Net.Kind;
import com.explorationremix.world.terrain.Net.Shaft;
import com.explorationremix.world.terrain.Net.Way;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The deep cave networks' promises (design: network-final.md, section 14). The graph tests plan cell-layers and check
 * the plans; the voxel tests (tag "voxel") build cells block by block exactly as the game does and walk them.
 */
class NetworkTest {
    static final long[] SEEDS = {8675309L, 12345L, -42L};
    static final int[][] CELLS = {{0, 0}, {-1, 0}, {3, -2}};
    private static final Map<Long, Terrain> TERRAINS = new ConcurrentHashMap<>();

    static Terrain terrain(long seed) {
        return TERRAINS.computeIfAbsent(seed, s -> new Terrain(TerrainSettings.defaults(), -64, s));
    }

    static String tag(long seed, int li, int cx, int cz) { return "seed " + seed + " layer " + li + " cell " + cx + "," + cz; }

    interface PlanCheck { void check(Terrain t, Net n, String tag, List<String> fails); }

    /** Runs a check over every cell-layer of the fast tests (3 seeds x 3 cells x 3 layers) and fails with every miss. */
    static void overPlans(PlanCheck c) {
        List<String> fails = new ArrayList<>();
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int[] cell : CELLS) for (int li = 0; li < t.shape.layerCount; li++)
                c.check(t, t.network.net(li, cell[0], cell[1]), tag(seed, li, cell[0], cell[1]), fails);
        }
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails.subList(0, Math.min(40, fails.size()))));
    }

    // ================================================================================================ graph helpers
    /** Nodes joined to the entry by any edge between kept nodes (stubs too: a stub's pocket is walked to). */
    static boolean[] joined(Net n) {
        List<List<Edge>> adj = new ArrayList<>();
        for (int i = 0; i < n.ch.size(); i++) adj.add(new ArrayList<>());
        for (Edge e : n.edges) {
            if (!n.ch.get(e.a).keep || !n.ch.get(e.b).keep) continue;
            adj.get(e.a).add(e);
            adj.get(e.b).add(e);
        }
        boolean[] seen = new boolean[n.ch.size()];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(n.entry);
        seen[n.entry] = true;
        while (!q.isEmpty()) {
            int u = q.poll();
            for (Edge e : adj.get(u)) {
                int v = e.other(u);
                if (!seen[v]) { seen[v] = true; q.add(v); }
            }
        }
        return seen;
    }

    /** The ways out of a node: edges other than blind stubs, to kept nodes. */
    static List<Edge> waysOut(Net n, int id) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : n.edges) {
            if (e.kind == EK.STUB || (e.a != id && e.b != id)) continue;
            if (!n.ch.get(e.a).keep || !n.ch.get(e.b).keep) continue;
            out.add(e);
        }
        return out;
    }

    /** The PORTAL chamber of a plan standing at (x, z), or null. */
    static Chamber vestibule(Net n, double x, double z) {
        for (int id : n.portals) {
            Chamber c = n.ch.get(id);
            if (Math.abs(c.x - x) < 1e-9 && Math.abs(c.z - z) < 1e-9) return c;
        }
        return null;
    }

    static Way portalStub(Net n, int vestibule) {
        for (Way wy : n.ways) if (wy.kind == EK.PORTAL && (wy.a == vestibule || wy.b == vestibule)) return wy;
        return null;
    }

    // ================================================================================================ graph checks
    static void joinedToEntry(Terrain t, Net n, String tag, List<String> fails) {
        boolean[] r = joined(n);
        for (Chamber c : n.ch) {
            if (!c.keep || c.kind == Kind.LAVA) continue;
            if (!r[c.id]) fails.add(tag + ": " + c.kind + "#" + c.id + " at " + (int) c.x + " " + (int) c.f0 + " " + (int) c.z + " is not joined to the entry");
        }
        // the portals as the pure rule lays them out, not as the plan kept them
        for (Network.Portal p : t.network.portalsOfCell(n.li, n.cx, n.cz)) {
            boolean mine = p.cellA(n.cx, n.cz);
            Chamber v = vestibule(n, mine ? p.ax : p.bx, mine ? p.az : p.bz);
            if (v == null) { fails.add(tag + ": no vestibule for the portal at " + (int) p.line + "/" + (int) p.along); continue; }
            if (n.droppedPortals.contains(v.id)) {
                if (v.keep) fails.add(tag + ": dropped vestibule #" + v.id + " is kept");
                if (portalStub(n, v.id) != null) fails.add(tag + ": dropped vestibule #" + v.id + " still has its stub");
            } else if (!v.keep || !r[v.id]) fails.add(tag + ": portal vestibule #" + v.id + " is neither joined nor dropped");
        }
    }

    static void waysToGates(Terrain t, Net n, String tag, List<String> fails) {
        int f = NetGraph.maxFlow(n, n.entry, n.exit, false);
        if (f < 2) fails.add(tag + ": only " + f + " way(s) from the entry to the exit");
        int de = waysOut(n, n.entry).size(), dx = waysOut(n, n.exit).size();
        if (de < 2 || (n.courtsE.size() >= 3 && de < 3)) fails.add(tag + ": the entry landing has " + de + " ways out and " + n.courtsE.size() + " courts");
        if (dx < 2 || (n.courtsX.size() >= 3 && dx < 3)) fails.add(tag + ": the exit landing has " + dx + " ways out and " + n.courtsX.size() + " courts");
        if (!NetGraph.reach(n, n.entry, true)[n.exit]) fails.add(tag + ": no way from the entry to the exit without climbing");
    }

    /** True when the planner had to lower the chamber's ceiling under the seal below the band above. */
    static boolean sealCapped(Terrain t, Net n, Chamber c) {
        double room = t.shape.top(n.li) - Terrain.SEAL - 6.5 - c.f0 - c.rise - c.rampDrop * 0.5 - 2;
        return c.h <= room + 1e-6 && c.h >= room - 1e-6 || c.h <= 7.5 + 1e-9;
    }

    static void sizes(Terrain t, Net n, String tag, List<String> fails) {
        for (Chamber c : n.ch) {
            if (!c.keep || c.landing() || c.helix() || c.kind == Kind.PORTAL || c.kind == Kind.POCKET || c.kind == Kind.CANYON || c.dark >= 0) continue;
            double across = 2 * Math.max(c.rx, c.rz);
            String id = tag + ": " + c.kind + "#" + c.id + (c.set ? " (set piece)" : c.court ? " (court)" : "");
            // courts 9 to 14 (Blue 12 to 18), grottos 10 to 20 (Blue 12 to 20), the rest 9 to 20; 7.5 where the seal
            // under the band above leaves less room
            double hMin = n.li == Terrain.BLUE ? 12 : c.court ? 9 : c.kind == Kind.GROTTO ? 10 : 9;
            if (sealCapped(t, n, c)) hMin = 7.5;
            double hMax = c.court ? (n.li == Terrain.BLUE ? 18 : 14) : 20;
            if (c.kind == Kind.LAVA) {
                if (across < 36 - 1e-6 || across > 48 + 1e-6) fails.add(id + " is " + fmt(across) + " across, not 36 to 48");
            } else if (c.kind == Kind.CITY) {
                continue;
            } else if (c.set) {
                if (across < 36 - 1e-6 || across > 60 + 1e-6) fails.add(id + " is " + fmt(across) + " across, not 36 to 60");
            } else if (c.kind == Kind.TUBEHUB) {
                if (c.h < 12 - 1e-6 || c.h > 14 + 1e-6) fails.add(id + " is " + fmt(c.h) + " tall, not 12 to 14");
            } else if (n.li == Terrain.BLUE) {
                if (across < 20 - 1e-6 || across > 32 + 1e-6) fails.add(id + " is " + fmt(across) + " across, not 20 to 32");
                if (c.h < hMin - 1e-6 || c.h > hMax + 1e-6) fails.add(id + " is " + fmt(c.h) + " tall, not " + hMin + " to " + hMax);
            } else {
                if (across < 15 - 1e-6 || across > 36 + 1e-6) fails.add(id + " is " + fmt(across) + " across, not 15 to 36");
                if (c.h < hMin - 1e-6 || c.h > hMax + 1e-6) fails.add(id + " is " + fmt(c.h) + " tall, not " + hMin + " to " + hMax);
            }
        }
    }

    static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }

    static void refuges(Terrain t, Net n, String tag, List<String> fails) {
        int count = 0;
        for (Chamber c : n.ch) {
            if (!c.keep || !c.refuge) continue;
            count++;
            List<Edge> es = new ArrayList<>();
            for (Edge e : n.edges) if ((e.a == c.id || e.b == c.id) && n.ch.get(e.a).keep && n.ch.get(e.b).keep) es.add(e);
            if (es.size() != 1) { fails.add(tag + ": refuge " + c.kind + "#" + c.id + " has " + es.size() + " edges"); continue; }
            Edge e = es.get(0);
            Chamber o = n.ch.get(e.other(c.id));
            if (e.kind != EK.WALK) fails.add(tag + ": refuge " + c.kind + "#" + c.id + " is joined by a " + e.kind);
            if (o.landing() || o.kind == Kind.PORTAL || o.court) fails.add(tag + ": refuge " + c.kind + "#" + c.id + " opens on " + o.kind + "#" + o.id);
        }
        if (n.li != Terrain.BLUE && count != 2) fails.add(tag + ": " + count + " refuges, not 2");
    }

    static void basaltFloors(Terrain t, Net n, String tag, List<String> fails) {
        if (n.li != Terrain.BASALT) return;
        double min = t.shape.waterY(n.li) + Terrain.SURGE_RISE + 2;
        for (Chamber c : n.ch) {
            if (!c.keep || c.kind == Kind.BEDROCK || c.kind == Kind.LAVA || n.courtsX.contains(c.id)) continue;
            if (c.kind == Kind.POCKET && c.parent == n.exit) continue; // a surge niche
            if (c.f0 < min - 1e-9) fails.add(tag + ": " + c.kind + "#" + c.id + " has its floor " + fmt(min - c.f0) + " under the surge line");
        }
    }

    /** Portal vestibules dropped: none outside the Blue Reaches, and never with a stub left behind. */
    static void dropped(Terrain t, Net n, String tag, List<String> fails) {
        for (int id : n.droppedPortals) if (portalStub(n, id) != null) fails.add(tag + ": dropped vestibule #" + id + " left its stub");
        if (n.li != Terrain.BLUE && !n.droppedPortals.isEmpty()) fails.add(tag + ": " + n.droppedPortals.size() + " portal vestibules dropped");
    }

    // ================================================================================================ graph tests
    @Test
    void everyNodeIsJoinedToTheEntry() { overPlans(NetworkTest::joinedToEntry); }

    @Test
    void twoOrThreeWaysToEachGate() { overPlans(NetworkTest::waysToGates); }

    @Test
    void sizesFollowTaran() { overPlans(NetworkTest::sizes); }

    @Test
    void refugesAreQuiet() { overPlans(NetworkTest::refuges); }

    @Test
    void basaltFloorsAboveTheSurge() { overPlans(NetworkTest::basaltFloors); }

    @Test
    void portalsMatchAcrossEdges() {
        List<String> fails = new ArrayList<>();
        int checked = 0;
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            Network w = t.network;
            for (int[] cell : CELLS) for (int li = 0; li < t.shape.layerCount; li++) for (int side = 0; side < 2; side++) {
                boolean xEdge = side == 0;
                int ex = xEdge ? cell[0] + 1 : cell[0], ez = xEdge ? cell[1] : cell[1] + 1;
                for (Network.Portal p : w.portalsOfEdge(li, xEdge, ex, ez)) {
                    checked++;
                    String tg = tag(seed, li, ex, ez) + (xEdge ? " west" : " north") + " edge portal at " + (int) p.along;
                    double[] ca = crossing(w.net(li, p.acx, p.acz), p, true, tg + " (first cell)", fails);
                    double[] cb = crossing(w.net(li, p.bcx, p.bcz), p, false, tg + " (second cell)", fails);
                    if (ca != null && cb != null && (Math.abs(ca[0] - cb[0]) > 0.01 || Math.abs(ca[1] - cb[1]) > 0.01 || Math.abs(ca[2] - cb[2]) > 0.01))
                        fails.add(tg + ": the stub halves meet at " + fmt(ca[0]) + "," + fmt(ca[1]) + " and " + fmt(cb[0]) + "," + fmt(cb[1]));
                }
            }
        }
        assertTrue(checked > 0);
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /** The point where a cell's copy of the portal's stub crosses the edge {x, z, floor}, or null when dropped. */
    static double[] crossing(Net n, Network.Portal p, boolean a, String tg, List<String> fails) {
        Chamber v = vestibule(n, a ? p.ax : p.bx, a ? p.az : p.bz);
        if (v == null) { fails.add(tg + ": no vestibule"); return null; }
        if (v.f0 != p.floor || v.rx != p.vestR || v.h != p.vestH) fails.add(tg + ": the vestibule is not the portal's");
        if (n.droppedPortals.contains(v.id)) return null;
        Way wy = portalStub(n, v.id);
        if (wy == null) { fails.add(tg + ": no stub"); return null; }
        Way s = p.stub;
        if (wy.n != s.n) { fails.add(tg + ": the stub is not the portal's"); return null; }
        for (int i = 0; i < s.n; i++)
            if (wy.x[i] != s.x[i] || wy.z[i] != s.z[i] || wy.f[i] != s.f[i] || wy.w[i] != s.w[i] || wy.hgt[i] != s.hgt[i]) { fails.add(tg + ": the stub is not the portal's"); return null; }
        // where the stub's line crosses the edge
        for (int i = 0; i + 1 < wy.n; i++) {
            double u0 = p.xEdge ? wy.x[i] : wy.z[i], u1 = p.xEdge ? wy.x[i + 1] : wy.z[i + 1];
            if ((u0 - p.line) * (u1 - p.line) > 0 || u0 == u1) continue;
            double q = (p.line - u0) / (u1 - u0);
            double x = wy.x[i] + (wy.x[i + 1] - wy.x[i]) * q, z = wy.z[i] + (wy.z[i + 1] - wy.z[i]) * q;
            return new double[]{x, z, wy.f[i] + (wy.f[i + 1] - wy.f[i]) * q};
        }
        fails.add(tg + ": the stub does not cross the edge");
        return null;
    }

    @Test
    void portalsAreJoined() {
        List<String> fails = new ArrayList<>();
        Terrain t = terrain(SEEDS[0]);
        Network w = t.network;
        int li = Terrain.BLUE, cells = 0, drops = 0, edges = 0;
        for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) {
            Net n = w.net(li, cx, cz);
            cells++;
            drops += n.droppedPortals.size();
            dropped(t, n, tag(SEEDS[0], li, cx, cz), fails);
        }
        for (int side = 0; side < 2; side++) for (int a = 1; a < 4; a++) for (int b = 0; b < 4; b++) {
            boolean xEdge = side == 0;
            int ex = xEdge ? a : b, ez = xEdge ? b : a;
            edges++;
            int working = 0;
            for (Network.Portal p : w.portalsOfEdge(li, xEdge, ex, ez)) {
                Net na = w.net(li, p.acx, p.acz), nb = w.net(li, p.bcx, p.bcz);
                Chamber va = vestibule(na, p.ax, p.az), vb = vestibule(nb, p.bx, p.bz);
                if (va != null && vb != null && va.keep && vb.keep && joined(na)[va.id] && joined(nb)[vb.id]) working++;
            }
            if (working == 0) fails.add("Blue Reaches " + (xEdge ? "west" : "north") + " edge of cell " + ex + "," + ez + ": no portal works");
        }
        assertEquals(24, edges);
        if (drops > cells) fails.add(drops + " portal vestibules dropped in " + cells + " cell-layers (at most one each on average)");
        // the other layers never drop one
        for (long seed : SEEDS) for (int[] cell : CELLS) for (int l = 1; l < t.shape.layerCount; l++)
            dropped(terrain(seed), terrain(seed).network.net(l, cell[0], cell[1]), tag(seed, l, cell[0], cell[1]), fails);
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /**
     * Every chamber, way and shaft the bucket index lists for a column's bucket and that reaches the column is in the
     * column's lists: the lists grow, nothing is skipped.
     */
    @Test
    void columnCachesNeverSkip() {
        Terrain t = terrain(SEEDS[0]);
        Network w = t.network;
        long growthsBefore = w.columnGrowths.sum();
        List<String> fails = new ArrayList<>();
        int columns = 0;
        for (int[] cell : CELLS) for (int li = 0; li < t.shape.layerCount; li++) {
            Net n = w.net(li, cell[0], cell[1]);
            int x0 = (int) (cell[0] * w.cell), z0 = (int) (cell[1] * w.cell);
            for (int x = x0; x < x0 + w.cell; x += 4) for (int z = z0; z < z0 + w.cell; z += 4) {
                columns++;
                Network.LCol lc = w.layer(w.column(x, z), li);
                int b = NetIndex.bucket(n, x, z);
                for (int id : n.bCh[b]) {
                    Chamber c = n.ch.get(id);
                    if (w.e(c, x, z) > 1.5) continue;
                    boolean in = false;
                    for (int k = 0; k < lc.nc; k++) if (lc.c[k] == c) in = true;
                    if (!in) fails.add(tag(SEEDS[0], li, cell[0], cell[1]) + ": column " + x + "," + z + " misses " + c.kind + "#" + c.id);
                }
                Map<Integer, Double> near = new HashMap<>();
                for (int k = 0; k < n.bWay[b].length; k++) {
                    Way wy = n.ways.get(n.bWay[b][k]);
                    int i = n.bWaySeg[b][k];
                    double d = segDist(x, z, wy.x[i], wy.z[i], wy.x[i + 1], wy.z[i + 1]);
                    near.merge(wy.id, d, Math::min);
                }
                for (Map.Entry<Integer, Double> e : near.entrySet()) {
                    Way wy = n.ways.get(e.getKey());
                    double reach = wy.inner ? 6.5 : Math.max(wy.w[0], wy.w[wy.n - 1]) * 1.3 + NetIndex.REACH;
                    if (e.getValue() > Math.max(reach, 9) - 0.01) continue;
                    double seen = 1e9;
                    for (int k = 0; k < lc.nw; k++) if (lc.w[k] == wy) seen = Math.min(seen, lc.wd[k]);
                    if (seen > 1e8) fails.add(tag(SEEDS[0], li, cell[0], cell[1]) + ": column " + x + "," + z + " misses way #" + wy.id);
                    else if (seen > e.getValue() + 0.01) fails.add(tag(SEEDS[0], li, cell[0], cell[1]) + ": column " + x + "," + z + " sees way #" + wy.id + " " + fmt(seen) + " away, not " + fmt(e.getValue()));
                }
                for (int id : n.bSh[b]) {
                    Shaft s = n.shafts.get(id);
                    if (Math.hypot(x - s.x, z - s.z) > s.r + 8) continue;
                    boolean in = false;
                    for (int k = 0; k < lc.ns; k++) if (lc.s[k] == s) in = true;
                    if (!in) fails.add(tag(SEEDS[0], li, cell[0], cell[1]) + ": column " + x + "," + z + " misses shaft #" + s.id);
                }
            }
        }
        System.out.println("columnCachesNeverSkip: " + columns + " columns, list growths " + (w.columnGrowths.sum() - growthsBefore));
        assertTrue(fails.isEmpty(), fails.size() + " misses:\n" + String.join("\n", fails.subList(0, Math.min(30, fails.size()))));
    }

    static double segDist(double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, len2 = dx * dx + dz * dz;
        double q = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len2));
        return Math.hypot(x - (ax + dx * q), z - (az + dz * q));
    }

    /**
     * The per-block pass only looks inside each column's fine span; outside it returns the interpolated value. That
     * is exact only if every term that can turn a block lies inside the span: compare with the pass computed in full
     * everywhere, at random points and at points near the network's chambers, ways and shafts, with random
     * interpolated values.
     */
    @Test
    void fineSpanCoversEveryTerm() {
        Terrain t = terrain(SEEDS[0]);
        Network w = t.network;
        List<String> fails = new ArrayList<>();
        try {
            for (int[] cell : CELLS) for (int li = 0; li < t.shape.layerCount; li++) {
                Net n = w.net(li, cell[0], cell[1]);
                Random r = new Random(Hashing.mix(SEEDS[0], li, cell[0], cell[1]));
                int bot = t.shape.bottom(li), top = t.shape.top(li);
                double x0 = cell[0] * w.cell, z0 = cell[1] * w.cell;
                for (int k = 0; k < 20000; k++) {
                    double x, z, y;
                    int pick = k % 4;
                    if (pick == 0 || n.ways.isEmpty()) {
                        x = x0 + r.nextDouble() * w.cell; z = z0 + r.nextDouble() * w.cell; y = bot + r.nextDouble() * (top - bot);
                    } else if (pick == 1) {
                        Way wy = n.ways.get(r.nextInt(n.ways.size()));
                        int i = r.nextInt(wy.n);
                        x = wy.x[i] + (r.nextDouble() * 2 - 1) * (wy.w[i] + 6);
                        z = wy.z[i] + (r.nextDouble() * 2 - 1) * (wy.w[i] + 6);
                        y = wy.f[i] - wy.slab[i] - 5 + r.nextDouble() * (wy.slab[i] + wy.hgt[i] + 14);
                    } else if (pick == 2 || n.shafts.isEmpty()) {
                        Chamber c = n.ch.get(r.nextInt(n.ch.size()));
                        x = c.x + (r.nextDouble() * 2 - 1) * (c.R() + 6);
                        z = c.z + (r.nextDouble() * 2 - 1) * (c.R() + 6);
                        y = c.yLo() - 14 + r.nextDouble() * (c.yHi() - c.yLo() + 28);
                    } else {
                        Shaft s = n.shafts.get(r.nextInt(n.shafts.size()));
                        x = s.x + (r.nextDouble() * 2 - 1) * (s.r + 9);
                        z = s.z + (r.nextDouble() * 2 - 1) * (s.r + 9);
                        y = s.y0 - 4 + r.nextDouble() * (s.y1 - s.y0 + 8);
                    }
                    int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
                    if (by < bot || by > top) continue;
                    if (t.shape.layerAt(by) != li) continue;
                    double coarse = r.nextDouble() * 2 - 1;
                    w.fullFine = false;
                    double a = w.fine(li, bx, by, bz, coarse);
                    w.fullFine = true;
                    double b = w.fine(li, bx, by, bz, coarse);
                    if (Math.abs(a - b) > 1e-9 && fails.size() < 30)
                        fails.add(tag(SEEDS[0], li, cell[0], cell[1]) + ": at " + bx + " " + by + " " + bz + " the pass gives " + a + " with the span and " + b + " in full");
                }
            }
        } finally {
            w.fullFine = false;
        }
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /** Graph tests 1, 2, 5, 6, 7 and 9 over 120 cell-layers (40 per layer) on 10 seeds the fast tests never use. */
    @Test
    @Tag("slow")
    void manyCells() {
        List<String> fails = new ArrayList<>();
        int[] drops = new int[3], cells = new int[3];
        for (int si = 0; si < 10; si++) {
            long seed = 1000003L * (si + 1) + 17;
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            for (int li = 0; li < t.shape.layerCount; li++) for (int c = 0; c < 4; c++) {
                int cx = (int) Math.floorMod(Hashing.mix(seed, c, 11, 3), 21) - 10, cz = (int) Math.floorMod(Hashing.mix(seed, c, 12, 3), 21) - 10;
                Net n = t.network.net(li, cx, cz);
                String tg = tag(seed, li, cx, cz);
                joinedToEntry(t, n, tg, fails);
                waysToGates(t, n, tg, fails);
                sizes(t, n, tg, fails);
                refuges(t, n, tg, fails);
                basaltFloors(t, n, tg, fails);
                dropped(t, n, tg, fails);
                drops[li] += n.droppedPortals.size();
                cells[li]++;
            }
        }
        if (drops[Terrain.BLUE] > cells[Terrain.BLUE]) fails.add(drops[Terrain.BLUE] + " Blue Reaches portal vestibules dropped in " + cells[Terrain.BLUE] + " cell-layers");
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails.subList(0, Math.min(40, fails.size()))));
    }

    // ================================================================================================ voxel tests
    /** Built cell-layers that more than one test reads (the four of the Deep Gardens and Basalt Galleries tests). */
    private static final Map<String, VoxelHarness> BUILT = new HashMap<>();

    /**
     * One or more cells of a layer built as the game builds them, the plan's climbs marked, the Deep Blue Rift's shelf
     * vines too, and joined from the first cell's entry landing. An apron of 64 blocks round the cells (the deep dark
     * raster's) keeps the Blue Reaches' caverns that run across a seam joined as they are in the world.
     */
    static synchronized VoxelHarness built(long seed, int li, int cx, int cz, int wc, int hc) {
        String key = seed + "/" + li + "/" + cx + "/" + cz + "/" + wc + "/" + hc;
        VoxelHarness v = BUILT.get(key);
        if (v != null) return v;
        boolean keep = wc == 1 && hc == 1 && li != Terrain.BLUE;
        Terrain t = terrain(seed);
        Network w = t.network;
        int cell = (int) w.cell, ap = li == Terrain.BLUE ? NetDark.APRON : 0;
        v = VoxelHarness.build(t, li, cx * cell - ap, cz * cell - ap, wc * cell + 2 * ap, hc * cell + 2 * ap);
        for (int i = ap > 0 ? -1 : 0; i < wc + (ap > 0 ? 1 : 0); i++) for (int k = ap > 0 ? -1 : 0; k < hc + (ap > 0 ? 1 : 0); k++)
            v.markShafts(w.net(li, cx + i, cz + k));
        if (li == Terrain.BLUE) v.markLipVines(DeepBiome.DEEP_BLUE_RIFT);
        Net n = w.net(li, cx, cz);
        Chamber e = n.ch.get(n.entry);
        v.connect(e.x, e.f0 + 1, e.z);
        if (keep) BUILT.put(key, v);
        return v;
    }

    /** The cell-layers of the Deep Gardens and Basalt Galleries voxel tests. */
    static final long[] VOX_SEEDS = {8675309L, 2000023L};
    static final int[][] VOX_CELLS = {{0, -3}, {-1, 1}};

    /** True for a way's point next to a landing, where the gate's own passage and landing shape the floor. */
    static boolean gateMouth(Terrain t, Net n, double x, double z) {
        Plans.Gate en = t.plans.gate(n.li + 1, n.cx, n.cz);
        if (Math.hypot(x - en.lx, z - en.lz) < en.landR + 6) return true;
        if (n.li + 2 <= t.shape.layerCount) {
            Plans.Gate ex = t.plans.gate(n.li + 2, n.cx, n.cz);
            return Math.hypot(x - ex.ux, z - ex.uz) < ex.landR + 6;
        }
        return false;
    }

    /** Every non-pocket chamber reached, the exit landing reached, every walk way walkable along its line. */
    static void reachAll(VoxelHarness v, Terrain t, Net n, String tg, List<String> fails) {
        for (Chamber c : n.ch) {
            if (!c.keep || c.kind == Kind.CANYON || c.kind == Kind.LAVA || c.kind == Kind.POCKET) continue;
            if (v.reach(c) == null) fails.add(tg + ": " + c.kind + "#" + c.id + " at " + (int) c.x + " " + (int) c.f0 + " " + (int) c.z + " not reached");
        }
        for (Edge e : n.edges) {
            if (e.kind != EK.WALK || e.w1 == null) continue;
            Way wy = e.w1;
            for (int i = 0; i < wy.n; i += 2) {
                if (v.nearestPlace(wy.x[i], wy.f[i] + 1, wy.z[i], 2, 3, true) != null || gateMouth(t, n, wy.x[i], wy.z[i])) continue;
                fails.add(tg + ": way #" + wy.id + " (" + n.ch.get(e.a).kind + " to " + n.ch.get(e.b).kind + ") cannot be walked at " + (int) wy.x[i] + " " + (int) wy.f[i] + " " + (int) wy.z[i]);
                break;
            }
        }
    }

    static void oneCave(int li, double minShare) {
        List<String> fails = new ArrayList<>();
        for (int s = 0; s < VOX_SEEDS.length; s++) {
            long seed = VOX_SEEDS[s];
            int cx = VOX_CELLS[s][0], cz = VOX_CELLS[s][1];
            Terrain t = terrain(seed);
            Net n = t.network.net(li, cx, cz);
            VoxelHarness v = built(seed, li, cx, cz, 1, 1);
            String tg = tag(seed, li, cx, cz);
            System.out.printf("%s: main component %.2f%% of %d places, %d components%n", tg, 100 * v.mainShare(), v.places(), v.components());
            if (v.mainShare() < minShare) fails.add(tg + ": the main component holds " + fmt(100 * v.mainShare()) + "% of the standing places");
            reachAll(v, t, n, tg, fails);
        }
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    @Test
    @Tag("voxel")
    void gardensAreOneCave() { oneCave(Terrain.GARDENS, 0.980); }

    @Test
    @Tag("voxel")
    void basaltIsOneCave() { oneCave(Terrain.BASALT, 0.960); }

    /** Under the highest surge, every place is a short walk from one above it. */
    @Test
    @Tag("voxel")
    void basaltSurgeHasAWayOut() {
        List<String> fails = new ArrayList<>();
        for (int s = 0; s < VOX_SEEDS.length; s++) {
            Terrain t = terrain(VOX_SEEDS[s]);
            VoxelHarness v = built(VOX_SEEDS[s], Terrain.BASALT, VOX_CELLS[s][0], VOX_CELLS[s][1], 1, 1);
            int W = (int) Math.round(t.shape.waterY(Terrain.BASALT));
            int[] h = v.surge(W + Terrain.SURGE_RISE + 1);
            int under = h[6];
            double within64 = (h[0] + h[1] + h[2] + h[3]) / (double) Math.max(1, under);
            String tg = tag(VOX_SEEDS[s], Terrain.BASALT, VOX_CELLS[s][0], VOX_CELLS[s][1]);
            System.out.printf("%s: %d places under the surge, %.1f%% within 64 of safety, longest %d%n", tg, under, 100 * within64, h[7]);
            if (under == 0) fails.add(tg + ": nothing under the surge (the bedrock landing is missing?)");
            if (within64 < 0.95) fails.add(tg + ": only " + fmt(100 * within64) + "% of the places under the surge are within 64 of safety");
            if (h[5] > 0) fails.add(tg + ": " + h[5] + " places under the surge have no way out");
            if (h[7] > 96) fails.add(tg + ": the longest walk out from under the surge is " + h[7]);
        }
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /** The share of the standing places in the main component, every node and every portal of 2 x 2 cells reached. */
    @Test
    @Tag("voxel")
    void blueReachesAreOneCave() {
        long seed = SEEDS[0];
        int cx = 0, cz = -3;
        Terrain t = terrain(seed);
        Network w = t.network;
        VoxelHarness v = built(seed, Terrain.BLUE, cx, cz, 2, 2);
        int cell = (int) w.cell;
        long[] in = v.placesIn(cx * cell, cz * cell, (cx + 2) * cell, (cz + 2) * cell);
        double share = in[0] / (double) in[1];
        System.out.printf("Blue Reaches 2 x 2 cells: main component %.2f%% of %d places%n", 100 * share, in[1]);
        List<String> fails = new ArrayList<>();
        // measured 94.90% (rift shelf vines only, as the harness models them in this step): 1.5 points of margin
        if (share < 0.934) fails.add("the main component holds " + fmt(100 * share) + "% of the standing places");
        for (int i = 0; i < 2; i++) for (int k = 0; k < 2; k++) {
            Net n = w.net(Terrain.BLUE, cx + i, cz + k);
            String tg = tag(seed, Terrain.BLUE, cx + i, cz + k);
            for (Chamber c : n.ch) {
                if (!c.keep || c.kind == Kind.CANYON) continue;
                if (v.reach(c) == null) fails.add(tg + ": " + c.kind + "#" + c.id + " at " + (int) c.x + " " + (int) c.f0 + " " + (int) c.z + " not reached");
            }
        }
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /** Two neighbouring cells per layer: the second's entry landing is reached from the first's, every shared portal joined. */
    @Test
    @Tag("voxel")
    void neighboursJoin() {
        long seed = SEEDS[0];
        int cx = 0, cz = -3;
        Terrain t = terrain(seed);
        Network w = t.network;
        List<String> fails = new ArrayList<>();
        for (int li = 0; li < t.shape.layerCount; li++) {
            VoxelHarness v = built(seed, li, cx, cz, 2, 1);
            Net b = w.net(li, cx + 1, cz);
            Chamber eb = b.ch.get(b.entry);
            String tg = tag(seed, li, cx, cz) + " and " + (cx + 1) + "," + cz;
            if (v.nearestPlace(eb.x, eb.f0 + 1, eb.z, 6, 4, true) == null) fails.add(tg + ": the second cell's entry landing is not reached from the first's");
            int shared = 0, joinedP = 0;
            for (Network.Portal p : w.portalsOfEdge(li, true, cx + 1, cz)) {
                shared++;
                int[] pa = v.nearestPlace(p.ax, p.floor + 1, p.az, 5, 4, true), pb = v.nearestPlace(p.bx, p.floor + 1, p.bz, 5, 4, true);
                int[] mid = v.nearestPlace(p.line, p.floor + 1, p.along, 3, 3, true);
                if (pa != null && pb != null && mid != null) joinedP++;
                else fails.add(tg + ": the portal at " + (int) p.along + " is not joined");
            }
            System.out.printf("%s: shared portals joined %d/%d%n", tg, joinedP, shared);
            if (shared < 2) fails.add(tg + ": only " + shared + " portals on the shared edge");
        }
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /** Rooms keep most of their planned interior open after interpolation, and every tube hub's centre pillar stands. */
    @Test
    @Tag("voxel")
    void roomsStayOpen() {
        List<String> fails = new ArrayList<>();
        Map<Kind, List<Double>> share = new EnumMap<>(Kind.class);
        int hubs = 0;
        for (int li = Terrain.GARDENS; li <= Terrain.BASALT; li++) for (int s = 0; s < VOX_SEEDS.length; s++) {
            long seed = VOX_SEEDS[s];
            int cx = VOX_CELLS[s][0], cz = VOX_CELLS[s][1];
            Terrain t = terrain(seed);
            Network w = t.network;
            Net n = w.net(li, cx, cz);
            VoxelHarness v = built(seed, li, cx, cz, 1, 1);
            for (Chamber c : n.ch) {
                if (!c.keep || c.helix() || c.landing() || c.kind == Kind.LAKE || c.kind == Kind.LAVA) continue;
                long[] o = v.openShare(w, c);
                if (o[1] < 50) continue;
                share.computeIfAbsent(c.kind, k -> new ArrayList<>()).add(o[0] / (double) o[1]);
                if (c.kind != Kind.TUBEHUB || c.pillars.length < 3) continue;
                hubs++;
                int px = (int) Math.floor(c.x + c.pillars[0]), pz = (int) Math.floor(c.z + c.pillars[1]);
                Network.LCol lc = w.layer(w.column(px, pz), li);
                for (int k = 0; k < lc.nc; k++) {
                    if (lc.c[k] != c) continue;
                    for (int y = (int) Math.ceil(lc.F[k] + 1); y <= Math.floor(lc.C[k] - 1); y++)
                        if (v.mat(px, y, pz) != VoxelHarness.ROCK) { fails.add(tag(seed, li, cx, cz) + ": the pillar of tube hub #" + c.id + " is open at " + px + " " + y + " " + pz); break; }
                }
            }
        }
        for (Map.Entry<Kind, List<Double>> e : share.entrySet()) {
            List<Double> l = e.getValue();
            Collections.sort(l);
            double p10 = l.get((int) (0.1 * l.size()));
            double min = switch (e.getKey()) {
                case TUBEHUB -> 0.60;
                case POCKET -> 0.50;
                default -> 0.70;
            };
            System.out.printf("roomsStayOpen: %s n %d open share p10 %.2f%n", e.getKey(), l.size(), p10);
            if (p10 < min) fails.add(e.getKey() + ": open share p10 " + fmt(p10) + " (n " + l.size() + "), under " + min);
        }
        if (hubs == 0) fails.add("no tube hub to check");
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /** Lava fills the bottom of the Basalt Galleries round the bedrock landing, never above the table, with a way out. */
    @Test
    @Tag("voxel")
    void lavaAtTheBottom() {
        List<String> fails = new ArrayList<>();
        for (int s = 0; s < VOX_SEEDS.length; s++) {
            long seed = VOX_SEEDS[s];
            int cx = VOX_CELLS[s][0], cz = VOX_CELLS[s][1];
            Terrain t = terrain(seed);
            Net n = t.network.net(Terrain.BASALT, cx, cz);
            VoxelHarness v = built(seed, Terrain.BASALT, cx, cz, 1, 1);
            Chamber bed = n.ch.get(n.exit);
            int W = (int) Math.round(t.shape.waterY(Terrain.BASALT));
            long lava = 0, high = 0, steps = 0;
            for (int x = v.x0; x < v.x0 + v.nx; x++) for (int z = v.z0; z < v.z0 + v.nz; z++) {
                double d = Math.hypot(x + 0.5 - bed.x, z + 0.5 - bed.z);
                for (int y = v.y0; y < v.y0 + v.ny; y++) {
                    if (v.mat(x, y, z) != VoxelHarness.LAVA) continue;
                    if (y >= W) high++;
                    if (d <= 70) lava++;
                    if (y == W - 1 && v.mat(x, y + 1, z) == VoxelHarness.AIR) {
                        for (int k = 0; k < 4; k++) if (v.inMain(x + VoxelHarness.DX[k], W, z + VoxelHarness.DZ[k])) { steps++; break; }
                    }
                }
            }
            String tg = tag(seed, Terrain.BASALT, cx, cz);
            System.out.printf("%s: %d lava blocks within 70 of the bedrock landing, %d at or above the table, %d step-outs%n", tg, lava, high, steps);
            if (lava < 1000) fails.add(tg + ": only " + lava + " lava blocks within 70 of the bedrock landing");
            if (high > 0) fails.add(tg + ": " + high + " lava blocks at or above the lava table");
            if (steps == 0) fails.add(tg + ": nowhere to step out of the lava onto the landing's side");
            if (v.nearestPlace(bed.x, bed.f0 + 1, bed.z, 6, 3, true) == null) fails.add(tg + ": the bedrock landing is not reached");
        }
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }
}
