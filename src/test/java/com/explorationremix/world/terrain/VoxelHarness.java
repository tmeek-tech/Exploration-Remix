package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.Kind;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * The blocks one deep layer gets in a box, made exactly as the game makes them: the density at Minecraft's 4 x 8 x 4
 * interpolation corners, trilinear interpolation, {@link Terrain#fine} per block (as {@code DeepFineDensity} does),
 * then rock where the result is positive and {@link Terrain#fluidAt} elsewhere (as {@code NoiseBasedAquiferMixin}
 * does). Then the walk graph over those blocks.
 *
 * <p>A standing place is two open blocks (air or water) over rock, water (swimming) or a climbable block. Moves are
 * two-way only: a step to a neighbour on the same level, one block up with headroom or the step back down, and
 * swimming or climbing straight up and down. A larger drop is one-way and left out, so a component can be walked both
 * ways; {@link #trapComponents} finds the components a player can fall into from the main one but not walk out of.
 *
 * <p>Memory: two bits per block (rock, air, water, lava), one for climbable blocks, and the standing places listed per
 * column and joined with union-find. A 600 x 600 cell-layer takes about 40 MB, 2 x 2 Blue Reaches cells about 110 MB.
 */
final class VoxelHarness {
    static final int ROCK = 0, AIR = 1, WATER = 2, LAVA = 3;
    static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    final Terrain t;
    final int li, x0, y0, z0, nx, ny, nz;
    /** Words per column: materials (32 blocks a word) and climbable bits (64 a word). Columns never share a word. */
    private final int mw, cw;
    private final long[] mat, climb;
    long cornerNs, blockNs;

    // standing places, per column in rising order
    private int[] colStart;
    private short[] placeY;
    /** Per place, its component (the index of the component's first place); and per component, its size. */
    private int[] comp, compSize;
    private int mainComp = -1, components;

    private VoxelHarness(Terrain t, int li, int x0, int z0, int nx, int nz) {
        this.t = t;
        this.li = li;
        this.x0 = x0;
        this.z0 = z0;
        this.nx = nx;
        this.nz = nz;
        this.y0 = t.shape.bottom(li);
        this.ny = t.shape.height(li);
        int nyP = (ny + 63) & ~63;
        mw = nyP / 32;
        cw = nyP / 64;
        mat = new long[nx * nz * mw];
        climb = new long[nx * nz * cw];
    }

    /** Builds the blocks of layer li in [x0, x0 + nx) x [z0, z0 + nz), over the layer's full height. */
    static VoxelHarness build(Terrain t, int li, int x0, int z0, int nx, int nz) {
        VoxelHarness v = new VoxelHarness(t, li, x0, z0, nx, nz);
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
    }

    // ================================================================================================ blocks
    boolean in(int x, int y, int z) {
        return x >= x0 && x < x0 + nx && y >= y0 && y < y0 + ny && z >= z0 && z < z0 + nz;
    }

    /** The material at a block; rock outside the box. */
    int mat(int x, int y, int z) {
        if (!in(x, y, z)) return ROCK;
        int j = y - y0;
        long w = mat[((x - x0) * nz + (z - z0)) * mw + (j >> 5)];
        return (int) (w >>> ((j & 31) << 1)) & 3;
    }

    /** Open for a player: air or water (lava is not). */
    boolean open(int x, int y, int z) {
        int m = mat(x, y, z);
        return m == AIR || m == WATER;
    }

    boolean solid(int x, int y, int z) { return mat(x, y, z) == ROCK; }

    boolean climb(int x, int y, int z) {
        if (!in(x, y, z)) return false;
        int j = y - y0;
        return (climb[((x - x0) * nz + (z - z0)) * cw + (j >> 6)] >>> (j & 63) & 1) != 0;
    }

    private void setClimb(int x, int y, int z) {
        if (!in(x, y, z)) return;
        int j = y - y0;
        climb[((x - x0) * nz + (z - z0)) * cw + (j >> 6)] |= 1L << (j & 63);
    }

    long count(int material) {
        long n = 0;
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) for (int j = 0; j < ny; j++) if (mat(x0 + i, y0 + j, z0 + k) == material) n++;
        return n;
    }

    /** Marks the climb zones of a plan's shafts: vines go on their walls. */
    void markShafts(Net n) {
        for (Net.Shaft s : n.shafts) {
            int xa = (int) Math.floor(s.x - s.r - 1), xb = (int) Math.ceil(s.x + s.r + 1);
            int za = (int) Math.floor(s.z - s.r - 1), zb = (int) Math.ceil(s.z + s.r + 1);
            int ya = (int) Math.floor(s.y0) - 1, yb = (int) Math.ceil(s.y1) + 2;
            for (int x = xa; x <= xb; x++) for (int z = za; z <= zb; z++) {
                if (Math.hypot(x + 0.5 - s.x, z + 0.5 - s.z) > s.r + 0.5) continue;
                for (int y = ya; y <= yb; y++) setClimb(x, y, z);
            }
        }
    }

    /**
     * Shelf vines: wherever a standing place in the given biome has an edge with a drop of 2 to 48 blocks beside it,
     * vines hang from the lip down to the floor below, so the drop can be climbed both ways. Returns the number of
     * vine columns. Call before {@link #connect}.
     */
    int markLipVines(DeepBiome biome) {
        int cols = 0;
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            int x = x0 + i, z = z0 + k;
            for (int j = 1; j < ny - 2; j++) {
                int y = y0 + j;
                if (!place(x, y, z) || !solid(x, y - 1, z)) continue;
                for (int d = 0; d < 4; d++) {
                    int qx = x + DX[d], qz = z + DZ[d];
                    if (!in(qx, y, qz) || !open(qx, y, qz) || !open(qx, y + 1, qz) || !open(qx, y - 1, qz) || !open(qx, y - 2, qz)) continue;
                    int fy = y - 2;
                    while (fy > y0 && open(qx, fy - 1, qz) && y - fy < 49) fy--;
                    if (y - fy > 48 || !solid(qx, fy - 1, qz)) continue;
                    if (t.biomeAt(x, y, z) != biome) continue;
                    for (int yy = fy; yy <= y; yy++) setClimb(qx, yy, qz);
                    cols++;
                }
            }
        }
        return cols;
    }

    // ================================================================================================ the walk graph
    /** A place a player can be, feet in block (x, y, z). */
    boolean place(int x, int y, int z) {
        if (!open(x, y, z) || !open(x, y + 1, z)) return false;
        return solid(x, y - 1, z) || mat(x, y, z) == WATER || mat(x, y - 1, z) == WATER || climb(x, y, z) || climb(x, y - 1, z);
    }

    /** Swimming, or a climbable wall: a vertical link between the place at y and the place over it. */
    boolean vertLink(int x, int y, int z) { return mat(x, y, z) == WATER || climb(x, y, z) || climb(x, y - 1, z); }

    interface Visit { void at(int x, int y, int z); }

    /** Every two-way move from a place. */
    void neighbours(int x, int y, int z, Visit out) {
        for (int d = 0; d < 4; d++) {
            int qx = x + DX[d], qz = z + DZ[d];
            if (place(qx, y, qz)) out.at(qx, y, qz);
            if (open(x, y + 2, z) && place(qx, y + 1, qz)) out.at(qx, y + 1, qz);
            if (open(qx, y + 1, qz) && place(qx, y - 1, qz)) out.at(qx, y - 1, qz);
        }
        if (vertLink(x, y, z) && place(x, y + 1, z)) out.at(x, y + 1, z);
        if (place(x, y - 1, z) && vertLink(x, y - 1, z)) out.at(x, y - 1, z);
    }

    /**
     * Lists the standing places and joins them into components. The main component is the one holding the place
     * nearest {@code (sx, sy, sz)} (within 6 across and 4 up or down), or none when there is no place there.
     */
    void connect(double sx, double sy, double sz) {
        int cols = nx * nz;
        int[] count = new int[cols];
        IntStream.range(0, nx).parallel().forEach(i -> {
            for (int k = 0; k < nz; k++) {
                int x = x0 + i, z = z0 + k, n = 0;
                for (int j = 0; j < ny; j++) if (place(x, y0 + j, z)) n++;
                count[i * nz + k] = n;
            }
        });
        colStart = new int[cols + 1];
        for (int q = 0; q < cols; q++) colStart[q + 1] = colStart[q] + count[q];
        int P = colStart[cols];
        placeY = new short[P];
        IntStream.range(0, nx).parallel().forEach(i -> {
            for (int k = 0; k < nz; k++) {
                int x = x0 + i, z = z0 + k, p = colStart[i * nz + k];
                for (int j = 0; j < ny; j++) if (place(x, y0 + j, z)) placeY[p++] = (short) j;
            }
        });
        int[] parent = new int[P];
        for (int p = 0; p < P; p++) parent[p] = p;
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            int x = x0 + i, z = z0 + k;
            for (int p = colStart[i * nz + k]; p < colStart[i * nz + k + 1]; p++) {
                int y = y0 + placeY[p];
                // the moves to the east and south on one level, every move up (the moves down are their mirrors), and
                // the vertical link up
                union(parent, p, index(x + 1, y, z));
                union(parent, p, index(x, y, z + 1));
                if (open(x, y + 2, z)) for (int d = 0; d < 4; d++) union(parent, p, index(x + DX[d], y + 1, z + DZ[d]));
                if (vertLink(x, y, z)) union(parent, p, index(x, y + 1, z));
            }
        }
        comp = new int[P];
        compSize = new int[P];
        components = 0;
        for (int p = 0; p < P; p++) {
            comp[p] = find(parent, p);
            if (compSize[comp[p]]++ == 0) components++;
        }
        mainComp = -1;
        int[] s = nearestPlace(sx, sy, sz, 6, 4, false);
        if (s != null) mainComp = comp[index(s[0], s[1], s[2])];
    }

    private static int find(int[] parent, int p) {
        while (parent[p] != p) {
            parent[p] = parent[parent[p]];
            p = parent[p];
        }
        return p;
    }

    private static void union(int[] parent, int a, int b) {
        if (b < 0) return;
        int ra = find(parent, a), rb = find(parent, b);
        if (ra == rb) return;
        if (ra < rb) parent[rb] = ra;
        else parent[ra] = rb;
    }

    /** The index of the place with feet at (x, y, z), or -1 when there is none. */
    int index(int x, int y, int z) {
        if (!in(x, y, z)) return -1;
        int col = (x - x0) * nz + (z - z0), j = y - y0;
        for (int p = colStart[col], e = colStart[col + 1]; p < e; p++) {
            if (placeY[p] == j) return p;
            if (placeY[p] > j) return -1;
        }
        return -1;
    }

    long places() { return colStart[nx * nz]; }

    long mainSize() { return mainComp < 0 ? 0 : compSize[mainComp]; }

    double mainShare() { return mainSize() / (double) Math.max(1, places()); }

    int components() { return components; }

    /** {main, all}: the standing places in [xa, xb) x [za, zb), and how many of them are in the main component. */
    long[] placesIn(int xa, int za, int xb, int zb) {
        long all = 0, main = 0;
        for (int x = Math.max(xa, x0); x < Math.min(xb, x0 + nx); x++) for (int z = Math.max(za, z0); z < Math.min(zb, z0 + nz); z++) {
            int col = (x - x0) * nz + (z - z0);
            for (int p = colStart[col]; p < colStart[col + 1]; p++) {
                all++;
                if (comp[p] == mainComp) main++;
            }
        }
        return new long[]{main, all};
    }

    boolean inMain(int x, int y, int z) {
        int p = index(x, y, z);
        return p >= 0 && mainComp >= 0 && comp[p] == mainComp;
    }

    int compOf(int x, int y, int z) {
        int p = index(x, y, z);
        return p < 0 ? -1 : comp[p];
    }

    int compSize(int c) { return c < 0 ? 0 : compSize[c]; }

    /**
     * The place nearest (x, y, z) within rxz across and ry up or down, by dx^2 + dz^2 + 2 dy^2, or null. With
     * {@code mainOnly}, only places of the main component count.
     */
    int[] nearestPlace(double x, double y, double z, int rxz, int ry, boolean mainOnly) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        int[] best = null;
        double bd = 1e18;
        for (int px = bx - rxz; px <= bx + rxz; px++) for (int pz = bz - rxz; pz <= bz + rxz; pz++) {
            if (px < x0 || px >= x0 + nx || pz < z0 || pz >= z0 + nz) continue;
            int col = (px - x0) * nz + (pz - z0);
            for (int p = colStart[col], e = colStart[col + 1]; p < e; p++) {
                int py = y0 + placeY[p];
                if (py < by - ry || py > by + ry) continue;
                if (mainOnly && (mainComp < 0 || comp[p] != mainComp)) continue;
                double d = (px - bx) * (px - bx) + (pz - bz) * (pz - bz) + 2.0 * (py - by) * (py - by);
                if (d < bd) {
                    bd = d;
                    best = new int[]{px, py, pz};
                }
            }
        }
        return best;
    }

    /** Per component, true when a player can fall into it from the main component (in, but no way back on foot). */
    boolean[] trapComponents() {
        boolean[] trap = new boolean[comp.length];
        if (mainComp < 0) return trap;
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            int x = x0 + i, z = z0 + k;
            for (int p = colStart[i * nz + k]; p < colStart[i * nz + k + 1]; p++) {
                if (comp[p] != mainComp) continue;
                int y = y0 + placeY[p];
                for (int d = 0; d < 4; d++) {
                    int qx = x + DX[d], qz = z + DZ[d];
                    if (!open(qx, y, qz) || !open(qx, y + 1, qz) || solid(qx, y - 1, qz)) continue;
                    int fy = y - 1;
                    while (fy > y0 && open(qx, fy - 1, qz) && mat(qx, fy, qz) != WATER) fy--;
                    int c = compOf(qx, fy, qz);
                    if (c >= 0 && c != mainComp) trap[c] = true;
                }
            }
        }
        return trap;
    }

    /**
     * Walking distance (in moves) from each main-component place with feet under {@code safeFeet} to one at or above
     * it. Returns a histogram: within 16, 32, 48, 64, more, unreachable; then the number of places under, and the
     * longest walk.
     */
    int[] surge(int safeFeet) {
        int P = comp.length;
        int[] dist = new int[P];
        Arrays.fill(dist, -1);
        int[] q = new int[P];
        int h = 0, tl = 0;
        int under = 0;
        int[] px = new int[P];
        int[] pz = new int[P];
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++)
            for (int p = colStart[i * nz + k]; p < colStart[i * nz + k + 1]; p++) {
                px[p] = x0 + i;
                pz[p] = z0 + k;
                if (comp[p] != mainComp) continue;
                if (y0 + placeY[p] >= safeFeet) { dist[p] = 0; q[tl++] = p; }
                else under++;
            }
        int[] buf = new int[16];
        int[] nb = new int[1];
        while (h != tl) {
            int p = q[h++];
            nb[0] = 0;
            neighbours(px[p], y0 + placeY[p], pz[p], (ax, ay, az) -> {
                int o = index(ax, ay, az);
                if (o >= 0) buf[nb[0]++] = o;
            });
            for (int s = 0; s < nb[0]; s++) {
                int o = buf[s];
                if (dist[o] >= 0 || comp[o] != mainComp) continue;
                dist[o] = dist[p] + 1;
                q[tl++] = o;
            }
        }
        int[] out = new int[8];
        for (int p = 0; p < P; p++) {
            if (comp[p] != mainComp || y0 + placeY[p] >= safeFeet) continue;
            int d = dist[p];
            if (d < 0) { out[5]++; continue; }
            out[7] = Math.max(out[7], d);
            out[d <= 16 ? 0 : d <= 32 ? 1 : d <= 48 ? 2 : d <= 64 ? 3 : 4]++;
        }
        out[6] = under;
        return out;
    }

    // ================================================================================================ plan checks
    /** A main-component place inside the chamber: near its floor (or its lake's surface), in the middle of it. */
    int[] reach(Chamber c) {
        int r = (int) Math.max(3, Math.min(c.rx, c.rz) * 0.6);
        double y = c.kind == Kind.LAKE && !Double.isNaN(c.lakeY) ? c.lakeY : c.f0 + 1;
        int ry = (int) Math.max(6, c.helix() ? 8 : c.rampDrop + c.rise + 6);
        return nearestPlace(c.x, y + Math.max(0, c.rampDrop * 0.5), c.z, r, ry, true);
    }

    /**
     * The share of the chamber's planned interior that is open: the columns well inside its footprint (e under 0.95)
     * and off its pillars, from a block over its floor to a block under its ceiling. Returns {open, planned}.
     */
    long[] openShare(Network w, Chamber ch) {
        long plan = 0, open = 0;
        int xa = Math.max(x0, (int) Math.floor(ch.x - ch.R())), xb = Math.min(x0 + nx - 1, (int) Math.ceil(ch.x + ch.R()));
        int za = Math.max(z0, (int) Math.floor(ch.z - ch.R())), zb = Math.min(z0 + nz - 1, (int) Math.ceil(ch.z + ch.R()));
        for (int x = xa; x <= xb; x++) for (int z = za; z <= zb; z++) {
            Network.LCol lc = w.layer(w.column(x, z), li);
            for (int k = 0; k < lc.nc; k++) {
                if (lc.c[k] != ch || lc.e[k] >= 0.95) continue;
                if (lc.pr[k] > 0 && lc.pd[k] < lc.pr[k] + 1) continue;
                int ya = (int) Math.ceil(lc.F[k] + 1), yb = (int) Math.floor(lc.C[k] - 1);
                for (int y = ya; y <= yb; y++) {
                    if (!in(x, y, z)) continue;
                    plan++;
                    if (mat(x, y, z) != ROCK) open++;
                }
            }
        }
        return new long[]{open, plan};
    }
}
