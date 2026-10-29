package com.explorationremix.world.terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The Blue Reaches' deep dark exactly as 0.2.0 shaped it: two tiers of heightfield caverns
 * (lower floors at the water table + 22, upper at + 100) under two warped fbm masks, with hourglass pillars. Same noise
 * seeds as 0.2.0's Terrain, so the caverns are the same ones. The network planner rasterises both tiers per cell and
 * treats each walkable region as one node it must join; regions too small to be worth a tunnel are closed.
 */
final class NetDark {
    /** Raster step (blocks) and the apron round the cell (blocks). */
    static final int G = 4, APRON = 64;
    /** Core: where the floor is within about two blocks of the tier's own floor (walkable cavern floor). */
    static final double CORE = 0.8;
    /** Regions smaller than this (raster cells, 16 blocks^2 each) are closed; parts inside a cell smaller than JOIN are left to the neighbour. */
    static final int KEEP_MIN = 48, JOIN_MIN = 16;

    final Perlin warpX, warpZ, maskA, maskB, floorN, heightN;
    final long seed;
    final Shape shape;
    final double W, cell;
    final int top;
    /** Raster cells per side: the cell and its apron. */
    final int N;

    NetDark(long seed, Shape shape, double cell) {
        this.seed = seed;
        this.shape = shape;
        this.cell = cell;
        this.N = (int) Math.ceil((cell + 2 * APRON) / G);
        long s = seed ^ 0x6A09E667F3BCC909L;
        warpX = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpZ = new Perlin(Hashing.mix(s, 2, 0, 0));
        maskA = new Perlin(Hashing.mix(s, 3, 0, 0));
        maskB = new Perlin(Hashing.mix(s, 4, 0, 0));
        floorN = new Perlin(Hashing.mix(s, 6, 0, 0));
        heightN = new Perlin(Hashing.mix(s, 9, 0, 0));
        W = shape.waterY(Terrain.BLUE);
        top = shape.top(Terrain.BLUE);
    }

    /** Raw openness of tier t (0 lower, 1 upper) at a column, 0..1, as 0.2.0's computeLayer. */
    double open(int t, double x, double z) {
        double wx = x + 48 * warpX.noise2(x / 160.0, z / 160.0);
        double wz = z + 48 * warpZ.noise2(x / 160.0, z / 160.0);
        if (t == 0) {
            double m = maskA.fbm2(wx / 110.0 - 40, wz / 110.0 + 13, 3);
            return Math.min(1, (m + 0.1) / 0.18);
        }
        double mb = maskB.fbm2(wx / 85.0 - 40, wz / 85.0 + 13, 3);
        return Math.min(1, (mb + 0.02) / 0.18);
    }

    double floor0(int t, double x, double z) {
        return t == 0 ? W + 22 + 6 * floorN.noise2(x / 60.0 + 90, z / 60.0) : W + 100 + 10 * floorN.noise2(x / 60.0 + 90, z / 60.0 - 40);
    }

    double ceil0(int t, double x, double z) {
        double f0 = floor0(t, x, z);
        double hgt = t == 0 ? 30 + 24 * (0.5 + 0.5 * heightN.noise2(x / 75.0 + 3, z / 75.0)) : 24 + 22 * (0.5 + 0.5 * heightN.noise2(x / 75.0 - 30, z / 75.0));
        return Math.min(f0 + hgt, top - 6);
    }

    /** 0.2.0's tier(): floor and ceiling of a tier at openness {@code open}; out = {F, C}, C <= F when closed. */
    void tier(double open, double f0, double c0, double[] out) {
        if (open <= 0 || c0 <= f0) { out[0] = 64; out[1] = -64; return; }
        double e = Terrain.smooth(open);
        double mid = f0 + (c0 - f0) * 0.4;
        out[0] = mid + (f0 - mid) * e;
        out[1] = mid + (c0 - mid) * Math.pow(e, 0.8);
    }

    /** 0.2.0's pillars(): nearest pillar {distance, radius} of tier t. */
    void pillar(int t, double x, double z, double[] out) {
        double grid = t == 0 ? 30 : 34, prob = t == 0 ? 0.45 : 0.4, rMin = 3, rMax = t == 0 ? 6 : 6.5;
        int salt = t == 0 ? 1003 : 1007;
        int gx = (int) Math.floor(x / grid), gz = (int) Math.floor(z / grid);
        double best = 1e9, br = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long h = Hashing.mix(seed, salt, gx + dx, gz + dz);
            if (Hashing.unit(h) > prob) continue;
            double cx = (gx + dx + Hashing.range(Hashing.mix(h, 1, 0, 0), 0.2, 0.8)) * grid;
            double cz = (gz + dz + Hashing.range(Hashing.mix(h, 2, 0, 0), 0.2, 0.8)) * grid;
            double r = Hashing.range(Hashing.mix(h, 3, 0, 0), rMin, rMax);
            double d = Math.hypot(x - cx, z - cz);
            if (d - r < best - br) { best = d; br = r; }
        }
        out[0] = best;
        out[1] = br;
    }

    /** Blocks from (x, z) to the nearest edge of the cell holding it. */
    static double seamDist(double x, double z, double cell) {
        double fx = x - Math.floor(x / cell) * cell, fz = z - Math.floor(z / cell) * cell;
        return Math.min(Math.min(fx, cell - fx), Math.min(fz, cell - fz));
    }

    /**
     * The seam band (section 6.3): 1 within 16 blocks of a seam, where the raw tier field is carved whatever the plan
     * kept, fading to 0 at 32. Geodes stay at least 52 blocks from seams (their fade reaches 24 past the rim), so the
     * raw field is the same pure function on both sides there.
     */
    static double band(double seamDist) {
        return seamDist <= 16 ? 1 : seamDist >= 32 ? 0 : 1 - (seamDist - 16) / 16.0;
    }

    /** Fade near the refuges (geodes), as 0.2.0: none within 8 blocks of a geode's rim, full from 24. */
    static double fade(List<Route.Geode> geodes, double x, double z) {
        double near = 1e9;
        for (Route.Geode g : geodes) near = Math.min(near, Math.hypot(x - g.x(), z - g.z()) - g.r());
        return near > 1e8 ? 1 : Terrain.smooth((near - 8) / 16.0);
    }

    // ============================================================================================== the per-cell plan
    static final class Region {
        int id, tier, area, inside;      // raster cells: all, and inside the cell's margins
        boolean keep;
        int node = -1;
        double cx, cz, f0, ceil, inR;    // deepest inside-cell core cell, its floor and ceiling, inscribed radius
        int[] boundary;                  // inside-cell core cells next to a non-core cell
        int[] doors;                     // inside-cell core cells two cells (8 blocks) in from the core's edge: door sites
    }

    static final class Plan {
        final int N;                     // raster cells per side
        final double x0, z0;             // raster origin (cell corner minus the apron)
        final double cellX0, cellZ0, cell;
        final float[][] open;
        final short[][] reg;             // region index + 1 on core cells
        final byte[][] keep;             // 1 where the tier may open
        final short[][] own;             // region index + 1 of the kept core an open cell belongs to
        final float[][] F, C;            // the tier's floor and ceiling (C <= F: closed)
        final List<Region> regions = new ArrayList<>();
        List<Route.Geode> geodes = List.of();

        Plan(int cx, int cz, double cell, int N) {
            this.N = N;
            this.cell = cell;
            cellX0 = cx * cell;
            cellZ0 = cz * cell;
            x0 = cellX0 - APRON;
            z0 = cellZ0 - APRON;
            open = new float[2][N * N];
            reg = new short[2][N * N];
            keep = new byte[2][N * N];
            own = new short[2][N * N];
            F = new float[2][N * N];
            C = new float[2][N * N];
        }

        int ix(double x) { return (int) Math.floor((x - x0) / G); }
        int iz(double z) { return (int) Math.floor((z - z0) / G); }
        double px(int i) { return x0 + G * i + G * 0.5; }
        double pz(int k) { return z0 + G * k + G * 0.5; }
        boolean inR(int i, int k) { return i >= 0 && k >= 0 && i < N && k < N; }

        /** Keep factor of tier t at a column: bilinear between raster points, 0..1. */
        double keepAt(int t, double x, double z) {
            double fx = (x - x0) / G - 0.5, fz = (z - z0) / G - 0.5;
            int i = (int) Math.floor(fx), k = (int) Math.floor(fz);
            double tx = fx - i, tz = fz - k;
            double a = kv(t, i, k), b = kv(t, i + 1, k), c = kv(t, i, k + 1), d = kv(t, i + 1, k + 1);
            return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
        }

        private double kv(int t, int i, int k) {
            if (!inR(i, k)) return 1;
            return keep[t][i * N + k];
        }

        /** The kept region whose core holds the raster point nearest (x, z) on tier t, or null. */
        Region regionAt(int t, double x, double z) {
            int i = ix(x), k = iz(z);
            if (!inR(i, k)) return null;
            int r = reg[t][i * N + k] - 1;
            if (r < 0) return null;
            Region g = regions.get(r);
            return g.keep ? g : null;
        }

        /**
         * True when the open space of either tier near (x, z) (within rad) reaches into [ylo, yhi]; cells owned by
         * region {@code except} (its core and its slope) do not count.
         */
        boolean hit(double x, double z, double rad, double ylo, double yhi, int exceptA, int exceptB) {
            int i0 = ix(x - rad), i1 = ix(x + rad), k0 = iz(z - rad), k1 = iz(z + rad);
            for (int t = 0; t < 2; t++) {
                for (int i = Math.max(0, i0); i <= Math.min(N - 1, i1); i++) for (int k = Math.max(0, k0); k <= Math.min(N - 1, k1); k++) {
                    int c = i * N + k;
                    if (keep[t][c] == 0 || open[t][c] <= 0 || C[t][c] <= F[t][c]) continue;
                    int ow = own[t][c] - 1;
                    if (ow >= 0 && (ow == exceptA || ow == exceptB)) continue;
                    if (Math.hypot(px(i) - x, pz(k) - z) > rad + G) continue;
                    if (C[t][c] + 3 < ylo || F[t][c] - 3 > yhi) continue;
                    return true;
                }
            }
            return false;
        }

        /** True when (x, z) lies on region g's core or slope (within reach blocks). */
        boolean near(Region g, double x, double z, double reach) {
            int i0 = ix(x - reach), i1 = ix(x + reach), k0 = iz(z - reach), k1 = iz(z + reach);
            for (int i = Math.max(0, i0); i <= Math.min(N - 1, i1); i++) for (int k = Math.max(0, k0); k <= Math.min(N - 1, k1); k++)
                if (own[g.tier][i * N + k] == g.id + 1) return true;
            return false;
        }

        /** The tier's floor at a raster point near (x, z). */
        double floorAt(int t, double x, double z) {
            int i = Math.max(0, Math.min(N - 1, ix(x))), k = Math.max(0, Math.min(N - 1, iz(z)));
            return F[t][i * N + k];
        }

        /** Distance from (x, z) to the nearest boundary cell of region g (inside the cell). */
        double distTo(Region g, double x, double z) {
            double best = 1e9;
            for (int c : g.boundary) best = Math.min(best, Math.hypot(px(c / N) - x, pz(c % N) - z));
            return best;
        }

        /** The boundary cells of g nearest (x, z), nearest first: {x, z}. */
        List<double[]> nearestBoundary(Region g, double x, double z, int k) {
            return nearest(g.boundary, x, z, k);
        }

        /** Door sites of g nearest (x, z): two cells in from the core's edge (the edge itself when the core is thin). */
        List<double[]> nearestDoors(Region g, double x, double z, int k) {
            return nearest(g.doors.length > 0 ? g.doors : g.boundary, x, z, k);
        }

        List<double[]> nearest(int[] cells, double x, double z, int k) {
            List<double[]> all = new ArrayList<>();
            for (int c : cells) all.add(new double[]{px(c / N), pz(c % N), Math.hypot(px(c / N) - x, pz(c % N) - z)});
            all.sort((p, q) -> Double.compare(p[2], q[2]));
            // spread the picks: skip cells within 6 blocks of one already picked
            List<double[]> out = new ArrayList<>();
            for (double[] p : all) {
                boolean near = false;
                for (double[] o : out) if (Math.hypot(o[0] - p[0], o[1] - p[1]) < 6) near = true;
                if (near) continue;
                out.add(p);
                if (out.size() >= k) break;
            }
            return out;
        }
    }

    /** Rasterises both tiers over the cell and its apron, splits the cores into regions and decides which stay. */
    Plan plan(int cx, int cz, List<Route.Geode> geodes, double margin) {
        Plan p = new Plan(cx, cz, cell, N);
        p.geodes = geodes;
        for (int t = 0; t < 2; t++) {
            float[] o = p.open[t];
            double[] fc = new double[2];
            for (int i = 0; i < N; i++) for (int k = 0; k < N; k++) {
                double x = p.px(i), z = p.pz(k);
                double op = open(t, x, z) * fade(geodes, x, z);
                o[i * N + k] = (float) op;
                tier(op, floor0(t, x, z), ceil0(t, x, z), fc);
                p.F[t][i * N + k] = (float) fc[0];
                p.C[t][i * N + k] = (float) fc[1];
            }
            // 8-connected core regions
            short[] reg = p.reg[t];
            int[] q = new int[N * N];
            for (int s = 0; s < N * N; s++) {
                if (o[s] < CORE || reg[s] != 0) continue;
                Region g = new Region();
                g.id = p.regions.size();
                g.tier = t;
                p.regions.add(g);
                int h = 0, tl = 0;
                q[tl++] = s;
                reg[s] = (short) (g.id + 1);
                while (h < tl) {
                    int u = q[h++];
                    int i = u / N, k = u % N;
                    g.area++;
                    for (int di = -1; di <= 1; di++) for (int dk = -1; dk <= 1; dk++) {
                        int ni = i + di, nk = k + dk;
                        if ((di == 0 && dk == 0) || !p.inR(ni, nk)) continue;
                        int v = ni * N + nk;
                        if (o[v] >= CORE && reg[v] == 0) { reg[v] = (short) (g.id + 1); q[tl++] = v; }
                    }
                }
            }
        }
        // inside-cell statistics, centres and boundaries
        for (Region g : p.regions) {
            short[] reg = p.reg[g.tier];
            g.keep = g.area >= KEEP_MIN;
            List<Integer> bnd = new ArrayList<>(), drs = new ArrayList<>();
            int[] dist = chamfer(reg, g.id + 1);
            int best = -1;
            for (int i = 0; i < N; i++) for (int k = 0; k < N; k++) {
                int c = i * N + k;
                if (reg[c] != g.id + 1) continue;
                double x = p.px(i), z = p.pz(k);
                boolean inside = x > p.cellX0 + margin && x < p.cellX0 + cell - margin && z > p.cellZ0 + margin && z < p.cellZ0 + cell - margin;
                if (!inside) continue;
                g.inside++;
                if (best < 0 || dist[c] > dist[best]) best = c;
                boolean edge = false;
                for (int d = 0; d < 4; d++) {
                    int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nk = k + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (!p.inR(ni, nk) || reg[ni * N + nk] != g.id + 1) edge = true;
                }
                if (edge) bnd.add(c);
                if (dist[c] == 3) drs.add(c);
            }
            g.boundary = bnd.stream().mapToInt(Integer::intValue).toArray();
            g.doors = drs.stream().mapToInt(Integer::intValue).toArray();
            if (best >= 0) {
                g.cx = p.px(best / N);
                g.cz = p.pz(best % N);
                g.f0 = floor0(g.tier, g.cx, g.cz);
                g.ceil = ceil0(g.tier, g.cx, g.cz);
                g.inR = dist[best] * G;
            }
        }
        recomputeKeep(p);
        return p;
    }

    /** Which raster cells may open: from every kept core, through open cells, never into another region's core. */
    void recomputeKeep(Plan p) {
        for (int t = 0; t < 2; t++) {
            float[] o = p.open[t];
            short[] reg = p.reg[t];
            byte[] kp = p.keep[t];
            short[] own = p.own[t];
            java.util.Arrays.fill(kp, (byte) 0);
            java.util.Arrays.fill(own, (short) 0);
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int c = 0; c < N * N; c++) {
                int r = reg[c] - 1;
                if (r >= 0 && p.regions.get(r).keep) { kp[c] = 1; own[c] = reg[c]; q.add(c); }
            }
            while (!q.isEmpty()) {
                int u = q.poll();
                int i = u / N, k = u % N;
                int ru = reg[u];
                for (int di = -1; di <= 1; di++) for (int dk = -1; dk <= 1; dk++) {
                    int ni = i + di, nk = k + dk;
                    if (!p.inR(ni, nk)) continue;
                    int v = ni * N + nk;
                    if (kp[v] != 0 || o[v] <= 0) continue;
                    if (reg[v] != 0 && reg[v] != ru) continue; // another core: its own decision
                    kp[v] = 1;
                    own[v] = own[u];
                    q.add(v);
                }
            }
            // a ring of keep round every kept cell, so the bilinear fade starts outside the open area
            byte[] grown = kp.clone();
            for (int i = 0; i < N; i++) for (int k = 0; k < N; k++) {
                if (kp[i * N + k] == 0) continue;
                for (int di = -1; di <= 1; di++) for (int dk = -1; dk <= 1; dk++) {
                    int ni = i + di, nk = k + dk;
                    if (!p.inR(ni, nk)) continue;
                    int v = ni * N + nk;
                    int rv = reg[v] - 1;
                    if (rv >= 0 && !p.regions.get(rv).keep) continue;
                    grown[v] = 1;
                }
            }
            System.arraycopy(grown, 0, kp, 0, kp.length);
        }
    }

    /** Chamfer distance (in raster cells) from each cell of region id to the nearest cell outside it. */
    private int[] chamfer(short[] reg, int id) {
        int[] d = new int[N * N];
        int INF = 1 << 20;
        for (int c = 0; c < N * N; c++) d[c] = reg[c] == id ? INF : 0;
        for (int i = 0; i < N; i++) for (int k = 0; k < N; k++) {
            int c = i * N + k;
            if (d[c] == 0) continue;
            int m = d[c];
            if (i > 0) m = Math.min(m, d[c - N] + 1); else m = Math.min(m, 1);
            if (k > 0) m = Math.min(m, d[c - 1] + 1); else m = Math.min(m, 1);
            d[c] = m;
        }
        for (int i = N - 1; i >= 0; i--) for (int k = N - 1; k >= 0; k--) {
            int c = i * N + k;
            if (d[c] == 0) continue;
            int m = d[c];
            if (i < N - 1) m = Math.min(m, d[c + N] + 1); else m = Math.min(m, 1);
            if (k < N - 1) m = Math.min(m, d[c + 1] + 1); else m = Math.min(m, 1);
            d[c] = m;
        }
        return d;
    }
}
