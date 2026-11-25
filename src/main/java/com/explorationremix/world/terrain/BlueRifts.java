package com.explorationremix.world.terrain;

/**
 * The Blue Reaches' noise rifts (0.21.7, Taran's cards: "More rifts, some very long", "A mix that changes along a rift"
 * and shelves "Mixed, 4 to 16 blocks out"): chasms down to the water table along the zero lines of a warped noise
 * field, cut into separate rifts by a second field, each changing along its length from a steep ravine about 14 blocks
 * across, its walls nearly sheer, to a wide stepped chasm up to about 100 across, a quarter shallower, its terraces
 * about seven blocks deep for each ten up. A stream runs along the bottom (the water table, two blocks deep), with
 * shallows and a dry bank either side; the walls climb in terraces to the rim, and a low vault closes over it (as the
 * Deep Blue Rift's own cross-section, {@link Terrain#riftFloor}). Shelves widen some terraces far out into the rift,
 * both here and in the Deep Blue Rift ({@link #shelf}), and ponds sit on the shelves and the broader terraces, each on
 * a platform of its own ({@link NetRifts#pondsCol}).
 *
 * <p>All of it is a pure function of the seed and the column. What a rift keeps clear of (rooms, climbs, landings,
 * gates, geodes, the city) comes in as the network's keep factor ({@link #shape}'s {@code keep}): where it falls the
 * rift narrows, grows shallower and shuts, as it does at its own ends.
 */
final class BlueRifts {
    /** noise2's usual range to about [-1, 1] (as {@link UpperShapes}). */
    static final double K2 = 1.381;
    /** The centre lines' field: its scale, and the warp's reach and scale (blocks). Lines lie about 150 to 250 apart. */
    static final double LINE_SCALE = 280, WARP = 28, WARP_SCALE = 150;
    /**
     * The cut: a rift is open where a broad field is over {@link #CUT}, easing in over {@link #CUT_EASE} of it (about 70
     * blocks along a rift), so rifts are a few hundred blocks long and some run on for a thousand.
     */
    static final double CUT_SCALE = 650, CUT = -0.08, CUT_EASE = 0.3;
    /** The shape's scale along a rift, from ravine to chasm and back. */
    static final double SHAPE_SCALE = 170;
    /**
     * The terraces' run (blocks across for each block up, on average) from a ravine's ({@link #RUN_MIN}: its walls
     * sheer) to a chasm's ({@link #RUN_MAX}: treads about seven blocks deep for each ten-block step).
     */
    static final double RUN_MIN = 0.03, RUN_MAX = 0.7;
    /** The rim's height over the water table: from {@link #TOP_MIN} to {@link #TOP_MAX} (a chasm's a quarter lower). */
    static final double TOP_MIN = 34, TOP_MAX = 84, CHASM_SHALLOW = 0.25;
    /** The widest a rift's half width at its rim grows, and how far from its centre line it can reach (with a margin). */
    static final double HALF_MAX = 52, REACH = HALF_MAX + 6;
    /** The rift shuts where its half width at the rim falls under this. */
    static final double HALF_SHUT = 3.0;
    /** The shelves: their zones' scale along a rift, how far one reaches out (blocks), the levels' scale. */
    static final double SHELF_SCALE = 44, SHELF_OUT_MIN = 4, SHELF_OUT_MAX = 16;

    final Perlin lineN, warpA, warpB, cutN, shapeN, sizeN, topN, stepN, wigN, shelfN, levelN, outN;
    /** The water table, and the highest a rift's vault may reach. */
    final double W, roof;

    BlueRifts(long seed, Shape shape) {
        long s = seed ^ 0x2B992DDFA23249D6L;
        lineN = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpA = new Perlin(Hashing.mix(s, 2, 0, 0));
        warpB = new Perlin(Hashing.mix(s, 3, 0, 0));
        cutN = new Perlin(Hashing.mix(s, 4, 0, 0));
        shapeN = new Perlin(Hashing.mix(s, 5, 0, 0));
        sizeN = new Perlin(Hashing.mix(s, 6, 0, 0));
        topN = new Perlin(Hashing.mix(s, 7, 0, 0));
        stepN = new Perlin(Hashing.mix(s, 8, 0, 0));
        wigN = new Perlin(Hashing.mix(s, 9, 0, 0));
        shelfN = new Perlin(Hashing.mix(s, 10, 0, 0));
        levelN = new Perlin(Hashing.mix(s, 11, 0, 0));
        outN = new Perlin(Hashing.mix(s, 12, 0, 0));
        W = shape.waterY(Terrain.BLUE);
        roof = shape.top(Terrain.BLUE) - 14;
    }

    // ------------------------------------------------------------------------------------------------ the lines

    /**
     * The nearest centre line to a column: out = {distance, unit normal x and z (from the line towards the column),
     * nearest point x and z, side (1 or -1: which side of the line the column lies)}; false when no line is within
     * {@link #REACH}. {@code g} is scratch (2 long).
     */
    boolean line(double x, double z, double[] g, double[] out) {
        double wx = WARP * K2 * warpA.noise2(x / WARP_SCALE, z / WARP_SCALE, g);
        double wxx = WARP * K2 * g[0] / WARP_SCALE, wxz = WARP * K2 * g[1] / WARP_SCALE;
        double wz = WARP * K2 * warpB.noise2(x / WARP_SCALE + 50, z / WARP_SCALE + 50, g);
        double wzx = WARP * K2 * g[0] / WARP_SCALE, wzz = WARP * K2 * g[1] / WARP_SCALE;
        double f = lineN.noise2((x + wx) / LINE_SCALE, (z + wz) / LINE_SCALE, g);
        // the field's slope by x and z of the block (the warp's Jacobian on the field's own gradient)
        double fx = (g[0] * (1 + wxx) + g[1] * wzx) / LINE_SCALE, fz = (g[0] * wxz + g[1] * (1 + wzz)) / LINE_SCALE;
        double gl = Math.hypot(fx, fz);
        double d = Math.abs(f) / Math.max(gl, 0.53 / LINE_SCALE);
        if (d > REACH) return false;
        double side = f >= 0 ? 1 : -1, inv = gl < 1e-12 ? 0 : side / gl;
        out[0] = d;
        out[1] = fx * inv;
        out[2] = fz * inv;
        out[3] = x - out[1] * d;
        out[4] = z - out[2] * d;
        out[5] = side;
        return true;
    }

    // ------------------------------------------------------------------------------------------------ the shape

    /** {@link #shape}'s output, by index. */
    static final int HALF = 0, TOP = 1, VAULT = 2, STREAM = 3, BANK = 4, STEP = 5, OPEN = 6, FORM = 7;

    /**
     * A rift's cross-section where its centre line's nearest point is (qx, qz) and the network keeps it to {@code keep}
     * (1 where nothing is near, 0 where it must shut): out = {half width at the rim, the rim's height, the vault over
     * it, the stream's half width, the bank's width, the terraces' step, how open (0 to 1), how far from a ravine
     * towards a chasm (0 to 1)}; false where it is shut.
     */
    boolean shape(double qx, double qz, double keep, double[] out) {
        double cut = Terrain.smooth((K2 * cutN.noise2(qx / CUT_SCALE + 17, qz / CUT_SCALE - 9) - CUT) / CUT_EASE);
        double o = cut * keep;
        if (o < 0.02) return false;
        // from ravine to chasm and back along a rift, a little more often at either end than between
        double form = Terrain.smooth(0.5 + 0.85 * K2 * shapeN.noise2(qx / SHAPE_SCALE, qz / SHAPE_SCALE));
        double size = 0.88 + 0.12 * K2 * sizeN.noise2(qx / 90.0 + 4, qz / 90.0 - 7);
        double tu = Math.max(0, Math.min(1, 0.5 + 0.6 * K2 * topN.noise2(qx / 130.0 - 3, qz / 130.0 + 11)));
        double vault = 4 + 5 * form;
        double top = W + (TOP_MIN + (TOP_MAX - TOP_MIN) * tu) * (1 - CHASM_SHALLOW * form) * (0.35 + 0.65 * Math.sqrt(o));
        top = Math.min(top, roof - vault);
        // the terraces' run across for their height: sheer in a ravine, broad treads in a chasm (and most of the way
        // there only near it); the whole narrowing where the rift shuts
        double stream = 2.8 + 1.4 * form, bank = 1.0 + 1.5 * form;
        double run = (RUN_MIN + (RUN_MAX - RUN_MIN) * form * form) * size;
        double half = Math.min(HALF_MAX, stream + 1 + bank + (top - W) * run) * Math.pow(o, 0.6);
        if (half < HALF_SHUT) return false;
        out[HALF] = half;
        out[TOP] = top;
        out[VAULT] = vault * Math.min(1, half / 8);
        out[STREAM] = Math.min(stream, 0.42 * half);
        out[BANK] = Math.min(bank, 0.2 * half);
        out[STEP] = 10 + 2.5 * K2 * stepN.noise2(qx / 90.0, qz / 90.0 + 30);
        out[OPEN] = o;
        out[FORM] = form;
        return true;
    }

    /** Where a rift's terraces start: past the stream, the shallows and the bank. */
    static double terraceStart(double[] sh) { return sh[STREAM] + 1 + sh[BANK]; }

    /** The small wander of a wall's line, so no terrace's lip runs dead straight (blocks, at a column). */
    double wiggle(double x, double z) { return 1.6 * K2 * wigN.noise2(x / 14.0, z / 14.0); }

    /**
     * The floor across a rift at {@code v} from its centre line: the stream two blocks under the water table, the
     * shallows one block under, the dry bank at it, and then the terraces up to the rim at the half width.
     */
    static double floor(double v, double W, double[] sh) {
        double st = sh[STREAM];
        if (v < st - 1) return W - 2;
        if (v < st + 1) return W - 1;
        double v0 = terraceStart(sh);
        if (v < v0) return W;
        return rise(v, v0, W, sh[TOP], sh[HALF], sh[STEP]);
    }

    /** The vault over a rift at {@code v} from its centre line. */
    static double ceiling(double v, double[] sh) {
        double q = v / sh[HALF];
        return sh[TOP] + sh[VAULT] * Math.max(0, 1 - q * q);
    }

    /**
     * Terraces from {@code base} at {@code v0} up to {@code top} at {@code half}, of height {@code step} and all as
     * deep, each flat for a little over half its depth and then rising sharply (as {@link Terrain#riftFloor}'s, from
     * any start); past the half width the rock closes.
     */
    static double rise(double v, double v0, double base, double top, double half, double step) {
        double depth = top - base;
        double t = (v - v0) / Math.max(1, half - v0);
        if (t >= 1) return top + 2 + (t - 1) * 40;
        if (t <= 0) return base;
        double g = depth * t;
        double k = Math.floor(g / step), frac = g / step - k;
        double gq = step * (k + Terrain.smooth((frac - 0.55) / 0.45));
        return base + Math.min(gq, depth + 2);
    }

    /** Where {@link #rise} reaches {@code g} over its base: the inverse of its unstepped line. */
    static double riseAt(double g, double v0, double depth, double half) {
        double t = Math.max(0, Math.min(1, g / depth));
        return v0 + t * (Math.max(v0 + 1, half) - v0);
    }

    // ------------------------------------------------------------------------------------------------ shelves

    /**
     * A shelf on one side of a rift: terrace {@code k} (its flat at base + k step) widened by {@code out} blocks
     * towards the centre line, its zone's {@code strength} (0 to 1) along the rift narrowing it to nothing at either
     * end. Out = {strength, k, out}; false for none. {@code u} and {@code w} place the side's zone noise: the nearest
     * centre-line point, or the Deep Blue Rift's arc length and side.
     */
    boolean shelf(double u, double w, int side, double depth, double step, double[] out) {
        int kmax = (int) Math.floor(depth / step) - 1;
        if (kmax < 1) return false;
        double zn = K2 * shelfN.noise2(u / SHELF_SCALE + side * 37.1, w / SHELF_SCALE - side * 11.3);
        double strength = Terrain.smooth((zn + 0.1) / 0.25);
        if (strength <= 0) return false;
        // one level for a whole zone, mostly: a height over the base from a field far broader than the zones', to the
        // nearest terrace (so a rift growing deeper or shallower along its length keeps its shelves where they were)
        double lv = 0.5 + 0.6 * K2 * levelN.noise2(u / 240.0 + side * 13.0, w / 240.0 + side * 5.0);
        double want = 8 + Math.max(0, Math.min(1, lv)) * (TOP_MAX - 24);
        int k = Math.max(1, Math.min(kmax, (int) Math.round(want / step)));
        double ou = 0.5 + 0.55 * K2 * outN.noise2(u / 40.0 + side * 7.0, w / 40.0 - side * 3.0);
        out[0] = strength;
        out[1] = k;
        out[2] = SHELF_OUT_MIN + (SHELF_OUT_MAX - SHELF_OUT_MIN) * Math.max(0, Math.min(1, ou));
        return true;
    }
}
