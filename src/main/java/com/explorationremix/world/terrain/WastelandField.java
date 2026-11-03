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
 * tunnels runs under the band 3 passage's foot, its floor level is pulled to the landing's floor (its relief kept), and
 * its cave noise is raised a little, so the passage opens into an ordinary tunnel crossing or medium cave. The end of the
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
     * Terraces: in caves (half-width from 6, fully at 10) the floor of each basalt column is set up or down by a whole
     * number of blocks, TERRACE times a noise read at the column's site (scale TERRACE_SCALE), so neighbouring columns
     * step together; at most TERRACE_MAX either way.
     */
    static final double TERRACE = 7, TERRACE_SCALE = 22, TERRACE_MAX = 4;
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
     * out from UND_R0 + UND_FADE to UND_R0. The sheet's pattern shifts so a tunnel's line runs through the landing's
     * centre (fully within SHIFT_R0, none from SHIFT_R1), and its cave noise is at least LAND_CAVE within LAND_R
     * (fading over LAND_FADE).
     */
    static final double PULL_R = 80, PULL_FULL = 40, PULL_MAX = 92, PULL_SLOPE = 0.65;
    static final double SHIFT_R0 = 20, SHIFT_R1 = 90, UND_R0 = 6, UND_FADE = 20, LAND_R = 30, LAND_FADE = 10, LAND_CAVE = 0.5;
    /** Terraces, hanging ends and pillars keep this far (blocks) from the landing's centre, and out of the passage. */
    static final double LAND_CLEAR = 40;
    /**
     * The crater round a hole the Galleries open with a ramp ({@link Join.Lower#craterRing}): out to CR_FLAT blocks past the
     * hole's edge (the ramp's head and a block beyond) the lowest sheet's vault is as on its tunnel's middle line, so its
     * floor is level and whole round the lip; the distance it reads from the line grows CR_FLAT_G blocks a block beyond.
     */
    static final double CR_FLAT = GalleriesField.LIP + 1, CR_FLAT_G = 2;
    /** The crater's depth fades out over the last CR_FADE blocks of its reach (it is 0 there in the default layout anyway). */
    static final double CR_FADE = 6;
    /** The share of a crater's fall its vault's crown keeps (the crown comes down by the rest). */
    static final double CR_CEIL = 0.5;
    /** Lava pools keep CR_DRY_GAP blocks of rock from where a crater may go under the lava table, fading in over 3 more. */
    static final double CR_DRY_GAP = 4;
    /** The decorations' walk guard ({@link #walkNear}): rows from this far under a tunnel's floor to this far over it. */
    static final int WALK_BELOW = 3, WALK_ABOVE = 6;

    final Terrain base;
    final Shape shape;
    final long seed;
    final int li = Terrain.WASTELAND;
    /** The layer's top and bottom rows, and its lava table (the first row above the lava). */
    final int top, bot, W;
    private final Perlin caveN, whN, lavaN, ashN, rough1, rough2, rough3, terN, clusterN, lavaWN, lavaEN;
    /** The sheets' warp, lattices, floors and the lowest sheet's opening weight, shared with the Galleries' join. */
    final WastelandSheets sh;
    /**
     * The sheets this layer has: the top one's level sits 4 to 30 blocks under gate 3's lower landing floor, which its
     * floor rises to near the landing ({@link #PULL_R}).
     */
    final int sheets;
    /** The rise from one sheet to the next: {@link #STEP}, or more where the sheets run out under a high landing. */
    final double step;
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
     * Blocks from a ramped hole's edge out to which a crater may go under the lava table's guard row (W + 2), plus
     * {@link #CR_DRY_GAP}: no lava pool comes nearer, so the craters' open space under the table (air) never meets lava.
     */
    final double crDry;

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
        this.crDry = GalleriesField.CR_IN + (W + 2 - (gTop - GalleriesField.CR_DROP)) / GalleriesField.CR_S + CR_DRY_GAP;
        // the sheets (how many, their rise, lattices and floors) are the shared helper's, built before both fields
        this.sh = base.sheets;
        sheets = sh.sheets;
        step = sh.step;
        long s = seed ^ 0x510E527FADE682D1L;
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
        cache = ThreadLocal.withInitial(Caches::new);
    }

    // ================================================================================================ columns
    /** What a column needs that does not depend on y, per sheet and for the column. */
    static final class Col extends WastelandSheets.Cells {
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
        /**
         * Blocks to the edge of the nearest hole the Galleries open with a ramp (+inf: none near), and how far the crater
         * round it lowers the lowest sheet's floor here (blocks; 0: not at all).
         */
        double ring, crater;
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
    }

    /**
     * A thread's caches: the columns (by {@code (x & 31, z & 31)}, each holding the column it was filled for), a scratch
     * column for {@link #gateShift}, and the last gate's shift (warped blocks) and landing edge.
     */
    static final class Caches {
        final Col[] cols = new Col[1024];
        final Col scratch = new Col();
        final double[] site = new double[2], side = new double[1], shift = new double[3];
        Plans.Gate shiftGate;
        double shiftU, shiftV, edge;
        /** Nested column fills in progress (counted only under assertions). */
        int depth;

        Caches() {
            for (int i = 0; i < cols.length; i++) cols[i] = new Col();
        }
    }

    Col column(int x, int z) {
        Caches k = cache.get();
        Col c = k.cols[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        // a column is computed from the fields built before this one only: nothing it reads may call back into this
        // cache (the slot is marked unfilled first, and under the tests' assertions a nested fill fails)
        c.valid = false;
        c.x = x;
        c.z = z;
        assert k.depth++ == 0 : "WastelandField.column re-entered at " + x + ", " + z;
        try {
            compute(c, k);
        } finally {
            assert k.depth-- == 1;
        }
        c.valid = true;
        return c;
    }

    private static int floor(double v) { int i = (int) v; return v < i ? i - 1 : i; }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** Sheet k's cellular pattern at the warped point (u, v), into {@code c} ({@link WastelandSheets#cells}). */
    void cells(Col c, int k, double u, double v) { sh.cells(c, k, u, v); }

    /**
     * The run a column's tunnel on sheet k follows, after {@link #cells}: +1 or -1 for the side of its middle line the
     * column is on (the side of the run's first site, in lattice order), times a value in [-1, 1] that is the same all
     * along the run (a hash of its two sites). {@code side} gets the sign alone.
     */
    double runValue(Col c, int k, double[] side) {
        int ia = c.a0 + c.near1 / 4, ib = c.b0 + c.near1 % 4, ja = c.a0 + c.across / 4, jb = c.b0 + c.across % 4;
        boolean first = ia < ja || (ia == ja && ib < jb);
        long h = first ? Hashing.mix(sh.sheetSeed(k) ^ 0x3C6EF372FE94F82BL, ia, ib, ((long) ja << 32) ^ (jb & 0xFFFFFFFFL))
                : Hashing.mix(sh.sheetSeed(k) ^ 0x3C6EF372FE94F82BL, ja, jb, ((long) ia << 32) ^ (ib & 0xFFFFFFFFL));
        side[0] = first ? 1 : -1;
        return Hashing.unit(h) * 2 - 1;
    }

    /** The floor level of sheet k at a column before dips and the gate (blocks): its level, undulation and ramps. */
    double sheetFloor(int k, double x, double z) { return sheetFloor(k, x, z, 1); }

    /** {@link #sheetFloor}, with the rise to the next sheet scaled by {@code up} (the lowest sheet stays down where it opens). */
    double sheetFloor(int k, double x, double z, double up) { return sh.sheetFloor(k, x, z, up, sh.undulation(k, x, z)); }

    /** The undulation of sheet k's floor (blocks): its relief, two octaves. */
    double undulation(int k, double x, double z) { return sh.undulation(k, x, z); }

    private void compute(Col c, Caches k0) {
        int x = c.x, z = c.z;
        double u = x + sh.warpX(x, z), v = z + sh.warpZ(x, z);
        c.beta = joinOn ? breakthrough.beta(x, z) : 0;
        c.ring = joinOn ? lower.craterRing(x, z) : Double.POSITIVE_INFINITY;
        c.ash = ash(x, z);
        double lava = clamp01((lavaN.noise2(x / LAVA_SCALE, z / LAVA_SCALE) - LAVA_LO) / (LAVA_HI - LAVA_LO));
        // lava only far from any opening, and none in the refuge
        lava *= (1 - clamp01(c.beta / Join.LAVA_BETA)) * (1 - c.ash);
        // and none within reach of a crater's open space under the table (crDry), so its air never meets lava
        if (c.ring < crDry + 3) lava *= clamp01((c.ring - crDry) / 3);
        double hole = WastelandSheets.hole(c.beta);
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
        c.entryD = StrictMath.hypot(x - en.lx, z - en.lz);
        c.enNear = en.overlaps(x, z, x, z, 8);
        c.fLo = 1e9;
        c.fHi = -1e9;
        c.lavaDip = 0;
        c.holeDip = 0;
        c.lavaW = 0;
        c.crater = 0;
        // the crater's floor here (the cone round the nearest hole with a ramp), and its weight (it fades out at its reach)
        double crFloor = GalleriesField.craterFloor(gTop, c.ring);
        double crW = crFloor < Double.POSITIVE_INFINITY ? Terrain.smooth((GalleriesField.CR_IN + GalleriesField.CR_REACH - c.ring) / CR_FADE) : 0;
        for (int k = 0; k < sheets; k++) {
            boolean topSheet = k == sheets - 1;
            if (topSheet && c.entryD < SHIFT_R1) {
                // the top sheet's pattern slides so one of its tunnels' lines runs through the landing's centre
                gateShift(en, k0);
                double sft = WastelandSheets.shiftShare(c.entryD);
                cells(c, k, u + k0.shiftU * sft, v + k0.shiftV * sft);
            } else cells(c, k, u, v);
            double e = c.out[0], j = c.out[1];
            double cave = clamp01((caveN.noise2(x / CAVE_SCALE + 13.1 * k, z / CAVE_SCALE - 29.7 * k) + (k == 0 ? 0.05 : 0) - CAVE_LO) / (CAVE_HI - CAVE_LO));
            cave *= CAVE_RUN + (1 - CAVE_RUN) * Terrain.smooth(1 - j / CAVE_J);
            double und = sh.undulation(k, x, z);
            double f = sh.sheetFloor(k, x, z, k == 0 ? 1 - hole : 1, und);
            // the lowest sheet's dips: only where it lies at its own level (not up a ramp)
            double low = k == 0 ? sh.low(f) : 0;
            // lava stays out of the junctions, so every pool has dry ground round its ends and the banks on either
            // side of it join there (lava along every edge round a cell would otherwise leave each bank an island)
            double lw = lava * low * Terrain.smooth((j - LAVA_J) / LAVA_JF), hw = hole * low;
            // lava pools lie along caves; openings at the middle of rooms round junctions
            // the dip's weight is the shared opening site (the Galleries open under it) times the guard
            double hr = hw * Terrain.smooth(1 - j / HOLE_ROOM), hd = WastelandSheets.dipWeight(hole, low, j) * c.under;
            c.lavaW = Math.max(c.lavaW, lw);
            cave = Math.max(cave, Math.max(lw * 0.9, hr));
            cave = Math.max(cave, c.ash * ASH_CAVE);
            // the passage from band 3 comes down into a medium cave at most, of the field's own profile
            if (topSheet && c.entryD < LAND_R) cave = Math.max(cave, LAND_CAVE * clamp01((LAND_R - c.entryD) / LAND_FADE));
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
            if (k == 0 && crW > 0) {
                // the crater (after the dip's weight, which is the Galleries' opening site): the lowest sheet's level goes
                // down to the cone round the hole, its ramps going down with it, and they fade out toward the hole (all of
                // them at the lip, none at the crater's reach), so the floor round the lip is the cone's and nothing steps;
                // round the lip the vault is level and whole, so the ramp's head always lies in it
                double fn = f - Math.max(0, sh.sheetFloor(0, x, z, 0, und) - crFloor) * crW;
                fn -= Terrain.smooth((GalleriesField.CR_IN + GalleriesField.CR_REACH - c.ring) / (GalleriesField.CR_IN + GalleriesField.CR_REACH - CR_FLAT))
                        * Math.max(0, fn - crFloor);
                if (fn < f) {
                    c.crater = f - fn;
                    f = fn;
                    e = Math.min(e, CR_FLAT_G * Math.max(0, c.ring - CR_FLAT));
                    // the vault's crown comes down by only part of the floor's fall: the crater is a funnel under the
                    // tunnel's own roof, and its roof stays over the join line (a low crown there would be a flat lid)
                    h += CR_CEIL * c.crater;
                }
            }
            c.e[k] = e;
            // the vault stays under the layer's seal
            h = Math.min(h, top - Terrain.SEAL - 1 - f);
            double fl = f;
            if (hd > 0) {
                // an opening: the room's floor falls through into the Galleries, with a ledge round it
                fl = f + (Math.min(f, bot - 4) - f) * hd;
                c.holeDip = Math.max(c.holeDip, hd);
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
            boolean dip = k == 0 && (c.lavaDip > 0 || c.holeDip > 0 || c.crater > 0);
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
        sh.landingShift(en.lx, en.lz, k.scratch, k.shift);
        k.shiftU = k.shift[0];
        k.shiftV = k.shift[1];
        k.edge = k.shift[2];
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
    Perlin warpA() { return sh.warpA(); }
    Perlin warpB() { return sh.warpB(); }
    Perlin warpC() { return sh.warpC(); }
    Perlin warpD() { return sh.warpD(); }
    long sheetSeed(int k) { return sh.sheetSeed(k); }
    double offA(int k) { return sh.offA(k); }
    double offB(int k) { return sh.offB(k); }
    double cos(int k) { return sh.cos(k); }
    double sin(int k) { return sh.sin(k); }

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

    /** Sheet k's core inset at a column: CORE_IN on the lowest sheet's opening dips and craters (their floors are walked), else {@link #coreIn}. */
    static double coreOf(Col c, int k) { return k == 0 && (c.holeDip > 0 || c.crater > 0) ? CORE_IN : coreIn(c.h[k]); }

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
            if (fb > bot + 4 || (k == 0 && c.crater > 0)) {
                // the floor guard: rock from 1 to 4 blocks under the floor, so roughness never holes it (a crater's too)
                keep = Math.max(keep, Math.min(fb - 1 - y, y - (fb - 4)));
                // round gate 3's landing the top sheet's floor is whole up to its level, so the landing never cuts off
                if (k == sheets - 1 && c.entryD < PULL_FULL) keep = Math.max(keep, Math.min(fb - y, y - (fb - 3)));
            }
            open = Math.min(open, vault(e, y, fb, f, w, h) + coreOf(c, k));
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
            open = Math.min(open, vault(c.e[k], y, fb, c.f[k], c.w[k], c.h[k]) + coreOf(c, k));
        }
        return open > 0 ? Terrain.SOLID : open;
    }

    // ================================================================================================ fluids and biomes
    /**
     * What fills an open block of the Wasteland: lava under the table, but air where the breakthrough noise is high
     * enough that an opening could be near ({@link Join#AIR_BETA}) and within {@link #crDry} of a hole the Galleries open
     * with a ramp (its crater may go under the table there, and no lava pool comes that near); air above it. The join's
     * seal keeps every open block under the table either far from the openings (lava) or inside them (air), never both
     * side by side.
     */
    public int fluid(int x, int y, int z) {
        if (y >= W) return Terrain.AIR;
        Col c = column(x, z);
        return c.beta >= Join.AIR_BETA || c.ring < crDry ? Terrain.AIR : Terrain.LAVA;
    }

    /**
     * The Ash Hollows where their climate passes halfway, else the Wasteland. Read straight from the climate, the
     * column's own {@link Col#ash}: a biome query never needs the rest of the column (its craters ask the Galleries).
     */
    public DeepBiome biome(int x, int y, int z) {
        return ash(x, z) >= 0.5 ? DeepBiome.ASH_HOLLOWS : DeepBiome.WASTELAND;
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
        // a crater's floor slopes down to its hole, so lava anywhere it lowers the floor would run into the Galleries
        boolean crater = lower.craterRing(x, z) <= GalleriesField.CR_IN + GalleriesField.CR_REACH + 1.5 * r;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (crater && column(x + dx, z + dz).crater > 0) return true;
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
            if (!c.near[k] || c.e[k] >= 1.0 + margin || (c.fl[k] <= bot + 4 && !(k == 0 && c.crater > 0))) continue;
            int fb = (int) Math.floor(walkFloor(c, k));
            if (hi >= fb - WALK_BELOW && lo <= fb + WALK_ABOVE) return true;
        }
        return false;
    }

    /**
     * The Ash Hollows' climate at a column (0..1): pale rock blends in from 0, the biome is theirs from 0.5. Its own
     * noise, so it needs nothing else of the column ({@link Col#ash} is this).
     */
    public double ash(int x, int z) {
        return clamp01((ashN.noise2(x / ASH_SCALE, z / ASH_SCALE) - ASH_LO) / (ASH_HI - ASH_LO));
    }

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
        if (c.ring < Double.POSITIVE_INFINITY)
            out.add(String.format(java.util.Locale.ROOT, "  crater: %.1f from a ramped hole's edge, floor lowered %.1f", c.ring, c.crater));
        for (int k = 0; k < sheets; k++)
            out.add(String.format(java.util.Locale.ROOT, "  sheet %d: %.1f from its line, floor %.1f (%.1f here, terrace %+.0f), half-width %.1f, vault %.1f%s%s%s", k, c.e[k], c.f[k], c.fl[k],
                    c.toff[k], c.w[k], c.h[k], c.hang[k] > 0 ? String.format(java.util.Locale.ROOT, ", hanging end %.0f", c.hang[k]) : "", c.pillar[k] ? ", pillar" : "",
                    c.near[k] ? "" : ", too far"));
        int s = sheetAt(x, y, z);
        out.add(s >= 0 ? "  in sheet " + s + "'s vault" : "  in rock");
        return out;
    }
}
