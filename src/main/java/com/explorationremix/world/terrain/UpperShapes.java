package com.explorationremix.world.terrain;

/**
 * The Upper Caves grown from noise (0.13, Taran's pick of 2026-11-11: all four characters, layered by depth), carved
 * into the pack's own caves above its old floor in place of the planned entrances ({@link Entrances}) and set pieces
 * ({@link Upper}). Nothing here is placed piece by piece: every shape is a function of world coordinates, and the
 * pack's caves (Tectonic's) stay underneath as the base.
 * <ul>
 *     <li><b>Root warrens</b>, 5 to 45 blocks under the ground everywhere: two rolling sheets of tunnels along the zero
 *     lines of two crossing 2D fields per sheet, so they loop and fork but never end blind; the top one just under the
 *     soil, the lower one deeper. They swell into root chambers, roots hang from their ceilings under trees, and pools
 *     lie in the chambers' floors. Under forests the top sheet rises to the ground in places (the mouths: rooty under
 *     warm forests, snowed in under cold ones), and here and there it dips down to the lower sheet (the links). They are
 *     carved into the ground's own rock (see {@code DeepSurfaceRules.HostRule}); only their floors are dressed: the
 *     Root Vaults' under forests, the karst's under plains, sand under deserts.</li>
 *     <li><b>Rifts</b>, deep down at the bottom of the Upper Caves, close to the Blue Reaches (Taran, 2026-11-12): tall
 *     cracks along the zero lines of two slow 2D fields, about 10 across and 35 tall (half 0.13's size), their floors
 *     about {@link #RIFT_FLOOR} over the old floor, their walls stepping back in ledges every six blocks, a stream sunk
 *     into the floor's flat stretches (each held by a dry lip where the floor steps down), and springs high on the walls
 *     whose water falls into it. They never reach the ground, and they pinch shut short of the den gates' works
 *     ({@link Dens}), whose own rifts go on down into the Blue Reaches ({@code Terrain.denRift}). The Silver Fern Rift's
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
 * <p>Still water (pools, the rifts' streams, the sinkholes' lakes) only lies where the rock round it holds it: every
 * block beside it and under it is rock to every shape ({@link #holds}), and the pack's caves and the canyons may not
 * open that rim ({@link #rim}). The pack's own water next to the shapes' open space turns to rock instead of standing
 * as a wall (see {@code NoiseBasedAquiferMixin}). Only the springs flow.
 *
 * <p>Where the shapes meet the pack's own caves ({@link Pack}, Tectonic's) through rock {@link #SEAM} blocks thick or
 * less, one over the other, that rock falls away and the two open into one cave (Taran, 2026-11-17: "merge into one
 * cave"); see {@link #seam}. Still water lies only where the pack's caves leave the rock round it whole
 * ({@link #packHolds}), and the roots, icicles, stalactites and stalagmites only where they keep thick rock over (or
 * under) them ({@link #anchored}), so nothing is left on a crust over the pack's caves.
 *
 * <p>All of it is a pure function of the seed, the surface ({@link Overhead}) and the pack's density: safe on any
 * thread, the same on every call. Everything a column needs that does not depend on y is computed once per column and
 * cached per thread, and so is what the merge asks at each height.
 */
public final class UpperShapes {
    public static final int WARRENS = 0, RIFTS = 1, HALLS = 2, SINKHOLES = 3;
    public static final String[] NAMES = {"warrens", "rifts", "halls", "sinkholes"};
    /** What {@link #fluid} puts in an open block: air, still water, or a spring's water (which flows). */
    public static final int AIR = 0, WATER = 1, SPRING = 2;
    /** Signed distance returned where nothing is near. */
    public static final double SOLID = 64;
    /**
     * How far a rift's open space reaches from its crack's middle line, per unit of its scale: its widest half width
     * (with the ledges) and the roughness, so no rift block lies further out.
     */
    static final double RIFT_REACH = 7.5;
    /** A rift's half width at its floor and top, and how much the middle of its height adds (per unit of its scale). */
    static final double RIFT_EDGE = 1.3, RIFT_BELLY = 3.7;
    /**
     * A rift's floor lies about this far over the pack's old floor (give or take 8, never under {@link #RIFT_FLOOR_MIN}),
     * and its top about {@link #RIFT_HEIGHT} over its floor (give or take 7, and at least 24 under the ground): the rifts
     * lie at the bottom of the Upper Caves, close to the Blue Reaches (Taran, 2026-11-12).
     */
    static final double RIFT_FLOOR = 8, RIFT_FLOOR_MIN = 4, RIFT_HEIGHT = 35;
    /**
     * A rift pinches shut short of the den gates' works above the old floor (their landings, rifts and ramps, which lie
     * as deep): its crack narrows from {@link #RIFT_KEEP} + {@link #RIFT_EASE} across the ground from the nearest of them
     * and is shut by {@link #RIFT_KEEP}, past both reaches and a wall of rock between.
     */
    static final double RIFT_KEEP = 30, RIFT_EASE = 18;
    /**
     * The cracks run on in a web under all of the Upper Caves. A slow field cuts the web into separate rifts (Taran,
     * 2026-11-17: "not quite as long so that they don't take up so much of the Upper Caves"): where the field, over
     * {@link #RIFT_CUT_SCALE} blocks, falls under {@link #RIFT_CUT} the crack pinches shut, narrowing over
     * {@link #RIFT_CUT_EASE} of the field as it does short of the den gates. Measured over 3,200 blocks square: the rifts
     * keep about half the ground they had, in separate rifts, half of them under 150 blocks across and nine in ten under
     * 470, where the web ran on across it all.
     */
    static final double RIFT_CUT_SCALE = 220, RIFT_CUT = -0.05, RIFT_CUT_EASE = 0.15;
    /**
     * Rock this thick or thinner between the shapes' open space and the pack's caves, one over the other, falls away
     * (see {@link #seam}): about 3 blocks (Taran, 2026-11-17).
     */
    public static final int SEAM = 3;
    /** What {@link #seamKind} says of a block: no seam, our floor over the pack's cave, or the pack's floor over our ceiling. */
    public static final int NO_SEAM = 0, OVER_PACK = 1, UNDER_PACK = 2;
    /** The carve in a seam's rock once it falls away. */
    private static final double SEAM_OPEN = -0.5;
    /**
     * Only rock the shapes' distance puts closer than this to their open space can be a seam (the distance runs a little
     * short of the true one in places, so this leaves room over {@link #SEAM}).
     */
    private static final double SEAM_GATE = SEAM + 4;
    /** What a block is to the merge: rock to both, open in a shape, open in the pack's caves, or what stops a seam. */
    private static final int ROCK = 0, OURS = 1, PACK_OPEN = 2, STOP = 3;

    /** What a formation block is made of (see {@link #formation}). */
    public static final int ROOT = 1, ICICLE = 2, STALACTITE = 3, STALAGMITE = 4, DRIP = 5;
    /** The biome of a rift or hall (see {@link #piece}). */
    public static final int PIECE_RIFT = 1, PIECE_HALL = 2, PIECE_COLD_HALL = 3;

    /** Scale Java's noise to the spread of the round-1 previews' noise (values 2D and 3D). */
    static final double K2 = 1.381, K3 = 1.143;
    /**
     * Where each warren sheet lies under the ground (its middle), and how far it rolls up and down. The top sheet keeps
     * close under the soil (0.14, Taran: the Root Vaults sit just under the dirt and rooted dirt), so most of its roofs
     * are the soil's rooted dirt; the lower one rolls deep.
     */
    static final double[] SHEET_MID = {12, 31}, SHEET_AMP = {5, 13};
    /**
     * No floor, pool or stream of the warrens, halls and sinkholes below this far over the pack's old floor: clear of the
     * den gates' landings and ramps. The rifts lie below it, kept clear of the den gates across the ground instead
     * ({@link #RIFT_KEEP}).
     */
    static final int FLOOR_CLEARANCE = 56;
    /** The sinkholes' field's scale: about 45 pits a square kilometre over plains and deserts, one every 150 blocks or so. */
    static final double PIT_SCALE = 72;
    /**
     * The roots of the trees overhead through the Root Vaults (0.18.6): strands of their wood wind over the warrens'
     * ceilings and walls ({@link #strand}) as thick as they come down to {@link #STRAND_FULL} under the ground, thinning
     * out to none at {@link #STRAND_DEPTH}; and in the wider rooms a few come down to the floor as twisted pillars
     * ({@link #pillarDist}), at most one a sheet in each {@link #PILLAR_CELL}-block cell.
     */
    static final double STRAND_FULL = 14, STRAND_DEPTH = 30;
    static final int PILLAR_CELL = 20;
    /** How far across a pillar's roots may spread from its middle. */
    private static final double PILLAR_REACH = 5;
    /** How many spots of its cell a pillar tries: the wider rooms are few, so most spots miss them. */
    private static final int PILLAR_TRIES = 16;
    private static final int NO_Y = Integer.MIN_VALUE;

    /**
     * How far across the ground a column lies from the den gates' works above the old floor (their upper landings, rifts
     * and ramps; {@code Terrain.denClearance}), which the rifts keep clear of. Safe on any thread.
     */
    public interface Dens {
        double clearance(int x, int z);
    }

    /**
     * The pack's own final density at a block, as the world builds it (positive in rock, negative in its caves and in the
     * open air), without anything of ours. Safe on any thread. See {@code PackProbe}.
     */
    public interface Pack {
        double density(int x, int y, int z);
    }

    public final long seed;
    public final int packFloor;
    public final Overhead overhead;
    /** The den gates the rifts keep clear of, or null for none (tests and tools). */
    private final Dens dens;
    /** The pack's caves the shapes merge with, or null to leave them a plain overlay (tests, tools, and the config's choice). */
    private final Pack pack;
    public final boolean warrens, rifts, halls, sinkholes;
    /** Vanilla's worm tunnels (the cave carver) above the old floor: off unless asked for (the canyons stay). */
    public final boolean wormTunnels;
    private final int minFloor;

    private final Perlin[] wA = new Perlin[2], wB = new Perlin[2], wC = new Perlin[2], wD = new Perlin[2];
    private final Perlin mouthN, linkN, rootN, lookN, poolN, chillN, strandN, strandM, roofN, holeN;
    private final Perlin rA, rB, rC, rD, rE;
    private final Perlin hA, hB, hC, hD, hE, hS, hK;
    private final Perlin sA, sB;
    private final Perlin leanN, roughN;

    private final ThreadLocal<Col[]> cols = ThreadLocal.withInitial(() -> {
        Col[] a = new Col[1024];
        for (int i = 0; i < a.length; i++) a[i] = new Col();
        return a;
    });
    private final ThreadLocal<double[]> grads = ThreadLocal.withInitial(() -> new double[2]);
    /** Per thread, a wall's normal for the roots (strand). */
    private final ThreadLocal<double[]> normals = ThreadLocal.withInitial(() -> new double[3]);
    private final ThreadLocal<Cells> cells = ThreadLocal.withInitial(Cells::new);

    public UpperShapes(long seed, int packFloor, Overhead overhead, Dens dens, Pack pack, boolean warrens, boolean rifts, boolean halls,
                       boolean sinkholes, boolean wormTunnels) {
        this.seed = seed;
        this.packFloor = packFloor;
        this.overhead = overhead;
        this.dens = dens;
        this.pack = pack;
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
        strandN = noise(s, 46);
        strandM = noise(s, 47);
        roofN = noise(s, 48);
        holeN = noise(s, 49);
        rA = noise(s, 50);
        rB = noise(s, 51);
        rC = noise(s, 52);
        rD = noise(s, 53);
        rE = noise(s, 54);
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

    /** All four shapes, worm tunnels off (tests and tools); {@code dens} may be null. */
    public UpperShapes(long seed, int packFloor, Overhead overhead, Dens dens) {
        this(seed, packFloor, overhead, dens, null, true, true, true, true, false);
    }

    /** All four shapes, worm tunnels off, merged with the pack's caves (tests and tools); {@code dens} may be null. */
    public UpperShapes(long seed, int packFloor, Overhead overhead, Dens dens, Pack pack) {
        this(seed, packFloor, overhead, dens, pack, true, true, true, true, false);
    }

    /** True where the shapes merge with the pack's caves ({@link #SEAM}). */
    public boolean merging() { return pack != null; }

    /** All four shapes, worm tunnels off, no den gates (tests and tools). */
    public UpperShapes(long seed, int packFloor, Overhead overhead) {
        this(seed, packFloor, overhead, null);
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
        /** Across the ground, the way out from the middle line of sheet i's nearest tunnel (a unit vector; see strand). */
        final double[] wNx = new double[2], wNz = new double[2];
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
        /** How thickly root strands run over the column's warren walls, 0 (none) to 1 (see {@link #strand}). */
        double strandK;
        /** Per sheet: 0 not known yet, 1 the pack's caves keep clear of its walls here (strands may run there), 2 not. */
        final byte[] strandSafe = new byte[2];
        /** The twisted root pillar within reach of the column, per sheet, or null; worked out on first use. */
        boolean pilDone;
        final Pillar[] pil = new Pillar[2];
        boolean poolDone;
        /** The y of a pool block sunk under a warren floor, per sheet, or NO_Y. */
        final int[] wPool = new int[2];

        // ---- rifts
        boolean rOn;
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
        /** The pit's middle: the peak of the pit field's slow part, on the 4-block lattice (its lake and spring). */
        int sCx, sCz;
        boolean sSpringDone;
        int sSpringY;

        // ---- still water (see waterLevels): the heights the column holds water at, and where its rim may lie
        boolean waterDone, rimDone, rPoolDone, rPool;
        int waterN, rimLo, rimHi;
        final int[] waterY = new int[6];

        // ---- the merge with the pack's caves (see seam), per height from sBase over sLen heights, set up on first use:
        // what each height is (0 not known yet, else 1 + NO_SEAM, OVER_PACK or UNDER_PACK), the pack's density there and
        // the shapes' carve before the merge (NaN not known yet)
        boolean sReady;
        int sBase, sLen;
        byte[] sKind;
        float[] sPack, sCarve;
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
        c.wet = sea(x, z);
        c.cold = s.cold();
        // under the sea the warrens are plain caves: no Root Vaults, karst or hollows (Taran, 2026-11-17: "make ocean
        // biomes not have root vaults")
        c.forest = !c.wet && s.cover() == Overhead.Cover.FOREST;
        c.plains = !c.wet && s.cover() == Overhead.Cover.PLAINS;
        c.red = !c.wet && s.cover() == Overhead.Cover.BADLANDS;
        c.desert = !c.wet && s.cover() == Overhead.Cover.DESERT || c.red;
        c.tropical = c.forest && s.trees().tropical();
        c.lean = clamp(1.2 * K2 * leanN.noise2(x / 600.0, z / 600.0), -1, 1);
        c.lo = 1e9;
        c.hi = -1e9;
        c.fKind = 0;
        c.strandK = 0;
        c.strandSafe[0] = c.strandSafe[1] = 0;
        c.pilDone = false;
        c.pil[0] = c.pil[1] = null;
        c.poolDone = false;
        c.springDone = false;
        c.sSpringDone = false;
        c.hPoolDone = false;
        c.waterDone = false;
        c.rimDone = false;
        c.rPoolDone = false;
        c.stalBottom = Double.NaN;
        c.miteTop = NO_Y;
        warrenColumn(c);
        c.rOn = rifts && riftColumn(c);
        c.hOn = halls && hallColumn(c);
        c.sOn = sinkholes && sinkColumn(c);
        c.lo -= 6;
        c.hi += 6;
        c.sReady = false;
        c.sBase = (int) Math.floor(c.lo) - 2;
        c.sLen = c.lo > c.hi ? 0 : (int) Math.ceil(c.hi) - c.sBase + 3;
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

    /**
     * True where the ground of (x, z)'s 4x4 column lies under the sea: an ocean, river or beach, or ground under the sea
     * level with one of those within 16 blocks (the pack floods forests along its coasts; inland swamps keep theirs).
     */
    private boolean sea(int x, int z) {
        Overhead.Surface s = overhead.at(x, z);
        if (s.wet()) return true;
        if (s.y() >= overhead.seaLevel()) return false;
        for (int dx = -16; dx <= 16; dx += 8)
            for (int dz = -16; dz <= 16; dz += 8)
                if ((dx != 0 || dz != 0) && overhead.at(x + dx, z + dz).wet()) return true;
        return false;
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

    private static final int GATE_MOUTH = 0, GATE_PIT = 1;

    /** How much of the ground round a column (its 3 x 3 surface columns) suits a kind of opening, eased to 0..1. */
    private double gate(int x, int z, int kind) {
        int n = 0;
        int sea = overhead.seaLevel();
        for (int dx = -4; dx <= 4; dx += 4) for (int dz = -4; dz <= 4; dz += 4) {
            Overhead.Surface s = overhead.at(x + dx, z + dz);
            if (s.wet() || s.y() < sea + 2) continue;
            boolean ok = kind == GATE_MOUTH ? s.cover() == Overhead.Cover.FOREST
                    : s.cover() == Overhead.Cover.PLAINS || s.cover() == Overhead.Cover.DESERT || s.cover() == Overhead.Cover.BADLANDS;
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
        double g1x = (g[0] * (1 + wxx) + g[1] * wzx) / 70.0, g1z = (g[0] * wxz + g[1] * (1 + wzz)) / 70.0;
        double l1 = Math.abs(f1) / Math.max(Math.hypot(g1x, g1z), 0.53 / 70.0);
        double f2 = wC[i].noise2((x - wz) / 85.0 + 9, (z + wx) / 85.0 + 9, g);
        double g2x = (g[0] * (1 - wzx) + g[1] * wxx) / 85.0, g2z = (-g[0] * wzz + g[1] * (1 + wxz)) / 85.0;
        double l2 = Math.abs(f2) / Math.max(Math.hypot(g2x, g2z), 0.53 / 85.0);
        c.wLine[i] = Math.min(l1, l2);
        // the way out from the line: up the slope of |f| (0.18.6, for the roots, which stand straight out of the walls)
        double nx = l1 <= l2 ? Math.signum(f1) * g1x : Math.signum(f2) * g2x, nz = l1 <= l2 ? Math.signum(f1) * g1z : Math.signum(f2) * g2z;
        double nn = Math.hypot(nx, nz);
        c.wNx[i] = nn > 1e-12 ? nx / nn : 1;
        c.wNz[i] = nn > 1e-12 ? nz / nn : 0;
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
        // where the cap is lifted the tunnel opens as a sunken way: wider, and tall enough that its roof meets the lifted
        // cap a few blocks over the ground (the pack's real ground sits a block or two over the estimate here), so the
        // opening is wide at the real ground, the floor about 5 blocks under it and the sides overhanging a little
        double mw = c.mouth;
        c.wHw[0] += 2.5 * mw;
        c.wHh[0] += 2.7 * mw;
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
        if (c.forest && warrens) {
            formation(c);
            if (c.wOn[0] || c.wOn[1]) c.strandK = clamp((K2 * strandM.noise2(x / 56.0, z / 56.0) + 0.45) / 0.6, 0, 1);
        }
    }

    /** How far a mouth lets the top sheet climb from its own depth toward the ground, in blocks. */
    static final double MOUTH_RISE = 20;
    /** How much soil a roofed mouth's tunnel keeps over it, between its openings (see {@link #roofed}). */
    static final double ROOFED_DEPTH = 3.5;

    /**
     * 0..1: how far the mouths at a spot are short roofed ones rather than long sunken ways (0.18.6, Taran: "a mix by
     * place"), by a slow field, so a forest's ways in are mostly the one or the other and the next forest's may differ.
     */
    double roofed(int x, int z) { return smooth((-0.05 - K2 * roofN.noise2(x / 260.0, z / 260.0)) / 0.08); }

    /**
     * 0..1: the patches where a roofed mouth opens, some 30 blocks across: the caps climb out of the roof at their slope,
     * so only the middle opens to the sky, typically some 5 by 7 blocks and up to about 10 by 15, and the way falls
     * back under a roof of soil on either side (smaller patches left only skylights in the pack, whose real ground lies a
     * block or two over the estimate).
     */
    private double holes(int x, int z) { return smooth((K2 * holeN.noise2(x / 40.0 + 5, z / 40.0 + 5) - 0.05) / 0.15); }

    /** 0..1: how far the top sheet climbs toward the ground at a column (a slow field, whatever the ground). */
    private double mouthRamp(int x, int z) {
        double v = K2 * mouthN.noise2(x / 70.0, z / 70.0);
        return v > 0.15 ? smooth((v - 0.15) / 0.45) : 0;
    }

    // ------------------------------------------------------------------------------------------------ roof caps

    /**
     * How steeply a warren roof's cap may fall or climb, in blocks per block along x or z (about 1 on a diagonal, so a
     * tunnel never climbs more than a block per block): near a valley the tunnels sink under it on a slope no steeper
     * than this, and they climb into a mouth the same way, never down a chute. A mouth opens where the forest floor
     * falls no faster than this for the caps' reach round it.
     */
    static final double ROOF_SLOPE = 0.7;
    /**
     * How far round a cell the caps look for lower ground and for mouths, in 4x4 cells (56 blocks): ground more than
     * 39 blocks (the slope over this reach) under a cap's would still bring a step where it comes into reach.
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
            k.cap[i] = overhead.low(bx, bz) - (sea(bx, bz) ? 9 : 5);
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
        if (warrens && s.cover() == Overhead.Cover.FOREST && !sea(bx, bz)) {
            double e = mouthRamp(bx, bz);
            if (e > 0) e *= gate(bx, bz, GATE_MOUTH);
            double top = s.y() + 4;
            // the ways in mixed by place (Taran, 18 Nov): where a slow field is low a mouth's tunnel keeps a roof of soil
            // ROOFED_DEPTH thick under the forest floor, opening only in patches (the caps' slope rounds each into a
            // short mouth, its roof dipping under the trees on either side); elsewhere it opens all along, a long
            // sunken way
            if (e > 0) {
                double roofed = roofed(bx, bz);
                if (roofed > 0) top = lerp(roofed * (1 - holes(bx, bz)), top, s.y() - ROOFED_DEPTH);
            }
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
            // only from thick rock: none where the pack's caves come close over the ceiling
            if (!anchored(c.x, c.z, (int) Math.floor(ceil) + 1, 1, c.gF)) continue;
            c.fKind = ice ? ICICLE : ROOT;
            c.fBottom = Math.max(ceil - 0.5 - len, floorTop + 2.0);
            c.fTop = ceil + 1.5;
            c.hi = Math.max(c.hi, c.fTop + 1);
            return;
        }
    }

    // ------------------------------------------------------------------------------------------------ roots

    /**
     * True where a block belongs to a root strand over sheet i's ceiling or walls (0.18.6). The strands lie where the
     * zero surface of a 3D field cuts the tunnel's shell, so each runs along the rock wherever it goes, winding over a
     * ceiling and down a wall to the floor's edge, and now and then dives into the rock and comes out further on. The
     * field changes slower up and down than across, so its zero surfaces stand up and on the walls the strands mostly
     * run down. How thick they are is the column's ({@link Col#strandK}): up to two blocks across, standing out of the
     * rock by up to two along their middle, where the strands are thick, and thin as a block where they are thin. They
     * run on a little into the rock, dressed as wood there too, so they never end flat on a wall. None across a floor,
     * in the snowy zone's ice, under {@link #STRAND_DEPTH}, inside the other sheet's tunnels, or where the pack's caves
     * come near the walls (they would cut it loose).
     */
    private boolean strand(Col c, int i, int x, int y, int z) {
        if (c.strandK <= 0 || !c.wOn[i]) return false;
        double[] n = normals.get();
        // each block of a strand out of the wall rests face to face on the next one in toward the wall (along the wall's
        // normal's largest part), and so on to the wall itself, so none hangs on by an edge where the wall slopes; each
        // is judged in its own column, as it is when asked about itself
        Col at = c;
        int bx = x, by = y, bz = z;
        for (int step = 0; ; step++) {
            double e = strandBlock(at, i, bx, by, bz, step == 0, n);
            if (e != e) return false;
            if (e >= 0) return true;
            if (step == 3) return false;
            double ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
            if (ay >= ax && ay >= az) by += n[1] > 0 ? 1 : -1;
            else {
                if (ax >= az) bx += n[0] > 0 ? 1 : -1;
                else bz += n[2] > 0 ? 1 : -1;
                at = bx == c.x && bz == c.z ? c : side(bx, bz);
            }
        }
    }

    /**
     * For {@link #strand}: how far a block lies out of sheet i's wall at its column (negative in the open), with the
     * wall's outward normal there in {@code n}, where the block is a part of a strand; NaN where it is none. Past the
     * first block asked about, a block in the wall will do (the strand rests on it).
     */
    private double strandBlock(Col c, int i, int x, int y, int z, boolean first, double[] n) {
        if (!c.wOn[i]) return first || othersOpen(c, x, y, z) || c.wOn[1 - i] && warrenDist(c, 1 - i, x, y, z) < 0 ? Double.NaN : 1;
        double yy = y + 0.5, hh = c.wHh[i], hw = c.wHw[i], floorTop = c.wYc[i] - 0.6 * hh;
        double dy = (yy - c.wYc[i]) / hh, dxz = c.wLine[i] / hw;
        double e = (Math.sqrt(dy * dy + dxz * dxz) - 1) * Math.min(hh, hw);
        // the walls' roughening (as warrenDist roughens them) moves them by under 0.6
        if (first && (e < -2.6 || e > 1.3)) return Double.NaN;
        if (e > -3 && e < 3) e += 0.5 * K3 * roughN.noise3(x / 6.0, y / 6.0, z / 6.0);
        if (!first && e >= 0)
            return othersOpen(c, x, y, z) || c.wOn[1 - i] && warrenDist(c, 1 - i, x, y, z) < 0 ? Double.NaN : e;
        double depth = c.gF - yy;
        if (c.strandK <= 0 || depth < 1.5 || depth > STRAND_DEPTH) return Double.NaN;
        // on the walls and ceilings only: not under the floor, nor where the floor is nearer than the wall, nor over the
        // middle of a floor (a narrow tunnel's floor lies close to its arch)
        if (e < -2.0 || e > 0.7 || floorTop - yy > e || yy < floorTop - 1 || e < 0 && yy - floorTop < 1.5 && dxz < 0.75) return Double.NaN;
        // the strand is worked out at the point of the wall under the block, straight in along the tunnel's normal, so
        // it is the same all the way out from the wall
        double nl = dxz / hw, ny = dy / hh, nn = Math.hypot(nl, ny);
        if (nn < 1e-6) return Double.NaN;
        n[0] = nl / nn * c.wNx[i];
        n[1] = ny / nn;
        n[2] = nl / nn * c.wNz[i];
        double wx = x + 0.5 - e * n[0], wy = yy - e * n[1], wz = z + 0.5 - e * n[2];
        double k = c.strandK * (depth <= STRAND_FULL ? 1 : (STRAND_DEPTH - depth) / (STRAND_DEPTH - STRAND_FULL));
        // the strands fade out and in again along their length, so they end and fork rather than net the whole vault
        double t = (0.06 + 0.12 * k) * clamp(0.5 + 1.6 * K3 * strandM.noise3(wx / 22.0 + 31, wy / 22.0, wz / 22.0 + 31), 0, 1);
        if (t <= 0.01) return Double.NaN;
        double g = Math.abs(K3 * strandN.noise3(wx / 10.0, wy / 17.0, wz / 10.0));
        if (g >= t) return Double.NaN;
        double f = g / t, out = (0.5 + 1.5 * k) * Math.sqrt(1 - f * f);
        if (e < -out) return Double.NaN;
        if (c.cold && chillAt(c, yy) < 3 || othersOpen(c, x, y, z) || c.wOn[1 - i] && warrenDist(c, 1 - i, x, y, z) < -0.3) return Double.NaN;
        return strandSafe(c, i) ? e : Double.NaN;
    }

    private static final int SIDE_SLOTS = 16;
    /** Per thread, the columns beside the ones asked about, for the roots (side), apart from the main cache. */
    private final ThreadLocal<Col[]> sides = ThreadLocal.withInitial(() -> {
        Col[] a = new Col[SIDE_SLOTS];
        for (int k = 0; k < a.length; k++) a[k] = new Col();
        return a;
    });

    /**
     * A column worked out on its own copy, kept a while per thread outside the main cache (so it never pushes out a
     * column an asker holds): the next column in toward a wall, for a root strand ({@link #strand}).
     */
    private Col side(int x, int z) {
        Col c = sides.get()[((x & 3) << 2) | (z & 3)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.x = x;
        c.z = z;
        c.valid = true;
        fill(c);
        return c;
    }

    /**
     * Whether the pack's caves keep clear of sheet i's walls at a column (asked once): rock to the pack from under the
     * floor to {@link #SEAM} + 1 over the roof, in the column and two blocks out each way, so no strand there is left
     * hanging in the pack's open space, nor kept as a stub in it.
     */
    private boolean strandSafe(Col c, int i) {
        if (pack == null) return true;
        if (c.strandSafe[i] == 0) {
            byte s = 1;
            int lo = (int) Math.floor(c.wYc[i] - 0.6 * c.wHh[i]) - 1;
            int hi = Math.min((int) Math.ceil(c.wYc[i] + c.wHh[i]) + SEAM + 1, (int) Math.floor(c.gF) - 2);
            for (int k = 0; k < 5 && s == 1; k++) {
                int nx = c.x + (k == 1 ? 2 : k == 2 ? -2 : 0), nz = c.z + (k == 3 ? 2 : k == 4 ? -2 : 0);
                for (int y = lo; y <= hi; y += 2)
                    if (pack.density(nx, y, nz) < 0) {
                        s = 2;
                        break;
                    }
            }
            c.strandSafe[i] = s;
        }
        return c.strandSafe[i] == 1;
    }

    /**
     * A twisted root pillar, where a root of the trees overhead comes down to a vault's floor: its middle, the floor and
     * roof of its room there, the girth of its core, how many strands twist round the core, and their twist.
     */
    static final class Pillar {
        final int sheet, strands;
        final double x, z, floor, ceil, girth, phase, turn;

        Pillar(int sheet, double x, double z, double floor, double ceil, double girth, double phase, double turn, int strands) {
            this.sheet = sheet;
            this.x = x;
            this.z = z;
            this.floor = floor;
            this.ceil = ceil;
            this.girth = girth;
            this.phase = phase;
            this.turn = turn;
            this.strands = strands;
        }
    }

    private static final Pillar NO_PILLAR = new Pillar(-1, 0, 0, 0, 0, 0, 0, 0, 0);
    private static final int PILLAR_SLOTS = 64;
    /** Per thread, the pillars of the cells asked about lately, by cell and sheet. */
    private final ThreadLocal<long[]> pillarKeys = ThreadLocal.withInitial(() -> {
        long[] a = new long[PILLAR_SLOTS];
        java.util.Arrays.fill(a, Long.MIN_VALUE);
        return a;
    });
    private final ThreadLocal<Pillar[]> pillarVals = ThreadLocal.withInitial(() -> new Pillar[PILLAR_SLOTS]);

    /** A cell's pillar on sheet i, or null. */
    Pillar pillarOf(int cx, int cz, int i) {
        long key = ((long) cx << 33) ^ ((cz & 0xFFFFFFFFL) << 1) ^ i;
        int slot = ((cx * 7 + cz * 3) * 2 + i) & (PILLAR_SLOTS - 1);
        long[] keys = pillarKeys.get();
        Pillar[] vals = pillarVals.get();
        if (keys[slot] != key) {
            Pillar p = makePillar(cx, cz, i);
            keys[slot] = key;
            vals[slot] = p == null ? NO_PILLAR : p;
        }
        Pillar p = vals[slot];
        return p == NO_PILLAR ? null : p;
    }

    /**
     * Where a cell's pillar on sheet i stands, if it has one: a spot of the cell (kept off its edges) in one of the
     * forest warrens' wider rooms, off the room's middle so its way stays open and clear of its walls, under at most
     * {@link #STRAND_DEPTH} - 4 of ground, its room at least six blocks high; not over a pool, in a mouth or a link, in
     * the snowy zone, near another shape's open space or the other sheet's, or where the pack's caves come near it.
     * Reads the column on its own copy, never the cache.
     */
    private Pillar makePillar(int cx, int cz, int i) {
        long h = Hashing.mix(seed ^ 0x52007A1CL, cx, cz, i);
        for (int t = 0; t < PILLAR_TRIES; t++) {
            Pillar p = pillarTry(Hashing.mix(h, 8, t, 0), cx, cz, i);
            if (p != null) return p;
        }
        return null;
    }

    /** One try for a cell's pillar (see {@link #makePillar}). */
    private Pillar pillarTry(long h, int cx, int cz, int i) {
        int px = cx * PILLAR_CELL + 4 + (int) (Hashing.unit(Hashing.mix(h, 1, 0, 0)) * (PILLAR_CELL - 8));
        int pz = cz * PILLAR_CELL + 4 + (int) (Hashing.unit(Hashing.mix(h, 2, 0, 0)) * (PILLAR_CELL - 8));
        Col m = scratch(px, pz);
        if (!m.forest || !m.wOn[i] || m.mouth > 0.02 || m.link > 0.2) return null;
        // off the middle line toward one wall, leaving a way past it on the other side
        double lat = m.wLine[i], hw = m.wHw[i], q = lat / hw;
        if (hw < 3.5 || lat < 1 || lat > hw - 0.5) return null;
        double ceil = m.wYc[i] + m.wHh[i] * Math.sqrt(1 - q * q), floor = m.wYc[i] - 0.6 * m.wHh[i];
        if (ceil - floor < 5.5 || m.gF - ceil < 3 || m.gF - ceil > STRAND_DEPTH - 4) return null;
        if (m.cold && chillAt(m, ceil) < 3) return null;
        // no pool round its foot (warrenPools sinks them where this field is high)
        for (int k = 0; k < 9; k++) {
            double ax = px + (k == 0 ? 0 : 4 * Math.cos(k * Math.PI / 4)), az = pz + (k == 0 ? 0 : 4 * Math.sin(k * Math.PI / 4));
            if (K2 * poolN.noise2(ax / 9.0, az / 9.0) >= 0.25) return null;
        }
        for (int y = (int) Math.floor(floor); y <= ceil; y += 2) {
            if (m.rOn && riftDist(m, px, y, pz) < 3 || m.hOn && hallDist(m, px, y, pz) < 3 || m.sOn && sinkDist(m, px, y, pz) < 3) return null;
            if (m.wOn[1 - i] && warrenDist(m, 1 - i, px, y, pz) < 2) return null;
        }
        // the way past it: open from four to seven blocks out on the far side from its wall, at its feet and halfway up
        // (read off the columns there, which a bend or a crossing tunnel may narrow)
        int low = (int) Math.floor(floor + 1), mid = (int) Math.floor((floor + ceil) / 2);
        for (int r = 4; r <= 7; r++) {
            int bx = (int) Math.floor(px + 0.5 - r * m.wNx[i]), bz = (int) Math.floor(pz + 0.5 - r * m.wNz[i]);
            Col b = scratch(bx, bz);
            if (!b.wOn[i] || warrenDist(b, i, bx, low, bz) > -0.5 || warrenDist(b, i, bx, mid, bz) > -0.5) return null;
        }
        if (pack != null)
            for (int k = 0; k < 5; k++) {
                int nx = px + (k == 1 ? 3 : k == 2 ? -3 : 0), nz = pz + (k == 3 ? 3 : k == 4 ? -3 : 0);
                for (int y = (int) Math.floor(floor) - 2; y <= Math.min(ceil + SEAM + 1, m.gF - 2); y += 2)
                    if (pack.density(nx, y, nz) < 0) return null;
            }
        double girth = 0.75 + 0.35 * Hashing.unit(Hashing.mix(h, 3, 0, 0));
        double phase = 2 * Math.PI * Hashing.unit(Hashing.mix(h, 4, 0, 0));
        double turn = (Hashing.unit(Hashing.mix(h, 5, 0, 0)) < 0.5 ? 1 : -1) * 2 * Math.PI / (10 + 6 * Hashing.unit(Hashing.mix(h, 6, 0, 0)));
        int strands = Hashing.unit(Hashing.mix(h, 7, 0, 0)) < 0.6 ? 3 : 2;
        return new Pillar(i, px + 0.5, pz + 0.5, floor, ceil, girth, phase, turn, strands);
    }

    /** Finds the pillar within reach of a column on each sheet, once. */
    private void pillarsReady(Col c) {
        if (c.pilDone) return;
        c.pilDone = true;
        if (!c.forest || !warrens) return;
        int r = (int) Math.ceil(PILLAR_REACH);
        for (int i = 0; i < 2; i++) {
            if (!c.wOn[i]) continue;
            double best = PILLAR_REACH * PILLAR_REACH;
            for (int cx = Math.floorDiv(c.x - r, PILLAR_CELL); cx <= Math.floorDiv(c.x + r, PILLAR_CELL); cx++)
                for (int cz = Math.floorDiv(c.z - r, PILLAR_CELL); cz <= Math.floorDiv(c.z + r, PILLAR_CELL); cz++) {
                    Pillar p = pillarOf(cx, cz, i);
                    if (p == null) continue;
                    double dx = c.x + 0.5 - p.x, dz = c.z + 0.5 - p.z;
                    if (dx * dx + dz * dz < best) {
                        best = dx * dx + dz * dz;
                        c.pil[i] = p;
                    }
                }
        }
    }

    /**
     * How far a block lies from sheet i's twisted root pillar near its column (negative inside), or {@link #SOLID}
     * where none reaches: from 1.5 blocks into the floor of the pillar's room to 3 over its roof.
     */
    private double pillarAt(Col c, int i, int x, int y, int z) {
        pillarsReady(c);
        Pillar p = c.pil[i];
        if (p == null) return SOLID;
        double yy = y + 0.5;
        if (yy < p.floor - 1.5 || yy > p.ceil + 3) return SOLID;
        return pillarDist(p, x, yy, z);
    }

    /**
     * Across, how far a block lies from a pillar at height yy (negative inside): a core swaying a little as it climbs,
     * with two or three strands twisting round it; core and strands thicken and the strands spread out near the floor
     * and the roof, so the pillar stands on root feet and fans out over the roof.
     */
    static double pillarDist(Pillar p, int x, double yy, int z) {
        double t = Math.max(yy - p.floor, 0), fb = Math.exp(-t / 1.8), ft = Math.exp(-Math.max(p.ceil - yy, 0) / 1.8);
        // the twist eases off down at the feet, which spread out wide (so each layer of a foot rests on the one under it)
        double ang = p.phase + p.turn * (t - 1.8 * (1 - fb));
        double px = x + 0.5 - p.x - 0.45 * Math.cos(0.5 * ang), pz = z + 0.5 - p.z - 0.45 * Math.sin(0.5 * ang);
        double d = Math.sqrt(px * px + pz * pz) - p.girth * (1 + 0.8 * fb + 0.5 * ft);
        double orbit = 1.25 + 1.8 * fb + 0.6 * ft, rs = 0.72 * (1 + 0.5 * fb + 0.3 * ft);
        for (int k = 0; k < p.strands; k++) {
            double a = ang + 2 * Math.PI * k / p.strands;
            double sx = px - orbit * Math.cos(a), sz = pz - orbit * Math.sin(a);
            d = Math.min(d, Math.sqrt(sx * sx + sz * sz) - rs);
        }
        return d;
    }

    /** True where a block is a root (strand or pillar) of sheet i, within its tunnels or up to 1.2 into their rock. */
    private boolean rootAt(Col c, int i, int x, int y, int z) {
        if (!c.wOn[i]) return false;
        if (strand(c, i, x, y, z)) return true;
        return c.forest && pillarAt(c, i, x, y, z) < 0 && warrenDist(c, i, x, y, z) <= 1.2;
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
     * only where no neighbour's floor lies lower and no other shape opens the rock round it (the water stays in its
     * basin, {@link #holds}).
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
            if (ok && holds(x, fl - 1, z, NO_Y) && packHolds(x, fl - 1, z)) c.wPool[i] = fl - 1;
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
        // the second set of cracks a little narrower (by as much as the cracks were halved, 0.14: by more, its narrow
        // stretches pinch shut and leave short slots that open onto nothing)
        c.rLine = Math.min(d1, d2 + 2);
        // wider where the region leans to rifts
        c.rScale = (0.8 + 0.25 * (K2 * rB.noise2(x / 70.0 + 3, z / 70.0 + 3) + 1)) * (1 - 0.25 * c.lean);
        if (c.rLine > RIFT_REACH * c.rScale + 3) return false;
        // cut into separate rifts, each narrowing to shut where the field falls under the cut
        double cut = smooth((K2 * rE.noise2(x / RIFT_CUT_SCALE + 31, z / RIFT_CUT_SCALE + 17) - RIFT_CUT) / RIFT_CUT_EASE);
        if (cut < 0.15) return false;
        c.rScale *= cut;
        if (c.rLine > RIFT_REACH * c.rScale + 3) return false;
        // narrowing to shut short of the den gates' works, which lie as deep
        if (dens != null) {
            double keep = smooth((dens.clearance(c.x, c.z) - RIFT_KEEP) / RIFT_EASE);
            if (keep < 0.15) return false;
            c.rScale *= keep;
            if (c.rLine > RIFT_REACH * c.rScale + 3) return false;
        }
        // deep down at the bottom of the Upper Caves, never reaching the ground
        double floor = Math.max(packFloor + RIFT_FLOOR + 6 * K2 * rB.noise2(x / 140.0 + 9, z / 140.0 + 9), packFloor + RIFT_FLOOR_MIN);
        int fq = 3 * (int) Math.ceil(floor / 3.0);
        double top = Math.min(fq + RIFT_HEIGHT + 5 * K2 * rB.noise2(x / 80.0, z / 80.0), Math.min(c.gC - 24, c.low - (c.wet ? 12 : 8)));
        if (top - fq < 12) return false;
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
        if (dV > 1) return Math.max(dV, c.rLine - RIFT_REACH * c.rScale);
        double t = clamp((yy - c.rFloor) / (c.rTop - c.rFloor), 0, 1);
        double lens = Math.sqrt(Math.sin(Math.PI * t));
        int step = (int) Math.floor((yy + c.rStep) / 6.0);
        double ledge = K3 * rC.noise3(x / 22.0, step * 1.7, z / 22.0);
        double width = (RIFT_EDGE + RIFT_BELLY * lens + 1.0 * ledge) * c.rScale;
        double d = c.rLine - width;
        if (d < 4 && d > -4) d += 0.7 * K3 * roughN.noise3(x / 7.0 + 40, y / 7.0, z / 7.0 + 40);
        return Math.max(d, dV);
    }

    /**
     * A spring high on a rift's wall: one candidate column in each 24-block square (some squares have none) that lies in
     * the wall at some height in the crack's upper half; its block there opens and holds water that falls down the
     * wall into the stream (the only water of the shapes that flows, {@link #SPRING}).
     */
    int spring(Col c) {
        if (c.springDone) return c.springY;
        c.springDone = true;
        c.springY = NO_Y;
        if (!c.rOn || c.rLine < RIFT_EDGE) return NO_Y;
        int cx = Math.floorDiv(c.x, 24), cz = Math.floorDiv(c.z, 24);
        long h = Hashing.mix(seed ^ 0x51F15EL, cx, cz, 7);
        if (Hashing.unit(h) > 0.45) return NO_Y;
        int sx = cx * 24 + 2 + (int) (Hashing.unit(Hashing.mix(h, 1, 0, 0)) * 20), sz = cz * 24 + 2 + (int) (Hashing.unit(Hashing.mix(h, 2, 0, 0)) * 20);
        if (sx != c.x || sz != c.z) return NO_Y;
        // the height where the crack's plain profile reaches this column, in its upper half
        double want = (c.rLine - 0.7) / c.rScale;
        double l = (want - RIFT_EDGE) / RIFT_BELLY;
        if (l <= 0.05 || l >= 0.98) return NO_Y;
        double t = 1 - Math.asin(l * l) / Math.PI;
        int y0 = (int) Math.floor(c.rFloor + t * (c.rTop - c.rFloor));
        for (int k = 0; k < 9; k++) {
            int y = y0 + (k % 2 == 0 ? k / 2 : -(k + 1) / 2);
            if (y < c.rFloor + 5 || y > c.rTop - 4 || y > c.gF - 8) continue;
            double d = riftDist(c, c.x, y, c.z);
            if (d > 0.2 && d < 1.6 && riftDist(c, c.x, y + 1, c.z) > 0.5) {
                c.springY = y;
                return y;
            }
        }
        return NO_Y;
    }

    /**
     * The rift's stream at a column: water sunk one block under the floor wherever the floor over it is open and the rock
     * round it holds it ({@link #holds}), so each flat stretch of the stepped floor holds a long pool that stops a block
     * short of the step down (a dry lip), and no stream spills over a step or hangs over a cave.
     */
    private boolean riftPool(Col c) {
        if (c.rPoolDone) return c.rPool;
        c.rPoolDone = true;
        c.rPool = c.rOn && riftDist(c, c.x, c.rFloor, c.z) <= -0.5 && holds(c.x, c.rFloor - 1, c.z, NO_Y) && packHolds(c.x, c.rFloor - 1, c.z);
        return c.rPool;
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
        // floors settle into terraces three blocks high; under cold ground (the Frost Fern Grotto) they lie smooth, at
        // about the terraces' mean height (Taran, 2026-11-17: no steps like the Ivory Root Cavern's)
        double fv = floorRaw + 2.5 * (K2 * hD.noise2(x / 26.0, z / 26.0) + 1);
        int hf = c.cold ? (int) Math.floor(fv) - 1 : 3 * (int) Math.floor(fv / 3.0);
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
            // (0.14: about twice as many as 0.13 had, now the rifts, halved and since 0.14.1 deep down, seldom reach the halls)
            double t = smooth((K2 * hK.noise2(x / 20.0, z / 20.0) + 0.05) / 0.2) * smooth((0.9 - q) / 0.5);
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
        // stalactites only under thick rock and stalagmites only on it: none where the pack's caves come close
        if (!Double.isNaN(c.stalBottom) && !anchored(c.x, c.z, (int) Math.floor(c.hCeil) + 1, 1, c.gF)) {
            c.stalBottom = Double.NaN;
            c.miteTop = NO_Y;
        }
        if (c.miteTop != NO_Y && !anchored(c.x, c.z, hf - 1, -1, c.gF)) c.miteTop = NO_Y;
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
        c.hPool = holds(c.x, c.hFloor - 1, c.z, NO_Y) && packHolds(c.x, c.hFloor - 1, c.z);
        return c.hPool;
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
        long peak = pitPeak(c.x, c.z);
        int lake = lakeLevel(peak);
        if (lake == NO_Y || lake > c.gF - 14) return false;
        c.sScore = score;
        c.sLake = lake;
        c.sCx = (int) (peak >> 32) * 4;
        c.sCz = (int) peak * 4;
        c.sThick = 5 + 2.5 * K2 * sB.noise2(x / 30.0, z / 30.0);
        c.lo = Math.min(c.lo, lake - 2);
        c.hi = Math.max(c.hi, c.gF + 3);
        return true;
    }

    /**
     * The middle of the pit a column belongs to: the peak of the pit field's slow part, found by climbing it on a
     * 4-block lattice from the column, as {i, j} cells (i in the high half).
     */
    private long pitPeak(int x, int z) {
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
        return ((long) i << 32) | (j & 0xffffffffL);
    }

    /**
     * The surface of a sinkhole's lake: one level for the whole pit, from its middle ({@link #pitPeak}): a block under
     * the lower sheet's floor there.
     */
    private int lakeLevel(long peak) {
        int cx = (int) (peak >> 32) * 4, cz = (int) peak * 4;
        double gC = coarseGround(cx, cz);
        double yc = sheetY(1, gC, cx, cz);
        double room = clamp(K2 * wD[1].noise2(cx / 28.0 + 70, cz / 28.0 + 70) * 1.6 - 0.35, 0, 1);
        double hh = Math.max(2.1, 2.6 + 0.6 * K2 * wA[1].noise2(cx / 16.0 + 5, cz / 16.0 + 5) + 2.5 * room);
        int lake = (int) Math.floor(yc - 0.6 * hh) - 1;
        lake = Math.min(lake, overhead.low(cx, cz) - 16);
        return lake < minFloor + 2 ? NO_Y : lake;
    }

    /** How far apart the shelves up a sinkhole's shaft lie, in blocks (0.18.6). */
    static final int LEDGE_STEP = 6;
    /** How far a shelf juts out at its top, in the pit field's units: about two blocks on a pit of middling size. */
    static final double LEDGE_OUT = 0.035;

    /**
     * Shelves up a sinkhole's shaft (0.18.6), as how much more of the pit field a block needs to lie open: every
     * {@link #LEDGE_STEP} blocks from a little over the chamber's roof to under the soil, flat on top and tapering under
     * over three blocks (a corbel, whose underside the grass and roots hang from), in patches round the wall where a
     * field is high, so they come and go round the pit rather than ringing it. Plants grow on their tops.
     */
    private double ledge(Col c, int x, double yy, int z) {
        double base = c.sLake + 1 + c.sThick + 5;
        if (yy < base - 3 || yy > c.gF - 7) return 0;
        double amount = clamp((pitTrait(c, TRAIT_SHELVES) - 0.3) / 0.4, 0, 1);
        if (amount <= 0) return 0;
        int k = (int) Math.ceil((yy - base) / LEDGE_STEP);
        double d = base + k * LEDGE_STEP - yy;
        if (d >= 3) return 0;
        double patch = smooth((K2 * sB.noise2(x / 9.0 + 17 * k, z / 9.0 - 11 * k) + 0.15) / 0.5);
        return LEDGE_OUT * amount * (1 - d / 3) * patch;
    }

    /**
     * What sets one sinkhole apart from the next (0.18.6, Taran: a feature in every pit would make them cookie-cutter):
     * each trait is a number from 0 to 1 drawn for the whole pit from its middle. {@link #TRAIT_SHELVES}: none in about
     * three pits in ten, few to many in the rest; {@link #TRAIT_FALL}: a spring in about one pit in six (the trait in
     * {@link #FALL_SHARE} of them, about half of which have a wall that can take one);
     * {@link #TRAIT_SLOPE}: a slope of fallen rock into the lake in a little under half (its side from
     * {@link #TRAIT_SLOPE_SIDE}); {@link #TRAIT_IVY}: walls hung thick with ivy in about half; {@link #TRAIT_RIM}: how
     * thickly grass and roots hang over the rim (the features read these two).
     */
    public static final int TRAIT_SHELVES = 0, TRAIT_FALL = 1, TRAIT_SLOPE = 2, TRAIT_SLOPE_SIDE = 3, TRAIT_IVY = 4, TRAIT_RIM = 5;
    /** The share of pits that look for a spring ({@link #TRAIT_FALL} at most this); about half find a wall for one. */
    public static final double FALL_SHARE = 0.3;

    private double pitTrait(Col c, int trait) {
        return Hashing.unit(Hashing.mix(seed ^ 0x51D7EA17L, c.sCx, c.sCz, trait));
    }

    /**
     * The ground over a green sinkhole at a column (0.18.6), for the dressing of its rim: a plains pit not snowed in, not
     * a desert's hollow. {@link Integer#MIN_VALUE} elsewhere; a look at the pit field first, so most columns cost two
     * noise samples and no column of their own.
     */
    public int sinkholeRim(int x, int z) {
        if (!sinkholes || K2 * (sA.noise2(x / PIT_SCALE, z / PIT_SCALE) + 0.18 * sB.noise2(x / 14.0, z / 14.0)) < 0.3) return Integer.MIN_VALUE;
        Col c = column(x, z);
        return c.sOn && !c.desert && !c.cold ? (int) Math.floor(c.gF) : Integer.MIN_VALUE;
    }

    /**
     * The ground over a forest's warrens at a column, where the trees' roots run through them (0.18.6: strands, pillars
     * and hanging stubs, all within {@link #STRAND_DEPTH} of it); {@link Integer#MIN_VALUE} elsewhere.
     */
    public int rootGround(int x, int z) {
        Col c = column(x, z);
        return c.forest && warrens && (c.wOn[0] || c.wOn[1]) ? (int) Math.floor(c.gF) : Integer.MIN_VALUE;
    }

    /** True where a sinkhole's pit itself (not its chamber) is open at a block (0.18.6). */
    public boolean pitOpen(int x, int y, int z) {
        Col c = column(x, z);
        return c.sOn && inPit(c, x, y, z);
    }

    /** A trait of the sinkhole at a column (see {@link #TRAIT_SHELVES}), or -1 where no pit is. */
    public double pitTrait(int x, int z, int trait) {
        Col c = column(x, z);
        return c.sOn ? pitTrait(c, trait) : -1;
    }

    /**
     * How far a pit's floor rises at a column over its lake's bed (0.18.6): in a pit with a bank
     * ({@link #TRAIT_SLOPE}), a fan of fallen rock leaning on one side of the shaft, rising from the middle of the lake to
     * a few blocks over its shore at the wall, lumpy, and keeping three blocks of headroom under the chamber's roof (so
     * it never shuts the way from the pit to the tunnels). Never below the bed, which would open rock under the lake.
     */
    private double pitRise(Col c, int x, int z) {
        if (pitTrait(c, TRAIT_SLOPE) > 0.45) return 0;
        double a = 2 * Math.PI * pitTrait(c, TRAIT_SLOPE_SIDE);
        double ux = Math.cos(a), uz = Math.sin(a);
        double dx = x + 0.5 - c.sCx, dz = z + 0.5 - c.sCz;
        double along = dx * ux + dz * uz, across = Math.abs(-dx * uz + dz * ux);
        double f = clamp((along + 2) / 15.0, 0, 1), g = clamp(1 - across / 11.0, 0, 1);
        if (f <= 0 || g <= 0) return 0;
        double top = c.sThick - 2;
        if (top <= 0) return 0;
        double rise = top * Math.pow(f, 1.4) * Math.sqrt(g);
        if (rise >= 0.5) rise += 0.8 * K2 * sB.noise2(x / 5.0 + 50, z / 5.0 + 50);
        return clamp(rise, 0, top);
    }

    /** Signed distance to the pit and the chamber at its foot (negative inside). */
    private double sinkDist(Col c, int x, int y, int z) {
        double yy = y + 0.5;
        int lake = c.sLake;
        // the lake's bed, or the fallen rock over it
        double bed = lake - 1.5 + pitRise(c, x, z);
        if (yy < bed) return bed + 0.5 - yy;
        double depth = c.gF - yy;
        double under = clamp((depth - 3) / 12.0, 0, 1);
        double thresh = 0.62 - 0.12 * under - 0.04 * K3 * sA.noise3(x / 9.0, y / 9.0, z / 9.0) + ledge(c, x, yy, z);
        double pit = Math.max((thresh - c.sScore) / 0.05, Math.max(bed + 0.5 - yy, yy - (c.gF + 3)));
        double near = c.sScore + 0.25 * K3 * sB.noise3(x / 25.0 + 5, y / 15.0, z / 25.0 + 5);
        double top = lake + 1 + c.sThick + 0.8 * K3 * roughN.noise3(x / 8.0 + 20, y / 6.0, z / 8.0 + 20);
        double cave = Math.max((0.30 - near) / 0.05, Math.max(Math.max(lake + 1, bed + 0.5) - yy, yy - top));
        return Math.min(pit, cave);
    }

    /** The pit itself (not its chamber), open from the lake's bed (or the fallen rock over it) up through the ground. */
    private boolean inPit(Col c, int x, int y, int z) {
        double yy = y + 0.5;
        if (yy < c.sLake - 1.5 + pitRise(c, x, z)) return false;
        double depth = c.gF - yy;
        double under = clamp((depth - 3) / 12.0, 0, 1);
        double thresh = 0.62 - 0.12 * under - 0.04 * K3 * sA.noise3(x / 9.0, y / 9.0, z / 9.0) + ledge(c, x, yy, z);
        return c.sScore > thresh;
    }

    /** The springs found so far, per thread, by pit: a direct-mapped cache of pits' keys and their springs {x, y, z}. */
    private static final int SPRING_SLOTS = 64;
    private final ThreadLocal<long[]> springKeys = ThreadLocal.withInitial(() -> {
        long[] a = new long[SPRING_SLOTS];
        java.util.Arrays.fill(a, Long.MIN_VALUE);
        return a;
    });
    private final ThreadLocal<int[]> springAt = ThreadLocal.withInitial(() -> new int[3 * SPRING_SLOTS]);

    /**
     * A sinkhole's spring (0.18.6): about one pit in six has one ({@link #TRAIT_FALL}), high on the wall of the shaft,
     * whose water pours down into the lake (a waterfall; with the rifts' springs the only water of the shapes that
     * flows, {@link #SPRING}). The height of the spring at a column, or NO_Y where none is.
     */
    int sinkSpring(Col c) {
        if (c.sSpringDone) return c.sSpringY;
        c.sSpringDone = true;
        c.sSpringY = NO_Y;
        if (!c.sOn) return NO_Y;
        long key = ((long) c.sCx << 32) ^ (c.sCz & 0xffffffffL);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 58);
        long[] keys = springKeys.get();
        int[] at = springAt.get();
        if (keys[slot] != key) {
            int[] s = findSpring(c.sCx, c.sCz);
            keys[slot] = key;
            at[3 * slot] = s == null ? 0 : s[0];
            at[3 * slot + 1] = s == null ? NO_Y : s[1];
            at[3 * slot + 2] = s == null ? 0 : s[2];
        }
        if (at[3 * slot + 1] != NO_Y && at[3 * slot] == c.x && at[3 * slot + 2] == c.z) c.sSpringY = at[3 * slot + 1];
        return c.sSpringY;
    }

    /**
     * Where the pit round (cx, cz) keeps its spring, or null: about one pit in six has one. Its side and height come
     * from the pit's middle, the height somewhere up the shaft between the chamber's roof and the soil; the spring is the
     * first block of the wall out along that side, kept only where the rock closes over it, under it and on every side
     * but the pit's (the pack's caves too), so its water runs only into the pit. Up to six sides and heights are tried.
     * Reads the columns on its own copies, never the cache (they lie far apart, and would push out the asker's).
     */
    int[] findSpring(int cx, int cz) {
        Col m = scratch(cx, cz);
        // nothing pours into a desert's hollow (Taran, 18 Nov: no sandfalls, no springs; its pool lies hidden)
        if (!m.sOn || m.desert || pitTrait(m, TRAIT_FALL) > FALL_SHARE) return null;
        long h = Hashing.mix(seed ^ 0x5B1C5EL, cx, cz, 3);
        double lo = m.sLake + 1 + m.sThick + 4, hi = m.gF - 9;
        if (hi - lo < 3) return null;
        for (int t = 0; t < 6; t++) {
            int ys = (int) Math.floor(lo + Hashing.unit(Hashing.mix(h, 1, t, 0)) * (hi - lo));
            double a = 2 * Math.PI * (Hashing.unit(Hashing.mix(h, 2, 0, 0)) + t / 3.0 + (t >= 3 ? 1 / 6.0 : 0));
            int[] s = springAlong(cx, cz, ys, Math.cos(a), Math.sin(a));
            if (s != null) return s;
        }
        return null;
    }

    /** The first block of the pit's wall out from (cx, cz) along (ux, uz) at height ys, if a spring may open there. */
    private int[] springAlong(int cx, int cz, int ys, double ux, double uz) {
        boolean open = false;
        int px = Integer.MIN_VALUE, pz = 0;
        for (double r = 0; r < 64; r += 0.5) {
            int x = (int) Math.floor(cx + 0.5 + r * ux), z = (int) Math.floor(cz + 0.5 + r * uz);
            if (x == px && z == pz) continue;
            px = x;
            pz = z;
            Col n = scratch(x, z);
            if (n.sOn && sinkDist(n, x, ys, z) < 0 && raw(n, x, ys, z, false) > 0) {
                open = true;
                continue;
            }
            if (!open) continue;
            // the wall: of this pit (so its column finds this spring again), rock to every shape, over and under the
            // spring too, under the soil
            if (!n.sOn || n.sCx != cx || n.sCz != cz || ys > n.gF - 8 || raw(n, x, ys, z, false) <= 0) return null;
            for (int dy = -1; dy <= 1; dy += 2)
                if (sinkDist(n, x, ys + dy, z) < 0.3 || raw(n, x, ys + dy, z, false) <= 0) return null;
            // every side either the pit's open space (at least one) or rock
            int pits = 0;
            for (int k = 0; k < 4; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
                Col b = scratch(nx, nz);
                if (raw(b, nx, ys, nz, false) <= 0) return null;
                if (b.sOn && sinkDist(b, nx, ys, nz) < 0) pits++;
            }
            if (pits == 0) return null;
            if (pack != null)
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                    for (int y = ys - SEAM - 1; y <= ys + 1; y++) if (pack.density(x + dx, y, z + dz) < 0) return null;
            return new int[]{x, ys, z};
        }
        return null;
    }

    /** A column worked out on its own, outside the per-thread cache. */
    private Col scratch(int x, int z) {
        Col c = new Col();
        c.x = x;
        c.z = z;
        c.valid = true;
        fill(c);
        return c;
    }

    // ================================================================================================ queries

    /**
     * Signed distance (in blocks, roughly) to the open space of every shape: negative inside, {@link #SOLID} far off. The
     * thin rock between a shape and the pack's caves counts as open ({@link #seam}).
     */
    public double carve(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo || y > c.hi) return SOLID;
        return carve(c, x, y, z);
    }

    /** As {@link #carve(int, int, int)} inside the column's range, with the merge. */
    private double carve(Col c, int x, int y, int z) {
        if (pack == null) return carve0(c, x, y, z);
        double d = carve0At(c, y);
        if (d >= 0 && d < SEAM_GATE && seam(c, y) != NO_SEAM) return SEAM_OPEN;
        return d;
    }

    /** The open space with its still water carved in, before the merge with the pack's caves. */
    private double carve0(Col c, int x, int y, int z) {
        double d = raw(c, x, y, z);
        // still water is open, whether it is sunk into rock (a pool, a stream) or lies in open space (a sinkhole's lake:
        // until 0.18.6 the water was carved only where the rock was nearly shut, so the fill below took the middle of
        // every lake and only its edges kept their water)
        if (water(c, y)) return Math.min(d, -0.5);
        // a sinkhole's lake where its rock cannot hold it fills in, so its water never meets air at its own level; not
        // where another shape is open (a rift through the pit), which would leave the fill hanging in that open space
        if (d < 0.5 && c.sOn && lakeBlock(c, x, y, z, c.sLake) && othersShut(c, x, y, z)) return 0.5;
        return d;
    }

    /** True where no shape but the sinkhole is open: the warrens, the rift and the hall are rock there. */
    private boolean othersShut(Col c, int x, int y, int z) { return raw(c, x, y, z, false) > 0; }

    /** The shapes' open space before any still water: the minimum over every shape, its springs and formations. */
    private double raw(Col c, int x, int y, int z) { return raw(c, x, y, z, true); }

    /** {@link #raw(Col, int, int, int)}, leaving the sinkhole out unless {@code sink}. */
    private double raw(Col c, int x, int y, int z, boolean sink) {
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
        if (sink && c.sOn) {
            double s = sinkDist(c, x, y, z);
            if (s < d) { d = s; owner = SINKHOLES; }
            if (d > -0.5 && y == sinkSpring(c)) { d = -0.5; owner = SINKHOLES; }
        }
        // a root pillar shuts its own blocks, and keeps the open space round it near enough to the rock that the
        // interpolation between noise cells (UpperFine) works its edges out block by block
        if (d < 0 && owner == WARRENS && c.forest && warrens && !othersOpen(c, x, y, z))
            for (int i = 0; i < 2; i++) {
                if (!c.wOn[i]) continue;
                double p = pillarAt(c, i, x, y, z);
                if (p < 4) d = Math.max(d, -p);
            }
        if (d < 0 && formationOf(c, owner, x, y, z) != 0) return 0.5;
        return d;
    }

    /** True where a rift, hall or sinkhole is open at a block. */
    private boolean othersOpen(Col c, int x, int y, int z) {
        return c.rOn && riftDist(c, x, y, z) < 0 || c.hOn && hallDist(c, x, y, z) < 0 || c.sOn && sinkDist(c, x, y, z) < 0;
    }

    /** {@link #raw} at any block, {@link #SOLID} outside its column's range. */
    private double raw(int x, int y, int z) {
        Col c = column(x, z);
        return y < c.lo || y > c.hi ? SOLID : raw(c, x, y, z);
    }

    // ------------------------------------------------------------------------------------------------ still water

    /**
     * The heights a column holds still water at (each block sunk under a floor or lying in a sinkhole's lake): its
     * warren pools, its hall pool, its stretch of rift stream and its sinkhole's lake, where the rock round each holds
     * it. Worked out once per column.
     */
    private void waterLevels(Col c) {
        if (c.waterDone) return;
        c.waterDone = true;
        c.waterN = 0;
        if (c.wOn[0] || c.wOn[1]) {
            warrenPools(c);
            for (int i = 0; i < 2; i++) if (c.wPool[i] != NO_Y) addWater(c, c.wPool[i]);
        }
        if (c.hOn && hallPool(c)) addWater(c, c.hFloor - 1);
        if (riftPool(c)) addWater(c, c.rFloor - 1);
        if (c.sOn) {
            for (int y = c.sLake - 1; y <= c.sLake; y++)
                if (lakeBlock(c, c.x, y, c.z, c.sLake) && holds(c.x, y, c.z, c.sLake) && packHolds(c.x, y, c.z)) addWater(c, y);
        }
    }

    private static void addWater(Col c, int y) {
        for (int i = 0; i < c.waterN; i++) if (c.waterY[i] == y) return;
        c.waterY[c.waterN++] = y;
    }

    /** Still water at this column and height (a pool, a stretch of stream or a sinkhole's lake). */
    boolean water(Col c, int y) {
        waterLevels(c);
        for (int i = 0; i < c.waterN; i++) if (c.waterY[i] == y) return true;
        return false;
    }

    /** A block of the sinkhole lake at height {@code lake}: inside a pit of that lake, at the lake's level or one under. */
    private boolean lakeBlock(Col c, int x, int y, int z, int lake) {
        return c.sOn && c.sLake == lake && y >= lake - 1 && y <= lake && inPit(c, x, y, z);
    }

    /**
     * Whether still water at a block stays put: the four blocks beside it and the one under it are rock to every shape
     * (for a sinkhole's lake, {@code lake}, they may also be the same lake's water or the rock it fills in with, see
     * {@link #carve(Col, int, int, int)}, but not a block of it another shape opens). The pack's caves and the canyons
     * are kept out of that rim by {@link #rim}.
     */
    private boolean holds(int x, int y, int z, int lake) {
        for (int k = 0; k < 5; k++) {
            int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0), ny = k == 4 ? y - 1 : y;
            if (lake != NO_Y) {
                Col n = column(nx, nz);
                if (lakeBlock(n, nx, ny, nz, lake) && othersShut(n, nx, ny, nz)) continue;
            }
            if (raw(nx, ny, nz) <= 0) return false;
        }
        return true;
    }

    /**
     * True where the rock round the shapes' still water lies: within 2 blocks across of a block of it, from its height
     * down to {@link #SEAM} + 1 under it (what {@link #packHolds} asks of the pack's caves). The canyons stay out of it too
     * where the shapes merge with the pack's caves, so no pool keeps only a one-block rim beside a canyon.
     */
    public boolean nearWater(int x, int y, int z) {
        if (pack == null) return false;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Col n = column(x + dx, z + dz);
                if (y < n.lo - SEAM - 2 || y > n.hi) continue;
                waterLevels(n);
                for (int i = 0; i < n.waterN; i++) if (y <= n.waterY[i] && y >= n.waterY[i] - SEAM - 1) return true;
            }
        }
        return false;
    }

    /**
     * The rim of the shapes' still water: a block beside a block of it or under one, that is not water itself. It is
     * rock to every shape ({@link #holds}); the pack's caves may not open it ({@code UpperSeal}), nor the canyons.
     */
    public boolean rim(int x, int y, int z) {
        Col c = column(x, z);
        if (!c.rimDone) rimRange(c);
        if (y < c.rimLo || y > c.rimHi || water(c, y)) return false;
        if (water(c, y + 1)) return true;
        return water(column(x + 1, z), y) || water(column(x - 1, z), y) || water(column(x, z + 1), y) || water(column(x, z - 1), y);
    }

    /** The heights a column's rim may lie at: under its own water, and beside its neighbours'. */
    private void rimRange(Col c) {
        c.rimDone = true;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        waterLevels(c);
        for (int i = 0; i < c.waterN; i++) {
            lo = Math.min(lo, c.waterY[i] - 1);
            hi = Math.max(hi, c.waterY[i] - 1);
        }
        int x = c.x, z = c.z;
        for (int k = 0; k < 4; k++) {
            Col n = column(x + (k == 0 ? 1 : k == 1 ? -1 : 0), z + (k == 2 ? 1 : k == 3 ? -1 : 0));
            waterLevels(n);
            for (int i = 0; i < n.waterN; i++) {
                lo = Math.min(lo, n.waterY[i]);
                hi = Math.max(hi, n.waterY[i]);
            }
        }
        c.rimLo = lo;
        c.rimHi = hi;
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
        // the trees' roots through a forest's warrens: strands over the walls, and the twisted pillars
        if (owner == WARRENS && c.forest && warrens && (rootAt(c, 0, x, y, z) || rootAt(c, 1, x, y, z)))
            return othersOpen(c, x, y, z) ? 0 : ROOT;
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
        boolean roots = c.forest && warrens && (c.wOn[0] || c.wOn[1]);
        if (c.fKind == 0 && Double.isNaN(c.stalBottom) && c.miteTop == NO_Y && !roots) return 0;
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
            // so do the roots, a little way (rootAt)
            else if (roots && d <= 1.5) owner = WARRENS;
            else return 0;
        }
        return formationOf(c, owner, x, y, z);
    }

    /** Where the pack's own caves must stay shut: the formations, which they would otherwise cut loose. */
    public boolean seals(int x, int y, int z) { return formation(x, y, z) != 0; }

    /**
     * What fills an open block: {@link #WATER} (still) in the pools, the rifts' streams and the sinkholes' lakes,
     * {@link #SPRING} in a spring (it flows), {@link #AIR} elsewhere in the shapes (so the pack's water tables do not
     * flood them), or -1 to leave it to the pack. Where the pack's water would then stand beside the shapes' open space
     * it turns to rock ({@link #besideOpen}).
     */
    public int fluid(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo || y > c.hi) return -1;
        if (water(c, y)) return WATER;
        if (c.rOn && y == spring(c)) return SPRING;
        if (c.sOn && y == sinkSpring(c)) return SPRING;
        return carve(c, x, y, z) < 0 ? AIR : -1;
    }

    /**
     * What a block is in the shapes alone, for the dev scans: {@link #AIR}, {@link #WATER} or {@link #SPRING} where a
     * shape is open there, -1 where none is.
     */
    public int openKind(int x, int y, int z) {
        Col c = column(x, z);
        if (y < c.lo || y > c.hi) return -1;
        if (water(c, y)) return WATER;
        if (c.rOn && y == spring(c)) return SPRING;
        if (c.sOn && y == sinkSpring(c)) return SPRING;
        return carve0(c, x, y, z) < 0 ? AIR : -1;
    }

    /**
     * True where a block beside this one or the one under it is open in a shape (or in a seam that fell away): the pack's
     * water here would pour into the shapes (or hang over them), so it stays rock instead. So does the pack's water up to
     * {@link #SEAM} + 1 blocks over a shape's ceiling or over a seam under the pack's cave: the lake there keeps a floor
     * thicker than a seam.
     */
    public boolean besideOpen(int x, int y, int z) {
        if (carve(x, y - 1, z) < 0 || carve(x + 1, y, z) < 0 || carve(x - 1, y, z) < 0 || carve(x, y, z + 1) < 0 || carve(x, y, z - 1) < 0) return true;
        if (pack == null) return false;
        Col c = column(x, z);
        if (y - 1 < c.lo || y - 1 > c.hi) return false;
        for (int h = y - 1; h >= y - 1 - SEAM; h--) {
            int k = cls(c, h);
            if (k == OURS) return true;
            if (k != PACK_OPEN) return false;
            if (seam(c, h - 1) == UNDER_PACK) return true;
        }
        return false;
    }

    /** What a block is to the merge, for the dev scans: ours, pack, rock or stop (see {@link #cls}); "-" without a merge. */
    public String mergeClass(int x, int y, int z) {
        if (pack == null) return "-";
        int k = cls(column(x, z), y);
        return k == OURS ? "ours" : k == PACK_OPEN ? "pack" : k == STOP ? "stop" : "rock";
    }

    /**
     * {@link #NO_SEAM}, or what the rock at a block falls away as: {@link #OVER_PACK}, a shape's floor over the pack's
     * cave, or {@link #UNDER_PACK}, the pack's floor over a shape's ceiling (see {@link #seam}).
     */
    public int seamKind(int x, int y, int z) {
        if (pack == null) return NO_SEAM;
        Col c = column(x, z);
        if (y < c.lo || y > c.hi || carve0At(c, y) < 0) return NO_SEAM;
        return seam(c, y);
    }

    // ------------------------------------------------------------------------------------------------ the merge

    /** Sets up a column's per-height answers for the merge. */
    private static void seamReady(Col c) {
        if (c.sReady) return;
        c.sReady = true;
        if (c.sLen <= 0) return;
        if (c.sKind == null || c.sKind.length < c.sLen) {
            int n = Math.max(c.sLen, 96);
            c.sKind = new byte[n];
            c.sPack = new float[n];
            c.sCarve = new float[n];
        }
        java.util.Arrays.fill(c.sKind, 0, c.sLen, (byte) 0);
        java.util.Arrays.fill(c.sPack, 0, c.sLen, Float.NaN);
        java.util.Arrays.fill(c.sCarve, 0, c.sLen, Float.NaN);
    }

    /** {@link #carve0} at a height of a column, kept; {@link #SOLID} outside its range. */
    private double carve0At(Col c, int y) {
        if (y < c.lo || y > c.hi) return SOLID;
        int i = y - c.sBase;
        if (i < 0 || i >= c.sLen) return carve0(c, c.x, y, c.z);
        seamReady(c);
        float v = c.sCarve[i];
        if (v != v) {
            v = (float) carve0(c, c.x, y, c.z);
            c.sCarve[i] = v;
        }
        return v;
    }

    /** The pack's density at a height of a column, kept. */
    private double packAt(Col c, int y) {
        int i = y - c.sBase;
        if (i < 0 || i >= c.sLen) return pack.density(c.x, y, c.z);
        seamReady(c);
        float v = c.sPack[i];
        if (v != v) {
            v = (float) pack.density(c.x, y, c.z);
            c.sPack[i] = v;
        }
        return v;
    }

    /**
     * What a block is to the merge: {@link #OURS} open in a shape (or its water), {@link #PACK_OPEN} open in the pack's
     * caves (not the open air over the ground, nor under the old floor), {@link #ROCK}, or {@link #STOP} for rock the merge
     * may not open (what holds the shapes' water, their formations) and for the pack's open air near the ground.
     */
    private int cls(Col c, int y) {
        if (carve0At(c, y) < 0) return OURS;
        if (y < packFloor) return ROCK;
        if (formation(c.x, y, c.z) != 0 || rim(c.x, y, c.z)) return STOP;
        if (packAt(c, y) >= 0) return ROCK;
        return y <= c.low - 6 ? PACK_OPEN : STOP;
    }

    /**
     * Whether the rock at a height of a column falls away, and how: the run of rock it lies in, straight up and down, is
     * at most {@link #SEAM} blocks thick, with a shape's open space at one end and the pack's cave at the other
     * ({@link #OVER_PACK} with the shape over it, {@link #UNDER_PACK} with the pack's cave over it). Worked out once for the
     * whole run.
     */
    private int seam(Col c, int y) {
        int i = y - c.sBase;
        if (i < 0 || i >= c.sLen) return NO_SEAM;
        seamReady(c);
        int k = c.sKind[i];
        if (k != 0) return k - 1;
        int r = NO_SEAM, top = y, bot = y;
        if (cls(c, y) == ROCK) {
            // up and then down while the rock goes on, until it is too thick (top - bot + 1 > SEAM)
            int up = cls(c, top + 1);
            while (up == ROCK && top - bot < SEAM) up = cls(c, ++top + 1);
            int down = STOP;
            if (top - bot < SEAM) {
                down = cls(c, bot - 1);
                while (down == ROCK && top - bot < SEAM) down = cls(c, --bot - 1);
            }
            boolean thin = top - bot < SEAM && up != ROCK && down != ROCK;
            if (thin && up == OURS && down == PACK_OPEN) r = OVER_PACK;
            else if (thin && up == PACK_OPEN && down == OURS) r = UNDER_PACK;
        }
        for (int h = Math.max(bot, c.sBase); h <= top && h - c.sBase < c.sLen; h++) c.sKind[h - c.sBase] = (byte) (r + 1);
        return r;
    }

    /**
     * True where the pack's caves leave the rock round still water whole: rock to the pack in every column within 2
     * blocks across, from the water's height down to {@link #SEAM} + 1 under it. So no seam ever opens beside the water
     * or under it, and the water never sits on a crust over the pack's caves.
     */
    private boolean packHolds(int x, int wy, int z) {
        if (pack == null) return true;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Col n = column(x + dx, z + dz);
                for (int y = Math.max(wy - SEAM - 1, packFloor); y <= wy; y++) if (packAt(n, y) < 0) return false;
            }
        }
        return true;
    }

    /**
     * True where a formation keeps thick rock over it ({@code dir} 1, from {@code from} up) or under it (-1, from
     * {@code from} down): rock to the pack for {@link #SEAM} + 2 blocks in its own column and the four beside it, so no
     * seam cuts it loose. Near the ground (within 2 of {@code ground}) the open air does not count.
     */
    private boolean anchored(int x, int z, int from, int dir, double ground) {
        if (pack == null) return true;
        for (int k = 0; k < 5; k++) {
            int nx = x + (k == 1 ? 1 : k == 2 ? -1 : 0), nz = z + (k == 3 ? 1 : k == 4 ? -1 : 0);
            for (int j = 0; j < SEAM + 2; j++) {
                int y = from + dir * j;
                if (y < packFloor || y > ground - 2) continue;
                if (pack.density(nx, y, nz) < 0) return false;
            }
        }
        return true;
    }

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
        if (a == null) return null;
        Col c = column(x, z);
        return y <= c.gF - 6 && !c.wet ? a : null;
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

    /**
     * Why a sinkhole's lake holds its water at a column or not, for the dev commands (0.18.6): for its two lake blocks,
     * whether the pit is open there, the first neighbour that lets the water out (open to some shape and not the same
     * lake) and the first block of the pack's caves near it ({@link #packHolds}).
     */
    public java.util.List<String> lakeDebug(int x, int z) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Col c = column(x, z);
        if (!c.sOn) {
            out.add("no sinkhole at " + x + " " + z);
            return out;
        }
        int lake = c.sLake;
        for (int y = lake; y >= lake - 1; y--) {
            StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "lake %d at y %d: ", lake, y));
            if (!lakeBlock(c, x, y, z, lake)) {
                out.add(b.append("not open in the pit").toString());
                continue;
            }
            b.append(water(c, y) ? "water; " : "no water; ");
            String why = null;
            for (int k = 0; k < 5 && why == null; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0), ny = k == 4 ? y - 1 : y;
                Col n = column(nx, nz);
                boolean same = lakeBlock(n, nx, ny, nz, lake), shut = othersShut(n, nx, ny, nz);
                if (same && shut) continue;
                double r = raw(nx, ny, nz);
                if (r <= 0) why = String.format(java.util.Locale.ROOT, "open at %d %d %d (raw %.2f; %s; same lake %b, others shut %b)", nx, ny, nz, r,
                        n.sOn ? "pit of lake " + n.sLake + " middle " + n.sCx + " " + n.sCz : "no pit", same, shut);
            }
            b.append(why == null ? "held all round" : why);
            if (pack != null) {
                String pw = null;
                for (int dx = -2; dx <= 2 && pw == null; dx++) for (int dz = -2; dz <= 2 && pw == null; dz++) {
                    Col n = column(x + dx, z + dz);
                    for (int h = Math.max(y - SEAM - 1, packFloor); h <= y && pw == null; h++) {
                        double v = packAt(n, h);
                        if (v < 0) pw = String.format(java.util.Locale.ROOT, "pack open at %d %d %d (%.3f)", x + dx, h, z + dz, v);
                    }
                }
                b.append("; ").append(pw == null ? "pack rock all round" : pw);
            }
            out.add(b.toString());
        }
        return out;
    }

    /**
     * How the merge reads a column from {@code y1} down to {@code y0}, one line a height, for the dev commands: what the
     * block is to the merge (ours, pack, rock, stop), the shapes' carve before and after the merge, the pack's density
     * and the seam the rock falls away as.
     */
    public java.util.List<String> seamDebug(int x, int z, int y0, int y1) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Col c = column(x, z);
        out.add(String.format(java.util.Locale.ROOT, "column %d %d: lo %.1f hi %.1f low %.1f ground %.1f", x, z, c.lo, c.hi, c.low, c.gF));
        for (int y = y1; y >= y0; y--) {
            if (y < c.lo || y > c.hi) {
                out.add(y + " outside the shapes' range");
                continue;
            }
            String k = "-";
            if (pack != null) {
                int cl = cls(c, y);
                k = cl == OURS ? "ours" : cl == PACK_OPEN ? "pack" : cl == STOP ? "stop" : "rock";
            }
            int s = pack == null ? NO_SEAM : seamKind(x, y, z);
            out.add(String.format(java.util.Locale.ROOT, "%d %s carve0 %.2f carve %.2f pack %s%s", y, k, carve0At(c, y), carve(c, x, y, z),
                    pack == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", packAt(c, y)),
                    s == OVER_PACK ? " seam over the pack's" : s == UNDER_PACK ? " seam under the pack's" : ""));
        }
        return out;
    }

    /** What the shapes hold over a column, for the dev commands. */
    public String describe(int x, int z) {
        Col c = column(x, z);
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "ground %.1f (coarse %.1f, low %.1f) %s%s lean %.2f", c.gF, c.gC,
                c.low, c.s.cover(), c.cold ? " cold" : "", c.lean));
        for (int i = 0; i < 2; i++)
            if (c.wOn[i]) b.append(String.format(java.util.Locale.ROOT, "; warren %d y %.1f half %.1fx%.1f line %.1f room %.2f", i, c.wYc[i], c.wHw[i],
                    c.wHh[i], c.wLine[i], c.wRoom[i]));
        if (c.mouth > 0) b.append(String.format(java.util.Locale.ROOT, "; mouth %.2f (roofed %.2f)", c.mouth, roofed(x, z)));
        if (c.link > 0) b.append(String.format(java.util.Locale.ROOT, "; link %.2f", c.link));
        if (c.rOn) b.append(String.format(java.util.Locale.ROOT, "; rift floor %d top %.1f line %.1f scale %.2f", c.rFloor, c.rTop, c.rLine, c.rScale));
        if (c.hOn) b.append(String.format(java.util.Locale.ROOT, "; hall floor %d ceiling %.1f", c.hFloor, c.hCeil));
        if (c.sOn) {
            b.append(String.format(java.util.Locale.ROOT, "; sinkhole lake %d score %.2f middle %d %d shelves %.2f fall %.2f slope %.2f ivy %.2f",
                    c.sLake, c.sScore, c.sCx, c.sCz, pitTrait(c, TRAIT_SHELVES), pitTrait(c, TRAIT_FALL), pitTrait(c, TRAIT_SLOPE),
                    pitTrait(c, TRAIT_IVY)));
            int sp = sinkSpring(c);
            if (sp != NO_Y) b.append("; its spring at y ").append(sp);
        }
        if (c.strandK > 0) b.append(String.format(java.util.Locale.ROOT, "; root strands %.2f", c.strandK));
        pillarsReady(c);
        for (int i = 0; i < 2; i++)
            if (c.pil[i] != null) b.append(String.format(java.util.Locale.ROOT, "; root pillar (sheet %d) at %d %d, floor %.1f roof %.1f",
                    i, (int) Math.floor(c.pil[i].x), (int) Math.floor(c.pil[i].z), c.pil[i].floor, c.pil[i].ceil));
        return b.toString();
    }

    /**
     * Places to look at each shape, nearest first, for the dev commands: open blocks of a kind ({@code mouth},
     * {@code chambers} and {@code frozen} for the warrens' mouths, their root chambers and a cold forest's warrens as
     * well), searched on a 4-block grid in rings
     * round (x, z) out to {@code radius}, each at least {@code apart} from the others. Each is {x, y, z} with y the
     * lowest open block of that kind in its column.
     */
    public java.util.List<int[]> find(String kind, int x0, int z0, int radius, int max, int apart) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (kind.equals("pillars")) {
            // by their cells, which is quicker than asking every fourth column
            int c0 = Math.floorDiv(x0, PILLAR_CELL), d0 = Math.floorDiv(z0, PILLAR_CELL);
            for (int ring = 0; ring * PILLAR_CELL <= radius && out.size() < max; ring++)
                for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                    if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                    for (int k = 0; k < 2 && out.size() < max; k++) {
                        Pillar p = pillarOf(c0 + i, d0 + j, k);
                        if (p == null) continue;
                        int[] at = {(int) Math.floor(p.x), (int) Math.floor(p.floor + 0.5), (int) Math.floor(p.z)};
                        boolean far = true;
                        for (int[] o : out) far &= Math.hypot(o[0] - at[0], o[2] - at[2]) >= apart;
                        if (far) out.add(at);
                    }
                }
            return out;
        }
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
        // the pits first by their field alone, which is cheap: a column's own answers take its roof caps
        boolean pit = switch (kind) {
            case "sinkholes", "hollows", "falls", "slopes", "shelves" -> true;
            default -> false;
        };
        if (pit && K2 * (sA.noise2(x / PIT_SCALE, z / PIT_SCALE) + 0.18 * sB.noise2(x / 14.0, z / 14.0)) < 0.3) return null;
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
            case "roots" -> {
                // thick root strands over the top sheet, near enough to the ground for the strands to run full
                if (c.strandK < 0.8 || !c.wOn[0] || c.wLine[0] > 0.5 * c.wHw[0] || c.gF - c.wYc[0] > STRAND_FULL) return null;
                lo = floorLevel(c, 0);
                hi = (int) Math.ceil(c.wYc[0] + c.wHh[0]);
                want = WARRENS;
            }
            case "pillars" -> {
                // the middle of a root pillar: the spot is its foot, inside it
                pillarsReady(c);
                for (Pillar p : c.pil)
                    if (p != null && Math.abs(p.x - 0.5 - x) <= 2 && Math.abs(p.z - 0.5 - z) <= 2)
                        return new int[]{(int) Math.floor(p.x), (int) Math.floor(p.floor + 0.5), (int) Math.floor(p.z)};
                return null;
            }
            case "rifts" -> {
                if (!c.rOn || c.rLine > 2) return null;
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
            case "sinkholes", "hollows", "falls", "slopes", "shelves" -> {
                if (!c.sOn || c.sScore < 0.55) return null;
                if ((kind.equals("sinkholes") || kind.equals("hollows")) && kind.equals("hollows") != c.desert) return null;
                // pits with a waterfall, a slope of fallen rock, or many shelves (0.18.6), of either kind
                if (kind.equals("falls") && (pitTrait(c, TRAIT_FALL) > FALL_SHARE || sinkSpring(c) == NO_Y)
                        || kind.equals("slopes") && pitTrait(c, TRAIT_SLOPE) > 0.45 || kind.equals("shelves") && pitTrait(c, TRAIT_SHELVES) < 0.6) return null;
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
