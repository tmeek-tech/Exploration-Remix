package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.EK;
import com.explorationremix.world.terrain.Net.Edge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/** Graph algorithms over a planned network: distances, edge-disjoint ways (max-flow, minimum cut), reach, degrees. */
final class NetGraph {
    private NetGraph() {}

    static double weight(Edge e) {
        if (e.kind == EK.STUB) return 1e9;
        double w = e.len + 2.0 * e.dy;
        if (e.kind == EK.CLIMB) w += 3 * e.dy;
        return Math.max(1, w);
    }

    static List<List<Edge>> adjacency(Net n, boolean walkOnly) {
        List<List<Edge>> adj = new ArrayList<>();
        for (int i = 0; i < n.ch.size(); i++) adj.add(new ArrayList<>());
        for (Edge e : n.edges) {
            if (e.kind == EK.STUB) continue;
            if (!n.ch.get(e.a).keep || !n.ch.get(e.b).keep) continue;
            if (walkOnly && e.kind == EK.CLIMB) continue;
            adj.get(e.a).add(e);
            adj.get(e.b).add(e);
        }
        return adj;
    }

    static double dist(Net n, int a, int b) {
        double[] d = dijkstra(n, a, false, null);
        return d[b];
    }

    /**
     * True when the way from a to b over adjacency {@code adj} (by {@link #weight}) is shorter than {@code limit}: the
     * same answer as {@code dist(n, a, b) < limit}, but the search stops at that distance, so it costs what lies within
     * it rather than the whole network (the Wasteland asks it of every candidate tunnel).
     */
    static boolean within(Net n, List<List<Edge>> adj, int a, int b, double limit) {
        java.util.Map<Integer, Double> d = new java.util.HashMap<>();
        d.put(a, 0.0);
        PriorityQueue<double[]> q = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
        q.add(new double[]{0, a});
        while (!q.isEmpty()) {
            double[] t = q.poll();
            int u = (int) t[1];
            if (t[0] > d.get(u)) continue;
            if (u == b) return true;
            for (Edge e : adj.get(u)) {
                int v = e.other(u);
                double nd = t[0] + weight(e);
                if (nd >= limit) continue;
                Double old = d.get(v);
                if (old == null || nd < old) {
                    d.put(v, nd);
                    q.add(new double[]{nd, v});
                }
            }
        }
        return false;
    }

    static double[] dijkstra(Net n, int src, boolean walkOnly, Edge[] via) {
        List<List<Edge>> adj = adjacency(n, walkOnly);
        double[] d = new double[n.ch.size()];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        d[src] = 0;
        PriorityQueue<double[]> q = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
        q.add(new double[]{0, src});
        while (!q.isEmpty()) {
            double[] t = q.poll();
            int u = (int) t[1];
            if (t[0] > d[u]) continue;
            for (Edge e : adj.get(u)) {
                int v = e.other(u);
                double nd = d[u] + weight(e);
                if (nd < d[v]) {
                    d[v] = nd;
                    if (via != null) via[v] = e;
                    q.add(new double[]{nd, v});
                }
            }
        }
        return d;
    }

    /** Number of edge-disjoint ways between s and t (unit capacities, undirected). */
    static int maxFlow(Net n, int s, int t, boolean walkOnly) {
        boolean[] sink = new boolean[n.ch.size()];
        sink[t] = true;
        return flow(n, s, sink, walkOnly, null);
    }

    /** Number of edge-disjoint ways from s to any node of the sink set. */
    static int maxFlowToSet(Net n, int s, boolean[] sink, boolean walkOnly) {
        return flow(n, s, sink, walkOnly, null);
    }

    /** The source side of a minimum cut between s and t: what the residual graph still reaches from s. */
    static boolean[] minCutSide(Net n, int s, int t, boolean walkOnly) {
        boolean[] sink = new boolean[n.ch.size()];
        sink[t] = true;
        boolean[] side = new boolean[n.ch.size()];
        flow(n, s, sink, walkOnly, side);
        return side;
    }

    private static int flow(Net n, int s, boolean[] sink, boolean walkOnly, boolean[] sideOut) {
        List<Edge> es = new ArrayList<>();
        for (Edge e : n.edges) {
            if (e.kind == EK.STUB || !n.ch.get(e.a).keep || !n.ch.get(e.b).keep) continue;
            if (walkOnly && e.kind == EK.CLIMB) continue;
            if (e.a == e.b) continue;
            es.add(e);
        }
        int m = es.size();
        int[] flow = new int[m]; // +1: a->b, -1: b->a
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n.ch.size(); i++) adj.add(new ArrayList<>());
        for (int i = 0; i < m; i++) { adj.get(es.get(i).a).add(i); adj.get(es.get(i).b).add(i); }
        int total = 0;
        int[] prevE = new int[n.ch.size()];
        while (true) {
            Arrays.fill(prevE, -2);
            prevE[s] = -1;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(s);
            int hit = -1;
            while (!q.isEmpty() && hit < 0) {
                int u = q.poll();
                for (int ei : adj.get(u)) {
                    Edge e = es.get(ei);
                    int v = e.other(u);
                    if (prevE[v] != -2) continue;
                    int dir = u == e.a ? 1 : -1;
                    if (flow[ei] == dir) continue; // already used this way
                    prevE[v] = ei;
                    if (sink[v]) { hit = v; break; }
                    q.add(v);
                }
            }
            if (hit < 0 || total >= 8) {
                if (sideOut != null) for (int i = 0; i < prevE.length; i++) sideOut[i] = prevE[i] != -2;
                break;
            }
            int v = hit;
            while (v != s) {
                int ei = prevE[v];
                Edge e = es.get(ei);
                int u = e.other(v);
                flow[ei] += u == e.a ? 1 : -1;
                v = u;
            }
            total++;
        }
        return total;
    }

    /** Up to k edge-disjoint ways from s to t, shortest first (greedy: each takes the edges it uses away). */
    static List<List<Edge>> disjointPaths(Net n, int s, int t, int k) {
        List<List<Edge>> out = new ArrayList<>();
        List<Edge> removed = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            Edge[] via = new Edge[n.ch.size()];
            double[] d = dijkstraWithout(n, s, removed, via);
            if (Double.isInfinite(d[t])) break;
            List<Edge> path = new ArrayList<>();
            int v = t;
            while (v != s) {
                Edge e = via[v];
                path.add(e);
                v = e.other(v);
            }
            out.add(path);
            removed.addAll(path);
        }
        return out;
    }

    static double[] dijkstraWithout(Net n, int src, List<Edge> removed, Edge[] via) {
        List<List<Edge>> adj = adjacency(n, false);
        double[] d = new double[n.ch.size()];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        d[src] = 0;
        PriorityQueue<double[]> q = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
        q.add(new double[]{0, src});
        while (!q.isEmpty()) {
            double[] tt = q.poll();
            int u = (int) tt[1];
            if (tt[0] > d[u]) continue;
            for (Edge e : adj.get(u)) {
                if (removed.contains(e)) continue;
                int v = e.other(u);
                double nd = d[u] + weight(e);
                if (nd < d[v]) { d[v] = nd; via[v] = e; q.add(new double[]{nd, v}); }
            }
        }
        return d;
    }

    /** Nodes reachable from src. */
    static boolean[] reach(Net n, int src, boolean walkOnly) {
        List<List<Edge>> adj = adjacency(n, walkOnly);
        boolean[] seen = new boolean[n.ch.size()];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(src);
        seen[src] = true;
        while (!q.isEmpty()) {
            int u = q.poll();
            for (Edge e : adj.get(u)) {
                int v = e.other(u);
                if (!seen[v]) { seen[v] = true; q.add(v); }
            }
        }
        return seen;
    }

    /** Nodes reachable from src over any edge (stubs aside) with node {@code without} taken out (src itself stays). */
    static boolean[] reachWithout(Net n, int src, int without) {
        List<List<Edge>> adj = adjacency(n, false);
        boolean[] seen = new boolean[n.ch.size()];
        if (src == without) return seen;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(src);
        seen[src] = true;
        while (!q.isEmpty()) {
            int u = q.poll();
            for (Edge e : adj.get(u)) {
                int v = e.other(u);
                if (v == without || seen[v]) continue;
                seen[v] = true;
                q.add(v);
            }
        }
        return seen;
    }

    /** Degree of each node over real edges (stubs count: they are dead ends hanging off it). */
    static int[] degrees(Net n) {
        int[] d = new int[n.ch.size()];
        for (Edge e : n.edges) {
            if (!n.ch.get(e.a).keep || !n.ch.get(e.b).keep) continue;
            if (e.kind == EK.STUB && e.a == e.b) { d[e.a]++; continue; }
            d[e.a]++;
            d[e.b]++;
        }
        return d;
    }

    /**
     * The blocks walked along an edge from door to door: its tunnel, or a climb's lower and upper tunnels and the ledge
     * passages between its stages (the shafts themselves are climbed, not walked), less the 3 blocks each end runs on
     * into its chamber ({@link #RUN_IN}). An edge with no tunnel (a deep dark door) walks none.
     */
    static double walkLength(Edge e) {
        double l = 0;
        if (e.w1 != null) l += e.w1.length;
        if (e.w2 != null) l += e.w2.length;
        for (Net.Way w : e.ledges) l += w.length;
        return Math.max(0, l - 2 * RUN_IN);
    }

    /** How far a tunnel's line runs on past its door into the chamber (walkWay's first and last 3 blocks). */
    static final double RUN_IN = 3;

    /**
     * The ways a Wasteland chamber needs so that it is no dead end: a junction room three (it is a fork; with two it
     * would only be a bulge in one passage), everything else two (a portal vestibule two inside the cell besides its
     * stub, so its tunnel is no bridge either).
     */
    static int waysNeeded(Chamber c) {
        return c.kind == Net.Kind.JUNCTION ? 3 : 2;
    }

    /** The network's ways: edges between two different kept chambers, blind stubs left out. */
    static List<Edge> ways(Net n) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : n.edges) {
            if (e.kind == EK.STUB || e.a == e.b || !n.ch.get(e.a).keep || !n.ch.get(e.b).keep) continue;
            out.add(e);
        }
        return out;
    }

    /** Each kept chamber's number of ways ({@link #ways}): unlike {@link #degrees}, a blind stub is no way out. */
    static int[] wayCounts(Net n) {
        int[] d = new int[n.ch.size()];
        for (Edge e : ways(n)) { d[e.a]++; d[e.b]++; }
        return d;
    }

    /**
     * The bridges among the network's ways ({@link #ways}): the edges whose loss would cut the network in two, so no
     * loop runs through them. Two edges between the same two chambers are two ways, so neither is a bridge. Tarjan's
     * low-link walk, with an explicit stack so a long chain cannot overflow the thread's.
     */
    static List<Edge> bridges(Net n) {
        List<Edge> es = ways(n);
        int N = n.ch.size();
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < N; i++) adj.add(new ArrayList<>());
        for (int i = 0; i < es.size(); i++) {
            adj.get(es.get(i).a).add(new int[]{es.get(i).b, i});
            adj.get(es.get(i).b).add(new int[]{es.get(i).a, i});
        }
        int[] tin = new int[N], low = new int[N];
        Arrays.fill(tin, -1);
        int timer = 0;
        List<Edge> out = new ArrayList<>();
        ArrayDeque<int[]> st = new ArrayDeque<>(); // {chamber, the edge it was reached by, next neighbour to look at}
        for (int s = 0; s < N; s++) {
            if (tin[s] >= 0 || !n.ch.get(s).keep) continue;
            tin[s] = low[s] = timer++;
            st.push(new int[]{s, -1, 0});
            while (!st.isEmpty()) {
                int[] f = st.peek();
                int v = f[0];
                if (f[2] < adj.get(v).size()) {
                    int[] nb = adj.get(v).get(f[2]++);
                    if (nb[1] == f[1]) continue; // the edge it came by (not a parallel one)
                    int u = nb[0];
                    if (tin[u] >= 0) low[v] = Math.min(low[v], tin[u]);
                    else {
                        tin[u] = low[u] = timer++;
                        st.push(new int[]{u, nb[1], 0});
                    }
                } else {
                    st.pop();
                    if (st.isEmpty()) continue;
                    int p = st.peek()[0];
                    low[p] = Math.min(low[p], low[v]);
                    if (low[v] > tin[p]) out.add(es.get(f[1]));
                }
            }
        }
        return out;
    }

    /**
     * The runs of the network: its ways ({@link #ways}) cut into the longest chains that pass only through chambers with
     * exactly two ways, where a walker has no choice but to go on. A run ends at a chamber with one way or with three or
     * more, at a landing (its gate is another way on) and at a portal vestibule (its stub to the next cell is another
     * way on). A closed ring of two-way chambers is one run.
     */
    static List<List<Edge>> runs(Net n) { return chains(n, false); }

    /**
     * The passages of the network: its ways cut into the longest chains that pass only through junction rooms with
     * exactly two ways (a fork that lost its third way is a bulge in one passage), so each is a stretch of tunnel
     * between two rooms or forks. Every chamber but such a junction ends a passage, as a run's ends do ({@link #runs}).
     */
    static List<List<Edge>> passages(Net n) { return chains(n, true); }

    /** The runs ({@code rooms} false) or the passages ({@code rooms} true). */
    private static List<List<Edge>> chains(Net n, boolean rooms) {
        List<Edge> es = ways(n);
        int N = n.ch.size();
        int[] deg = new int[N];
        List<List<Edge>> adj = new ArrayList<>();
        for (int i = 0; i < N; i++) adj.add(new ArrayList<>());
        for (Edge e : es) { deg[e.a]++; deg[e.b]++; adj.get(e.a).add(e); adj.get(e.b).add(e); }
        java.util.Set<Edge> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<List<Edge>> out = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            // ends first, then whatever is left: rings with no end
            for (Chamber c : n.ch) {
                if (!c.keep || chainEnd(c, deg[c.id], rooms) != (pass == 0)) continue;
                for (Edge e0 : adj.get(c.id)) {
                    if (seen.contains(e0)) continue;
                    List<Edge> run = new ArrayList<>();
                    int v = c.id;
                    Edge e = e0;
                    while (true) {
                        seen.add(e);
                        run.add(e);
                        v = e.other(v);
                        if (chainEnd(n.ch.get(v), deg[v], rooms)) break;
                        Edge nx = adj.get(v).get(0) == e ? adj.get(v).get(1) : adj.get(v).get(0);
                        if (seen.contains(nx)) break;
                        e = nx;
                    }
                    out.add(run);
                }
            }
        }
        return out;
    }

    /** True where a run ends: see {@link #runs}. */
    static boolean runEnd(Chamber c, int ways) {
        return ways != 2 || c.landing() || c.kind == Net.Kind.PORTAL;
    }

    /** True where a run ends, or with {@code rooms} a passage (see {@link #passages}). */
    private static boolean chainEnd(Chamber c, int ways, boolean rooms) {
        return runEnd(c, ways) || (rooms && c.kind != Net.Kind.JUNCTION);
    }

    /** The blocks walked along a run's tunnels ({@link #walkLength} of each edge). */
    static double runLength(List<Edge> run) {
        double l = 0;
        for (Edge e : run) l += walkLength(e);
        return l;
    }

    /**
     * The blocks a walker covers along a run ({@link #runs}): its tunnels ({@link #runLength}) and, in each chamber
     * inside it, the way across the floor from the door it comes in by to the door it leaves by. A room with only two
     * ways offers no other way on, so to the walker it is part of the same long passage.
     */
    static double runWalk(Net n, List<Edge> run) {
        double l = runLength(run);
        if (run.size() < 2) return l;
        int v = runStart(n, run);
        for (int i = 0; i + 1 < run.size(); i++) {
            v = run.get(i).other(v);
            double[] p = doorOf(n, run.get(i), v), q = doorOf(n, run.get(i + 1), v);
            l += Math.hypot(p[0] - q[0], p[1] - q[1]);
        }
        return l;
    }

    /**
     * The chamber a run of two or more edges starts from: the end of its first edge that the second does not share,
     * or, where the two join the same two chambers, the one of them that ends runs.
     */
    static int runStart(Net n, List<Edge> run) {
        Edge e0 = run.get(0), e1 = run.get(1);
        boolean a = e0.a == e1.a || e0.a == e1.b, b = e0.b == e1.a || e0.b == e1.b;
        if (a != b) return a ? e0.b : e0.a;
        int[] ways = wayCounts(n);
        return runEnd(n.ch.get(e0.a), ways[e0.a]) ? e0.a : e0.b;
    }

    /**
     * Where edge e leaves chamber v, {x, z}: the point of its tunnel's line {@link #RUN_IN} back from the end that lies
     * in v (a climb's lower tunnel starts at the lower chamber, its upper one ends at the upper chamber). An edge with
     * no tunnel leaves from the chamber's centre.
     */
    static double[] doorOf(Net n, Edge e, int v) {
        Chamber c = n.ch.get(v);
        Net.Way wy = e.w1;
        if (wy == null) return new double[]{c.x, c.z};
        boolean fromStart;
        if (e.kind == EK.CLIMB && e.w2 != null) {
            Net.Way up = e.w2;
            fromStart = Math.hypot(wy.x[0] - c.x, wy.z[0] - c.z) <= Math.hypot(up.x[up.n - 1] - c.x, up.z[up.n - 1] - c.z);
            if (!fromStart) wy = up;
        } else {
            fromStart = Math.hypot(wy.x[0] - c.x, wy.z[0] - c.z) <= Math.hypot(wy.x[wy.n - 1] - c.x, wy.z[wy.n - 1] - c.z);
        }
        double s = 0;
        for (int j = 1; j < wy.n; j++) {
            int i = fromStart ? j : wy.n - 1 - j, ip = fromStart ? j - 1 : wy.n - j;
            double l = Math.hypot(wy.x[i] - wy.x[ip], wy.z[i] - wy.z[ip]);
            if (s + l >= RUN_IN) {
                double t = (RUN_IN - s) / Math.max(1e-9, l);
                return new double[]{wy.x[ip] + (wy.x[i] - wy.x[ip]) * t, wy.z[ip] + (wy.z[i] - wy.z[ip]) * t};
            }
            s += l;
        }
        int i = fromStart ? wy.n - 1 : 0;
        return new double[]{wy.x[i], wy.z[i]};
    }

    /** Cyclomatic number of the walkable graph (edges minus nodes plus components), stubs left out. */
    static int cycles(Net n) {
        int V = 0, E = 0;
        for (Chamber c : n.ch) if (c.keep) V++;
        for (Edge e : n.edges) if (e.kind != EK.STUB && n.ch.get(e.a).keep && n.ch.get(e.b).keep) E++;
        // components
        boolean[] seen = new boolean[n.ch.size()];
        List<List<Edge>> adj = adjacency(n, false);
        int comps = 0;
        for (Chamber c : n.ch) {
            if (!c.keep || seen[c.id]) continue;
            comps++;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(c.id);
            seen[c.id] = true;
            while (!q.isEmpty()) {
                int u = q.poll();
                for (Edge e : adj.get(u)) { int v = e.other(u); if (!seen[v]) { seen[v] = true; q.add(v); } }
            }
        }
        return E - V + comps;
    }
}
