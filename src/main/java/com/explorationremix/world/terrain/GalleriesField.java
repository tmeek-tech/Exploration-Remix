package com.explorationremix.world.terrain;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The Basalt Galleries (layer 3) as a noise field: nothing planned, nothing placed. Every block is a pure function of
 * the seed and its position (noise and hash lattices only), so any number of worldgen threads can call in, in any
 * order, and there are no seams. No JVM flag, system property or environment variable is read, and no platform math
 * whose last bit may vary (only +, -, *, /, sqrt, floor and StrictMath), so the shape cannot depend on how it is run.
 *
 * <p>The shape. One vast open cavern under a vault as high as the layer allows, its floor a landscape of high plains,
 * low ground stepping down in basalt terraces to lava rivers and basins, and four kinds of standing rock: sparse big
 * massifs from floor to vault, clumped clusters of hanging columns (fringes, masses, a few pillars), spires, and long
 * rock bands. Where the Galleries open one of the Wasteland's openings with a ramp, the Wasteland's lowest tunnels go
 * down to it round the hole ({@link #craterFloor}), and a natural ramp of columns runs from the hole's lip down to the
 * floor: an arch over the first, high part, a filled ridge with talus flanks over the rest, bending as it goes; its deck
 * steps a row at a time block by block, so it is walked up as well as down.
 *
 * <p>How it is built. The layer is cut into basalt column cells: a jittered hexagonal lattice about 3 blocks across
 * (5 in the Causeway). Every 2D field is read at the cell's site only, so every block of a cell has the same vertical
 * profile: floors are column tops, ceilings are hanging column ends, walls are column faces. A cell's profile is the
 * open rows {@code [f, c)} less the rock of a band {@code [bl, bh)}. Below the lava table W every open block is lava.
 * Minecraft samples the density at 4 x 8 x 4 corners and interpolates; the per-block pass ({@link #fine}) then puts
 * back the exact profile, block by block, from the cached cell (a few comparisons a block).
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
    final Perlin warpA, warpB, elevN, detailN, riverN, riverW, ceilN2, bandN, bandMask, bandH, pierN, canN, canW, causN,
            massN, fordN, densN, roofN, hillN, stepN, bendN, sagN, clumpN, rubN, deckN;
    final Perlin widthN, rimN;

    /**
     * The join with the Wasteland above, as the Galleries need it; null: no join (the seal stays). Every method reads the
     * Wasteland's own noise only (never the Galleries), so there is no cycle.
     */
    public interface Join {
        /** The Wasteland's breakthrough noise beta at column (x, z), 0..1. */
        double beta(double x, double z);
        /**
         * The weight of the Wasteland's opening dip at column (x, z) before its guard, 0..1: its lowest sheet's junction
         * room falling through its floor, hole x low x clamp01((HOLE_R - j) / HOLE_FADE). The Galleries find the
         * openings' middles from it and choose which of them they open.
         */
        double openingSite(double x, double z);
        /** A bound on the highest open row of the Galleries' space under an opening (the layer's top + 6); they stay under T - 3. */
        int bandTop();
    }

    final Join join;
    /**
     * A ramp's head's first open row (T - HEAD_DROP: the crater's floor's first open row) and the first rock row over an
     * opened disc (T - CR_DROP + 2: the disc is open up to the crater's floor's first open row).
     */
    final double headTop, discTop;
    /** The Obsidian Tubes' field (its own region noise), or null (no tubes). */
    final TubeField tubes;

    public GalleriesField(long seed, int bottom, int top, int lavaTable, TubeField tubes, Join join) {
        this(seed, bottom, top, lavaTable, tubes, join, 0, 0);
    }

    /**
     * As above, with every ramp's deck moved {@code deckShift} rows and every opened disc's cap {@code capShift} rows up
     * (down where negative) against the Wasteland's craters, whose floors stay put: only JoinTest's one-row check
     * (W-ENG-2) moves them; the game keeps both at 0.
     */
    GalleriesField(long seed, int bottom, int top, int lavaTable, TubeField tubes, Join join, int deckShift, int capShift) {
        this.seed = seed;
        this.tubes = tubes;
        this.B = bottom;
        this.T = top;
        this.W = lavaTable;
        this.openLo = B + SEAL;
        this.openHi = T - SEAL;
        this.join = join;
        this.headTop = T - HEAD_DROP + deckShift;
        this.discTop = T - CR_DROP + 2 + capShift;
        long s = seed ^ 0x5851F42D4C957F2DL;
        warpA = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpB = new Perlin(Hashing.mix(s, 2, 0, 0));
        elevN = new Perlin(Hashing.mix(s, 4, 0, 0));
        detailN = new Perlin(Hashing.mix(s, 8, 0, 0));
        riverN = new Perlin(Hashing.mix(s, 9, 0, 0));
        riverW = new Perlin(Hashing.mix(s, 10, 0, 0));
        ceilN2 = new Perlin(Hashing.mix(s, 12, 0, 0));
        bandN = new Perlin(Hashing.mix(s, 13, 0, 0));
        bandMask = new Perlin(Hashing.mix(s, 14, 0, 0));
        bandH = new Perlin(Hashing.mix(s, 15, 0, 0));
        pierN = new Perlin(Hashing.mix(s, 16, 0, 0));
        canN = new Perlin(Hashing.mix(s, 17, 0, 0));
        canW = new Perlin(Hashing.mix(s, 18, 0, 0));
        causN = new Perlin(Hashing.mix(s, 20, 0, 0));
        massN = new Perlin(Hashing.mix(s, 40, 0, 0));
        fordN = new Perlin(Hashing.mix(s, 48, 0, 0));
        densN = new Perlin(Hashing.mix(s, 49, 0, 0));
        roofN = new Perlin(Hashing.mix(s, 51, 0, 0));
        hillN = new Perlin(Hashing.mix(s, 52, 0, 0));
        stepN = new Perlin(Hashing.mix(s, 54, 0, 0));
        bendN = new Perlin(Hashing.mix(s, 55, 0, 0));
        sagN = new Perlin(Hashing.mix(s, 56, 0, 0));
        clumpN = new Perlin(Hashing.mix(s, 57, 0, 0));
        rubN = new Perlin(Hashing.mix(s, 58, 0, 0));
        deckN = new Perlin(Hashing.mix(s, 59, 0, 0));
        widthN = new Perlin(Hashing.mix(s, 60, 0, 0));
        rimN = new Perlin(Hashing.mix(s, 61, 0, 0));
    }

    // ============================================================================================ helpers
    static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    static double smooth(double t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Euclidean length (sqrt is exact in IEEE arithmetic; Math.hypot is slower and not needed at these sizes). */
    static double len(double dx, double dz) { return Math.sqrt(dx * dx + dz * dz); }

    /** Terraces of the given step: flat for {@code flat} of each step, then a steep rise. */
    static double terrace(double v, double step, double flat) {
        double k = Math.floor(v / step), fr = v / step - k;
        return step * (k + smooth((fr - flat) / (1 - flat)));
    }

    /** Directions: a table of DIRS unit vectors built with StrictMath (no platform trigonometry in the shape). */
    static final int DIRS = 64;
    static final double[] DIR_X = new double[DIRS], DIR_Z = new double[DIRS];

    static {
        for (int k = 0; k < DIRS; k++) {
            double a = 2 * StrictMath.PI * k / DIRS;
            DIR_X[k] = StrictMath.cos(a);
            DIR_Z[k] = StrictMath.sin(a);
        }
    }

    /** The direction index of a hash share u in [0, 1). */
    static int dirOf(double u) { return Math.min(DIRS - 1, (int) (u * DIRS)); }

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
        warpXZ(x, z, out);
        warpJ(x, z, out);
    }

    /** The warped point only (out[0..1]; see {@link #warp}). */
    void warpXZ(double x, double z, double[] out) {
        out[0] = x + WARP_A * warpA.noise2(x / WARP_P, z / WARP_P);
        out[1] = z + WARP_A * warpB.noise2(x / WARP_P, z / WARP_P);
    }

    /** The warp's Jacobian only (out[2..5]; see {@link #warp}). */
    void warpJ(double x, double z, double[] out) {
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
     * the basins sit in low ground and the canyons are cut into high ground: a lowland patch is a Caldera or a Causeway
     * (hashed), a highland one Scoria Canyons (CAN_SHARE of them), and the ground between has none.
     */
    Patch patch(int i, int j) { return patch(patches.get(), i, j); }

    /** {@link #patch(int, int)} through the thread's cache pc (a loop over many patches reads the thread's cache once). */
    Patch patch(Patch[] pc, int i, int j) {
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
        Patch[] pc = patches.get();
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            Patch p = patch(pc, gi + di, gj + dj);
            if (p.kind == G) continue;
            double d = len(wx - p.cx, wz - p.cz) / p.r;
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
        /** A rock band (a ramp's deck, a bridge, arch or rib) inside the open rows: rock in [bl, bh); bl == bh: none. */
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
        /**
         * A ramp's deck, read block by block ({@link #blocks(Cell, int, int, int)}): 0 none, 1 the deck is the floor f, 2
         * it is the band's top bh. Its ramp's axis (from (dkX, dkZ) along (dkUx, dkUz)), where the deck starts along it,
         * the first open row there and the slope; the cell's own deck row and its ragged drop (rows); the ground under it.
         */
        byte deckMode;
        double dkX, dkZ, dkUx, dkUz, dkT0, dkTop;
        Ramp dkRamp;
        int dkRow, dkDrop, dkGround;

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
                k.si[n] = i;
                k.sj[n] = j;
                double dx = x - tmp[0], dz = z - tmp[1], d = dx * dx + dz * dz;
                if (d < best) { best = d; out[0] = i; out[1] = j; x1 = tmp[0]; z1 = tmp[1]; }
                n++;
            }
        }
        double e = 1e9;
        k.nearQ = -1;
        for (int q = 0; q < n; q++) {
            double ex = sx[q] - x1, ez = sz[q] - z1, l = Math.sqrt(ex * ex + ez * ez);
            k.eq[q] = 1e9;
            if (l < 1e-9) continue;
            double d2 = (x - sx[q]) * (x - sx[q]) + (z - sz[q]) * (z - sz[q]);
            double eq = (d2 - best) / (2 * l);
            k.eq[q] = eq;
            if (eq < e) { e = eq; k.nearQ = q; }
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
        /** The tubes' per-block rows of the column ([lo, hi], empty where no country stands), read once: lo is SPAN_UNREAD until then. */
        final int[] colLo = new int[COL_CACHE], colHi = new int[COL_CACHE];
        /** The column's floor on the stairs at its cell's nearest joint ({@link #tread}; NO_TREAD: the cell's own floor). */
        final int[] colF = new int[COL_CACHE];
        final int[] ij = new int[2];
        final double[] tmp = new double[2], sx = new double[9], sz = new double[9];
        /** The lattice cells of the 9 sites {@link #cellOf} looked at, and which of them holds the nearest joint. */
        final int[] si = new int[9], sj = new int[9];
        /** The distance from the column to its cell's joint with each of the 9 sites (1e9 for its own). */
        final double[] eq = new double[9];
        int nearQ;
        final Climate cl = new Climate();
        final Shape0 sh = new Shape0();
        Caches() { Arrays.fill(colX, Integer.MIN_VALUE); }
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
        return column(caches.get(), x, z);
    }

    private Cell column(Caches k, int x, int z) {
        int slot = (x & 31) << 5 | (z & 31);
        if (k.colX[slot] == x && k.colZ[slot] == z) return k.col[slot];
        boolean coarse = coarseAt(x + 0.5, z + 0.5, k);
        boolean seam = Math.abs(k.cl.wCaus - 0.5) < SEAM_W;
        double e = cellOf(coarse, x + 0.5, z + 0.5, k.ij, k);
        int nq = k.nearQ;
        Cell c = cell(coarse, k.ij[0], k.ij[1]);
        if (c.near == null) nearOf(c);
        int tf = NO_TREAD;
        if (e < TREAD_REACH && nq >= 0) {
            // the joints within reach, nearest first: the first whose two cells make stairs (the nearest alone left a
            // riser without stairs wherever a cell's nearest joint was with lava or a deck)
            double last = -1;
            for (int pass = 0; pass < 8 && tf == NO_TREAD; pass++) {
                int bq = -1;
                double be = TREAD_REACH;
                for (int q = 0; q < 9; q++) if (k.eq[q] > last && k.eq[q] < be) { be = k.eq[q]; bq = q; }
                if (bq < 0) break;
                last = be;
                tf = tread(c, be, k.si[bq], k.sj[bq]);
            }
        }
        if (tf == NO_TREAD && seam) tf = seamTread(c, coarse, x, z, k);
        k.colX[slot] = x;
        k.colZ[slot] = z;
        k.col[slot] = c;
        k.colE[slot] = e;
        k.colF[slot] = tf;
        k.colCone[slot] = Double.NaN;
        k.colLo[slot] = SPAN_UNREAD;
        return c;
    }

    /** No stairs at the column: its floor is its cell's ({@link #tread}). */
    static final int NO_TREAD = Integer.MIN_VALUE;
    /**
     * The low ground's stairs ({@link #tread}): between two cells of open ground whose floors differ by 2..TREAD_K rows,
     * the lower one's floor under W + TREAD_TOP (the flood's reach and a little over), the columns within TREAD_REACH of
     * their joint step a row a block from one floor to the other.
     */
    static final int TREAD_K = 6, TREAD_TOP = 44;
    static final double TREAD_REACH = 3;

    /**
     * The floor of a column of cell c on the stairs at the cell's nearest joint (e blocks away, with lattice cell (ni,
     * nj) of the same lattice), or NO_TREAD. Basalt columns step down to the lava a row or two at a
     * time, but where two neighbouring cells of the low ground differ by 2..TREAD_K rows (a riser of the terraces, a
     * river's bank, the Causeway's prisms, a ragged column top on a slope) a player could not climb out of the flood
     * there. So on such a joint the columns step a row a block across it: the signed distance s from the joint (into the
     * higher cell) puts the column at lo + clamp(m + floor(s), 0, d), d the rows between the floors and m = ceil(d / 2)
     * (the step is shared out between the two cells: the lower one's columns at the joint rise, the higher one's sink).
     * Each column takes the stairs of the nearest of its cell's joints within TREAD_REACH that has any (fix round 2b: of
     * the nearest joint only, a riser whose cells met lava or a deck nearer kept no stairs), so the cells' tops stay level
     * away from their joints. Both cells must be plain open ground (no deck, band or pillar), the raised floor must keep
     * three rows open over it, and lava cells (floor under W) take none. Pure (cells and sites are).
     */
    int tread(Cell c, double e, int ni, int nj) {
        if (!treadGround(c)) return NO_TREAD;
        Cell n = cell(c.coarse, ni, nj);
        if (!treadGround(n)) return NO_TREAD;
        int lo = Math.min(c.f, n.f), d = Math.abs(c.f - n.f);
        if (d < 2 || d > TREAD_K || lo < W || lo >= W + TREAD_TOP) return NO_TREAD;
        double s = c.f < n.f ? -e : e;
        int h = lo + Math.max(0, Math.min(d, (d + 1) / 2 + (int) Math.floor(s)));
        if (h == c.f || h > c.c - 3) return NO_TREAD;
        return h;
    }

    /** Columns this close to the Causeway's coarse prisms' seam (its weight from a half) look across it ({@link #seamTread}). */
    static final double SEAM_W = 0.15;

    /**
     * The low ground's stairs across the seam between the Causeway's coarse prisms and the fine cells ({@link #tread}
     * looks at one lattice only): where a block beside column (x, z) lies in a cell of the other lattice whose floor
     * differs by 2 or 3 rows, the column takes its side's step of the stairs, as if the joint ran between the two
     * blocks. NO_TREAD where none.
     */
    int seamTread(Cell c, boolean coarse, int x, int z, Caches k) {
        if (!treadGround(c)) return NO_TREAD;
        for (int d = 0; d < 4; d++) {
            int ax = x + (d == 0 ? 1 : d == 1 ? -1 : 0), az = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
            boolean co = coarseAt(ax + 0.5, az + 0.5, k);
            if (co == coarse) continue;
            cellOf(co, ax + 0.5, az + 0.5, k.ij, k);
            Cell n = cell(co, k.ij[0], k.ij[1]);
            if (!treadGround(n)) continue;
            int lo = Math.min(c.f, n.f), dd = Math.abs(c.f - n.f);
            if (dd < 2 || dd > 3 || lo < W || lo >= W + TREAD_TOP) continue;
            int h = lo + (dd + 1) / 2 - (c.f < n.f ? 1 : 0);
            if (h == c.f || h > c.c - 3) continue;
            return h;
        }
        return NO_TREAD;
    }

    /** A cell the low ground's stairs may cross: open from its floor, no ramp deck or rock band in it. */
    static boolean treadGround(Cell c) {
        return c.f < c.c && c.deckMode == 0 && c.bl >= c.bh;
    }

    /** The column's floor on the stairs (see {@link #tread}), NO_TREAD where it is its cell's; c is column(x, z). */
    int treadAt(Cell c, int x, int z) {
        Caches k = caches.get();
        int slot = (x & 31) << 5 | (z & 31);
        if (k.colX[slot] != x || k.colZ[slot] != z) column(x, z);
        return k.col[slot] == c ? k.colF[slot] : NO_TREAD;
    }

    /** The Causeway's coarse prisms where its weight passes a half. */
    boolean coarseAt(double x, double z, Caches k) {
        climate(x, z, k.cl);
        return k.cl.wCaus > 0.5;
    }

    // ============================================================================================ the profile
    /** Scratch for one cell's shape. */
    static final class Shape0 {
        double F, C, open, hang, rise, jt;
        /** The massif noise (minus its threshold: over 0 is massif) and the blocks to the massif's edge. */
        double mass, dm;
        /** The ceiling's cap (row, unrounded): T - 3 less the vault's relief, raised into the band over an opening's disc. */
        double cap;
        /** A ramp's deck over the cell: the first open row on it (NaN: none) and its thickness (rows; 1e9: filled to the floor). */
        double deck, deckTh;
        /** The rows the deck's ragged outer half drops at this cell. */
        int deckDrop;
        /** The tube mouth's weight at the cell, and the lowest ceiling the cell must keep (a ramp's or a mouth's head room; -1e9: none). */
        double mouth, minC;
        final double[] tmp2 = new double[2];
        boolean pillar, massif;
        /** Whether the cell's column top may stand FLOOR_OFF higher (flat ground, or a sub-biome). */
        boolean ragged;
        final double[] w = new double[6], w2 = new double[6];
        final RampHit rh = new RampHit();
    }

    /** Spires standing on the floor: grid spacing, chance per grid cell, radius range. */
    static final double SPIRE_GRID = 24, SPIRE_P = 0.2, SPIRE_R0 = 3.5, SPIRE_R1 = 8;
    /** Caldera stacks: grid spacing and chance per grid cell (a stack stands where its middle's Caldera weight passes 0.6). */
    static final double CONE_GRID = 30, CONE_P = 0.55;

    // ---- the landscape
    /** Domain warp of the large shapes: amplitude and period (blocks). */
    static final double WARP_A = 34, WARP_P = 130;
    /** The landscape's elevation: period, mean and amplitude over W (blocks). Its slopes stay under about 0.3. */
    static final double ELEV_P = 700, ELEV_MID = 52, ELEV_AMP = 85;
    /** Hills on the landscape: HL_A rows, 2 octaves of period HL_P (warped plane). */
    static final double HL_A = 18, HL_P = 260;
    /** Lava rivers: the zero lines of a noise of this period; their bed rises out of the lava as the land rises. */
    static final double RIVER_P = 430, RIVER_BANK = 0.22, RIVER_RISE = 3.0, RIVER_LOW = 22;
    /** Fords: where a noise of period FORD_P (warped plane) passes FORD_T (fully 0.15 on) a river's bed rises FORD_H rows. */
    static final double FORD_P = 45, FORD_T = 0.15, FORD_H = 7;
    // ---- one open cavern with standing massifs
    /**
     * Massifs: rock from floor to vault where a 2-octave noise of period M_P, read in the large shapes' warped plane,
     * passes M_T (about 30 % of the area). Weaker in the Caldera and Causeway basins (M_SUB x their weight) and none in a
     * tube country's apron. Within M_NEAR of the threshold the blocks to the edge are measured; the flare and the skirt
     * take effect over the first M_SEAM of that band (no seam where the measuring starts).
     */
    static final double M_P = 170, M_T = 0.21, M_SUB = 0.25, M_NEAR = 0.32, M_SEAM = 0.1;
    /**
     * The vault's relief under the cap (see {@link #vaultRelief}): VR0 + VR1 x (0.5 + a 2-octave noise of period VR_P)
     * plus the dome-and-sag term DS_A x a 2-octave noise of period DS_P, both linear (no clamp, so no plateau), with a
     * soft floor of width VR_K; eased off by VR_B under the breakthrough patches; VR2 x a fine noise of period VR2_P; all
     * of it down to VR_HF over high ground (VR_HR); and VR_MIN rows more everywhere but an opening's disc.
     */
    static final double VR0 = 3, VR1 = 24, VR_P = 90, VR2 = 5, VR2_P = 16, VR_B = 0.6, VR_HF = 0.35, VR_HR = 40,
            DS_A = 36, DS_P = 250, VR_K = 8, VR_MIN = 3;
    /**
     * The hanging clusters (see {@link #clusters}): a jittered lattice of HC_G blocks searched 5 x 5 (lobes reach under
     * HC_REACH < 2.15 HC_G); chance HC_P0..HC_P1 from a density noise of period HC_DP (gain HC_DS), times 0.35 + 0.9 x a
     * clumping noise of period HC_CP (gain HC_CG), which also grows the radius (0.8 + 0.5 x it): groups of clusters with
     * clear stretches between; radius HC_R0..HC_R1, HC_FR fringes, HC_PIL x the density pillars.
     */
    static final double HC_G = 44, HC_DP = 380, HC_DS = 1.1, HC_P0 = 0.2, HC_P1 = 1.0, HC_R0 = 16, HC_R1 = 34, HC_FR = 0.35, HC_PIL = 0.14,
            HC_CP = 170, HC_CG = 1.3;
    /** The farthest a cluster's lobe reaches from its lattice point: r max (HC_R1 x 1.2 x 1.3) x 1.45. */
    static final double HC_REACH = HC_R1 * 1.2 * 1.3 * 1.45;
    /** Spires stand SPIRE_H0..SPIRE_H0 + SPIRE_H1 rows (at most SPIRE_HF of the head room) and stop SPIRE_GAP rows under hanging rock. */
    static final double SPIRE_H0 = 8, SPIRE_H1 = 26, SPIRE_HF = 0.8, SPIRE_GAP = 10;
    /** A pillar's middle reaches the floor out to HC_PR0..+HC_PR1 blocks (at most 0.8 of its lobe), so pillars are stout. */
    static final double HC_PR0 = 5, HC_PR1 = 4;
    /** Column ends: each cell's ceiling hangs 0..CJ-1 rows under its vault (over a ramp only as far as its head room allows). */
    static final int CJ = 6, CJ_COARSE = 8;
    /** Flare: within FL_R blocks of a massif the vault comes down to (1 - FL_D) of the head room at the massif's face. */
    static final double FL_R = 22, FL_D = 0.4;
    /** Skirt: within SK_R blocks of a massif the floor rises up to SK_H rows (column stairs up to its foot). */
    static final double SK_R = 20, SK_H = 4;
    /** Fine floor relief: DT_A x a noise of period DT_P. */
    static final double DT_A = 1.2, DT_P = 40;
    /**
     * Terraces of the low ground (see {@link #terraces}): steps of TR_S rows from W up, fading out from W + TR_HI over
     * TR_HF rows. Each step is flat for tf of its height and rises over the rest: tf = min(TR_FMAX, 1 - slope / TR_RS) x
     * (TR_V0 + (1 - TR_V0) x smooth(0.5 + TR_M x a noise of period TR_P)), so the risers are never steeper than TR_RS rows
     * a block (1-row column steps, sometimes 2) and the treads run several cells deep where the ground is gentle; none
     * where tf is under TR_FW (steep ground keeps its own stairs). The slope is measured SL_D blocks apart.
     */
    static final double TR_S = 2, TR_HI = 40, TR_HF = 16, TR_FMAX = 0.8, TR_RS = 0.36, TR_V0 = 0.4, TR_M = 1.3, TR_P = 280, TR_FW = 0.2,
            SL_D = 2;
    /**
     * Rubble of the high plains (see {@link #rubble}): on a hash lattice of RB_G blocks a share RB_P x dens (a noise of
     * period RB_DP, gain RB_DG) holds a rubble mound (radius RB_R0..RB_R1, height RB_H0..RB_H1 of it) or, RB_RS of them, a
     * ridge of fallen columns (half length RB_L0..RB_L1, half width RB_W0..RB_W1, height RB_HR0..RB_HR1 of the half
     * width), profile H (1 - d^2)^2; faded in from W + RB_LO over RB_FADE rows, main galleries only.
     */
    static final double RB_G = 64, RB_P = 0.55, RB_DP = 500, RB_DG = 1.3, RB_R0 = 12, RB_R1 = 30, RB_H0 = 0.11, RB_H1 = 0.18,
            RB_RS = 0.4, RB_L0 = 12, RB_L1 = 30, RB_W0 = 7, RB_W1 = 14, RB_HR0 = 0.15, RB_HR1 = 0.26, RB_LO = 44, RB_FADE = 8;
    /** Massifs fade out in a tube country's apron from AP_M0 to AP_M0 + 0.5 of its weight. */
    static final double AP_M0 = 0.7;
    /**
     * Rivers end short of the Caldera's and the Causeway's basins: their beds rise RV_PATCH rows as the nearest such
     * patch's middle comes from RV_PD1 to RV_PD0 of its radius (warped plane), under a row a block. (Read from the
     * patches' climate weight, which comes in over a quarter of the radius, the beds rose 3 to 4 rows a block and stood
     * as walls of 5 to 9 rows over the lava: the last closed pits of seeds 77 and -42.) The Causeway's prisms stand
     * CAUS_B rows higher.
     */
    static final double RV_PATCH = 40, RV_PD0 = 0.8, RV_PD1 = 1.7, CAUS_B = 2;

    /** The rise of a river's bed toward the basins at the warped point w (rows; see {@link #RV_PATCH}). */
    double riverPatch(double[] w) {
        double px = w[0], pz = w[1], best = 9;
        int gi = (int) Math.floor(px / SP), gj = (int) Math.floor(pz / SP);
        Patch[] pc = patches.get();
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            Patch p = patch(pc, gi + di, gj + dj);
            if (p.kind != CALDERA && p.kind != CAUSEWAY) continue;
            best = Math.min(best, len(px - p.cx, pz - p.cz) / p.r);
        }
        return RV_PATCH * smooth((RV_PD1 - best) / (RV_PD1 - RV_PD0));
    }
    /** Rivers end short of the massifs: within RV_DM blocks of one the bed rises up to RV_DMH rows. Canyon slots pinch out where the ground is under W + CAN_LOW. */
    static final double RV_DM = 50, RV_DMH = 9, CAN_LOW = 44;
    /**
     * The apron's ramps: the floor keeps within AP_S a block of the apron's floor, and from AP_D0 on within AP_S + AP_R a
     * block (a slope the low ground's stairs, {@link #tread}, can climb), released from AP_D over AP_FD blocks. (Released
     * from AP_S alone, an apron standing over a river's valley ended in a cliff of 30 to 40 rows: seed 77's basin.) Over
     * lava the shelf is not released: it runs on down into the lava at its own slope (released, it ended in a ledge over
     * the lake 5 to 10 rows under the shelf, a pit).
     */
    static final double AP_S = 0.2, AP_D0 = 12, AP_R = 0.75, AP_D = 54, AP_FD = 10;
    // ---- the sub-biomes' terrain
    /** Caldera: a lava lake (LAKE x the patch radius) in a basin. */
    static final double CAL_LAKE = 0.5;
    /** Causeway: flats of prisms in the lava (FLAT x radius) in a basin. */
    static final double CAUS_FLAT = 0.6;
    /**
     * Scoria Canyons: lava slots (W - 4) along the zero lines of a noise of period CAN_P, sheer walls up to W + CAN_DRY,
     * then stepped cliffs of CAN_STEP rows up to the high ground; the terrain fades out with the patch's weight, and the
     * slots pinch out before it does (a slot's floor is lava or none, never in the flood's reach); they pinch out too
     * over CAN_AP blocks before a tube country's apron, which draws the floor toward the tubes' level (fix round 2b: it
     * lifted a slot's lava floor into a trench 20 rows deep, closed at the massif: a pit in seed -42's box).
     */
    static final double CAN_P = 160, CAN_STEP = 5, CAN_DRY = 40, CAN_AP = 8;
    /** Slope of the basins' shores: column stairs, 1 row in 3 to 4 blocks. */
    static final double SHORE_S = 0.22;
    /** A quarter of the cells (hashed) stand this many rows higher where the ground's slope is under FO_S: ragged column tops. */
    static final double FLOOR_OFF = 1, FO_S = 0.12;

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
        if (s.massif) {
            c.f = c.c = W + 200;
            return c;
        }
        c.open = (float) s.open;
        c.hang = (float) s.hang;
        c.rise = (float) s.rise;
        c.pillar = s.pillar;
        int f, ce;
        if (s.pillar || s.C - s.F < 2.5) {
            f = ce = W + 200;
        } else {
            // column ends: the floor's top stands a row higher on a quarter of the cells of flat ground
            double of = (c.h & 3) == 0 && s.ragged ? FLOOR_OFF : 0;
            f = (int) Math.floor(s.F + of + 0.5);
            ce = (int) Math.floor(s.C + 0.5);
            int hi = (int) Math.floor(s.cap + 0.5);
            f = Math.max(f, openLo);
            ce = Math.min(ce, hi);
            // the ceiling's column ends hang 0..lim rows lower, cell by cell, so no ceiling is a plane; lim is CJ - 1, or
            // what the head room over a ramp's deck or in a tube's mouth leaves (the share is scaled, not cut, so the
            // lengths stay spread)
            int lim = (coarse ? CJ_COARSE : CJ) - 1;
            if (s.minC > -1e8) lim = Math.min(lim, Math.max(0, ce - (int) Math.ceil(s.minC)));
            ce -= (int) ((lim + 1) * Hashing.unit(Hashing.mix(c.h, 11, 0, 0)));
            if (ce - f < 2) f = ce = W + 200;
        }
        c.f = f;
        c.c = ce;
        c.bl = c.bh = 0;
        if (f < ce) {
            // a cell whose site lies on the other side of the seam between the Causeway's coarse prisms and the fine
            // cells keeps only the few columns of its side (the others are nearer a site of the other lattice), so a deck
            // or a band in it could stand alone in mid-air: there a deck or a band is filled down to the ground (a deck
            // slab of one column floated in seed -42's box at (-3520, 6060), a band's in a coarse cell at (-4515, 7408))
            boolean stray = coarse != cl.wCaus > 0.5;
            if (s.deck == s.deck) deck(c, s, stray);
            else band(c, x, z, cl, s.mouth, stray);
        }
        return c;
    }


    /**
     * A ramp's deck in the cell: rock rows [deck - thickness, deck), open over it; solid down to the floor where it is
     * filled (a stray cell's, see {@link #build}) or less than 4 rows would stay open under it.
     */
    void deck(Cell c, Shape0 s, boolean filled) {
        int d0 = (int) Math.floor(s.deck + 0.5);
        if (d0 <= c.f) return;                       // the ground already stands at the deck
        if (d0 > c.c - 2) { c.f = c.c = W + 200; return; }
        int bl = s.deckTh > 1e8 ? Integer.MIN_VALUE : d0 - (int) Math.round(s.deckTh);
        RampHit rh = s.rh;
        c.dkX = rh.rx; c.dkZ = rh.rz; c.dkUx = rh.rux; c.dkUz = rh.ruz; c.dkT0 = rh.rt0; c.dkTop = rh.rtop; c.dkRamp = rh.ramp;
        c.dkRow = d0;
        c.dkDrop = s.deckDrop;
        c.dkGround = c.f;
        if (filled || bl <= c.f + 3) { c.f = d0; c.deckMode = 1; return; }     // filled: a ridge of columns on the ground
        c.bl = bl;
        c.bh = d0;
        c.deckMode = 2;
    }

    /**
     * The profile at block (x, y, z) of cell c: {@link #blocks(Cell, int)}, but on a ramp's deck the deck's row is read at
     * the block itself (the ramp's plane, falling s rows a block along its axis but less on its landings, {@link #deckDrop},
     * with its terraces, {@link #deckTerrace},
     * rounded), so walking along a ramp it steps a row at a time, never the 2 to 4 rows a whole cell's step would be, and
     * the steps' edges wander. The deck keeps 2 open rows under the cell's ceiling, stays on or over the ground, and an
     * arch's band keeps a row open under it or becomes a fill.
     */
    double blocks(Cell c, int x, int y, int z) {
        if (c.deckMode == 0) {
            int tf = treadAt(c, x, z);
            return tf == NO_TREAD ? blocks(c, y) : blocks(c, tf, c.c, c.bl, c.bh, y);
        }
        return deckBlocks(c, x, y, z);
    }

    /** {@link #blocks(Cell, int, int, int)} of a column cell with a ramp's deck. */
    double deckBlocks(Cell c, int x, int y, int z) {
        double t = (x + 0.5 - c.dkX) * c.dkUx + (z + 0.5 - c.dkZ) * c.dkUz;
        int row = (int) Math.floor(c.dkTop - deckDrop(c.dkRamp, t - c.dkT0) + deckTerrace(x + 0.5, z + 0.5, t - c.dkT0) + 0.5) - c.dkDrop;
        row = Math.min(row, c.c - 2);
        if (row == c.dkRow) return blocks(c, y);
        if (c.deckMode == 1) return blocks(c, Math.max(row, c.dkGround), c.c, 0, 0, y);
        int bl = c.bl + (row - c.dkRow);
        if (bl <= c.dkGround + 1) return blocks(c, Math.max(row, c.dkGround), c.c, 0, 0, y);
        return blocks(c, c.dkGround, c.c, bl, row, y);
    }

    /**
     * The Caldera and Causeway basins at the warped point w: {the shore cone (rows; +inf away from any), the mounds' cap}.
     * Each basin is flat at its lake out to CAL_LAKE (CAUS_FLAT) of its radius, then its shore rises SHORE_S a block of
     * the warped plane; the refuge mounds may stand over the shore by MD_SS a block from the lake's edge, at most MD_SH.
     * The lowest of the 5 x 5 patches round the point (so no seam).
     */
    double[] patchCone(double[] w) {
        double px = w[0], pz = w[1];
        int gi = (int) Math.floor(px / SP), gj = (int) Math.floor(pz / SP);
        double lo = 1e9, cap = 1e9;
        Patch[] pc = patches.get();
        for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
            Patch p = patch(pc, gi + di, gj + dj);
            if (p.kind == G || p.kind == CANYONS) continue;
            double d = len(px - p.cx, pz - p.cz);
            double q = Math.max(0, d - p.r * (p.kind == CALDERA ? CAL_LAKE : CAUS_FLAT));
            if (q * SHORE_S > 110) continue;
            double base = (p.kind == CALDERA ? W - 4 : W - 1) + SHORE_S * q;
            lo = Math.min(lo, base);
            cap = Math.min(cap, base + Math.min(MD_SH, MD_SS * q));
        }
        return new double[]{lo, cap};
    }

    static final double MD_SH = 60, MD_SS = 0.15;

    /**
     * The 2D shape at a cell's site: floor F and ceiling C (rows, unrounded), the cap, hanging length, spire rise, massif,
     * and a ramp's deck. In order: massifs; the floor (landscape, fine relief, rivers, rubble, skirts, terraces, column
     * stairs, the sub-biomes, a tube country's apron, a ramp's talus); the vault (the cap less its relief, flaring onto
     * the massifs, lifted under the breakthrough patches and into the opened openings); a ramp's deck and head room;
     * hanging clusters and spires, kept out of mouths, openings and ramps.
     */
    void shape(double x, double z, Climate cl, Shape0 out, long cellHash) {
        double[] w = out.w;
        warp(x, z, w);
        double wx = w[0], wz = w[1];
        double wMain = cl.wMain();
        out.massif = false;
        out.deck = Double.NaN;
        out.deckTh = 0;
        out.mouth = 0;
        // ---- the tube country's apron (TubeField): no massif stands in it
        double ap = tubesAt(x, z) ? tubes.apronAt(x, z) : 0;
        // ---- massifs
        double m = massN.fbm2(wx / M_P, wz / M_P, 2) - M_SUB * (cl.wCal + cl.wCaus) - 2 * smooth((ap - AP_M0) / 0.5);
        out.mass = m - M_T;
        if (m > M_T) {
            out.massif = true;
            out.F = out.C = out.cap = W + 200;
            out.open = 0; out.hang = 0; out.rise = 0; out.pillar = false; out.jt = 0; out.dm = 0;
            return;
        }
        double dm = 1e9, sw = 0;
        if (m > M_T - M_NEAR) {
            // blocks to the massif's edge: the noise's distance to its threshold over its gradient, through the warp; the
            // flare and the skirt come in over the first M_SEAM of the band where it is measured
            double gx = (massN.fbm2((wx + 1) / M_P, wz / M_P, 2) - massN.fbm2((wx - 1) / M_P, wz / M_P, 2)) * 0.5;
            double gz = (massN.fbm2(wx / M_P, (wz + 1) / M_P, 2) - massN.fbm2(wx / M_P, (wz - 1) / M_P, 2)) * 0.5;
            dm = (M_T - m) / Math.max(realGrad(gx, gz, w), 1e-6);
            sw = smooth((m - (M_T - M_NEAR)) / M_SEAM);
        }
        out.dm = dm;
        double fl = dm < FL_R ? sw * (1 - smooth(dm / FL_R)) : 0;
        // ---- the join: the opened openings' discs and the ramps round the column, and the breakthrough noise
        RampHit rh = out.rh;
        rampsAt(x, z, rh, cl.wCaus > 0.5 ? HW_CO : 0);
        double beta = join != null ? join.beta(x, z) : 0;
        out.open = 1 - fl;
        // ---- the floor
        double Fs = landscape(x, z, w);
        double clrR = Math.max(rh.clr, rh.near);   // the rubble keeps off the discs and the ramps
        double Fb = floorBase(x, z, w, Fs, cl, dm, clrR);
        double skirt = 0, skirtSlope = 0;
        if (dm < SK_R) {
            // the massifs' feet: column stairs up to the rock
            double q = dm / SK_R;
            skirt = SK_H * sw * (1 - smooth(q));
            skirtSlope = SK_H * sw * 6 * q * (1 - q) / SK_R;
        }
        double F = Fb + skirt;
        // the slope: the floor before the skirts SL_D blocks along x and z (the same climate, the massif's distance and the
        // clearing as here), and the skirt's own slope added to it; the low ground's terraces follow it, and the column
        // tops of the quarter of cells that may stand ragged (FLOOR_OFF) do so only where the ground is flatter than FO_S
        // (on a slope a raised top is a 2-row step). Other cells of the high ground need no slope.
        boolean low = F > W - 1 && F < W + TR_HI + TR_HF + 2, mayRag = (cellHash & 3) == 0;
        out.ragged = false;
        if (low || mayRag) {
            double fx = floorAt(x + SL_D, z, out.w2, cl, dm, clrR);
            double fz = floorAt(x, z + SL_D, out.w2, cl, dm, clrR);
            double slope = len(fx - Fb, fz - Fb) / SL_D + skirtSlope;
            out.ragged = slope < FO_S || cl.kind != G;
            if (low) F = terraces(F, slope, x, z);
        }
        F = terrace(F, 1, 0.5);
        // ---- sub-biomes
        if (cl.wCal > 0) F = Math.max(F, coneFloor(x, z));   // stacks stand in the lake, their craters the vents
        if (cl.wCaus > 0.2) {
            // prisms stepping out of the lava: the coarse cells' tops scatter round the table
            double st = CAUS_B + 2.4 * causN.noise2(x / 38.0, z / 38.0) + 1.2 * causN.noise2(x / 11.0 + 50, z / 11.0);
            F = lerp(F, F + st, smooth((cl.wCaus - 0.2) / 0.5) * smooth((W + 6 - F) / 6.0));
        }
        if (cl.wCan > 0) F = canyonFloor(x, z, w, cl, F);
        // ---- a tube country's apron (TubeField): the floor goes to the tubes' level; where a tube runs out of the massif
        // (its mouth) the space is kept open with head room
        double mouth = 0;
        double ad = tubesAt(x, z) ? -tubes.massifAt(x, z) - (TubeField.APRON_OUT - TubeField.APRON_FADE) : 1e9;
        if (ad < AP_D + AP_FD) F = terrace(apronFit(x, z, ad, F), 1, 0.5);
        if (ap > 0) {
            int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
            if (tubes.runs(ix, iz)) mouth = ap;
        }
        out.mouth = mouth;
        // ---- a ramp's talus beside its solid part: the floor rises to its deck, TALUS_S rows a block lower outwards, or
        // steeper where the column's own floor lies lower, so the talus meets it at its outer edge (measured against the
        // ground under the centre line it stood as a wall of up to 26 rows over a valley beside the ramp: seed -42's pit);
        // only on ground out of the flood's reach, W + TREAD_TOP or over: the talus lies cell by cell, a step of up to 10
        // rows a cell where a tall ridge's talus is steep, and in the low ground those steps walled in hollows between
        // the talus and the ground, dimples where two joints' stairs met and a shelf over the lava (seeds 8675309 and
        // -42, with the ridges' fill); there the ridge stands sheer on the ground (and none over lava, fix round 2b: in
        // a Scoria Canyons slot the talus filled the lava up to a dry shelf). Each ramp's talus is read at the column's
        // own floor and the highest kept ({@link RampHit#tn}).
        if (rh.talus > F && F >= W + TREAD_TOP) {
            double best = F;
            for (int i = 0; i < rh.tn; i++) {
                if (rh.tEst[i] <= F) continue;
                double tal = rh.tDk[i] - rh.tU[i] * Math.max(TALUS_S * TALUS_MAX, rh.tDk[i] - F);
                if (tal > best) best = tal;
            }
            // a ramp's deck in the column stays open through another ramp's talus (a ridge's talus, steep where it stands
            // high, buried the deck of a lower ramp 18 blocks off for 33 blocks of its run in seed 8675309's box)
            if (rh.deck == rh.deck) best = Math.min(best, Math.max(F, rh.deck));
            if (best > F) { F = Math.floor(best); out.ragged = false; }
        }
        // ---- the vault: the cap less its relief; over an opened opening's disc it rises into the band; flaring down onto
        // the massifs, lifted back toward the cap under the breakthrough patches
        double clr = rh.clr;
        double cap0 = openHi + 1;
        double relief = vaultRelief(x, z, beta, F, clr, rh.jt), cap = cap0 - relief;
        if (rh.rb == rh.rb) cap = Math.max(cap, Math.min(cap0, rh.rb));   // the relief stays over a ramp's head room
        // the openings' cuts into the vault (cut: the row they open the column to; hanging rock stays over it): a hole's
        // breakdown funnel first, the vault's underside rising toward the hole (never over T - FUN_TOP)
        double cut = rh.fun > -1e8 ? rh.fun : Double.NaN;
        if (cut > cap) cap = cut;
        if (mouth > 0) cap = Math.max(cap, Math.min(cap0, F + TubeField.APRON_HEAD * mouth + 2));   // and over a tube's mouth
        out.jt = rh.jt;
        if (rh.jt > 0) cap = lerp(cap, rh.jtTop, rh.jt);
        double C = Fs + (cap - Fs) * (1 - FL_D * fl);
        if (beta > 0.35) C = Math.max(C, lerp(C, cap, smooth((beta - 0.35) / 0.25) * (1 - fl)));
        if (cut > C) C = lerp(cut, C, fl);   // the funnel holds but where the vault flares down onto a massif
        if (rh.jt > 0) C = Math.max(C, cap);
        out.minC = -1e9;
        // the column ends hang at most FUN_J rows under the funnel, so its underside stays a cone, ragged cell by cell
        if (rh.fun > -1e8) out.minC = rh.fun - FUN_J;
        if (mouth > 0) {
            C = Math.max(C, F + TubeField.APRON_HEAD * mouth);
            out.minC = Math.max(out.minC, F + TubeField.APRON_HEAD * mouth);
        }
        // ---- a ramp's deck: ragged edges, an arch under its free span, filled to the floor where it comes near it; head
        // room over it, and a breach over its first stretch
        out.deckDrop = 0;
        if (rh.deck == rh.deck) {
            double dk = rh.deck;
            if (rh.edge > 0 && dk - F >= EDGE_G) {
                out.deckDrop = (int) Math.floor(rh.edge * (EDGE_LOW + 1) * Hashing.unit(Hashing.mix(cellHash, 12, 0, 0)));
                dk -= out.deckDrop;
            }
            out.deck = dk;
            double gap = dk - rh.th0 - F;
            if (rh.fill || gap <= rh.dkFill) out.deckTh = 1e9;
            else {
                double sp = (rh.sig + DK_ROOT) / (1 + DK_ROOT);
                double a = 4 * sp * (1 - sp);
                out.deckTh = rh.th0 + gap * (1 - a) + DK_J * Hashing.unit(Hashing.mix(cellHash, 10, 0, 0));
            }
            // where the vault comes down near the deck, its head room grows along the first stretch: a breach up into the
            // vault, under BR_F of its relief (so it keeps the relief's lie and never lies flat at the cap), not a cut
            // stair tunnel
            double top = Math.min(cap0, rh.deck + RAMP_HEAD);
            if (C < rh.deck + RAMP_HEAD + BR_M)
                top = Math.max(top, Math.min(cap0 - BR_F * relief, rh.deck + RAMP_HEAD + HR_G * rh.deckU * breach(rh.deckU)
                        + HR_J * Hashing.unit(Hashing.mix(cellHash, 14, 0, 0))));
            // where the crater's floor lies a little over the deck's head room (the gully, {@link #CR_T}) the head room
            // reaches it, so the ramp runs down in a slot open to the crater, never under a lid of the floor's rock 1 to 3
            // rows thick (2 rows of it lidded the ramp at the gully's end and cut its shelf off as a shut opening: JoinTest's
            // seed 1 at (268, 423)); deeper the crater's floor runs on over the ramp as a natural bridge, and the way up
            // from the ramp's foot crosses the slot on it (opened, the slot cut that way off: seed 1 at (442, 318), decks
            // one row down)
            boolean gully = rh.gf + 1 <= cap0 && rh.gf - rh.deck <= RAMP_HEAD + 2;
            if (gully) top = Math.max(top, rh.gf + 1);
            C = Math.max(C, top);
            cap = Math.max(cap, top);
            cut = cut == cut ? Math.max(cut, top) : top;
            out.minC = Math.max(out.minC, dk + RAMP_HEAD - 2);
            if (gully) out.minC = Math.max(out.minC, rh.gf + 1);
        }
        // an opening's disc opens to its crater's floor, so no column end hangs into the hole there
        if (rh.jt >= 0.5) out.minC = Math.max(out.minC, cap);
        C = Math.min(C, cap);
        out.cap = cap;
        double head = C - F;
        double clear = (1 - mouth) * (1 - clr);
        double cuBot = 1e9;
        // ---- hanging rock: clumped clusters of columns (fringes, masses, a few pillars)
        double hang = 0;
        boolean pillar = false;
        if (head > 6) {
            double[] hc = out.tmp2;
            clusters(x, z, head, cellHash, hc);
            hang = hc[0];
            pillar = hc[1] > 0;
        }
        // ---- spires standing on the floor (few: their tops are perches), on dry ground only (fix round 2b: a spire's foot
        // in a Caldera lake left ledges over the lava under its flanks, a pit in seed -42's box)
        double up = 0;
        if (head > 8 && F >= W) {
            int gi = (int) Math.floor(x / SPIRE_GRID), gj = (int) Math.floor(z / SPIRE_GRID);
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
                long h = Hashing.mix(seed, 35001, gi + di, gj + dj);
                if (Hashing.unit(h) > SPIRE_P * (0.4 + 0.6 * wMain)) continue;
                double sx = (gi + di + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * SPIRE_GRID;
                double sz = (gj + dj + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * SPIRE_GRID;
                double r = Hashing.range(Hashing.mix(h, 3, 0, 0), SPIRE_R0, SPIRE_R1);
                double d = len(x - sx, z - sz) / r;
                if (d >= 1) continue;
                double hgt = Math.min(SPIRE_HF * head, SPIRE_H0 + SPIRE_H1 * Hashing.unit(Hashing.mix(h, 4, 0, 0)));
                double vv = hgt * StrictMath.pow(1 - d, 0.8) * (0.8 + 0.4 * Hashing.unit(Hashing.mix(cellHash, 6, 0, 0)));
                if (vv > up) up = vv;
            }
        }
        // clusters, pillars and spires stay out of the tubes' mouths, the openings and the ramps; no pillars on skirts
        hang *= clear;
        up *= clear * (1 - rh.near);
        if (clear < 0.7) pillar = false;
        if (dm < SK_R) pillar = false;
        if (rh.rb == rh.rb) {
            // over a ramp: a pillar stands under its deck, spires none, clusters end over its head room
            if (pillar) cuBot = -1e9;
            pillar = false;
            up = 0;
            hang = Math.min(hang, Math.max(0, C - rh.rb));
            if (out.deck == out.deck && out.deckTh < 1e8 && cuBot < out.deck - out.deckTh) out.deckTh = 1e9;
        }
        if (cut == cut) {
            // in a funnel or a breach: no pillar, clusters end over the cut
            pillar = false;
            hang = Math.min(hang, Math.max(0, C - cut));
        }
        if (up > 0 && hang > 0) up = Math.min(up, Math.max(0, head - hang - SPIRE_GAP));
        if (F + up >= C - hang - 4) pillar = pillar || (hang > 0 && up > 0) || up > head - 6;
        out.F = F + up;
        out.C = C - hang;
        out.hang = hang;
        out.rise = up;
        out.pillar = pillar;
    }

    /**
     * The floor before the massifs' skirts, the terraces and the sub-biomes (rows, unrounded): the landscape Fs, the fine
     * relief, the lava rivers with their fords and banks, and the high plains' rubble. {@code w} is the warp at (x, z).
     */
    double floorBase(double x, double z, double[] w, double Fs, Climate cl, double dm, double clr) {
        double F = riverFloor(x, z, w, Fs, riverPatch(w), dm < RV_DM ? RV_DMH * (1 - smooth(dm / RV_DM)) : 0);
        double rw = smooth((F - W - RB_LO) / RB_FADE) * (1 - clr) * cl.wMain();
        if (rw > 0) F += rw * rubble(x, z);
        return F;
    }

    /**
     * The landscape Fs with the fine relief and the lava rivers' valleys (rows, unrounded): the bed sheer up to the table,
     * then gentle banks, RIVER_BANK rows a block (so a river's valley reaches far out into the low landscape); fords where
     * a noise is high (the bed rises out of the lava, its banks all gentle). The bed rises by {@code patch} toward the
     * Caldera's and the Causeway's basins ({@link #riverPatch}) and by {@code foot} at the massifs' feet. {@code w} is the warp at (x, z).
     */
    double riverFloor(double x, double z, double[] w, double Fs, double patch, double foot) {
        double F = Fs + DT_A * detailN.noise2(x / DT_P, z / DT_P);
        if (Fs < W + RIVER_LOW + 16) {
            double dr = lineDistW(riverN, w, RIVER_P);
            double wc = 3.5 + 4 * smooth(0.5 + riverW.noise2(x / 90.0, z / 90.0));
            double q = Math.max(0, dr - wc);
            double ford = FORD_H * smooth((fordN.noise2(w[0] / FORD_P, w[1] / FORD_P) - FORD_T) / 0.15);
            double bed = W - 4 + RIVER_RISE * Math.max(0, Fs - (W + RIVER_LOW)) + ford + patch + foot;
            double sheer = Math.max(0, Math.min(5, W + 1 - bed));
            F = Math.min(F, bed + Math.min(q, sheer) + RIVER_BANK * Math.max(0, q - sheer));
        }
        return F;
    }

    /**
     * How free column (x, z) is of a tube country's apron for the canyons' slots: 1 where no apron reaches it, falling to 0
     * over CAN_AP blocks before the apron's released stretch ({@link #AP_D} + {@link #AP_FD} from the massif's edge).
     */
    double apronFree(double x, double z) {
        if (!tubesAt(x, z)) return 1;
        double ad = -tubes.massifAt(x, z) - (TubeField.APRON_OUT - TubeField.APRON_FADE);
        return smooth((ad - AP_D - AP_FD) / CAN_AP);
    }

    /**
     * The Scoria Canyons over the floor F at (x, z) (where cl.wCan > 0): lava slots along the zero lines of a noise, sheer
     * walls up to the dry ground, then stepped cliffs up to the ridges; the terrain fades out with the patch's weight, the
     * slots pinch out before it fades and before a tube country's apron ({@link #apronFree}). Never over F. {@code w} is
     * the warp at (x, z).
     */
    double canyonFloor(double x, double z, double[] w, Climate cl, double F) {
        double dc = lineDistW(canN, w, CAN_P);
        double cw0 = smooth((cl.wCan - 0.15) / 0.35);
        double fw = (2.5 + 3.5 * smooth(0.5 + canW.noise2(x / 70.0, z / 70.0))) * smooth((cw0 - 0.8) / 0.15)
                * smooth((F - (W + CAN_LOW)) / 8.0) * apronFree(x, z);
        double ws = 1.0 + 1.8 * smooth(0.5 + canW.noise2(x / 110.0 + 20, z / 110.0));
        double u = dc - fw;
        double Fk = fw > 0 && u <= 0 ? W - 4 : W + CAN_DRY + terrace(ws * Math.max(0, u), CAN_STEP, 0.45) + 300 * (1 - cw0);
        return Math.min(F, Fk);
    }

    /**
     * A tube country's apron: the floor F at a column {@code ad} blocks out past the apron's fade (ad under AP_D + AP_FD)
     * drawn toward the tubes' level, to within AP_S x ad (+ AP_R x (ad - AP_D0)) rows of it (unrounded).
     */
    double apronFit(double x, double z, double ad, double F) {
        double af = tubes.apronFloorAt(x, z);
        double lim = AP_S * Math.max(0, ad) + AP_R * Math.max(0, ad - AP_D0), rel = 1000 * smooth((ad - AP_D) / AP_FD);
        // over lava the shelf is not released: it runs on down into the lava at its own slope (released there, it ended
        // in a ledge 5 to 10 rows under the shelf, over the lake: closed pits in seed 8675309's Caldera)
        double down = F < W ? lim : lim + rel;
        return af + Math.max(-down, Math.min(lim + rel, F - af));
    }

    /**
     * The ground a ramp is laid against at (x, z) (rows, unrounded): the floor a player meets there before any ramp, less
     * the parts that only raise it by a few rows (the massifs' feet, the rubble, the terraces' and prisms' steps): the
     * landscape with the lava rivers' valleys cut in (their beds rising over the patches' shores), the Caldera's stacks,
     * the Canyons' slots and cliffs, and a tube country's apron drawn to the tubes' level. {@code w} holds the warped point
     * at (x, z) (its Jacobian is filled in here where the rivers or the canyons read it), {@code cl} its climate.
     */
    double rampGround(double x, double z, double[] w, Climate cl) {
        double Fs = landscape(x, z, w);
        // the rivers and the canyons read the warp's Jacobian: only they need it
        if (Fs < W + RIVER_LOW + 16 || cl.wCan > 0) warpJ(x, z, w);
        double F = riverFloor(x, z, w, Fs, riverPatch(w), 0);
        if (cl.wCal > 0) F = Math.max(F, coneFloor(x, z));
        if (cl.wCan > 0) F = canyonFloor(x, z, w, cl, F);
        if (tubesAt(x, z)) {
            double ad = -tubes.massifAt(x, z) - (TubeField.APRON_OUT - TubeField.APRON_FADE);
            if (ad < AP_D + AP_FD) F = apronFit(x, z, ad, F);
        }
        return F;
    }

    /** {@link #floorBase} at (x, z) with its own warp in w (the Jacobian only where the rivers need it). */
    double floorAt(double x, double z, double[] w, Climate cl, double dm, double clr) {
        warpXZ(x, z, w);
        double Fs = landscape(x, z, w);
        if (Fs < W + RIVER_LOW + 16) warpJ(x, z, w);
        return floorBase(x, z, w, Fs, cl, dm, clr);
    }

    /** The low ground's terraces (see {@link #TR_S}) on the floor F of the given slope (rows a block). */
    double terraces(double F, double slope, double x, double z) {
        double v = F - W;
        if (v <= 0 || v >= TR_HI + TR_HF) return F;
        double tf = Math.min(TR_FMAX, 1 - slope / TR_RS) * (TR_V0 + (1 - TR_V0) * smooth(0.5 + TR_M * stepN.noise2(x / TR_P, z / TR_P)));
        double wgt = smooth(tf / TR_FW) * smooth(v / 2) * (1 - smooth((v - TR_HI) / TR_HF));
        if (wgt <= 0) return F;
        return W + lerp(v, terrace(v, TR_S, tf), wgt);
    }

    /** The high plains' rubble at (x, z): the highest mound or fallen-column ridge of the 3 x 3 lattice cells round it (rows). */
    double rubble(double x, double z) {
        int gi = (int) Math.floor(x / RB_G), gj = (int) Math.floor(z / RB_G);
        double best = 0, reach = Math.max(RB_R1, RB_L1);
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            long h = Hashing.mix(seed, 54001, gi + di, gj + dj);
            double cx = (gi + di + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * RB_G;
            double cz = (gj + dj + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * RB_G;
            double dx = x - cx, dz = z - cz;
            if (Math.abs(dx) > reach || Math.abs(dz) > reach) continue;
            double dens = smooth(0.5 + RB_DG * rubN.noise2(cx / RB_DP, cz / RB_DP));
            if (Hashing.unit(h) >= RB_P * dens) continue;
            double d2, H;
            if (Hashing.unit(Hashing.mix(h, 5, 0, 0)) < RB_RS) {
                int a = dirOf(Hashing.unit(Hashing.mix(h, 6, 0, 0)));
                double L = Hashing.range(Hashing.mix(h, 3, 0, 0), RB_L0, RB_L1), Wd = Hashing.range(Hashing.mix(h, 4, 0, 0), RB_W0, RB_W1);
                double al = (dx * DIR_X[a] + dz * DIR_Z[a]) / L, ac = (dx * DIR_Z[a] - dz * DIR_X[a]) / Wd;
                d2 = al * al + ac * ac;
                H = Wd * Hashing.range(Hashing.mix(h, 7, 0, 0), RB_HR0, RB_HR1);
            } else {
                double R = Hashing.range(Hashing.mix(h, 3, 0, 0), RB_R0, RB_R1);
                d2 = (dx * dx + dz * dz) / (R * R);
                H = R * Hashing.range(Hashing.mix(h, 7, 0, 0), RB_H0, RB_H1);
            }
            if (d2 >= 1) continue;
            double v = H * (1 - d2) * (1 - d2);
            if (v > best) best = v;
        }
        return best;
    }

    /**
     * The vault's relief under the cap (rows). The broad part is VR0 + VR1 x (0.5 + a 2-octave noise of period VR_P)
     * plus the dome-and-sag term DS_A x a 2-octave noise of period DS_P (domes and lower bays a few hundred blocks
     * across), both linear, so the vault has no plateau: where the sum falls toward 0 a soft floor of width VR_K, (b +
     * sqrt(b^2 + VR_K^2)) / 2, rounds the dome's top off instead of clipping it. It is eased off by VR_B under the
     * breakthrough patches (the domes rise to the openings) and gone in the openings' clearing rings (clr). Plus VR2 x a
     * fine noise of period VR2_P (the columns' ends in patches), gone over the openings' discs (jt); all of it down to
     * VR_HF over the highest ground (where the floor comes within VR_HR rows of the cap), so high ground keeps its head
     * room; and VR_MIN rows everywhere but over an opening's disc, so no vault lies flat against the layer's top.
     */
    double vaultRelief(double x, double z, double beta, double F, double clr, double jt) {
        double b = VR0 + VR1 * (0.5 + roofN.fbm2(x / VR_P, z / VR_P, 2)) + DS_A * sagN.fbm2(x / DS_P, z / DS_P, 2);
        double broad = 0.5 * (b + Math.sqrt(b * b + VR_K * VR_K));
        double r = broad * (1 - VR_B * smooth((beta - 0.3) / 0.3)) * (1 - clr)
                + VR2 * (0.5 + ceilN2.noise2(x / VR2_P, z / VR2_P)) * (1 - jt);
        return VR_MIN * (1 - jt) + Math.max(0, r) * (VR_HF + (1 - VR_HF) * smooth((openHi + 1 - F - VR_HR) / 30.0));
    }

    /**
     * The hanging rock: a jittered lattice of HC_G blocks (5 x 5 searched, see {@link #HC_G}); a lattice point holds a
     * cluster with a chance from the density and clumping noises. A cluster is 1 to 3 lobes round its point (irregular
     * footprints); its kind from its hash: a fringe (HC_FR of them: 0.06..0.2 of the head room, broad), a mass (0.28..0.62)
     * or a pillar (HC_PIL x the density: its middle lobe's core reaches the floor, the cluster round it its flared top).
     * Each column cell hangs its own share (0.55..1) of the profile: columns at many lengths. out = {length, 1 when a
     * pillar stands here}.
     */
    void clusters(double x, double z, double head, long cellHash, double[] out) {
        int gi = (int) Math.floor(x / HC_G), gj = (int) Math.floor(z / HC_G);
        double best = 0;
        boolean pillar = false;
        double rag = Hashing.unit(Hashing.mix(cellHash, 5, 0, 0));
        for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
            long h = Hashing.mix(seed, 34101, gi + di, gj + dj);
            double cx = (gi + di + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * HC_G;
            double cz = (gj + dj + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * HC_G;
            if (Math.abs(x - cx) > HC_REACH || Math.abs(z - cz) > HC_REACH) continue;
            double dens = smooth(0.5 + HC_DS * densN.noise2(cx / HC_DP, cz / HC_DP));
            double clump = smooth(0.5 + HC_CG * clumpN.noise2(cx / HC_CP, cz / HC_CP));
            if (Hashing.unit(h) > (HC_P0 + (HC_P1 - HC_P0) * dens) * (0.35 + 0.9 * clump)) continue;
            double r = Hashing.range(Hashing.mix(h, 3, 0, 0), HC_R0, HC_R1) * (0.8 + 0.4 * dens) * (0.8 + 0.5 * clump);
            double uc = Hashing.unit(Hashing.mix(h, 4, 0, 0)), us = Hashing.unit(Hashing.mix(h, 6, 0, 0));
            int kind = uc < HC_FR ? 0 : (uc > 1 - HC_PIL * dens ? 2 : 1);
            int nl = 1 + (int) (3 * Hashing.unit(Hashing.mix(h, 5, 0, 0)));
            for (int l = 0; l < nl; l++) {
                double lx = cx, lz = cz, lr = r * 0.62;
                if (l > 0) {
                    int a = dirOf(Hashing.unit(Hashing.mix(h, 10 + l, 0, 0)));
                    double off = r * (0.45 + 0.4 * Hashing.unit(Hashing.mix(h, 20 + l, 0, 0)));
                    lx += off * DIR_X[a];
                    lz += off * DIR_Z[a];
                    lr = r * (0.3 + 0.3 * Hashing.unit(Hashing.mix(h, 30 + l, 0, 0)));
                }
                double d = len(x - lx, z - lz) / lr;
                if (d >= 1) continue;
                double prof = StrictMath.pow(1 - d * Math.sqrt(d), 0.7);
                double ln;
                if (kind == 0) ln = head * (0.06 + 0.14 * us) * (0.6 + 0.4 * prof) * (0.5 + 0.5 * rag);
                else if (kind == 1) ln = head * (0.28 + 0.34 * us) * prof * (0.55 + 0.45 * rag);
                else {
                    ln = head * (0.4 + 0.25 * us) * prof * (0.55 + 0.45 * rag);
                    if (l == 0 && d * lr < Math.min(0.8 * lr, HC_PR0 + HC_PR1 * Hashing.unit(Hashing.mix(h, 7, 0, 0)))) pillar = true;
                }
                if (ln > best) best = ln;
            }
        }
        out[0] = best;
        out[1] = pillar ? 1 : 0;
    }

    // ============================================================================================ the join's openings
    /*
     * The Wasteland's openings as the Galleries take them. The Wasteland offers openingSite(x, z). The Galleries find
     * each opening's middle (the centroid of the samples where that weight passes a half, on a grid of OP_S blocks,
     * searched per lattice cell of OP_G blocks with a margin of OP_M; an opening belongs to the lattice cell holding its
     * middle) and take every one off the massifs and the tube countries as a candidate. A candidate that keeps a ramp
     * opens: the cap over a disc round its middle (radius r + 1, fading to r + LIP) opens up to the floor of the crater
     * the Wasteland's lowest sheet makes round it ({@link #craterFloor}). The guard closes every other opening.
     *
     * Ramps. Every candidate offers up to RAMP_DIRS ramp candidates (one per direction from a hashed start): a deck
     * from the disc's lip (r + LIP along its axis), whose first open row there is HEAD_DROP rows under the layer's top
     * (the crater's floor), falling s = RAMP_S0..RAMP_S1 rows a block along its axis, RAMP_HW0..RAMP_HW1
     * blocks to either side of a centre line that bends sideways (bendA x a slow noise along the axis, fading in over
     * BEND_IN blocks), down to the ground under it ({@link #rampGround}: the deck, its fill and its talus are laid against
     * the ground a player meets, so no filled body or talus stands as a wall across a river's valley or a canyon, and no
     * deck runs on buried under a stack or a tube country's apron); a candidate must stay off massifs and tube countries, land on dry ground off
     * the canyons, the Caldera and the rivers (W + 6 at least; W + 40 or more preferred by 60 blocks), and keep all it
     * touches within RAMP_REACH of the middle. The candidates are taken in the order of a cheap estimate ({@link
     * #rampOrder}: the bent centre line followed, the ground read every RO_STEP samples, the landing's estimated length,
     * long for a low landing; a direction the estimate already finds blocked is never built), and each is built only when
     * a pass first asks for it. Which candidate an opening builds is decided in RAMP_PASSES + 1 passes (see {@link
     * #choice}) by priority (the lattice cell's hashed priority, then the opening's hash), so that no two ramps (their
     * heads' half discs of r + 3.5 included) come within RAMP_GAP blocks:
     * pass 0 takes each opening's first candidate; pass k its first candidate clear of the pass k-1 choices of all
     * higher-priority openings; the last pass keeps the pass-RAMP_PASSES choice only if it is clear of the same pass's
     * choices of all higher-priority openings, else the opening has no ramp and stays shut. Two kept ramps never conflict:
     * the lower of the two was checked against the higher's last choice, which is the higher's ramp if it has one. Since
     * priority goes by lattice cell, an opening's rivals lie only in the cells round it of a higher priority (and its own),
     * so a resolution reads about 850 raw cells for a cold chunk, not every cell of its cone.
     */
    /** A ramp's head keeps the half disc of r + 3.5 on its side of DK_T x the radius past the middle clear of other ramps. */
    static final double DK_T = 0.1;
    /**
     * A ramp's head: its first open row is HEAD_DROP rows under the layer's top, at the disc's lip LIP blocks past the
     * opening's radius (where the disc's weight ends). Its deck starts there, so nothing of it lies over the hole.
     */
    static final double HEAD_DROP = 5, LIP = 2.5;
    /**
     * The crater the Wasteland's lowest sheet makes round an opening with a ramp ({@link #craterFloor}): its floor's rock
     * top CR_DROP rows under the layer's top (one row under the ramp's head, on the Galleries' guaranteed rock) out to
     * r + CR_IN, rising CR_S rows a block beyond, out to CR_REACH blocks further. The disc opens up to that floor only.
     */
    static final double CR_DROP = 6, CR_IN = 4, CR_S = 0.65, CR_REACH = 42;
    /**
     * The crater's rim and the ramp's gully (the ways down as natural rock: a level collar ran the whole way round every
     * hole, and a ramp's first stretch ran under the layer's top rows, a lidded slot): {@link #ringOf} reads the ring up to
     * CR_T blocks nearer over the ramp's first CR_TU blocks past the lip, so the crater's floor stays down at the head's
     * level over the deck and the ramp runs down in a gully open to the crater; beside the deck the gully's flanks climb a
     * ring every CR_TK blocks, and past CR_TU its end a ring every CR_TE blocks along the axis (the ring then grows
     * 1 + 1 / CR_TE a block, the floor 0.87 rows), so both are walked up to the bowl and on into the Wasteland (as steep walls, 3 blocks
     * wide, the flanks left the head no way up to the crater's bowl at 15 of 45 holes of JoinTest's seeds 1 and 2; an end
     * fading over 6 blocks rose 2 to 3 rows a block, and the crater's floor round 7 of 23 ramps' heads in JoinTest's three
     * boxes walked into the Wasteland only round by another hole, 240 to 714 moves). Off the ramp's side of the
     * rim (from about 32 degrees off its axis: the bearing's cosine under CR_LA, fully from 57 degrees, CR_LF less), and
     * out of the gully, the ring reads up to CR_B blocks further, by smooth(CR_BM + CR_BG x a noise of the bearing of
     * scale CR_BK), rising from the hole's edge over CR_BQ blocks, so the rim there rises up to CR_B x CR_S rows and
     * breaks, and a lip of a block or two stays low to look down from: the level tread is the ramp's side only.
     */
    static final double CR_T = 14, CR_TU = 16, CR_TE = 3, CR_TW = 3, CR_TK = 1.5, CR_LA = 0.85, CR_LF = 0.3, CR_B = 10,
            CR_BK = 1.1, CR_BM = 0.75, CR_BG = 1.4, CR_BQ = 3;
    /**
     * The hole's outline ({@link #rimR}): off the ramp's side (the same bearings as the broken rim) its radius is r plus
     * RIM_A x a noise of the bearing of scale RIM_K, so the rock juts into the hole in spurs and falls back in bays a few
     * blocks across; on the ramp's side it is r, so the head meets the lip as before. The disc, the crater's ring and the
     * funnel all read it.
     */
    static final double RIM_A = 3, RIM_K = 3.5;
    /**
     * A hole's breakdown funnel (holes were vertical chimneys through the vault): under the crater's floor the vault's
     * underside rises toward the hole, its first rock row FUN_TOP rows under the layer's top at the disc's lip (r + LIP)
     * and FUN_K rows a block lower outwards, out to FUN_R blocks past the lip, flaring down FUN_DROP rows more over its
     * last FUN_FO blocks so it meets the vault round it without a ledge (cut off flat at FUN_R, the ceiling fell 16 rows
     * or more within 3 columns of its edge on a tenth of the bearings from seed 5150's holes, against 9 elsewhere; with
     * the flare 8, as elsewhere); a column's vault is raised to it, hanging rock ends over it and no pillar stands under
     * it. FUN_K is under tan 31 degrees, so a look down through the hole at 30 degrees or steeper passes under the
     * funnel's far side into the cavern; the column ends ({@link #CJ}) hang up to FUN_J rows under it, cell by cell (held
     * at it, its rings of one row lay flat: 132 columns at T - 7 in seed 8675309's box, against the flat-lid test's 64).
     */
    static final double FUN_TOP = 6, FUN_K = 0.6, FUN_R = 43, FUN_FO = 20, FUN_DROP = 60, FUN_J = 3;
    /**
     * The breach over a ramp's first stretch (a constant head room read as a cut stair tunnel with a flat lid). Where the
     * vault comes within RAMP_HEAD + BR_M rows of the deck, the head room is RAMP_HEAD + HR_G x u (u blocks past the head;
     * the rise fades out from BR_U over BR_UF more) plus 0..HR_J rows per cell, up to the vault less BR_F of its relief
     * (so the breach keeps the relief's lie: lifted to the cap itself, T - 3, beside the decks as well, it lay flat there
     * in patches of up to 93 columns in seed -42's box, against the flat-lid test's 64).
     */
    static final double HR_G = 0.7, HR_J = 6, BR_M = 4, BR_U = 64, BR_UF = 16, BR_F = 0.5;
    static final double OP_G = 64;
    static final int OP_S = 3, OP_M = 18, OP_SEED = 6, RAMP_DIRS = 16;
    /**
     * Ramps: head room RAMP_HEAD (more over the first stretch, {@link #HR_G}); run at most RAMP_MAX along the axis,
     * sampled every RAMP_DT; slope (so gentle that with the deck's terraces, {@link #DK_TA}, the deck read block by block
     * steps a row at a time), the head's half width ({@link #HW_LO} along the run) and base thickness ranges; bends of
     * BEND_A0..+BEND_A1 blocks (a noise of period BEND_P along the axis, in over BEND_IN; 8..22 of period 60 before the
     * ways down were reshaped, when the largest sideways bend over the chord was a median 0.04); RAMP_GAP blocks kept
     * between any two ramps (6 before fix round 2b, 5 before the reshape: the bent ramps take more room, and at 4 the
     * Wasteland test's three squares and JoinTest's three boxes keep their bars, 19 holes and 11 a box at the least).
     */
    static final double RAMP_HEAD = 7, RAMP_MAX = 175, RAMP_DT = 4, RAMP_S0 = 0.55, RAMP_S1 = 0.78,
            RAMP_HW0 = 3.5, RAMP_HW1 = 5, RAMP_TH0 = 6, RAMP_TH1 = 4, BEND_A0 = 20, BEND_A1 = 30, BEND_P = 45, BEND_IN = 24, RAMP_GAP = 4;
    /**
     * A ramp's body: filled to the floor from where the free height under its base thickness first comes within DK_FILL
     * rows (tg, along the centre line, and wherever it does at a cell); before that an arch: thickness th0 + gap x (1 - a),
     * a = 4 s' (1 - s') (a parabola), s' = (s + DK_ROOT) / (1 + DK_ROOT), s the place along the free span (0 the head, 1
     * tg), so the underside springs from a hanging root under the head (0.29 of the gap), is thinnest at about a third of
     * the span, and curves down to the floor at tg; plus DK_J rows of column ends per cell. The deck's outer half drops 0..EDGE_LOW rows per cell
     * (ragged column tops), fading in over the head's first EDGE_IN blocks (so the head's whole width meets the crater's
     * floor beside it). Beside the solid part a talus: the floor rises to the deck, falling TALUS_S rows a block
     * outwards, or steeper where the deck stands higher over the ground under its centre line (so that it meets the
     * ground TALUS_MAX blocks out). The solid part starts at ts, where the arch's opening over the ground (less the column
     * ends) first stays under TS_OPEN rows to tg, so no stretch of the body stands as a wall without a walkable flank.
     */
    static final double DK_FILL = 7, DK_ROOT = 0.3, DK_J = 4, EDGE_LOW = 2, EDGE_IN = 3, TALUS_S = 0.35, TALUS_MAX = 32, TS_OPEN = 3;
    /**
     * The ragged edge drops only where the deck stands EDGE_G rows or more over its cell's ground (where the ground came
     * up to the deck, a dropped edge cell lay between the deck and higher ground, a pit in seed -42's box). Each column
     * keeps the TAL_N highest ramps' taluses ({@link RampHit#tn}). Spires and the rubble keep off the ramps: fully to
     * NEAR_IN blocks past a deck's edge, coming back over NEAR_F more (a spire's foot beside a ramp's end walled its
     * ragged edge in, and the talus raised the ground round a spire into a closed pit: seeds -42 and 8675309).
     */
    static final double EDGE_G = 6, NEAR_IN = 16, NEAR_F = 16;
    static final int TAL_N = 4;
    /**
     * The ways down vary per hole and along each run (every hole had the same straight, even slab). The half width across
     * the axis follows a noise of period HW_P along it, between HW_LO blocks and the head's own (RAMP_HW0..RAMP_HW1; it
     * narrows, never widens, coming in over the first HW_IN; HW_HI is its bound for the searches), widened where the
     * centre line runs across the axis so its true width holds (at most BF_MAX times); the samples' half widths carry
     * HW_SLACK more where ramps are kept apart and bounded (the strip between two samples). A hole's ramp is a free arch
     * (filled from DK_FILL rows over the ground) for ARCH_P of the holes, else a ridge, filled from DK_FILL_R rows, with
     * talus flanks. 1..LD_N landings (LD_L0..LD_L0 + LD_L1 blocks where the deck falls LD_K of its slope) break its slope,
     * the k-th LD_U0 + (k + LD_J x hash) LD_SP blocks past the head; the rest of the deck is steepened by what they take
     * over a run of LD_RUN (up to RAMP_S1).
     */
    static final double HW_LO = 2.5, HW_HI = RAMP_HW1, HW_P = 30, HW_G = 1.2, HW_IN = 16, BF_MAX = 1.6, HW_SLACK = 0.75;
    /**
     * On the Causeway's coarse prisms the deck is at least HW_CO blocks to either side of the centre line: a column takes
     * its cell's shape, read at the cell's site, and a prism's site lies up to about 4 blocks off its columns (CS_COARSE
     * 5), so on a narrower deck rows of prisms whose sites lay off it cut the deck across its whole width (seed 12345's
     * slow box: an arch's deck in a slab of 3839 blocks floating at (4112..4139, -1856..-1838); seeds 2000023 and 77: gaps
     * of 2 to 4 columns near the heads of the ramps of the holes at (21319, 21168) and (40854, -11094) left them no way
     * down). Its 1.5 blocks over HW_LO lie within the samples' HW_SLACK twice over and the reach's margin.
     */
    static final double HW_CO = 4;
    static final double ARCH_P = 0.22, DK_FILL_R = 64;
    static final int LD_N = 3;
    static final double LD_U0 = 14, LD_SP = 30, LD_J = 0.6, LD_L0 = 4, LD_L1 = 4, LD_K = 0.25, LD_RUN = 110;
    /**
     * The deck's terraces: its height is the ramp's plane plus DK_TA rows x a noise of period DK_TP blocks (read at the
     * block, unwarped), faded in over DK_TIN blocks past the head, so its steps are a row high but their edges wander and
     * their treads vary (along the axis a median of 1.25 to 1.5 blocks, a tenth of them 2 or more, measured on three
     * seeds): stepped basalt, not a staircase. The plane falls at most RAMP_S1 a
     * block and the noise changes by at most DK_TA (NOISE_D / DK_TP + 0.75 x 1.5 / DK_TIN) a block (NOISE_D: the largest
     * derivative of Perlin.noise2, 2.12 measured over its whole period), so neighbouring blocks of a deck's walked half
     * differ by at most a row: walked up as well as down.
     */
    static final double DK_TA = 0.8, DK_TP = 10, DK_TIN = 24, NOISE_D = 2.2;
    /** Everything an opening touches lies within RAMP_REACH of its middle; ramps are resolved against openings within OP_CONF lattice cells; columns look OP_NEAR cells round. */
    static final double RAMP_REACH = 220;
    static final int OP_CONF = 6, OP_NEAR = 4;
    /** The passes of the ramps' resolution (see {@link #choice}). */
    static final int RAMP_PASSES = 3;
    /** The largest opening radius (the crater's reach is checked against it; a blob of a larger radius stays shut). */
    static final double OP_MAX_R = 40;

    static {
        // the searches cover what they must find (static checks of the radii)
        if (RAMP_REACH >= OP_NEAR * OP_G) throw new IllegalStateException("RAMP_REACH");
        if (2 * (RAMP_REACH - TALUS_MAX - 4) + RAMP_GAP + RAMP_DT * 0.5 >= OP_CONF * OP_G) throw new IllegalStateException("OP_CONF");
        if (HC_REACH >= 2.15 * HC_G) throw new IllegalStateException("HC_REACH");
        if (RAMP_PASSES >= 5) throw new IllegalStateException("RAMP_PASSES");   // KEPT
        if (Math.max(RB_R1, RB_L1) >= 1.15 * RB_G) throw new IllegalStateException("RB_G");
        if (SPIRE_R1 >= 1.15 * SPIRE_GRID) throw new IllegalStateException("SPIRE_GRID");
        if (OP_MAX_R + CR_IN + CR_REACH >= RAMP_REACH || CR_DROP != HEAD_DROP + 1) throw new IllegalStateException("CR_REACH");
        if (RAMP_S1 + DK_TA * (NOISE_D / DK_TP + 0.75 * 1.5 / DK_TIN) >= 1) throw new IllegalStateException("DK_TA");
        // the funnel (out to r + RIM_A + LIP + FUN_R, in the opening's reach) stays within RAMP_REACH, and under the
        // Galleries' guaranteed rock (T - CR_DROP and up)
        if (OP_MAX_R + RIM_A + LIP + FUN_R >= RAMP_REACH || FUN_TOP < CR_DROP) throw new IllegalStateException("FUN_R");
    }


    /** A ramp candidate of an opening. Immutable once built. */
    static final class Ramp {
        int dir;
        double ux, uz, s, hw, th0, tEnd, tg, ts, bendA, bk0, bk1, b0, reach, score;
        /** Its form ({@link #formOf}): the gap under its base it is filled from, its half width noise's offsets, its landings (from u past the head, lengths). */
        double dkFill, hk0, hk1, sd;
        int land;
        final double[] lu = new double[LD_N], ll = new double[LD_N];
        /** Centre-line samples every RAMP_DT along the axis from t = r, the ground under them ({@link #rampGround}) and the strip's half width there (with HW_SLACK). */
        double[] px, pz, fl, hwq;
        int n;
        /** Bounding box of the strip and the ledge. */
        double minX, maxX, minZ, maxZ;
    }

    /**
     * An opening: its middle, disc radius, hash and priority, and (shared by every thread; each is set once and is the same
     * whichever thread sets it) its ramp candidates as they are asked for, their order, each pass's choice and its rivals.
     * An opened opening ({@link #openings}) is a copy that holds the ramp it keeps.
     */
    static final class Opening {
        double x, z, r, reach, prio;
        long h;
        /** The ramp candidates by place in {@link #order} (NO_RAMP: none that way), built when first asked; null until then. */
        final AtomicReferenceArray<Ramp> cands = new AtomicReferenceArray<>(RAMP_DIRS);
        /** The candidates' order: direction slots ({@link #rampOrder}); null until asked. */
        volatile int[] order;
        /** Each pass's choice and the last pass's ({@link #KEPT}): a place in the order, -1 none, -2 not yet decided. */
        final int[] ch = {-2, -2, -2, -2, -2, -2};
        /** The openings before it whose ramps could meet its own ({@link #rivals}); null until asked. */
        volatile Rivals rivals;
        /** An opened opening's ramp (null on a raw one) and, for the diagnostics, its axis, slope, end and half width. */
        Ramp ramp;
        boolean walk;
        double ux, uz, s, tEnd, hw;
    }

    /**
     * The openings before an opening whose ramps could meet its own, named by lattice cell and place there (no opening
     * holds another, so a cell the cache drops is not kept alive), with their middles.
     */
    static final class Rivals {
        final long[] cell;
        final int[] at;
        final double[] x, z;

        Rivals(long[] cell, int[] at, double[] x, double[] z) {
            this.cell = cell;
            this.at = at;
            this.x = x;
            this.z = z;
        }
    }

    /** What the openings do at a column (see {@link #rampsAt}). */
    static final class RampHit {
        /** The disc's weight (the cap into the band) and the clearing weight round discs. */
        double jt, clr;
        /** The lowest deck over the column: its first open row (NaN: none), base thickness, place along the free span, edge weight; whether it is filled. */
        double deck, th0, sig, edge;
        boolean fill;
        /**
         * The lowest row rock may hang to over a ramp (NaN: none) and the talus floor beside a ramp's solid part (-1e9:
         * none) with the ground under the ramp's centre line; the highest such talus's deck and its share u of the
         * talus's width out from the deck's edge (the talus meets the column's own floor at u = 1, {@link #shape}).
         */
        double rb, talus, talDk, talU;
        /** The highest breakdown funnel's underside over the column (its first rock row, unrounded; -1e9: none), {@link #FUN_K}. */
        double fun;
        /** The lowest deck: blocks past its head. */
        double deckU;
        /** The cap the strongest disc opens to (its crater's floor). */
        double jtTop;
        /** The lowest deck's ramp: its opening's middle, axis, the deck's start along it and first open row there; the ramp. */
        double rx, rz, rux, ruz, rt0, rtop;
        Ramp ramp;
        /** The lowest deck's ramp's form: the gap under its base it is filled from ({@link #formOf}). */
        double dkFill;
        /**
         * The ramps' taluses over the column, up to TAL_N (the highest by the ground under their centre lines kept): each
         * one's deck, share u of its width and that estimate; {@link #shape} takes the highest at the column's own floor.
         */
        int tn;
        final double[] tDk = new double[TAL_N], tU = new double[TAL_N], tEst = new double[TAL_N];
        /** The crater's floor over the lowest deck (its rock top, unrounded; +inf: none), {@link #CR_T}. */
        double gf;
        /** How near the column lies to a ramp's deck (1 to NEAR_IN past its edge, 0 from NEAR_IN + NEAR_F): no spire or rubble. */
        double near;
        /** The lowest deck's opening. */
        Opening op;
    }

    private static final Opening[] NO_OPENINGS = new Opening[0];
    /** A ramp candidate that cannot be built (its run meets a massif, a tube country or RAMP_REACH, or ends low, wet or in a canyon). */
    private static final Ramp NO_RAMP = new Ramp();
    /** The place in {@link Opening#ch} of the last pass's choice (the ramp kept). */
    static final int KEPT = 5;
    /** Every ramp candidate of an opening, its ledge included, lies within EXT blocks of the opening's middle each way. */
    static final double EXT = RAMP_REACH - TALUS_MAX - 4;

    static final class OpCell { int i, j, blobs; Opening[] ops; }

    private static final class NearCell { int i, j; Opening[] ops; }

    /**
     * The lattice cells' openings are pure in the seed and the cell, and so is every choice made on them, so all threads
     * share them: the raw cells (the openings, with their candidates, choices and rivals as far as they were asked) and
     * each cell's opened openings, in two bounded caches keyed by the cell (the least recently used dropped first; a dropped
     * cell is found again bit for bit). A cold chunk asks for about 900 to 1100 raw cells (measured at the cost test's
     * three places: the openings after each resolved one along its runs, {@link #rampOrder}, as well as before it), so
     * RAW_CACHE holds many such windows; a resolution whose cells are dropped while it runs finds them again but repeats
     * its work, so the caches must hold the working set. The opened openings round a column are cached per thread
     * (nearCells, keyed by the whole cell).
     */
    static final int RAW_CACHE = 16384, OPEN_CACHE = 8192;
    /**
     * The sites' grid is read through tiles of SITE_T x SITE_T samples, shared by every cell's search and every thread:
     * whether each sample's site passes 0.5 (0: not read yet). The cells' searches reach OP_M past their cells, so side by
     * side they read the same samples (a cold chunk read 1.5 samples for every one it needed, a third of its time in the
     * sites); the tiles read each once. Pure in the seed and the sample, so a dropped tile is found again bit for bit, and
     * two threads reading one sample write the same state (a byte: no tearing). SITE_CACHE tiles (1 kB each) hold about
     * 19 km2 of the grid, four times what a cold chunk's searches cover; a search spans at most SITE_W tiles each way.
     */
    static final int SITE_T = 32, SITE_W = 3, SITE_CACHE = 2048;
    final LoadingCache<Long, byte[]> siteTiles = CacheBuilder.newBuilder().maximumSize(SITE_CACHE).build(CacheLoader.from(k -> new byte[SITE_T * SITE_T]));
    final LoadingCache<Long, OpCell> rawCells = CacheBuilder.newBuilder().maximumSize(RAW_CACHE).build(CacheLoader.from(this::loadRaw));
    final LoadingCache<Long, Opening[]> openCells = CacheBuilder.newBuilder().maximumSize(OPEN_CACHE).build(CacheLoader.from(this::loadOpen));
    private final ThreadLocal<NearCell[]> nearCells = ThreadLocal.withInitial(() -> new NearCell[1024]);

    static long cellKey(int i, int j) { return (long) i << 32 | (j & 0xFFFFFFFFL); }

    /** The openings (raw: not resolved) whose middles lie in lattice cell (i, j). */
    OpCell rawCell(int i, int j) { return rawCells.getUnchecked(cellKey(i, j)); }

    private OpCell loadRaw(long key) {
        OpCell c = new OpCell();
        c.i = (int) (key >> 32);
        c.j = (int) key;
        c.ops = findOpenings(c.i, c.j, c);
        return c;
    }

    /**
     * The opened openings whose middles lie in lattice cell (i, j), each a copy holding the ramp it keeps (o.ramp, o.walk).
     * Only an opening that keeps a ramp opens: one left without (no candidate clear of the ramps before it) stays shut, and
     * the Wasteland's guard closes it, so every hole has a way down and back up. (Holes without a ramp were measured as
     * views before they were dropped: a shaft through the vault's relief, its best rim seeing the Galleries through 0-4.5 %
     * of its downward rays, against 14-35 % from the rims of the holes with ramps, and a lethal fall.)
     */
    Opening[] openings(int i, int j) { return openCells.getUnchecked(cellKey(i, j)); }

    private Opening[] loadOpen(long key) {
        List<Opening> l = new ArrayList<>();
        for (Opening o : rawCells.getUnchecked(key).ops) {
            int q = kept(o);
            if (q >= 0) l.add(opened(o, cand(o, q)));
        }
        return l.isEmpty() ? NO_OPENINGS : l.toArray(new Opening[0]);
    }

    /** The opened copy of opening o with ramp rp: it reaches as far as the crater the Wasteland makes round it. */
    static Opening opened(Opening o, Ramp rp) {
        Opening f = new Opening();
        f.x = o.x;
        f.z = o.z;
        f.r = o.r;
        f.h = o.h;
        f.prio = o.prio;
        f.ramp = rp;
        f.walk = true;
        f.ux = rp.ux;
        f.uz = rp.uz;
        f.s = rp.s;
        f.tEnd = rp.tEnd;
        f.hw = rp.hw;
        f.reach = Math.max(o.r + 9, Math.max(rp.reach, o.r + Math.max(CR_IN + CR_REACH, RIM_A + LIP + FUN_R)));
        return f;
    }

    /** Diagnostics: the number of the Wasteland's openings whose middles lie in lattice cell (i, j), opened or not. */
    int openingBlobs(int i, int j) {
        return rawCell(i, j).blobs;
    }

    /**
     * The priority of the openings of lattice cell (i, j): hashed per cell, so a search for the openings before one passes
     * over every cell after its own without finding its openings.
     */
    double cellPrio(int i, int j) {
        return Hashing.unit(Hashing.mix(seed, 52091, i, j));
    }

    /** Whether opening p goes before opening o (a strict order: priority, then hash). */
    static boolean before(Opening p, Opening o) {
        return p.prio > o.prio || (p.prio == o.prio && p.h > o.h);
    }

    /**
     * The openings before o whose ramps could come within RAMP_GAP of o's: every ramp of either lies within EXT of its
     * middle each way, so they lie within 2 EXT + RAMP_GAP + RAMP_DT / 2 each way (the OP_CONF lattice cells round o's).
     */
    Rivals rivals(Opening o) {
        Rivals rv = o.rivals;
        if (rv != null) return rv;
        double lim = 2 * EXT + RAMP_GAP + RAMP_DT * 0.5;
        int ci = (int) Math.floor(o.x / OP_G), cj = (int) Math.floor(o.z / OP_G), n = 0;
        long[] cell = new long[16];
        int[] at = new int[16];
        double[] px = new double[16], pz = new double[16];
        for (int di = -OP_CONF; di <= OP_CONF; di++) for (int dj = -OP_CONF; dj <= OP_CONF; dj++) {
            // a cell after o's holds no opening before o
            if ((di != 0 || dj != 0) && cellPrio(ci + di, cj + dj) < o.prio) continue;
            OpCell c = rawCell(ci + di, cj + dj);
            for (int m = 0; m < c.ops.length; m++) {
                Opening p = c.ops[m];
                if (!before(p, o) || Math.abs(p.x - o.x) > lim || Math.abs(p.z - o.z) > lim) continue;
                if (n == cell.length) {
                    cell = Arrays.copyOf(cell, 2 * n);
                    at = Arrays.copyOf(at, 2 * n);
                    px = Arrays.copyOf(px, 2 * n);
                    pz = Arrays.copyOf(pz, 2 * n);
                }
                cell[n] = cellKey(c.i, c.j);
                at[n] = m;
                px[n] = p.x;
                pz[n] = p.z;
                n++;
            }
        }
        rv = new Rivals(Arrays.copyOf(cell, n), Arrays.copyOf(at, n), Arrays.copyOf(px, n), Arrays.copyOf(pz, n));
        o.rivals = rv;
        return rv;
    }

    /** Opening o's ramp candidate at place q of its order (NO_RAMP: none that way), built when first asked. */
    Ramp cand(Opening o, int q) {
        Ramp rp = o.cands.get(q);
        if (rp != null) return rp;
        int k = rampOrder(o)[q];
        rp = k < 0 ? null : rampCandidate(o, k);
        if (rp == null) rp = NO_RAMP;
        return o.cands.compareAndSet(q, null, rp) ? rp : o.cands.get(q);
    }

    /**
     * Pass k of the ramps' resolution: o's choice among its candidates (a place in their order, -1: none). Pass 0 takes its
     * first candidate; pass k its first candidate clear of the pass k-1 choices of all openings before it. The first
     * opening of any group keeps its first candidate from pass 0 on, the second settles by pass 1, and so on: the choices
     * settle down the priority order, one opening a pass. Candidates are built only as far as a choice reads them.
     */
    int choice(Opening o, int k) {
        int r = o.ch[k];
        if (r != -2) return r;
        r = -1;
        Rivals rv = k > 0 ? rivals(o) : null;
        for (int q = 0; q < RAMP_DIRS && r < 0; q++) {
            Ramp a = cand(o, q);
            if (a != NO_RAMP && (k == 0 || clearOf(o, a, rv, k - 1))) r = q;
        }
        o.ch[k] = r;
        return r;
    }

    /**
     * Whether ramp a of opening o is clear of pass k's choices of the openings before o ({@code rv}). A rival whose square
     * of EXT round its middle stays RAMP_GAP + RAMP_DT / 2 off a's box cannot meet a whatever it chooses, so its choice
     * is not asked.
     */
    boolean clearOf(Opening o, Ramp a, Rivals rv, int k) {
        double g = EXT + RAMP_GAP + RAMP_DT * 0.5;
        for (int m = 0; m < rv.at.length; m++) {
            double px = rv.x[m], pz = rv.z[m];
            if (px > a.maxX + g || px < a.minX - g || pz > a.maxZ + g || pz < a.minZ - g) continue;
            Opening p = rawCells.getUnchecked(rv.cell[m]).ops[rv.at[m]];
            int qp = choice(p, k);
            if (qp >= 0 && conflict(o, a, p, cand(p, qp))) return false;
        }
        return true;
    }

    /** The last pass: the place of the ramp o keeps, its pass-RAMP_PASSES choice if that is clear of the same pass's choices of all openings before it, else -1. */
    int kept(Opening o) {
        int r = o.ch[KEPT];
        if (r != -2) return r;
        int q = choice(o, RAMP_PASSES);
        r = q >= 0 && clearOf(o, cand(o, q), rivals(o), RAMP_PASSES) ? q : -1;
        o.ch[KEPT] = r;
        return r;
    }

    /**
     * Whether ramp a of opening A and ramp b of opening B come within RAMP_GAP of each other: their ledges (each the disc
     * of r + 3.5 on its ramp's side of DK_T r), and their strips (centre-line samples every RAMP_DT, half widths hw; half a
     * step of slack for the sampling).
     */
    static boolean conflict(Opening A, Ramp a, Opening B, Ramp b) {
        double g = RAMP_GAP + RAMP_DT * 0.5;
        if (a.maxX + g < b.minX || b.maxX + g < a.minX || a.maxZ + g < b.minZ || b.maxZ + g < a.minZ) return false;
        if (ledgesMeet(A, a, B, b, RAMP_GAP + 1.5)) return true;
        for (int i = 0; i < a.n; i++) {
            double x = a.px[i], z = a.pz[i], m = a.hwq[i] + g;
            if (ledgeDist(B, b, x, z) < m) return true;
            if (x + m < b.minX || x - m > b.maxX || z + m < b.minZ || z - m > b.maxZ) continue;
            for (int j = 0; j < b.n; j++) {
                double dx = x - b.px[j], dz = z - b.pz[j], rr = m + b.hwq[j];
                if (dx * dx + dz * dz < rr * rr) return true;
            }
        }
        for (int j = 0; j < b.n; j++) if (ledgeDist(A, a, b.px[j], b.pz[j]) < b.hwq[j] + g) return true;
        return false;
    }

    /**
     * Blocks from (x, z) to the ledge of opening A built with ramp a (the disc of L = r + 3.5 on the ramp's side of DK_T r):
     * behind the chord, the distance to the chord (exact: the ledge is less than half the disc); in front of it, the
     * distance to the whole disc (never more than the true one).
     */
    static double ledgeDist(Opening A, Ramp a, double x, double z) {
        double dx = x - A.x, dz = z - A.z, L = A.r + 3.5, t0 = DK_T * A.r;
        double t = dx * a.ux + dz * a.uz;
        if (t >= t0) return Math.max(0, len(dx, dz) - L);
        double l = Math.abs(dx * a.uz - dz * a.ux), hc = Math.sqrt(L * L - t0 * t0);
        return len(t0 - t, Math.max(0, l - hc));
    }

    /** Whether the ledges of (A, a) and (B, b) come within gap: B's ledge's outline (chord and arc, under 3 blocks apart) against A's ledge, and either inside the other. */
    static boolean ledgesMeet(Opening A, Ramp a, Opening B, Ramp b, double gap) {
        if (ledgeDist(A, a, B.x + b.ux * B.r * 0.5, B.z + b.uz * B.r * 0.5) < gap) return true;
        if (ledgeDist(B, b, A.x + a.ux * A.r * 0.5, A.z + a.uz * A.r * 0.5) < gap) return true;
        double L = B.r + 3.5, t0 = DK_T * B.r, hc = Math.sqrt(L * L - t0 * t0);
        int nc = Math.max(1, (int) Math.ceil(2 * hc / 3));
        for (int k = 0; k <= nc; k++) {
            double l = -hc + 2 * hc * k / nc;
            if (ledgeDist(A, a, B.x + b.ux * t0 + b.uz * l, B.z + b.uz * t0 - b.ux * l) < gap) return true;
        }
        for (int k = -DIRS / 4 - 1; k <= DIRS / 4 + 1; k++) {
            int d = (b.dir + k) & (DIRS - 1);
            double x = B.x + L * DIR_X[d], z = B.z + L * DIR_Z[d];
            if ((x - B.x) * b.ux + (z - B.z) * b.uz < t0) continue;
            if (ledgeDist(A, a, x, z) < gap) return true;
        }
        return false;
    }

    /**
     * The final openings whose reach meets lattice cell (ci, cj), from the OP_NEAR cells round it (cached per thread): of
     * those only the cells within RAMP_REACH of it, as no opening reaches further (the static checks), so the openings
     * in the window's corners are never resolved for it.
     */
    Opening[] nearOps(int ci, int cj) {
        NearCell[] nc = nearCells.get();
        int slot = (ci & 31) << 5 | (cj & 31);
        NearCell c = nc[slot];
        if (c != null && c.i == ci && c.j == cj) return c.ops;
        double x0 = ci * OP_G, z0 = cj * OP_G, x1 = x0 + OP_G, z1 = z0 + OP_G;
        List<Opening> l = new ArrayList<>();
        for (int di = -OP_NEAR; di <= OP_NEAR; di++) for (int dj = -OP_NEAR; dj <= OP_NEAR; dj++) {
            double gx = Math.max(0, Math.abs(di) - 1) * OP_G, gz = Math.max(0, Math.abs(dj) - 1) * OP_G;
            if (gx * gx + gz * gz > RAMP_REACH * RAMP_REACH) continue;
            for (Opening o : openings(ci + di, cj + dj)) {
                double ex = Math.max(0, Math.max(x0 - o.x, o.x - x1)), ez = Math.max(0, Math.max(z0 - o.z, o.z - z1));
                if (ex * ex + ez * ez <= o.reach * o.reach) l.add(o);
            }
        }
        c = new NearCell();
        c.i = ci;
        c.j = cj;
        c.ops = l.isEmpty() ? NO_OPENINGS : l.toArray(new Opening[0]);
        nc[slot] = c;
        return c.ops;
    }

    /**
     * The deck's terraces at (x, z), {@code u} blocks past the ramp's head along its axis (rows, added to the ramp's
     * plane): DK_TA x a noise of period DK_TP, faded in over DK_TIN (0 at the head itself, which meets the crater's floor).
     */
    double deckTerrace(double x, double z, double u) {
        if (u <= 0) return 0;
        return DK_TA * deckN.noise2(x / DK_TP, z / DK_TP) * smooth(u / DK_TIN);
    }

    /** The sideways offset of ramp rp's centre line at t along its axis (0 up to its head t0). */
    double bend(Ramp rp, double t0, double t) {
        if (t <= t0) return 0;
        return rp.bendA * (bendN.noise2(t / BEND_P + rp.bk0, rp.bk1) - rp.b0) * smooth((t - t0) / BEND_IN);
    }

    /**
     * How far ramp rp's deck plane has fallen u blocks past its head (rows): sd a block (s steepened by what its landings
     * take, up to RAMP_S1, so they lengthen the run little), LD_K of it on its landings.
     */
    static double deckDrop(Ramp rp, double u) {
        if (u <= 0) return 0;
        double run = u;
        for (int k = 0; k < rp.land; k++) run -= (1 - LD_K) * Math.max(0, Math.min(u - rp.lu[k], rp.ll[k]));
        return rp.sd * run;
    }

    /** Ramp rp's deck plane at t along its axis (its first open row before the terraces, unrounded), t0 its head. */
    double deckPlane(Ramp rp, double t0, double t) { return headTop - deckDrop(rp, t - t0); }

    /** Ramp rp's half width across its axis at t along it (blocks; see {@link #HW_LO}), t0 its head. */
    double halfWidth(Ramp rp, double t0, double t) { return halfWidth(rp, t0, t, bend(rp, t0, t)); }

    /** {@link #halfWidth(Ramp, double, double)} with the centre line's offset bt there. */
    double halfWidth(Ramp rp, double t0, double t, double bt) {
        double u = Math.max(0, t - t0);
        double nw = HW_LO + (rp.hw - HW_LO) * smooth(0.5 + HW_G * widthN.noise2(u / HW_P + rp.hk0, rp.hk1));
        double hw = lerp(rp.hw, nw, smooth(u / HW_IN));
        double b1 = bend(rp, t0, t + 1) - bt;
        return hw * Math.min(BF_MAX, Math.sqrt(1 + b1 * b1));
    }

    /** Opening o's ramps' form, the same in every direction (see {@link #HW_LO}): slope, the head's half width, base thickness, fill, width noise and landings. */
    static void formOf(Opening o, Ramp rp) {
        rp.s = rampSlope(o);
        rp.hw = RAMP_HW0 + (RAMP_HW1 - RAMP_HW0) * Hashing.unit(Hashing.mix(o.h, 4, 0, 0));
        rp.th0 = RAMP_TH0 + RAMP_TH1 * Hashing.unit(Hashing.mix(o.h, 9, 0, 0));
        rp.dkFill = Hashing.unit(Hashing.mix(o.h, 21, 0, 0)) < ARCH_P ? DK_FILL : DK_FILL_R;
        rp.hk0 = 64 * Hashing.unit(Hashing.mix(o.h, 22, 0, 0));
        rp.hk1 = 64 * Hashing.unit(Hashing.mix(o.h, 23, 0, 0));
        rp.land = 1 + (int) (LD_N * Hashing.unit(Hashing.mix(o.h, 24, 0, 0)));
        double flat = 0;
        for (int k = 0; k < rp.land; k++) {
            rp.lu[k] = LD_U0 + (k + LD_J * Hashing.unit(Hashing.mix(o.h, 25, k, 0))) * LD_SP;
            rp.ll[k] = LD_L0 + LD_L1 * Hashing.unit(Hashing.mix(o.h, 26, k, 0));
            flat += (1 - LD_K) * rp.ll[k];
        }
        rp.sd = Math.min(RAMP_S1, rp.s * (1 + flat / LD_RUN));
    }

    /**
     * The openings' effects at column (x, z): the discs' and clearing weights (and the cap the strongest disc opens to),
     * the lowest ramp deck over the column, the head room rows over ramps, and the talus floor beside the ramps' solid parts.
     * hwMin: the least half width a deck takes there ({@link #HW_CO} at a coarse prism's site, else 0).
     */
    void rampsAt(double x, double z, RampHit rh, double hwMin) {
        rh.jt = 0;
        rh.jtTop = 0;
        rh.clr = 0;
        rh.deck = Double.NaN;
        rh.th0 = 0;
        rh.sig = 0;
        rh.edge = 0;
        rh.fill = false;
        rh.rb = Double.NaN;
        rh.talus = -1e9;
        rh.fun = -1e9;
        rh.deckU = 0;
        rh.ramp = null;
        rh.dkFill = DK_FILL;
        rh.tn = 0;
        rh.gf = Double.POSITIVE_INFINITY;
        rh.near = 0;
        rh.op = null;
        if (join == null) return;
        int ci = (int) Math.floor(x / OP_G), cj = (int) Math.floor(z / OP_G);
        double top = headTop;
        for (Opening o : nearOps(ci, cj)) {
            double dx = x - o.x, dz = z - o.z;
            if (Math.abs(dx) > o.reach || Math.abs(dz) > o.reach) continue;
            double d2 = dx * dx + dz * dz;
            if (d2 > o.reach * o.reach) continue;
            double d = Math.sqrt(d2);
            Ramp rp = o.ramp;
            if (rp == null) continue;                // (opened openings all keep a ramp)
            double t0 = o.r + LIP, t = dx * rp.ux + dz * rp.uz, rr = rimR(o, dx, dz, d, t);
            double jt = smooth((rr + LIP - d) / 1.5);
            if (jt > rh.jt) {
                rh.jt = jt;
                // an opening opens up to its crater's floor (the Wasteland's tunnels come down to it)
                rh.jtTop = discTop;
            }
            rh.clr = Math.max(rh.clr, smooth((o.r + 6 - d) / 3));
            // the breakdown funnel round the disc: the vault's underside rises toward the lip
            double fu = d - rr - LIP;
            if (fu < FUN_R) rh.fun = Math.max(rh.fun, T - FUN_TOP - FUN_K * Math.max(0, fu) - FUN_DROP * smooth((fu - FUN_R + FUN_FO) / FUN_FO));
            if (t < t0 - 1 || t > rp.tEnd) continue;
            double l = dx * rp.uz - dz * rp.ux, bt = bend(rp, t0, t);
            double dl = Math.abs(l - bt);
            if (dl >= HW_HI * BF_MAX + TALUS_MAX) continue;
            double hw = Math.max(hwMin, halfWidth(rp, t0, t, bt));
            if (dl >= hw + TALUS_MAX) continue;
            if (dl < hw + NEAR_IN + NEAR_F) rh.near = Math.max(rh.near, 1 - smooth((dl - hw - NEAR_IN) / NEAR_F));
            double u = Math.max(0, t - t0), plane = top - deckDrop(rp, u);
            double dk = plane + deckTerrace(x, z, u);
            if (dl < hw + 1) {
                // over the ramp and a block beside it rock hangs no lower than its head room; beside it no higher than
                // the crater's floor's rock (T - CR_DROP), so round the head the crater's floor stays whole up to the
                // deck and walks onto it (cells beside the deck opened up to the head room cut the head off the
                // crater's floor at 3 of 81 holes measured, leaving those ramps no way back up)
                double rb = dk + RAMP_HEAD + 1;
                if (dl >= hw) rb = Math.min(rb, T - CR_DROP);
                if (!(rh.rb >= rb)) rh.rb = rb;
            }
            if (dl < hw) {
                double sig = clamp01((t - t0) / Math.max(1, rp.tg - t0));
                // the ragged edge fades in over the head's first EDGE_IN blocks: the head's full width stays at the
                // crater's floor's level beside it, a row up or down whatever the deck's row (W-ENG-2's one-row check)
                double edge = t > t0 ? clamp01((dl - 0.5 * hw) / (0.5 * hw)) * clamp01((t - t0) / EDGE_IN) : 0;
                if (!(rh.deck <= dk)) {
                    rh.deck = dk;
                    rh.th0 = rp.th0;
                    rh.sig = sig;
                    // filled from where the talus starts (ts): a cell over a hollow of the ground short of tg kept an arch
                    // with a few rows open under it between the filled cells and the talus, a closed pit in seed -42's box
                    rh.fill = t >= rp.ts;
                    rh.edge = edge;
                    rh.rx = o.x; rh.rz = o.z; rh.rux = rp.ux; rh.ruz = rp.uz; rh.rt0 = t0; rh.rtop = top; rh.ramp = rp;
                    rh.dkFill = rp.dkFill;
                    rh.deckU = u;
                    rh.op = o;
                }
            } else if (t >= rp.ts) {
                // the talus beside the solid part: down from the deck at TALUS_S rows a block, or steeper where the deck
                // stands higher, so that it meets the ground under the centre line TALUS_MAX blocks out
                int q = Math.min(rp.n - 1, Math.max(0, (int) Math.round((t - t0) / RAMP_DT)));
                double sl = Math.max(TALUS_S, (dk - rp.fl[q]) / TALUS_MAX);
                double tal = dk - (dl - hw) * sl;
                if (tal > rh.talus) { rh.talus = tal; rh.talDk = dk; rh.talU = (dl - hw) / TALUS_MAX; }
                // every ramp's talus is kept (the highest TAL_N): the highest at the column's own floor is not always the
                // highest at the centre line's (two ramps' taluses meeting in a valley left its floor at the lower one's
                // foot, a pit 15 rows deep in seed -42's box)
                int k = rh.tn;
                if (k == TAL_N) {
                    k = 0;
                    for (int i = 1; i < TAL_N; i++) if (rh.tEst[i] < rh.tEst[k]) k = i;
                    if (rh.tEst[k] >= tal) k = -1;
                } else rh.tn++;
                if (k >= 0) { rh.tDk[k] = dk; rh.tU[k] = (dl - hw) / TALUS_MAX; rh.tEst[k] = tal; }
            }
        }
        if (rh.op != null) rh.gf = craterFloor(T, ringOf(rh.op, x, z, len(x - rh.op.x, z - rh.op.z)));
    }

    /**
     * Opened opening o's hole's radius on the bearing (dx, dz) from its middle, d blocks out and t along its ramp's axis
     * (see {@link #RIM_A}): r on the ramp's side, r + RIM_A x a noise of the bearing off it.
     */
    double rimR(Opening o, double dx, double dz, double d, double t) {
        if (d < 1) return o.r;
        double off = smooth((CR_LA - t / d) / CR_LF);
        if (off <= 0) return o.r;
        double ox = 64 * Hashing.unit(Hashing.mix(o.h, 33, 0, 0)), oz = 64 * Hashing.unit(Hashing.mix(o.h, 34, 0, 0));
        return o.r + RIM_A * off * rimN.noise2(dx / d * RIM_K + ox, dz / d * RIM_K + oz);
    }

    /** How much of the breach's growth holds u blocks past a ramp's head: all of it to BR_U, none from BR_U + BR_UF. */
    static double breach(double u) { return 1 - smooth((u - BR_U) / BR_UF); }

    /**
     * Blocks from column (x, z) to the edge of the nearest disc the Galleries open with a ramp (its distance from the
     * opening's middle less its radius r; negative inside the disc), or +inf where no such opening reaches it (out to
     * r + CR_IN + CR_REACH). The Wasteland's lowest sheet goes down round it to {@link #craterFloor(int, double)}: a
     * player walks from its tunnels down to the hole's lip and onto the ramp, and back up. Pure (the openings are).
     */
    public double craterRing(int x, int z) {
        if (join == null) return Double.POSITIVE_INFINITY;
        double px = x + 0.5, pz = z + 0.5, best = Double.POSITIVE_INFINITY;
        for (Opening o : nearOps((int) Math.floor(px / OP_G), (int) Math.floor(pz / OP_G))) {
            if (o.ramp == null) continue;
            double dx = px - o.x, dz = pz - o.z;
            double lim = o.r + CR_IN + CR_REACH;
            if (Math.abs(dx) > lim || Math.abs(dz) > lim) continue;
            double d = len(dx, dz);
            if (d - o.r <= CR_IN + CR_REACH) best = Math.min(best, ringOf(o, px, pz, d));
        }
        return best;
    }

    /**
     * The crater's ring of opened opening o at (px, pz), d blocks from its middle: d less the hole's radius on that bearing
     * ({@link #rimR}) inside the hole; outside it, that distance read CR_T nearer over the ramp's gully and up to CR_B
     * further off the ramp's side of the rim (see {@link #CR_T}), never under 0.
     */
    double ringOf(Opening o, double px, double pz, double d) {
        Ramp rp = o.ramp;
        double dx = px - o.x, dz = pz - o.z, t0 = o.r + LIP, t = dx * rp.ux + dz * rp.uz, sh = 0;
        double q = d - rimR(o, dx, dz, d, t);
        if (q <= 0) return q;
        double gw = 0;
        if (t > t0 - CR_TW && t < t0 + CR_TU + CR_T * CR_TE) {
            // the gully: over the deck of the ramp's first stretch, its flanks climbing a ring every CR_TK blocks beside it
            // and its end a ring every CR_TE blocks along the axis
            double tt = Math.max(t0, t), bt = bend(rp, t0, tt);
            double e = Math.max(0, Math.abs(dx * rp.uz - dz * rp.ux - bt) - halfWidth(rp, t0, tt, bt));
            double s = e / CR_TK + Math.max(0, t - t0 - CR_TU) / CR_TE;
            if (s < CR_T) {
                sh = s - CR_T;
                gw = 1 - s / CR_T;
            }
        }
        double off = smooth((CR_LA - t / d) / CR_LF) * (1 - gw);
        if (off > 0) {
            // the rim off the ramp's side: a noise of the bearing (the unit vector, no angle taken), per hole, rising from
            // the hole's edge
            double ox = 64 * Hashing.unit(Hashing.mix(o.h, 31, 0, 0)), oz = 64 * Hashing.unit(Hashing.mix(o.h, 32, 0, 0));
            sh += CR_B * off * smooth(q / CR_BQ) * smooth(CR_BM + CR_BG * rimN.noise2(dx / d * CR_BK + ox, dz / d * CR_BK + oz));
        }
        return Math.max(0, q + sh);
    }

    /**
     * The crater's floor (the rock top, a row, unrounded) at {@code ring} blocks from the disc's edge ({@link #craterRing})
     * under a layer whose top row is {@code top}: CR_DROP rows under the top (one row under a ramp's head, on the
     * Galleries' guaranteed rock) out to CR_IN, rising CR_S rows a block beyond; +inf past CR_IN + CR_REACH.
     */
    static double craterFloor(int top, double ring) {
        if (!(ring <= CR_IN + CR_REACH)) return Double.POSITIVE_INFINITY;
        return top - CR_DROP + CR_S * Math.max(0, ring - CR_IN);
    }

    /** {@link #craterFloor(int, double)} at column (x, z). */
    public double craterFloor(int x, int z) { return craterFloor(T, craterRing(x, z)); }

    /**
     * The search of lattice cell (i, j) (see the openings' notes above): the openable openings. The sites' grid is looked
     * at first on every other sample (a checkerboard: of two samples side by side one is looked at), within OP_SEED
     * blocks of the cell; each blob a looked-at sample falls in is then flooded at full resolution out to the margin,
     * every sample it touches read once. A blob of one sample, missed on the off squares, stays shut (it is the same from
     * every cell: the checkerboard is the world's). The samples are read through the shared tiles ({@link #SITE_T}).
     */
    Opening[] findOpenings(int i, int j, OpCell cell) {
        if (join == null) return NO_OPENINGS;
        double x0 = i * OP_G, z0 = j * OP_G;
        // the Wasteland opens only where beta passes 0.45: a cheap look first
        boolean any = false;
        for (double bx = x0 - OP_M; bx <= x0 + OP_G + OP_M && !any; bx += 8)
            for (double bz = z0 - OP_M; bz <= z0 + OP_G + OP_M; bz += 8)
                if (join.beta(bx, bz) > 0.42) { any = true; break; }
        if (!any) return NO_OPENINGS;
        int gx0 = (int) Math.floor((x0 - OP_M) / OP_S), gz0 = (int) Math.floor((z0 - OP_M) / OP_S);
        int nx = (int) Math.ceil((x0 + OP_G + OP_M) / OP_S) - gx0 + 1, nz = (int) Math.ceil((z0 + OP_G + OP_M) / OP_S) - gz0 + 1;
        byte[] v = new byte[nx * nz];
        int tx0 = Math.floorDiv(gx0, SITE_T), tz0 = Math.floorDiv(gz0, SITE_T);
        byte[][] tiles = new byte[SITE_W * SITE_W][];
        boolean[] seen = new boolean[nx * nz];
        int[] stack = new int[nx * nz], mem = new int[nx * nz];
        List<Opening> out = new ArrayList<>();
        int a1 = (int) Math.floor((x0 - OP_SEED) / OP_S) - gx0, a2 = (int) Math.ceil((x0 + OP_G + OP_SEED) / OP_S) - gx0;
        int b1 = (int) Math.floor((z0 - OP_SEED) / OP_S) - gz0, b2 = (int) Math.ceil((z0 + OP_G + OP_SEED) / OP_S) - gz0;
        for (int a0 = Math.max(0, a1); a0 <= Math.min(nx - 1, a2); a0++)
            for (int b0 = Math.max(0, b1) + Math.floorMod(gx0 + a0 + gz0 + Math.max(0, b1), 2); b0 <= Math.min(nz - 1, b2); b0 += 2) {
            int s0 = a0 * nz + b0;
            if (seen[s0] || !site(v, tiles, tx0, tz0, s0, nz, gx0, gz0)) continue;
            int sp = 0, mn = 0;
            stack[sp++] = s0;
            seen[s0] = true;
            double sx = 0, sz = 0;
            while (sp > 0) {
                int q = stack[--sp];
                mem[mn++] = q;
                int a = q / nz, b = q % nz;
                sx += (gx0 + a) * OP_S;
                sz += (gz0 + b) * OP_S;
                if (a > 0 && !seen[q - nz] && site(v, tiles, tx0, tz0, q - nz, nz, gx0, gz0)) { seen[q - nz] = true; stack[sp++] = q - nz; }
                if (a < nx - 1 && !seen[q + nz] && site(v, tiles, tx0, tz0, q + nz, nz, gx0, gz0)) { seen[q + nz] = true; stack[sp++] = q + nz; }
                if (b > 0 && !seen[q - 1] && site(v, tiles, tx0, tz0, q - 1, nz, gx0, gz0)) { seen[q - 1] = true; stack[sp++] = q - 1; }
                if (b < nz - 1 && !seen[q + 1] && site(v, tiles, tx0, tz0, q + 1, nz, gx0, gz0)) { seen[q + 1] = true; stack[sp++] = q + 1; }
            }
            double cx = sx / mn, cz = sz / mn;
            if (cx < x0 || cx >= x0 + OP_G || cz < z0 || cz >= z0 + OP_G) continue;
            cell.blobs++;
            double r = 0;
            for (int k = 0; k < mn; k++) {
                int q = mem[k];
                r = Math.max(r, len((gx0 + q / nz) * OP_S - cx, (gz0 + q % nz) * OP_S - cz));
            }
            Opening o = new Opening();
            o.x = cx;
            o.z = cz;
            o.r = r + OP_S * 0.75;
            // the crater's reach is checked against OP_MAX_R (the holes measure under 16 blocks; a larger blob stays shut,
            // and the Wasteland's guard closes it)
            if (o.r > OP_MAX_R) continue;
            o.h = Hashing.mix(seed, 52001, (long) Math.floor(cx * 4), (long) Math.floor(cz * 4));
            if (!openable(o)) continue;
            o.prio = cellPrio(i, j);
            o.reach = o.r + 9;
            out.add(o);
        }
        return out.isEmpty() ? NO_OPENINGS : out.toArray(new Opening[0]);
    }

    /**
     * Whether the sites' grid's sample q passes 0.5 (its float form, as the search always read it): the search's own copy
     * v first, then the shared tile ({@link #SITE_T}), and only where neither holds it the Wasteland's opening site (0:
     * not read, 1: under, 2: over).
     */
    private boolean site(byte[] v, byte[][] tiles, int tx0, int tz0, int q, int nz, int gx0, int gz0) {
        byte f = v[q];
        if (f == 0) {
            int gx = gx0 + q / nz, gz = gz0 + q % nz, tx = Math.floorDiv(gx, SITE_T) - tx0, tz = Math.floorDiv(gz, SITE_T) - tz0;
            byte[] tile = tiles[tx * SITE_W + tz];
            if (tile == null) tiles[tx * SITE_W + tz] = tile = siteTiles.getUnchecked(cellKey(tx + tx0, tz + tz0));
            int k = Math.floorMod(gx, SITE_T) * SITE_T + Math.floorMod(gz, SITE_T);
            f = tile[k];
            if (f == 0) tile[k] = f = (byte) ((float) join.openingSite(gx * OP_S, gz * OP_S) >= 0.5 ? 2 : 1);
            v[q] = f;
        }
        return f == 2;
    }

    /** An opening is opened only over the cavern: not on a massif (or within 0.06 of its threshold), not in a tube country. */
    boolean openable(Opening o) {
        double[] w2 = new double[6];
        warp(o.x, o.z, w2);
        Climate cl = new Climate();
        climate(o.x, o.z, cl);
        if (massN.fbm2(w2[0] / M_P, w2[1] / M_P, 2) - M_SUB * (cl.wCal + cl.wCaus) > M_T - 0.06) return false;
        return !(tubesAt(o.x, o.z) && tubes.apronAt(o.x, o.z) > 0.02);
    }

    /** The direction of opening o's ramp candidate slot k (RAMP_DIRS slots round from a hashed start). */
    int slotDir(Opening o, int k) {
        int k0 = dirOf(Hashing.unit(Hashing.mix(o.h, 3, 0, 0)));
        return (k0 + k * (DIRS / RAMP_DIRS)) & (DIRS - 1);
    }

    /** Opening o's ramps' slope (rows a block). */
    static double rampSlope(Opening o) {
        return RAMP_S0 + (RAMP_S1 - RAMP_S0) * Hashing.unit(Hashing.mix(o.h, 2, 0, 0));
    }

    /** The ground's look for the candidates' order ({@link #rampOrder}) reads the ground every RO_STEP samples of a centre line. */
    static final int RO_STEP = 6;
    /**
     * The candidates' order offers a run that passes within RAMP_GAP of the disc (r + 3.5) of an opening after o, a hole
     * its ramp would shut, RO_PEN blocks later for each.
     */
    static final double RO_PEN = 80;
    /**
     * A run's sample passes near an opening's disc (within the run's half width, at most HW_HI BF_MAX, of r + 3.5 + the
     * gap) only within RV_R of its middle (a block's margin over the sum).
     */
    static final double RV_R = OP_MAX_R + 3.5 + RAMP_GAP + RAMP_DT * 0.5 + HW_SLACK + HW_HI * BF_MAX + 1;

    /**
     * The order in which opening o's ramp candidates are built and offered (direction slots): by the run each would take
     * (a dry end, W + 40 or more, before a wet one by 60 blocks), read along its bent centre line as {@link
     * #rampCandidate} reads it: massifs, tube countries and RAMP_REACH at every sample, the ground every RO_STEP samples,
     * and where the deck has come down to the ground (or the look stops) the samples since the last read one by one, so
     * the run's end is the candidate's own; RO_PEN more for each lower-priority opening's disc the run passes near. A slot
     * whose run meets a massif, a tube country or RAMP_REACH first, never comes down, or ends under W + 6, in a canyon,
     * the Caldera or a river is last and never built (a slot -1 - k); ties in the slots' hashed order. (Building every
     * candidate to find the shortest cost an opening RAMP_DIRS runs, most never asked for; reading the ground only every
     * RO_STEP samples, the look's end overshot the run's and dropped openings that had a way down.)
     */
    int[] rampOrder(Opening o) {
        int[] ord = o.order;
        if (ord != null) return ord;
        double t0 = o.r + LIP;
        double[] w2 = new double[6], gf = new double[1];
        Climate cl = new Climate();
        Ramp rp = new Ramp();
        formOf(o, rp);
        double[] est = new double[RAMP_DIRS];
        int nMax = (int) Math.floor(RAMP_MAX / RAMP_DT + 1e-9);
        // the openings after o whose discs its runs could pass over: their middles and reach (r + 3.5 + the gap), those
        // within far of o, read from the lattice cells after o's (their middles lie in their own) only as a run's sample
        // comes within RV_R of a cell: an opening whose middle lies further than RV_R from a sample is not passed near
        // there, so the cells no run comes near are never searched and the order is the same as from all the cells within
        // far (searched for this alone, those cost a cold chunk a third of its time)
        double far = RAMP_REACH - TALUS_MAX - 4 + OP_MAX_R + 3.5 + HW_HI * BF_MAX + RAMP_GAP + RAMP_DT * 0.5;
        int ia = (int) Math.floor((o.x - far) / OP_G), ib = (int) Math.floor((o.x + far) / OP_G);
        int ja = (int) Math.floor((o.z - far) / OP_G), jb = (int) Math.floor((o.z + far) / OP_G), nj = jb - ja + 1;
        boolean[] seen = new boolean[(ib - ia + 1) * nj];
        double[] nx = new double[16], nz = new double[16], nr = new double[16];
        int[] first = new int[16];
        int nn = 0;
        for (int k = 0; k < RAMP_DIRS; k++) {
            Arrays.fill(first, 0, nn, Integer.MAX_VALUE);
            int dir = slotDir(o, k);
            rp.ux = DIR_X[dir];
            rp.uz = DIR_Z[dir];
            bendOf(o, k, t0, rp);
            double e = 1e6;
            int read = -1;                   // the last sample whose ground was read
            for (int step = 0; ; step++) {
                double t = t0 + step * RAMP_DT;
                boolean stop = step > nMax;
                if (!stop) {
                    double b = bend(rp, t0, t);
                    double px = o.x + rp.ux * t + rp.uz * b, pz = o.z + rp.uz * t - rp.ux * b, d = len(px - o.x, pz - o.z);
                    if (d + HW_LO + HW_SLACK + TALUS_MAX + 4 > RAMP_REACH) stop = true;
                    else if (d + HW_HI * BF_MAX + HW_SLACK + TALUS_MAX + 4 > RAMP_REACH)
                        stop = d + halfWidth(rp, t0, t, b) + HW_SLACK + TALUS_MAX + 4 > RAMP_REACH;
                    if (!stop) {
                        int i1 = Math.max(ia, (int) Math.floor((px - RV_R) / OP_G)), i2 = Math.min(ib, (int) Math.floor((px + RV_R) / OP_G));
                        int j1 = Math.max(ja, (int) Math.floor((pz - RV_R) / OP_G)), j2 = Math.min(jb, (int) Math.floor((pz + RV_R) / OP_G));
                        for (int i = i1; i <= i2; i++)
                            for (int j = j1; j <= j2; j++) {
                                if (seen[(i - ia) * nj + j - ja]) continue;
                                seen[(i - ia) * nj + j - ja] = true;
                                // a cell before o's holds no opening after o
                                if (cellPrio(i, j) > o.prio) continue;
                                for (Opening p : rawCell(i, j).ops) {
                                    if (!before(o, p) || len(p.x - o.x, p.z - o.z) > far) continue;
                                    if (nn == nx.length) {
                                        nx = Arrays.copyOf(nx, 2 * nn);
                                        nz = Arrays.copyOf(nz, 2 * nn);
                                        nr = Arrays.copyOf(nr, 2 * nn);
                                        first = Arrays.copyOf(first, 2 * nn);
                                    }
                                    nx[nn] = p.x;
                                    nz[nn] = p.z;
                                    nr[nn] = p.r + 3.5 + RAMP_GAP + RAMP_DT * 0.5 + HW_SLACK;
                                    first[nn] = Integer.MAX_VALUE;
                                    nn++;
                                }
                            }
                    }
                    for (int m = 0; m < nn && !stop; m++) {
                        if (first[m] < step) continue;
                        // far off (a block's margin): not near
                        double ex = px - nx[m], ez = pz - nz[m], lim = nr[m] + HW_HI * BF_MAX + 1;
                        if (ex * ex + ez * ez >= lim * lim) continue;
                        double dd = len(ex, ez) - nr[m];
                        if (dd < HW_HI * BF_MAX && (dd < HW_LO || dd < halfWidth(rp, t0, t, b))) first[m] = step;
                    }
                    if (!stop) {
                        warpXZ(px, pz, w2);
                        stop = massN.fbm2(w2[0] / M_P, w2[1] / M_P, 2) > M_T - 0.08 || (tubesAt(px, pz) && tubes.apronAt(px, pz) > 0.02);
                    }
                }
                if (!stop && step % RO_STEP != 0) continue;
                int last = stop ? step - 1 : step;
                if (stop || groundGap(o, rp, t0, step, w2, cl, gf) <= 0) {
                    // the run ends at the first sample since the last read where the deck comes within 2 rows of the ground
                    int end = -1;
                    for (int q = read + 1; q <= last && end < 0; q++) if (groundGap(o, rp, t0, q, w2, cl, gf) <= 0) end = q;
                    if (end >= 0 && dryEnd(gf[0], cl, w2)) {
                        int cross = 0;
                        for (int m = 0; m < nn; m++) if (first[m] <= end) cross++;
                        e = t0 + end * RAMP_DT + (gf[0] < W + 40 ? 60 : 0) + RO_PEN * cross;
                    }
                    break;
                }
                read = step;
            }
            est[k] = e;
        }
        Integer[] idx = new Integer[RAMP_DIRS];
        for (int k = 0; k < RAMP_DIRS; k++) idx[k] = k;
        Arrays.sort(idx, Comparator.comparingDouble(k -> est[k]));   // stable: ties keep the slots' order
        ord = new int[RAMP_DIRS];
        for (int k = 0; k < RAMP_DIRS; k++) ord[k] = est[idx[k]] < 1e6 ? idx[k] : -1 - idx[k];
        o.order = ord;
        return ord;
    }

    /**
     * Whether a run that comes down on ground fl (rows) ends dry: over W + 6, out of the Canyons and the Caldera and off a
     * lava river's bed ({@code cl} the climate there, {@code w2} the warped point).
     */
    boolean dryEnd(double fl, Climate cl, double[] w2) {
        return !(fl < W + 6 || cl.wCan > 0.05 || cl.wCal > 0.3 || (fl < W + RIVER_LOW + 16 && lineDistW(riverN, w2, RIVER_P) < 14));
    }

    /**
     * How far the deck of ramp rp (opening o's, its head at t0) stands over the ground plus 2 rows at its centre line's
     * sample q (rows; at most 0: the run has come down): the warped point and the climate there are left in w2 and cl,
     * the ground in gf[0].
     */
    double groundGap(Opening o, Ramp rp, double t0, int q, double[] w2, Climate cl, double[] gf) {
        double t = t0 + q * RAMP_DT, b = bend(rp, t0, t);
        double px = o.x + rp.ux * t + rp.uz * b, pz = o.z + rp.uz * t - rp.ux * b;
        warpXZ(px, pz, w2);
        climate(px, pz, cl);
        double fl = rampGround(px, pz, w2, cl);
        gf[0] = fl;
        return headTop - deckDrop(rp, t - t0) - (fl + 2);
    }

    /** Ramp rp's bend for opening o's direction slot k (its centre line's sideways offset, {@link #bend}). */
    void bendOf(Opening o, int k, double t0, Ramp rp) {
        long hk = Hashing.mix(o.h, 60 + k, 0, 0);
        rp.bendA = BEND_A0 + BEND_A1 * Hashing.unit(hk);
        rp.bk0 = 64 * Hashing.unit(Hashing.mix(hk, 1, 0, 0));
        rp.bk1 = 64 * Hashing.unit(Hashing.mix(hk, 2, 0, 0));
        rp.b0 = bendN.noise2(t0 / BEND_P + rp.bk0, rp.bk1);
    }

    /** Opening o's ramp candidate in direction slot k (see the openings' notes above), or null where none can be built. */
    Ramp rampCandidate(Opening o, int k) {
        Ramp rp = new Ramp();
        formOf(o, rp);
        double th0 = rp.th0;
        double top = headTop, t0 = o.r + LIP;
        double[] w2 = new double[6];
        Climate cl = new Climate();
        int nMax = (int) (RAMP_MAX / RAMP_DT) + 2;
        double[] bx = new double[nMax], bz = new double[nMax], bf = new double[nMax], bh = new double[nMax];
        int dir = slotDir(o, k);
        rp.dir = dir;
        rp.ux = DIR_X[dir];
        rp.uz = DIR_Z[dir];
        bendOf(o, k, t0, rp);
        int n = 0;
        double tEnd = -1, endF = 0, reach = 0;
        for (double t = t0; t <= t0 + RAMP_MAX; t += RAMP_DT) {
            double b = bend(rp, t0, t);
            double px = o.x + rp.ux * t + rp.uz * b, pz = o.z + rp.uz * t - rp.ux * b;
            double hq = halfWidth(rp, t0, t, b) + HW_SLACK;
            double ext = len(px - o.x, pz - o.z) + hq + TALUS_MAX + 4;
            if (ext > RAMP_REACH) return null;
            warpXZ(px, pz, w2);
            if (massN.fbm2(w2[0] / M_P, w2[1] / M_P, 2) > M_T - 0.08) return null;
            if (tubesAt(px, pz) && tubes.apronAt(px, pz) > 0.02) return null;
            climate(px, pz, cl);
            double Fl = rampGround(px, pz, w2, cl);
            bx[n] = px;
            bz[n] = pz;
            bf[n] = Fl;
            bh[n] = hq;
            n++;
            reach = Math.max(reach, ext);
            if (top - deckDrop(rp, t - t0) <= Fl + 2) { tEnd = t; endF = Fl; break; }
        }
        if (tEnd < 0 || endF < W + 6) return null;
        double ex = bx[n - 1], ez = bz[n - 1];
        climate(ex, ez, cl);
        if (cl.wCan > 0.05 || cl.wCal > 0.3) return null;
        warp(ex, ez, w2);
        if (endF < W + RIVER_LOW + 16 && lineDistW(riverN, w2, RIVER_P) < 14) return null;
        // the fill starts where the free height under the base thickness first comes within its form's rows
        double tg = tEnd;
        for (int q = 0; q < n; q++) {
            double t = t0 + q * RAMP_DT;
            if (top - deckDrop(rp, t - t0) - th0 - bf[q] <= rp.dkFill) { tg = t; break; }
        }
        // the talus starts where the arch's opening under the deck closes: going back from tg, the last sample whose
        // opening over the ground, less the column ends' DK_J rows, is under TS_OPEN rows
        double ts = tg;
        for (int q = n - 1; q >= 0; q--) {
            double t = t0 + q * RAMP_DT;
            if (t >= tg) continue;
            double sp = (clamp01((t - t0) / Math.max(1, tg - t0)) + DK_ROOT) / (1 + DK_ROOT);
            if ((top - deckDrop(rp, t - t0) - th0 - bf[q]) * 4 * sp * (1 - sp) - DK_J >= TS_OPEN) break;
            ts = t;
        }
        rp.tEnd = tEnd + 2;
        rp.tg = tg;
        rp.ts = ts;
        rp.n = n;
        rp.px = Arrays.copyOf(bx, n);
        rp.pz = Arrays.copyOf(bz, n);
        rp.fl = Arrays.copyOf(bf, n);
        rp.hwq = Arrays.copyOf(bh, n);
        rp.reach = Math.max(o.r + 9, reach);
        double l = o.r + 3.5;
        rp.minX = o.x - l; rp.maxX = o.x + l; rp.minZ = o.z - l; rp.maxZ = o.z + l;
        for (int q = 0; q < n; q++) {
            rp.minX = Math.min(rp.minX, bx[q] - bh[q]); rp.maxX = Math.max(rp.maxX, bx[q] + bh[q]);
            rp.minZ = Math.min(rp.minZ, bz[q] - bh[q]); rp.maxZ = Math.max(rp.maxZ, bz[q] + bh[q]);
        }
        rp.score = tEnd + (endF < W + 40 ? 60 : 0);
        return rp;
    }

    /**
     * The landscape at (x, z) (rows, unrounded): the elevation with its hills, the sub-biomes' basins and the refuge
     * mounds. {@code w} must hold the warp at (x, z) ({@link #warp}).
     */
    double landscape(double x, double z, double[] w) {
        double wx = w[0], wz = w[1];
        double Fs = W + ELEV_MID + ELEV_AMP * elevN.fbm2(wx / ELEV_P, wz / ELEV_P, 2) + HL_A * hillN.fbm2(wx / HL_P, wz / HL_P, 2);
        double[] pc = patchCone(w);
        double cone = pc[0], cap = pc[1];
        Fs = Math.min(Fs, cone);
        // the refuge mounds stand over the low ground, no higher than the basins' shores allow
        if (Fs < W + MD_TOP0 + MD_TOP1) Fs = Math.max(Fs, Math.min(cap, mounds(w)));
        return Fs;
    }

    /**
     * Refuge mounds: on a hash lattice of MD_G blocks in the warped plane, a share MD_P of the cells holds a broad mound
     * of rock, its top MD_TOP0..MD_TOP0 + MD_TOP1 rows over W (over the flood's reach) and flat out to MD_R0..MD_R0 + MD_R1
     * blocks, its flanks falling MD_S0..MD_S1 a block in the warped plane: dry ground a walk from anywhere in the
     * lowlands. The landscape is the higher of the elevation and the mounds.
     */
    static final double MD_G = 210, MD_P = 0.85, MD_TOP0 = 44, MD_TOP1 = 12,
            MD_R0 = 14, MD_R1 = 30, MD_S0 = 0.14, MD_S1 = 0.19;

    /** The mounds of the 5 x 5 lattice cells round one (gi, gj), as {@link #mounds} reads them; one per thread. */
    private static final class Mounds {
        int gi = Integer.MIN_VALUE, gj, n;
        final double[] cx = new double[25], cz = new double[25], top = new double[25], r = new double[25], sl = new double[25];
    }

    private final ThreadLocal<Mounds> moundCells = ThreadLocal.withInitial(Mounds::new);

    /**
     * The highest mound floor at the warped point w (rows, unrounded), or -inf. The mounds of the 5 x 5 lattice cells round
     * w's are kept per thread while the points read stay in one cell (a ramp's run reads tens of points in one).
     */
    double mounds(double[] w) {
        double wx = w[0], wz = w[1];
        int gi = (int) Math.floor(wx / MD_G), gj = (int) Math.floor(wz / MD_G);
        Mounds ms = moundCells.get();
        if (ms.gi != gi || ms.gj != gj) {
            int n = 0;
            for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
                long h = Hashing.mix(seed, 53001, gi + di, gj + dj);
                if (Hashing.unit(h) >= MD_P) continue;
                ms.cx[n] = (gi + di + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * MD_G;
                ms.cz[n] = (gj + dj + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * MD_G;
                ms.top[n] = W + MD_TOP0 + MD_TOP1 * Hashing.unit(Hashing.mix(h, 3, 0, 0));
                ms.r[n] = MD_R0 + MD_R1 * Hashing.unit(Hashing.mix(h, 4, 0, 0));
                ms.sl[n] = MD_S0 + (MD_S1 - MD_S0) * Hashing.unit(Hashing.mix(h, 5, 0, 0));
                n++;
            }
            ms.n = n;
            ms.gi = gi;
            ms.gj = gj;
        }
        double best = -1e9;
        for (int k = 0; k < ms.n; k++) {
            double d = len(wx - ms.cx[k], wz - ms.cz[k]);
            best = Math.max(best, ms.top[k] - ms.sl[k] * Math.max(0, d - ms.r[k]));
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------- tube countries
    /**
     * A tube country stands only where the landscape round it reaches the tubes' level: of TV_N points on a ring
     * TV_OUT blocks outside its rim, at least TV_MIN must lie within TV_TOL rows of the apron's floor there. Elsewhere
     * its apron would stand as a cliff round it and the tubes could not be walked into: the Galleries ignore it (no
     * massif, no apron, no tubes, pools or lining).
     */
    static final int TV_N = 16, TV_MIN = 3;
    static final double TV_OUT = 76, TV_TOL = 10;

    private final ThreadLocal<long[]> standCache = ThreadLocal.withInitial(() -> { long[] a = new long[64]; Arrays.fill(a, Long.MIN_VALUE); return a; });

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
            int a = k * (DIRS / TV_N);
            double v = (c.a + TV_OUT) * DIR_X[a], u = (c.b + TV_OUT) * DIR_Z[a];
            double x = c.ox + v * c.cos - u * c.sin, z = c.oz + v * c.sin + u * c.cos;
            warp(x, z, w);
            double fs = landscape(x, z, w);
            if (Math.abs(fs - tubes.apronFloorAt(x, z)) <= TV_TOL) ok++;
        }
        return ok >= TV_MIN;
    }

    /** A stack stands where its middle's Caldera weight passes this. */
    static final double STACK_W = 0.6;

    /**
     * The Caldera's stacks round (x, z): sheer columns of rock out of the lake, their tops 47..62 rows over the table (over
     * the flood's reach) with a crater 3..6 deep (the vents); the highest stack floor here, or -inf.
     */
    double coneFloor(double x, double z) {
        int gi = (int) Math.floor(x / CONE_GRID), gj = (int) Math.floor(z / CONE_GRID);
        double best = -1e9;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            long h = Hashing.mix(seed, 36001, gi + di, gj + dj);
            if (Hashing.unit(h) > CONE_P) continue;
            double[] v = cone(h, gi + di, gj + dj);
            double d = len(x - v[0], z - v[1]);
            if (d >= v[2] || !stackStands(v)) continue;
            double fl = d < v[4] ? W + v[3] - v[5] : W + v[3] - 2 * (d / v[2]) * (d / v[2]);
            best = Math.max(best, fl);
        }
        return best;
    }

    /** Whether the stack {@code v} (see {@link #cone(long, int, int)}) stands: its middle's Caldera weight passes STACK_W. */
    boolean stackStands(double[] v) {
        Climate cl = new Climate();
        climate(v[0], v[1], cl);
        return cl.wCal > STACK_W;
    }

    /** A stack of grid cell (gi, gj): {x, z, radius, height over W, crater radius, crater depth}. */
    double[] cone(long h, int gi, int gj) {
        double sx = (gi + 0.2 + 0.6 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * CONE_GRID;
        double sz = (gj + 0.2 + 0.6 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * CONE_GRID;
        double r = Hashing.range(Hashing.mix(h, 3, 0, 0), 5, 9);
        double hgt = Hashing.range(Hashing.mix(h, 4, 0, 0), 47, 62);
        double cr = Hashing.range(Hashing.mix(h, 5, 0, 0), 1.6, 3.0);
        double cd = Hashing.range(Hashing.mix(h, 6, 0, 0), 3, 6);
        return new double[]{sx, sz, r, hgt, cr, cd};
    }

    // ------------------------------------------------------------------------------------------- bands
    /**
     * Rock bands: long ridges at mid height from a ridged noise, as bridges over low ground, ribs or ridges, where a mask
     * noise passes BAND_MASK. Over the mask's last BAND_FADE the band's underside comes down to the floor (its fade
     * share f: under = floor + f (under - floor)), so a band ends on a footing, never in mid-air (it ended where the
     * mask did, and a stretch with no pier to the ground floated: 18 539 and 8221 blocks in seed 555's box at (944,
     * -856)); none in a tube's mouth. In a stray cell ({@code footing}, see {@link #build}) the band stands on the ground.
     */
    static final double BAND_P = 300, BAND_MASK = 0.12, BAND_FADE = 0.1;

    void band(Cell c, double x, double z, Climate cl, double mouth, boolean footing) {
        double mk = bandMask.noise2(x / 240.0, z / 240.0);
        if (mk < BAND_MASK || mouth >= 1) return;
        double fade = smooth((mk - BAND_MASK) / BAND_FADE);
        double db = lineDist(bandN, x + 30 * warpB.noise2(x / 90.0, z / 90.0), z, BAND_P);
        double hw = (4 + 4 * smooth(0.5 + bandH.noise2(x / 80.0 + 7, z / 80.0)) * fade) * (1 - mouth);
        if (db >= hw) return;
        double q = db / hw;
        double deck = W + 68 + 16 * bandH.noise2(x / 190.0, z / 190.0);
        double top = deck + 1.5 * (1 - q * q);
        double thick = 4 + 5 * Math.sqrt(1 - q * q);
        double pier = Math.max(0, pierN.noise2(x / 34.0, z / 34.0) + 0.05) * 45;
        double under = deck - thick - pier - 3 * Hashing.unit(Hashing.mix(c.h, 7, 0, 0));
        under -= (1 - fade) * Math.max(0, under - c.f);
        int bl = (int) Math.floor(under + 0.5), bh = (int) Math.floor(top + 0.5) + 1;
        if (bh <= c.f || bl >= c.c) return;
        if (footing || bl <= c.f + 1) { c.f = Math.max(c.f, bh); if (c.c - c.f < 2) c.f = c.c = W + 200; return; }   // a ridge on the ground
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
                if (n + 4 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[n++] = lo; buf[n++] = o.bl; buf[n++] = o.bh; buf[n++] = hi;
            } else {
                if (n + 2 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[n++] = lo; buf[n++] = hi;
            }
        }
        c.near = buf;
        c.nearN = n;
    }

    // ============================================================================================ the density
    /** Density in blocks at a block of the layer (positive is rock): the cell's profile, exact. */
    public double density(int x, int y, int z) {
        Caches k = caches.get();
        return profile(k, column(k, x, z), x, y, z);
    }

    /** {@link #blocks(Cell, int, int, int)} where c = column(k, x, z) was the thread's last look at the column. */
    private double profile(Caches k, Cell c, int x, int y, int z) {
        if (c.deckMode != 0) return deckBlocks(c, x, y, z);
        int tf = k.colF[(x & 31) << 5 | (z & 31)];
        return tf == NO_TREAD ? blocks(c, y) : blocks(c, tf, c.c, c.bl, c.bh, y);
    }

    /** The profile's signed distance at row y (blocks): vertical, and 1.5 for rock beside a neighbour's open rows. */
    static double blocks(Cell c, int y) { return blocks(c, c.f, c.c, c.bl, c.bh, y); }

    /** The profile of open rows [f, ce) less the band [bl, bh) (none when bl >= bh) at row y, with c's neighbours. */
    static double blocks(Cell c, int f, int ce, int bl, int bh, int y) {
        if (f >= ce) return nearSide(c, y, 16);
        if (y >= f && y < ce) {
            if (bl < bh && y >= bl && y < bh) {
                double d = Math.min(y - bl + 1, bh - y);
                return nearSide(c, y, d);
            }
            double lo = bl < bh && y >= bh ? bh : f, hi = bl < bh && y < bl ? bl : ce;
            return -Math.min(y - lo + 1, hi - y);
        }
        double d = y < f ? f - y : y - ce + 1;
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

    /** A column's tube rows not read yet ({@link #fineBlocks}). */
    private static final int SPAN_UNREAD = Integer.MIN_VALUE;

    /**
     * The per-block density in blocks: the cell's profile, and the tubes carved in it (min(cave, tube)) in the rows the
     * tube field asks to be looked at where a country stands, read once per column and kept with it.
     */
    public double fineBlocks(int x, int y, int z) {
        Caches k = caches.get();
        double d = profile(k, column(k, x, z), x, y, z);
        if (tubes == null) return d;
        int slot = (x & 31) << 5 | (z & 31);
        int lo = k.colLo[slot];
        if (lo == SPAN_UNREAD) {
            boolean at = tubesAt(x + 0.5, z + 0.5);
            k.colHi[slot] = at ? tubes.spanHi(x, z) : 0;
            k.colLo[slot] = lo = at ? tubes.spanLo(x, z) : 1;
        }
        if (y >= lo && y <= k.colHi[slot]) d = Math.min(d, tubes.tube(x, y, z));
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
     * The Caldera's vents within r of (x, z) (their edge within reach): the craters of the stacks, enumerated from the
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
            if (len(v[0] - x, v[1] - z) > r + v[4]) continue;
            climate(v[0], v[1], cl);
            if (cl.wCal <= STACK_W) continue;
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
     * Places showing a Galleries biome round (x, z), nearest first, for /remix tp biome, /remix view and the shot planner:
     * sampled from the field itself, nothing planned. Every stand is checked block by block (two open rows over rock, no
     * fluid). The Obsidian Tubes: a spot in each standing tube country of the 5 x 5 tube cells round (x, z). Caldera,
     * Causeway and Scoria Canyons: the climate patches of that kind within PLACE_PATCHES lattice cells, up to 4 stands on
     * rings round each patch's middle. The main galleries: columns of a 16-block lattice over 785 x 785 blocks. At most
     * PLACE_MAX places, PLACE_SPACING apart.
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
                cand.add(new double[]{px, c.f, pz, len(px + 0.5 - x, pz + 0.5 - z)});
            }
            cand.sort(Comparator.comparingDouble(v -> v[3]));
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
            ps.sort(Comparator.comparingDouble(p -> len(p.cx - x, p.cz - z)));
            for (Patch pa : ps) {
                if (out.size() >= PLACE_MAX) break;
                Network.Place pl = null;
                // rings round the patch's middle (a warped-plane point; the warp moves it under WARP_A blocks)
                for (int ring = 0; ring < 5 && (pl == null || pl.stands.size() < 4); ring++) {
                    double rr = ring * 0.2 * pa.r;
                    int n = ring == 0 ? 1 : 12;
                    for (int q = 0; q < n && (pl == null || pl.stands.size() < 4); q++) {
                        int a = (q * DIRS / 12) & (DIRS - 1);
                        int px = (int) Math.floor(pa.cx + rr * DIR_X[a]), pz = (int) Math.floor(pa.cz + rr * DIR_Z[a]);
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
        out.sort(Comparator.comparingDouble(p -> len(p.x - x, p.z - z)));
        return out;
    }

    static boolean near(List<Network.Place> out, double x, double z) {
        for (Network.Place p : out) if (len(p.x - x, p.z - z) < PLACE_SPACING) return true;
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
        for (int k = 0; k < 4 && fineBlocks(x, y, z) >= 0; k++) y++;     // a column on the low ground's stairs (tread)
        for (int k = 0; k < 6 && fineBlocks(x, y - 1, z) < 0; k++) y--;
        if (fineBlocks(x, y - 1, z) <= 0 || fineBlocks(x, y, z) >= 0 || fineBlocks(x, y + 1, z) >= 0) return Integer.MIN_VALUE;
        if (fluid(x, y, z) != Terrain.AIR || fluid(x, y + 1, z) != Terrain.AIR) return Integer.MIN_VALUE;
        return y;
    }

    /** What the field says about block (x, y, z), for /remix info: the column cell, its rows, the tube country and the join. */
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
        if (join != null) {
            RampHit rh = new RampHit();
            rampsAt(c.x, c.z, rh, c.coarse ? HW_CO : 0);
            double ring = craterRing(x, z);
            out.add(String.format(java.util.Locale.ROOT, "  join: beta %.2f, opening site %.2f (the band's top y %d); disc %.2f%s%s", join.beta(x + 0.5, z + 0.5),
                    join.openingSite(x + 0.5, z + 0.5), join.bandTop(), rh.jt, rh.jt > 0 ? String.format(java.util.Locale.ROOT, " (open to y %.0f)", rh.jtTop - 1) : "",
                    rh.deck == rh.deck ? String.format(java.util.Locale.ROOT, ", a ramp's deck at y %.0f%s", rh.deck, rh.fill ? " (filled)" : "") : ""));
            if (ring < Double.POSITIVE_INFINITY)
                out.add(String.format(java.util.Locale.ROOT, "  crater: %.1f from a ramped hole's edge; the Wasteland's lowest floor may go down to y %.1f (a ramp's head is at y %.0f)",
                        ring, craterFloor(T, ring), headTop));
        }
        return out;
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
     * Distance from (x, z) to the nearest Caldera stack's axis in foot radii (0 axis, 1 foot), 9 when none is near. From
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

    double coneDist(int x, int z) {
        int gi = (int) Math.floor(x / CONE_GRID), gj = (int) Math.floor(z / CONE_GRID);
        double best = 9;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            long h = Hashing.mix(seed, 36001, gi + di, gj + dj);
            if (Hashing.unit(h) > CONE_P) continue;
            double[] v = cone(h, gi + di, gj + dj);
            best = Math.min(best, len(x + 0.5 - v[0], z + 0.5 - v[1]) / v[2]);
        }
        return best;
    }
}
