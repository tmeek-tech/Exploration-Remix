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
    void climbsAreStaged() { overPlans(NetworkTest::stagedClimbs); }

    @Test
    void tubesAndArches() { overPlans(NetworkTest::tubesArches); }

    @Test
    void queriesSeeEveryWayAndClimb() { overPlans(NetworkTest::queries); }

    /**
     * Section 12.1: wayLinesAt and carvedLinesAt find every point of every way that lies in the plan's own cell, on that
     * way's floor and half-width there (so Walkway protects and the open way pass re-opens every way), and shaftsIn
     * finds every climb stage from its axis, its vine column and its step. (A switchback that comes back past a point
     * within 4 blocks along the way is one pass, as in the density's column lookup, and its half-width there may be
     * that of the nearest stretch, up to a few tenths off.)
     */
    static void queries(Terrain t, Net n, String tag, List<String> fails) {
        Network w = t.network;
        int missed = 0, total = 0;
        String first = null;
        for (Way wy : n.ways) for (int i = 0; i < wy.n; i++) {
            if (w.cellOf(wy.x[i]) != n.cx || w.cellOf(wy.z[i]) != n.cz) continue;
            for (int pass = 0; pass < 2; pass++) {
                total++;
                Network.WayLines l = pass == 0 ? w.wayLinesAt(n.li, wy.x[i], wy.z[i], 0.5) : w.carvedLinesAt(n.li, wy.x[i], wy.z[i], 0.5);
                boolean ok = false;
                for (int k = 0; k < l.n && !ok; k++)
                    ok = l.d[k] < 1e-6 && Math.abs(l.floor[k] - wy.f[i]) < 1e-6 && Math.abs(l.half[k] - wy.w[i]) < 0.25;
                if (!ok) {
                    missed++;
                    if (first == null) first = String.format(java.util.Locale.ROOT, "way %d (%s) point %d of %d at %.1f, %.1f floor %.2f (pass %d)",
                            wy.id, wy.kind, i, wy.n, wy.x[i], wy.z[i], wy.f[i], pass);
                }
            }
        }
        if (missed > 0) fails.add(tag + ": " + missed + " of " + total + " way points not found, first " + first);
        for (Shaft s : n.shafts) {
            int x = (int) Math.floor(s.x), z = (int) Math.floor(s.z);
            if (!w.shaftsIn(n.li, x, z, x, z).contains(s)) fails.add(tag + ": climb stage " + s.id + " not found at its axis");
            if (!w.shaftsIn(n.li, s.vineX, s.vineZ, s.vineX, s.vineZ).contains(s)) fails.add(tag + ": climb stage " + s.id + " not found at its vines");
            if (s.stepX != Integer.MIN_VALUE && !w.shaftsIn(n.li, s.stepX, s.stepZ, s.stepX, s.stepZ).contains(s))
                fails.add(tag + ": climb stage " + s.id + " not found at its step");
        }
    }

    /**
     * Section 10: every climb is at most 4 stages of at most 12.5, consecutive axes 2 r + 3 to 2 r + 5 apart, each next
     * stage's foot on the floor of the ledge passage from the one below, the ledge level at that stage's top, the
     * upper tunnel at the last top, and every vine column inside its shaft's open core.
     */
    static void stagedClimbs(Terrain t, Net n, String tag, List<String> fails) {
        for (Edge e : n.edges) {
            if (e.kind != EK.CLIMB) continue;
            String id = tag + ": climb " + n.ch.get(e.a).kind + "#" + e.a + " to " + n.ch.get(e.b).kind + "#" + e.b;
            Shaft[] st = e.stages;
            if (st.length < 1 || st.length > 4 || e.ledges.length != st.length - 1 || e.sh != st[0]) { fails.add(id + " has " + st.length + " stages and " + e.ledges.length + " ledges"); continue; }
            for (int k = 0; k < st.length; k++) {
                Shaft s = st[k];
                if (s.top - s.foot > 12.5 + 1e-9) fails.add(id + ": stage " + (k + 1) + " is " + fmt(s.top - s.foot) + " tall");
                if (s.stage != k + 1 || s.stages != st.length) fails.add(id + ": stage " + (k + 1) + " is numbered " + s.stage + " of " + s.stages);
                if (!n.shafts.contains(s)) fails.add(id + ": stage " + (k + 1) + " is not in the plan");
                int[] v = Network.vineColumn(s);
                if (Math.hypot(v[0] - s.x, v[1] - s.z) >= s.r - 0.6) fails.add(id + ": stage " + (k + 1) + "'s vines are outside its open core");
                if (k > 0) {
                    Shaft p = st[k - 1];
                    double d = Math.hypot(s.x - p.x, s.z - p.z);
                    if (d < 2 * s.r + 3 - 1e-6 || d > 2 * s.r + 5 + 1e-6) fails.add(id + ": stages " + k + " and " + (k + 1) + " stand " + fmt(d) + " apart");
                    if (Math.abs(s.foot - p.top) > 1e-9) fails.add(id + ": stage " + (k + 1) + " starts " + fmt(s.foot - p.top) + " off the top of the one below");
                    Way lg = e.ledges[k - 1];
                    if (!n.ways.contains(lg) || !lg.ledge) fails.add(id + ": ledge " + k + " is missing");
                    for (int i = 0; i < lg.n; i++) if (Math.abs(lg.f[i] - p.top) > 1e-9) { fails.add(id + ": ledge " + k + " is not level at the top of stage " + k); break; }
                }
            }
            if (Math.abs(e.w2.f[0] - st[st.length - 1].top) > 1e-9) fails.add(id + ": the upper tunnel starts " + fmt(e.w2.f[0] - st[st.length - 1].top) + " off the last stage's top");
            if (Math.abs(e.w1.f[e.w1.n - 1] - st[0].foot) > 1e-9) fails.add(id + ": the lower tunnel ends " + fmt(e.w1.f[e.w1.n - 1] - st[0].foot) + " off the first stage's foot");
        }
    }

    /**
     * Section 5: every tube hub's tunnel is a round tube, with two or three blind round tubes beside it; arches only in
     * the Basalt Galleries, in every great hall, over W + 38, their crown at least 8 over the floor under it.
     */
    static void tubesArches(Terrain t, Net n, String tag, List<String> fails) {
        for (Chamber c : n.ch) {
            if (!c.keep || c.kind != Kind.TUBEHUB) continue;
            int tunnels = 0, round = 0, blind = 0;
            for (Way wy : n.ways) {
                if (wy.inner || (wy.a != c.id && wy.b != c.id)) continue;
                if (wy.kind == EK.STUB) { if (wy.round && wy.taper) blind++; continue; }
                tunnels++;
                if (wy.round) round++;
            }
            if (tunnels != 1 || round != 1) fails.add(tag + ": tube hub #" + c.id + " has " + tunnels + " tunnels, " + round + " round");
            if (blind < 2 || blind > 3) fails.add(tag + ": tube hub #" + c.id + " has " + blind + " blind tubes");
        }
        if (n.li != Terrain.BASALT && !n.arches.isEmpty()) fails.add(tag + ": " + n.arches.size() + " arches outside the Basalt Galleries");
        double W = t.shape.waterY(n.li);
        for (Net.Arch a : n.arches) {
            Chamber c = n.ch.get(a.chamber);
            if (c.kind != Kind.GREAT && c.kind != Kind.GALLERY) fails.add(tag + ": an arch in " + c.kind + "#" + c.id);
            if (a.deck < W + 38 - 1e-9) fails.add(tag + ": the arch in " + c.kind + "#" + c.id + " is " + fmt(W + 38 - a.deck) + " under W + 38");
            double f = t.network.floorAt(c, t.network.e(c, a.x, a.z), a.x, a.z);
            if (a.deck < f + 8 - 1e-9) fails.add(tag + ": the arch in " + c.kind + "#" + c.id + " is " + fmt(a.deck - f) + " over the floor");
        }
        for (Chamber c : n.ch) {
            if (!c.keep || c.kind != Kind.GREAT || n.li != Terrain.BASALT) continue;
            boolean has = false;
            for (Net.Arch a : n.arches) if (a.chamber == c.id) has = true;
            if (!has) fails.add(tag + ": great hall #" + c.id + " has no arch");
        }
    }

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
     * Section 11.1, over 3 seeds x 4 x 4 Blue cells: every eligible city site is at least 150 inside its cell and 140
     * from both landings; for every placed city, no chamber but the city (and the deep dark caverns, where the city
     * sits as in vanilla) and no tunnel or climb but the city's own enters its reserve (radius 128, W + 8 to W + 80),
     * no portal vestibule or stub enters it, and the rift and its geodes stay 128 plus their half-width away.
     */
    @Test
    void citySitesAreClear() {
        List<String> fails = new ArrayList<>();
        int sites = 0, eligible = 0, cities = 0;
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            Network w = t.network;
            int li = Terrain.BLUE;
            double W = t.shape.waterY(li), lo = W + Network.CITY_RESERVE_LO, hi = W + Network.CITY_RESERVE_HI, R = Network.CITY_RESERVE;
            for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) {
                String tg = tag(seed, li, cx, cz);
                sites += w.citySites(cx, cz).size();
                Plans.Gate in = t.plans.gate(li + 1, cx, cz), down = t.plans.gate(li + 2, cx, cz);
                double x0 = cx * w.cell, z0 = cz * w.cell;
                for (double[] s : w.eligibleCities(cx, cz)) {
                    eligible++;
                    double inside = Math.min(Math.min(s[0] - x0, x0 + w.cell - s[0]), Math.min(s[1] - z0, z0 + w.cell - s[1]));
                    if (inside < 150) fails.add(tg + ": eligible site " + (int) s[0] + "," + (int) s[1] + " only " + (int) inside + " inside");
                    if (Math.hypot(s[0] - in.lx, s[1] - in.lz) < 140 || Math.hypot(s[0] - down.ux, s[1] - down.uz) < 140)
                        fails.add(tg + ": eligible site " + (int) s[0] + "," + (int) s[1] + " within 140 of a landing");
                }
                Net n = w.net(li, cx, cz);
                for (Chamber city : n.ch) {
                    if (!city.keep || city.kind != Kind.CITY) continue;
                    cities++;
                    String ct = tg + " city at " + (int) city.x + "," + (int) city.z;
                    boolean placed = false;
                    for (double[] s : w.eligibleCities(cx, cz)) if (Math.abs(s[0] - city.x) < 1e-9 && Math.abs(s[1] - city.z) < 1e-9) placed = true;
                    if (!placed) fails.add(ct + ": not at an eligible site");
                    for (Chamber c : n.ch) {
                        // the rift's own nodes are checked with the rift, below
                        if (!c.keep || c == city || c.dark >= 0 || c.kind == Kind.CANYON) continue;
                        if (c.yHi() < lo || c.yLo() > hi || Math.hypot(c.x - city.x, c.z - city.z) >= R + c.R()) continue;
                        fails.add(ct + ": " + c.kind + " " + c.id + " in the reserve, " + (int) Math.hypot(c.x - city.x, c.z - city.z) + " from it");
                    }
                    // the city's own tunnels and climbs: the ways and stages of its edges
                    java.util.Set<Object> own = new java.util.HashSet<>();
                    for (Edge e : n.edges) {
                        if (e.a != city.id && e.b != city.id) continue;
                        if (e.w1 != null) own.add(e.w1);
                        if (e.w2 != null) own.add(e.w2);
                        own.addAll(List.of(e.ledges));
                        own.addAll(List.of(e.stages));
                    }
                    for (Way wy : n.ways) {
                        if (own.contains(wy) || wy.a == city.id || wy.b == city.id) continue;
                        if (wy.inner && wy.a >= 0 && n.ch.get(wy.a).dark >= 0) continue; // a deep dark cavern's own walk
                        for (int i = 0; i < wy.n; i++) {
                            if (wy.f[i] + wy.hgt[i] < lo || wy.f[i] - wy.slab[i] > hi) continue;
                            if (Math.hypot(wy.x[i] - city.x, wy.z[i] - city.z) >= R + wy.w[i]) continue;
                            fails.add(ct + ": way " + wy.id + " (" + wy.kind + ", " + wy.a + "-" + wy.b + ") enters the reserve");
                            break;
                        }
                    }
                    for (Shaft s : n.shafts) {
                        if (own.contains(s) || s.y1 < lo || s.y0 > hi) continue;
                        if (Math.hypot(s.x - city.x, s.z - city.z) < R + s.r + 2) fails.add(ct + ": climb stage " + s.id + " in the reserve");
                    }
                    for (Route r : n.canyons) {
                        for (double s = 0; s < r.length; s += 3) {
                            double[] p = r.pointAt(s);
                            if (Math.hypot(p[0] - city.x, p[1] - city.z) < r.at(r.riftHalf, s) + R) { fails.add(ct + ": the rift passes within 128 + its half-width"); break; }
                        }
                        for (Route.Geode g : r.geodes)
                            if (Math.hypot(g.x() - city.x, g.z() - city.z) < g.r() + R) fails.add(ct + ": a rift geode within 128 + its radius");
                    }
                }
            }
        }
        System.out.printf("citySitesAreClear: %d sites, %d eligible, %d cities in %d cells%n", sites, eligible, cities, SEEDS.length * 16);
        if (cities == 0) fails.add("no city placed in " + SEEDS.length * 16 + " cells");
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails.subList(0, Math.min(40, fails.size()))));
    }

    /**
     * Section 6.3, over 2 seeds x 3 x 3 Blue cells: along every seam between them, every 2 blocks, both tiers, the deep
     * dark carved at the last column of one cell and at the first column of the next is the raw tier field's span there
     * (within 16 blocks of a seam both cells carve the raw field, whatever their plans kept), and where the raw field is
     * open on both sides, neither side is open by more than 6 while the other is closed (a wall across a cavern).
     */
    @Test
    void deepDarkAgreesAcrossSeams() {
        List<String> fails = new ArrayList<>();
        int samples = 0, open = 0, walls = 0, off = 0;
        double[] t2 = new double[2];
        for (long seed : new long[]{SEEDS[0], SEEDS[1]}) {
            Terrain t = terrain(seed);
            Network w = t.network;
            int cell = (int) Math.round(w.cell);
            for (int side = 0; side < 2; side++) for (int a = 1; a < 3; a++) for (int along = 1; along < 3 * cell; along += 2) {
                boolean xEdge = side == 0;
                int line = a * cell;
                double[] h = new double[2];
                boolean rawBoth = true;
                for (int k = 0; k < 2; k++) {
                    for (int sd = 0; sd < 2; sd++) {
                        int x = xEdge ? line - 1 + sd : along, z = xEdge ? along : line - 1 + sd;
                        Network.LCol lc = w.layer(w.column(x, z), Terrain.BLUE);
                        double F = k == 0 ? lc.aF : lc.bF, C = k == 0 ? lc.aC : lc.bC;
                        double rF = 0, rC = 0;
                        double o = w.dark.open(k, x, z);
                        if (o > 0 && lc.net.dark != null) {
                            o *= NetDark.fade(lc.net.dark.geodes, x, z);
                            if (o > 0) {
                                w.dark.tier(o, w.dark.floor0(k, x, z), w.dark.ceil0(k, x, z), t2);
                                rF = t2[0];
                                rC = t2[1];
                            }
                        }
                        boolean carvedOpen = C > F, rawOpen = rC > rF;
                        samples++;
                        if (rawOpen) open++;
                        if (carvedOpen != rawOpen || (carvedOpen && (Math.abs(F - rF) > 1e-6 || Math.abs(C - rC) > 1e-6))) {
                            off++;
                            if (off <= 10) fails.add(String.format(java.util.Locale.ROOT, "seed %d tier %d at %d %d: carved %s, raw %s",
                                    seed, k, x, z, carvedOpen ? String.format(java.util.Locale.ROOT, "%.1f..%.1f", F, C) : "closed",
                                    rawOpen ? String.format(java.util.Locale.ROOT, "%.1f..%.1f", rF, rC) : "closed"));
                        }
                        h[sd] = carvedOpen ? C - F : 0;
                        rawBoth &= rawOpen;
                    }
                    if (rawBoth && ((h[0] > 6 && h[1] <= 0) || (h[1] > 6 && h[0] <= 0))) walls++;
                    rawBoth = true;
                }
            }
        }
        System.out.printf("deepDarkAgreesAcrossSeams: %d samples, %d open in the raw field, %d off the raw field, %d walls%n", samples, open, off, walls);
        if (open == 0) fails.add("no open deep dark on any seam");
        if (walls > 0) fails.add(walls + " walls across a cavern on a seam");
        assertTrue(fails.isEmpty() && off == 0, off + " samples off the raw field:\n" + String.join("\n", fails));
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
                    } else if (pick == 3 && !n.arches.isEmpty() && (k & 4) != 0) {
                        Net.Arch a = n.arches.get(r.nextInt(n.arches.size()));
                        double v = (r.nextDouble() * 2 - 1) * a.span, u = (r.nextDouble() * 2 - 1) * (a.halfAlong * 2.2 + 3);
                        x = a.x + a.vx * v + a.ux * u;
                        z = a.z + a.vz * v + a.uz * u;
                        y = a.deck - 18 + r.nextDouble() * 22;
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

    /**
     * The rock palette reads the plan through three queries (sections 5, 6 and 9): where the rock meets a tube hub or a
     * round tube it is lined with obsidian (shell depth under 1.8), where it meets a geode it takes the geode's layers
     * (under 1.7), and the bedrock landing's plate is bedrock.
     */
    @Test
    void paletteQueriesFollowThePlan() {
        List<String> fails = new ArrayList<>();
        int hubFaces = 0, tubeFaces = 0, geodeFaces = 0, landings = 0;
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int[] cell : CELLS) {
                Net nb = t.network.net(Terrain.BASALT, cell[0], cell[1]);
                String tg = tag(seed, Terrain.BASALT, cell[0], cell[1]);
                for (Chamber c : nb.ch) {
                    if (!c.keep || c.kind != Kind.TUBEHUB) continue;
                    double r0 = c.pillars.length >= 3 ? c.pillars[2] + 2 : 1;
                    // rays out from the pillar at the floor, the dome's middle and high in it; a ray that leaves the
                    // footprint in the open has gone out through a door and is not a face of the hub
                    for (double fy : new double[]{2, c.h * 0.4, c.h * 0.7}) {
                        int y = (int) Math.floor(c.f0 + fy);
                        for (int a = 0; a < 16; a++) {
                            double ang = a * Math.PI / 8 + 0.1;
                            // the open block before the rock must be inside the hub's own space: otherwise the ray
                            // has gone out through a door, and the rock is a tunnel's
                            boolean open = false, inHub = false;
                            for (double r = r0; r < c.R() + 6; r += 0.5) {
                                int x = (int) Math.floor(c.x + Math.cos(ang) * r), z = (int) Math.floor(c.z + Math.sin(ang) * r);
                                if (t.rawDensity(x, y, z) <= 0) {
                                    open = true;
                                    inHub = t.tubeShellDepth(x, y, z) < 0;
                                    continue;
                                }
                                if (!open) continue;
                                if (!inHub) break;
                                hubFaces++;
                                double g = t.tubeShellDepth(x, y, z);
                                DeepBiome b = t.biomeAt(x, y, z);
                                if (g >= 1.8 || b != DeepBiome.OBSIDIAN_TUBES) {
                                    fails.add(tg + ": tube hub #" + c.id + " meets rock at " + x + " " + y + " " + z + " with a shell depth of " + fmt(g) + " in " + b);
                                }
                                break;
                            }
                        }
                    }
                }
                // round tubes: the rock over the middle of each round way's line, out of the rooms at its ends
                for (Way wy : nb.ways) {
                    if (!wy.round) continue;
                    for (int i = 1; i + 1 < wy.n; i += 2) {
                        int x = (int) Math.floor(wy.x[i]), z = (int) Math.floor(wy.z[i]);
                        boolean inRoom = false;
                        for (Chamber c : nb.ch) if (c.keep && c.kind != Kind.TUBEHUB && t.network.e(c, x, z) < 1.05) inRoom = true;
                        if (inRoom) continue;
                        for (int y = (int) Math.ceil(wy.f[i]); y < wy.f[i] + wy.w[i] * 2 + 6; y++) {
                            if (t.rawDensity(x, y, z) <= 0) continue;
                            tubeFaces++;
                            double g = t.tubeShellDepth(x, y, z);
                            DeepBiome b = t.biomeAt(x, y, z);
                            if (g >= 1.8 || b != DeepBiome.OBSIDIAN_TUBES) fails.add(tg + ": round way #" + wy.id + " meets rock at " + x + " " + y + " " + z + " with a shell depth of " + fmt(g) + " in " + b);
                            break;
                        }
                    }
                }
                Chamber bed = nb.ch.get(nb.exit);
                landings++;
                int bx = (int) Math.floor(bed.x), bz = (int) Math.floor(bed.z), by = (int) Math.floor(bed.f0) - 1;
                if (bed.kind != Kind.BEDROCK || !t.bedrockLanding(bx, by, bz)) fails.add(tg + ": no bedrock under the bedrock landing at " + bx + " " + by + " " + bz);
                if (t.bedrockLanding(bx + (int) bed.rx + 12, by, bz)) fails.add(tg + ": bedrock 12 blocks past the bedrock landing's rim");

                Net nu = t.network.net(Terrain.BLUE, cell[0], cell[1]);
                // geodes: rays out from each geode's centre a little under its equator, to the first rock
                for (Route r : nu.canyons) for (Route.Geode ge : r.geodes) {
                    int y = (int) Math.floor(ge.y()) - 1;
                    for (int a = 0; a < 8; a++) {
                        double ang = a * Math.PI / 4 + 0.1;
                        boolean open = false, inGeode = false;
                        for (double rr = ge.r() * 0.5; rr < ge.r() + 4; rr += 0.25) {
                            int x = (int) Math.floor(ge.x() + Math.cos(ang) * rr), z = (int) Math.floor(ge.z() + Math.sin(ang) * rr);
                            if (t.rawDensity(x, y, z) <= 0) {
                                open = true;
                                inGeode = t.geodeShellDepth(x, y, z) < 0;
                                continue;
                            }
                            // the rock must close a geode, not one of the links or the rift beyond it
                            if (!open || !inGeode) break;
                            geodeFaces++;
                            double g = t.geodeShellDepth(x, y, z);
                            DeepBiome b = t.biomeAt(x, y, z);
                            if (g >= 1.7 || b != DeepBiome.ASURINE_GEODES) {
                                fails.add(tag(seed, Terrain.BLUE, cell[0], cell[1]) + ": geode at " + (int) ge.x() + " " + (int) ge.y() + " " + (int) ge.z() + " meets rock at " + x + " " + y + " " + z + " with a shell depth of " + fmt(g) + " in " + b);
                            }
                            break;
                        }
                    }
                }
            }
        }
        System.out.println("paletteQueriesFollowThePlan: " + hubFaces + " hub faces, " + tubeFaces + " round tube faces, " + geodeFaces + " geode faces, " + landings + " bedrock landings");
        assertTrue(hubFaces > 100 && geodeFaces > 100, "too few faces checked");
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails.subList(0, Math.min(30, fails.size()))));
    }

    /**
     * A plan depends on nothing but the seed, the layer and the cell: planned in a fresh world, planned on another
     * thread after its neighbours, and planned again by the planner itself, it hashes the same. Eight threads asking
     * for one new cell-layer at once get one plan, made once (single-flight).
     */
    @Test
    void plansAreDeterministic() throws Exception {
        long seed = SEEDS[0];
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            for (int li = 0; li < 3; li++) {
                final int L = li;
                long alone = planHash(fresh(seed).network.net(L, 0, 0));
                long afterNeighbours = ex.submit(() -> {
                    Network w = fresh(seed).network;
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) w.net(L, dx, dz);
                    return planHash(w.net(L, 0, 0));
                }).get();
                Network again = fresh(seed).network;
                long replanned = ex.submit(() -> planHash(NetPlanner.plan(again, L, 0, 0))).get();
                assertEquals(alone, afterNeighbours, "layer " + L + ": the plan changes when its neighbours are planned first");
                assertEquals(alone, replanned, "layer " + L + ": the plan changes when it is made again");

                Network w = fresh(seed).network;
                java.util.concurrent.CyclicBarrier go = new java.util.concurrent.CyclicBarrier(8);
                List<java.util.concurrent.Future<Net>> asks = new ArrayList<>();
                for (int i = 0; i < 8; i++) asks.add(ex.submit(() -> { go.await(); return w.net(L, 5, -3); }));
                Net first = asks.get(0).get();
                for (java.util.concurrent.Future<Net> f : asks) assertSame(first, f.get(), "layer " + L + ": two threads got different plans");
                assertEquals(1, w.plansMade.sum(), "layer " + L + ": the planner ran more than once for one cell-layer");
            }
        } finally {
            ex.shutdownNow();
        }
    }

    static Terrain fresh(long seed) { return new Terrain(TerrainSettings.defaults(), -64, seed); }

    /** A hash of what a plan carves: node centres, sizes and floors, edges, way samples and shafts, rounded to 1e-6. */
    static long planHash(Net n) {
        long h = 17;
        for (Chamber c : n.ch) {
            h = mixIn(h, c.kind.ordinal(), c.keep ? 1 : 0, c.x, c.z, c.f0, c.rx, c.rz, c.h, c.rise, c.rot, c.lakeY, c.top);
        }
        for (Edge e : n.edges) h = mixIn(h, e.a, e.b, e.kind.ordinal());
        for (Way wy : n.ways) {
            h = mixIn(h, wy.kind.ordinal(), wy.a, wy.b, wy.n, wy.inner ? 1 : 0);
            for (int i = 0; i < wy.n; i++) h = mixIn(h, wy.x[i], wy.z[i], wy.f[i], wy.w[i], wy.hgt[i], wy.slab[i]);
        }
        for (Shaft sh : n.shafts) h = mixIn(h, sh.x, sh.z, sh.r, sh.y0, sh.y1);
        return h;
    }

    static long mixIn(long h, double... vs) {
        for (double v : vs) h = h * 1000003L + (Double.isNaN(v) ? 7 : Math.round(v * 1e6));
        return h;
    }

    /**
     * The network follows the config (section 2.6): graph tests 1, 2, 6 and 9 with 480 and 720 block cells, and with
     * every layer 128 tall.
     */
    @Test
    void configVariants() {
        List<LayerSettings> d = TerrainSettings.defaults().layers();
        List<LayerSettings> short128 = new ArrayList<>();
        for (LayerSettings l : d) short128.add(new LayerSettings(l.id(), 128, l.fluidDepth(), l.lava(), l.heightMin(), l.heightMax(), l.widthMin(), l.widthMax()));
        TerrainSettings[] variants = {
                new TerrainSettings(16, 480, 4.5, 14.0, d),
                new TerrainSettings(16, 720, 4.5, 14.0, d),
                new TerrainSettings(16, 600, 4.5, 14.0, List.copyOf(short128))};
        String[] names = {"480 block cells", "720 block cells", "layers 128 tall"};
        List<String> fails = new ArrayList<>();
        for (int v = 0; v < variants.length; v++) {
            for (long seed : new long[]{SEEDS[0], SEEDS[2]}) {
                Terrain t = new Terrain(variants[v], -64, seed);
                for (int[] cell : CELLS) for (int li = 0; li < t.shape.layerCount; li++) {
                    Net n = t.network.net(li, cell[0], cell[1]);
                    String tg = names[v] + ", " + tag(seed, li, cell[0], cell[1]);
                    joinedToEntry(t, n, tg, fails);
                    waysToGates(t, n, tg, fails);
                    refuges(t, n, tg, fails);
                    dropped(t, n, tg, fails);
                }
                // test 9 on a 3 x 3 block of Blue cells: a portal works on every interior edge
                Network w = t.network;
                for (int side = 0; side < 2; side++) for (int a = 1; a < 3; a++) for (int b = 0; b < 3; b++) {
                    boolean xEdge = side == 0;
                    int ex = xEdge ? a : b, ez = xEdge ? b : a, working = 0;
                    for (Network.Portal p : w.portalsOfEdge(Terrain.BLUE, xEdge, ex, ez)) {
                        Net na = w.net(Terrain.BLUE, p.acx, p.acz), nb = w.net(Terrain.BLUE, p.bcx, p.bcz);
                        Chamber va = vestibule(na, p.ax, p.az), vb = vestibule(nb, p.bx, p.bz);
                        if (va != null && vb != null && va.keep && vb.keep && joined(na)[va.id] && joined(nb)[vb.id]) working++;
                    }
                    if (working == 0) fails.add(names[v] + ", seed " + seed + ": no portal works on the " + (xEdge ? "west" : "north") + " edge of Blue cell " + ex + "," + ez);
                }
            }
        }
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails.subList(0, Math.min(40, fails.size()))));
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
    /** Built cell-layers that more than one test reads (the Deep Gardens, Basalt Galleries and Blue Reaches tests). */
    private static final Map<String, VoxelHarness> BUILT = new HashMap<>();

    /**
     * One or more cells of a layer built as the game builds them, the climbs' vines marked, the shelf vines of the
     * Deep Blue Rift and the deep dark too (as ClimbVinesFeature and ShelfVinesFeature place them), and joined from the
     * first cell's entry landing. An apron of 64 blocks round the cells (the deep dark
     * raster's) keeps the Blue Reaches' caverns that run across a seam joined as they are in the world.
     */
    static synchronized VoxelHarness built(long seed, int li, int cx, int cz, int wc, int hc) {
        String key = seed + "/" + li + "/" + cx + "/" + cz + "/" + wc + "/" + hc;
        VoxelHarness v = BUILT.get(key);
        if (v != null) return v;
        // all but the neighbour pairs: the Blue 2 x 2 cells are read by two tests, the others by several
        boolean keep = !(wc == 2 && hc == 1);
        Terrain t = terrain(seed);
        Network w = t.network;
        int cell = (int) w.cell, ap = li == Terrain.BLUE ? NetDark.APRON : 0;
        v = VoxelHarness.build(t, li, cx * cell - ap, cz * cell - ap, wc * cell + 2 * ap, hc * cell + 2 * ap);
        for (int i = ap > 0 ? -1 : 0; i < wc + (ap > 0 ? 1 : 0); i++) for (int k = ap > 0 ? -1 : 0; k < hc + (ap > 0 ? 1 : 0); k++)
            v.markShafts(w.net(li, cx + i, cz + k));
        if (li == Terrain.BLUE) v.markShelfVines(DeepBiome.DEEP_BLUE_RIFT, DeepBiome.DEEP_DARK);
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

    /**
     * Under the highest surge, every place is a short walk from one above it. The steps are the harness's (4-neighbour
     * moves and vine rungs). A surge niche is a staged climb (section 10), so its ledge passage adds about 10 steps to
     * the design's single 25-block shaft: the bar is 98% within 80 steps (the design's single shafts: 96.9 to 100%
     * within 64).
     */
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
            int to80 = 0;
            for (int[] f : v.far) if (f[3] <= 80) to80++;
            double within64 = (h[0] + h[1] + h[2] + h[3]) / (double) Math.max(1, under);
            double within80 = (h[0] + h[1] + h[2] + h[3] + to80) / (double) Math.max(1, under);
            String tg = tag(VOX_SEEDS[s], Terrain.BASALT, VOX_CELLS[s][0], VOX_CELLS[s][1]);
            System.out.printf("%s: %d places under the surge, %.1f%% within 64 of safety, %.1f%% within 80, longest %d; surge niches %d%n", tg, under,
                    100 * within64, 100 * within80, h[7], t.network.net(Terrain.BASALT, VOX_CELLS[s][0], VOX_CELLS[s][1]).surgeNiches);
            if (under == 0) fails.add(tg + ": nothing under the surge (the bedrock landing is missing?)");
            if (within80 < 0.98) fails.add(tg + ": only " + fmt(100 * within80) + "% of the places under the surge are within 80 of safety");
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
        // measured 96.94% with the shelf vines as ShelfVinesFeature places them (89.78% with none); the design's bar is 93%
        if (share < 0.93) fails.add("the main component holds " + fmt(100 * share) + "% of the standing places");
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

    /**
     * Section 10: no fall from a standing place in a tunnel, a climb, a ledge passage or an ordinary room lands more
     * than 15 lower; a staged climb ends a fall on the floor of the stage it fell down. Falls into water or onto vines
     * are safe. Falls in the rift, the deep dark, the helices and the ancient city are reported, not failed: 0.2.0's
     * cliffs and the high ledges are meant to be dangerous.
     */
    @Test
    @Tag("voxel")
    void noLethalFalls() {
        List<String> fails = new ArrayList<>();
        List<Object[]> runs = new ArrayList<>();
        for (int s = 0; s < VOX_SEEDS.length; s++) for (int li : new int[]{Terrain.GARDENS, Terrain.BASALT}) runs.add(new Object[]{VOX_SEEDS[s], li, VOX_CELLS[s][0], VOX_CELLS[s][1], 1});
        runs.add(new Object[]{SEEDS[0], Terrain.BLUE, 0, -3, 2});
        for (Object[] run : runs) {
            long seed = (Long) run[0];
            int li = (Integer) run[1], cx = (Integer) run[2], cz = (Integer) run[3], wc = (Integer) run[4];
            Terrain t = terrain(seed);
            VoxelHarness v = built(seed, li, cx, cz, wc, wc);
            String tg = tag(seed, li, cx, cz) + (wc > 1 ? " (2 x 2)" : "");
            java.util.Map<String, Integer> told = new java.util.TreeMap<>();
            int lethal = 0, deepest = 0;
            for (int[] f : v.drops(16)) {
                int drop = f[1] - f[5];
                if (f[6] == 1 || f[6] == 2 || f[6] == 4) continue;
                String why = fallPlace(t, li, f[0], f[1], f[2]);
                if (why == null) why = fallPlace(t, li, f[3], f[1], f[4]);
                if (f[6] == 3) why = "into lava" + (why == null ? "" : ", " + why);
                if (why != null) { told.merge(why, 1, Integer::sum); continue; }
                lethal++;
                deepest = Math.max(deepest, drop);
                if (fails.size() < 40) fails.add(tg + ": a fall of " + drop + " from " + f[0] + " " + f[1] + " " + f[2] + " (" + where(t, li, f[0], f[1], f[2]) + ")");
            }
            System.out.printf("%s: %d lethal falls (deepest %d); reported: %s%n", tg, lethal, deepest, told);
        }
        assertTrue(fails.isEmpty(), fails.size() + " lethal falls:\n" + String.join("\n", fails));
    }

    /** Why a fall at a place is allowed (the rift, the deep dark, a helix, a city, a gate's mouth), or null. */
    static String fallPlace(Terrain t, int li, int x, int y, int z) {
        Network w = t.network;
        Network.LCol lc = w.layer(w.column(x, z), li);
        for (int k = 0; k < lc.nc; k++) {
            if (lc.e[k] > 1.2) continue;
            if (lc.c[k].helix()) return "helix";
            if (lc.c[k].kind == Kind.CITY) return "city";
        }
        if (lc.rOn && Math.abs(lc.rV) < lc.rHalfTop + 8) return "rift";
        if (lc.darkOn && ((lc.aC > lc.aF && y > lc.aF - 6 && y < lc.aC + 6) || (lc.bC > lc.bF && y > lc.bF - 6 && y < lc.bC + 6))) return "deep dark";
        if (gateMouth(t, lc.net, x, z)) return "gate mouth";
        return null;
    }

    /** What a place is in, for a failure message: the rooms, ways and shafts round it. */
    static String where(Terrain t, int li, int x, int y, int z) {
        Network w = t.network;
        Network.LCol lc = w.layer(w.column(x, z), li);
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < lc.nc; k++) if (lc.e[k] < 1.1) b.append(lc.c[k].kind).append('#').append(lc.c[k].id).append(" e ").append(fmt(lc.e[k])).append(' ');
        for (int k = 0; k < lc.nw; k++) if (lc.wd[k] < lc.ww[k] + 2) b.append(lc.w[k].inner ? "walk" : lc.w[k].ledge ? "ledge" : "way").append('#').append(lc.w[k].id).append(" floor ").append(fmt(lc.wf[k])).append(' ');
        for (int k = 0; k < lc.ns; k++) if (lc.sd[k] < lc.s[k].r + 3) b.append("stage ").append(lc.s[k].stage).append('/').append(lc.s[k].stages).append(" foot ").append(fmt(lc.s[k].foot)).append(" top ").append(fmt(lc.s[k].top)).append(' ');
        return b.length() == 0 ? "rock?" : b.toString().trim();
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

    /**
     * Sight lines: from the eyes of walkable places, rays at pitches -40 to +40 meet rock within 160 blocks, all but
     * 0.2% of them. Places in every 4th column of the Deep Gardens' and Basalt Galleries' voxel cells and of the Blue
     * Reaches' 2 x 2 cells (whose rays run on into the apron); rays that leave the box are not counted. Prints the
     * lengths' distribution and the longest rays.
     */
    @Test
    @Tag("voxel")
    void sightLinesAreShort() {
        List<String> fails = new ArrayList<>();
        List<Object[]> runs = new ArrayList<>();
        for (int li : new int[]{Terrain.GARDENS, Terrain.BASALT}) for (int s = 0; s < VOX_SEEDS.length; s++) runs.add(new Object[]{VOX_SEEDS[s], li, VOX_CELLS[s][0], VOX_CELLS[s][1], 1});
        runs.add(new Object[]{SEEDS[0], Terrain.BLUE, 0, -3, 2});
        for (Object[] run : runs) {
            long seed = (Long) run[0];
            int li = (Integer) run[1], cx = (Integer) run[2], cz = (Integer) run[3], wc = (Integer) run[4];
            Terrain t = terrain(seed);
            int cell = (int) t.network.cell;
            VoxelHarness v = built(seed, li, cx, cz, wc, wc);
            VoxelHarness.Sight s = v.sight(4, 400, 3, cx * cell, cz * cell, (cx + wc) * cell, (cz + wc) * cell);
            String tg = tag(seed, li, cx, cz) + (wc > 1 ? " (2 x 2)" : "");
            long counted = s.rays - s.left;
            double over = s.over(160) / (double) Math.max(1, counted);
            StringBuilder b = new StringBuilder();
            for (int lo = 0; lo < 160; lo += 40) {
                long n = 0;
                for (int k = lo / 10; k < lo / 10 + 4; k++) n += s.hist[k];
                b.append(String.format(java.util.Locale.ROOT, " %d-%d %.2f%%", lo, lo + 40, 100.0 * n / Math.max(1, counted)));
            }
            System.out.printf("%s: %d places, %d rays (%d left the box);%s, over 160 %.3f%%, longest %.0f%n", tg, s.places, counted, s.left, b, 100 * over, s.max);
            for (double[] r : s.longest)
                System.out.printf("    %.0f blocks from %.0f %.0f %.0f (yaw %.0f, pitch %.0f) to %.0f %.0f %.0f%n", r[5], r[0], r[1], r[2], r[3], r[4], r[6], r[7], r[8]);
            if (s.places < 1000) fails.add(tg + ": only " + s.places + " places looked from");
            if (over > 0.002) fails.add(tg + ": " + String.format(java.util.Locale.ROOT, "%.3f", 100 * over) + "% of the rays run over 160 blocks");
        }
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }
}
