package com.explorationremix.world.terrain;

/**
 * The Basalt Galleries' Obsidian Tubes as noise (0.5.1; Taran 2026-11-02: "Don't design the shape. It should be
 * procedurally generated."): round lava tubes about 20 blocks across and 18 to 27 high (picture 2's size), running
 * through the rock nearly level with floors at W+46..W+86 and vaults up to about W+113 (W the lava table), with arch
 * rings one behind the other (the bays between them bulge out, so each ring stands in on its legs as well as at its
 * crown), twin tubes round a pier, flat floors stepped by column cells, column ends hanging from the vaults by whole
 * column cells, and still water in sealed dips of the floor. Collapse gaps cut each tube country across the flow every 80
 * to 120 blocks, so a tube is a chain of short runs with mouths at both ends. Nothing is planned or placed: every value is
 * a pure function of the seed and the position (caches only hold those values), so any number of worldgen threads may
 * call in, and nothing seams.
 *
 * <p><b>Where tubes run.</b> Cellular noise: the world is cut into {@link #CELL}-block cells and a cell holds a tube
 * country with chance {@link #SITE_P}: a noise-warped ellipse, its long axis along a hashed flow direction. Inside its rim
 * ({@link #massif} over 0) the Galleries keep rock from floor to vault in place of their own massifs, except in the
 * collapse gaps ({@link #gap}): bands across the flow along the level lines of a warped along-coordinate,
 * {@link #GAP_HW_MIN} to {@link #GAP_HW_MAX} blocks either side of their middle, where the massif reads under 0 and the
 * cavern comes through. Outside the rim nothing of the country shapes the cavern's ground: no ring is pulled to one
 * height, no plateau raised and no ring sunk.
 *
 * <p><b>Runs.</b> A tube is cut only where rock encloses it, the country's massif or, past the rim, the Galleries' own
 * standing rock ({@link GalleriesLandscape#massif}, out to {@link #RUN_REACH} blocks past the rim), and only into the
 * cavern's rock. Each line's axis is sampled every {@link #RUN_STEP} blocks ({@link #runs}, once per country and thread):
 * a run is a stretch enclosed by rock between two samples where the cavern is open across the whole section, its mouths.
 * Its floor comes from the Galleries' landscape read without the tubes ({@link GalleriesLandscape}, built before both
 * fields, so neither reads the other to make it): the landscape's floor at each mouth, clamped to {@link #FLOOR_LOW} ..
 * {@link #FLOOR_TOP}, straight between the two and no steeper than {@link #RUN_SLOPE}; a run whose mouth would not meet
 * the landscape there (within {@link #MOUTH_TOL}, or a ledge at most {@link #LEDGE_MAX} over low ground, the lava
 * lowlands), or whose blend would end in a cliff round the mouth, is not cut. A country stands, and the Galleries keep
 * it, where it cuts at least {@link #STAND_RUNS} runs. Past a mouth the section stays whole {@link #FADE_OUT} blocks and
 * closes over {@link #FADE_LEN}, inside the cavern's open air, where it opens nothing.
 *
 * <p><b>Mouths.</b> {@link #mouth} tells the Galleries, at a point of the cavern, how far the nearest place lies where a
 * run's air meets the cavern and the run's floor there: within {@link #BLEND_MAX} blocks the cavern's floor stands at most
 * {@link #MOUTH_CUT} a block over the run's floor and lies at most as much under it (one row a block once under W +
 * {@link #MOUTH_TAL}, a talus), so the floor at a mouth is the run's and a walker goes in and out at it; the mouth's
 * weight keeps head room and clears hanging rock and bands within {@link #MOUTH_W} blocks. Nothing else of the ground is
 * shaped.
 *
 * <p><b>The tubes.</b> In a country's frame (u across the flow, v along it) the tube axes are the level lines of a warped
 * coordinate {@code U = u + A N(u, v)} at {@code U = (k + 1/2 + phase) S}: parallel, meandering and never branching; at
 * least S over the warp's steepest slope (about S / 2) apart, so two neighbours can only meet where they meander close
 * and one of them runs as twins. Where a twin noise passes a threshold a line splits into two tubes {@code o} either side
 * of it, a pier of rock between them, and they join again further on. The ribs' along-coordinate is v at the nearest
 * point of the (twin) axis, so the ribs stand square across the tube. A column reads the line it lies nearest and the two
 * beside it ({@link #REACH}): a twin's outer tube or a wide bay can reach past the middle between two lines, and the
 * column takes the union of what each near line opens there, its lining the nearest of their sections, so no tube is cut
 * flat where the nearest line changes.
 *
 * <p><b>The section.</b> A round vault (an ellipse {@code R} across, {@link #VSTRETCH} R high) cut by a floor
 * {@link #FLOOR_CUT} of the vault's height under the axis, flat with a shallow dish toward the walls. The floor is taken
 * at the site of the column's column cell (a jittered hex lattice {@link #HEX} across), so it steps in column tops, a
 * row at a time. A rib is a ring: the section shrunk by the rib's depth at the crown and {@link #RIB_WALL} of it at the
 * walls, down to the floor; between the ribs the section is {@link #BULGE} wider and higher (the floor's level stays), so
 * the ring's legs stand off the bays' walls. In the bays the vault's column cells hang 0 to {@link #HANG_MAX} blocks
 * lower, in clusters from a smooth noise.
 *
 * <p><b>Use.</b> The Galleries' field takes {@code min(cave, tube(x, y, z))} block by block in its per-block pass over
 * {@link #spanLo}..{@link #spanHi} of the column where its column cell is rock (the tube is not in Minecraft's 4 x 8 x 4
 * corners: its steps, ribs and hanging ends are thinner than the corner grid); in the cavern's open columns the tube
 * opens nothing and its rock reads as lining. {@link #shellDepth}, {@link #ribShare} and {@link #floorCell} dress the
 * lining, {@link #inLining} is the biome, {@link #pool} the still water. Every value is finite: a fading end closes the
 * section to nothing and the lining's distance reads {@link #SOLID} there.
 */
public final class TubeField {
    static final double SOLID = 64;

    // ------------------------------------------------------------------------------------------- tube countries
    /** Cell of the cellular noise that places tube countries (blocks); at most one country per cell. Public for /remix tp. */
    public static final int CELL = 640;
    /** Share of cells with a tube country. */
    static final double SITE_P = 0.8;
    /**
     * A country's centre lies within this share of CELL of its cell's centre, either way: its rim stays at least 92 blocks
     * inside the cell (170 + 19 of rim warp + 38), room for its runs' mouths and their blend ({@link #GUARD}).
     */
    static final double JIT = 0.06;
    /** The country's half-length along the flow (blocks), and its half-width as a share of that. */
    static final double A_MIN = 120, A_MAX = 170, ASPECT_MIN = 0.48, ASPECT_MAX = 0.7;
    /** The rim's noise warp: amplitude as a share of the half-width, and scale (blocks). */
    static final double RIM_WARP = 0.16, RIM_SCALE = 60;
    /** Beyond this unwarped radius rho skips the rim warp (every run and its blend end well inside it). */
    static final double R_FAR = 2.6;
    /**
     * The most the rim warp moves the ellipse's radius r (1 on the rim): it shifts u and v by at most RIM_WARP b times
     * Perlin's bound of 1.5 each, and r is the norm of (u / b, v / a), so r moves by at most 0.24 sqrt(1 + (b / a)^2), under
     * 0.293 (b / a at most ASPECT_MAX). Where the unwarped radius lies further than this from 1 the side of the rim a point
     * lies on, and a bound on its blocks from the rim, take no noise (the runs' enclosure test and the keep-out).
     */
    static final double RIM_DR = 0.3;
    /** The Obsidian Tubes' climate weight ({@link #region}) comes in from this rho (past the massif's rim, rho 0) to 0.1. */
    static final double RHO_TUBE = -0.13;
    /** Pools only this far inside the massif (rho): well away from the mouths and the cavern's open air. */
    static final double RHO_POOL = 0.10;

    // ------------------------------------------------------------------------------------------- runs and mouths
    /**
     * Runs: a line's tube runs where rock encloses it, the massif's (its collapse gaps are open) or, up to RUN_REACH
     * blocks past the rim, the Galleries' own standing rock ({@link GalleriesLandscape#massif}), and opens at each end at a
     * mouth, where the cavern is open across its whole section. The axis is sampled every RUN_STEP blocks; the enclosure
     * test reads five points across the section, out to SIDE_PAD blocks past its widest (both twins and their bays).
     */
    static final double RUN_STEP = 2, RUN_REACH = 30, SIDE_PAD = 3;
    /** The shortest run carved (blocks of enclosed axis), and the longest oblique mouth (last enclosed sample to first open one). */
    static final double RUN_MIN = 16, MOUTH_MAX = 36;
    /** A run's floor rises or falls at most RUN_SLOPE a block from mouth to mouth. */
    static final double RUN_SLOPE = 0.15;
    /** The highest floor line over W (the lowest is {@link #FLOOR_LOW}). */
    static final double FLOOR_TOP = 86;
    /**
     * A mouth's floor lies within MOUTH_TOL rows of the landscape there, or, where the landscape lies under the lowest
     * floor, is a ledge at most LEDGE_MAX rows over it (the Galleries lay a talus under it); else the run is not carved.
     */
    static final double MOUTH_TOL = 8, LEDGE_MAX = 30;
    /** The section stays whole FADE_OUT blocks past a mouth, then closes over FADE_LEN. */
    static final double FADE_OUT = 4, FADE_LEN = 6;
    /**
     * The mouth query ({@link #mouth}): the Galleries blend their floor to a run's floor out to BLEND_MAX blocks from where
     * its air meets the cavern; the mouth's weight (head room, no hanging rock or bands) falls to 0 over MOUTH_W blocks.
     */
    static final double BLEND_MAX = 36, MOUTH_W = 12;
    /**
     * No run's axis comes within GUARD blocks of its cell's border: its tube's reach ({@link #REACH}) past the fading end,
     * and its blend, stay inside the cell, so a column only ever reads its own cell's country.
     */
    static final double GUARD = 90;
    /** A country stands (the Galleries keep it) where it carves at least this many runs. */
    static final int STAND_RUNS = 2;
    /**
     * The mouths' blend ({@link #above}, {@link #below}): within BLEND_MAX of where a run's air meets the cavern the
     * cavern's floor keeps within MOUTH_CUT rows a block over the run's floor, and under it within MOUTH_CUT rows a block
     * down to W + MOUTH_TAL and a row a block below that (a talus: the Galleries' low ground's stairs wear its column
     * steps into a row a block). Round each mouth the landscape must lie where that blend meets it, out to BLEND_MAX
     * (EDGE_TOL rows over, TALUS_STEP under: a riser the stairs wear), or the run is not carved.
     */
    public static final double MOUTH_CUT = 1 / 3.0, MOUTH_TAL = 50, EDGE_TOL = 2, TALUS_STEP = 10;

    // ------------------------------------------------------------------------------------------- collapse gaps
    /**
     * Collapse gaps (Taran: no passage "too long without branching"; picture 2's lit far ends): the middles of a country's
     * gaps lie at {@code t = (n + gapPh) gapSp} of a warped along-coordinate {@code t = v + GAP_WARP N(u, v)}, spaced
     * GAP_SP_MIN..GAP_SP_MAX along the flow (hashed per country); a gap reaches GAP_HW_MIN..GAP_HW_MAX blocks either side of
     * its middle (a slow noise along it), its warp GAP_WARP blocks over GAP_WARP_L.
     */
    static final double GAP_SP_MIN = 80, GAP_SP_MAX = 120, GAP_HW_MIN = 8, GAP_HW_MAX = 13, GAP_HW_L = 60, GAP_WARP = 14, GAP_WARP_L = 45;
    /** A pool's disc keeps this many blocks more than its radius inside the massif (rim and gaps): its ring and the Galleries' cells. */
    static final double POOL_KEEP = 4.5;

    // ------------------------------------------------------------------------------------------- lines
    /** Spacing of the tube lines across the flow (blocks). */
    static final double S_MIN = 70, S_MAX = 100;
    /**
     * Meander of the lines: warp amplitude (share of S) and its scales across and along the flow (blocks). The warp's slope
     * across the flow stays under 0.75 (WARP_A S_MAX / WARP_LU times Perlin's steepest 2.12), so U never folds.
     */
    static final double WARP_A = 0.34, WARP_LU = 100, WARP_LV = 64;

    // ------------------------------------------------------------------------------------------- section
    /** Half-width of a tube's section at its ribs (blocks): 18 to 24 wide, the bays BULGE more. */
    static final double R_MIN = 9.0, R_MAX = 12.0;
    /** How much a tube's radius wanders along it (share), and over what length (blocks). */
    static final double R_WANDER = 0.12, R_WANDER_L = 45;
    /** The vault's height over the axis, as a share of the half-width. */
    static final double VSTRETCH = 1.5;
    /** The floor lies this share of the (unbulged) vault's height under the axis. */
    static final double FLOOR_CUT = 0.3;
    /**
     * The floor is flat out to BOWL_FLAT of the half-width, then dishes up to BOWL_H of the vault's height at the wall
     * (about 2 rows): a lava tube's floor is its last flow's flat crust. A deeper bowl (0.5 of the vault's height from half
     * way out, until the tube country lost its ring) rose 2 to 4 rows a column cell near the walls, and 15 to 18 % of the
     * tubes' standing places were shelves one could drop from but never climb to; at most a row a cell, every place of
     * the floor walks both ways.
     */
    static final double BOWL_FLAT = 0.4, BOWL_H = 0.12;
    /** Slow wobble of the floor (blocks). */
    static final double FLOOR_WOBBLE = 1.3;
    /**
     * Between the ribs the section's half-width is this much more (its height VSTRETCH times that), at full rib strength:
     * the bays bulge out round the rings, so a ring stands in on its legs down to the floor, not only at its crown. The
     * floor's level stays the unbulged section's.
     */
    static final double BULGE = 2.5;

    // ------------------------------------------------------------------------------------------- twins
    /** Twin noise: over T_ON a line splits into two tubes o either side of it, fully apart T_RAMP further on. */
    static final double T_ON = 0.10, T_RAMP = 0.22, TWIN_L = 100;
    /** Half the pier's width at full split (blocks; between the bays). */
    static final double PIER_MIN = 3.0, PIER_MAX = 5.0;

    // ------------------------------------------------------------------------------------------- height
    /**
     * The lowest floor and the highest vault of the unbulged section (over W); bays rise up to VSTRETCH BULGE more. A run's
     * floor comes from the landscape at its mouths ({@link #FLOOR_TOP} at most), so the vault stays under FLOOR_TOP plus
     * the tallest section.
     */
    static final double FLOOR_MIN = 46, VAULT_MAX = 113;
    /** The lowest a run's floor line lies (over W): its wobbled floor ({@link #FLOOR_WOBBLE}) stays at FLOOR_MIN or over. */
    static final double FLOOR_LOW = FLOOR_MIN + FLOOR_WOBBLE;

    // ------------------------------------------------------------------------------------------- ribs
    /** Rib spacing along the tube (blocks), rib thickness, its soft edge, and how far a rib stands in from the vault. */
    static final double RIB_P_MIN = 8, RIB_P_MAX = 12, RIB_W = 2.5, RIB_EDGE = 0.75, RIB_D_MIN = 2.0, RIB_D_MAX = 3.0;
    /** A rib is a ring: it stands in from the walls this share of its depth, down to the floor (its jambs). */
    static final double RIB_WALL = 0.3;
    /** No hanging column ends in a cell whose site is within this many blocks of a rib's edge (the rings stay clean). */
    static final double FRINGE_CLEAR = 1.5;
    /** Rib strength noise along the tube: under RIB_OFF no ribs, full ribs RIB_RAMP over it; its scale. */
    static final double RIB_OFF = -0.45, RIB_RAMP = 0.3, RIB_L = 50;

    // ------------------------------------------------------------------------------------------- column cells
    /** Spacing of the column cells (blocks) and their jitter (share). */
    static final double HEX = 2.6, HEX_JIT = 0.3;
    /**
     * Hanging column ends, by whole column cells: a cell hangs {@code min(HANG_MAX, floor(HANG_MAX t + hash))} blocks,
     * {@code t = (N(site / HANG_L) - HANG_T) / HANG_W} clamped to 0..1, so the ends come in clusters, longest in their
     * middle, ragged by a block from cell to cell.
     */
    static final double HANG_T = 0.1, HANG_W = 0.35, HANG_L = 7, HANG_MAX = 3;
    /** Hanging columns only where the vault is this share of the half-width from the axis or nearer. */
    static final double HANG_REACH = 0.8;

    // ------------------------------------------------------------------------------------------- pools
    /** Pool candidates: a jittered grid of this spacing (blocks), the share that are dips, their radius. */
    static final double DIP_SP = 14, DIP_P = 0.42, DIP_R_MIN = 2.5, DIP_R_MAX = 4.5;
    /** A dip wider than this is two deep in its middle (DIP_DEEP of the radius). */
    static final double DIP_TWO = 3.4, DIP_DEEP = 0.55;
    /** A pool's dip lies on the flat middle of the floor (share of the half-width from the axis). */
    static final double DIP_MID = 0.45;

    /**
     * The keep-out of a tube country ({@link #apronAt}): full out to APRON_OUT - APRON_FADE blocks past the rim (and in
     * the gaps) and in the mouths' blends, gone at APRON_OUT; the openings and the ramps (over 0.02: about 11 blocks out)
     * stay out of it as they stay off the cavern's own massifs (their 0.06 and 0.08 under the massifs' threshold lie a
     * median of 9.6 and 12.9 blocks from the edge, 5.3 and 7.0 at the tenth percentile: scratch VMargin, seeds 8675309 and
     * 1). Nothing of the cavern's ground is shaped by it. (With the ring it reached 56 blocks out all round, over the
     * ring's reshaped floor; kept so, it left two of the eight holes in the Wasteland's square over the country that seed
     * 1 now keeps at 335, 340, which carves five runs; 24 blocks out left three, 12 four.) APRON_HEAD: the head room the
     * Galleries keep over a run's floor at its mouths (over the tallest bay).
     */
    public static final double APRON_OUT = 12, APRON_FADE = 10, APRON_HEAD = 28;

    /** The lining's reach into the rock, for the biome (blocks). */
    public static final double SHELL_BIOME = 4;

    final long seed;
    /** The lava table (first block over the lava): heights are over it. */
    final int W;
    /** The Galleries' landscape before any tube is cut: the runs' floors and the standing rock they go on through. */
    final GalleriesLandscape land;
    private final Perlin rimA, rimB, warpN, twinN, radN, floorN, ribN, ribJ, gapN, gapW, hangN;

    public TubeField(long seed, int lavaTable) {
        this(seed, lavaTable, new GalleriesLandscape(seed, lavaTable));
    }

    /** The tube field over the Galleries' landscape {@code land} (of the same seed and lava table). */
    TubeField(long seed, int lavaTable, GalleriesLandscape land) {
        this.seed = seed;
        this.W = lavaTable;
        this.land = land;
        long s = seed ^ 0x5DEECE66DA3B1F27L;
        rimA = new Perlin(Hashing.mix(s, 1, 0, 0));
        rimB = new Perlin(Hashing.mix(s, 2, 0, 0));
        warpN = new Perlin(Hashing.mix(s, 3, 0, 0));
        twinN = new Perlin(Hashing.mix(s, 4, 0, 0));
        radN = new Perlin(Hashing.mix(s, 5, 0, 0));
        floorN = new Perlin(Hashing.mix(s, 7, 0, 0));
        ribN = new Perlin(Hashing.mix(s, 8, 0, 0));
        ribJ = new Perlin(Hashing.mix(s, 9, 0, 0));
        gapN = new Perlin(Hashing.mix(s, 10, 0, 0));
        gapW = new Perlin(Hashing.mix(s, 11, 0, 0));
        hangN = new Perlin(Hashing.mix(s, 12, 0, 0));
        cache = ThreadLocal.withInitial(() -> {
            Col[] a = new Col[1024];
            for (int i = 0; i < a.length; i++) a[i] = new Col();
            return a;
        });
    }

    static double clamp01(double t) { return t < 0 ? 0 : (t > 1 ? 1 : t); }
    static double smooth(double t) { t = clamp01(t); return t * t * (3 - 2 * t); }
    private double h(long a, long b, long c) { return Hashing.unit(Hashing.mix(seed ^ 0x2545F4914F6CDD1DL, a, b, c)); }

    // ================================================================================================ countries
    /**
     * A tube country: one per cell at most ({@code on} false where the cell has none). Immutable. Public for the
     * Galleries' description of a column ({@code GalleriesField.describe}) and the tests, which read its frame and size;
     * whether it stands is {@link #stands}.
     */
    public static final class Country {
        public final int ci, cj;
        public final boolean on;
        public final double ox, oz, cos, sin, a, b, S, phase, nu, nv, rimWarp;
        /** The collapse gaps' spacing along the flow (blocks) and phase (share of it). */
        final double gapSp, gapPh;

        Country(int ci, int cj, boolean on, double ox, double oz, double ang, double a, double b, double S, double phase,
                double nu, double nv, double gapSp, double gapPh) {
            this.ci = ci; this.cj = cj; this.on = on; this.ox = ox; this.oz = oz;
            this.cos = StrictMath.cos(ang); this.sin = StrictMath.sin(ang);
            this.a = a; this.b = b; this.S = S; this.phase = phase;
            this.nu = nu; this.nv = nv;
            this.rimWarp = RIM_WARP * b;
            this.gapSp = gapSp;
            this.gapPh = gapPh;
        }

        /** Along the flow. */
        double v(double x, double z) { return (x - ox) * cos + (z - oz) * sin; }
        /** Across the flow. */
        double u(double x, double z) { return -(x - ox) * sin + (z - oz) * cos; }
    }

    /** The tube country of cell (ci, cj) (pure: hashed from the seed and the cell). */
    Country country(int ci, int cj) {
        long k = Hashing.mix(seed ^ 0x7F4A7C159E3779B9L, ci, cj, 77);
        if (Hashing.unit(k) >= SITE_P) return new Country(ci, cj, false, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 0);
        double ox = (ci + 0.5 + JIT * 2 * (Hashing.unit(Hashing.mix(k, 1, 0, 0)) - 0.5)) * CELL;
        double oz = (cj + 0.5 + JIT * 2 * (Hashing.unit(Hashing.mix(k, 2, 0, 0)) - 0.5)) * CELL;
        double ang = Math.PI * 2 * Hashing.unit(Hashing.mix(k, 3, 0, 0));
        double a = A_MIN + (A_MAX - A_MIN) * Hashing.unit(Hashing.mix(k, 4, 0, 0));
        double b = a * (ASPECT_MIN + (ASPECT_MAX - ASPECT_MIN) * Hashing.unit(Hashing.mix(k, 5, 0, 0)));
        double S = S_MIN + (S_MAX - S_MIN) * Hashing.unit(Hashing.mix(k, 6, 0, 0));
        double phase = Hashing.unit(Hashing.mix(k, 7, 0, 0));
        double nu = 1000 * Hashing.unit(Hashing.mix(k, 10, 0, 0)), nv = 1000 * Hashing.unit(Hashing.mix(k, 11, 0, 0));
        double gapSp = GAP_SP_MIN + (GAP_SP_MAX - GAP_SP_MIN) * Hashing.unit(Hashing.mix(k, 12, 0, 0));
        double gapPh = Hashing.unit(Hashing.mix(k, 13, 0, 0));
        return new Country(ci, cj, true, ox, oz, ang, a, b, S, phase, nu, nv, gapSp, gapPh);
    }

    private final ThreadLocal<Country[]> lastCountry = ThreadLocal.withInitial(() -> new Country[1]);

    /** The country of the cell holding (x, z) (a per-thread one-entry cache of {@link #country}). */
    public Country countryAt(double x, double z) {
        int ci = (int) Math.floor(x / CELL), cj = (int) Math.floor(z / CELL);
        Country[] last = lastCountry.get();
        Country c = last[0];
        if (c != null && c.ci == ci && c.cj == cj) return c;
        c = country(ci, cj);
        last[0] = c;
        return c;
    }

    /**
     * How deep (x, z) lies in its cell's tube country: 1 - r, r the noise-warped ellipse radius (1 on the rim). Over 0 is
     * the massif (but for its collapse gaps). -9 where no country is near.
     */
    double rho(Country c, double x, double z) {
        if (!c.on) return -9;
        double u = c.u(x, z), v = c.v(x, z);
        double r0 = Math.sqrt((v / c.a) * (v / c.a) + (u / c.b) * (u / c.b));
        if (r0 > R_FAR) return 1 - r0;   // beyond every run and its blend: the warp no longer matters
        double wu = u + c.rimWarp * rimA.noise2(x / RIM_SCALE + c.nu, z / RIM_SCALE), wv = v + c.rimWarp * rimB.noise2(x / RIM_SCALE, z / RIM_SCALE + c.nv);
        return 1 - Math.sqrt((wv / c.a) * (wv / c.a) + (wu / c.b) * (wu / c.b));
    }

    /**
     * Over 0 in a tube country's massif: the Galleries keep rock there from floor to vault. About the distance in blocks
     * from the massif's rim (rho over the unwarped ellipse's gradient, so the tips read true too) or from the nearest
     * collapse gap's edge ({@link #gap}), whichever is nearer; -SOLID with no country.
     */
    public double massif(int x, int z) { return massifAt(x + 0.5, z + 0.5); }

    /** {@link #massif} at any point (the Galleries read it at their column cells' sites). */
    public double massifAt(double x, double z) {
        Country c = countryAt(x, z);
        if (!c.on) return -SOLID;
        return withGaps(c, x, z, blocks(c, x, z, rho(c, x, z)));
    }

    /** m, the distance from the rim in blocks, or the distance from the nearest gap's edge where that is less. */
    double withGaps(Country c, double x, double z, double m) {
        // a gap reaches at most GAP_HW_MAX under 0, so it only matters where the rim's distance is over that
        if (m <= -GAP_HW_MAX) return m;
        return Math.min(m, gap(c, c.u(x, z), c.v(x, z)));
    }

    /**
     * Distance in blocks (first order) from the point (u, v) of country c's frame to the nearer edge of its nearest
     * collapse gap: under 0 inside the gap. The gaps' middles are the level lines {@code t = (n + gapPh) gapSp} of
     * {@code t = v + GAP_WARP N(u, v)} (its slope along v stays over 0.3, so each is one wavy line across the country); the
     * distance to one is |t - t_n| over |grad t|, less the gap's half-width there.
     */
    double gap(Country c, double u, double v) {
        double L = GAP_WARP_L, e = 0.5, nu = c.nu, nv = c.nv;
        double t = v + GAP_WARP * gapN.noise2(u / L + nu, v / L + nv);
        double tu = GAP_WARP * (gapN.noise2((u + e) / L + nu, v / L + nv) - gapN.noise2((u - e) / L + nu, v / L + nv)) / (2 * e);
        double tv = 1 + GAP_WARP * (gapN.noise2(u / L + nu, (v + e) / L + nv) - gapN.noise2(u / L + nu, (v - e) / L + nv)) / (2 * e);
        double gt = Math.max(0.25, Math.sqrt(tu * tu + tv * tv));
        double f = t / c.gapSp - c.gapPh;
        double n = Math.floor(f + 0.5);
        double hw = GAP_HW_MIN + (GAP_HW_MAX - GAP_HW_MIN) * smooth(0.5 + gapW.noise2(u / GAP_HW_L + nv, n * 3.7 + nu));
        return Math.abs(f - n) * c.gapSp / gt - hw;
    }

    /** {@link #gap} at (x, z) of the world, or SOLID where no country is (for tools and tests). */
    double gapAt(double x, double z) {
        Country c = countryAt(x, z);
        return c.on ? gap(c, c.u(x, z), c.v(x, z)) : SOLID;
    }

    /** rho in blocks: rho over the gradient of the unwarped ellipse radius (between rho b and rho a). */
    static double blocks(Country c, double x, double z, double rho) {
        double u = c.u(x, z), v = c.v(x, z);
        double r0 = Math.sqrt((v / c.a) * (v / c.a) + (u / c.b) * (u / c.b));
        if (r0 < 1e-3) return rho * c.b;
        double gv = v / (c.a * c.a), gu = u / (c.b * c.b);
        double g = Math.sqrt(gv * gv + gu * gu) / r0;
        return rho / Math.max(g, 1 / c.a);
    }

    /**
     * The keep-out of a tube country, 0..1, where the Galleries' openings and ramps do not go (over 0.02): 1 in its
     * massif and its collapse gaps and out to APRON_OUT - APRON_FADE blocks past them, 0 from {@link #APRON_OUT} blocks
     * out, and 1 within a mouth's blend ({@link #mouth}, BLEND_MAX blocks of where a run's air meets the cavern), whose
     * floor and head room are the tube's. Nothing of the cavern's ground or rock is shaped by it.
     */
    public double apronAt(double x, double z) {
        Country c = countryAt(x, z);
        if (!c.on) return 0;
        double k = 0;
        // the rim's ring only where the rim may lie within APRON_OUT blocks (and one more, for rounding): the warp moves r
        // by RIM_DR at most
        double r0 = radius0(c, x, z);
        if (r0 <= 1 + RIM_DR || (1 - r0 + RIM_DR) * rhoBlocks(c, x, z) > -APRON_OUT - 1) {
            double m = withGaps(c, x, z, blocks(c, x, z, rho(c, x, z)));
            k = smooth((m + APRON_OUT) / APRON_FADE);
            if (k >= 1) return 1;
        }
        return nearestMeet(runs(c), x, z) != null ? 1 : k;
    }

    /** The unwarped ellipse radius of (x, z) in country c (1 on the unwarped rim; {@link #rho} warps it). */
    static double radius0(Country c, double x, double z) {
        double u = c.u(x, z), v = c.v(x, z);
        return Math.sqrt((v / c.a) * (v / c.a) + (u / c.b) * (u / c.b));
    }

    /** Blocks per unit of rho at (x, z) ({@link #blocks} divides rho by the inverse): a bound's scale, not bit for bit. */
    static double rhoBlocks(Country c, double x, double z) {
        double u = c.u(x, z), v = c.v(x, z);
        double r0 = Math.sqrt((v / c.a) * (v / c.a) + (u / c.b) * (u / c.b));
        if (r0 < 1e-3) return c.b;
        double gv = v / (c.a * c.a), gu = u / (c.b * c.b);
        return 1 / Math.max(Math.sqrt(gv * gv + gu * gu) / r0, 1 / c.a);
    }

    /**
     * About the blocks from (x, z) to its country's rim, over 0 inside it whether in a collapse gap or not ({@link #massif}
     * without the gaps); -SOLID with no country. Inside the rim the country's massif and gaps stand for the cavern's own
     * massifs (the Galleries keep none of theirs there).
     */
    public double rimAt(double x, double z) {
        Country c = countryAt(x, z);
        if (!c.on) return -SOLID;
        return blocks(c, x, z, rho(c, x, z));
    }

    /** 0 outside any tube country, 1 well inside: the Obsidian Tubes' climate weight. */
    public double region(int x, int z) {
        Country c = countryAt(x + 0.5, z + 0.5);
        if (!c.on) return 0;
        return smooth((rho(c, x + 0.5, z + 0.5) - RHO_TUBE) / (0.1 - RHO_TUBE));
    }

    // ================================================================================================ runs
    /**
     * One run of a line: the stretch of tube between its two mouths, oa < ob (the along-coordinate phi of the first axis
     * samples past its enclosed stretch where the cavern is open across the whole section); ea and eb the first and last
     * enclosed samples (between a mouth and them the section opens obliquely); its floor ya at oa and yb at ob (absolute
     * y, straight between them); and its side windows, phi intervals in pairs where the section is open on a side between
     * enclosed samples.
     */
    static final class Run {
        final double oa, ob, ea, eb, ya, yb;
        final double[] win;

        Run(double oa, double ob, double ea, double eb, double ya, double yb, double[] win) {
            this.oa = oa; this.ob = ob; this.ea = ea; this.eb = eb; this.ya = ya; this.yb = yb; this.win = win;
        }

        /** The floor at phi (absolute y, unrounded). */
        double floor(double phi) { return ya + (yb - ya) * clamp01((phi - oa) / (ob - oa)); }

        /** 1 between the mouths and FADE_OUT past them, closing to 0 over the next FADE_LEN. */
        double fade(double phi) {
            return smooth((phi - (oa - FADE_OUT - FADE_LEN)) / FADE_LEN) * smooth((ob + FADE_OUT + FADE_LEN - phi) / FADE_LEN);
        }
    }

    /**
     * Where a run's air meets the cavern: a mouth's open stretch out to FADE_OUT past it (from the open sample's end to the
     * first enclosed sample) or a side window, as the axis samples along it (x, z, along-coordinate v) and the section's
     * outer half-width at each (both twins and their bulged bays, {@link #spread}), with a circle round it out to
     * BLEND_MAX past the section ({@link #mouth} looks no further).
     */
    static final class Meet {
        final Run run;
        final double[] x, z, v, r;
        final double cx, cz, rad;

        Meet(Run run, double[] x, double[] z, double[] v, double[] r) {
            this.run = run; this.x = x; this.z = z; this.v = v; this.r = r;
            int q = x.length;
            cx = 0.5 * (x[0] + x[q - 1]);
            cz = 0.5 * (z[0] + z[q - 1]);
            double m = 0;
            for (int i = 0; i < q; i++) m = Math.max(m, Math.hypot(x[i] - cx, z[i] - cz) + r[i]);
            rad = m + BLEND_MAX + 1;
        }

        /**
         * Blocks from (x, z) to the section's footprint along the stretch, from the nearest point of its axis: past an end of
         * the stretch, the hypotenuse of how far out along the tube and how far beside its walls; elsewhere how far past
         * its walls. With {@code phi} true, the along-coordinate there instead (past an end, that far beyond it).
         */
        double dist(double px, double pz, boolean phi) {
            int q = x.length, bi = 0;
            double bt = 0, bd = Double.MAX_VALUE;
            for (int i = 0; i + 1 < q; i++) {
                double ex = x[i + 1] - x[i], ez = z[i + 1] - z[i], l2 = ex * ex + ez * ez;
                double t = l2 > 1e-12 ? ((px - x[i]) * ex + (pz - z[i]) * ez) / l2 : 0;
                t = t < 0 ? 0 : (t > 1 ? 1 : t);
                double ox = px - x[i] - t * ex, oz = pz - z[i] - t * ez, d = ox * ox + oz * oz;
                if (d < bd) { bd = d; bi = i; bt = t; }
            }
            // past the first sample, or past the last: along the tube from the end, and across it
            int e0 = bt == 0 && bi == 0 ? 0 : (bt == 1 && bi == q - 2 ? q - 1 : -1);
            if (e0 >= 0) {
                int e1 = e0 == 0 ? 1 : q - 2;
                double ex = x[e0] - x[e1], ez = z[e0] - z[e1], l = Math.sqrt(ex * ex + ez * ez);
                double along = l > 1e-6 ? ((px - x[e0]) * ex + (pz - z[e0]) * ez) / l : 0;
                if (along > 0) {
                    if (phi) return v[e0] + (e0 == 0 ? -along : along);
                    double across = Math.abs((px - x[e0]) * ez - (pz - z[e0]) * ex) / l;
                    return Math.hypot(along, Math.max(0, across - r[e0]));
                }
            }
            if (phi) return v[bi] + bt * (v[bi + 1] - v[bi]);
            return Math.max(0, Math.sqrt(bd) - (r[bi] + bt * (r[bi + 1] - r[bi])));
        }
    }

    /** A country's runs: per line k0 .. k0 + lines.length - 1, and what was looked at (for tools and tests). */
    static final class Runs {
        int ci, cj, k0;
        Run[][] lines = new Run[0][];
        /** Runs carved; stretches of the country's lines enclosed by rock (carved or not); their enclosed blocks. */
        int carved, candidates;
        double carvedLen, candidateLen;
        /**
         * Why candidates were left: no open sample at an end, an oblique end too long, too short, a mouth's floor off the
         * landscape, the landscape round a mouth off the blend.
         */
        int noExit, oblique, tooShort, offLand, offBlend;
        /** The field's reach past the rim (blocks): the furthest a run's fading end lies, plus {@link #REACH}. */
        double cut = -1e9;
        boolean stands;
        /** Where the carved runs' air meets the cavern: their mouths' open stretches and their side windows. */
        Meet[] meets = new Meet[0];

        /** The run of line k whose extent holds phi (the one least faded), or null. */
        Run at(int k, double phi) {
            int i = k - k0;
            if (i < 0 || i >= lines.length) return null;
            Run best = null;
            double bf = 0;
            for (Run r : lines[i]) {
                if (phi <= r.oa - FADE_OUT - FADE_LEN || phi >= r.ob + FADE_OUT + FADE_LEN) continue;
                double f = r.fade(phi);
                if (best == null || f > bf) { best = r; bf = f; }
            }
            return best;
        }

        Run[] line(int k) {
            int i = k - k0;
            return i < 0 || i >= lines.length ? null : lines[i];
        }
    }

    /**
     * The countries' runs a thread keeps, most recently used first: every country a chunk's columns can read (its cell's
     * and up to three beside it) for several places far apart at once, and the countries round them that the Galleries'
     * openings look at (their search for the Wasteland's openings and their ramps reaches some 1,300 blocks, over 15
     * countries round each place). Kept by slots of the cell's coordinates, two regions 5,000 blocks apart on one thread
     * (two players) shared a slot and made each other's runs again chunk by chunk (GalleriesFieldTest.costWithinBudget:
     * 3.4 times the Gardens' rows, bar 3); 12 kept made 43 runs for 33 countries in that test's two regions.
     */
    static final int RUN_CACHE = 32;
    private final ThreadLocal<Runs[]> runCache = ThreadLocal.withInitial(() -> new Runs[RUN_CACHE]);
    /** Whether a country stands, per thread, by a hash of its cell (key times 2, plus 1 where it stands). */
    private final ThreadLocal<long[]> standCache = ThreadLocal.withInitial(() -> {
        long[] a = new long[256];
        java.util.Arrays.fill(a, Long.MIN_VALUE);
        return a;
    });

    /** Country c's runs (computed once per thread and kept; pure: a function of the seed and the cell). */
    Runs runs(Country c) {
        Runs[] a = runCache.get();
        for (int i = 0; i < a.length && a[i] != null; i++) {
            Runs r = a[i];
            if (r.ci != c.ci || r.cj != c.cj) continue;
            if (i > 0) {
                System.arraycopy(a, 0, a, 1, i);
                a[0] = r;
            }
            return r;
        }
        Runs r = computeRuns(c);
        System.arraycopy(a, 0, a, 1, a.length - 1);
        a[0] = r;
        return r;
    }

    /**
     * Whether the country of tube cell (ci, cj) stands: it carves at least {@link #STAND_RUNS} runs (kept per thread by the
     * cell, so a country whose runs have left {@link #runCache} is not made again to answer this).
     */
    public boolean stands(int ci, int cj) {
        long key = ((long) ci << 32) ^ (cj & 0xFFFFFFFFL);
        long[] sc = standCache.get();
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 56);
        if (sc[slot] == (key << 1 | 1)) return true;
        if (sc[slot] == key << 1) return false;
        Country c = country(ci, cj);
        boolean on = c.on && runs(c).stands;
        sc[slot] = key << 1 | (on ? 1 : 0);
        return on;
    }

    /**
     * Rock that encloses a tube at (x, z): inside the country's rim its massif (open in the collapse gaps), outside it
     * the Galleries' own standing rock. The rim's warp is read only where the unwarped radius lies within RIM_DR of 1.
     */
    private boolean rockAt(Country c, double x, double z) {
        double r0 = radius0(c, x, z);
        return rockAt(c, x, z, r0 < 1 - RIM_DR || (r0 <= 1 + RIM_DR && rho(c, x, z) > 0));
    }

    /** {@link #rockAt} where it is known whether (x, z) lies inside the rim. */
    private boolean rockAt(Country c, double x, double z, boolean inside) {
        return inside ? gap(c, c.u(x, z), c.v(x, z)) > 0 : land.massifStands(x, z);
    }

    /** The across-coordinate u of line k's axis at along-coordinate v (Newton on U from u). */
    private double axisU(Country c, int k, double v, double u) {
        double target = (k + 0.5 + c.phase) * c.S, A = WARP_A * c.S, e = 0.5;
        for (int it = 0; it < 30; it++) {
            double U = u + A * warpN.noise2(u / WARP_LU + c.nu, v / WARP_LV + c.nv);
            double dU = 1 + A * (warpN.noise2((u + e) / WARP_LU + c.nu, v / WARP_LV + c.nv) - warpN.noise2((u - e) / WARP_LU + c.nu, v / WARP_LV + c.nv)) / (2 * e);
            double du = (U - target) / dU;
            u -= du;
            if (Math.abs(du) < 1e-4) break;
        }
        return u;
    }

    /** Line k's half-width at along-coordinate v: its widest bay, and the pier and the other twin's where it splits. */
    double spread(Country c, int k, double v) {
        long lk = Hashing.mix(seed, c.ci * 31L + c.cj, k, 991);
        return spread(c, k, v, R0(lk), pier(lk));
    }

    /** Line k's base half-width R0 and half its pier's width, from its hash lk ({@link #geom}). */
    static double R0(long lk) { return R_MIN + (R_MAX - R_MIN) * StrictMath.pow(Hashing.unit(lk), 0.9); }
    static double pier(long lk) { return PIER_MIN + (PIER_MAX - PIER_MIN) * Hashing.unit(Hashing.mix(lk, 1, 0, 0)); }

    /** {@link #spread(Country, int, double)} with the line's R0 and pier known. */
    private double spread(Country c, int k, double v, double R0, double pier) {
        double split = smooth((twinN.noise2(v / TWIN_L + c.nv, k * 7.77 + 3.1 + c.nu) - T_ON) / T_RAMP);
        double R = R0 * (1 + R_WANDER * radN.noise2(v / R_WANDER_L + c.nv, k * 5.31 + c.nu));
        double ribK = smooth((ribN.noise2(v / RIB_L + c.nv, k * 3.73 + c.nu) - RIB_OFF) / RIB_RAMP);
        double bay = R + BULGE * ribK;
        return (bay + pier) * split + bay;
    }

    /** Blocks from (x, z) to the nearest border of tube cell (ci, cj) (negative outside it). */
    static double borderDist(int ci, int cj, double x, double z) {
        double fx = x - (double) ci * CELL, fz = z - (double) cj * CELL;
        return Math.min(Math.min(fx, CELL - fx), Math.min(fz, CELL - fz));
    }

    /** The landscape's floor at (x, z) clamped to the tubes' floors (absolute y). */
    private double clampFloor(double l) { return Math.max(W + FLOOR_LOW, Math.min(W + FLOOR_TOP, l)); }

    /** Whether a run's floor y at a mouth meets the landscape l there ({@link #MOUTH_TOL}, {@link #LEDGE_MAX}). */
    private boolean meets(double y, double l) {
        if (l >= W + FLOOR_LOW) return Math.abs(y - l) <= MOUTH_TOL;
        return y - l <= LEDGE_MAX && y - (W + FLOOR_LOW) <= MOUTH_TOL;
    }

    /** Rows the mouths' blend lets the cavern's floor stand over a run's floor, d blocks from where its air meets the cavern. */
    static double above(double d) { return MOUTH_CUT * d; }

    /** Rows the mouths' blend lets the cavern's floor lie under a run's floor y (absolute), d blocks out (see {@link #MOUTH_CUT}). */
    double below(double y, double d) {
        double d1 = Math.max(0, (y - (W + MOUTH_TAL)) / MOUTH_CUT);
        return d <= d1 ? MOUTH_CUT * d : MOUTH_CUT * d1 + (d - d1);
    }

    /** The ring a mouth's landscape is read on: distances out, and the directions' cosines and sines off the outward one. */
    private static final double[] RING_D = {BLEND_MAX / 2, BLEND_MAX};
    private static final double[] RING_C = {0.5, 0.8660254037844386, 1, 0.8660254037844386, 0.5}, RING_S = {-0.8660254037844386, -0.5, 0, 0.5, 0.8660254037844386};

    /**
     * Whether the landscape round a mouth at (x, z), outward along the unit (tx, tz), lies where the blend from the run's
     * floor y meets it: at BLEND_MAX / 2 and BLEND_MAX blocks out, in five directions within 60 degrees of the outward one,
     * wherever the cavern is open there, no more than EDGE_TOL rows over what the blend lets stand, and no more than
     * TALUS_STEP rows under the talus (or lava). Else the blend would end in a cliff round the mouth.
     */
    private boolean blendMeets(Country c, double x, double z, double tx, double tz, double y) {
        for (double d : RING_D) for (int a = 0; a < RING_C.length; a++) {
            double qx = x + d * (RING_C[a] * tx - RING_S[a] * tz), qz = z + d * (RING_S[a] * tx + RING_C[a] * tz);
            if (rockAt(c, qx, qz)) continue;
            double l = land.floor(qx, qz);
            if (l > y + above(d) + EDGE_TOL) return false;
            if (l >= W && l < y - below(y, d) - TALUS_STEP) return false;
        }
        return true;
    }

    /** The unit vector from sample j toward sample i of the axis (the outward way at a mouth). */
    private static double[] outward(double[] ax, double[] az, int j, int i) {
        double dx = ax[i] - ax[j], dz = az[i] - az[j], l = Math.sqrt(dx * dx + dz * dz);
        return l < 1e-9 ? new double[]{1, 0} : new double[]{dx / l, dz / l};
    }

    /**
     * Country c's runs, line by line: the axis sampled every RUN_STEP blocks out to its tips and RUN_REACH past them;
     * each sample enclosed (rock at all five points across the section and SIDE_PAD past it), open (rock at none), or
     * partly open; out past RUN_REACH outside the rim or within GUARD of the cell's border. A run is a stretch of samples
     * that are not open, holding an enclosed one inside the rim, with an open sample at each end (its mouths), oblique
     * ends of at most MOUTH_MAX, RUN_MIN enclosed blocks, and floors at its mouths that meet the landscape there: the
     * landscape's floor at each mouth, clamped to FLOOR_LOW..FLOOR_TOP, straight between them and no steeper than
     * RUN_SLOPE (the two ends moved toward each other where they lie further apart). Where its air meets the cavern (its
     * mouths' open stretches and its side windows) is kept for the mouth query ({@link Meet}). The rim's warp is read only
     * where the unwarped radius says it can matter ({@link #RIM_DR}): the same runs, bit for bit, as reading it everywhere.
     */
    private Runs computeRuns(Country c) {
        Runs rs = new Runs();
        rs.ci = c.ci;
        rs.cj = c.cj;
        if (!c.on) return rs;
        double A = WARP_A * c.S;
        double uMax = c.b + c.rimWarp + RUN_REACH + 2 * (R_MAX * (1 + R_WANDER) + BULGE) + PIER_MAX + SIDE_PAD;
        int kLo = (int) Math.floor((-uMax - A) / c.S - c.phase - 0.5), kHi = (int) Math.ceil((uMax + A) / c.S - c.phase - 0.5);
        double V = c.a + c.rimWarp + RUN_REACH + MOUTH_MAX + RUN_STEP;
        int n = (int) Math.ceil(2 * V / RUN_STEP) + 1;
        rs.k0 = kLo;
        rs.lines = new Run[kHi - kLo + 1][];
        byte[] st = new byte[n];
        boolean[] inRim = new boolean[n];
        double[] ax = new double[n], az = new double[n], mm = new double[n], sp = new double[n];
        java.util.ArrayList<Run> out = new java.util.ArrayList<>();
        java.util.ArrayList<Double> win = new java.util.ArrayList<>();
        java.util.ArrayList<Meet> meets = new java.util.ArrayList<>();
        double reach = -1e9;
        for (int k = kLo; k <= kHi; k++) {
            long lk = Hashing.mix(seed, c.ci * 31L + c.cj, k, 991);
            double R0 = R0(lk), pier = pier(lk);
            double u = (k + 0.5 + c.phase) * c.S;
            for (int i = 0; i < n; i++) {
                double v = -V + i * RUN_STEP;
                u = axisU(c, k, v, u);
                double x = c.ox + v * c.cos - u * c.sin, z = c.oz + v * c.sin + u * c.cos;
                ax[i] = x;
                az[i] = z;
                sp[i] = Double.NaN;
                // inside the rim or not, and out past RUN_REACH or not: the blocks from the rim (mm, NaN until read) only
                // where the unwarped radius leaves it open
                double r0 = radius0(c, x, z), m = Double.NaN;
                boolean in;
                if (r0 < 1 - RIM_DR) in = true;
                else if (r0 > 1 + RIM_DR) in = false;
                else { m = blocks(c, x, z, rho(c, x, z)); in = m > 0; }
                boolean far = false;
                if (!in) {
                    double sc = rhoBlocks(c, x, z);
                    if ((1 - r0 + RIM_DR) * sc < -RUN_REACH - 1) far = true;
                    else if ((1 - r0 - RIM_DR) * sc <= -RUN_REACH + 1) {
                        if (m != m) m = blocks(c, x, z, rho(c, x, z));
                        far = m < -RUN_REACH;
                    }
                }
                mm[i] = m;
                inRim[i] = in;
                if (far || borderDist(c.ci, c.cj, x, z) < GUARD) { st[i] = -1; continue; }
                // the line's normal at the axis point, and five points across its section (the axis point first: one
                // rock and one open point make the sample partly open)
                double e = 0.5;
                double dUdu = 1 + A * (warpN.noise2((u + e) / WARP_LU + c.nu, v / WARP_LV + c.nv) - warpN.noise2((u - e) / WARP_LU + c.nu, v / WARP_LV + c.nv)) / (2 * e);
                double dUdv = A * (warpN.noise2(u / WARP_LU + c.nu, (v + e) / WARP_LV + c.nv) - warpN.noise2(u / WARP_LU + c.nu, (v - e) / WARP_LV + c.nv)) / (2 * e);
                double g = Math.sqrt(dUdu * dUdu + dUdv * dUdv);
                double gx = (-dUdu * c.sin + dUdv * c.cos) / g, gz = (dUdu * c.cos + dUdv * c.sin) / g;
                sp[i] = spread(c, k, v, R0, pier);
                double hw = sp[i] + SIDE_PAD;
                boolean r = rockAt(c, x, z, in), mixed = false;
                for (int j = -2; j <= 2 && !mixed; j++) {
                    if (j == 0) continue;
                    mixed = rockAt(c, x + j * 0.5 * hw * gx, z + j * 0.5 * hw * gz) != r;
                }
                st[i] = (byte) (mixed ? 1 : (r ? 2 : 0));
            }
            out.clear();
            for (int i = 0; i < n; ) {
                if (st[i] <= 0) { i++; continue; }
                int i0 = i, fe = -1, le = -1, encl = 0;
                boolean rim = false;
                while (i < n && st[i] > 0) {
                    if (st[i] == 2) { if (fe < 0) fe = i; le = i; encl++; rim |= inRim[i]; }
                    i++;
                }
                int i1 = i - 1;
                if (encl == 0 || !rim) continue;   // not this country's: the Galleries' own rock, or never enclosed
                rs.candidates++;
                rs.candidateLen += encl * RUN_STEP;
                if (i0 == 0 || i1 == n - 1 || st[i0 - 1] != 0 || st[i1 + 1] != 0) { rs.noExit++; continue; }
                double oa = -V + (i0 - 1) * RUN_STEP, ob = -V + (i1 + 1) * RUN_STEP, ea = -V + fe * RUN_STEP, eb = -V + le * RUN_STEP;
                if (ea - oa > MOUTH_MAX || ob - eb > MOUTH_MAX) { rs.oblique++; continue; }
                if (encl * RUN_STEP < RUN_MIN) { rs.tooShort++; continue; }
                double la = land.floor(ax[i0 - 1], az[i0 - 1]), lb = land.floor(ax[i1 + 1], az[i1 + 1]);
                double ya = clampFloor(la), yb = clampFloor(lb), lim = RUN_SLOPE * (ob - oa);
                if (Math.abs(yb - ya) > lim) {
                    double mid = 0.5 * (ya + yb), sg = Math.signum(yb - ya);
                    ya = mid - sg * 0.5 * lim;
                    yb = mid + sg * 0.5 * lim;
                }
                if (!meets(ya, la) || !meets(yb, lb)) { rs.offLand++; continue; }
                double[] ta = outward(ax, az, fe, i0 - 1), tb = outward(ax, az, le, i1 + 1);
                if (!blendMeets(c, ax[i0 - 1], az[i0 - 1], ta[0], ta[1], ya) || !blendMeets(c, ax[i1 + 1], az[i1 + 1], tb[0], tb[1], yb)) { rs.offBlend++; continue; }
                win.clear();
                for (int j = fe; j <= le; ) {
                    if (st[j] != 1) { j++; continue; }
                    int w0 = j;
                    while (j <= le && st[j] == 1) j++;
                    win.add(-V + (w0 - 1) * RUN_STEP);
                    win.add(-V + j * RUN_STEP);
                }
                double[] wa = new double[win.size()];
                for (int j = 0; j < wa.length; j++) wa[j] = win.get(j);
                Run run = new Run(oa, ob, ea, eb, ya, yb, wa);
                out.add(run);
                rs.carved++;
                rs.carvedLen += encl * RUN_STEP;
                // where its air meets the cavern: each mouth's open stretch out to FADE_OUT past it, and each window
                int fo = (int) Math.round(FADE_OUT / RUN_STEP);
                meets.add(meet(c, k, run, Math.max(0, i0 - 1 - fo), fe, V, ax, az, sp, R0, pier));
                meets.add(meet(c, k, run, le, Math.min(n - 1, i1 + 1 + fo), V, ax, az, sp, R0, pier));
                for (int j = 0; j < wa.length; j += 2)
                    meets.add(meet(c, k, run, (int) Math.round((wa[j] + V) / RUN_STEP), (int) Math.round((wa[j + 1] + V) / RUN_STEP), V, ax, az, sp, R0, pier));
                // the furthest past the rim its section reaches as it closes
                int ext = (int) Math.ceil((FADE_OUT + FADE_LEN) / RUN_STEP);
                for (int j = Math.max(0, i0 - 1 - ext); j <= Math.min(n - 1, i1 + 1 + ext); j++) {
                    if (mm[j] != mm[j]) mm[j] = blocks(c, ax[j], az[j], rho(c, ax[j], az[j]));
                    reach = Math.max(reach, -mm[j]);
                }
            }
            rs.lines[k - kLo] = out.toArray(new Run[0]);
        }
        rs.cut = reach + REACH;
        rs.stands = rs.carved >= STAND_RUNS;
        rs.meets = meets.toArray(new Meet[0]);
        return rs;
    }

    /** The meeting stretch of line k's run over axis samples i0..i1 (the section's half-width read where it was not). */
    private Meet meet(Country c, int k, Run run, int i0, int i1, double V, double[] ax, double[] az, double[] sp, double R0, double pier) {
        int q = i1 - i0 + 1;
        double[] x = new double[q], z = new double[q], v = new double[q], r = new double[q];
        for (int i = 0; i < q; i++) {
            int j = i0 + i;
            x[i] = ax[j];
            z[i] = az[j];
            v[i] = -V + j * RUN_STEP;
            if (sp[j] != sp[j]) sp[j] = spread(c, k, v[i], R0, pier);
            r[i] = sp[j];
        }
        return new Meet(run, x, z, v, r);
    }

    /**
     * The mouth query, at a point of the cavern: the nearest place where a carved run's air meets the cavern (a mouth's
     * open stretch out to FADE_OUT past it, or a side window: {@link Meet}), out = {weight, floor, blocks}: the blocks from
     * there (0 inside its section; along the tube past a stretch's end and across it past the walls), the run's floor
     * there (absolute y, unrounded), and the mouth's weight, 1 there and 0 from MOUTH_W blocks out. False where none lies
     * within BLEND_MAX blocks. The Galleries blend their floor to the run's within BLEND_MAX and keep the mouth's head room
     * by its weight. No noise is read: the stretches are the runs' axis samples.
     */
    public boolean mouth(double x, double z, double[] out) {
        Country c = countryAt(x, z);
        if (!c.on) return false;
        Runs rs = runs(c);
        Meet near = nearestMeet(rs, x, z);
        if (near == null) return false;
        double d = near.dist(x, z, false);
        out[0] = 1 - smooth(d / MOUTH_W);
        out[1] = near.run.floor(near.dist(x, z, true));
        out[2] = d;
        return true;
    }

    /** The meeting stretch of rs nearest (x, z), where it lies within BLEND_MAX blocks ({@link Meet#dist}); else null. */
    private static Meet nearestMeet(Runs rs, double x, double z) {
        double best = BLEND_MAX;
        Meet near = null;
        for (Meet mt : rs.meets) {
            double dx = x - mt.cx, dz = z - mt.cz;
            if (dx * dx + dz * dz > mt.rad * mt.rad) continue;
            double d = mt.dist(x, z, false);
            if (d < best || (d == best && near == null)) { best = d; near = mt; }
        }
        return near;
    }

    // ================================================================================================ the tube at a point
    /**
     * What the tube field is at a point (x, z) of the plane for one line: its twin split, the section's size. {@link #at}
     * fills it for the nearest line; {@link #line} for any line of the point's country, from the frame {@link #frame} set.
     */
    static final class Pt {
        boolean on;          // a tube line is near
        double rho;
        int line;            // the line's index k in its country
        double s;            // signed distance from the line (blocks)
        double gx, gz;       // the line's unit normal in the world, toward +s (for tools: the twins lie o either way along it)
        double o;            // the twins' offset either side of the line (0: one tube)
        double d;            // distance from the nearer twin's axis (blocks)
        double Rw;           // the section's half-width before the bulge, the rib and the end fade (wandering along the line)
        double R, Rv;        // half-width and vault height of the section (end fade and the bays' bulge in; not the rib's cut)
        double Rv0;          // the unbulged section's vault height (end fade in): the floor's level
        double yc;           // axis height (absolute y)
        double phi;          // along-coordinate of the axis point nearest (v there)
        double ribP, ribD, ribK; // rib spacing, depth, strength 0..1
        double ribQ;         // blocks from the nearest rib's middle
        double rib;          // the rib's share here, 0..1 (rib())
        double fade;         // end fade 0..1: the line's run past its mouths ({@link Run#fade}), 0 where it has none
        Country c;
        // the frame (frame()): the point, its coordinates across and along the flow, the warped coordinate's gradient
        // there, and f = U / S - phase (the nearest line is floor(f))
        double x, z, u, v, dUdu, dUdv, g, f;
    }

    /** Fills p with the tube field at (x, z) (block-centre coordinates) for the nearest line. */
    void at(double x, double z, Pt p) {
        if (frame(x, z, p)) line(p, (int) Math.floor(p.f), Double.MAX_VALUE);
    }

    /**
     * Sets p's frame at (x, z): the country, rho and the warped coordinate U's value and gradient; true where tubes may
     * run (p.on stays false until a line is filled in): within the reach of the country's runs ({@link Runs#cut} blocks
     * past its rim).
     */
    boolean frame(double x, double z, Pt p) {
        p.on = false;
        Country c = countryAt(x, z);
        p.c = c;
        p.x = x;
        p.z = z;
        if (!c.on) { p.rho = -9; return false; }
        double rho = rho(c, x, z);
        p.rho = rho;
        Runs rs = runs(c);
        if (rs.carved == 0 || blocks(c, x, z, rho) < -rs.cut) return false;
        double u = c.u(x, z), v = c.v(x, z);
        double A = WARP_A * c.S;
        double nu = c.nu, nv = c.nv;
        double U = u + A * warpN.noise2(u / WARP_LU + nu, v / WARP_LV + nv);
        double e = 0.5;
        double dUdu = 1 + A * (warpN.noise2((u + e) / WARP_LU + nu, v / WARP_LV + nv) - warpN.noise2((u - e) / WARP_LU + nu, v / WARP_LV + nv)) / (2 * e);
        double dUdv = A * (warpN.noise2(u / WARP_LU + nu, (v + e) / WARP_LV + nv) - warpN.noise2(u / WARP_LU + nu, (v - e) / WARP_LV + nv)) / (2 * e);
        p.u = u;
        p.v = v;
        p.dUdu = dUdu;
        p.dUdv = dUdv;
        p.g = Math.sqrt(dUdu * dUdu + dUdv * dUdv);
        p.f = U / c.S - c.phase;
        return true;
    }

    /** Copies q's frame into p, so p can take another line at the same point. */
    static void frameOf(Pt q, Pt p) {
        p.on = false;
        p.c = q.c;
        p.rho = q.rho;
        p.x = q.x;
        p.z = q.z;
        p.u = q.u;
        p.v = q.v;
        p.dUdu = q.dUdu;
        p.dUdv = q.dUdv;
        p.g = q.g;
        p.f = q.f;
    }

    /**
     * Fills p (its frame set) with line k's tube at p's point: k need not be the nearest line, as a line's tube is the same
     * shape on either side of the middle between two lines (its distance {@code s} is one smooth function there). Its
     * floor and its end fade come from the line's run at phi ({@link Runs#at}): no run, no tube (fade 0). False, p left
     * half filled, where the tube's bulged bays stay more than {@code nearMax} blocks off (a cheap early out).
     */
    boolean line(Pt p, int k, double nearMax) {
        if (!geom(p, k, nearMax)) return false;
        Run run = runs(p.c).at(k, p.phi);
        double fade = run == null ? 0 : run.fade(p.phi);
        double Rv0 = p.Rw * VSTRETCH;
        double yc = (run == null ? W + FLOOR_LOW : run.floor(p.phi)) + FLOOR_CUT * Rv0;
        yc = Math.max(yc, W + FLOOR_LOW + FLOOR_CUT * Rv0);
        yc = Math.min(yc, W + VAULT_MAX - Rv0);
        p.on = true;
        p.yc = yc;
        p.fade = fade;
        // the bays bulge out between the ribs: at a rib's middle the section is R, in a bay R + BULGE (full ribs)
        double sh = rib(p);
        double Rb = p.Rw + BULGE * (p.ribK - sh);
        p.rib = sh;
        p.R = Rb * fade;
        p.Rv = Rb * VSTRETCH * fade;
        p.Rv0 = Rv0 * fade;
        return true;
    }

    /**
     * Line k's shape at p's point (its frame set), without its run: the distance from the line and from the nearer twin's
     * axis, the twins' offset, phi, the unfaded half-width Rw and the ribs' spacing, depth and strength. False where the
     * tube's bulged bays stay more than {@code nearMax} blocks off.
     */
    private boolean geom(Pt p, int k, double nearMax) {
        Country c = p.c;
        double v = p.v, g = p.g, dUdu = p.dUdu, dUdv = p.dUdv, nu = c.nu, nv = c.nv;
        double s = (p.f - k - 0.5) * c.S / g, as = Math.abs(s);
        // the line's own values
        long lk = Hashing.mix(seed, c.ci * 31L + c.cj, k, 991);
        double R0 = R_MIN + (R_MAX - R_MIN) * StrictMath.pow(Hashing.unit(lk), 0.9);
        double pier = PIER_MIN + (PIER_MAX - PIER_MIN) * Hashing.unit(Hashing.mix(lk, 1, 0, 0));
        // cheap outs, before the noise: past the widest this line's twins can stand, or past one tube where it is single
        double bay = R0 * (1 + R_WANDER) + BULGE;
        if (as - (2 * bay + pier) > nearMax) return false;
        double split = smooth((twinN.noise2(v / TWIN_L + nv, k * 7.77 + 3.1 + nu) - T_ON) / T_RAMP);
        if (split <= 0 && as - bay > nearMax) return false;
        double R = R0 * (1 + R_WANDER * radN.noise2(v / R_WANDER_L + nv, k * 5.31 + nu));
        double ribK = smooth((ribN.noise2(v / RIB_L + nv, k * 3.73 + nu) - RIB_OFF) / RIB_RAMP);
        // the twins stand apart by their bays' bulged half-width and the pier, so the pier keeps its width at the bays
        double o = (R + BULGE * ribK + pier) * split;
        double d = Math.abs(as - o);
        if (d - (R + BULGE * ribK) > nearMax) return false;
        // the nearest point of the twin's axis, and the along-coordinate there (ribs stand square across the tube)
        double nV = dUdv / g;                          // the v part of the line's normal in the (u, v) frame
        double off = s - Math.signum(s) * o;           // signed distance from the nearer twin's axis
        p.line = k;
        p.s = s;
        p.gx = (-dUdu * c.sin + dUdv * c.cos) / g;
        p.gz = (dUdu * c.cos + dUdv * c.sin) / g;
        p.o = o;
        p.d = d;
        p.phi = v - off * nV;
        p.Rw = R;
        p.ribP = RIB_P_MIN + (RIB_P_MAX - RIB_P_MIN) * Hashing.unit(Hashing.mix(lk, 2, 0, 0));
        p.ribD = RIB_D_MIN + (RIB_D_MAX - RIB_D_MIN) * Hashing.unit(Hashing.mix(lk, 3, 0, 0));
        p.ribK = ribK;
        return true;
    }

    /** Blocks within which a column is near a line's tube: the lining's reach and a margin (SHELL_BIOME + 3). */
    static final double NEAR = SHELL_BIOME + 3;
    /**
     * The furthest from a line's axis (blocks of its distance s) that its tube comes within NEAR: the outer twin's offset
     * and bulged bay at the largest radius (R_MAX wandering R_WANDER up, the widest pier), and a margin of 2.
     */
    static final double REACH = 2 * (R_MAX * (1 + R_WANDER) + BULGE) + PIER_MAX + NEAR + 2;

    /**
     * The smooth floor of the section at a point (absolute y): flat in the middle at the unbulged section's level (bays and
     * rings share it), a bowl toward the walls.
     */
    double floorY(Pt p, double x, double z) {
        double t = p.R > 1e-6 ? p.d / p.R : 9;
        double yf = p.yc - FLOOR_CUT * p.Rv0 + FLOOR_WOBBLE * floorN.noise2(x / 9.0, z / 9.0);
        if (t > BOWL_FLAT) {
            double q = (t - BOWL_FLAT) / (1 - BOWL_FLAT);
            yf += BOWL_H * p.Rv * q * q;
        }
        // the round section's own bottom where it rises over the floor near the wall
        if (t < 1) yf = Math.max(yf, p.yc - p.Rv * Math.sqrt(1 - t * t));
        return yf;
    }

    /** The smooth vault at a point (absolute y), or -inf outside the section. */
    static double vaultY(double yc, double d, double R, double Rv) {
        if (R <= 1e-6 || d >= R) return Double.NEGATIVE_INFINITY;
        double t = d / R;
        return yc + Rv * Math.sqrt(1 - t * t);
    }

    /** The rib's share (0..1) at along-coordinate phi of a line (sets p.ribQ; at() keeps it in p.rib). */
    double rib(Pt p) {
        p.ribQ = 1e9;
        if (p.ribK <= 0) return 0;
        double ph = p.phi + 1.6 * ribJ.noise2(p.phi / 23.0, p.line * 9.1 + p.c.nu);
        double t = ph / p.ribP;
        double q = Math.abs(t - Math.floor(t) - 0.5) * p.ribP;   // blocks from the nearest rib's middle
        p.ribQ = q;
        double hw = RIB_W * 0.5;
        double r = q < hw ? 1 : (q < hw + RIB_EDGE ? 1 - (q - hw) / RIB_EDGE : 0);
        return r * p.ribK;
    }

    /**
     * For tools: the point (x, z) on the axis of line k of country c at along-coordinate v (Newton on U across), or
     * null when it does not converge.
     */
    double[] axisPoint(Country c, int k, double v) {
        double target = (k + 0.5 + c.phase) * c.S, A = WARP_A * c.S;
        double u = target;
        for (int it = 0; it < 30; it++) {
            double U = u + A * warpN.noise2(u / WARP_LU + c.nu, v / WARP_LV + c.nv);
            double e = 0.5;
            double dU = 1 + A * (warpN.noise2((u + e) / WARP_LU + c.nu, v / WARP_LV + c.nv) - warpN.noise2((u - e) / WARP_LU + c.nu, v / WARP_LV + c.nv)) / (2 * e);
            double du = (U - target) / dU;
            u -= du;
            if (Math.abs(du) < 1e-4) break;
        }
        double x = c.ox + v * c.cos - u * c.sin, z = c.oz + v * c.sin + u * c.cos;
        return new double[]{x, z, u};
    }

    // ================================================================================================ column cells
    double siteX(int q, int r) { return HEX * (q + r * 0.5) + (h(q, r, 1) - 0.5) * 2 * HEX_JIT * HEX; }
    double siteZ(int q, int r) { return HEX * Math.sqrt(3) / 2 * r + (h(q, r, 2) - 0.5) * 2 * HEX_JIT * HEX; }
    static final int[] NQ = {0, 1, -1, 0, 0, 1, -1}, NR = {0, 0, 0, 1, -1, -1, 1};

    /** The column cell holding block column (x, z): {q, r}. */
    void cellOf(int bx, int bz, int[] out) {
        double x = bx + 0.5, z = bz + 0.5, rz = HEX * Math.sqrt(3) / 2;
        double rf = z / rz, qf = x / HEX - rf * 0.5, sf = -qf - rf;
        long rq = Math.round(qf), rr = Math.round(rf), rs = Math.round(sf);
        double dq = Math.abs(rq - qf), dr = Math.abs(rr - rf), ds = Math.abs(rs - sf);
        if (dq > dr && dq > ds) rq = -rr - rs;
        else if (dr > ds) rr = -rq - rs;
        int bq = 0, br = 0;
        double best = 1e18;
        for (int k = 0; k < 7; k++) {
            int q = (int) rq + NQ[k], r = (int) rr + NR[k];
            double dx = siteX(q, r) - x, dz = siteZ(q, r) - z, d = dx * dx + dz * dz;
            if (d < best) { best = d; bq = q; br = r; }
        }
        out[0] = bq;
        out[1] = br;
    }

    // ================================================================================================ columns
    /**
     * A column's tube: the open rows F..C-1 (none when C <= F), the pool rows, and the smooth sections of the lines whose
     * tubes come near it, for the lining. A line's tube is whole wherever it reaches, not cut where the next line becomes
     * the nearer (a twin's outer tube often reaches past that middle): the column takes the nearest line and a neighbour
     * line on either side whose axis lies within {@link #REACH}, keeps those within {@link #NEAR} and opens the union of
     * their rows, so two tubes that meet merge round.
     */
    static final class Col {
        int x, z;
        boolean valid;
        boolean near;        // the tube field reaches this column (within the lining's reach)
        int F, C;            // open rows [F, C)
        int waterLo, waterHi; // water rows [waterLo, waterHi) (empty when equal)
        int spanLo, spanHi;  // rows the per-block pass must look at (widened by besides() to the rows its neighbours open)
        boolean runs;        // the smooth sections' own rows (8 either way) are not empty: a tube runs here
        double rho, rib;
        int cq, cr;          // the column cell
        /**
         * The near lines (pb, nb[0], nb[1]) in sec[0..n), and their smooth sections at the block centre (bulged, without
         * the rib's cut) for shellDepth: the first in yc..yfs, the others in xs[i - 1] = {yc, d, R, Rv, yfs}.
         */
        int n;
        final Pt[] sec = new Pt[3];
        double yc, d, R, Rv, yfs;
        final double[][] xs = new double[2][5];
        final Pt pb = new Pt(), pc = new Pt();
        final Pt[] nb = {new Pt(), new Pt()};
        final int[] cell = new int[2], rows = new int[2];
        boolean besideDone;  // beside holds the open rows {F, C} of the 4 columns beside (+x, -x, +z, -z), the span widened
        final int[] beside = new int[8];
    }

    private final ThreadLocal<Col[]> cache;

    Col column(int x, int z) {
        Col[] a = cache.get();
        Col c = a[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.x = x;
        c.z = z;
        c.valid = true;
        compute(c);
        return c;
    }

    private void compute(Col c) {
        computeRows(c);
        if (c.C > c.F) pool(c, c.x, c.z);
    }

    /**
     * Column c's near lines, smooth sections, span and open rows without pools: the union of the rows each near line opens
     * (two that overlap give their hull; where two lie apart, which is rare, the line the column lies deepest in).
     */
    private void computeRows(Col c) {
        int x = c.x, z = c.z;
        c.near = false;
        c.runs = false;
        c.besideDone = false;
        c.rib = 0;
        c.F = 0;
        c.C = 0;
        c.waterLo = c.waterHi = 0;
        c.spanLo = 1;
        c.spanHi = 0;
        c.n = 0;
        Pt pb = c.pb;
        boolean on = frame(x + 0.5, z + 0.5, pb);
        c.rho = pb.rho;
        if (!on) return;
        // the nearest line, then the neighbour lines, the one toward which the column lies first: their tubes may reach
        // past the middle (a line far off is left after a cheap look at its hashed size)
        int k0 = (int) Math.floor(pb.f), up = pb.f - k0 >= 0.5 ? 1 : -1;
        for (int side = 0; side < 2; side++) frameOf(pb, c.nb[side]);
        int n = 0;
        if (line(pb, k0, NEAR) && pb.d - pb.R <= NEAR) c.sec[n++] = pb;
        for (int side = 0; side < 2; side++) {
            int k = k0 + (side == 0 ? up : -up);
            if (Math.abs((pb.f - k - 0.5) * pb.c.S / pb.g) > REACH) continue;
            Pt q = c.nb[side];
            if (line(q, k, NEAR) && q.d - q.R <= NEAR) c.sec[n++] = q;
        }
        c.n = n;
        if (n == 0) return;   // too far out for the lining
        c.near = true;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, F = 0, C = 0;
        boolean open = false;
        double deepAll = 1e9, deepOpen = 1e9;
        for (int i = 0; i < n; i++) {
            Pt p = c.sec[i];
            double yfs = floorY(p, x + 0.5, z + 0.5);
            if (i == 0) {
                c.yc = p.yc;
                c.d = p.d;
                c.R = p.R;
                c.Rv = p.Rv;
                c.yfs = yfs;
            } else {
                double[] e = c.xs[i - 1];
                e[0] = p.yc;
                e[1] = p.d;
                e[2] = p.R;
                e[3] = p.Rv;
                e[4] = yfs;
            }
            if (p.R <= 1e-6) continue;   // a closed section past a tube's end: only its neighbours' faces (besides())
            lo = Math.min(lo, (int) Math.floor(yfs) - 8);
            hi = Math.max(hi, (int) Math.ceil(p.yc + p.Rv) + 8);
            double deep = p.d - p.R;
            if (!open && deep < deepAll) { deepAll = deep; c.rib = p.rib; }
            if (!rowsOf(x, z, p, p.rib, c.pc, c.cell, c.rows)) continue;
            int f = c.rows[0], cc = c.rows[1];
            if (open && f <= C && F <= cc) {
                F = Math.min(F, f);
                C = Math.max(C, cc);
            } else if (open && deep >= deepOpen) continue;
            else {
                F = f;
                C = cc;
            }
            if (!open || deep < deepOpen) { deepOpen = deep; c.rib = p.rib; }
            open = true;
        }
        c.spanLo = lo;
        c.spanHi = hi;
        c.runs = lo <= hi;
        if (!open) return;
        c.cq = c.cell[0];
        c.cr = c.cell[1];
        c.F = F;
        c.C = C;
    }

    /**
     * The open rows {F, C} of block column (x, z) without pools, from its column cell's floor and hanging end and the rib
     * cut at the block, for the line pb holds: false where that line's tube does not open the column. pb is the line's
     * field at the block, rib its rib share; the column cell's floor is taken for the same line.
     */
    boolean rowsOf(int x, int z, Pt pb, double rib, Pt pc, int[] cell, int[] out) {
        // the vault, per block: the round section; a rib is a ring, its intrados the section shrunk by the rib's depth at
        // the crown and RIB_WALL of it at the walls, standing down to the floor as the arch's jambs
        double yv = vaultY(pb.yc, pb.d, pb.R, pb.Rv);
        if (yv == Double.NEGATIVE_INFINITY) return false;
        if (rib > 0) {
            double Rr = Math.max(0, pb.R - RIB_WALL * pb.ribD * rib), Rvr = Math.max(0, pb.Rv - pb.ribD * rib);
            double yr = vaultY(pb.yc, pb.d, Rr, Rvr);
            if (yr == Double.NEGATIVE_INFINITY) return false;   // the jamb
            yv = Math.min(yv, yr);
        }
        // the floor, per column cell (the cell's site on the same line): steps with column-shaped risers
        cellOf(x, z, cell);
        Site st = site(cell[0], cell[1], pb.line, pc);
        if (!st.on) return false;
        int F = st.F;
        // column ends hanging from the vault in the bays, a whole column cell at a time (outside the rings' soft edges)
        if (rib <= 0) yv -= st.hang;
        int C = (int) Math.round(yv);
        if (C - F < 2) return false;
        out[0] = F;
        out[1] = C;
        return true;
    }

    /**
     * A column cell's draw at its site for one line: whether a tube is there, the cell's floor row, how far its column
     * hangs.
     */
    static final class Site {
        int q, r, k, F, hang;
        boolean valid, on;
    }

    private final ThreadLocal<Site[]> sites = ThreadLocal.withInitial(() -> {
        Site[] a = new Site[512];
        for (int i = 0; i < a.length; i++) a[i] = new Site();
        return a;
    });

    /**
     * Column cell (q, r)'s draw for line k, the line of the column asking (a per-thread cache, neighbouring lines in
     * slots of their own; pc is scratch for the field at the site). The site may lie nearer another line: the cell still
     * takes line k's floor, so a tube is the same shape on both sides of the middle between two lines.
     */
    Site site(int q, int r, int k, Pt pc) {
        Site st = sites.get()[((q & 15) << 5) | ((r & 15) << 1) | (k & 1)];
        if (st.valid && st.q == q && st.r == r && st.k == k) return st;
        st.q = q;
        st.r = r;
        st.k = k;
        st.valid = true;
        double sx = siteX(q, r), sz = siteZ(q, r);
        // a site where the line has no run, or past its closing end, opens nothing
        st.on = frame(sx, sz, pc) && line(pc, k, Double.MAX_VALUE) && pc.fade > 0;
        st.F = st.on ? (int) Math.round(floorY(pc, sx, sz)) : 0;
        st.hang = st.on ? hang(q, r, sx, sz, pc) : 0;
        return st;
    }

    /**
     * How many blocks column cell (q, r) hangs from the vault (0 to {@link #HANG_MAX}), decided at its site (pc the field
     * there): only in the bays, clear of the rings by FRINGE_CLEAR, and within HANG_REACH of the axis; there a cluster
     * noise at the site, longest in its middle, a hash raggedness of a block.
     */
    int hang(int q, int r, double sx, double sz, Pt pc) {
        if (pc.ribK > 0 && pc.ribQ <= RIB_W * 0.5 + RIB_EDGE + FRINGE_CLEAR) return 0;
        if (pc.d >= HANG_REACH * pc.R) return 0;
        double t = clamp01((hangN.noise2(sx / HANG_L + pc.c.nu, sz / HANG_L + pc.c.nv) - HANG_T) / HANG_W);
        if (t <= 0) return 0;
        return (int) Math.min(HANG_MAX, Math.floor(HANG_MAX * t + h(q, r, 3)));
    }

    // ================================================================================================ pools
    private final ThreadLocal<java.util.HashMap<Long, int[]>> dipCache = ThreadLocal.withInitial(java.util.HashMap::new);

    /** A dip candidate's draw: {hash, cx, cz, radius}, or null when the grid cell holds none. */
    private double[] dipDraw(int gi, int gj) {
        long hk = Hashing.mix(seed ^ 0x9E3779B97F4A7C15L, gi, gj, 0xD1B);
        double u = Hashing.unit(hk);
        if (u >= DIP_P) return null;
        double cx = (gi + 0.2 + 0.6 * Hashing.unit(Hashing.mix(hk, 1, 0, 0))) * DIP_SP;
        double cz = (gj + 0.2 + 0.6 * Hashing.unit(Hashing.mix(hk, 2, 0, 0))) * DIP_SP;
        double rd = DIP_R_MIN + (DIP_R_MAX - DIP_R_MIN) * Hashing.unit(Hashing.mix(hk, 3, 0, 0));
        return new double[]{Hashing.unit(Hashing.mix(hk, 4, 0, 0)), cx, cz, rd};
    }

    /**
     * The pool of grid cell (gi, gj): {valid 0/1, water top F0 (the first row over the water), cx*16, cz*16, radius*16};
     * cached per thread. A dip is valid well inside the massif on the flat middle of a tube's floor, where no
     * overlapping candidate of a neighbouring grid cell draws higher (so pools never touch), with at least three open
     * columns in its disc, where the floor is level (the floors in its disc and the ring of a block round it at most a row
     * apart, so the walking level over the water is never 2 under a floor beside it). Its water's top row is one under the
     * lowest floor in the disc and the ring, so every block beside the water at its rows is rock.
     */
    int[] dip(int gi, int gj) {
        java.util.HashMap<Long, int[]> m = dipCache.get();
        long key = ((long) gi << 32) ^ (gj & 0xffffffffL);
        int[] v = m.get(key);
        if (v != null) return v;
        if (m.size() > 4096) m.clear();
        v = new int[]{0, 0, 0, 0, 0};
        m.put(key, v);
        double[] dr = dipDraw(gi, gj);
        if (dr == null) return v;
        double cx = dr[1], cz = dr[2], rd = dr[3];
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            if (di == 0 && dj == 0) continue;
            double[] o = dipDraw(gi + di, gj + dj);
            if (o != null && o[0] > dr[0] && StrictMath.hypot(o[1] - cx, o[2] - cz) < rd + o[3] + 2) return v;
        }
        Pt p = new Pt();
        at(cx, cz, p);
        if (!p.on || p.rho < RHO_POOL || p.fade < 1 || p.d > DIP_MID * p.R) return v;
        // the disc and its ring well inside the massif's rock, clear of the rim and of every collapse gap
        if (massifAt(cx, cz) < rd + POOL_KEEP) return v;
        Col t = new Col();
        int F0 = Integer.MAX_VALUE, Fmax = Integer.MIN_VALUE, open = 0;
        int ext = (int) Math.ceil(rd + 1.5);
        int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        for (int dx = -ext; dx <= ext; dx++) for (int dz = -ext; dz <= ext; dz++) {
            double ddx = bx + dx + 0.5 - cx, ddz = bz + dz + 0.5 - cz, dd = Math.sqrt(ddx * ddx + ddz * ddz);
            if (dd > rd + 1.5) continue;
            int[] fc = openFloor(bx + dx, bz + dz, t);
            if (fc == null) continue;                  // rock (the wall or a rib's jamb): it seals the pool
            F0 = Math.min(F0, fc[0]);
            Fmax = Math.max(Fmax, fc[0]);
            if (dd <= rd) open++;
        }
        // still water lies where the floor is level: every floor round it at most a row over the lowest, so one steps out
        if (open < 3 || F0 - 3 < W + Terrain.FLOOD_RISE + 1 || Fmax - F0 > 1) return v;
        v[0] = 1;
        v[1] = F0;
        v[2] = (int) Math.round(cx * 16);
        v[3] = (int) Math.round(cz * 16);
        v[4] = (int) Math.round(rd * 16);
        return v;
    }

    /**
     * The floor and vault {F, C} of block column (bx, bz) without pools (every near line's), or null where no tube opens
     * it; t is scratch, outside the column cache, which may be filling the column that asks.
     */
    private int[] openFloor(int bx, int bz, Col t) {
        t.x = bx;
        t.z = bz;
        computeRows(t);
        return t.C > t.F ? new int[]{t.F, t.C} : null;
    }

    private void pool(Col c, int x, int z) {
        int gi0 = (int) Math.floor((x + 0.5) / DIP_SP), gj0 = (int) Math.floor((z + 0.5) / DIP_SP);
        for (int gi = gi0 - 1; gi <= gi0 + 1; gi++) for (int gj = gj0 - 1; gj <= gj0 + 1; gj++) {
            int[] v = dip(gi, gj);
            if (v[0] == 0) continue;
            double cx = v[2] / 16.0, cz = v[3] / 16.0, rd = v[4] / 16.0;
            double dd = StrictMath.hypot(x + 0.5 - cx, z + 0.5 - cz);
            if (dd > rd) continue;
            int F0 = v[1];
            int depth = rd > DIP_TWO && dd < rd * DIP_DEEP ? 2 : 1;
            // water in rows F0-depth..F0-1; the column's floor steps over F0 inside the disc are taken away
            c.waterLo = F0 - depth;
            c.waterHi = F0;
            c.F = c.waterLo;
            return;
        }
    }

    // ================================================================================================ queries
    /**
     * The tube's density at a block (blocks, negative inside a tube's air, ribs and hanging columns included): the
     * Galleries' field takes {@code min(cave, tube)}. Rock reads the distance to the section's smooth envelope (at least
     * 0.5; a ring's rock and a hanging end read 0.5), so the deep rock and the surface pass split as they do at any cave
     * wall; rock face-adjacent to the tube's air or water reads 0.5 however far the smooth section is (a column cell's
     * floor stepped under the bowl, a pool's bed by the wall), so every face the tube opens is surface rock, which the
     * palette lines (the game's deep rock starts at a density of 0.75, 6 blocks). SOLID where no tube is near. Always
     * finite.
     */
    public double tube(int x, int y, int z) {
        Col c = column(x, z);
        if (!c.near) return SOLID;
        int F = c.F, C = c.C;
        if (y >= F && y < C) {
            if (y >= c.waterLo && y < c.waterHi) return -0.5;
            return -(Math.min(y - F, C - 1 - y) + 0.5);
        }
        double e = envelope(c, y);
        if (e > FACE_CHECK && ((C > F && (y == F - 1 || y == C)) || openBeside(c, y))) return 0.5;
        return Math.max(0.5, e);
    }

    /** Over this distance from the smooth section tube() asks whether the rock still faces the tube's open rows. */
    static final double FACE_CHECK = 4;

    /** True when one of the 4 columns beside column c has the tube's air or water at row y. */
    private boolean openBeside(Col c, int y) {
        besides(c);
        int[] nb = c.beside;
        for (int k = 0; k < 8; k += 2) if (y >= nb[k] && y < nb[k + 1]) return true;
        return false;
    }

    /**
     * Keeps in near column c the open rows of the 4 columns beside it, and widens its span to every row that faces open
     * rows, its own or theirs: a pool's water or a stepped floor beside the bowl's rising wall can lie under the span
     * the column's own smooth section gives, and the rock there must still read as the tube's surface.
     */
    private void besides(Col c) {
        if (c.besideDone) return;
        int[] nb = c.beside;
        int lo = c.spanLo, hi = c.spanHi;
        if (c.C > c.F) {
            lo = Math.min(lo, c.F - 1);
            hi = Math.max(hi, c.C);
        }
        // a neighbour never shares c's slot of the cache, so c stays valid while they are computed
        for (int k = 0; k < 4; k++) {
            Col n = column(c.x + (k == 0 ? 1 : k == 1 ? -1 : 0), c.z + (k == 2 ? 1 : k == 3 ? -1 : 0));
            int f = n.near ? n.F : 0, cc = n.near ? n.C : 0;
            nb[2 * k] = f;
            nb[2 * k + 1] = cc;
            if (cc > f) {
                lo = Math.min(lo, f);
                hi = Math.max(hi, cc - 1);
            }
        }
        c.spanLo = lo;
        c.spanHi = hi;
        c.besideDone = true;
    }

    /**
     * Distance (about blocks) from the smooth sections' envelope, negative inside it: the nearest of the column's near
     * lines' sections (where two tubes meet, the union of the two). SOLID where no line is near.
     */
    static double envelope(Col c, int y) {
        if (c.n == 0) return SOLID;
        double e = envelope(c.yc, c.d, c.R, c.Rv, c.yfs, y);
        for (int i = 1; i < c.n; i++) {
            double[] x = c.xs[i - 1];
            e = Math.min(e, envelope(x[0], x[1], x[2], x[3], x[4], y));
        }
        return e;
    }

    /**
     * Distance (about blocks) from one line's smooth section (axis height yc, distance d from the axis, half-width R,
     * vault height Rv), negative inside it: the section without the ribs' cut (a rib's rock reads inside it and is
     * lined), over the smooth floor yfs. A fading end closes both radii to nothing: there it is SOLID, never 0 x infinity
     * (NaN, which the game would read as open).
     */
    static double envelope(double yc, double d, double R, double Rv, double yfs, int y) {
        if (R <= 1e-6 || Rv <= 1e-6) return SOLID;
        double dy = (y + 0.5 - yc) / Rv, dx = d / R;
        double sec = (Math.sqrt(dx * dx + dy * dy) - 1) * Math.min(R, Rv);
        return Math.max(sec, yfs - (y + 0.5));
    }

    /**
     * Depth into the rock from the tube's smooth section (without the ribs' cut): about 0 at the tube's surface, rising
     * into the rock; under 0 for rock standing inside the smooth section (the rings, hanging column ends, floor steps);
     * 1e9 where no tube is near. The lining's palette reads it (obsidian skin, basalt and scoria behind). Always finite.
     *
     * <p>The open rows are quantised (a column cell's floor is its site's), so near the walls, where the bowl climbs
     * steeply, the tube's air reaches outside the smooth section: a cell's floor lies rows under the bowl beside it. So the
     * depth is never more than the distance to the nearest open row of the block's column and the 8 round it
     * ({@link #openReach}): every face of the tube and of its pools is lined, and in the Obsidian Tubes.
     */
    public double shellDepth(int x, int y, int z) {
        Col c = column(x, z);
        if (!c.near) return 1e9;
        double e = envelope(c, y);
        return e <= -0.5 ? e : Math.min(e, openReach(x, y, z));
    }

    /**
     * Distance (about blocks) from the block to the nearest open row (air or a pool's water) of its own column or of the
     * 8 columns round it, between block centres less half a block: -0.5 in the open rows, 0.5 for rock beside them, 9
     * when none is within reach.
     */
    private double openReach(int x, int y, int z) {
        double best = 9;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Col n = column(x + dx, z + dz);
            if (!n.near || n.C <= n.F) continue;
            int v = y < n.F ? n.F - y : (y >= n.C ? y - n.C + 1 : 0);
            double dd = Math.sqrt(dx * dx + dz * dz + v * v) - 0.5;
            if (dd < best) best = dd;
        }
        return best;
    }

    /**
     * The tube's walking level in column (x, z): the first open row over its floor or over its pool's water;
     * Integer.MAX_VALUE where no tube opens the column. The Galleries keep a roof over the tube where their own open space
     * floats too high over it (GalleriesField.roofed).
     */
    public int walk(int x, int z) {
        Col c = column(x, z);
        if (!c.near || c.C <= c.F) return Integer.MAX_VALUE;
        return c.waterHi > c.waterLo ? c.waterHi : c.F;
    }

    /** True for a block of still water in a pool's dip. */
    public boolean pool(int x, int y, int z) {
        Col c = column(x, z);
        return c.near && y >= c.waterLo && y < c.waterHi;
    }

    /**
     * The rows the per-block pass must look at in column (x, z): [spanLo, spanHi], the smooth section's rows with 8 more
     * either way and every row facing the column's or a side neighbour's open rows; empty when spanLo > spanHi.
     */
    public int spanLo(int x, int z) {
        Col c = column(x, z);
        if (!c.near) return 1;
        besides(c);
        return c.spanLo;
    }

    public int spanHi(int x, int z) {
        Col c = column(x, z);
        if (!c.near) return 0;
        besides(c);
        return c.spanHi;
    }

    /**
     * True where a tube runs at column (x, z): near it, with rows of its smooth section to look at (the per-block span
     * before it is widened to the neighbours' open rows). The Galleries keep a mouth's head room where it is.
     */
    public boolean runs(int x, int z) {
        Col c = column(x, z);
        return c.near && c.runs;
    }

    /**
     * True in the tube's lining or air ({@link #shellDepth} under {@link #SHELL_BIOME}): the Obsidian Tubes' biome, where
     * the cave itself is rock.
     */
    public boolean inLining(int x, int y, int z) {
        Col c = column(x, z);
        if (!c.near) return false;
        return envelope(c, y) < SHELL_BIOME || openReach(x, y, z) < SHELL_BIOME;
    }

    /** The rib's share (0..1) over column (x, z): the palette lines the ribs with obsidian, the bays' vaults paler. */
    public double ribShare(int x, int z) {
        Col c = column(x, z);
        return c.near ? c.rib : 0;
    }

    /**
     * The column cell under column (x, z) where a tube opens it, as a hash (the palette alternates column tops by it:
     * obsidian and pale stone underfoot), or 0 where no tube opens the column.
     */
    public long floorCell(int x, int z) {
        Col c = column(x, z);
        return c.near && c.C > c.F ? Hashing.mix(seed ^ 0x3C6EF372FE94F82BL, c.cq, c.cr, 4242) | 1 : 0;
    }

    /**
     * A standing spot in a tube of cell (ci, cj) for /remix tp and the shot planner: {x, y, z} with y the first open row
     * over the floor, on the axis of the line nearest the country's middle, well inside the massif; null when the cell
     * has no country or no line opens there.
     */
    public int[] spotIn(int ci, int cj) {
        Country c = country(ci, cj);
        if (!c.on) return null;
        int[] ks = {0, -1, 1, -2, 2, -3, 3};
        Pt p = new Pt();
        for (double v : new double[]{0, -30, 30}) for (int k : ks) {
            double[] q = axisPoint(c, k, v);
            at(q[0], q[1], p);
            if (!p.on || p.line != k || p.rho < RHO_POOL || massifAt(q[0], q[1]) < GAP_HW_MAX) continue;
            double x = q[0] + (p.o > 0.5 ? p.o * -c.sin : 0), z = q[1] + (p.o > 0.5 ? p.o * c.cos : 0);
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            Col col = column(bx, bz);
            if (!col.near || col.C - col.F < 3 || col.waterHi > col.waterLo) continue;
            return new int[]{bx, col.F, bz};
        }
        return null;
    }

    /** The open rows of the column: {F, C} (none when C <= F). For tools. */
    int[] rows(int x, int z) { Col c = column(x, z); return c.near ? new int[]{c.F, c.C, c.waterLo, c.waterHi} : null; }

    /** How many lines' tubes come near column (x, z) (0 to 3; 2 or more where a tube reaches past the middle). For tools. */
    int nearLines(int x, int z) { return column(x, z).n; }

    /**
     * For tools: true where exactly one line's tube comes near column (x, z), with out filled for that line at the block
     * centre (not always the nearest line: a neighbour's twin may be the only tube near).
     */
    boolean soleLine(int x, int z, Pt out) {
        Col c = column(x, z);
        if (c.n != 1) return false;
        int k = c.sec[0].line;
        return frame(x + 0.5, z + 0.5, out) && line(out, k, Double.MAX_VALUE);
    }
}
