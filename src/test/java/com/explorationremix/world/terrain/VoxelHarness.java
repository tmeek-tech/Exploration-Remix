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

    /**
     * Marks the vines of a plan's climbs as {@code ClimbVinesFeature} places them (section 10): one column per stage
     * ({@link Network#vineColumn}), hanging from the first rock over the floor the stage climbs to (searched up to 16
     * blocks over it) down through open air to the stage's foot. Returns the number of stages that got vines.
     */
    int markShafts(Net n) {
        int made = 0;
        for (Net.Shaft s : n.shafts) {
            int[] c = Network.vineColumn(s);
            int foot = Network.vineFoot(s), top = Network.vineTop(s);
            int hold = top + 1;
            while (hold <= top + 16 && in(c[0], hold, c[1]) && mat(c[0], hold, c[1]) == AIR) hold++;
            if (hold > top + 16 || !in(c[0], hold, c[1]) || !solid(c[0], hold, c[1])) continue;
            int y = hold - 1;
            while (y >= foot && mat(c[0], y, c[1]) == AIR) setClimb(c[0], y--, c[1]);
            made++;
        }
        return made;
    }

    /**
     * One-way drops from the main component (section 14.3): from a place standing on rock, a step sideways into a
     * column open at the feet and head with no floor within a block under it, and the fall to the first rock, water,
     * lava or climbable block. Each drop of at least {@code min} as {x, y, z of the place, x and z of the column, the
     * landing's feet y, what it lands on: 0 rock, 1 water, 2 climbable, 3 lava, 4 out of the box}.
     */
    java.util.List<int[]> drops(int min) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            int x = x0 + i, z = z0 + k, col = i * nz + k;
            for (int p = colStart[col]; p < colStart[col + 1]; p++) {
                if (comp[p] != mainComp) continue;
                int y = y0 + placeY[p];
                if (!solid(x, y - 1, z)) continue;
                for (int d = 0; d < 4; d++) {
                    int qx = x + DX[d], qz = z + DZ[d];
                    if (!in(qx, y, qz) || !open(qx, y, qz) || !open(qx, y + 1, qz)) continue;
                    if (place(qx, y, qz) || place(qx, y - 1, qz)) continue;
                    int fy = y, kind;
                    while (true) {
                        if (mat(qx, fy, qz) == WATER) { kind = 1; break; }
                        if (climb(qx, fy, qz)) { kind = 2; break; }
                        if (!in(qx, fy - 1, qz)) { kind = 4; break; }
                        int m = mat(qx, fy - 1, qz);
                        if (m == ROCK) { kind = 0; break; }
                        if (m == LAVA) { kind = 3; break; }
                        fy--;
                    }
                    if (y - fy >= min) out.add(new int[]{x, y, z, qx, qz, fy, kind});
                }
            }
        }
        return out;
    }

    /**
     * Shelf vines as {@code ShelfVinesFeature} places them (section 10). A lip is a place standing on rock, in one of
     * the given biomes, beside a column that is air from a block over its head down to two under its feet. In such a
     * column on the vine lines ({@link Network#shelfVineColumn}), a vine hangs from the first rock over it (at most
     * {@link Network#SHELF_MAX_UP} over the feet) down through the air to the floor or water below, when that lies 2 to
     * {@link Network#SHELF_MAX_DROP} under the feet. Returns the number of vines. Call before {@link #connect}.
     */
    int markShelfVines(DeepBiome... biomes) {
        int vines = 0;
        Network w = t.network;
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            int qx = x0 + i, qz = z0 + k;
            if (!w.shelfVineColumn(qx, qz)) continue;
            int last = Integer.MAX_VALUE;
            for (int j = ny - 3; j >= 2; j--) {
                int y = y0 + j;
                if (y >= last) continue;
                if (mat(qx, y + 1, qz) != AIR || mat(qx, y, qz) != AIR || mat(qx, y - 1, qz) != AIR || mat(qx, y - 2, qz) != AIR) continue;
                boolean lip = false;
                for (int d = 0; d < 4 && !lip; d++) {
                    int x = qx - DX[d], z = qz - DZ[d];
                    if (!in(x, y, z) || !place(x, y, z) || !solid(x, y - 1, z)) continue;
                    DeepBiome b = t.biomeAt(x, y, z);
                    for (DeepBiome bb : biomes) if (b == bb) lip = true;
                }
                if (!lip) continue;
                int fy = y - 2;
                while (fy > y0 && mat(qx, fy - 1, qz) == AIR) fy--;
                int under = mat(qx, fy - 1, qz);
                if (y - fy > Network.SHELF_MAX_DROP || (under != ROCK && under != WATER)) continue;
                int hy = y + 2;
                while (hy < y0 + ny && hy <= y + 1 + Network.SHELF_MAX_UP && mat(qx, hy, qz) == AIR) hy++;
                if (hy >= y0 + ny || mat(qx, hy, qz) != ROCK) continue;
                for (int yy = fy; yy < hy; yy++) setClimb(qx, yy, qz);
                vines++;
                last = fy;
            }
        }
        return vines;
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
        far.clear();
        for (int p = 0; p < P; p++) {
            if (comp[p] != mainComp || y0 + placeY[p] >= safeFeet) continue;
            int d = dist[p];
            if (d < 0) { out[5]++; continue; }
            out[7] = Math.max(out[7], d);
            out[d <= 16 ? 0 : d <= 32 ? 1 : d <= 48 ? 2 : d <= 64 ? 3 : 4]++;
            if (d > 64) far.add(new int[]{px[p], y0 + placeY[p], pz[p], d});
        }
        out[6] = under;
        return out;
    }

    /** After {@link #surge}: the places more than 64 steps from safety, {x, feet, z, steps}. */
    final java.util.List<int[]> far = new java.util.ArrayList<>();

    // ================================================================================================ sight lines
    /** Ray pitches (degrees) of the sight-line check, and the number of yaws per pitch (every 15 degrees). */
    static final int[] SIGHT_PITCHES = {-40, -30, -20, -10, 0, 10, 20, 30, 40};
    static final int SIGHT_YAWS = 24;
    /** Eye height over the feet. */
    static final double EYE = 1.62;
    /** A view longer than this (blocks) shows the sky past the render distance under the shader. */
    static final double LONG = 160;

    /** What the sight-line check saw. */
    static final class Sight {
        /** left: rays that left the box first; leftLong: those of them that had run more than {@link #LONG} by then. */
        long places, rays, left, leftLong;
        /** The places with a view longer than {@link #LONG} (a ray that left the box after that counts): {x, y, z}. */
        final java.util.List<int[]> longPlaces = new java.util.ArrayList<>();
        /** Ray lengths in 10-block bins; the last bin holds 170 and more (up to the cap). */
        final long[] hist = new long[18];
        double max;
        /** The longest rays, longest first: {eye x, y, z, yaw, pitch, length, end x, y, z}. */
        final java.util.List<double[]> longest = new java.util.ArrayList<>();

        long over(double len) {
            long n = 0;
            for (int b = (int) Math.ceil(len / 10); b < hist.length; b++) n += hist[b];
            return n;
        }

        void add(Sight o, int keep) {
            places += o.places;
            rays += o.rays;
            left += o.left;
            leftLong += o.leftLong;
            longPlaces.addAll(o.longPlaces);
            for (int b = 0; b < hist.length; b++) hist[b] += o.hist[b];
            max = Math.max(max, o.max);
            longest.addAll(o.longest);
            longest.sort((a, b) -> Double.compare(b[5], a[5]));
            while (longest.size() > keep) longest.remove(longest.size() - 1);
        }
    }

    /**
     * Sight lines: from the eyes of main-component places standing on rock, in every {@code every}-th column along x and
     * z, rays at the pitches of {@link #SIGHT_PITCHES} and 24 yaws are marched block by block to the first rock (the
     * bands over and under the layer are rock). A ray that leaves the box first is counted apart, and as a long view
     * when it had run more than {@link #LONG} by then. Rays are followed up to {@code cap}; the {@code keep} longest are
     * listed.
     */
    Sight sight(int every, double cap, int keep) { return sight(every, cap, keep, x0, z0, x0 + nx, z0 + nz); }

    /** {@link #sight(int, double, int)} from the places in the columns xa..xb-1, za..zb-1 only (their rays run on anywhere in the box). */
    Sight sight(int every, double cap, int keep, int xa, int za, int xb, int zb) {
        double[] ys = new double[SIGHT_PITCHES.length], cs = new double[SIGHT_PITCHES.length];
        for (int q = 0; q < ys.length; q++) { ys[q] = Math.sin(Math.toRadians(SIGHT_PITCHES[q])); cs[q] = Math.cos(Math.toRadians(SIGHT_PITCHES[q])); }
        int ia = Math.max(0, xa - x0), ib = Math.min(nx, xb - x0), ka = Math.max(0, za - z0), kb = Math.min(nz, zb - z0);
        int rows = Math.max(0, (ib - ia + every - 1) / every);
        return IntStream.range(0, rows).parallel().mapToObj(r -> {
            Sight s = new Sight();
            int i = ia + r * every;
            for (int k = ka; k < kb; k += every) {
                int x = x0 + i, z = z0 + k, col = i * nz + k;
                for (int p = colStart[col]; p < colStart[col + 1]; p++) {
                    if (comp[p] != mainComp) continue;
                    int y = y0 + placeY[p];
                    if (!solid(x, y - 1, z)) continue;
                    s.places++;
                    boolean far = false;
                    double ox = x + 0.5, oy = y + EYE, oz = z + 0.5;
                    for (int q = 0; q < ys.length; q++) for (int a = 0; a < SIGHT_YAWS; a++) {
                        double yaw = a * (360.0 / SIGHT_YAWS), th = Math.toRadians(yaw);
                        double dx = cs[q] * Math.cos(th), dy = ys[q], dz = cs[q] * Math.sin(th);
                        double d = ray(ox, oy, oz, dx, dy, dz, cap);
                        s.rays++;
                        if (d < 0) {
                            s.left++;
                            if (-1 - d > LONG) { s.leftLong++; far = true; }
                            continue;
                        }
                        if (d > LONG) far = true;
                        s.hist[Math.min(s.hist.length - 1, (int) (d / 10))]++;
                        s.max = Math.max(s.max, d);
                        if (keep > 0 && (s.longest.size() < keep || d > s.longest.get(s.longest.size() - 1)[5])) {
                            s.longest.add(new double[]{ox, oy, oz, yaw, SIGHT_PITCHES[q], d, ox + dx * d, oy + dy * d, oz + dz * d});
                            s.longest.sort((u, v) -> Double.compare(v[5], u[5]));
                            if (s.longest.size() > keep) s.longest.remove(s.longest.size() - 1);
                        }
                    }
                    if (far) s.longPlaces.add(new int[]{x, y, z});
                }
            }
            return s;
        }).reduce(new Sight(), (u, v) -> { Sight m = new Sight(); m.add(u, keep); m.add(v, keep); return m; });
    }

    /**
     * The distance from (ox, oy, oz) along the unit direction (dx, dy, dz) to the first rock block it enters; the cap
     * when it meets none that near; -1 - t when it leaves the box across a side first, after running t. Over and under
     * the layer is rock.
     */
    double ray(double ox, double oy, double oz, double dx, double dy, double dz, double cap) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double inf = Double.POSITIVE_INFINITY;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        double tx = ax < 1e-12 ? inf : (dx > 0 ? x + 1 - ox : ox - x) / ax, ty = ay < 1e-12 ? inf : (dy > 0 ? y + 1 - oy : oy - y) / ay;
        double tz = az < 1e-12 ? inf : (dz > 0 ? z + 1 - oz : oz - z) / az;
        double ddx = ax < 1e-12 ? inf : 1 / ax, ddy = ay < 1e-12 ? inf : 1 / ay, ddz = az < 1e-12 ? inf : 1 / az;
        double t = 0;
        while (t <= cap) {
            if (y < y0 || y >= y0 + ny) return t;
            if (x < x0 || x >= x0 + nx || z < z0 || z >= z0 + nz) return -1 - t;
            if (mat(x, y, z) == ROCK) return t;
            if (tx <= ty && tx <= tz) { x += sx; t = tx; tx += ddx; }
            else if (ty <= tz) { y += sy; t = ty; ty += ddy; }
            else { z += sz; t = tz; tz += ddz; }
        }
        return cap;
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
