package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * The Basalt Galleries (layer 3) as a noise field: nothing planned, nothing placed. Every block is a pure function of
 * the seed and its position, so any number of worldgen threads can call in, and there are no seams.
 *
 * <p>How it is built. The layer is cut into basalt column cells: a jittered hexagonal lattice about 3 blocks across
 * (5 in the Causeway). Every 2D field (the climate, the massifs, the floor, the ceiling, the hanging clusters, the
 * spires, the rock bands) is read at the cell's site only, so every block of a cell has the same vertical profile:
 * floors are column tops, ceilings are hanging column ends, walls are column faces. A cell's profile is the open rows
 * {@code [f, c)} less the rock of a band {@code [bl, bh)}. Below the lava table W every open block is lava.
 *
 * <p>Minecraft samples the density at 4 x 8 x 4 corners and interpolates; the per-block pass ({@link #fine}) then
 * puts back the exact profile, block by block, from the cached cell (a few comparisons a block).
 *
 * <p><b>Interim layout.</b> The cavern's layout here (domes on a cellular noise, pinching to windows and walls at their
 * edges) is the first version; a second replaces it. All of it is the 2D shape at a cell's site, {@code shape} with its
 * helpers ({@code dome}, {@code edgeClass}, {@code patchTerrain}, {@code landscape}), turned into the cell's profile by
 * {@code build}; keep the shape code there. What stays is everything round it: the public methods, the column cells,
 * the climate, the tube hook ({@link #tubesAt}), the join hook ({@link Join}) and the look queries.
 */
public final class GalleriesField {
    // ============================================================================================ layout
    /** Rows of rock kept over the bedrock and under the layer above (Terrain.SEAL). */
    static final int SEAL = 4;
    final long seed;
    /** Bottom row, top row and the lava table (the first row above the lava) of the layer. */
    final int B, T, W;
    /** The lowest and highest rows that may be open (without the join). */
    final int openLo, openHi;

    // ============================================================================================ column cells
    /** Column cell spacing (blocks) and the coarse spacing of the Causeway's prisms. */
    static final double CS = 3.0, CS_COARSE = 5.0;
    /** Jitter of a cell's site, as a share of the spacing. */
    static final double JIT = 0.4;

    // ============================================================================================ noise
    final Perlin warpA, warpB, elevN, detailN, riverN, riverW, ceilN, ceilN2, bandN, bandMask, bandH, pierN, canN, canW, causN, passN,
            hillN;

    /** The join with the Wasteland above (breakthrough noise and band top); null: no join (the seal stays). */
    public interface Join {
        /**
         * 0 .. 1 over column (x, z): how far the Galleries' ceiling rises into the join (0: it stops 4 rows under the top,
         * 1: it reaches {@link #bandTop}). From the Wasteland's breakthrough noise: smooth((beta - 0.35) / 0.25).
         */
        double breakthrough(double x, double z);
        /** The highest open row of the Galleries' space where the breakthrough is 1 (the layer's top + 6). */
        int bandTop();
    }

    final Join join;
    /** The Obsidian Tubes' field (its own region noise), or null (no tubes). */
    final TubeField tubes;

    public GalleriesField(long seed, int bottom, int top, int lavaTable, TubeField tubes, Join join) {
        this.seed = seed;
        this.tubes = tubes;
        this.B = bottom;
        this.T = top;
        this.W = lavaTable;
        this.openLo = B + SEAL;
        this.openHi = T - SEAL;
        this.join = join;
        long s = seed ^ 0x5851F42D4C957F2DL;
        warpA = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpB = new Perlin(Hashing.mix(s, 2, 0, 0));
        elevN = new Perlin(Hashing.mix(s, 4, 0, 0));
        detailN = new Perlin(Hashing.mix(s, 8, 0, 0));
        riverN = new Perlin(Hashing.mix(s, 9, 0, 0));
        riverW = new Perlin(Hashing.mix(s, 10, 0, 0));
        ceilN = new Perlin(Hashing.mix(s, 11, 0, 0));
        ceilN2 = new Perlin(Hashing.mix(s, 12, 0, 0));
        bandN = new Perlin(Hashing.mix(s, 13, 0, 0));
        bandMask = new Perlin(Hashing.mix(s, 14, 0, 0));
        bandH = new Perlin(Hashing.mix(s, 15, 0, 0));
        pierN = new Perlin(Hashing.mix(s, 16, 0, 0));
        canN = new Perlin(Hashing.mix(s, 17, 0, 0));
        canW = new Perlin(Hashing.mix(s, 18, 0, 0));
        causN = new Perlin(Hashing.mix(s, 20, 0, 0));
        passN = new Perlin(Hashing.mix(s, 24, 0, 0));
        hillN = new Perlin(Hashing.mix(s, 27, 0, 0));
    }

    // ============================================================================================ helpers
    static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    static double smooth(double t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Terraces of the given step: flat for {@code flat} of each step, then a steep rise. */
    static double terrace(double v, double step, double flat) {
        double k = Math.floor(v / step), fr = v / step - k;
        return step * (k + smooth((fr - flat) / (1 - flat)));
    }

    /**
     * Distance (blocks) from (x, z) to the zero line of a noise field n(x / p, z / p): |n| over its gradient, a first
     * order estimate, exact on straight stretches. The gradient is taken by central differences 1 block apart.
     */
    static double lineDist(Perlin n, double x, double z, double p) {
        double v = n.noise2(x / p, z / p);
        double gx = (n.noise2((x + 1) / p, z / p) - n.noise2((x - 1) / p, z / p)) * 0.5;
        double gz = (n.noise2(x / p, (z + 1) / p) - n.noise2(x / p, (z - 1) / p)) * 0.5;
        double g = Math.sqrt(gx * gx + gz * gz);
        return Math.abs(v) / Math.max(g, 1e-5);
    }

    /**
     * The large shapes' domain warp at (x, z): out = {wx, wz, j11, j12, j21, j22}, the warped point and the warp's
     * Jacobian (d wx/dx, d wx/dz, d wz/dx, d wz/dz) by central differences a block apart. Distances measured in the
     * warped plane are turned back into blocks with it, so slopes stay what the constants say.
     */
    void warp(double x, double z, double[] out) {
        out[0] = x + WARP_A * warpA.noise2(x / WARP_P, z / WARP_P);
        out[1] = z + WARP_A * warpB.noise2(x / WARP_P, z / WARP_P);
        double a = WARP_A * 0.5;
        out[2] = 1 + a * (warpA.noise2((x + 1) / WARP_P, z / WARP_P) - warpA.noise2((x - 1) / WARP_P, z / WARP_P));
        out[3] = a * (warpA.noise2(x / WARP_P, (z + 1) / WARP_P) - warpA.noise2(x / WARP_P, (z - 1) / WARP_P));
        out[4] = a * (warpB.noise2((x + 1) / WARP_P, z / WARP_P) - warpB.noise2((x - 1) / WARP_P, z / WARP_P));
        out[5] = 1 + a * (warpB.noise2(x / WARP_P, (z + 1) / WARP_P) - warpB.noise2(x / WARP_P, (z - 1) / WARP_P));
    }

    /** The length in blocks of a warped-plane gradient (gx, gz) at a point whose warp Jacobian is in w[2..5]. */
    static double realGrad(double gx, double gz, double[] w) {
        double rx = w[2] * gx + w[4] * gz, rz = w[3] * gx + w[5] * gz;
        return Math.sqrt(rx * rx + rz * rz);
    }

    /** Distance in blocks to the zero line of n(wx / p, wz / p) through the warp w (see {@link #warp}). */
    static double lineDistW(Perlin n, double[] w, double p) {
        double x = w[0], z = w[1];
        double v = n.noise2(x / p, z / p);
        double gx = (n.noise2((x + 1) / p, z / p) - n.noise2((x - 1) / p, z / p)) * 0.5;
        double gz = (n.noise2(x / p, (z + 1) / p) - n.noise2(x / p, (z - 1) / p)) * 0.5;
        return Math.abs(v) / Math.max(realGrad(gx, gz, w), 1e-5);
    }

    // ============================================================================================ climate
    /** Sub-biome kinds of the climate patches. */
    static final int G = 0, CALDERA = 1, CAUSEWAY = 2, CANYONS = 3;
    /** Patch lattice spacing (blocks); each lattice cell holds at most one sub-biome patch. */
    static final double SP = 250;
    static final double PATCH_R_MIN = 0.32, PATCH_R_MAX = 0.42, PATCH_P = 0.92, PATCH_BLEND = 0.28;

    /** Climate at a column: the weights of the three patch kinds (the rest is the main galleries) and the patch ids. */
    static final class Climate {
        double wCal, wCaus, wCan;
        /** Normalised distance from the strongest patch's centre (0 centre, 1 rim) and that patch's id. */
        double dn = 9;
        long patch;
        int kind;

        double wMain() { return Math.max(0, 1 - wCal - wCaus - wCan); }
    }

    /** Lowland patches (Caldera, Causeway) where the landscape at the patch's middle is under E_LOW, Canyons up to E_HIGH. */
    static final double E_LOW = -0.16, E_HIGH = -0.05, CAN_SHARE = 0.45;

    /** A climate patch: lattice cell (i, j), kind (G: none), middle (in the warped plane), radius, hash. Immutable. */
    static final class Patch {
        int i, j, kind;
        double cx, cz, r;
        long h;
    }

    /**
     * The patch of lattice cell (i, j), from a small per-thread cache. Its kind follows the landscape at its middle, so
     * the basins sit in low ground and the canyons are cut into high ground, and their shores stay short and gentle: a
     * lowland patch is a Caldera or a Causeway (hashed), a highland one Scoria Canyons, and the ground between has none.
     */
    Patch patch(int i, int j) {
        Patch[] pc = patches.get();
        int slot = (i & 7) << 3 | (j & 7);
        Patch p = pc[slot];
        if (p != null && p.i == i && p.j == j) return p;
        p = new Patch();
        p.i = i;
        p.j = j;
        long h = Hashing.mix(seed, 31001, i, j);
        p.h = h;
        p.cx = (i + 0.5 + 0.3 * (Hashing.unit(Hashing.mix(h, 1, 0, 0)) - 0.5)) * SP;
        p.cz = (j + 0.5 + 0.3 * (Hashing.unit(Hashing.mix(h, 2, 0, 0)) - 0.5)) * SP;
        p.r = SP * Hashing.range(Hashing.mix(h, 3, 0, 0), PATCH_R_MIN, PATCH_R_MAX);
        if (Hashing.unit(h) > PATCH_P) p.kind = G;
        else {
            double e = elevN.fbm2(p.cx / ELEV_P, p.cz / ELEV_P, 2);
            double u2 = Hashing.unit(Hashing.mix(h, 7, 0, 0));
            p.kind = e < E_LOW ? (u2 < 0.5 ? CALDERA : CAUSEWAY) : (e > E_HIGH && u2 < CAN_SHARE ? CANYONS : G);
        }
        pc[slot] = p;
        return p;
    }

    private final ThreadLocal<Patch[]> patches = ThreadLocal.withInitial(() -> new Patch[64]);

    void climate(double x, double z, Climate out) {
        double wx = x + WARP_A * warpA.noise2(x / WARP_P, z / WARP_P), wz = z + WARP_A * warpB.noise2(x / WARP_P, z / WARP_P);
        int gi = (int) Math.floor(wx / SP), gj = (int) Math.floor(wz / SP);
        out.wCal = out.wCaus = out.wCan = 0;
        out.dn = 9;
        out.patch = 0;
        out.kind = G;
        double bestW = 0;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            Patch p = patch(gi + di, gj + dj);
            if (p.kind == G) continue;
            double d = Math.hypot(wx - p.cx, wz - p.cz) / p.r;
            if (d >= 1) continue;
            double w = smooth((1 - d) / PATCH_BLEND);
            switch (p.kind) {
                case CALDERA -> out.wCal = Math.max(out.wCal, w);
                case CAUSEWAY -> out.wCaus = Math.max(out.wCaus, w);
                default -> out.wCan = Math.max(out.wCan, w);
            }
            if (w > bestW || (w == bestW && d < out.dn)) {
                bestW = w;
                out.dn = d;
                out.patch = p.h;
                out.kind = w >= 0.5 ? p.kind : G;
            }
        }
        double sum = out.wCal + out.wCaus + out.wCan;
        if (sum > 1) { out.wCal /= sum; out.wCaus /= sum; out.wCan /= sum; }
    }

    // ============================================================================================ cells
    /**
     * One basalt column cell and its vertical profile. Open rows are {@code [f, c)} less the band's rock {@code [bl, bh)};
     * {@code f >= c} is a closed cell. Read-only once built.
     */
    public static final class Cell {
        public int i, j;
        public boolean coarse;
        public double x, z;
        long h;
        /** First open row over the floor, first rock row of the ceiling. */
        public int f, c;
        /** A rock band (bridge, arch or rib) inside the open rows: rock in [bl, bh); bl == bh: none. */
        public int bl, bh;
        public int kind;
        public float wCal, wCaus, wCan, open;
        /** How far the ceiling hangs down at this cell (a hanging cluster), and the spire's rise from the floor. */
        public float hang, rise;
        /** A floor-to-ceiling pillar (a hanging cluster meeting a spire or the floor). */
        public boolean pillar;
        /** Rows within reach of open air in a neighbouring cell, as [lo, hi) pairs: for the density's magnitude. */
        int[] near;
        int nearN;

        public boolean closed() { return f >= c; }

        public boolean open(int y) { return y >= f && y < c && (y < bl || y >= bh); }
    }

    /** The site of lattice cell (i, j): a hexagonal lattice (odd rows shifted half a cell), jittered. */
    void site(boolean coarse, int i, int j, double[] out) {
        double s = coarse ? CS_COARSE : CS, rowH = s * 0.8660254;
        long h = Hashing.mix(seed, coarse ? 32002 : 32001, i, j);
        out[0] = (i + ((j & 1) != 0 ? 0.5 : 0) + JIT * (Hashing.unit(h) - 0.5)) * s;
        out[1] = (j + JIT * (Hashing.unit(Hashing.mix(h, 1, 0, 0)) - 0.5)) * rowH;
    }

    /**
     * The lattice cell holding column (x, z): {i, j} of the nearest of the 9 sites round it. Returns the distance from
     * (x, z) to the nearest joint with a neighbouring cell (the bisector with the nearest other site; 0 on the joint).
     */
    double cellOf(boolean coarse, double x, double z, int[] out, Caches k) {
        double s = coarse ? CS_COARSE : CS, rowH = s * 0.8660254;
        double[] tmp = k.tmp, sx = k.sx, sz = k.sz;
        int j0 = (int) Math.floor(z / rowH);
        double best = 1e18, x1 = 0, z1 = 0;
        int n = 0;
        for (int j = j0 - 1; j <= j0 + 1; j++) {
            int i0 = (int) Math.floor(x / s - ((j & 1) != 0 ? 0.5 : 0));
            for (int i = i0 - 1; i <= i0 + 1; i++) {
                site(coarse, i, j, tmp);
                sx[n] = tmp[0];
                sz[n] = tmp[1];
                double dx = x - tmp[0], dz = z - tmp[1], d = dx * dx + dz * dz;
                if (d < best) { best = d; out[0] = i; out[1] = j; x1 = tmp[0]; z1 = tmp[1]; }
                n++;
            }
        }
        double e = 1e9;
        for (int q = 0; q < n; q++) {
            double ex = sx[q] - x1, ez = sz[q] - z1, len = Math.sqrt(ex * ex + ez * ez);
            if (len < 1e-9) continue;
            double d2 = (x - sx[q]) * (x - sx[q]) + (z - sz[q]) * (z - sz[q]);
            e = Math.min(e, (d2 - best) / (2 * len));
        }
        return e;
    }

    // ------------------------------------------------------------------------------------------- caches
    private static final int CELL_CACHE = 4096, COL_CACHE = 1024;

    private static final class Caches {
        final Cell[] cells = new Cell[CELL_CACHE];
        final int[] colX = new int[COL_CACHE], colZ = new int[COL_CACHE];
        final Cell[] col = new Cell[COL_CACHE];
        /** The column's distance to its cell's nearest joint (see {@link #cellOf}). */
        final double[] colE = new double[COL_CACHE];
        /** The column's cone distance ({@link #cone(int, int)}), NaN until first asked. */
        final double[] colCone = new double[COL_CACHE];
        /** The top of the column's cone ground ({@link #coneTop(int, int)}), NaN until first asked. */
        final double[] colConeTop = new double[COL_CACHE];
        final int[] ij = new int[2];
        final double[] tmp = new double[2], sx = new double[9], sz = new double[9];
        final Climate cl = new Climate();
        final Shape0 sh = new Shape0();
        Caches() { java.util.Arrays.fill(colX, Integer.MIN_VALUE); }
    }

    private final ThreadLocal<Caches> caches = ThreadLocal.withInitial(Caches::new);

    /** The cell of lattice (coarse, i, j), from the thread's cache or built. */
    Cell cell(boolean coarse, int i, int j) {
        Caches k = caches.get();
        int slot = ((i & 63) << 6 | (j & 63)) ^ (coarse ? 0x800 : 0);
        Cell c = k.cells[slot];
        if (c != null && c.i == i && c.j == j && c.coarse == coarse) return c;
        c = build(coarse, i, j, k);
        k.cells[slot] = c;
        return c;
    }

    /** The cell over column (x, z) (cached per thread, 32 x 32 columns). */
    public Cell column(int x, int z) {
        Caches k = caches.get();
        int slot = (x & 31) << 5 | (z & 31);
        if (k.colX[slot] == x && k.colZ[slot] == z) return k.col[slot];
        boolean coarse = coarseAt(x + 0.5, z + 0.5, k);
        double e = cellOf(coarse, x + 0.5, z + 0.5, k.ij, k);
        Cell c = cell(coarse, k.ij[0], k.ij[1]);
        if (c.near == null) nearOf(c);
        k.colX[slot] = x;
        k.colZ[slot] = z;
        k.col[slot] = c;
        k.colE[slot] = e;
        k.colCone[slot] = Double.NaN;
        k.colConeTop[slot] = Double.NaN;
        return c;
    }

    /** The Causeway's coarse prisms where its weight passes a half. */
    boolean coarseAt(double x, double z, Caches k) {
        climate(x, z, k.cl);
        return k.cl.wCaus > 0.5;
    }

    // ============================================================================================ the profile
    /** Scratch for one cell's shape. */
    static final class Shape0 {
        double F, C, open, gap, hang, rise, jt;
        boolean pillar;
        final double[] ch = new double[2], w = new double[6];
        final DomeScratch sc = new DomeScratch();
    }

    /** Hanging clusters: grid spacing, chance per grid cell, radius range. */
    static final double HANG_GRID = 26, HANG_P = 0.6, HANG_R0 = 5, HANG_R1 = 13;
    /** Spires standing on the floor. */
    static final double SPIRE_GRID = 24, SPIRE_P = 0.6, SPIRE_R0 = 3.5, SPIRE_R1 = 8;
    /** Caldera cones. */
    static final double CONE_GRID = 30, CONE_P = 0.8;
    /** How far the cones sink where the Caldera fades out (its weight under 0.7). */
    static final double CONE_SINK = 80;

    // ---- the landscape
    /** Domain warp of the large shapes: amplitude and period (blocks). */
    static final double WARP_A = 34, WARP_P = 130;
    /** The landscape's elevation: period, mean and amplitude over W (blocks). Its slopes stay under about 0.3. */
    static final double ELEV_P = 700, ELEV_MID = 52, ELEV_AMP = 85;
    /** Hills in the low ground: where a noise of period HILL_P passes HILL_T the land rises HILL_A x the excess. */
    static final double HILL_A = 110, HILL_P = 280, HILL_T = 0.05;
    /** Lava rivers: the zero lines of a noise of this period; their bed rises out of the lava as the land rises. */
    static final double RIVER_P = 430, RIVER_BANK = 0.22, RIVER_RISE = 3.0, RIVER_LOW = 22;
    // ---- the domes
    /** Domes: a cellular (Worley) noise of this spacing and jitter; the vault pinches down over PINCH_R of a cell edge. */
    static final double DOME_D = 80, DOME_JIT = 0.8, PINCH_R = 24;
    /** Head room over the landscape at a dome's middle. */
    static final double HEAD_MIN = 55, HEAD_MAX = 84;
    /** Head room at an edge: GAP0 + GAP1 x pass noise (period PASS_P) + the edge's class (merged, wall or plain). */
    static final double GAP0 = 4, GAP1 = 12, PASS_P = 46;
    /** The floor rises SADDLE rows into a pinch, so a pass is a window over a low saddle, not a slot at floor level. */
    static final double SADDLE = 4;
    static final double P_WALL = 0.35, WALL_OFF = -40;
    /** At the foot of a closed edge the floor rises (at most WALL_RISE rows). */
    static final double WALL_RISE = 8;
    /** Walls fade out below W + WALL_LOW (over 30 rows). */
    static final double WALL_LOW = 15;
    /** Over lava a pinch's head room shrinks LAVA_PINCH rows for each row of lava under it. */
    static final double LAVA_PINCH = 4;
    // ---- the sub-biomes' terrain
    /** Caldera: a lava lake (LAKE x the patch radius) in a basin. */
    static final double CAL_LAKE = 0.5;
    /** Causeway: flats of prisms in the lava (FLAT x radius) in a basin. */
    static final double CAUS_FLAT = 0.6;
    /**
     * Scoria Canyons: lava slots (W - 4) along the zero lines of a noise of period CAN_P, sheer walls up to W + CAN_DRY,
     * then stepped cliffs of CAN_STEP rows up to the high ground; they fade out with their patch's weight.
     */
    static final double CAN_P = 160, CAN_STEP = 5, CAN_DRY = 40;
    /** Slope of the basins' shores: column stairs, 1 row in 3 to 4 blocks. */
    static final double SHORE_S = 0.22;
    /** A quarter of the cells (hashed) stand this many rows higher: ragged column tops. */
    static final double FLOOR_OFF = 1;

    private Cell build(boolean coarse, int i, int j, Caches k) {
        Cell c = new Cell();
        c.i = i;
        c.j = j;
        c.coarse = coarse;
        site(coarse, i, j, k.tmp);
        double x = k.tmp[0], z = k.tmp[1];
        c.x = x;
        c.z = z;
        c.h = Hashing.mix(seed, coarse ? 33002 : 33001, i, j);
        Climate cl = k.cl;
        climate(x, z, cl);
        c.kind = cl.kind;
        c.wCal = (float) cl.wCal;
        c.wCaus = (float) cl.wCaus;
        c.wCan = (float) cl.wCan;
        Shape0 s = k.sh;
        if (tubesAt(x, z) && tubes.massifAt(x, z) > 0) {
            // a tube country's massif: rock from floor to vault (the tubes are carved in it by the per-block pass)
            c.f = c.c = W + 200;
            return c;
        }
        shape(x, z, cl, s, c.h);
        c.open = (float) s.open;
        c.hang = (float) s.hang;
        c.rise = (float) s.rise;
        c.pillar = s.pillar;
        int f, ce;
        if (s.pillar || s.C - s.F < 2.5) {
            f = ce = W + 200;
        } else {
            // column ends: the floor's top stands a row higher on a quarter of the cells, the ceiling's ends hang 0-3 lower
            double of = (c.h & 3) == 0 ? FLOOR_OFF : 0;
            double oc = (c.h >>> 8 & 3);
            f = (int) Math.floor(s.F + of + 0.5);
            ce = (int) Math.floor(s.C - oc + 0.5);
            int hi = openHi + 1;
            if (s.jt > 0) hi = (int) Math.round(lerp(openHi + 1, join.bandTop() + 1, s.jt));
            f = Math.max(f, openLo);
            ce = Math.min(ce, hi);
            if (ce - f < 2) f = ce = W + 200;
        }
        c.f = f;
        c.c = ce;
        c.bl = c.bh = 0;
        if (f < ce) band(c, x, z, cl);
        return c;
    }

    /** Scratch for {@link #dome}: the 5 x 5 sites round a point. */
    static final class DomeScratch {
        final double[] x = new double[25], z = new double[25];
        final long[] h = new long[25];
    }

    /**
     * The dome noise at warped point (wx, wz): out[0] the distance to the nearest edge of the cellular noise (blocks, the
     * exact distance to the bisector with the nearest other site), out[1] the edge class's head-room offset, blended
     * across the edges' corners so it never jumps.
     */
    void dome(double wx, double wz, double[] out, DomeScratch sc) {
        int ci = (int) Math.floor(wx / DOME_D), cj = (int) Math.floor(wz / DOME_D);
        int n = 0, best = 0;
        double bd = 1e30;
        for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
            long h = Hashing.mix(seed, 37001, ci + di, cj + dj);
            double sx = (ci + di + 0.5 + DOME_JIT * (Hashing.unit(Hashing.mix(h, 1, 0, 0)) - 0.5)) * DOME_D;
            double sz = (cj + dj + 0.5 + DOME_JIT * (Hashing.unit(Hashing.mix(h, 2, 0, 0)) - 0.5)) * DOME_D;
            sc.x[n] = sx; sc.z[n] = sz; sc.h[n] = h;
            double d = (wx - sx) * (wx - sx) + (wz - sz) * (wz - sz);
            if (d < bd) { bd = d; best = n; }
            n++;
        }
        double x1 = sc.x[best], z1 = sc.z[best];
        long h1 = sc.h[best];
        double e1 = 1e30, e2 = 1e30;
        long k1 = 0, k2 = 0;
        for (int j = 0; j < n; j++) {
            if (j == best) continue;
            double ex = sc.x[j] - x1, ez = sc.z[j] - z1, len = Math.sqrt(ex * ex + ez * ez);
            double e = -((wx - (x1 + sc.x[j]) * 0.5) * ex + (wz - (z1 + sc.z[j]) * 0.5) * ez) / len;
            if (e < e1) { e2 = e1; k2 = k1; e1 = e; k1 = sc.h[j]; }
            else if (e < e2) { e2 = e; k2 = sc.h[j]; }
        }
        double o1 = edgeClass(h1, k1), o2 = edgeClass(h1, k2);
        double t = 0.5 * (1 - smooth((e2 - e1) / 12.0));
        out[0] = e1;
        out[1] = o1 + (o2 - o1) * t;
    }

    /** The class of the edge between two domes: 1 a wall (chance P_WALL), 0 plain (its passes open by noise). */
    double edgeClass(long a, long b) {
        double u = Hashing.unit(Hashing.mix(seed, 37002, Math.min(a, b), Math.max(a, b)));
        return u < P_WALL ? 1 : 0;
    }

    /**
     * The lowland sub-biomes' basins on the landscape: Caldera (W - 4) and Causeway (W - 1) flats out to CAL_LAKE and
     * CAUS_FLAT of the patch radius, their shores rising SHORE_S a block (real blocks through the warp), the lowest of the
     * 5 x 5 patches round the point (so no seam). Fs is the landscape at the column, w its warp (see {@link #warp}).
     */
    double patchTerrain(double Fs, double[] w) {
        double px = w[0], pz = w[1];
        int gi = (int) Math.floor(px / SP), gj = (int) Math.floor(pz / SP);
        double lo = Fs;
        for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
            Patch p = patch(gi + di, gj + dj);
            if (p.kind == G) continue;
            double d = Math.hypot(px - p.cx, pz - p.cz);
            if (p.kind == CANYONS) continue;
            double q = Math.max(0, d - p.r * (p.kind == CALDERA ? CAL_LAKE : CAUS_FLAT));
            if (q * SHORE_S > 110) continue;
            if (q > 0) q /= Math.max(0.3, realGrad((px - p.cx) / d, (pz - p.cz) / d, w));   // warped distance to blocks
            lo = Math.min(lo, (p.kind == CALDERA ? W - 4 : W - 1) + SHORE_S * q);
        }
        return lo;
    }

    /**
     * The 2D shape at a cell's site: floor F and ceiling C (rows, unrounded), openness, hanging length, spire rise.
     *
     * <p>The floor is a landscape: a gentle elevation noise (slopes under about 0.3, so 1-row column stairs climb it),
     * with the lowland basins and hills, lava rivers cut along the zero lines of a noise where the land is low, and
     * fine relief. The vault is a dome over each cell of a cellular noise, its head room over the landscape pinched
     * down at the cells' edges: to a wall where the edge's head room is under zero, to a pass where a noise opens it.
     * The pinches keep views short; the passes keep the space one.
     */
    void shape(double x, double z, Climate cl, Shape0 out, long cellHash) {
        double[] w = out.w;
        warp(x, z, w);
        double wx = w[0], wz = w[1];
        double wMain = cl.wMain();
        // ---- the landscape
        double Fs = landscape(x, z, w);
        // ---- the vault
        dome(wx, wz, out.ch, out.sc);
        double H = HEAD_MIN + (HEAD_MAX - HEAD_MIN) * smooth(0.5 + 0.8 * ceilN.noise2(x / 170.0, z / 170.0)) + 3 * ceilN2.noise2(x / 55.0, z / 55.0);
        // walls (closed edges) stand on the dry ground; in the low ground they open out, so the lowlands drain upward
        double wallW = out.ch[1] * smooth((Fs - W - WALL_LOW) / 30.0);
        double gap = Math.min(H, GAP0 + GAP1 * passN.noise2(wx / PASS_P, wz / PASS_P) + WALL_OFF * wallW);
        double sP = smooth(out.ch[0] / PINCH_R);
        out.open = sP;
        out.gap = gap;
        // ---- the floor
        double F = Fs + 1.2 * detailN.noise2(x / 40.0, z / 40.0);
        if (Fs < W + RIVER_LOW + 16) {
            double dr = lineDistW(riverN, w, RIVER_P);
            double wc = 3.5 + 4 * smooth(0.5 + riverW.noise2(x / 90.0, z / 90.0));
            double q = Math.max(0, dr - wc);
            double Fr = W - 4 + Math.min(q, 5) + RIVER_BANK * Math.max(0, q - 5) + RIVER_RISE * Math.max(0, Fs - (W + RIVER_LOW));
            F = Math.min(F, Fr);
        }
        F += SADDLE * (1 - sP) * (1 - sP);
        if (gap < 0) F += Math.min(WALL_RISE, -gap * 0.4) * Math.pow(1 - sP, 1.5);
        F = terrace(F, 1, 0.5);
        // ---- sub-biomes
        if (cl.wCal > 0) {
            // cones stand in the lake, their craters the vents
            F = Math.max(F, coneFloor(x, z) - CONE_SINK * (1 - smooth((cl.wCal - 0.2) / 0.5)));
        }
        if (cl.wCaus > 0.2) {
            // prisms stepping out of the lava: the coarse cells' tops scatter round the table
            double st = 2.4 * causN.noise2(x / 38.0, z / 38.0) + 1.2 * causN.noise2(x / 11.0 + 50, z / 11.0);
            F = lerp(F, F + st, smooth((cl.wCaus - 0.2) / 0.5) * smooth((W + 6 - F) / 6.0));
        }
        if (cl.wCan > 0) {
            // canyons: lava slots along the zero lines of a noise, sheer walls up to the dry ground (so no flood-zone floor
            // is left down there to be trapped on), then stepped cliffs up to the ridges; they end where the patch fades
            double dc = lineDistW(canN, w, CAN_P);
            double fw = 2.5 + 3.5 * smooth(0.5 + canW.noise2(x / 70.0, z / 70.0));
            double ws = 1.0 + 1.8 * smooth(0.5 + canW.noise2(x / 110.0 + 20, z / 110.0));
            double u = dc - fw;
            double Fk = u <= 0 ? W - 4 : W + CAN_DRY + terrace(ws * u, CAN_STEP, 0.45);
            Fk += 300 * (1 - smooth((cl.wCan - 0.15) / 0.35));
            F = Math.min(F, Fk);
        }
        // ---- talus cones under some of the join's openings: column stairs down from the Wasteland
        if (join != null) F = Math.max(F, talus(x, z));
        // ---- the vault: the dome's head room over the landscape, pinched at the edge to the gap over the floor itself
        // (so a river or a canyon passes under a pinch only through its window)
        // over lava a pinch closes down into it (columns standing in the lava): no window lies flat over a lake
        double gapF = F < W ? gap - LAVA_PINCH * (W - F) : gap;
        double C = lerp(F + gapF, Fs + H, 1 - (1 - sP) * (1 - sP));
        // ---- a tube country's apron (TubeField): the floor goes to the tubes' level and the vault moves with it (pinches
        // stay pinches); where a tube runs out of the massif (its mouth) the space is kept open with head room
        double ap = tubesAt(x, z) ? tubes.apronAt(x, z) : 0, mouth = 0;
        if (ap > 0) {
            double F2 = terrace(lerp(F, tubes.apronFloorAt(x, z), ap), 1, 0.5);
            C += F2 - F;
            F = F2;
            int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
            if (tubes.spanLo(ix, iz) <= tubes.spanHi(ix, iz)) mouth = ap;
            C = Math.max(C, F + TubeField.APRON_HEAD * mouth);
        }
        // ---- the join: under the Wasteland's breakthroughs the domes rise into the band (the pinches stay)
        double jt = join != null ? join.breakthrough(x, z) : 0;
        out.jt = jt;
        if (jt > 0) C = Math.max(C, lerp(C, join.bandTop() + 1, jt * sP));
        // ---- hanging clusters: inverted spires of columns, longest at the middle, longest over the low ground
        double head = C - F;
        double hang = 0;
        boolean pillar = false;
        if (head > 6) {
            int gi = (int) Math.floor(x / HANG_GRID), gj = (int) Math.floor(z / HANG_GRID);
            double pH = HANG_P * (0.6 + 0.4 * wMain) + 0.25 * cl.wCaus;
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
                long h = Hashing.mix(seed, 34001, gi + di, gj + dj);
                if (Hashing.unit(h) > pH) continue;
                double sx = (gi + di + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * HANG_GRID;
                double sz = (gj + dj + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * HANG_GRID;
                double r = Hashing.range(Hashing.mix(h, 3, 0, 0), HANG_R0, HANG_R1);
                double d = Math.hypot(x - sx, z - sz) / r;
                if (d >= 1) continue;
                double rc = Hashing.range(Hashing.mix(h, 4, 0, 0), 0.25, 0.95);
                double len = rc * head * Math.pow(1 - Math.pow(d, 1.6), 0.85);
                len *= 0.78 + 0.44 * Hashing.unit(Hashing.mix(cellHash, 5, 0, 0));
                if (len > hang) hang = len;
                if (rc > 0.82 && d < 0.5) pillar = true;
            }
        }
        // ---- spires standing on the floor
        double up = 0;
        if (head > 8) {
            int gi = (int) Math.floor(x / SPIRE_GRID), gj = (int) Math.floor(z / SPIRE_GRID);
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
                long h = Hashing.mix(seed, 35001, gi + di, gj + dj);
                if (Hashing.unit(h) > SPIRE_P * (0.4 + 0.6 * wMain)) continue;
                double sx = (gi + di + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * SPIRE_GRID;
                double sz = (gj + dj + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * SPIRE_GRID;
                double r = Hashing.range(Hashing.mix(h, 3, 0, 0), SPIRE_R0, SPIRE_R1);
                double d = Math.hypot(x - sx, z - sz) / r;
                if (d >= 1) continue;
                double hgt = Hashing.range(Hashing.mix(h, 4, 0, 0), 0.25, 1.0) * head;
                double vv = hgt * Math.pow(1 - d, 0.8) * (0.8 + 0.4 * Hashing.unit(Hashing.mix(cellHash, 6, 0, 0)));
                if (vv > up) up = vv;
            }
        }
        // clusters and spires stay out of the tubes' mouths and the join's openings
        double clear = (1 - mouth) * (1 - jt);
        hang *= clear;
        up *= 1 - mouth;
        if (clear < 0.7) pillar = false;
        if (F + up >= C - hang - 4) pillar = pillar || (hang > 0 && up > 0) || up > head - 6;
        out.F = F + up;
        out.C = C - hang;
        out.hang = hang;
        out.rise = up;
        out.pillar = pillar;
    }

    // ------------------------------------------------------------------------------------------- talus cones (the join)
    /**
     * Talus cones: on a grid of TAL_GRID, a candidate per grid cell (chance TAL_P) stands where the join's breakthrough at
     * its middle is at least TAL_BETA: a flat top TAL_DROP rows under the layer's top (TAL_R0..TAL_R1 across), flanks
     * falling TAL_S rows a block (about 4 rows a column cell: walkable down, not up).
     */
    static final double TAL_GRID = 128, TAL_P = 0.6, TAL_DROP = 8, TAL_R0 = 4, TAL_R1 = 8, TAL_S = 1.3,
            TAL_BETA = 0.95;

    /** A talus candidate of grid cell (i, j): middle, top radius, whether it stands. Immutable once built. */
    static final class Talus {
        int i, j;
        double x, z, r;
        boolean on;
    }

    private final ThreadLocal<Talus[]> taluses = ThreadLocal.withInitial(() -> new Talus[64]);

    Talus talusAt(int i, int j) {
        Talus[] tc = taluses.get();
        int slot = (i & 7) << 3 | (j & 7);
        Talus t = tc[slot];
        if (t != null && t.i == i && t.j == j) return t;
        t = new Talus();
        t.i = i;
        t.j = j;
        long h = Hashing.mix(seed, 38001, i, j);
        t.x = (i + 0.2 + 0.6 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * TAL_GRID;
        t.z = (j + 0.2 + 0.6 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * TAL_GRID;
        t.r = Hashing.range(Hashing.mix(h, 3, 0, 0), TAL_R0, TAL_R1);
        t.on = Hashing.unit(h) < TAL_P && join.breakthrough(t.x, t.z) >= TAL_BETA;
        tc[slot] = t;
        return t;
    }

    /** The highest talus floor at (x, z), or -inf. */
    double talus(double x, double z) {
        int gi = (int) Math.floor(x / TAL_GRID), gj = (int) Math.floor(z / TAL_GRID);
        double best = -1e9;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            Talus t = talusAt(gi + di, gj + dj);
            if (!t.on) continue;
            double d = Math.hypot(x - t.x, z - t.z);
            best = Math.max(best, T - TAL_DROP - TAL_S * Math.max(0, d - t.r));
        }
        return best;
    }

    /**
     * The landscape at (x, z) (rows, unrounded): the elevation, the sub-biomes' basins and the lowland hills. {@code w}
     * must hold the warp at (x, z) ({@link #warp}).
     */
    double landscape(double x, double z, double[] w) {
        double wx = w[0], wz = w[1];
        double Fs = W + ELEV_MID + ELEV_AMP * elevN.fbm2(wx / ELEV_P, wz / ELEV_P, 2);
        Fs = patchTerrain(Fs, w);
        // hills in the low ground: dry refuges over the flood, a short walk from anywhere in the lowlands
        if (HILL_A > 0 && Fs < W + 50) {
            double hn = hillN.noise2(wx / HILL_P, wz / HILL_P);
            if (hn > HILL_T) Fs += HILL_A * (hn - HILL_T) * smooth((W + 50 - Fs) / 30.0);
        }
        return Fs;
    }

    // ------------------------------------------------------------------------------------------- tube countries
    /**
     * A tube country stands only where the landscape round it reaches the tubes' level: of TV_N points on a ring
     * TV_OUT blocks outside its rim, at least TV_MIN must lie within TV_TOL rows of the apron's floor there. Elsewhere
     * (a country in the lava lowlands or on the high ground) its apron would stand as a cliff round it and the tubes
     * could not be walked into: the Galleries ignore it (no massif, no apron, no tubes, pools or lining).
     */
    static final int TV_N = 16, TV_MIN = 3;
    static final double TV_OUT = 50, TV_TOL = 10;

    private final ThreadLocal<long[]> standCache = ThreadLocal.withInitial(() -> { long[] a = new long[64]; java.util.Arrays.fill(a, Long.MIN_VALUE); return a; });

    /** Whether the tube country of the column (x, z)'s tube cell stands (see {@link #TV_MIN}). */
    public boolean tubesAt(double x, double z) {
        if (tubes == null) return false;
        int ci = (int) Math.floor(x / TubeField.CELL), cj = (int) Math.floor(z / TubeField.CELL);
        long key = ((long) ci << 32) ^ (cj & 0xFFFFFFFFL);
        long[] sc = standCache.get();
        int slot = (ci & 7) << 3 | (cj & 7);
        if (sc[slot] == (key << 1 | 1)) return true;
        if (sc[slot] == (key << 1)) return false;
        boolean on = countryStands(ci, cj);
        sc[slot] = key << 1 | (on ? 1 : 0);
        return on;
    }

    boolean countryStands(int ci, int cj) {
        TubeField.Country c = tubes.countryAt((ci + 0.5) * TubeField.CELL, (cj + 0.5) * TubeField.CELL);
        if (!c.on) return false;
        double[] w = new double[6];
        int ok = 0;
        for (int k = 0; k < TV_N; k++) {
            double ph = 2 * Math.PI * k / TV_N;
            double v = (c.a + TV_OUT) * Math.cos(ph), u = (c.b + TV_OUT) * Math.sin(ph);
            double x = c.ox + v * c.cos - u * c.sin, z = c.oz + v * c.sin + u * c.cos;
            warp(x, z, w);
            double fs = landscape(x, z, w);
            if (Math.abs(fs - tubes.apronFloorAt(x, z)) <= TV_TOL) ok++;
        }
        return ok >= TV_MIN;
    }

    /** The Caldera's cones round (x, z): the highest cone floor here (a crater at each top), or -inf. */
    double coneFloor(double x, double z) {
        int gi = (int) Math.floor(x / CONE_GRID), gj = (int) Math.floor(z / CONE_GRID);
        double best = -1e9;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            long h = Hashing.mix(seed, 36001, gi + di, gj + dj);
            if (Hashing.unit(h) > CONE_P) continue;
            double[] v = cone(h, gi + di, gj + dj);
            double d = Math.hypot(x - v[0], z - v[1]);
            if (d >= v[2]) continue;
            double top = W + v[3];
            double fl = W - 4 + (top - (W - 4)) * Math.pow(1 - d / v[2], 1.15);
            if (d < v[4]) fl = Math.min(fl, top - v[5]);                     // the crater
            best = Math.max(best, fl);
        }
        return best;
    }

    /** A cone of grid cell (gi, gj): {x, z, radius, height over W, crater radius, crater depth}. */
    double[] cone(long h, int gi, int gj) {
        double sx = (gi + 0.2 + 0.6 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * CONE_GRID;
        double sz = (gj + 0.2 + 0.6 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * CONE_GRID;
        double r = Hashing.range(Hashing.mix(h, 3, 0, 0), 7, 14);
        double hgt = Hashing.range(Hashing.mix(h, 4, 0, 0), 4, 24);
        double cr = Hashing.range(Hashing.mix(h, 5, 0, 0), 2.2, 4.2);
        double cd = Hashing.range(Hashing.mix(h, 6, 0, 0), 3, 7);
        return new double[]{sx, sz, r, hgt, cr, cd};
    }

    // ------------------------------------------------------------------------------------------- bands
    /** Rock bands: long ridges at mid height from a ridged noise, as bridges over low ground, ribs or ridges. */
    static final double BAND_P = 300, BAND_MASK = 0.12;

    void band(Cell c, double x, double z, Climate cl) {
        double mk = bandMask.noise2(x / 240.0, z / 240.0);
        if (mk < BAND_MASK) return;
        double db = lineDist(bandN, x + 30 * warpB.noise2(x / 90.0, z / 90.0), z, BAND_P);
        double hw = 4 + 4 * smooth(0.5 + bandH.noise2(x / 80.0 + 7, z / 80.0)) * smooth((mk - BAND_MASK) / 0.1);
        if (db >= hw) return;
        double q = db / hw;
        double deck = W + 68 + 16 * bandH.noise2(x / 190.0, z / 190.0);
        double top = deck + 1.5 * (1 - q * q);
        double thick = 4 + 5 * Math.sqrt(1 - q * q);
        double pier = Math.max(0, pierN.noise2(x / 34.0, z / 34.0) + 0.05) * 45;
        double under = deck - thick - pier - 3 * Hashing.unit(Hashing.mix(c.h, 7, 0, 0));
        int bl = (int) Math.floor(under + 0.5), bh = (int) Math.floor(top + 0.5) + 1;
        if (bh <= c.f || bl >= c.c) return;
        if (bl <= c.f + 1) { c.f = Math.max(c.f, bh); if (c.c - c.f < 2) c.f = c.c = W + 200; return; }   // a ridge on the ground
        if (bh >= c.c - 1) { c.c = Math.min(c.c, bl); if (c.c - c.f < 2) c.f = c.c = W + 200; return; }   // a rib under the ceiling
        c.bl = bl;
        c.bh = bh;
    }

    // ------------------------------------------------------------------------------------------- neighbours
    /** The rows open in the cells round c (the 6 hexagonal neighbours and the two more a jittered lattice can need). */
    void nearOf(Cell c) {
        int[] buf = new int[32];
        int n = 0;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            if (di == 0 && dj == 0) continue;
            Cell o = cell(c.coarse, c.i + di, c.j + dj);
            if (o.closed()) continue;
            int lo = o.f, hi = o.c;
            if (o.bl < o.bh) {
                if (n + 4 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                buf[n++] = lo; buf[n++] = o.bl; buf[n++] = o.bh; buf[n++] = hi;
            } else {
                if (n + 2 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                buf[n++] = lo; buf[n++] = hi;
            }
        }
        c.near = buf;
        c.nearN = n;
    }

    // ============================================================================================ the density
    /** Density in blocks at a block of the layer (positive is rock): the cell's profile, exact. */
    public double density(int x, int y, int z) {
        Cell c = column(x, z);
        return blocks(c, y);
    }

    /** The profile's signed distance at row y (blocks): vertical, and 1.5 for rock beside a neighbour's open rows. */
    static double blocks(Cell c, int y) {
        if (c.f >= c.c) return nearSide(c, y, 16);
        if (y >= c.f && y < c.c) {
            if (c.bl < c.bh && y >= c.bl && y < c.bh) {
                double d = Math.min(y - c.bl + 1, c.bh - y);
                return nearSide(c, y, d);
            }
            double lo = c.bl < c.bh && y >= c.bh ? c.bh : c.f, hi = c.bl < c.bh && y < c.bl ? c.bl : c.c;
            return -Math.min(y - lo + 1, hi - y);
        }
        double d = y < c.f ? c.f - y : y - c.c + 1;
        return nearSide(c, y, Math.min(d, 16));
    }

    static double nearSide(Cell c, int y, double d) {
        if (d <= 1.5 || c.near == null) return d;
        for (int k = 0; k + 1 < c.nearN; k += 2) if (y >= c.near[k] - 1 && y < c.near[k + 1] + 1) return 1.5;
        return d;
    }

    /**
     * The per-block pass: the exact profile wherever the layer is. {@code coarse} (Minecraft's interpolated density) is
     * ignored inside the layer: the column cells' faces are never on the 4-block corner grid, so it is only ever a blur.
     */
    public double fine(int x, int y, int z, double coarse) {
        double d = fineBlocks(x, y, z);
        d /= 8.0;
        return d < -1 ? -1 : (d > 1 ? 1 : d);
    }

    /** The per-block density in blocks: the cell's profile, and the tubes carved in it (min(cave, tube)). */
    public double fineBlocks(int x, int y, int z) {
        double d = density(x, y, z);
        if (tubes != null && y >= tubes.spanLo(x, z) && y <= tubes.spanHi(x, z) && tubesAt(x + 0.5, z + 0.5)) d = Math.min(d, tubes.tube(x, y, z));
        return d;
    }

    /** What fills an open block: still water in a tube's pools, lava under the table, else air. */
    public int fluid(int x, int y, int z) {
        if (y < W) return Terrain.LAVA;
        if (tubes != null && tubes.pool(x, y, z) && tubesAt(x + 0.5, z + 0.5)) return Terrain.WATER;
        return Terrain.AIR;
    }

    /**
     * The sub-biome at a block: OBSIDIAN_TUBES in a tube's air and lining where the cavern itself is rock, else the
     * column cell's climate kind.
     */
    public DeepBiome biome(int x, int y, int z) {
        Cell c = column(x, z);
        if (tubes != null && !c.open(y) && tubes.inLining(x, y, z) && tubesAt(x + 0.5, z + 0.5)) return DeepBiome.OBSIDIAN_TUBES;
        return switch (c.kind) {
            case CALDERA -> DeepBiome.CALDERA;
            case CAUSEWAY -> DeepBiome.CAUSEWAY;
            case CANYONS -> DeepBiome.SCORIA_CANYONS;
            default -> DeepBiome.BASALT_GALLERIES;
        };
    }

    // ============================================================================================ vents
    /**
     * The Caldera's vents within r of (x, z) (their edge within reach): the craters of the cones, enumerated from the
     * cone lattice. {@code y} is the crater floor's top block, or the lava's top block when the crater holds lava;
     * {@code crest} when the crater is dry; {@code chamber} the Caldera patch's id (one per patch).
     */
    public List<Net.Vent> ventsNear(double x, double z, double r) {
        List<Net.Vent> out = new ArrayList<>();
        Climate cl = new Climate();
        int g0 = (int) Math.floor((x - r - 20) / CONE_GRID), g1 = (int) Math.floor((x + r + 20) / CONE_GRID);
        int h0 = (int) Math.floor((z - r - 20) / CONE_GRID), h1 = (int) Math.floor((z + r + 20) / CONE_GRID);
        for (int gi = g0; gi <= g1; gi++) for (int gj = h0; gj <= h1; gj++) {
            long h = Hashing.mix(seed, 36001, gi, gj);
            if (Hashing.unit(h) > CONE_P) continue;
            double[] v = cone(h, gi, gj);
            if (Math.hypot(v[0] - x, v[1] - z) > r + v[4]) continue;
            climate(v[0], v[1], cl);
            if (cl.kind != CALDERA) continue;
            Cell c = column((int) Math.floor(v[0]), (int) Math.floor(v[1]));
            if (c.closed()) continue;
            Net.Vent vt = new Net.Vent();
            vt.x = v[0];
            vt.z = v[1];
            vt.r = v[4];
            vt.crest = c.f >= W;
            vt.y = vt.crest ? c.f - 1 : W - 1;
            vt.chamber = (int) (cl.patch ^ (cl.patch >>> 32));
            out.add(vt);
        }
        return out;
    }

    // ============================================================================================ places (dev tools)
    /** The places search: lattice step (blocks) and half width (steps) for the main galleries, patch reach (lattice cells). */
    static final int PLACE_STEP = 16, PLACE_HALF = 24, PLACE_PATCHES = 8, PLACE_MAX = 8;
    static final double PLACE_SPACING = 64;

    /**
     * Places showing a Galleries biome round (x, z), nearest first, for /remix tp biome, /remix view and the shot planner
     * (in place of Network.places for layer 3): sampled from the field itself, nothing planned. Every stand is checked
     * block by block (two open rows over rock, no fluid). The Obsidian Tubes: a spot in each standing tube country of the
     * 5 x 5 tube cells round (x, z) ({@link #tubeSpot}). Caldera, Causeway and Scoria Canyons: the climate patches of
     * that kind within PLACE_PATCHES lattice cells (about 2000 blocks), up to 4 stands on rings round each patch's middle.
     * The main galleries: columns of a 16-block lattice over 785 x 785 blocks. At most PLACE_MAX places, PLACE_SPACING
     * apart.
     */
    public List<Network.Place> places(DeepBiome b, double x, double z) {
        List<Network.Place> out = new ArrayList<>();
        if (b == DeepBiome.OBSIDIAN_TUBES) {
            if (tubes == null) return out;
            int ci0 = (int) Math.floor(x / TubeField.CELL), cj0 = (int) Math.floor(z / TubeField.CELL);
            for (int ci = ci0 - 2; ci <= ci0 + 2; ci++) for (int cj = cj0 - 2; cj <= cj0 + 2; cj++) {
                int[] s = tubeSpot(ci, cj);
                if (s == null) continue;
                int fy = standAt(s[0], s[1], s[2]);
                if (fy == Integer.MIN_VALUE) continue;
                Network.Place p = new Network.Place(s[0] + 0.5, s[2] + 0.5, 6, false, "a tube of tube cell " + ci + " " + cj);
                p.stands.add(new double[]{s[0] + 0.5, fy, s[2] + 0.5});
                out.add(p);
            }
        } else if (b == DeepBiome.BASALT_GALLERIES) {
            int x0 = (int) Math.floor(x / PLACE_STEP) * PLACE_STEP, z0 = (int) Math.floor(z / PLACE_STEP) * PLACE_STEP;
            List<double[]> cand = new ArrayList<>();
            for (int i = -PLACE_HALF; i <= PLACE_HALF; i++) for (int j = -PLACE_HALF; j <= PLACE_HALF; j++) {
                int px = x0 + i * PLACE_STEP, pz = z0 + j * PLACE_STEP;
                Cell c = cellAt(px, pz);
                if (c.closed() || c.f < W || c.c - c.f < 6 || c.kind != G) continue;
                cand.add(new double[]{px, c.f, pz, Math.hypot(px + 0.5 - x, pz + 0.5 - z)});
            }
            cand.sort(java.util.Comparator.comparingDouble(v -> v[3]));
            for (double[] v : cand) {
                if (out.size() >= PLACE_MAX) break;
                if (near(out, v[0] + 0.5, v[2] + 0.5)) continue;
                int px = (int) v[0], pz = (int) v[2], fy = standAt(px, (int) v[1], pz);
                if (fy == Integer.MIN_VALUE || biome(px, fy, pz) != b) continue;
                Network.Place p = new Network.Place(px + 0.5, pz + 0.5, 8, false, "the galleries at " + px + " " + pz);
                p.stands.add(new double[]{px + 0.5, fy, pz + 0.5});
                out.add(p);
            }
        } else {
            int kind = b == DeepBiome.CALDERA ? CALDERA : b == DeepBiome.CAUSEWAY ? CAUSEWAY : b == DeepBiome.SCORIA_CANYONS ? CANYONS : -1;
            if (kind < 0) return out;
            int gi = (int) Math.floor(x / SP), gj = (int) Math.floor(z / SP);
            List<Patch> ps = new ArrayList<>();
            for (int di = -PLACE_PATCHES; di <= PLACE_PATCHES; di++) for (int dj = -PLACE_PATCHES; dj <= PLACE_PATCHES; dj++) {
                Patch p = patch(gi + di, gj + dj);
                if (p.kind == kind) ps.add(p);
            }
            ps.sort(java.util.Comparator.comparingDouble(p -> Math.hypot(p.cx - x, p.cz - z)));
            for (Patch pa : ps) {
                if (out.size() >= PLACE_MAX) break;
                Network.Place pl = null;
                // rings round the patch's middle (a warped-plane point; the warp moves it under WARP_A blocks)
                for (int ring = 0; ring < 5 && (pl == null || pl.stands.size() < 4); ring++) {
                    double rr = ring * 0.2 * pa.r;
                    int n = ring == 0 ? 1 : 12;
                    for (int q = 0; q < n && (pl == null || pl.stands.size() < 4); q++) {
                        double an = 2 * Math.PI * q / n;
                        int px = (int) Math.floor(pa.cx + rr * Math.cos(an)), pz = (int) Math.floor(pa.cz + rr * Math.sin(an));
                        Cell c = cellAt(px, pz);
                        if (c.closed() || c.f < W || c.c - c.f < 6 || c.kind != kind) continue;
                        int fy = standAt(px, c.f, pz);
                        if (fy == Integer.MIN_VALUE || biome(px, fy, pz) != b) continue;
                        if (pl == null) {
                            if (near(out, px + 0.5, pz + 0.5)) break;
                            pl = new Network.Place(px + 0.5, pz + 0.5, pa.r, false, b.name().toLowerCase(java.util.Locale.ROOT) + " patch " + pa.i + " " + pa.j);
                        }
                        pl.stands.add(new double[]{px + 0.5, fy, pz + 0.5});
                    }
                }
                if (pl != null) out.add(pl);
            }
        }
        out.sort(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x - x, p.z - z)));
        return out;
    }

    /** For /remix info: the column cell's profile and what made it, the tube country and the join over a block, in words. */
    public List<String> describe(int x, int y, int z) {
        Cell c = column(x, z);
        String[] kinds = {"galleries", "caldera", "causeway", "scoria canyons"};
        List<String> out = new ArrayList<>();
        out.add(String.format(java.util.Locale.ROOT, "galleries: column cell %d %d%s (site %.1f %.1f, %.1f to its edge), %s (caldera %.2f, causeway %.2f, canyons %.2f)",
                c.i, c.j, c.coarse ? " (coarse)" : "", c.x, c.z, columnEdge(x, z), kinds[Math.max(0, Math.min(3, c.kind))], c.wCal, c.wCaus, c.wCan));
        String rows = c.closed() ? "closed" + (c.pillar ? " (a pillar)" : "")
                : String.format(java.util.Locale.ROOT, "open rows %d..%d%s, hanging cluster %.0f, spire %.0f", c.f, c.c - 1,
                        c.bl < c.bh ? String.format(java.util.Locale.ROOT, " less a rock band %d..%d", c.bl, c.bh - 1) : "", c.hang, c.rise);
        out.add(String.format(java.util.Locale.ROOT, "  %s; here density %.1f, per block %.1f, %s; lava table y %d", rows, density(x, y, z), fineBlocks(x, y, z),
                c.open(y) ? "open" : "rock", W - 1));
        if (tubes != null) {
            int ci = (int) Math.floor(x / (double) TubeField.CELL), cj = (int) Math.floor(z / (double) TubeField.CELL);
            boolean stands = tubesAt(x + 0.5, z + 0.5);
            TubeField.Country k = tubes.countryAt((ci + 0.5) * TubeField.CELL, (cj + 0.5) * TubeField.CELL);
            out.add(String.format(java.util.Locale.ROOT, "  tube cell %d %d: %s%s", ci, cj, !k.on ? "no country" : stands ? "a country" : "a country the cavern does not let stand",
                    stands ? String.format(java.util.Locale.ROOT, ", massif %.2f, apron %.2f, tube %.1f, lining depth %.1f", tubes.massifAt(x + 0.5, z + 0.5),
                            tubes.apronAt(x + 0.5, z + 0.5), tubes.tube(x, y, z), tubes.shellDepth(x, y, z)) : ""));
        }
        if (join != null) out.add(String.format(java.util.Locale.ROOT, "  breakthrough %.2f (the ceiling may rise to y %d)", join.breakthrough(x + 0.5, z + 0.5), join.bandTop()));
        return out;
    }

    static boolean near(List<Network.Place> out, double x, double z) {
        for (Network.Place p : out) if (Math.hypot(p.x - x, p.z - z) < PLACE_SPACING) return true;
        return false;
    }

    /** The cell over column (x, z) without its neighbours' rows (cheaper than {@link #column}; for searches). */
    Cell cellAt(int x, int z) {
        Caches k = caches.get();
        boolean coarse = coarseAt(x + 0.5, z + 0.5, k);
        cellOf(coarse, x + 0.5, z + 0.5, k.ij, k);
        return cell(coarse, k.ij[0], k.ij[1]);
    }

    /**
     * Feet row of a stand at column (x, z) from row y: moved down to the first row over rock (at most 6 rows), then two
     * open rows of air over rock in the exact per-block density; Integer.MIN_VALUE when there is none.
     */
    int standAt(int x, int y, int z) {
        for (int k = 0; k < 6 && fineBlocks(x, y - 1, z) < 0; k++) y--;
        if (fineBlocks(x, y - 1, z) <= 0 || fineBlocks(x, y, z) >= 0 || fineBlocks(x, y + 1, z) >= 0) return Integer.MIN_VALUE;
        if (fluid(x, y, z) != Terrain.AIR || fluid(x, y + 1, z) != Terrain.AIR) return Integer.MIN_VALUE;
        return y;
    }

    // ============================================================================================ look queries
    /** The tubes' lining depth (TubeField.shellDepth) where the country stands, else 1e9: Terrain.tubeShellDepth. */
    public double tubeShellDepth(int x, int y, int z) {
        return tubes != null && tubesAt(x + 0.5, z + 0.5) ? tubes.shellDepth(x, y, z) : 1e9;
    }

    /** The tube field's value where the country stands (negative in tube air), else Terrain.SOLID: LookQueries.tube. */
    public double tube(int x, int y, int z) {
        return tubes != null && tubesAt(x + 0.5, z + 0.5) ? tubes.tube(x, y, z) : Terrain.SOLID;
    }

    /**
     * LookQueries.tubeRib: (x, y, z) is in an arch rib (the rib's share over its column is over a half) where the country
     * stands. The palette asks it only of OBSIDIAN_TUBES lining blocks, so y is not needed.
     */
    public boolean tubeRib(int x, int y, int z) {
        return tubes != null && tubesAt(x + 0.5, z + 0.5) && tubes.ribShare(x, z) > 0.5;
    }

    /** TubeField.floorCell where the country stands, else 0 (one stone per tube floor step, if the palette wants it). */
    public long tubeFloorCell(int x, int z) {
        return tubes != null && tubesAt(x + 0.5, z + 0.5) ? tubes.floorCell(x, z) : 0;
    }

    /** A standing spot in the tubes of tube cell (ci, cj) for /remix tp and the shot planner, or null (no standing country). */
    public int[] tubeSpot(int ci, int cj) {
        if (tubes == null || !tubesAt((ci + 0.5) * TubeField.CELL, (cj + 0.5) * TubeField.CELL)) return null;
        return tubes.spotIn(ci, cj);
    }

    /** The basalt column holding column (x, z): its cell's hash, equal along the cell, different for neighbours. */
    public long columnCell(int x, int z) {
        long h = column(x, z).h;
        return h == 0 ? 1 : h;
    }

    /**
     * Blocks from the middle of column (x, z) to the nearest joint with a neighbouring column: the distance to the
     * bisector between the cell's site and the nearest other site (0 on the joint). From the per-column cache.
     */
    public double columnEdge(int x, int z) {
        column(x, z);
        return caches.get().colE[(x & 31) << 5 | (z & 31)];
    }

    /** 0..1: (x, y, z) is in a hanging cluster's rock (over 0.05) and how long that cluster is (1 for 30 rows or more). */
    public double hang(int x, int y, int z) {
        Cell c = column(x, z);
        if (c.hang < 1 || c.f >= c.c || y < c.c || y >= c.c + c.hang + 1) return 0;
        return Math.max(0.06, Math.min(1, c.hang / 30.0));
    }

    /**
     * Distance from (x, z) to the nearest Caldera cone's axis in foot radii (0 axis, 1 foot), 9 when none is near. From
     * the per-column cache, computed the first time a column is asked (the palette asks it only in the Caldera).
     */
    public double cone(int x, int z) {
        column(x, z);
        Caches k = caches.get();
        int slot = (x & 31) << 5 | (z & 31);
        double v = k.colCone[slot];
        if (v != v) k.colCone[slot] = v = coneDist(x, z);
        return v;
    }

    /**
     * The top of the cone ground under column (x, z): the floor the Caldera's cones give the column's cell, as
     * {@link #shape} lays it (the highest cone floor at the cell's site, sunk where the Caldera fades), or about -1e9
     * where the cell has none. Over it, the walls, hanging masses and vault over a cone's foot are not the cone. From the
     * per-column cache, like {@link #cone(int, int)}.
     */
    public double coneTop(int x, int z) {
        Cell c = column(x, z);
        Caches k = caches.get();
        int slot = (x & 31) << 5 | (z & 31);
        double v = k.colConeTop[slot];
        if (v != v) k.colConeTop[slot] = v = c.wCal > 0 ? coneFloor(c.x, c.z) - CONE_SINK * (1 - smooth((c.wCal - 0.2) / 0.5)) : -1e9;
        return v;
    }

    double coneDist(int x, int z) {
        int gi = (int) Math.floor(x / CONE_GRID), gj = (int) Math.floor(z / CONE_GRID);
        double best = 9;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            long h = Hashing.mix(seed, 36001, gi + di, gj + dj);
            if (Hashing.unit(h) > CONE_P) continue;
            double[] v = cone(h, gi + di, gj + dj);
            best = Math.min(best, Math.hypot(x + 0.5 - v[0], z + 0.5 - v[1]) / v[2]);
        }
        return best;
    }
}
