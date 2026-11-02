package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * The Wasteland (layer 2) as noise, since 0.5.1: winding tunnels and medium caves with the old Basalt Galleries' look,
 * generated as density functions of the seed and the position, with nothing planned or placed.
 *
 * <p>The tunnels lie on gently undulating sheets, one over another ({@link #sheets} of them: as many as the layer's
 * height holds under gate 3's landing, five at the default height). On each sheet they follow the
 * edges of a domain-warped cellular (Voronoi) pattern of its own: the boundaries between the cells of jittered sites.
 * Those edges form one connected net that meets in threes and has no loose ends, so a sheet's tunnels branch every few
 * dozen blocks and never dead-end. The warp bends them, and their corners and junctions (120 degrees apart, rough
 * walls) are blind. Medium caves are the same tunnels swollen by a 2D noise ({@link #CAVE_LO}), so a cave only ever
 * grows where tunnels pass; junctions widen a little into small rooms. A sheet rises to the level of the one above it
 * (or sinks to the one below) where a slow 2D noise says so, at walkable slopes, and there the two nets cross and join:
 * that is how the levels connect. The lowest sheet dips in its wide places: into lava pools below the lava table, or,
 * where the breakthrough noise ({@link Breakthrough#beta}) is high, down through the Galleries' ceiling.
 *
 * <p>Like {@link Network} it has a corner density ({@link #density}), a per-block pass after Minecraft's interpolation
 * ({@link #fine}) that puts back what the 4 x 8 x 4 corners round off (walking floors, tunnel cores, the basalt column
 * faces), fluids and biomes. Everything it needs per column is cached per thread ({@link #column}); the caches only
 * hold values of these pure functions, so any number of worldgen threads can call in. Over the rows where it meets the
 * Galleries the {@link Join} combines the two fields; this field reads the Galleries' density there (through
 * {@link Join.Lower}) only to keep its openings off their rock, and the Galleries never read this field.
 *
 * <p>The only plumbing is gate 3's lower landing (P2): near it the top sheet's floor rises to the landing's floor and its
 * tunnels widen until the landing sits inside one, so the landing always opens into the caves. The landing ellipsoid
 * and the end of the band 3 passage are {@link Network}'s, unchanged since 0.5.0.
 */
public final class WastelandField {
    /** At most this many tunnel sheets, bottom up (the default layer, 144 rows, has five). */
    static final int MAX_SHEETS = 6;
    /** The lowest sheet's floor over the lava table, and the rise from one sheet to the next (blocks). */
    static final double SHEET0 = 4, STEP = 26;
    /** Site spacing of each sheet's cellular pattern (blocks), and how far a site may sit from its lattice cell's middle. */
    static final double[] SPACING = {40, 36, 42, 38, 44, 40};
    static final double JITTER = 0.8;
    /** The shared domain warp: a broad bend and a short wiggle (amplitude per unit of noise, scale in blocks). */
    static final double WARP1 = 22, WARP1_SCALE = 90, WARP2 = 8, WARP2_SCALE = 26;
    /** Floor undulation of a sheet. */
    static final double FLOOR_AMP = 4, FLOOR_SCALE = 70, FLOOR2_AMP = 1.2, FLOOR2_SCALE = 18;
    /** Where a sheet rises to the next (or sinks to the one below): a slow noise between these values (linear ramp). */
    static final double RAMP_SCALE = 170, RAMP_LO = 0.08, RAMP_HI = 0.36;
    /** Tunnel half-width and vault height, before caves and junctions: base and noise swing. */
    static final double W0 = 2.6, W_SWING = 0.7, H0 = 5.6, H_SWING = 1.0, WH_SCALE = 30;
    /** Caves: where the cave noise passes CAVE_LO the tunnel starts to swell, fully at CAVE_HI. */
    static final double CAVE_SCALE = 64, CAVE_LO = 0.07, CAVE_HI = 0.32, CAVE_W = 8.5, CAVE_H = 9;
    /** Caves swell most round junctions: along a run the swelling falls to CAVE_RUN of it, by F3 - F1 = CAVE_J (blocks). */
    static final double CAVE_J = 20, CAVE_RUN = 0.2;
    /** Junction rooms: within JUNC_R of a meeting of three edges a tunnel is this much wider and taller. */
    static final double JUNC_R = 14, JUNC_W = 3.2, JUNC_H = 3.5;
    /** Openings: in the breakthrough patches the lowest sheet's junctions swell into rooms out to HOLE_ROOM (blocks of
     * F3 - F1) and their floors fall through within HOLE_R, fading over HOLE_FADE. */
    static final double HOLE_ROOM = 22, HOLE_R = 6.5, HOLE_FADE = 2.5;
    /** An opening's room is this much wider still (blocks), so its ledge runs all round the hole. */
    static final double HOLE_W = 3;
    /** Lava pools of the lowest sheet: where this noise passes LAVA_LO (fully at LAVA_HI), its wide places dip under the table. */
    static final double LAVA_SCALE = 80, LAVA_LO = 0.04, LAVA_HI = 0.18;
    /** No lava within LAVA_J (blocks of F3 - F1) of a junction, all of it from LAVA_J + LAVA_JF on. */
    static final double LAVA_J = 5, LAVA_JF = 7;
    /** The Ash Hollows (refuge), a 2D climate: from ASH_LO to ASH_HI; the biome is theirs from halfway. */
    static final double ASH_SCALE = 170, ASH_LO = 0.29, ASH_HI = 0.41;
    /** Roughness of the walls (blocks) and its scale. */
    static final double ROUGH = 2.0, ROUGH_SCALE = 14;
    /** The basalt columns in the walls: a per-column offset, constant over a small Voronoi cell (as 0.5.0). */
    static final double COLUMN = 1.7, COLUMN_CELL = 3.3;
    /** Floors take this share of the column offset, so the steps stay walkable (0.5.0's FLOOR_FADE). */
    static final double FLOOR_FADE = 0.5;
    /** Half-width of the walking band along each tunnel's middle line, whose floor the fine pass keeps exact. */
    static final double WALK_HALF = 2.2;
    /** The fine pass keeps open the vault shrunk by this much, so rough walls never close a tunnel. */
    static final double CORE_IN = 1.0;
    /** Gate 3's landing: the top sheet's floor follows it within PULL_R (fully within PULL_FULL), and widens within OPEN_R. */
    static final double PULL_R = 80, PULL_FULL = 40, OPEN_R = 30, OPEN_FADE = 10, OPEN_H = 10;

    final Terrain base;
    final Shape shape;
    final long seed;
    final int li = Terrain.WASTELAND;
    /** The layer's top and bottom rows, and its lava table (the first row above the lava). */
    final int top, bot, W;
    private final Perlin warpA, warpB, warpC, warpD, floorN, rampN, caveN, whN, lavaN, ashN, rough1, rough2;
    /**
     * The sheets this layer has: the top one's level sits 4 to 30 blocks under gate 3's lower landing floor, which its
     * floor rises to near the landing ({@link #PULL_R}).
     */
    final int sheets;
    private final long[] sheetSeed = new long[MAX_SHEETS];
    private final double[] cos = new double[MAX_SHEETS], sin = new double[MAX_SHEETS], offA = new double[MAX_SHEETS], offB = new double[MAX_SHEETS];
    private final ThreadLocal<Caches> cache;
    /** The breakthrough noise both layers read (where they open into each other). */
    final Breakthrough breakthrough;
    /** The Galleries under the join (for {@link Join#under}), or null. */
    final Join.Lower lower;
    /** Whether the Wasteland opens into the Galleries ({@link Join#on}); without the join beta is 0 everywhere. */
    final boolean joinOn;
    /** The Galleries' top row (the row under {@link #bot}) when the join is on. */
    final int gTop;

    /**
     * The Wasteland of {@code base}'s seed and layout. {@code breakthrough} and {@code lower} (the Galleries' field under
     * the join) are built before it; without them, or where the layout has a band between the two layers, the Wasteland
     * is closed at its bottom like the other layers.
     */
    public WastelandField(Terrain base, Breakthrough breakthrough, Join.Lower lower) {
        this.base = base;
        this.shape = base.shape;
        this.seed = base.seed;
        top = shape.top(li);
        bot = shape.bottom(li);
        W = shape.waterY(li);
        this.breakthrough = breakthrough;
        this.lower = lower;
        this.joinOn = breakthrough != null && lower != null && Join.on(shape);
        this.gTop = bot - 1;
        // gate 3's lower landing floor (Plans.buildGate): 3 + 1.55 landing half-heights under the layer's top
        double lFloor = top - 3 - 1.55 * base.settings.landingRadius() / 1.6;
        sheets = Math.max(1, Math.min(MAX_SHEETS, (int) Math.floor((lFloor - 4 - (W + SHEET0)) / STEP) + 1));
        long s = seed ^ 0x510E527FADE682D1L;
        warpA = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpB = new Perlin(Hashing.mix(s, 2, 0, 0));
        warpC = new Perlin(Hashing.mix(s, 3, 0, 0));
        warpD = new Perlin(Hashing.mix(s, 4, 0, 0));
        floorN = new Perlin(Hashing.mix(s, 5, 0, 0));
        rampN = new Perlin(Hashing.mix(s, 6, 0, 0));
        caveN = new Perlin(Hashing.mix(s, 7, 0, 0));
        whN = new Perlin(Hashing.mix(s, 8, 0, 0));
        lavaN = new Perlin(Hashing.mix(s, 9, 0, 0));
        ashN = new Perlin(Hashing.mix(s, 10, 0, 0));
        rough1 = new Perlin(Hashing.mix(s, 11, 0, 0));
        rough2 = new Perlin(Hashing.mix(s, 12, 0, 0));
        for (int k = 0; k < MAX_SHEETS; k++) {
            long h = Hashing.mix(s, 100 + k, 0, 0);
            sheetSeed[k] = h;
            double ang = 0.71 * k + 0.3 * Hashing.unit(Hashing.mix(h, 1, 0, 0));
            cos[k] = Math.cos(ang);
            sin[k] = Math.sin(ang);
            offA[k] = 1000 * Hashing.unit(Hashing.mix(h, 2, 0, 0));
            offB[k] = 1000 * Hashing.unit(Hashing.mix(h, 3, 0, 0));
        }
        cache = ThreadLocal.withInitial(Caches::new);
    }

    // ================================================================================================ columns
    /** What a column needs that does not depend on y, per sheet and for the column. */
    static final class Col {
        int x, z;
        boolean valid;
        /** Per sheet: distance to the nearest edge (blocks), floor at the edge's middle line, floor here (with the dip),
         * half-width, vault height, the dip's weight here (0..1), and whether the column is near the tunnel at all. */
        final double[] e = new double[MAX_SHEETS], f = new double[MAX_SHEETS], fl = new double[MAX_SHEETS], w = new double[MAX_SHEETS], h = new double[MAX_SHEETS];
        final boolean[] near = new boolean[MAX_SHEETS];
        double beta, ash, lavaDip, holeDip, shift;
        /** The lowest sheet's lava weight here (0..1, before the dip): above 0 on a pool's whole stretch, banks included. */
        double lavaW;
        /** How open the Galleries are right under the join at this column (0..1; 1 where the join is off). */
        double under;
        /** The roughness here: less in small tunnels, which it would close, and in the refuge. */
        double roughK;
        double fLo, fHi;
        Plans.Gate en;
        double entryD;
        boolean enNear;
        /** Scratch for the cellular search. */
        final double[] px = new double[16], pz = new double[16], pd = new double[16];
        final double[] out = new double[2];
    }

    /**
     * A thread's caches: the columns (by {@code (x & 31, z & 31)}, each holding the column it was filled for), a scratch
     * column for {@link #landingEdge}, and the last gate's landing edge.
     */
    static final class Caches {
        final Col[] cols = new Col[1024];
        final Col scratch = new Col();
        Plans.Gate edgeGate;
        double edge;

        Caches() {
            for (int i = 0; i < cols.length; i++) cols[i] = new Col();
        }
    }

    Col column(int x, int z) {
        Caches k = cache.get();
        Col c = k.cols[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.valid = false;
        c.x = x;
        c.z = z;
        compute(c, k);
        c.valid = true;
        return c;
    }

    private static int floor(double v) { int i = (int) v; return v < i ? i - 1 : i; }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /**
     * Sheet k's cellular pattern at the warped point (u, v): {distance to the nearest cell boundary, F3 - F1}, both in
     * blocks. The boundaries are the tunnels' middle lines; F3 - F1 is 0 where three cells meet (a junction).
     */
    void cells(Col c, int k, double u, double v) {
        double S = SPACING[k];
        double a = (cos[k] * u - sin[k] * v) / S + offA[k], b = (sin[k] * u + cos[k] * v) / S + offB[k];
        int ia = floor(a), ib = floor(b);
        int a0 = a - ia < 0.5 ? ia - 2 : ia - 1, b0 = b - ib < 0.5 ? ib - 2 : ib - 1;
        double d1 = 1e9, d2 = 1e9, d3 = 1e9;
        int i1 = 0;
        int n = 0;
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++, n++) {
            long hh = Hashing.mix(sheetSeed[k], a0 + i, b0 + j, 7);
            double sx = a0 + i + 0.5 + JITTER * ((hh & 0xFFFFFFFFL) * 0x1.0p-32 - 0.5);
            double sz = b0 + j + 0.5 + JITTER * ((hh >>> 32) * 0x1.0p-32 - 0.5);
            double dx = sx - a, dz = sz - b, d = dx * dx + dz * dz;
            c.px[n] = sx;
            c.pz[n] = sz;
            c.pd[n] = d;
            if (d < d1) { d3 = d2; d2 = d1; d1 = d; i1 = n; }
            else if (d < d2) { d3 = d2; d2 = d; }
            else if (d < d3) d3 = d;
        }
        // distance to the boundary of the nearest site's cell: the nearest of its bisectors with the other sites
        double e = 1e9, x1 = c.px[i1], z1 = c.pz[i1];
        for (int m = 0; m < 16; m++) {
            if (m == i1) continue;
            double dd = c.pd[m] - d1;
            double qx = c.px[m] - x1, qz = c.pz[m] - z1;
            double t = dd / (2 * Math.sqrt(qx * qx + qz * qz));
            if (t < e) e = t;
        }
        c.out[0] = e * S;
        c.out[1] = (Math.sqrt(d3) - Math.sqrt(d1)) * S;
    }

    /** The floor level of sheet k at a column before dips and the gate (blocks): its level, undulation and ramps. */
    double sheetFloor(int k, double x, double z) { return sheetFloor(k, x, z, 1); }

    /** {@link #sheetFloor}, with the rise to the next sheet scaled by {@code up} (the lowest sheet stays down where it opens). */
    double sheetFloor(int k, double x, double z, double up) {
        double f = W + SHEET0 + STEP * k;
        f += FLOOR_AMP * floorN.noise2(x / FLOOR_SCALE + 31.7 * k, z / FLOOR_SCALE - 17.3 * k) / 0.75;
        f += FLOOR2_AMP * floorN.noise2(x / FLOOR2_SCALE - 7.1 * k, z / FLOOR2_SCALE + 3.3 * k) / 0.75;
        double m = rampN.noise2(x / RAMP_SCALE + 51.3 * k, z / RAMP_SCALE - 23.9 * k);
        if (k < sheets - 1) f += STEP * up * clamp01((m - RAMP_LO) / (RAMP_HI - RAMP_LO));
        if (k > 0) f -= STEP * clamp01((-m - RAMP_LO) / (RAMP_HI - RAMP_LO));
        return f;
    }

    private void compute(Col c, Caches k0) {
        int x = c.x, z = c.z;
        double wx = WARP1 * warpA.noise2(x / WARP1_SCALE, z / WARP1_SCALE) + WARP2 * warpC.noise2(x / WARP2_SCALE, z / WARP2_SCALE);
        double wz = WARP1 * warpB.noise2(x / WARP1_SCALE, z / WARP1_SCALE) + WARP2 * warpD.noise2(x / WARP2_SCALE, z / WARP2_SCALE);
        double u = x + wx, v = z + wz;
        c.beta = joinOn ? breakthrough.beta(x, z) : 0;
        c.ash = clamp01((ashN.noise2(x / ASH_SCALE, z / ASH_SCALE) - ASH_LO) / (ASH_HI - ASH_LO));
        double lava = clamp01((lavaN.noise2(x / LAVA_SCALE, z / LAVA_SCALE) - LAVA_LO) / (LAVA_HI - LAVA_LO));
        // lava only far from any opening, and none in the refuge
        lava *= (1 - clamp01(c.beta / Join.LAVA_BETA)) * (1 - c.ash);
        double hole = Terrain.smooth((c.beta - Join.HOLE_BETA) / Join.HOLE_RAMP);
        // an opening only falls where the Galleries are open right under it, so it never ends in a pit on their rock
        c.under = hole > 0 ? Join.under(lower, gTop, x, z) : 1;
        double wh = whN.noise2(x / WH_SCALE, z / WH_SCALE) / 0.4, wh2 = whN.noise2(x / WH_SCALE + 40.5, z / WH_SCALE - 12.5) / 0.4;
        wh = wh < -1 ? -1 : (wh > 1 ? 1 : wh);
        wh2 = wh2 < -1 ? -1 : (wh2 > 1 ? 1 : wh2);
        c.shift = COLUMN * voronoiValue(x, z) * (1 - c.ash);
        // gate 3's lower landing in this cell (its centre keeps 92 blocks inside the cell, past PULL_R)
        Plans.Gate en = base.plans.gate(li + 1, base.plans.cellOf(x), base.plans.cellOf(z));
        c.en = en;
        c.entryD = Math.hypot(x - en.lx, z - en.lz);
        c.enNear = en.overlaps(x, z, x, z, 8);
        c.fLo = 1e9;
        c.fHi = -1e9;
        c.lavaDip = 0;
        c.holeDip = 0;
        c.lavaW = 0;
        for (int k = 0; k < sheets; k++) {
            cells(c, k, u, v);
            double e = c.out[0], j = c.out[1];
            c.e[k] = e;
            double cave = clamp01((caveN.noise2(x / CAVE_SCALE + 13.1 * k, z / CAVE_SCALE - 29.7 * k) + (k == 0 ? 0.05 : 0) - CAVE_LO) / (CAVE_HI - CAVE_LO));
            cave *= CAVE_RUN + (1 - CAVE_RUN) * Terrain.smooth(1 - j / CAVE_J);
            double f = sheetFloor(k, x, z, k == 0 ? 1 - hole : 1);
            // the lowest sheet's dips: only where it lies at its own level (not up a ramp)
            double low = k == 0 ? clamp01((W + 10 - f) / 4) : 0;
            // lava stays out of the junctions, so every pool has dry ground round its ends and the banks on either
            // side of it join there (lava along every edge round a cell would otherwise leave each bank an island)
            double lw = lava * low * Terrain.smooth((j - LAVA_J) / LAVA_JF), hw = hole * low;
            // lava pools lie along the middle of caves; openings at the middle of rooms round junctions
            double hr = hw * Terrain.smooth(1 - j / HOLE_ROOM), hd = hw * clamp01((HOLE_R - j) / HOLE_FADE) * c.under;
            c.lavaW = Math.max(c.lavaW, lw);
            cave = Math.max(cave, Math.max(lw * 0.9, hr));
            cave = Math.max(cave, c.ash * 0.45);
            double cs = Terrain.smooth(cave);
            double jr = Math.max(0, 1 - j / JUNC_R);
            jr *= jr;
            double w = W0 + W_SWING * (k % 2 == 0 ? wh : wh2) + CAVE_W * cs + JUNC_W * jr + HOLE_W * hr;
            double h = H0 + H_SWING * (k % 2 == 0 ? wh2 : wh) + CAVE_H * cs + JUNC_H * jr;
            if (k == sheets - 1 && c.entryD < PULL_R) {
                double p = clamp01((PULL_R - c.entryD) / (PULL_R - PULL_FULL));
                f += (en.lFloor - f) * p;
                if (c.entryD < OPEN_R) {
                    double o = clamp01((OPEN_R - c.entryD) / OPEN_FADE);
                    double eLand = landingEdge(en, k0);
                    w = Math.max(w, w + (eLand + 3.5 - w) * o);
                    h = Math.max(h, h + (OPEN_H - h) * o);
                }
            }
            // the vault stays under the layer's seal
            h = Math.min(h, top - Terrain.SEAL - 1 - f);
            double fl = f;
            if (hd > 0) {
                // an opening: the room's floor falls through into the Galleries, with a ledge round it
                fl = f + (Math.min(f, bot - 4) - f) * hd;
                c.holeDip = Math.max(c.holeDip, hd);
            } else if (lw > 0) {
                // a lava pool along the middle of a wide place, with banks of at least a few blocks
                double dw = lw * clamp01((0.62 * w - e) / (0.25 * w));
                fl = f + (Math.min(f, W - 3) - f) * dw;
                c.lavaDip = Math.max(c.lavaDip, dw);
            }
            c.f[k] = f;
            c.fl[k] = fl;
            c.w[k] = w;
            c.h[k] = h;
            c.near[k] = e < w * 1.25 + 1.5 && h > 0;
            if (c.near[k]) {
                c.fLo = Math.min(c.fLo, fl - 5);
                c.fHi = Math.max(c.fHi, f + h + 2);
            }
        }
        double hmax = 0;
        for (int k = 0; k < sheets; k++) if (c.near[k] && c.e[k] < c.w[k]) hmax = Math.max(hmax, c.h[k]);
        c.roughK = (hmax > 0 ? clamp01((hmax - 2) / 8) * 0.65 + 0.35 : 1) * (1 - 0.6 * c.ash);
        if (c.entryD < en.landR + 6) {
            c.fLo = Math.min(c.fLo, en.lFloor - 9);
            c.fHi = Math.max(c.fHi, en.lCy + en.landHalf + 4);
        }
    }

    /**
     * How far gate 3's lower landing lies from the top sheet's nearest tunnel line (blocks), ignoring the gate's own pull.
     * A function of the gate alone; the thread keeps the last gate's.
     */
    double landingEdge(Plans.Gate en, Caches k) {
        if (k.edgeGate == en) return k.edge;
        Col c = k.scratch;
        int x = (int) Math.floor(en.lx), z = (int) Math.floor(en.lz);
        double wx = WARP1 * warpA.noise2(x / WARP1_SCALE, z / WARP1_SCALE) + WARP2 * warpC.noise2(x / WARP2_SCALE, z / WARP2_SCALE);
        double wz = WARP1 * warpB.noise2(x / WARP1_SCALE, z / WARP1_SCALE) + WARP2 * warpD.noise2(x / WARP2_SCALE, z / WARP2_SCALE);
        cells(c, sheets - 1, x + wx, z + wz);
        k.edgeGate = en;
        k.edge = c.out[0];
        return k.edge;
    }

    // the tests' and tools' view of the pattern
    Perlin warpA() { return warpA; }
    Perlin warpB() { return warpB; }
    Perlin warpC() { return warpC; }
    Perlin warpD() { return warpD; }
    long sheetSeed(int k) { return sheetSeed[k]; }
    double offA(int k) { return offA[k]; }
    double offB(int k) { return offB[k]; }
    double cos(int k) { return cos[k]; }
    double sin(int k) { return sin[k]; }

    /** A value in [-1, 1] constant over each small Voronoi cell (the basalt columns' offsets; Network.voronoiValue). */
    double voronoiValue(double x, double z) {
        double size = COLUMN_CELL;
        int gx = (int) Math.floor(x / size), gz = (int) Math.floor(z / size);
        double best = 1e9;
        long bh = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long hh = Hashing.mix(seed, 8800, gx + dx, gz + dz);
            double px = (gx + dx + 0.15 + 0.7 * Hashing.unit(hh)) * size, pz = (gz + dz + 0.15 + 0.7 * Hashing.unit(Hashing.mix(hh, 1, 0, 0))) * size;
            double d = (x - px) * (x - px) + (z - pz) * (z - pz);
            if (d < best) { best = d; bh = hh; }
        }
        return Hashing.unit(Hashing.mix(bh, 2, 0, 0)) * 2 - 1;
    }

    // ================================================================================================ density
    /**
     * A tunnel's cross-section (0.5.0's vault): the floor rises toward the walls past a third of the half-width, the
     * ceiling is an ellipse over the middle line's floor. {@code fl} is the floor here (lower in a dip), {@code f} the
     * middle line's. Negative inside.
     */
    static double vault(double e, double y, double fl, double f, double w, double h) {
        double q = e / w;
        if (q >= 1.25) return Terrain.SOLID;
        double floor = fl + (q > 0.35 ? (q - 0.35) * (q - 0.35) * w * 0.9 : 0);
        double ceil = f + h * Math.sqrt(Math.max(0, 1 - q * q));
        return Math.max(floor - y, y - ceil);
    }

    double rough(int x, int y, int z) {
        double s = ROUGH_SCALE;
        return ROUGH * rough1.noise3(x / s, y / (s * 0.75), z / s) + ROUGH * 0.35 * rough2.noise3(x / (s * 0.4), y / (s * 0.4), z / (s * 0.4));
    }

    /**
     * Density in blocks (positive is rock) at a corner. Defined for the layer's rows and on down through the join's
     * band ({@link Join}), where it is the Wasteland's half of the combined density: no bottom seal of its own.
     */
    public double density(int x, int y, int z) {
        Col c = column(x, z);
        double d = Terrain.SOLID;
        for (int k = 0; k < sheets; k++) {
            if (!c.near[k]) continue;
            if (y < c.fl[k] - 8 || y > c.f[k] + c.h[k] + 8) continue;
            d = Network.smin(d, vault(c.e[k], y, c.fl[k], c.f[k], c.w[k], c.h[k]), 3);
        }
        if (d > -12 && d < 12) d += rough(x, y, z) * c.roughK;
        if (top - y < Terrain.SEAL) d = Math.max(d, Terrain.SEAL - (top - y));
        // the Wasteland reaches at most a few rows into the Galleries (its dips), and is rock under that
        if (y < bot - 8) d = Math.max(d, (bot - 8) - y);
        Plans.Gate en = c.en;
        if (c.entryD < en.landR + 4 && y > en.lFloor - 2 && y < en.lCy + en.landHalf + 4)
            d = Math.min(d, base.network.landing(c.entryD, en.landR, en.lCy, en.landHalf, en.lFloor, x, y, z));
        if (c.enNear && y > en.y1 - 3) d = Math.min(d, base.network.passage(en, x, y, z));
        return d;
    }

    /**
     * The per-block pass after interpolation ({@code c8} is the interpolated density in blocks; so is the result): the
     * basalt columns' faces, the walking floor along each tunnel's middle line (rock under it, open over it), the
     * tunnels' cores and the landing. Outside the column's span only the columns' offset acts.
     */
    double fineBlocks(int x, int y, int z, double c8) {
        Col c = column(x, z);
        boolean floorNear = false;
        if (y >= c.fLo && y <= c.fHi) {
            for (int k = 0; k < sheets; k++) if (c.near[k] && c.e[k] < c.w[k] && Math.abs(y - c.fl[k]) < 2.5) { floorNear = true; break; }
        }
        if (c8 > -3 && c8 < 3) c8 += c.shift * (floorNear ? FLOOR_FADE : 1);
        Plans.Gate en = c.en;
        boolean pass = c.enNear && y > en.y1 - 3;
        if ((y < c.fLo || y > c.fHi) && !pass) return c8;
        double keep = -Terrain.SOLID, open = Terrain.SOLID;
        for (int k = 0; k < sheets; k++) {
            if (!c.near[k]) continue;
            double e = c.e[k], f = c.f[k], fl = c.fl[k];
            if (y < fl - 5 || y > f + c.h[k] + 2) continue;
            // the walking band: exact floor (none where it falls into the Galleries) and two blocks of air over it
            if (e < WALK_HALF && fl > bot + 4) {
                keep = Math.max(keep, Math.min(fl - y, y - (fl - 3)));
                open = Math.min(open, Math.max(e - 1.0, Math.max(fl - y, y - (fl + 2.6))));
            }
            if (e < c.w[k] * 1.1) open = Math.min(open, vault(e, y, fl, f, c.w[k], c.h[k]) + CORE_IN);
        }
        if (c.entryD < en.landR + 4 && y > en.lFloor - 2 && y < en.lCy + en.landHalf + 4)
            open = Math.min(open, base.network.landing(c.entryD, en.landR, en.lCy, en.landHalf, en.lFloor, x, y, z));
        if (pass) open = Math.min(open, base.network.passage(en, x, y, z));
        if (keep <= 0) keep = -Terrain.SOLID;
        if (open > 0) open = Terrain.SOLID;
        return Math.min(Math.max(c8, keep), open);
    }

    /** {@link #fineBlocks} on Minecraft's scale, for the layer's rows above the join's band. */
    public double fine(int x, int y, int z, double coarse) {
        double d = fineBlocks(x, y, z, coarse * 8.0) / 8.0;
        return d < -1 ? -1 : (d > 1 ? 1 : d);
    }

    /** The cores the fine pass keeps open, alone (negative inside): for the join's rows under the layer. */
    double openCore(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.fLo || y > c.fHi) return Terrain.SOLID;
        double open = Terrain.SOLID;
        for (int k = 0; k < sheets; k++) {
            if (!c.near[k]) continue;
            if (y < c.fl[k] - 5 || y > c.f[k] + c.h[k] + 2) continue;
            if (c.e[k] < c.w[k] * 1.1) open = Math.min(open, vault(c.e[k], y, c.fl[k], c.f[k], c.w[k], c.h[k]) + CORE_IN);
        }
        return open > 0 ? Terrain.SOLID : open;
    }

    // ================================================================================================ fluids and biomes
    /**
     * What fills an open block of the Wasteland: lava under the table, but air where the breakthrough noise is high
     * enough that an opening could be near ({@link Join#AIR_BETA}); air above it. The join's seal keeps every open
     * block under the table either far from the openings (lava) or inside them (air), never both side by side.
     */
    public int fluid(int x, int y, int z) {
        if (y >= W) return Terrain.AIR;
        Col c = column(x, z);
        return c.beta >= Join.AIR_BETA ? Terrain.AIR : Terrain.LAVA;
    }

    /** The Ash Hollows where their climate passes halfway, else the Wasteland. */
    public DeepBiome biome(int x, int y, int z) {
        return column(x, z).ash >= 0.5 ? DeepBiome.ASH_HOLLOWS : DeepBiome.WASTELAND;
    }

    // ================================================================================================ look queries
    /** The basalt columns' offset at a column (blocks, about -1.7 to 1.7; 0 in the Ash Hollows). */
    public double columnShift(int x, int z) { return column(x, z).shift; }

    /** An id of the small Voronoi cell a column belongs to (one basalt column): equal ids, one column. */
    public long columnCell(int x, int z) {
        double size = COLUMN_CELL;
        int gx = (int) Math.floor(x / size), gz = (int) Math.floor(z / size);
        double best = 1e9;
        long bh = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long hh = Hashing.mix(seed, 8800, gx + dx, gz + dz);
            double px = (gx + dx + 0.15 + 0.7 * Hashing.unit(hh)) * size, pz = (gz + dz + 0.15 + 0.7 * Hashing.unit(Hashing.mix(hh, 1, 0, 0))) * size;
            double d = (x - px) * (x - px) + (z - pz) * (z - pz);
            if (d < best) { best = d; bh = hh; }
        }
        return bh;
    }

    /** How much of a lava pool's dip a column has (0..1): magma rims and scorched banks go where it is above 0. */
    public double lavaPool(int x, int z) { return column(x, z).lavaDip; }

    /**
     * Whether the field keeps lava off the open space round (x, y, z) (LookQueries.lavaKept): true on every column outside
     * a lava pool's stretch of the lowest sheet, which includes every opening into the Galleries and its rim (pools only
     * exist where beta is under Join.LAVA_BETA). The palette's Wasteland shore puts no magma where this is true.
     */
    public boolean lavaKept(int x, int y, int z) { return column(x, z).lavaW <= 0; }

    /** How much of an opening's dip a column has (0..1): the rims of the holes into the Galleries. */
    public double openingRim(int x, int z) { return column(x, z).holeDip; }

    /** The Ash Hollows' climate at a column (0..1): pale rock blends in from 0, the biome is theirs from 0.5. */
    public double ash(int x, int z) { return column(x, z).ash; }

    /** The sheet whose tunnel or cave holds a block (its vault is nearest), or -1 in the rock. */
    public int sheetAt(int x, int y, int z) {
        Col c = column(x, z);
        int best = -1;
        double bd = 0;
        for (int k = 0; k < sheets; k++) {
            if (!c.near[k]) continue;
            double d = vault(c.e[k], y, c.fl[k], c.f[k], c.w[k], c.h[k]);
            if (d < bd) { bd = d; best = k; }
        }
        return best;
    }

    /** How cave-like the widest tunnel over a column is (0 a tunnel, 1 a full cave): its half-width past the tunnels'. */
    public double caveness(int x, int z) {
        Col c = column(x, z);
        double best = 0;
        for (int k = 0; k < sheets; k++) if (c.near[k]) best = Math.max(best, clamp01((c.w[k] - W0 - W_SWING) / CAVE_W));
        return best;
    }

    // ================================================================================================ dev tools
    /** How far {@link #places} looks (blocks) and how many places it lists at most. */
    static final double PLACE_REACH = 384;
    static final int PLACE_MAX = 64;

    /** {@link #places(DeepBiome, double, double, double, int)} out to {@link #PLACE_REACH}, at most {@link #PLACE_MAX}. */
    public List<Network.Place> places(DeepBiome b, double x, double z) { return places(b, x, z, PLACE_REACH, PLACE_MAX); }

    /**
     * Places of the Wasteland or the Ash Hollows near (x, z), nearest first, for /remix tp biome and /remix view: points
     * on the tunnels' middle lines, sampled on a grid out to {@code reach}, each with its spot to stand on (the walking
     * floor, never under the lava table or over an opening).
     */
    public List<Network.Place> places(DeepBiome b, double x, double z, double reach, int max) {
        List<Network.Place> out = new ArrayList<>();
        int step = 6;
        int R = (int) Math.ceil(reach / step);
        for (int r = 0; r <= R && out.size() < max; r++) {
            for (int i = -r; i <= r && out.size() < max; i++) for (int j = -r; j <= r && out.size() < max; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != r) continue;
                int px = (int) Math.floor(x) + i * step, pz = (int) Math.floor(z) + j * step;
                Col c = column(px, pz);
                if (biome(px, 0, pz) != b) continue;
                for (int k = 0; k < sheets; k++) {
                    if (!c.near[k] || c.e[k] > 1.2 || c.fl[k] < W + 1 || c.h[k] < 3) continue;
                    Network.Place p = new Network.Place(px + 0.5, pz + 0.5, c.w[k], false, b.id + " sheet " + k);
                    p.stands.add(new double[]{px + 0.5, Math.ceil(c.fl[k]), pz + 0.5});
                    out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    /** For /remix info: the column's values and each sheet's tunnel over it, and the sheet whose vault holds the block. */
    public List<String> describe(int x, int y, int z) {
        Col c = column(x, z);
        List<String> out = new ArrayList<>();
        out.add(String.format(java.util.Locale.ROOT, "wasteland: beta %.2f, ash %.2f, lava pool %.2f, opening %.2f (Galleries open under it %.2f), column shift %.1f, rows %.0f..%.0f",
                c.beta, c.ash, c.lavaDip, c.holeDip, c.under, c.shift, c.fLo, c.fHi));
        for (int k = 0; k < sheets; k++)
            out.add(String.format(java.util.Locale.ROOT, "  sheet %d: %.1f from its line, floor %.1f (%.1f here), half-width %.1f, vault %.1f%s", k, c.e[k], c.f[k], c.fl[k], c.w[k], c.h[k],
                    c.near[k] ? "" : ", too far"));
        int s = sheetAt(x, y, z);
        out.add(s >= 0 ? "  in sheet " + s + "'s vault" : "  in rock");
        return out;
    }
}
