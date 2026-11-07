package com.explorationremix.world.terrain;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * The blocks of the noise layers (the Wasteland, the rows where it meets the Galleries, the Galleries) in a box, made as
 * the game makes them: {@link Terrain#density} at Minecraft's 4 x 8 x 4 corners, trilinear interpolation,
 * {@link Terrain#fine} per block, rock where that is positive and {@link Terrain#fluidAt} elsewhere. Then the walk graph
 * the 0.5.1 field specs measured on such blocks: a standing place is two open blocks (air or water) over rock; from it a
 * player steps into a neighbouring column one block up (with head room over his own feet), on the level, or off an edge
 * down to the first standing place at most {@link #MAX_DROP} under it; a fall into lava, or a longer one, is no move.
 *
 * <p>The drops make the graph directed, so its parts are strongly connected components: places a player can walk
 * between both ways. (The planned layers' {@link VoxelHarness} keeps two-way moves only.) Over the graph: the walks into
 * the main component and up out of a flood, and sight lines. Nothing here reads a field's insides, only Terrain, so the
 * same measures hold whatever shape a field takes.
 *
 * <p>Two bits a block: a 1024 x 1024 box of the Galleries' 160 rows takes 42 MB, its places and graph about as much again.
 */
final class NoiseVox {
    static final int ROCK = 0, AIR = 1, WATER = 2, LAVA = 3;
    /** The longest drop a move may take (blocks fallen). */
    static final int MAX_DROP = 15;
    static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    final Terrain t;
    final int x0, y0, z0, nx, ny, nz;
    /** Words of 32 blocks per column. */
    private final int mw;
    private final long[] mat;
    /** Time spent on the corners and on the per-block pass (nanoseconds, all threads together). */
    long cornerNs, blockNs;
    /** Blocks whose density came back NaN. */
    long nans;

    // ------------------------------------------------------------------------ the walk graph (after graph())
    /** Places of column q are colStart[q]..colStart[q + 1] - 1, bottom up. */
    int[] colStart;
    /** Each place's feet row (y - y0) and column (i * nz + k). */
    short[] feet;
    int[] colOf;
    int places;
    /** Each place's moves, 4 slots (one per direction), -1 for none. */
    int[] next;
    /** Each place's strongly connected component, and each component's size; the largest one. */
    int[] comp, compSize;
    int main = -1;

    private NoiseVox(Terrain t, int x0, int y0, int z0, int nx, int ny, int nz) {
        this.t = t;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        mw = (ny + 31) / 32;
        mat = new long[nx * nz * mw];
    }

    /** The blocks of rows ylo..yhi over the columns [x0, x0 + nx) x [z0, z0 + nz). */
    static NoiseVox build(Terrain t, int x0, int z0, int nx, int nz, int ylo, int yhi) {
        NoiseVox v = new NoiseVox(t, x0, ylo, z0, nx, yhi - ylo + 1, nz);
        v.fill();
        return v;
    }

    private void fill() {
        int cx0 = Math.floorDiv(x0, 4), cx1 = Math.floorDiv(x0 + nx - 1, 4) + 1;
        int cz0 = Math.floorDiv(z0, 4), cz1 = Math.floorDiv(z0 + nz - 1, 4) + 1;
        int cy0 = Math.floorDiv(y0, 8), cy1 = Math.floorDiv(y0 + ny - 1, 8) + 1;
        int CX = cx1 - cx0 + 1, CZ = cz1 - cz0 + 1, CY = cy1 - cy0 + 1;
        double[] c = new double[CX * CZ * CY];
        long t0 = System.nanoTime();
        IntStream.range(0, CX).parallel().forEach(i -> {
            for (int k = 0; k < CZ; k++) for (int j = 0; j < CY; j++)
                c[(i * CZ + k) * CY + j] = t.density((cx0 + i) * 4, (cy0 + j) * 8, (cz0 + k) * 4);
        });
        long t1 = System.nanoTime();
        long[] nan = new long[nx];
        IntStream.range(0, nx).parallel().forEach(i -> {
            int x = x0 + i;
            int ci = Math.floorDiv(x, 4) - cx0;
            double fx = (x - Math.floorDiv(x, 4) * 4) / 4.0;
            for (int k = 0; k < nz; k++) {
                int z = z0 + k;
                int ck = Math.floorDiv(z, 4) - cz0;
                double fz = (z - Math.floorDiv(z, 4) * 4) / 4.0;
                int base = (i * nz + k) * mw;
                long word = 0;
                for (int j = 0; j < ny; j++) {
                    int y = y0 + j;
                    int cj = Math.floorDiv(y, 8) - cy0;
                    double fy = (y - Math.floorDiv(y, 8) * 8) / 8.0;
                    double c000 = c[(ci * CZ + ck) * CY + cj], c100 = c[((ci + 1) * CZ + ck) * CY + cj];
                    double c010 = c[(ci * CZ + ck) * CY + cj + 1], c110 = c[((ci + 1) * CZ + ck) * CY + cj + 1];
                    double c001 = c[(ci * CZ + ck + 1) * CY + cj], c101 = c[((ci + 1) * CZ + ck + 1) * CY + cj];
                    double c011 = c[(ci * CZ + ck + 1) * CY + cj + 1], c111 = c[((ci + 1) * CZ + ck + 1) * CY + cj + 1];
                    double x00 = c000 + (c100 - c000) * fx, x10 = c010 + (c110 - c010) * fx;
                    double x01 = c001 + (c101 - c001) * fx, x11 = c011 + (c111 - c011) * fx;
                    double y0v = x00 + (x10 - x00) * fy, y1v = x01 + (x11 - x01) * fy;
                    double d = t.fine(x, y, z, y0v + (y1v - y0v) * fz);
                    if (d != d) nan[i]++;
                    int m;
                    if (d > 0) m = ROCK;
                    else {
                        int f = t.fluidAt(x, y, z);
                        m = f == Terrain.WATER ? WATER : (f == Terrain.LAVA ? LAVA : AIR);
                    }
                    word |= (long) m << ((j & 31) << 1);
                    if ((j & 31) == 31 || j == ny - 1) {
                        mat[base + (j >> 5)] = word;
                        word = 0;
                    }
                }
            }
        });
        long t2 = System.nanoTime();
        cornerNs = t1 - t0;
        blockNs = t2 - t1;
        for (long n : nan) nans += n;
    }

    // ================================================================================================ blocks
    boolean in(int x, int y, int z) {
        return x >= x0 && x < x0 + nx && y >= y0 && y < y0 + ny && z >= z0 && z < z0 + nz;
    }

    boolean inColumns(int x, int z) { return x >= x0 && x < x0 + nx && z >= z0 && z < z0 + nz; }

    /** The material at a block; rock outside the box. */
    int mat(int x, int y, int z) {
        if (!in(x, y, z)) return ROCK;
        int j = y - y0;
        long w = mat[((x - x0) * nz + (z - z0)) * mw + (j >> 5)];
        return (int) (w >>> ((j & 31) << 1)) & 3;
    }

    /**
     * Sets a block (a decoration laid on the blocks, {@link DecorBox}); nothing outside the box. The walk graph is stale
     * until {@link #graph} runs again.
     */
    void set(int x, int y, int z, int m) {
        if (!in(x, y, z)) return;
        int j = y - y0, w = ((x - x0) * nz + (z - z0)) * mw + (j >> 5), sh = (j & 31) << 1;
        mat[w] = (mat[w] & ~(3L << sh)) | ((long) (m & 3) << sh);
    }

    /** Open for a player: air or water (lava is not). */
    boolean open(int x, int y, int z) {
        int m = mat(x, y, z);
        return m == AIR || m == WATER;
    }

    boolean rock(int x, int y, int z) { return mat(x, y, z) == ROCK; }

    /** A standing place: feet and head open, rock under the feet, all inside the box. */
    boolean stand(int x, int y, int z) {
        return y > y0 && y + 1 < y0 + ny && inColumns(x, z) && open(x, y, z) && open(x, y + 1, z) && mat(x, y - 1, z) == ROCK;
    }

    // ================================================================================================ the walk graph
    int px(int p) { return x0 + colOf[p] / nz; }

    int pz(int p) { return z0 + colOf[p] % nz; }

    int py(int p) { return y0 + feet[p]; }

    /** The place with feet at (x, y, z), or -1. */
    int index(int x, int y, int z) {
        if (!in(x, y, z)) return -1;
        int q = (x - x0) * nz + (z - z0), j = y - y0;
        for (int p = colStart[q], e = colStart[q + 1]; p < e; p++) {
            if (feet[p] == j) return p;
            if (feet[p] > j) return -1;
        }
        return -1;
    }

    /**
     * Where a player standing at (x, y, z) gets by stepping toward direction d: one block up (rock under the neighbour's
     * new feet, head room over his own), on the level, or down to the first standing place at most {@link #MAX_DROP}
     * under; -1 for none (a wall, a longer fall, a fall into lava).
     */
    int move(int x, int y, int z, int d) {
        int qx = x + DX[d], qz = z + DZ[d];
        if (!inColumns(qx, qz)) return -1;
        if (stand(qx, y + 1, qz) && open(x, y + 2, z)) return index(qx, y + 1, qz);
        if (!open(qx, y, qz) || !open(qx, y + 1, qz)) return -1;
        for (int yy = y; yy >= y - MAX_DROP && yy > y0; yy--) {
            if (!open(qx, yy, qz)) return -1;
            if (mat(qx, yy - 1, qz) == ROCK) return index(qx, yy, qz);
        }
        return -1;
    }

    /** Lists the standing places, their moves and the strongly connected components; the main one is the largest. */
    NoiseVox graph() {
        int cols = nx * nz;
        int[] count = new int[cols];
        IntStream.range(0, nx).parallel().forEach(i -> {
            for (int k = 0; k < nz; k++) {
                int x = x0 + i, z = z0 + k, n = 0;
                for (int j = 1; j + 1 < ny; j++) if (stand(x, y0 + j, z)) n++;
                count[i * nz + k] = n;
            }
        });
        colStart = new int[cols + 1];
        for (int q = 0; q < cols; q++) colStart[q + 1] = colStart[q] + count[q];
        places = colStart[cols];
        feet = new short[places];
        colOf = new int[places];
        IntStream.range(0, nx).parallel().forEach(i -> {
            for (int k = 0; k < nz; k++) {
                int x = x0 + i, z = z0 + k, q = i * nz + k, p = colStart[q];
                for (int j = 1; j + 1 < ny; j++) if (stand(x, y0 + j, z)) { feet[p] = (short) j; colOf[p++] = q; }
            }
        });
        next = new int[places * 4];
        IntStream.range(0, places).parallel().forEach(p -> {
            int x = px(p), y = py(p), z = pz(p);
            for (int d = 0; d < 4; d++) next[p * 4 + d] = move(x, y, z, d);
        });
        components();
        return this;
    }

    /** Tarjan's strongly connected components, iterative. */
    private void components() {
        int P = places;
        comp = new int[P];
        Arrays.fill(comp, -1);
        int[] index = new int[P], low = new int[P], stack = new int[P], cs = new int[P];
        byte[] ce = new byte[P];
        boolean[] onStack = new boolean[P];
        Arrays.fill(index, -1);
        int idx = 0, comps = 0, sp = 0;
        int[] sizes = new int[1024];
        for (int s = 0; s < P; s++) {
            if (index[s] >= 0) continue;
            int top = 0;
            cs[0] = s;
            ce[0] = 0;
            index[s] = low[s] = idx++;
            stack[sp++] = s;
            onStack[s] = true;
            while (top >= 0) {
                int v = cs[top];
                if (ce[top] < 4) {
                    int w = next[v * 4 + ce[top]++];
                    if (w < 0) continue;
                    if (index[w] < 0) {
                        index[w] = low[w] = idx++;
                        stack[sp++] = w;
                        onStack[w] = true;
                        top++;
                        cs[top] = w;
                        ce[top] = 0;
                    } else if (onStack[w]) low[v] = Math.min(low[v], index[w]);
                } else {
                    if (low[v] == index[v]) {
                        int n = 0;
                        while (true) {
                            int w = stack[--sp];
                            onStack[w] = false;
                            comp[w] = comps;
                            n++;
                            if (w == v) break;
                        }
                        if (comps == sizes.length) sizes = Arrays.copyOf(sizes, sizes.length * 2);
                        sizes[comps++] = n;
                    }
                    top--;
                    if (top >= 0) { int u = cs[top]; low[u] = Math.min(low[u], low[v]); }
                }
            }
        }
        compSize = Arrays.copyOf(sizes, comps);
        main = -1;
        for (int c = 0; c < comps; c++) if (main < 0 || compSize[c] > compSize[main]) main = c;
    }

    boolean inMain(int p) { return main >= 0 && comp[p] == main; }

    /** Whether place p stands in the columns [xa, xb) x [za, zb) with its feet in rows ya..yb. */
    boolean inArea(int p, int xa, int za, int xb, int zb, int ya, int yb) {
        int x = px(p), z = pz(p), y = py(p);
        return x >= xa && x < xb && z >= za && z < zb && y >= ya && y <= yb;
    }

    /**
     * Moves (walking forward) from each place to the nearest place of {@code goal}, by a breadth-first search back from
     * the goal over the reversed moves; -1 where no walk gets there.
     */
    int[] walkTo(boolean[] goal) {
        int P = places;
        int[] rs = new int[P + 1];
        for (int e : next) if (e >= 0) rs[e + 1]++;
        for (int i = 0; i < P; i++) rs[i + 1] += rs[i];
        int[] radj = new int[rs[P]];
        int[] fill = Arrays.copyOf(rs, P);
        for (int u = 0; u < P; u++) for (int d = 0; d < 4; d++) { int w = next[u * 4 + d]; if (w >= 0) radj[fill[w]++] = u; }
        int[] dist = new int[P];
        Arrays.fill(dist, -1);
        int[] q = new int[P];
        int h = 0, tl = 0;
        for (int p = 0; p < P; p++) if (goal[p]) { dist[p] = 0; q[tl++] = p; }
        while (h < tl) {
            int u = q[h++];
            for (int e = rs[u]; e < rs[u + 1]; e++) {
                int w = radj[e];
                if (dist[w] < 0) { dist[w] = dist[u] + 1; q[tl++] = w; }
            }
        }
        return dist;
    }

    /** The places a walk (forward) from any place of {@code from} reaches, those included. */
    boolean[] reachFrom(boolean[] from) {
        boolean[] seen = Arrays.copyOf(from, places);
        int[] q = new int[places];
        int h = 0, tl = 0;
        for (int p = 0; p < places; p++) if (seen[p]) q[tl++] = p;
        while (h < tl) {
            int u = q[h++];
            for (int d = 0; d < 4; d++) {
                int w = next[u * 4 + d];
                if (w >= 0 && !seen[w]) { seen[w] = true; q[tl++] = w; }
            }
        }
        return seen;
    }

    /**
     * Rock not held by anything: the components of rock blocks (6-connected, found as runs per column joined by
     * union-find) that touch no face of the box. Returns each such component of {@code minBlocks} or more as {blocks,
     * x, y, z of one of its blocks}, largest first.
     */
    java.util.List<int[]> floating(int minBlocks) {
        int[] colRun = new int[nx * nz + 1];
        int[] ry0 = new int[1 << 16], ry1 = new int[1 << 16];
        int nr = 0;
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            colRun[i * nz + k] = nr;
            int x = x0 + i, z = z0 + k, y = y0, top = y0 + ny - 1;
            while (y <= top) {
                if (mat(x, y, z) != ROCK) { y++; continue; }
                int a = y;
                while (y <= top && mat(x, y, z) == ROCK) y++;
                if (nr == ry0.length) { ry0 = Arrays.copyOf(ry0, nr * 2); ry1 = Arrays.copyOf(ry1, nr * 2); }
                ry0[nr] = a;
                ry1[nr] = y - 1;
                nr++;
            }
        }
        colRun[nx * nz] = nr;
        int[] parent = new int[nr + 1];
        for (int r = 0; r <= nr; r++) parent[r] = r;
        int anchor = nr, top = y0 + ny - 1;
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            int q = i * nz + k;
            boolean edge = i == 0 || k == 0 || i == nx - 1 || k == nz - 1;
            for (int r = colRun[q]; r < colRun[q + 1]; r++) if (edge || ry0[r] == y0 || ry1[r] == top) union(parent, r, anchor);
            for (int d = 0; d < 2; d++) {
                int i2 = i + (d == 0 ? 1 : 0), k2 = k + (d == 1 ? 1 : 0);
                if (i2 >= nx || k2 >= nz) continue;
                int q2 = i2 * nz + k2;
                int a = colRun[q], ae = colRun[q + 1], b = colRun[q2], be = colRun[q2 + 1];
                while (a < ae && b < be) {
                    if (ry1[a] >= ry0[b] && ry1[b] >= ry0[a]) union(parent, a, b);
                    if (ry1[a] < ry1[b]) a++; else b++;
                }
            }
        }
        int root = find(parent, anchor);
        java.util.Map<Integer, int[]> comps = new java.util.HashMap<>();
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            int q = i * nz + k;
            for (int r = colRun[q]; r < colRun[q + 1]; r++) {
                int c = find(parent, r);
                if (c == root) continue;
                int[] e = comps.computeIfAbsent(c, z -> new int[4]);
                if (e[0] == 0) { e[1] = x0 + i; e[2] = ry0[r]; e[3] = z0 + k; }
                e[0] += ry1[r] - ry0[r] + 1;
            }
        }
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int[] e : comps.values()) if (e[0] >= minBlocks) out.add(e);
        out.sort((p, q) -> q[0] - p[0]);
        return out;
    }

    private static int find(int[] parent, int a) {
        while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a]; }
        return a;
    }

    private static void union(int[] parent, int a, int b) {
        a = find(parent, a);
        b = find(parent, b);
        if (a != b) parent[Math.max(a, b)] = Math.min(a, b);
    }

    /** {@link #walkTo} the main component. */
    int[] walkToMain() {
        boolean[] g = new boolean[places];
        for (int p = 0; p < places; p++) g[p] = inMain(p);
        return walkTo(g);
    }

    /** {@link #walkTo} the places with their feet at row {@code y} or higher (out of a flood that tops out under it). */
    int[] walkUpTo(int y) {
        boolean[] g = new boolean[places];
        for (int p = 0; p < places; p++) g[p] = py(p) >= y;
        return walkTo(g);
    }

    // ================================================================================================ sight lines
    /** Ray pitches (degrees), yaws per pitch and the eye's height over the feet, as {@link VoxelHarness}. */
    static final int[] PITCHES = VoxelHarness.SIGHT_PITCHES;
    static final int YAWS = VoxelHarness.SIGHT_YAWS;
    static final double EYE = VoxelHarness.EYE;

    /**
     * The longest view from each main-component place in every {@code every}-th column of [xa, xb) x [za, zb) (feet in
     * rows ya..yb): rays at {@link #PITCHES} and {@link #YAWS} yaws marched block by block to the first rock (and lava, when
     * {@code lavaStops}), or out of rows rayLo..rayHi; a ray that leaves the box counts what it ran inside it. Returns the
     * lengths, one per place, in no particular order.
     */
    double[] longestViews(int every, int xa, int za, int xb, int zb, int ya, int yb, int rayLo, int rayHi, boolean lavaStops, double cap) {
        double[] ys = new double[PITCHES.length], cs = new double[PITCHES.length];
        for (int q = 0; q < ys.length; q++) { ys[q] = Math.sin(Math.toRadians(PITCHES[q])); cs[q] = Math.cos(Math.toRadians(PITCHES[q])); }
        int ia = Math.max(0, xa - x0), ib = Math.min(nx, xb - x0), ka = Math.max(0, za - z0), kb = Math.min(nz, zb - z0);
        int rows = Math.max(0, (ib - ia + every - 1) / every);
        return IntStream.range(0, rows).parallel().mapToObj(r -> {
            double[] out = new double[64];
            int n = 0;
            int i = ia + r * every;
            for (int k = ka; k < kb; k += every) {
                int q = i * nz + k;
                for (int p = colStart[q]; p < colStart[q + 1]; p++) {
                    int x = x0 + i, y = py(p), z = z0 + k;
                    if (!inMain(p) || y < ya || y > yb) continue;
                    double ox = x + 0.5, oy = y + EYE, oz = z + 0.5, best = 0;
                    for (int pi = 0; pi < ys.length; pi++) for (int a = 0; a < YAWS; a++) {
                        double th = Math.toRadians(a * (360.0 / YAWS));
                        best = Math.max(best, ray(ox, oy, oz, cs[pi] * Math.cos(th), ys[pi], cs[pi] * Math.sin(th), cap, rayLo, rayHi, lavaStops));
                    }
                    if (n == out.length) out = Arrays.copyOf(out, n * 2);
                    out[n++] = best;
                }
            }
            return Arrays.copyOf(out, n);
        }).flatMapToDouble(Arrays::stream).toArray();
    }

    /**
     * How far a ray runs from (ox, oy, oz) along the unit direction to the first rock (or lava), out of rows rayLo..rayHi
     * or out of the box.
     */
    double ray(double ox, double oy, double oz, double dx, double dy, double dz, double cap, int rayLo, int rayHi, boolean lavaStops) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double inf = Double.POSITIVE_INFINITY;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        double tx = ax < 1e-12 ? inf : (dx > 0 ? x + 1 - ox : ox - x) / ax, ty = ay < 1e-12 ? inf : (dy > 0 ? y + 1 - oy : oy - y) / ay;
        double tz = az < 1e-12 ? inf : (dz > 0 ? z + 1 - oz : oz - z) / az;
        double ddx = ax < 1e-12 ? inf : 1 / ax, ddy = ay < 1e-12 ? inf : 1 / ay, ddz = az < 1e-12 ? inf : 1 / az;
        double tt = 0;
        while (tt <= cap) {
            if (!in(x, y, z) || y < rayLo || y > rayHi) return tt;
            int m = mat(x, y, z);
            if (m == ROCK || (lavaStops && m == LAVA)) return tt;
            if (tx <= ty && tx <= tz) { x += sx; tt = tx; tx += ddx; }
            else if (ty <= tz) { y += sy; tt = ty; ty += ddy; }
            else { z += sz; tt = tz; tz += ddz; }
        }
        return cap;
    }

    /** The value at quantile q (0..1) of sorted values. */
    static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) return Double.NaN;
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * sorted.length))];
    }
}
