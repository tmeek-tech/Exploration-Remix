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
 * <p>The caves are not arenas: the larger a vault, the rougher its walls (a broad third octave in the caves), the
 * smaller the core the per-block pass keeps open in it, and the more its floor steps up and down in whole basalt
 * columns (terraces, coherent over a few columns); in tall caves some column cells hang from the ceiling and a few stand
 * from floor to ceiling. Nothing is carved along the tunnels: their floors take the same columns and roughness as the
 * rest, over a guard of rock that keeps holes out of them.
 *
 * <p>Like {@link Network} it has a corner density ({@link #density}), a per-block pass after Minecraft's interpolation
 * ({@link #fine}) that puts back what the 4 x 8 x 4 corners round off (floor guards, tunnel cores, the basalt column
 * faces and terraces), fluids and biomes. Everything it needs per column is cached per thread ({@link #column}); the
 * caches only hold values of these pure functions, so any number of worldgen threads can call in. Over the rows where it
 * meets the Galleries the {@link Join} combines the two fields; this field reads the Galleries' density there (through
 * {@link Join.Lower}) only to keep its openings off their rock, and the Galleries never read this field.
 *
 * <p>The only plumbing is gate 3's lower landing (P2): near it the top sheet's pattern is shifted so one of its own
 * tunnels runs under the band 3 passage's foot and its floor level is pulled to the landing's floor (its relief kept but
 * at the landing itself), so the passage opens into one of the field's own tunnels, as wide as it is there. The end of the
 * band 3 passage is {@link Network}'s, unchanged since 0.5.0; the landing's dome is not carved here.
 */
public final class WastelandField {
    /** At most this many tunnel sheets, bottom up (the default layer, 144 rows, has five). */
    static final int MAX_SHEETS = 6;
    /** The lowest sheet's floor over the lava table, and the rise from one sheet to the next (blocks). */
    static final double SHEET0 = 4, STEP = 26;
    /** Site spacing of each sheet's cellular pattern (blocks), and how far a site may sit from its lattice cell's middle. */
    static final double[] SPACING = {40, 36, 40, 38, 40, 40};
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
    static final double CAVE_SCALE = 64, CAVE_LO = 0.07, CAVE_HI = 0.32, CAVE_W = 6, CAVE_H = 9;
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
    /**
     * A lava pool's shape across its run: its middle sits off the tunnel's line by up to LAVA_OFF of the half-width (one
     * value per run, from the hash of the two sites the run divides), its half-width is LAVA_HALF of the tunnel's,
     * swelling and narrowing by LAVA_SWING along the run (noise of scale LAVA_SWING_SCALE), its outline ragged by
     * LAVA_EDGE blocks (scale LAVA_EDGE_SCALE); the bank on the far side keeps LAVA_BANK blocks at least.
     */
    static final double LAVA_OFF = 0.3, LAVA_HALF = 0.62, LAVA_SWING = 0.15, LAVA_SWING_SCALE = 24, LAVA_EDGE = 1.5, LAVA_EDGE_SCALE = 7, LAVA_BANK = 2.5;
    /** The Ash Hollows (refuge), a 2D climate: from ASH_LO to ASH_HI; the biome is theirs from halfway. */
    static final double ASH_SCALE = 170, ASH_LO = 0.29, ASH_HI = 0.41;
    /** Their climate swells the tunnels at least this much (of the cave noise), so the refuge is roomier. */
    static final double ASH_CAVE = 0.3;
    /**
     * Roughness of the walls (blocks) and its scale, in a tunnel; it grows by ROUGH_W per block the vault a column is in
     * is wider than W0, and in caves (from half-width 6, fully at 10) a third, broad octave of the same amplitude joins it
     * (scale ROUGH3_SCALE, flattened 3:4): bulges, ledges and recesses the size of the cave.
     */
    static final double ROUGH = 2.0, ROUGH_SCALE = 14, ROUGH_W = 0.25, ROUGH3_SCALE = 28;
    /** The basalt columns in the walls: a per-column offset, constant over a small Voronoi cell (as 0.5.0). */
    static final double COLUMN = 1.7, COLUMN_CELL = 3.3;
    /** Floors take this share of the column offset, so the steps stay walkable (0.5.0's FLOOR_FADE). */
    static final double FLOOR_FADE = 0.5;
    /**
     * The fine pass keeps open the vault shrunk by CORE_IN, so rough walls never close a tunnel; in vaults taller than
     * CORE_H by CORE_DEEP more per block, so a cave's walls, floor and ceiling keep their relief.
     */
    static final double CORE_IN = 1.0, CORE_DEEP = 0.35, CORE_H = 10;
    /**
     * The floor guard (rock under every floor, so roughness never holes it) starts at the floor's own row in the narrow
     * tunnels (half-width GUARD_W0 or less) and a row under it in the wider vaults (GUARD_W1 or more), between them by
     * the share: a free row lets the floor take the roughness's pits, and in a tunnel that free row against a raised
     * neighbour is a step of two, which a walker climbs one way only.
     */
    static final double GUARD_W0 = 4, GUARD_W1 = 6;
    /**
     * Terraces: in caves (half-width from 6, fully at 10) the floor of each basalt column is set up or down by a whole
     * number of blocks, TERRACE times a noise read at the column's site (scale TERRACE_SCALE), so neighbouring columns
     * step together; at most TERRACE_MAX either way.
     */
    static final double TERRACE = 8, TERRACE_SCALE = 16, TERRACE_MAX = 4;
    /**
     * A cave's floor rises and falls across it, under the terraces: up to BUMP blocks either way, a noise of about a
     * cave's own size (scale BUMP_SCALE), in caves as the terraces are, fading out into the dips and at gate 3's landing.
     */
    static final double BUMP = 4, BUMP_SCALE = 10;
    /**
     * Hanging column ends and pillars in tall caves (vault HANG_H or more): a column cell hangs from the ceiling by up to
     * HANG_MAX (and h - 8) blocks where a cluster noise (scale HANG_SCALE) says so; a cell whose hash passes PILLAR stands
     * from floor to ceiling, PILLAR_E blocks or more from the tunnel's line.
     */
    static final double HANG_MAX = 6, HANG_H = 12, HANG_SCALE = 20, PILLAR = 0.97, PILLAR_E = 3.7;
    /**
     * Gate 3's landing. The top sheet's floor level (its base and ramps, not its relief) is pulled to the landing's floor
     * within PULL_FULL, fading out at PULL_R, or further where the gap is deep (the fade is at least the gap over
     * PULL_SLOPE long, up to PULL_MAX, inside the 92 blocks the landing keeps from its cell's edges); the relief fades
     * out from UND_R0 + UND_FADE to UND_R0, so only the landing itself is level. The sheet's pattern shifts so a tunnel's
     * line runs through the landing's centre (fully within SHIFT_R0, none from SHIFT_R1); its width and height are the
     * field's own there (no cave is made for the landing).
     */
    static final double PULL_R = 80, PULL_FULL = 40, PULL_MAX = 92, PULL_SLOPE = 0.65;
    static final double SHIFT_R0 = 20, SHIFT_R1 = 90, UND_R0 = 6, UND_FADE = 8;
    /**
     * Terraces, hanging ends and pillars keep this far (blocks) from the landing's centre, and out of the passage; within
     * it the top sheet's floor guard is exact (whole up to the floor's own row), so the landing never cuts off.
     */
    static final double LAND_CLEAR = 10;
    /** The decorations' walk guard ({@link #walkNear}): rows from this far under a tunnel's floor to this far over it. */
    static final int WALK_BELOW = 3, WALK_ABOVE = 6;

    final Terrain base;
    final Shape shape;
    final long seed;
    final int li = Terrain.WASTELAND;
    /** The layer's top and bottom rows, and its lava table (the first row above the lava). */
    final int top, bot, W;
    private final Perlin warpA, warpB, warpC, warpD, floorN, rampN, caveN, whN, lavaN, ashN, rough1, rough2, rough3, terN, clusterN, lavaWN, lavaEN, bumpN;
    /**
     * The sheets this layer has: the top one's level sits 4 to 30 blocks under gate 3's lower landing floor, which its
     * floor rises to near the landing ({@link #PULL_R}).
     */
    final int sheets;
    /** The rise from one sheet to the next: {@link #STEP}, or more where the sheets run out under a high landing. */
    final double step;
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
        // a layer too high for the sheets (a thick band 3 gives it its rows) spaces them out, so the top one still sits
        // within 30 blocks under the landing and the pull to it stays walkable
        step = sheets == MAX_SHEETS && lFloor - (W + SHEET0 + STEP * (sheets - 1)) > 30 ? (lFloor - 17 - (W + SHEET0)) / (sheets - 1) : STEP;
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
        rough3 = new Perlin(Hashing.mix(s, 13, 0, 0));
        terN = new Perlin(Hashing.mix(s, 14, 0, 0));
        clusterN = new Perlin(Hashing.mix(s, 15, 0, 0));
        lavaWN = new Perlin(Hashing.mix(s, 16, 0, 0));
        lavaEN = new Perlin(Hashing.mix(s, 17, 0, 0));
        bumpN = new Perlin(Hashing.mix(s, 18, 0, 0));
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
        /** The half-width of the widest vault the column is in (faded out from 0.85 to 1.25 of it): the roughness grows with it. */
        double wIn;
        /** Per sheet: the terrace (whole blocks the floor is set up or down), the hanging end's length, and a pillar. */
        final double[] toff = new double[MAX_SHEETS], hang = new double[MAX_SHEETS];
        final boolean[] pillar = new boolean[MAX_SHEETS];
        /** Whether any sheet has a hanging end or a pillar here. */
        boolean feat;
        double fLo, fHi;
        Plans.Gate en;
        double entryD;
        boolean enNear;
        /** Scratch for the cellular search, and its result: {e, F3 - F1}, the nearest site and the one across the nearest boundary. */
        final double[] px = new double[16], pz = new double[16], pd = new double[16];
        final double[] out = new double[2];
        int near1, across;
        int a0, b0;
    }

    /**
     * A thread's caches: the columns (by {@code (x & 31, z & 31)}, each holding the column it was filled for), a scratch
     * column for {@link #gateShift}, and the last gate's shift (warped blocks) and landing edge.
     */
    static final class Caches {
        final Col[] cols = new Col[1024];
        final Col scratch = new Col();
        final double[] site = new double[2], side = new double[1];
        Plans.Gate shiftGate;
        double shiftU, shiftV, edge;

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
        int across = i1;
        for (int m = 0; m < 16; m++) {
            if (m == i1) continue;
            double dd = c.pd[m] - d1;
            double qx = c.px[m] - x1, qz = c.pz[m] - z1;
            double t = dd / (2 * Math.sqrt(qx * qx + qz * qz));
            if (t < e) { e = t; across = m; }
        }
        c.out[0] = e * S;
        c.out[1] = (Math.sqrt(d3) - Math.sqrt(d1)) * S;
        c.near1 = i1;
        c.across = across;
        c.a0 = a0;
        c.b0 = b0;
    }

    /**
     * The run a column's tunnel on sheet k follows, after {@link #cells}: +1 or -1 for the side of its middle line the
     * column is on (the side of the run's first site, in lattice order), times a value in [-1, 1] that is the same all
     * along the run (a hash of its two sites). {@code side} gets the sign alone.
     */
    double runValue(Col c, int k, double[] side) {
        int ia = c.a0 + c.near1 / 4, ib = c.b0 + c.near1 % 4, ja = c.a0 + c.across / 4, jb = c.b0 + c.across % 4;
        boolean first = ia < ja || (ia == ja && ib < jb);
        long h = first ? Hashing.mix(sheetSeed[k] ^ 0x3C6EF372FE94F82BL, ia, ib, ((long) ja << 32) ^ (jb & 0xFFFFFFFFL))
                : Hashing.mix(sheetSeed[k] ^ 0x3C6EF372FE94F82BL, ja, jb, ((long) ia << 32) ^ (ib & 0xFFFFFFFFL));
        side[0] = first ? 1 : -1;
        return Hashing.unit(h) * 2 - 1;
    }

    /** The floor level of sheet k at a column before dips and the gate (blocks): its level, undulation and ramps. */
    double sheetFloor(int k, double x, double z) { return sheetFloor(k, x, z, 1); }

    /** {@link #sheetFloor}, with the rise to the next sheet scaled by {@code up} (the lowest sheet stays down where it opens). */
    double sheetFloor(int k, double x, double z, double up) { return sheetFloor(k, x, z, up, undulation(k, x, z)); }

    /** The undulation of sheet k's floor (blocks): its relief, two octaves. */
    double undulation(int k, double x, double z) {
        return FLOOR_AMP * floorN.noise2(x / FLOOR_SCALE + 31.7 * k, z / FLOOR_SCALE - 17.3 * k) / 0.75
                + FLOOR2_AMP * floorN.noise2(x / FLOOR2_SCALE - 7.1 * k, z / FLOOR2_SCALE + 3.3 * k) / 0.75;
    }

    private double sheetFloor(int k, double x, double z, double up, double und) {
        double f = W + SHEET0 + step * k + und;
        double m = rampN.noise2(x / RAMP_SCALE + 51.3 * k, z / RAMP_SCALE - 23.9 * k);
        if (k < sheets - 1) f += step * up * clamp01((m - RAMP_LO) / (RAMP_HI - RAMP_LO));
        if (k > 0) f -= step * clamp01((-m - RAMP_LO) / (RAMP_HI - RAMP_LO));
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
        // the basalt column this column belongs to: its offset, and its site for the terraces
        long cell = columnCell(x, z, k0.site);
        c.shift = COLUMN * (Hashing.unit(Hashing.mix(cell, 2, 0, 0)) * 2 - 1) * (1 - c.ash);
        // gate 3's lower landing in this cell (its centre keeps 92 blocks inside the cell, past PULL_MAX)
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
            boolean topSheet = k == sheets - 1;
            if (topSheet && c.entryD < SHIFT_R1) {
                // the top sheet's pattern slides so one of its tunnels' lines runs through the landing's centre
                gateShift(en, k0);
                double sft = Terrain.smooth((SHIFT_R1 - c.entryD) / (SHIFT_R1 - SHIFT_R0));
                cells(c, k, u + k0.shiftU * sft, v + k0.shiftV * sft);
            } else cells(c, k, u, v);
            double e = c.out[0], j = c.out[1];
            c.e[k] = e;
            double cave = clamp01((caveN.noise2(x / CAVE_SCALE + 13.1 * k, z / CAVE_SCALE - 29.7 * k) + (k == 0 ? 0.05 : 0) - CAVE_LO) / (CAVE_HI - CAVE_LO));
            cave *= CAVE_RUN + (1 - CAVE_RUN) * Terrain.smooth(1 - j / CAVE_J);
            double und = undulation(k, x, z);
            double f = sheetFloor(k, x, z, k == 0 ? 1 - hole : 1, und);
            // the lowest sheet's dips: only where it lies at its own level (not up a ramp)
            double low = k == 0 ? clamp01((W + 10 - f) / 4) : 0;
            // lava stays out of the junctions, so every pool has dry ground round its ends and the banks on either
            // side of it join there (lava along every edge round a cell would otherwise leave each bank an island)
            double lw = lava * low * Terrain.smooth((j - LAVA_J) / LAVA_JF), hw = hole * low;
            // lava pools lie along caves; openings at the middle of rooms round junctions
            double hr = hw * Terrain.smooth(1 - j / HOLE_ROOM), hd = hw * clamp01((HOLE_R - j) / HOLE_FADE) * c.under;
            c.lavaW = Math.max(c.lavaW, lw);
            cave = Math.max(cave, Math.max(lw * 0.9, hr));
            cave = Math.max(cave, c.ash * ASH_CAVE);
            double cs = Terrain.smooth(cave);
            double jr = Math.max(0, 1 - j / JUNC_R);
            jr *= jr;
            double w = W0 + W_SWING * (k % 2 == 0 ? wh : wh2) + CAVE_W * cs + JUNC_W * jr + HOLE_W * hr;
            double h = H0 + H_SWING * (k % 2 == 0 ? wh2 : wh) + CAVE_H * cs + JUNC_H * jr;
            if (topSheet && c.entryD < PULL_MAX) {
                // the level (base and ramps) goes to the landing's floor, the relief stays but at the landing itself;
                // a deep gap gets a longer fade, so the floor never climbs more than PULL_SLOPE a block for the pull
                double level = f - und, gap = en.lFloor - level;
                double r1 = Math.max(PULL_R, Math.min(PULL_MAX, PULL_FULL + Math.abs(gap) / PULL_SLOPE));
                double p = clamp01((r1 - c.entryD) / (r1 - PULL_FULL));
                f = level + gap * p + und * clamp01((c.entryD - UND_R0) / UND_FADE);
            }
            // the vault stays under the layer's seal
            h = Math.min(h, top - Terrain.SEAL - 1 - f);
            double fl = f, dip = 0;
            if (hd > 0) {
                // an opening: the room's floor falls through into the Galleries, with a ledge round it
                fl = f + (Math.min(f, bot - 4) - f) * hd;
                c.holeDip = Math.max(c.holeDip, hd);
                dip = hd;
            } else if (lw > 0) {
                // a lava pool along a wide place: off the tunnel's line by the same share all along its run, swelling
                // and narrowing, its outline ragged; it may reach one wall, never both (the far bank stays)
                double[] side = k0.side;
                double off = LAVA_OFF * w * runValue(c, k, side), es = side[0] * e;
                double sw = lavaWN.noise2(x / LAVA_SWING_SCALE, z / LAVA_SWING_SCALE) / 0.6;
                sw = sw < -1 ? -1 : (sw > 1 ? 1 : sw);
                double half = w * (LAVA_HALF + LAVA_SWING * sw) + LAVA_EDGE * lavaEN.noise2(x / LAVA_EDGE_SCALE, z / LAVA_EDGE_SCALE) / 0.75;
                half = Math.min(half, w + Math.abs(off) - LAVA_BANK);
                double dw = lw * clamp01((half - Math.abs(es - off)) / (0.25 * w));
                fl = f + (Math.min(f, W - 3) - f) * dw;
                c.lavaDip = Math.max(c.lavaDip, dw);
                dip = dw;
            }
            // a cave's floor rises and falls (not on the dips, nor at the landing); the lowest sheet's never sinks under
            // the lava table's guard row
            double bw = Terrain.smooth((w - 6) / 4) * (1 - c.ash) * (1 - dip);
            if (topSheet && c.entryD < PULL_MAX) bw *= clamp01((c.entryD - UND_R0) / UND_FADE);
            if (bw > 0) {
                double b = BUMP * bw * bumpN.noise2(x / BUMP_SCALE + 7.7 * k, z / BUMP_SCALE - 5.3 * k) / 0.75;
                if (k == 0) b = Math.max(b, Math.min(0, W + 2 - fl));
                fl += b;
            }
            c.f[k] = f;
            c.fl[k] = fl;
            c.w[k] = w;
            c.h[k] = h;
            c.near[k] = e < w * 1.25 + 1.5 && h > 0;
        }
        double hmax = 0, wIn = 0;
        for (int k = 0; k < sheets; k++) {
            if (!c.near[k]) continue;
            if (c.e[k] < c.w[k]) hmax = Math.max(hmax, c.h[k]);
            wIn = Math.max(wIn, c.w[k] * clamp01((1.25 * c.w[k] - c.e[k]) / (0.4 * c.w[k])));
        }
        c.roughK = (hmax > 0 ? clamp01((hmax - 2) / 8) * 0.65 + 0.35 : 1) * (1 - 0.6 * c.ash);
        c.wIn = wIn;
        // terraces, hanging column ends and pillars in the caves: none on the lowest sheet's pools and openings, none
        // round the landing or in the passage
        boolean clear = c.entryD < LAND_CLEAR || c.enNear;
        double terrace = TERRACE * terN.noise2(k0.site[0] / TERRACE_SCALE, k0.site[1] / TERRACE_SCALE);
        double hv = Hashing.unit(Hashing.mix(cell, 3, 0, 0)), hp = Hashing.unit(Hashing.mix(cell, 4, 0, 0));
        double cluster = Terrain.smooth((clusterN.noise2(x / HANG_SCALE, z / HANG_SCALE) / 0.75 + 0.1) / 0.4);
        c.feat = false;
        for (int k = 0; k < sheets; k++) {
            double cw = Terrain.smooth((c.w[k] - 6) / 4) * (1 - c.ash);
            boolean dip = k == 0 && (c.lavaDip > 0 || c.holeDip > 0);
            double t = dip || clear ? 0 : Math.round(terrace * cw);
            // the lowest sheet's terraces never step down under the lava table's guard row (no new lava, no pits)
            if (k == 0 && t < 0) t = Math.min(0, Math.max(t, Math.ceil(W + 2 - c.fl[k])));
            c.toff[k] = t < -TERRACE_MAX ? -TERRACE_MAX : (t > TERRACE_MAX ? TERRACE_MAX : t);
            c.hang[k] = 0;
            c.pillar[k] = false;
            if (c.near[k]) {
                c.fLo = Math.min(c.fLo, c.fl[k] + Math.min(0, c.toff[k]) - 5);
                c.fHi = Math.max(c.fHi, c.f[k] + c.h[k] + 2);
            }
            if (!c.near[k] || dip || clear || c.h[k] < HANG_H || cw <= 0) continue;
            c.hang[k] = Math.round(Math.min(HANG_MAX, c.h[k] - 8) * hv * cluster * cw);
            c.pillar[k] = cw > 0.5 && hp > PILLAR && c.e[k] > PILLAR_E && c.e[k] < 0.8 * c.w[k];
            if (c.hang[k] > 0 || c.pillar[k]) c.feat = true;
        }
        if (c.entryD < en.landR + 6) {
            c.fLo = Math.min(c.fLo, en.lFloor - 9);
            c.fHi = Math.max(c.fHi, en.lCy + en.landHalf + 4);
        }
    }

    /**
     * The shift (warped blocks) that brings the top sheet's nearest tunnel line to gate 3's lower landing's centre: from
     * the centre's warped point straight to the nearest boundary of its cell, so its length is the landing's distance from
     * the line ({@link #landingEdge}). A function of the gate alone; the thread keeps the last gate's.
     */
    void gateShift(Plans.Gate en, Caches k) {
        if (k.shiftGate == en) return;
        Col c = k.scratch;
        int x = (int) Math.floor(en.lx), z = (int) Math.floor(en.lz);
        double wx = WARP1 * warpA.noise2(x / WARP1_SCALE, z / WARP1_SCALE) + WARP2 * warpC.noise2(x / WARP2_SCALE, z / WARP2_SCALE);
        double wz = WARP1 * warpB.noise2(x / WARP1_SCALE, z / WARP1_SCALE) + WARP2 * warpD.noise2(x / WARP2_SCALE, z / WARP2_SCALE);
        int t = sheets - 1;
        cells(c, t, x + wx, z + wz);
        double S = SPACING[t];
        double qx = c.px[c.across] - c.px[c.near1], qz = c.pz[c.across] - c.pz[c.near1], ql = Math.sqrt(qx * qx + qz * qz);
        double el = c.out[0] / S, da = el * qx / ql, db = el * qz / ql;
        k.shiftU = S * (cos[t] * da + sin[t] * db);
        k.shiftV = S * (-sin[t] * da + cos[t] * db);
        k.edge = c.out[0];
        k.shiftGate = en;
    }

    /** How far gate 3's lower landing lies from the top sheet's nearest tunnel line before the shift (blocks). */
    double landingEdge(Plans.Gate en, Caches k) {
        gateShift(en, k);
        return k.edge;
    }

    /** {@link #landingEdge} on this thread's caches. */
    double landingEdge(Plans.Gate en) { return landingEdge(en, cache.get()); }

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
        return Hashing.unit(Hashing.mix(columnCell(x, z, new double[2]), 2, 0, 0)) * 2 - 1;
    }

    /** The small Voronoi cell (one basalt column) holding (x, z): its id, and its site into {@code site}. */
    long columnCell(double x, double z, double[] site) {
        double size = COLUMN_CELL;
        int gx = (int) Math.floor(x / size), gz = (int) Math.floor(z / size);
        double best = 1e9;
        long bh = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long hh = Hashing.mix(seed, 8800, gx + dx, gz + dz);
            double px = (gx + dx + 0.15 + 0.7 * Hashing.unit(hh)) * size, pz = (gz + dz + 0.15 + 0.7 * Hashing.unit(Hashing.mix(hh, 1, 0, 0))) * size;
            double d = (x - px) * (x - px) + (z - pz) * (z - pz);
            if (d < best) { best = d; bh = hh; site[0] = px; site[1] = pz; }
        }
        return bh;
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

    /** The core's inset in a vault of height h: deeper in tall caves, so their relief survives the per-block pass. */
    static double coreIn(double h) { return CORE_IN + CORE_DEEP * Math.max(0, h - CORE_H); }

    /** Sheet k's analytic floor at a column (the vault's floor at its e, before terraces). */
    static double floorOf(Col c, int k) {
        double q = c.e[k] / c.w[k];
        return c.fl[k] + (q > 0.35 ? (q - 0.35) * (q - 0.35) * c.w[k] * 0.9 : 0);
    }

    /** Sheet k's analytic ceiling at a column. */
    static double ceilOf(Col c, int k) {
        double q = c.e[k] / c.w[k];
        return c.f[k] + c.h[k] * Math.sqrt(Math.max(0, 1 - q * q));
    }

    /** The walking floor sheet k's vault gives a column: its floor with the terrace, but the plain floor on an opening's dip. */
    static double walkFloor(Col c, int k) { return k == 0 && c.holeDip > 0 ? c.fl[k] : c.fl[k] + c.toff[k]; }

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
        // rougher the wider the vault: the two octaves grow with it, and a broad third joins them in the caves
        double A = ROUGH + ROUGH_W * Math.max(0, c.wIn - W0), lim = 12 + 2 * A;
        if (d > -lim && d < lim) {
            double r = (A / ROUGH) * rough(x, y, z);
            double broad = A * Terrain.smooth((c.wIn - 6) / 4);
            if (broad > 0) r += broad * rough3.noise3(x / ROUGH3_SCALE, y / (ROUGH3_SCALE * 0.75), z / ROUGH3_SCALE);
            d += r * c.roughK;
        }
        if (top - y < Terrain.SEAL) d = Math.max(d, Terrain.SEAL - (top - y));
        // the Wasteland reaches at most a few rows into the Galleries (its dips), and is rock under that
        if (y < bot - 8) d = Math.max(d, (bot - 8) - y);
        Plans.Gate en = c.en;
        if (c.enNear && y > en.y1 - 3) d = Math.min(d, base.network.passage(en, x, y, z));
        return d;
    }

    /**
     * The per-block pass after interpolation ({@code c8} is the interpolated density in blocks; so is the result): the
     * basalt columns' faces and the caves' terraces, a guard of rock under every floor (so no hole opens through one), the
     * vaults' cores (shrunk in tall caves), the passage from band 3, then the caves' hanging column ends and pillars.
     * Outside the column's span only the columns' offset acts.
     */
    double fineBlocks(int x, int y, int z, double c8) {
        Col c = column(x, z);
        boolean span = y >= c.fLo && y <= c.fHi, floorNear = false;
        int terr = -1;
        if (span) {
            for (int k = 0; k < sheets; k++) {
                if (!c.near[k] || c.e[k] >= c.w[k]) continue;
                if (Math.abs(y - walkFloor(c, k)) < 2.5) floorNear = true;
                if (terr < 0 && c.toff[k] != 0 && Math.abs(y - floorOf(c, k)) < TERRACE_MAX + 0.5 && y < (floorOf(c, k) + ceilOf(c, k)) / 2) terr = k;
            }
        }
        if (c8 > -3 && c8 < 3) c8 += c.shift * (floorNear ? FLOOR_FADE : 1);
        // a terrace sets a whole column's floor up or down
        if (terr >= 0 && c8 > -TERRACE_MAX - 1 && c8 < TERRACE_MAX + 1) c8 += c.toff[terr];
        Plans.Gate en = c.en;
        boolean pass = c.enNear && y > en.y1 - 3;
        if (!span && !pass) return c8;
        double keep = -Terrain.SOLID, open = Terrain.SOLID;
        for (int k = 0; k < sheets; k++) {
            if (!c.near[k]) continue;
            double e = c.e[k], f = c.f[k], w = c.w[k], h = c.h[k], fb = walkFloor(c, k);
            if (y < fb - 5 || y > f + h + 2 || e >= w * 1.1) continue;
            if (fb > bot + 4) {
                // the floor guard: rock from the floor (a tunnel's) or a block under it (a cave's) to 4 under it
                double free = clamp01((w - GUARD_W0) / (GUARD_W1 - GUARD_W0));
                keep = Math.max(keep, Math.min(fb - free - y, y - (fb - 4)));
                // at gate 3's landing the top sheet's floor is whole up to its level, so the landing never cuts off
                if (k == sheets - 1 && c.entryD < LAND_CLEAR) keep = Math.max(keep, Math.min(fb - y, y - (fb - 3)));
            }
            open = Math.min(open, vault(e, y, fb, f, w, h) + (k == 0 && c.holeDip > 0 ? CORE_IN : coreIn(h)));
        }
        if (pass) open = Math.min(open, base.network.passage(en, x, y, z));
        if (keep <= 0) keep = -Terrain.SOLID;
        if (open > 0) open = Terrain.SOLID;
        double res = Math.min(Math.max(c8, keep), open);
        return c.feat && span && res <= 0 ? columns(c, y, res) : res;
    }

    /** A cave's hanging column ends and pillars: whole basalt columns, under the vault's analytic ceiling. */
    private double columns(Col c, int y, double res) {
        for (int k = 0; k < sheets; k++) {
            if (!c.near[k] || (c.hang[k] <= 0 && !c.pillar[k]) || c.e[k] >= c.w[k]) continue;
            double ceil = ceilOf(c, k), floor = floorOf(c, k) + c.toff[k];
            if (y < floor - 2 || y > ceil + 3) continue;
            if (c.pillar[k] || y >= ceil - c.hang[k] - 0.5) return 1;
        }
        return res;
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
            double fb = walkFloor(c, k);
            if (y < fb - 5 || y > c.f[k] + c.h[k] + 2 || c.e[k] >= c.w[k] * 1.1) continue;
            open = Math.min(open, vault(c.e[k], y, fb, c.f[k], c.w[k], c.h[k]) + (k == 0 && c.holeDip > 0 ? CORE_IN : coreIn(c.h[k])));
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

    /**
     * Whether an opening's dip ({@link #openingRim}) reaches any column within {@code r} blocks of (x, z) each way (a
     * square, as the lava rule measures "within 3 columns"): decorations keep their lava that far from the holes into the
     * Galleries.
     */
    public boolean openingNear(int x, int z, int r) {
        if (!joinOn) return false;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            // a dip needs beta over HOLE_BETA, and beta is cheap to ask
            if (breakthrough.beta(x + dx, z + dz) <= Join.HOLE_BETA) continue;
            if (column(x + dx, z + dz).holeDip > 0) return true;
        }
        return false;
    }

    /**
     * Whether a structure over rows lo..hi of column (x, z) would stand in a tunnel's floor channel: within 1 + margin
     * blocks of the middle line of a sheet's tunnel (not where its floor falls through into the Galleries), in rows from
     * {@link #WALK_BELOW} under its walking floor to {@link #WALK_ABOVE} over it. The walk guard (Walkway) keeps the
     * decorations out of it, so they never wall a tunnel off or land on its floor.
     */
    public boolean walkNear(int x, int z, int lo, int hi, double margin) {
        Col c = column(x, z);
        for (int k = 0; k < sheets; k++) {
            if (!c.near[k] || c.e[k] >= 1.0 + margin || c.fl[k] <= bot + 4) continue;
            int fb = (int) Math.floor(walkFloor(c, k));
            if (hi >= fb - WALK_BELOW && lo <= fb + WALK_ABOVE) return true;
        }
        return false;
    }

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
                    p.stands.add(new double[]{px + 0.5, Math.ceil(walkFloor(c, k)), pz + 0.5});
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
            out.add(String.format(java.util.Locale.ROOT, "  sheet %d: %.1f from its line, floor %.1f (%.1f here, terrace %+.0f), half-width %.1f, vault %.1f%s%s%s", k, c.e[k], c.f[k], c.fl[k],
                    c.toff[k], c.w[k], c.h[k], c.hang[k] > 0 ? String.format(java.util.Locale.ROOT, ", hanging end %.0f", c.hang[k]) : "", c.pillar[k] ? ", pillar" : "",
                    c.near[k] ? "" : ", too far"));
        int s = sheetAt(x, y, z);
        out.add(s >= 0 ? "  in sheet " + s + "'s vault" : "  in rock");
        return out;
    }
}
