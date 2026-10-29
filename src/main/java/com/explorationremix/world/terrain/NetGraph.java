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
