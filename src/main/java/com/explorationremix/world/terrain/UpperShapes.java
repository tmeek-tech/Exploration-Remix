package com.explorationremix.world.terrain;

/**
 * The Upper Caves grown from noise (0.13, Taran's pick of 2026-11-11: all four characters, layered by depth), carved
 * into the pack's own caves above its old floor in place of the planned entrances ({@link Entrances}) and set pieces
 * ({@link Upper}). Nothing here is placed piece by piece: every shape is a function of world coordinates, and the
 * pack's caves (Tectonic's) stay underneath as the base.
 * <ul>
 *     <li><b>Root warrens</b>, 5 to 45 blocks under the ground everywhere: two rolling sheets of tunnels along the zero
 *     lines of two crossing 2D fields per sheet, so they loop and fork but never end blind. They swell into root
 *     chambers, roots hang from their ceilings under trees, and pools lie in the chambers' floors. Under forests the top
 *     sheet rises to the ground in places (the mouths: rooty under warm forests, snowed in under cold ones), and here and
 *     there it dips down to the lower sheet (the links). Their rock follows the ground: the Root Vaults' under forests,
 *     limestone karst under plains, sandstone under deserts.</li>
 *     <li><b>Rifts</b>, 15 to 90 blocks down: tall cracks along the zero lines of two slow 2D fields, their walls stepping
 *     back in ledges every six blocks, a stream along the floor that steps down in pools with small falls between, and
 *     springs high on the walls whose water falls into it. In places the crack opens to the sky. The Silver Fern Rift's
 *     look.</li>
 *     <li><b>Halls</b>, about 60 to 85 blocks down: wide caverns where a slow field rises over its threshold, floors in
 *     three-block terraces with pools sunk into them, stalactites (icicles under cold ground) and stalagmites; here and
 *     there a chimney rises from the ceiling to a tunnel of the lower warren sheet. The Ivory Root Cavern's look.</li>
 *     <li><b>Sinkholes</b> under plains, sandy hollows under deserts and badlands: ragged pits where a 2D field peaks
 *     beside a tunnel of the lower warren sheet, wider under their rims, dropping into a chamber with a lake at its foot
 *     that opens onto the tunnel.</li>
 * </ul>
 * One slow field leans each region towards rifts or halls. Each shape has its own switch.
 *
 * <p>All of it is a pure function of the seed and the surface ({@link Overhead}): safe on any thread, the same on every
 * call. Everything a column needs that does not depend on y is computed once per column and cached per thread.
 */
public final class UpperShapes {
    public static final int WARRENS = 0, RIFTS = 1, HALLS = 2, SINKHOLES = 3;
    public static final String[] NAMES = {"warrens", "rifts", "halls", "sinkholes"};
    public static final int AIR = 0, WATER = 1;
    /** Signed distance returned where nothing is near. */
    public static final double SOLID = 64;
    /** What a formation block is made of (see {@link #formation}). */
    public static final int ROOT = 1, ICICLE = 2, STALACTITE = 3, STALAGMITE = 4, DRIP = 5;
    /** The biome of a rift or hall (see {@link #piece}). */
    public static final int PIECE_RIFT = 1, PIECE_HALL = 2, PIECE_COLD_HALL = 3;

    /** Scale Java's noise to the spread of the round-1 previews' noise (values 2D and 3D). */
    static final double K2 = 1.381, K3 = 1.143;
    /** Where each warren sheet lies under the ground (its middle), and how far it rolls up and down. */
    static final double[] SHEET_MID = {14, 31}, SHEET_AMP = {9, 13};
    /** No floor, pool or stream below this far over the pack's old floor: clear of the den gates' landings and ramps. */
    static final int FLOOR_CLEARANCE = 56;
    /** The sinkholes' field's scale: about 45 pits a square kilometre over plains and deserts, one every 150 blocks or so. */
    static final double PIT_SCALE = 72;
    private static final int NO_Y = Integer.MIN_VALUE;

    public final long seed;
    public final int packFloor;
    public final Overhead overhead;
    public final boolean warrens, rifts, halls, sinkholes;
    /** Vanilla's worm tunnels (the cave carver) above the old floor: off unless asked for (the canyons stay). */
    public final boolean wormTunnels;
    private final int minFloor;

    private final Perlin[] wA = new Perlin[2], wB = new Perlin[2], wC = new Perlin[2], wD = new Perlin[2];
    private final Perlin mouthN, linkN, rootN, lookN, poolN, chillN;
    private final Perlin rA, rB, rC, rD;
    private final Perlin hA, hB, hC, hD, hE, hS, hK;
    private final Perlin sA, sB;
    private final Perlin leanN, roughN;

    private final ThreadLocal<Col[]> cols = ThreadLocal.withInitial(() -> {
        Col[] a = new Col[1024];
        for (int i = 0; i < a.length; i++) a[i] = new Col();
        return a;
    });
    private final ThreadLocal<double[]> grads = ThreadLocal.withInitial(() -> new double[2]);
    private final ThreadLocal<Cells> cells = ThreadLocal.withInitial(Cells::new);

    public UpperShapes(long seed, int packFloor, Overhead overhead, boolean warrens, boolean rifts, boolean halls, boolean sinkholes, boolean wormTunnels) {
        this.seed = seed;
        this.packFloor = packFloor;
        this.overhead = overhead;
        this.warrens = warrens;
        this.rifts = rifts;
        this.halls = halls;
        this.sinkholes = sinkholes;
        this.wormTunnels = wormTunnels;
        this.minFloor = packFloor + FLOOR_CLEARANCE;
        long s = seed ^ 0x2545F4914F6CDD1DL;
        for (int i = 0; i < 2; i++) {
            wA[i] = noise(s, 10 + 10 * i);
            wB[i] = noise(s, 11 + 10 * i);
            wC[i] = noise(s, 12 + 10 * i);
            wD[i] = noise(s, 13 + 10 * i);
        }
        mouthN = noise(s, 40);
        linkN = noise(s, 41);
        rootN = noise(s, 42);
        lookN = noise(s, 43);
        poolN = noise(s, 44);
        chillN = noise(s, 45);
        rA = noise(s, 50);
        rB = noise(s, 51);
        rC = noise(s, 52);
        rD = noise(s, 53);
        hA = noise(s, 60);
        hB = noise(s, 61);
        hC = noise(s, 62);
        hD = noise(s, 63);
        hE = noise(s, 64);
        hS = noise(s, 65);
        hK = noise(s, 66);
        sA = noise(s, 70);
        sB = noise(s, 71);
        leanN = noise(s, 80);
        roughN = noise(s, 81);
    }

    /** All four shapes, worm tunnels off (tests and tools). */
    public UpperShapes(long seed, int packFloor, Overhead overhead) {
        this(seed, packFloor, overhead, true, true, true, true, false);
    }

    private static Perlin noise(long s, int i) { return new Perlin(Hashing.mix(s, i, 0, 0)); }

    // ================================================================================================ columns

    /** What a column needs that does not depend on y. */
    static final class Col {
        int x, z;
        boolean valid;
        Overhead.Surface s;
        /** The ground followed at block scale, at 16-block scale, and the lowest ground nearby (all continuous). */
        double gF, gC, low;
        boolean wet, cold, forest, plains, desert, red, tropical;
        /** -1 leans to rifts, 1 to halls. */
        double lean;
        /** Open space of any shape lies only between these (padded). */
        double lo, hi;

        // ---- warrens, two sheets
        final boolean[] wOn = new boolean[2];
        final double[] wYc = new double[2], wHh = new double[2], wHw = new double[2], wLine = new double[2], wRoom = new double[2];
        /** Sheet 1 before the cap (the sinkholes' lake level). */
        double yc1Raw, hh1;
        /** The top sheet's roof cap: the column's cap, lifted where a mouth opens. */
        double cap0;
        /**
         * 0..1: how far a mouth lifts the top sheet's roof cap toward 4 over the ground (1 where it opens fully); how far
         * the top sheet dips to the lower one (a link).
         */
        double mouth, link;
        /** The column's hanging root or icicle: kind, sheet, and its span [fBottom, fTop] (block centres). */
        int fKind;
        double fBottom, fTop;
        boolean poolDone;
        /** The y of a pool block sunk under a warren floor, per sheet, or NO_Y. */
        final int[] wPool = new int[2];

        // ---- rifts
        boolean rOn, rSky;
        double rLine, rTop, rScale, rStep;
        int rFloor;
        boolean springDone;
        int springY;

        // ---- halls
        boolean hOn;
        int hFloor;
        double hCeil, hH;
        /** Stalactite: lowest block centre it reaches (NaN for none), its tip block. Stalagmite: its top block. */
        double stalBottom;
        int stalTip, miteTop;
        boolean hPoolDone, hPool;

        // ---- sinkholes
        boolean sOn;
        double sScore, sThick;
        int sLake;
    }

    /** The column's cached answers (package-private for the tests). */
    Col column(int x, int z) {
        Col[] arr = cols.get();
        Col c = arr[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.x = x;
        c.z = z;
        c.valid = true;
        fill(c);
        return c;
    }

    private void fill(Col c) {
        int x = c.x, z = c.z;
        Overhead.Surface s = overhead.at(x, z);
        c.s = s;
        c.gF = fineGround(x, z);
        c.gC = coarseGround(x, z);
        c.low = lowGround(x, z);
        c.wet = s.wet();
        c.cold = s.cold();
        c.forest = s.cover() == Overhead.Cover.FOREST;
        c.plains = s.cover() == Overhead.Cover.PLAINS;
        c.red = s.cover() == Overhead.Cover.BADLANDS;
        c.desert = s.cover() == Overhead.Cover.DESERT || c.red;
        c.tropical = c.forest && s.trees().tropical();
        c.lean = clamp(1.2 * K2 * leanN.noise2(x / 600.0, z / 600.0), -1, 1);
        c.lo = 1e9;
        c.hi = -1e9;
        c.fKind = 0;
        c.poolDone = false;
        c.springDone = false;
        c.hPoolDone = false;
        c.stalBottom = Double.NaN;
        c.miteTop = NO_Y;
        warrenColumn(c);
        c.rOn = rifts && riftColumn(c);
        c.hOn = halls && hallColumn(c);
        c.sOn = sinkholes && sinkColumn(c);
        c.lo -= 6;
        c.hi += 6;
    }

    /** The ground at block scale: the surface's 4x4 columns, straight between their middles. */
    private double fineGround(int x, int z) {
        double u = (x + 0.5 - 2) / 4.0, v = (z + 0.5 - 2) / 4.0;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        double fu = u - i, fv = v - j;
        int x0 = i * 4 + 2, z0 = j * 4 + 2;
        double a = overhead.at(x0, z0).y(), b = overhead.at(x0 + 4, z0).y(), c = overhead.at(x0, z0 + 4).y(), d = overhead.at(x0 + 4, z0 + 4).y();
        return lerp(fv, lerp(fu, a, b), lerp(fu, c, d));
    }

    /** The lowest ground of the surface's 4x4 columns, straight between their middles: what a roof keeps under. */
    private double lowGround(int x, int z) {
        double u = (x + 0.5 - 2) / 4.0, v = (z + 0.5 - 2) / 4.0;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        double fu = u - i, fv = v - j;
        int x0 = i * 4 + 2, z0 = j * 4 + 2;
        double a = overhead.low(x0, z0), b = overhead.low(x0 + 4, z0), c = overhead.low(x0, z0 + 4), d = overhead.low(x0 + 4, z0 + 4);
        return lerp(fv, lerp(fu, a, b), lerp(fu, c, d));
    }

    /** The ground at 16-block scale, which the warren sheets and the deeper shapes follow, so hills do not jolt them. */
    private double coarseGround(int x, int z) {
        double u = (x + 0.5 - 8) / 16.0, v = (z + 0.5 - 8) / 16.0;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        double fu = u - i, fv = v - j;
        int x0 = i * 16 + 8, z0 = j * 16 + 8;
        double a = overhead.at(x0, z0).y(), b = overhead.at(x0 + 16, z0).y(), c = overhead.at(x0, z0 + 16).y(), d = overhead.at(x0 + 16, z0 + 16).y();
        return lerp(fv, lerp(fu, a, b), lerp(fu, c, d));
    }

    private static final int GATE_MOUTH = 0, GATE_PIT = 1, GATE_SKY = 2;

    /** How much of the ground round a column (its 3 x 3 surface columns) suits a kind of opening, eased to 0..1. */
    private double gate(int x, int z, int kind) {
        int n = 0;
        int sea = overhead.seaLevel();
        for (int dx = -4; dx <= 4; dx += 4) for (int dz = -4; dz <= 4; dz += 4) {
            Overhead.Surface s = overhead.at(x + dx, z + dz);
            if (s.wet() || s.y() < sea + 2) continue;
            boolean ok = switch (kind) {
                case GATE_MOUTH -> s.cover() == Overhead.Cover.FOREST;
                case GATE_PIT -> s.cover() == Overhead.Cover.PLAINS || s.cover() == Overhead.Cover.DESERT || s.cover() == Overhead.Cover.BADLANDS;
                default -> s.ravineOk();
            };
            if (ok) n++;
        }
        return smooth((n / 9.0 - 0.55) / 0.45);
    }

    // ------------------------------------------------------------------------------------------------ warrens

    /** Sheet i's tunnels at a column, before the mouths, links and roof caps. */
    private void sheet(Col c, int i) {
        double x = c.x, z = c.z;
        double[] g = grads.get();
        // the warp, and its slope (the zero lines' distance needs the slope of the warped field)
        Perlin D = wD[i];
        double wx = 8 * K2 * D.noise2(x / 45.0, z / 45.0, g);
        double wxx = 8 * K2 * g[0] / 45.0, wxz = 8 * K2 * g[1] / 45.0;
        double wz = 8 * K2 * D.noise2(x / 45.0 + 30, z / 45.0 + 30, g);
        double wzx = 8 * K2 * g[0] / 45.0, wzz = 8 * K2 * g[1] / 45.0;
        double f1 = wB[i].noise2((x + wx) / 70.0 + 3, (z + wz) / 70.0 + 3, g);
        double l1 = Math.abs(f1) / Math.max(Math.hypot(g[0] * (1 + wxx) + g[1] * wzx, g[0] * wxz + g[1] * (1 + wzz)) / 70.0, 0.53 / 70.0);
        double f2 = wC[i].noise2((x - wz) / 85.0 + 9, (z + wx) / 85.0 + 9, g);
        double l2 = Math.abs(f2) / Math.max(Math.hypot(g[0] * (1 - wzx) + g[1] * wxx, -g[0] * wzz + g[1] * (1 + wxz)) / 85.0, 0.53 / 85.0);
        c.wLine[i] = Math.min(l1, l2);
        c.wYc[i] = sheetY(i, c.gC, x, z);
        double room = clamp(K2 * D.noise2(x / 28.0 + 70, z / 28.0 + 70) * 1.6 - 0.35, 0, 1);
        c.wRoom[i] = room;
        c.wHw[i] = Math.max(1.4, 2.0 + 0.8 * K2 * wC[i].noise2(x / 20.0, z / 20.0) + 5.0 * room);
        c.wHh[i] = Math.max(2.1, 2.6 + 0.6 * K2 * wA[i].noise2(x / 16.0 + 5, z / 16.0 + 5) + 2.5 * room);
    }

    /** The middle of sheet i's tunnels under ground at 16-block scale gC, before mouths, links and caps. */
    private double sheetY(int i, double gC, double x, double z) {
        return gC - SHEET_MID[i] - SHEET_AMP[i] * K2 * wA[i].noise2(x / 110.0, z / 110.0) - 3 * K2 * wB[i].noise2(x / 35.0, z / 35.0);
    }

    private void warrenColumn(Col c) {
        // the lower sheet's lines are needed by the sinkholes even with the warrens off
        sheet(c, 0);
        sheet(c, 1);
        c.yc1Raw = c.wYc[1];
        c.hh1 = c.wHh[1];
        int x = c.x, z = c.z;
        // the roof caps (see roofCaps): under the lowest ground near, and lifted to the ground over a mouth
        double cap = roofCaps(c);
        // mouths: where a slow field is high the top sheet climbs toward the ground; under a forest the lifted cap lets
        // it open there as a sunken way in, elsewhere it runs just under the cap
        double m = mouthRamp(x, z);
        // links: here and there the top sheet dips to the lower one, so the two meet where their tunnels cross
        double k = K2 * linkN.noise2(x / 80.0, z / 80.0);
        k = k > 0.40 ? smooth((k - 0.40) / 0.45) * (1 - m) : 0;
        c.link = k;
        c.wYc[0] += k * (c.wYc[1] - c.wYc[0]);
        if (m > 0) {
            double to = Math.min(c.gF - 1.2, c.wYc[0] + MOUTH_RISE);
            if (to > c.wYc[0]) c.wYc[0] += m * (to - c.wYc[0]);
        }
        for (int i = 0; i < 2; i++) {
            double over = c.wYc[i] + c.wHh[i] - (i == 0 ? c.cap0 : cap);
            if (over > 0) c.wYc[i] -= over;
            c.wOn[i] = warrens && c.wYc[i] - 0.6 * c.wHh[i] > minFloor + 2 && c.wLine[i] < 3 * c.wHw[i] + 3;
            c.wPool[i] = NO_Y;
            if (c.wOn[i]) {
                c.lo = Math.min(c.lo, c.wYc[i] - 0.6 * c.wHh[i] - 2);
                c.hi = Math.max(c.hi, c.wYc[i] + c.wHh[i] + 2);
            }
        }
        if (c.forest && warrens) formation(c);
    }

    /** How far a mouth lets the top sheet climb from its own depth toward the ground, in blocks. */
    static final double MOUTH_RISE = 20;

    /** 0..1: how far the top sheet climbs toward the ground at a column (a slow field, whatever the ground). */
    private double mouthRamp(int x, int z) {
        double v = K2 * mouthN.noise2(x / 70.0, z / 70.0);
        return v > 0.2 ? smooth((v - 0.2) / 0.45) : 0;
    }

    // ------------------------------------------------------------------------------------------------ roof caps

    /**
     * How steeply a warren roof's cap may fall or climb, in blocks per block along x or z (about 0.85 on a diagonal):
     * near a valley the tunnels sink under it on a slope no steeper than this, and they climb into a mouth the same way,
     * never down a chute.
     */
    static final double ROOF_SLOPE = 0.6;
    /**
     * How far round a cell the caps look for lower ground and for mouths, in 4x4 cells (56 blocks): ground more than
     * 34 blocks (the slope over this reach) under a cap's would still bring a step where it comes into reach.
     */
    static final int ROOF_REACH = 14;
    private static final float STEP = (float) (4 * ROOF_SLOPE);
    private static final int CELL_BITS = 7, CELL_MASK = (1 << CELL_BITS) - 1;

    /**
     * The 4x4 cells' roof caps, per thread: a direct-mapped cache of 128 x 128 cells (every cell one answer needs lies
     * within 16 of the first, so none pushes out another). NaN marks a value not worked out yet.
     */
    private static final class Cells {
        final long[] key = new long[1 << 2 * CELL_BITS];
        /** The cell's own cap: 5 under its lowest ground, 9 under water. */
        final float[] cap = new float[key.length];
        /** The cap a mouth lifts it to (its own cap where none opens). */
        final float[] lift = new float[key.length];
        /** Along x, and then along z too: the least cap (or lifted cap) in reach, plus the slope times the distance. */
        final float[] rowCap = new float[key.length], rowLift = new float[key.length], cone = new float[key.length], coneLift = new float[key.length];

        Cells() { java.util.Arrays.fill(key, Long.MIN_VALUE); }
    }

    /** Cell (cx, cz)'s slot, taken over and its own cap worked out if another cell held it. */
    private int slot(Cells k, int cx, int cz) {
        int i = ((cx & CELL_MASK) << CELL_BITS) | (cz & CELL_MASK);
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        if (k.key[i] != key) {
            int bx = (cx << 2) + 2, bz = (cz << 2) + 2;
            k.key[i] = key;
            k.cap[i] = overhead.low(bx, bz) - (overhead.at(bx, bz).wet() ? 9 : 5);
            k.lift[i] = k.rowCap[i] = k.rowLift[i] = k.cone[i] = k.coneLift[i] = Float.NaN;
        }
        return i;
    }

    /**
     * The cap a mouth lifts cell (cx, cz)'s to: toward 4 over its ground where the mouths' field is high under a dry
     * forest (the gate), its own cap elsewhere. It asks nothing of cells further off, so the lifted caps' slope reaches
     * no further than the caps' own.
     */
    private float lift(Cells k, int cx, int cz) {
        int i = slot(k, cx, cz);
        float v = k.lift[i];
        if (v == v) return v;
        v = k.cap[i];
        int bx = (cx << 2) + 2, bz = (cz << 2) + 2;
        Overhead.Surface s = overhead.at(bx, bz);
        if (warrens && s.cover() == Overhead.Cover.FOREST && !s.wet()) {
            double e = mouthRamp(bx, bz);
            if (e > 0) e *= gate(bx, bz, GATE_MOUTH);
            double top = s.y() + 4;
            if (e > 0 && top > v) v = (float) lerp(e, v, top);
        }
        k.lift[i] = v;
        return v;
    }

    /**
     * Cell (cx, cz)'s cap sunk toward every lower cap in reach, or with {@code lifted} its lifted cap sunk toward every
     * lower lifted cap: the least of their caps plus {@link #ROOF_SLOPE} times the distance (along x, then z).
     */
    private float cone(Cells k, int cx, int cz, boolean lifted) {
        int i = slot(k, cx, cz);
        float v = lifted ? k.coneLift[i] : k.cone[i];
        if (v == v) return v;
        v = Float.POSITIVE_INFINITY;
        for (int dz = -ROOF_REACH; dz <= ROOF_REACH; dz++) {
            int j = slot(k, cx, cz + dz);
            float r = lifted ? k.rowLift[j] : k.rowCap[j];
            if (r != r) {
                r = Float.POSITIVE_INFINITY;
                for (int dx = -ROOF_REACH; dx <= ROOF_REACH; dx++) {
                    float c = lifted ? lift(k, cx + dx, cz + dz) : k.cap[slot(k, cx + dx, cz + dz)];
                    r = Math.min(r, c + STEP * Math.abs(dx));
                }
                if (lifted) k.rowLift[j] = r;
                else k.rowCap[j] = r;
            }
            v = Math.min(v, r + STEP * Math.abs(dz));
        }
        if (lifted) k.coneLift[i] = v;
        else k.cone[i] = v;
        return v;
    }

    /**
     * The roofs' caps at a column, straight between the cells' middles: the lower sheet's (returned) keeps under the
     * lowest ground near, 5 blocks under it and 9 under water, sinking toward lower ground in reach on a slope; the top
     * sheet's ({@link Col#cap0}) is the same but lifted to the ground over a mouth, so a mouth's tunnel falls from it on
     * the same slope. Sets {@link Col#mouth}.
     */
    private double roofCaps(Col c) {
        Cells k = cells.get();
        double u = (c.x + 0.5 - 2) / 4.0, v = (c.z + 0.5 - 2) / 4.0;
        int ci = (int) Math.floor(u), cj = (int) Math.floor(v);
        double fu = u - ci, fv = v - cj;
        double cone = lerp(fv, lerp(fu, cone(k, ci, cj, false), cone(k, ci + 1, cj, false)), lerp(fu, cone(k, ci, cj + 1, false), cone(k, ci + 1, cj + 1, false)));
        double cap = Math.min(c.low - (c.wet ? 9 : 5), cone);
        c.cap0 = cap;
        c.mouth = 0;
        int ox = c.x >> 2, oz = c.z >> 2;
        if (lift(k, ox, oz) > k.cap[slot(k, ox, oz)]) {
            double m = lerp(fv, lerp(fu, cone(k, ci, cj, true), cone(k, ci + 1, cj, true)), lerp(fu, cone(k, ci, cj + 1, true), cone(k, ci + 1, cj + 1, true)));
            if (m > cap) {
                c.cap0 = m;
                c.mouth = clamp((m - cap) / Math.max(1, c.gF + 4 - cap), 0, 1);
            }
        }
        return cap;
    }

    /** The column's hanging root (under trees), or in the snowy zone of a cold forest's warrens an icicle. */
    private void formation(Col c) {
        double pk = K2 * (rootN.noise2(c.x / 3.1, c.z / 3.1) + 0.5 * rootN.noise2(c.x / 11.0 + 7, c.z / 11.0 + 7));
        if (pk < 0.62) return;
        for (int i = 0; i < 2; i++) {
            if (!c.wOn[i]) continue;
            double q = c.wLine[i] / c.wHw[i];
            if (q > 0.85) continue;
            double ceil = c.wYc[i] + c.wHh[i] * Math.sqrt(1 - q * q);
            double floorTop = c.wYc[i] - 0.6 * c.wHh[i];
            double depth = c.gF - ceil;
            if (depth < 3) continue;
            boolean ice = c.cold && chillAt(c, ceil) < 3;
            double len = ice ? clamp((pk - 0.62) * 14, 0, 6) : clamp((pk - 0.75) * 12, 0, 4);
            if (len <= 0.3 || !ice && depth > 26) continue;
            c.fKind = ice ? ICICLE : ROOT;
            c.fBottom = Math.max(ceil - 0.5 - len, floorTop + 2.0);
            c.fTop = ceil + 1.5;
            c.hi = Math.max(c.hi, c.fTop + 1);
            return;
        }
    }

    /** Signed distance to sheet i's tunnels (negative inside), without its pool, roots or anything else. */
    private double warrenDist(Col c, int i, int x, int y, int z) {
        double yy = y + 0.5;
        double hh = c.wHh[i], hw = c.wHw[i];
        double dy = (yy - c.wYc[i]) / hh, dxz = c.wLine[i] / hw;
        double dFloor = (c.wYc[i] - 0.6 * hh) - yy;
        if (dxz > 3 || dy > 3 || dy < -3) return Math.max((Math.max(dxz, Math.abs(dy)) - 1) * Math.min(hh, hw), dFloor);
        double dEll = (Math.sqrt(dy * dy + dxz * dxz) - 1) * Math.min(hh, hw);
        if (dEll < 3 && dEll > -3) dEll += 0.5 * K3 * roughN.noise3(x / 6.0, y / 6.0, z / 6.0);
        return Math.max(dEll, dFloor);
    }

    /** The lowest open block of sheet i's floor at a column. */
    private static int floorLevel(Col c, int i) { return (int) Math.floor(c.wYc[i] - 0.6 * c.wHh[i] - 0.5) + 1; }

    /**
     * Pools in the warrens' root chambers: one block sunk under a flat stretch of floor where a fine field is high, and
     * only where no neighbour's floor lies lower (the water stays in its basin).
     */
    private void warrenPools(Col c) {
        if (c.poolDone) return;
        c.poolDone = true;
        for (int i = 0; i < 2; i++) {
            c.wPool[i] = NO_Y;
            if (!c.wOn[i] || c.wRoom[i] < 0.55 || c.wLine[i] > 0.7 * c.wHw[i]) continue;
            if (K2 * poolN.noise2(c.x / 9.0, c.z / 9.0) < 0.30) continue;
            int fl = floorLevel(c, i);
            if (fl > c.gF - 6) continue;
            int x = c.x, z = c.z;
            boolean ok = true;
            for (int k = 0; k < 4 && ok; k++) {
                Col n = column(x + (k == 0 ? 1 : k == 1 ? -1 : 0), z + (k == 2 ? 1 : k == 3 ? -1 : 0));
                ok = n.wOn[i] && n.wLine[i] < 0.85 * n.wHw[i] && floorLevel(n, i) >= fl;
            }
            if (ok) c.wPool[i] = fl - 1;
        }
    }

    /** How far a block of a cold forest's warrens lies beyond the snowy zone round the ground (0 or less: snowy). */
    private double chillAt(Col c, double y) {
        return (c.gF - y) - 12 - 4 * K2 * chillN.noise2(c.x / 24.0, c.z / 24.0);
    }

    // ------------------------------------------------------------------------------------------------ rifts

    private boolean riftColumn(Col c) {
        double x = c.x, z = c.z;
        double[] g = grads.get();
        double wx = 14 * K2 * rD.noise2(x / 120.0, z / 120.0, g);
        double wxx = 14 * K2 * g[0] / 120.0, wxz = 14 * K2 * g[1] / 120.0;
        double wz = 14 * K2 * rD.noise2(x / 120.0 + 50, z / 120.0 + 50, g);
        double wzx = 14 * K2 * g[0] / 120.0, wzz = 14 * K2 * g[1] / 120.0;
        double f1 = rA.noise2((x + wx) / 190.0, (z + wz) / 190.0, g);
        double d1 = Math.abs(f1) / Math.max(Math.hypot(g[0] * (1 + wxx) + g[1] * wzx, g[0] * wxz + g[1] * (1 + wzz)) / 190.0, 0.53 / 190.0);
        double f2 = rB.noise2((x - wz) / 150.0 + 13, (z + wx) / 150.0 + 7, g);
        double d2 = Math.abs(f2) / Math.max(Math.hypot(g[0] * (1 - wzx) + g[1] * wxx, -g[0] * wzz + g[1] * (1 + wxz)) / 150.0, 0.53 / 150.0);
        // the second set of cracks a little narrower
        c.rLine = Math.min(d1, d2 + 4);
        // wider where the region leans to rifts
        c.rScale = (0.8 + 0.25 * (K2 * rB.noise2(x / 70.0 + 3, z / 70.0 + 3) + 1)) * (1 - 0.25 * c.lean);
        if (c.rLine > 15 * c.rScale + 6) return false;
        double closed = Math.min(c.gC - 16 - 8 * K2 * rB.noise2(x / 80.0, z / 80.0), c.low - (c.wet ? 10 : 6));
        // in places the crack opens to the sky (dry open land only), easing in so its walls do not jump
        double sky = K2 * rC.noise2(x / 60.0 + 20, z / 60.0 + 20);
        sky = sky > 0.30 ? smooth((sky - 0.30) / 0.12) : 0;
        if (sky > 0) sky *= gate(c.x, c.z, GATE_SKY);
        c.rSky = sky > 0.5;
        double top = lerp(sky, closed, c.gF + 2);
        double floor = Math.max(c.gC - 92 + 14 * K2 * rB.noise2(x / 140.0 + 9, z / 140.0 + 9), minFloor);
        int fq = 3 * (int) Math.ceil(floor / 3.0);
        if (top - fq < 24) return false;
        c.rTop = top;
        c.rFloor = fq;
        c.rStep = 3 * K2 * rC.noise2(x / 30.0, z / 30.0);
        c.lo = Math.min(c.lo, fq - 1);
        c.hi = Math.max(c.hi, top + 1);
        return true;
    }

    /** Signed distance to the rift's open space (negative inside). */
    private double riftDist(Col c, int x, int y, int z) {
        double yy = y + 0.5;
        double dV = Math.max(c.rFloor - yy, yy - c.rTop);
        if (dV > 1) return Math.max(dV, c.rLine - 15 * c.rScale);
        double t = clamp((yy - c.rFloor) / (c.rTop - c.rFloor), 0, 1);
        double lens = Math.sqrt(Math.sin(Math.PI * t));
        int step = (int) Math.floor((yy + c.rStep) / 6.0);
        double ledge = K3 * rC.noise3(x / 22.0, step * 1.7, z / 22.0);
        double width = (2.5 + 7.5 * lens + 2.0 * ledge) * c.rScale;
        double d = c.rLine - width;
        if (d < 4 && d > -4) d += 0.9 * K3 * roughN.noise3(x / 7.0 + 40, y / 7.0, z / 7.0 + 40);
        return Math.max(d, dV);
    }

    /**
     * A spring high on a rift's wall: one candidate column in each 24-block square (some squares have none) that lies in
     * the wall at some height in the crack's upper half; its block there opens and holds water that falls down the
     * wall into the stream.
     */
    int spring(Col c) {
        if (c.springDone) return c.springY;
        c.springDone = true;
        c.springY = NO_Y;
        if (!c.rOn || c.rLine < 2.5) return NO_Y;
        int cx = Math.floorDiv(c.x, 24), cz = Math.floorDiv(c.z, 24);
        long h = Hashing.mix(seed ^ 0x51F15EL, cx, cz, 7);
        if (Hashing.unit(h) > 0.45) return NO_Y;
        int sx = cx * 24 + 2 + (int) (Hashing.unit(Hashing.mix(h, 1, 0, 0)) * 20), sz = cz * 24 + 2 + (int) (Hashing.unit(Hashing.mix(h, 2, 0, 0)) * 20);
        if (sx != c.x || sz != c.z) return NO_Y;
        // the height where the crack's plain profile reaches this column, in its upper half
        double want = (c.rLine - 0.7) / c.rScale;
        double l = (want - 2.5) / 7.5;
        if (l <= 0.05 || l >= 0.98) return NO_Y;
        double t = 1 - Math.asin(l * l) / Math.PI;
        int y0 = (int) Math.floor(c.rFloor + t * (c.rTop - c.rFloor));
        for (int k = 0; k < 9; k++) {
            int y = y0 + (k % 2 == 0 ? k / 2 : -(k + 1) / 2);
            if (y < c.rFloor + 8 || y > c.rTop - 6 || y > c.gF - 8) continue;
            double d = riftDist(c, c.x, y, c.z);
            if (d > 0.2 && d < 1.6 && riftDist(c, c.x, y + 1, c.z) > 0.5) {
                c.springY = y;
                return y;
            }
        }
        return NO_Y;
    }

    // ------------------------------------------------------------------------------------------------ halls

    private boolean hallColumn(Col c) {
        double x = c.x, z = c.z;
        // more halls where the region leans to them
        double m = K2 * (hA.noise2(x / 110.0, z / 110.0) + 0.25 * hB.noise2(x / 30.0, z / 30.0)) - 0.30 + 0.15 * c.lean;
        if (m <= 0) return false;
        double H = (16 + 8 * K2 * hC.noise2(x / 60.0, z / 60.0)) * smooth(m / 0.10);
        double cy = c.gC - 72 + 12 * K2 * hC.noise2(x / 150.0 + 40, z / 150.0 + 40);
        cy = Math.min(cy, c.gC - 30 - 0.55 * H);
        if (cy < minFloor + 3 + 0.45 * H) return false;
        double floorRaw = cy - 0.45 * H;
        double ceil = Math.min(cy + 0.55 * H + 2 * K2 * hE.noise2(x / 18.0, z / 18.0) * Math.min(1, H / 8), c.low - 14);
        // floors settle into terraces three blocks high
        int hf = 3 * (int) Math.floor((floorRaw + 2.5 * (K2 * hD.noise2(x / 26.0, z / 26.0) + 1)) / 3.0);
        if (ceil - hf < 2.0) return false;
        c.hFloor = hf;
        c.hCeil = ceil;
        c.hH = H;
        // stalactites (icicles under cold ground) where a fine field peaks, leaving headroom; stalagmites under some
        double pk = K2 * (hS.noise2(x / 5.0 + 40, z / 5.0 + 40) + 0.4 * hS.noise2(x / 2.3, z / 2.3));
        double len = Math.min(clamp((pk - 0.55) * 22, 0, 8), ceil - hf - 5);
        if (len > 0.5) {
            c.stalBottom = ceil - 1.5 - len;
            c.stalTip = (int) Math.ceil(c.stalBottom - 0.5);
            if (!c.cold && K2 * hS.noise2(x / 13.0 + 9, z / 13.0 + 9) > 0.1) {
                double mite = Math.min(0.45 * len, c.stalBottom - hf - 3.5);
                if (mite >= 1) c.miteTop = hf + (int) Math.floor(mite) - 1;
            }
        }
        // chimneys: here and there under a tunnel of the lower warren sheet the ceiling rises in a shaft to the tunnel's
        // floor, so the halls open onto the warrens (water from the tunnel's pools falls down them)
        if (c.wOn[1]) {
            double q = c.wLine[1] / c.wHw[1];
            double t = smooth((K2 * hK.noise2(x / 20.0, z / 20.0) - 0.25) / 0.2) * smooth((0.9 - q) / 0.5);
            double floor1 = c.wYc[1] - 0.6 * c.wHh[1];
            if (t > 0 && floor1 > ceil) {
                ceil = lerp(t, ceil, floor1 + 2.5);
                c.hCeil = ceil;
                if (t > 0.05) {
                    c.stalBottom = Double.NaN;
                    c.miteTop = NO_Y;
                }
            }
        }
        c.lo = Math.min(c.lo, hf - 2);
        c.hi = Math.max(c.hi, ceil + 3);
        return true;
    }

    /** Signed distance to the hall's open space (negative inside), without its pools and formations. */
    private double hallDist(Col c, int x, int y, int z) {
        double yy = y + 0.5;
        double dF = c.hFloor - yy;
        double dC = yy - c.hCeil;
        if (dC > -4 && dC < 4) dC -= 1.5 * K3 * roughN.noise3(x / 12.0 + 90, y / 8.0, z / 12.0 + 90);
        return Math.max(dF, dC);
    }

    /** A pool sunk one block into a terrace, where a fine field is high and no neighbour's floor lies lower. */
    private boolean hallPool(Col c) {
        if (c.hPoolDone) return c.hPool;
        c.hPoolDone = true;
        c.hPool = false;
        if (!c.hOn || c.hCeil - c.hFloor < 5 || c.miteTop != NO_Y) return false;
        double pf = K2 * (hE.noise2(c.x / 9.0, c.z / 9.0) + 0.5 * hD.noise2(c.x / 4.0 + 5, c.z / 4.0 + 5)) + 0.1 * c.lean;
        if (pf < 0.25) return false;
        for (int k = 0; k < 4; k++) {
            Col n = column(c.x + (k == 0 ? 1 : k == 1 ? -1 : 0), c.z + (k == 2 ? 1 : k == 3 ? -1 : 0));
            if (!n.hOn || n.hFloor < c.hFloor || n.hCeil - n.hFloor < 3) return false;
            if (n.rOn && riftDist(n, n.x, c.hFloor - 1, n.z) < 0.5) return false;
        }
        c.hPool = true;
        return true;
    }

    // ------------------------------------------------------------------------------------------------ sinkholes

    private boolean sinkColumn(Col c) {
        if (!(c.plains || c.desert) || c.wet) return false;
        double x = c.x, z = c.z;
        double pk = K2 * (sA.noise2(x / PIT_SCALE, z / PIT_SCALE) + 0.18 * sB.noise2(x / 14.0, z / 14.0));
        if (pk < 0.3) return false;
        double gate = gate(c.x, c.z, GATE_PIT);
        if (gate <= 0) return false;
        // pits sit beside a tunnel of the lower sheet, which their chamber opens onto
        double score = pk - 0.30 * smooth((c.wLine[1] - 6) / 16.0) - 0.6 * (1 - gate);
        if (score < 0.24) return false;
        int lake = lakeLevel(c.x, c.z);
        if (lake == NO_Y || lake > c.gF - 14) return false;
        c.sScore = score;
        c.sLake = lake;
        c.sThick = 5 + 2.5 * K2 * sB.noise2(x / 30.0, z / 30.0);
        c.lo = Math.min(c.lo, lake - 2);
        c.hi = Math.max(c.hi, c.gF + 3);
        return true;
    }

    /**
     * The surface of a sinkhole's lake: one level for the whole pit, from its middle (the peak of the pit field's slow
     * part, found by climbing it on a 4-block lattice): a block under the lower sheet's floor there.
     */
    private int lakeLevel(int x, int z) {
        int i = Math.floorDiv(x, 4), j = Math.floorDiv(z, 4);
        double best = sA.noise2(i * 4 / PIT_SCALE, j * 4 / PIT_SCALE);
        for (int step = 0; step < 48; step++) {
            int bi = i, bj = j;
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                double v = sA.noise2((i + di) * 4 / PIT_SCALE, (j + dj) * 4 / PIT_SCALE);
                if (v > best) { best = v; bi = i + di; bj = j + dj; }
            }
            if (bi == i && bj == j) break;
            i = bi;
            j = bj;
        }
        int cx = i * 4, cz = j * 4;
        double gC = coarseGround(cx, cz);
        double yc = sheetY(1, gC, cx, cz);
        double room = clamp(K2 * wD[1].noise2(cx / 28.0 + 70, cz / 28.0 + 70) * 1.6 - 0.35, 0, 1);
        double hh = Math.max(2.1, 2.6 + 0.6 * K2 * wA[1].noise2(cx / 16.0 + 5, cz / 16.0 + 5) + 2.5 * room);
        int lake = (int) Math.floor(yc - 0.6 * hh) - 1;
        lake = Math.min(lake, overhead.low(cx, cz) - 16);
        return lake < minFloor + 2 ? NO_Y : lake;
    }

    /** Signed distance to the pit and the chamber at its foot (negative inside). */
    private double sinkDist(Col c, int x, int y, int z) {
        double yy = y + 0.5;
        int lake = c.sLake;
        if (yy < lake - 1.5) return lake - 1 - yy;
        double depth = c.gF - yy;
        double under = clamp((depth - 3) / 12.0, 0, 1);
        double thresh = 0.62 - 0.12 * under - 0.04 * K3 * sA.noise3(x / 9.0, y / 9.0, z / 9.0);
        double pit = Math.max((thresh - c.sScore) / 0.05, Math.max(lake - 1 - yy, yy - (c.gF + 3)));
        double near = c.sScore + 0.25 * K3 * sB.noise3(x / 25.0 + 5, y / 15.0, z / 25.0 + 5);
        double top = lake + 1 + c.sThick + 0.8 * K3 * roughN.noise3(x / 8.0 + 20, y / 6.0, z / 8.0 + 20);
        double cave = Math.max((0.30 - near) / 0.05, Math.max(lake + 1 - yy, yy - top));
        return Math.min(pit, cave);
    }

    /** The pit itself (not its chamber), open from the lake's bed up through the ground. */
    private boolean inPit(Col c, int x, int y, int z) {
        double yy = y + 0.5;
        if (yy < c.sLake - 1.5) return false;
        double depth = c.gF - yy;
        double under = clamp((depth - 3) / 12.0, 0, 1);
        double thresh = 0.62 - 0.12 * under - 0.04 * K3 * sA.noise3(x / 9.0, y / 9.0, z / 9.0);
        return c.sScore > thresh;
    }

    // ================================================================================================ queries

    /** Signed distance (in blocks, roughly) to the open space of every shape: negative inside, {@link #SOLID} far off. */
    public double carve(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo || y > c.hi) return SOLID;
        return carve(c, x, y, z);
    }

    private double carve(Col c, int x, int y, int z) {
        double d = SOLID;
        int owner = -1;
        for (int i = 0; i < 2; i++) {
            if (!c.wOn[i]) continue;
            double w = warrenDist(c, i, x, y, z);
            if (w < d) { d = w; owner = WARRENS; }
        }
        if (c.rOn) {
            double r = riftDist(c, x, y, z);
            if (r < d) { d = r; owner = RIFTS; }
            if (d > -0.5 && y == spring(c)) { d = -0.5; owner = RIFTS; }
        }
        if (c.hOn) {
            double h = hallDist(c, x, y, z);
            if (h < d) { d = h; owner = HALLS; }
        }
        if (c.sOn) {
            double s = sinkDist(c, x, y, z);
            if (s < d) { d = s; owner = SINKHOLES; }
        }
        if (d > -0.5 && pool(c, y)) return -0.5;
        if (d < 0 && formationOf(c, owner, x, y, z) != 0) return 0.5;
        return d;
    }

    /** A pool block (sunk under a warren's or hall's floor) at this column and height. */
    boolean pool(Col c, int y) {
        boolean near = false;
        for (int i = 0; i < 2; i++) near |= c.wOn[i] && y == floorLevel(c, i) - 1;
        if (near) {
            warrenPools(c);
            if (y == c.wPool[0] || y == c.wPool[1]) return true;
        }
        return c.hOn && y == c.hFloor - 1 && hallPool(c);
    }

    /**
     * The formation a block of open space holds (it stays rock): {@link #ROOT} or {@link #ICICLE} hanging from a warren's
     * ceiling, {@link #STALACTITE} or {@link #STALAGMITE} in a hall ({@link #DRIP} at their tips, {@link #ICICLE} under cold
     * ground), or 0. Only where the shape the block belongs to is the only one open there, so nothing hangs in a rift
     * that crosses a hall.
     */
    private int formationOf(Col c, int owner, int x, int y, int z) {
        double yy = y + 0.5;
        if (owner == WARRENS && c.fKind != 0 && yy >= c.fBottom && yy <= c.fTop) {
            if (c.rOn && riftDist(c, x, y, z) < 0) return 0;
            if (c.hOn && hallDist(c, x, y, z) < 0) return 0;
            if (c.sOn && sinkDist(c, x, y, z) < 0) return 0;
            return c.fKind;
        }
        if (owner == HALLS) {
            if (!Double.isNaN(c.stalBottom) && yy >= c.stalBottom && yy <= c.hCeil + 2.5) {
                if (c.rOn && riftDist(c, x, y, z) < 0) return 0;
                return c.cold ? ICICLE : y == c.stalTip ? DRIP : STALACTITE;
            }
            if (c.miteTop != NO_Y && y >= c.hFloor && y <= c.miteTop) {
                if (c.rOn && riftDist(c, x, y, z) < 0) return 0;
                return y == c.miteTop ? DRIP : STALAGMITE;
            }
        }
        return 0;
    }

    /**
     * A formation block (see {@link #formationOf}) at a block, whatever opens round it: the surface pass dresses these,
     * and the pack's caves may not open them ({@link #seals}).
     */
    public int formation(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo || y > c.hi) return 0;
        if (c.fKind == 0 && Double.isNaN(c.stalBottom) && c.miteTop == NO_Y) return 0;
        double d = SOLID;
        int owner = -1;
        for (int i = 0; i < 2; i++) {
            if (!c.wOn[i]) continue;
            double w = warrenDist(c, i, x, y, z);
            if (w < d) { d = w; owner = WARRENS; }
        }
        if (c.hOn) {
            double h = hallDist(c, x, y, z);
            if (h < d) { d = h; owner = HALLS; }
        }
        // a root or stalactite runs on up into the rock over its ceiling, so it never hangs loose of it
        double yy = y + 0.5;
        if (d >= 0) {
            if (c.fKind != 0 && yy >= c.fBottom && yy <= c.fTop) owner = WARRENS;
            else if (c.hOn && !Double.isNaN(c.stalBottom) && yy >= c.stalBottom && yy <= c.hCeil + 2.5) owner = HALLS;
            else return 0;
        }
        return formationOf(c, owner, x, y, z);
    }

    /** Where the pack's own caves must stay shut: the formations, which they would otherwise cut loose. */
    public boolean seals(int x, int y, int z) { return formation(x, y, z) != 0; }

    /**
     * What fills an open block: {@link #WATER} in the rift's stream, its springs, the pools and the sinkholes' lakes,
     * {@link #AIR} elsewhere in the shapes and within two blocks of them (so the pack's water tables do not flood them),
     * or -1 to leave it to the pack. All the water is set to flow as the chunk finishes, so none hangs over air.
     */
    public int fluid(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo - 3 || y > c.hi + 3) return -1;
        double best = SOLID;
        if (c.rOn) {
            double r = riftDist(c, x, y, z);
            if (r < 0.5 && y <= c.rFloor + 1) return WATER;
            if (y == spring(c)) return WATER;
            best = Math.min(best, r);
        }
        if (c.sOn) {
            double s = sinkDist(c, x, y, z);
            if (s < 0.5 && y <= c.sLake && inPit(c, x, y, z)) return WATER;
            best = Math.min(best, s);
        }
        if (pool(c, y)) return WATER;
        for (int i = 0; i < 2; i++) if (c.wOn[i]) best = Math.min(best, warrenDist(c, i, x, y, z));
        if (c.hOn) best = Math.min(best, hallDist(c, x, y, z));
        return best < 2.0 ? AIR : -1;
    }

    /** True within {@code r} blocks of any shape's open space (where the pack's water should flow into ours). */
    public boolean near(int x, int y, int z, double r) { return carve(x, y, z) < r; }

    /**
     * How deep in the rock a block lies, for dressing the shapes' shells: {@link #carve}, which measures up and down its
     * column, or one more than its nearest side neighbour's, so a wall face under a ledge counts as the surface it is.
     */
    public double shell(int x, int y, int z) {
        double d = carve(x, y, z);
        if (d <= 3) return d;
        double side = Math.min(Math.min(carve(x + 1, y, z), carve(x - 1, y, z)), Math.min(carve(x, y, z + 1), carve(x, y, z - 1)));
        return Math.min(d, side + 1);
    }

    /**
     * The rift's or hall's biome round a block ({@link #PIECE_RIFT}, {@link #PIECE_HALL}, {@link #PIECE_COLD_HALL} under
     * cold ground), within 6 blocks of its open space and never within 6 of the ground; or 0.
     */
    public int piece(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo - 6 || y > c.hi + 6 || y > c.gF - 6) return 0;
        if (c.rOn && riftDist(c, x, y, z) < 6) return PIECE_RIFT;
        if (c.hOn && hallDist(c, x, y, z) < 6) return c.cold ? PIECE_COLD_HALL : PIECE_HALL;
        return 0;
    }

    /**
     * The biome the warrens and sinkholes give a block, by what covers the ground: the Root Vaults under forests (frozen
     * under cold ones), the sinkholes' limestone under plains, the oasis hollows' sandstone under deserts and badlands;
     * within 5 blocks of their open space or one biome cell (4 blocks) to the side of such a place, and never within 6
     * of the ground. Null elsewhere, and under other ground.
     */
    public Entrances.Area areaAt(int x, int y, int z) {
        Entrances.Area a = inArea(x, y, z);
        if (a != null) return a;
        a = inArea(x - 4, y, z);
        if (a == null) a = inArea(x + 4, y, z);
        if (a == null) a = inArea(x, y, z - 4);
        if (a == null) a = inArea(x, y, z + 4);
        return a != null && y <= column(x, z).gF - 6 ? a : null;
    }

    private Entrances.Area inArea(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo - 6 || y > c.hi + 6 || y > c.gF - 6) return null;
        if (c.sOn && sinkDist(c, x, y, z) < 5) return c.desert ? Entrances.Area.OASIS_HOLLOW : Entrances.Area.SINKHOLE;
        boolean near = false;
        for (int i = 0; i < 2 && !near; i++) near = c.wOn[i] && warrenDist(c, i, x, y, z) < 5;
        if (!near) return null;
        if (c.forest) return c.cold ? Entrances.Area.FROZEN_ROOTVAULTS : Entrances.Area.ROOTVAULTS;
        if (c.plains) return Entrances.Area.SINKHOLE;
        if (c.desert) return Entrances.Area.OASIS_HOLLOW;
        return null;
    }

    /**
     * The entrances' look a block takes, for their plants and rock (see {@link Entrances#HALL} and the others): sinkholes
     * and karst under plains, hollows under deserts; under forests root halls, teal grottos where a slow field is low,
     * and the open look ({@link Entrances#RAVINE}) round the mouths. -1 under other ground.
     */
    public int look(int x, int y, int z) {
        Col c = column(x, z);
        if (c.desert) return Entrances.HOLLOW;
        if (c.plains) return Entrances.SINKHOLE;
        if (!c.forest) return -1;
        if (c.mouth > 0.3 && y > c.gF - 16) return Entrances.RAVINE;
        return K2 * lookN.noise2(x / 90.0, z / 90.0) < -0.35 ? Entrances.TEAL : Entrances.HALL;
    }

    /** The ground over a column is cold (snow falls on it). */
    public boolean cold(int x, int z) { return column(x, z).cold; }

    /** Tropical trees grow over a column (veridium). */
    public boolean tropical(int x, int z) { return column(x, z).tropical; }

    /** The column's desert is the badlands (red sandstone). */
    public boolean red(int x, int z) { return column(x, z).red; }

    /** What covers the ground over a column. */
    public Overhead.Cover cover(int x, int z) { return column(x, z).s.cover(); }

    /** The kind of trees over a column (the wood of its roots). */
    public RootKind trees(int x, int z) { return column(x, z).s.trees(); }

    /**
     * How far a block of a cold forest's warrens lies beyond the snowy zone round the ground: 0 or less within about 12
     * blocks of it (snow and ice), thawing deeper in (frost moss, then plants). Infinite under warm or open ground.
     */
    public double chill(int x, int y, int z) {
        Col c = column(x, z);
        return c.forest && c.cold ? chillAt(c, y + 0.5) : Double.POSITIVE_INFINITY;
    }

    /** A sinkhole's pit wall within 2 blocks of the pit, up to two blocks under the ground: dressed as the sinkhole. */
    public boolean openWall(int x, int y, int z) {
        Col c = column(x, z);
        if (!c.sOn || y > c.gF - 2 || y < c.sLake - 3) return false;
        return sinkDist(c, x, y, z) < 2;
    }

    /** The surface of the lake or pool nearest under a hall's floor at a column (its lowest terrace), for its rock. */
    public double hallLake(int x, int z) {
        Col c = column(x, z);
        return c.hOn ? c.hFloor : packFloor + 14;
    }

    /** True where a warren tunnel lies right under the ground of a forest column, so the trees' roots reach it. */
    public boolean underRoots(int x, int z) {
        Col c = column(x, z);
        if (!c.forest) return false;
        for (int i = 0; i < 2; i++) {
            if (!c.wOn[i] || c.wLine[i] > 0.8 * c.wHw[i]) continue;
            if (c.gF - (c.wYc[i] + c.wHh[i]) < 22) return true;
        }
        return false;
    }

    /**
     * The heights a feature should try over a column to land in the warrens or a sinkhole: {lowest, highest}, or null
     * where none lies under it. Kept 4 blocks under the ground (2 in a sinkhole, which opens higher).
     */
    public int[] band(int x, int z) {
        Col c = column(x, z);
        double lo = 1e9, hi = -1e9;
        boolean open = false;
        for (int i = 0; i < 2; i++) {
            if (!c.wOn[i] || c.wLine[i] > c.wHw[i]) continue;
            lo = Math.min(lo, c.wYc[i] - 0.6 * c.wHh[i] - 1);
            hi = Math.max(hi, c.wYc[i] + c.wHh[i] + 1);
        }
        if (c.sOn && c.sScore > 0.3) {
            lo = Math.min(lo, c.sLake - 1);
            hi = Math.max(hi, c.gF);
            open = true;
        }
        if (lo > hi) return null;
        hi = Math.min(hi, c.gF - (open ? 2 : 4));
        if (lo > hi) return null;
        return new int[]{(int) Math.floor(lo), (int) Math.ceil(hi)};
    }

    /** The heights over a column where a rift or hall is open: {lowest, highest}, or null where neither is. */
    public int[] pieceBand(int x, int z) {
        Col c = column(x, z);
        double lo = 1e9, hi = -1e9;
        if (c.rOn && c.rLine < 12 * c.rScale) {
            lo = c.rFloor;
            hi = Math.min(c.rTop, c.gF - 4);
        }
        if (c.hOn) {
            lo = Math.min(lo, c.hFloor - 1);
            hi = Math.max(hi, c.hCeil + 2);
        }
        if (lo > hi) return null;
        return new int[]{(int) Math.floor(lo), (int) Math.ceil(hi)};
    }

    /** Which shape's open space a block lies in (the first, in the order of {@link #NAMES}), or -1. */
    public int kindAt(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo || y > c.hi) return -1;
        for (int i = 0; i < 2; i++) if (c.wOn[i] && warrenDist(c, i, x, y, z) < 0) return WARRENS;
        if (c.rOn && riftDist(c, x, y, z) < 0) return RIFTS;
        if (c.hOn && hallDist(c, x, y, z) < 0) return HALLS;
        if (c.sOn && sinkDist(c, x, y, z) < 0) return SINKHOLES;
        return -1;
    }

    /** What the shapes hold over a column, for the dev commands. */
    public String describe(int x, int z) {
        Col c = column(x, z);
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "ground %.1f (coarse %.1f, low %.1f) %s%s lean %.2f", c.gF, c.gC,
                c.low, c.s.cover(), c.cold ? " cold" : "", c.lean));
        for (int i = 0; i < 2; i++)
            if (c.wOn[i]) b.append(String.format(java.util.Locale.ROOT, "; warren %d y %.1f half %.1fx%.1f line %.1f room %.2f", i, c.wYc[i], c.wHw[i],
                    c.wHh[i], c.wLine[i], c.wRoom[i]));
        if (c.mouth > 0) b.append(String.format(java.util.Locale.ROOT, "; mouth %.2f", c.mouth));
        if (c.link > 0) b.append(String.format(java.util.Locale.ROOT, "; link %.2f", c.link));
        if (c.rOn) b.append(String.format(java.util.Locale.ROOT, "; rift floor %d top %.1f line %.1f scale %.2f%s", c.rFloor, c.rTop, c.rLine, c.rScale,
                c.rSky ? " sky" : ""));
        if (c.hOn) b.append(String.format(java.util.Locale.ROOT, "; hall floor %d ceiling %.1f", c.hFloor, c.hCeil));
        if (c.sOn) b.append(String.format(java.util.Locale.ROOT, "; sinkhole lake %d score %.2f", c.sLake, c.sScore));
        return b.toString();
    }

    /**
     * Places to look at each shape, nearest first, for the dev commands: open blocks of a kind ({@code mouth},
     * {@code chambers}, {@code frozen} and {@code sky} for the warrens' mouths, their root chambers, a cold forest's
     * warrens and the rifts open to the sky as well), searched on a 4-block grid in rings
     * round (x, z) out to {@code radius}, each at least {@code apart} from the others. Each is {x, y, z} with y the
     * lowest open block of that kind in its column.
     */
    public java.util.List<int[]> find(String kind, int x0, int z0, int radius, int max, int apart) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int ring = 0; ring * 4 <= radius && out.size() < max; ring++) {
            for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring || out.size() >= max) continue;
                int x = x0 + i * 4, z = z0 + j * 4;
                int[] at = spotIn(kind, x, z);
                if (at == null) continue;
                boolean far = true;
                for (int[] o : out) far &= Math.hypot(o[0] - at[0], o[2] - at[2]) >= apart;
                if (far) out.add(at);
            }
        }
        return out;
    }

    private int[] spotIn(String kind, int x, int z) {
        Col c = column(x, z);
        int lo, hi, want;
        switch (kind) {
            case "warrens", "chambers", "mouth", "frozen" -> {
                if (kind.equals("mouth") && c.mouth < 0.9 || kind.equals("frozen") && !(c.forest && c.cold)) return null;
                if ((kind.equals("warrens") || kind.equals("chambers")) && !c.forest) return null;
                int i = c.wOn[0] && c.wLine[0] < 0.5 * c.wHw[0] ? 0 : c.wOn[1] && c.wLine[1] < 0.5 * c.wHw[1] ? 1 : -1;
                if (i < 0 || kind.equals("mouth") && i != 0 || kind.equals("chambers") && c.wRoom[i] < 0.6) return null;
                lo = floorLevel(c, i);
                hi = (int) Math.ceil(c.wYc[i] + c.wHh[i]);
                want = WARRENS;
            }
            case "rifts", "sky" -> {
                if (!c.rOn || c.rLine > 2 || kind.equals("sky") && !c.rSky) return null;
                lo = c.rFloor + 2;
                hi = (int) Math.ceil(c.rTop);
                want = RIFTS;
            }
            case "halls" -> {
                if (!c.hOn || c.hH < 12) return null;
                lo = c.hFloor;
                hi = (int) Math.ceil(c.hCeil);
                want = HALLS;
            }
            case "sinkholes", "hollows" -> {
                if (!c.sOn || c.sScore < 0.55 || kind.equals("hollows") != c.desert) return null;
                lo = c.sLake + 1;
                hi = (int) Math.ceil(c.gF);
                want = SINKHOLES;
            }
            default -> {
                return null;
            }
        }
        for (int y = lo; y <= hi; y++) if (kindAt(x, y, z) == want) return new int[]{x, y, z};
        return null;
    }

    // ================================================================================================ helpers

    static double smooth(double t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    static double lerp(double t, double a, double b) { return a + t * (b - a); }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
