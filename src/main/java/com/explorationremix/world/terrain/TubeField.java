package com.explorationremix.world.terrain;

/**
 * The Basalt Galleries' Obsidian Tubes as noise (0.5.1; Taran 2026-11-02: "Don't design the shape. It should be
 * procedurally generated."): round lava tubes 7 to 15 blocks wide, running nearly level through the rock with floors at
 * W+46..W+74 and vaults up to W+94 (W the lava table), with arch ribs one behind the other, twin tubes round a pier,
 * floors stepped by column cells, short column ends hanging in the vaults and still water in sealed dips of the floor.
 * Nothing is planned or placed: every value is a pure function of the seed and the position (caches only hold those
 * values), so any number of worldgen threads may call in, and nothing seams.
 *
 * <p><b>Where tubes run.</b> Cellular noise: the world is cut into {@link #CELL}-block cells and a cell holds a tube
 * country with chance {@link #SITE_P}: a noise-warped ellipse, its long axis along a hashed flow direction. Inside its rim
 * ({@link #massif} over 0) the Galleries keep rock from floor to vault; round it lies the apron ({@link #apronAt}), where
 * the Galleries open their space and pull their floor to the tubes' level, so the tubes, which run on past the rim, open
 * into the cavern at its floor. A country, its tubes and its apron never reach the cell's border, so only a column's own
 * cell is ever read.
 *
 * <p><b>The tubes.</b> In a country's frame (u across the flow, v along it) the tube axes are the level lines of a warped
 * coordinate {@code U = u + A N(u, v)} at {@code U = (k + 1/2 + phase) S}: parallel, meandering, crossing the country
 * from rim to rim, never ending inside it and never branching, but touching where two meander together. Where a twin
 * noise passes a threshold a line splits into two tubes {@code o} either side of it, a pier of rock between them, and
 * they join again further on. The ribs' along-coordinate is v at the nearest point of the (twin) axis, so the ribs stand
 * square across the tube.
 *
 * <p><b>The section.</b> A round vault (an ellipse {@code R} across, {@link #VSTRETCH} R high) cut by a floor
 * {@link #FLOOR_CUT} of the vault's height under the axis, rising to the walls in a bowl. The floor is taken at the site
 * of the column's column cell (a jittered hex lattice {@link #HEX} across), so it steps in column tops. A rib is a ring:
 * the section shrunk by the rib's depth at the crown and {@link #RIB_WALL} of it at the walls, down to the floor. In the
 * bays between the ribs short column ends hang from the vault (per block, a few per cell).
 *
 * <p><b>Use.</b> The Galleries' field takes {@code min(cave, tube(x, y, z))} block by block in its per-block pass over
 * {@link #spanLo}..{@link #spanHi} of the column (the tube is not in Minecraft's 4 x 8 x 4 corners: its steps, ribs and
 * hanging ends are thinner than the corner grid). {@link #shellDepth}, {@link #ribShare} and {@link #floorCell} dress the
 * lining, {@link #inLining} is the biome, {@link #pool} the still water.
 */
public final class TubeField {
    static final double SOLID = 64;

    // ------------------------------------------------------------------------------------------- tube countries
    /** Cell of the cellular noise that places tube countries (blocks); at most one country per cell. Public for /remix tp. */
    public static final int CELL = 640;
    /** Share of cells with a tube country. */
    static final double SITE_P = 0.8;
    /** A country's centre lies within this share of CELL of its cell's centre, either way. */
    static final double JIT = 0.10;
    /** The country's half-length along the flow (blocks), and its half-width as a share of that. */
    static final double A_MIN = 120, A_MAX = 170, ASPECT_MIN = 0.48, ASPECT_MAX = 0.7;
    /** The rim's noise warp: amplitude as a share of the half-width, and scale (blocks). */
    static final double RIM_WARP = 0.16, RIM_SCALE = 60;
    /** Beyond this unwarped radius rho skips the rim warp (the apron ends well inside it). */
    static final double R_FAR = 2.6;
    /** Tubes are carved where rho > RHO_TUBE (past the massif's rim, rho 0, into the cavern). */
    static final double RHO_TUBE = -0.13;
    /** Over the last END_FADE of rho a tube's radius closes to nothing (its end in the cavern, or a blind end). */
    static final double END_FADE = 0.05;
    /** Pools only this far inside the massif (rho): well away from the mouths and the cavern's open air. */
    static final double RHO_POOL = 0.10;

    // ------------------------------------------------------------------------------------------- lines
    /** Spacing of the tube lines across the flow (blocks). */
    static final double S_MIN = 36, S_MAX = 48;
    /** Meander of the lines: warp amplitude (share of S) and its scales across and along the flow (blocks). */
    static final double WARP_A = 0.34, WARP_LU = 70, WARP_LV = 64;

    // ------------------------------------------------------------------------------------------- section
    /** Half-width of a tube at its axis (blocks): 8.4 to 13.6 wide. */
    static final double R_MIN = 5.0, R_MAX = 7.2;
    /** How much a tube's radius wanders along it (share), and over what length (blocks). */
    static final double R_WANDER = 0.12, R_WANDER_L = 45;
    /** The vault's height over the axis, as a share of the half-width. */
    static final double VSTRETCH = 1.25;
    /** The floor lies this share of the vault's height under the axis. */
    static final double FLOOR_CUT = 0.42;
    /** The floor is flat out to BOWL_FLAT of the half-width, then rises up to BOWL_H of the vault's height at the wall. */
    static final double BOWL_FLAT = 0.42, BOWL_H = 0.55;
    /** Slow wobble of the floor (blocks). */
    static final double FLOOR_WOBBLE = 1.3;

    // ------------------------------------------------------------------------------------------- twins
    /** Twin noise: over T_ON a line splits into two tubes o either side of it, fully apart T_RAMP further on. */
    static final double T_ON = 0.10, T_RAMP = 0.22, TWIN_L = 58;
    /** Half the pier's width at full split (blocks). */
    static final double PIER_MIN = 1.6, PIER_MAX = 3.2;

    // ------------------------------------------------------------------------------------------- height
    /** The axis height over the lava table W: a country's mean, the noise round it and its scale (blocks). */
    static final double H_MIN = 56, H_MAX = 70, H_AMP = 9, H_SCALE = 140;
    /** Fall of the axis along the flow (blocks per block), either way. */
    static final double SLOPE_MAX = 0.025;
    /** The lowest floor and the highest vault (over W). */
    static final double FLOOR_MIN = 46, VAULT_MAX = 94;

    // ------------------------------------------------------------------------------------------- ribs
    /** Rib spacing along the tube (blocks), rib thickness, its soft edge, and how far a rib stands in from the vault. */
    static final double RIB_P_MIN = 6, RIB_P_MAX = 9, RIB_W = 2.0, RIB_EDGE = 0.5, RIB_D_MIN = 3.0, RIB_D_MAX = 4.5;
    /** A rib is a ring: it stands in from the walls this share of its depth, down to the floor (its jambs). */
    static final double RIB_WALL = 0.3;
    /** No hanging column ends within this many blocks of a rib's edge (the ribs read as arches one behind the other). */
    static final double FRINGE_CLEAR = 1.0;
    /** Rib strength noise along the tube: under RIB_OFF no ribs, full ribs RIB_RAMP over it; its scale. */
    static final double RIB_OFF = -0.45, RIB_RAMP = 0.3, RIB_L = 50;

    // ------------------------------------------------------------------------------------------- column cells
    /** Spacing of the column cells (blocks), their jitter (share), and the share of vault cells that hang lower. */
    static final double HEX = 2.3, HEX_JIT = 0.3, HANG_P = 0.12;
    /** Share of the vault's block columns that hang one more (the fringe of short column ends). */
    static final double FRINGE_P = 0.3, FRINGE_P2 = 0.1, FRINGE_P3 = 0.03;
    /** Hanging columns only where the vault is this share of the half-width from the axis or nearer. */
    static final double HANG_REACH = 0.8;

    // ------------------------------------------------------------------------------------------- pools
    /** Pool candidates: a jittered grid of this spacing (blocks), the share that are dips, their radius. */
    static final double DIP_SP = 10, DIP_P = 0.2, DIP_R_MIN = 1.8, DIP_R_MAX = 3.4;
    /** A dip wider than this is two deep in its middle (DIP_DEEP of the radius). */
    static final double DIP_TWO = 2.6, DIP_DEEP = 0.55;
    /** A pool's dip lies on the flat middle of the floor (share of the half-width from the axis). */
    static final double DIP_MID = 0.45;

    /** The apron: full out to APRON_OUT - APRON_FADE blocks past the rim, gone at APRON_OUT; headroom it keeps. */
    public static final double APRON_OUT = 56, APRON_FADE = 34, APRON_HEAD = 22;
    /**
     * How much the apron raises the Galleries' massif threshold (their massif noise's units): it opens the ring. Not read
     * by the Galleries' field as it stands: its massif closes a column cell outright and the apron moves the cell's floor
     * and vault instead ({@code GalleriesField.shape}).
     */
    public static final double APRON_OPEN = 0.35;
    /** The apron's floor under the tubes' floor tendency (0: mouths open level, both ways walkable; 1.5 measured worse). */
    static final double APRON_DROP = 0;
    /** A tube's mean half-width (for the apron's floor). */
    static final double R_MEAN = 6.1;

    /** The lining's reach into the rock, for the biome (blocks). */
    public static final double SHELL_BIOME = 4;

    final long seed;
    /** The lava table (first block over the lava): heights are over it. */
    final int W;
    private final Perlin rimA, rimB, warpN, twinN, radN, heightN, floorN, ribN, ribJ;

    public TubeField(long seed, int lavaTable) {
        this.seed = seed;
        this.W = lavaTable;
        long s = seed ^ 0x5DEECE66DA3B1F27L;
        rimA = new Perlin(Hashing.mix(s, 1, 0, 0));
        rimB = new Perlin(Hashing.mix(s, 2, 0, 0));
        warpN = new Perlin(Hashing.mix(s, 3, 0, 0));
        twinN = new Perlin(Hashing.mix(s, 4, 0, 0));
        radN = new Perlin(Hashing.mix(s, 5, 0, 0));
        heightN = new Perlin(Hashing.mix(s, 6, 0, 0));
        floorN = new Perlin(Hashing.mix(s, 7, 0, 0));
        ribN = new Perlin(Hashing.mix(s, 8, 0, 0));
        ribJ = new Perlin(Hashing.mix(s, 9, 0, 0));
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
     * Galleries' veto ({@code GalleriesField.countryStands}), which reads its frame and size.
     */
    public static final class Country {
        public final int ci, cj;
        public final boolean on;
        public final double ox, oz, cos, sin, a, b, S, phase, h0, slope, nu, nv, rimWarp;

        Country(int ci, int cj, boolean on, double ox, double oz, double ang, double a, double b, double S, double phase,
                double h0, double slope, double nu, double nv) {
            this.ci = ci; this.cj = cj; this.on = on; this.ox = ox; this.oz = oz;
            this.cos = Math.cos(ang); this.sin = Math.sin(ang);
            this.a = a; this.b = b; this.S = S; this.phase = phase; this.h0 = h0; this.slope = slope;
            this.nu = nu; this.nv = nv;
            this.rimWarp = RIM_WARP * b;
        }

        /** Along the flow. */
        double v(double x, double z) { return (x - ox) * cos + (z - oz) * sin; }
        /** Across the flow. */
        double u(double x, double z) { return -(x - ox) * sin + (z - oz) * cos; }
    }

    /** The tube country of cell (ci, cj) (pure: hashed from the seed and the cell). */
    Country country(int ci, int cj) {
        long k = Hashing.mix(seed ^ 0x7F4A7C159E3779B9L, ci, cj, 77);
        if (Hashing.unit(k) >= SITE_P) return new Country(ci, cj, false, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0);
        double ox = (ci + 0.5 + JIT * 2 * (Hashing.unit(Hashing.mix(k, 1, 0, 0)) - 0.5)) * CELL;
        double oz = (cj + 0.5 + JIT * 2 * (Hashing.unit(Hashing.mix(k, 2, 0, 0)) - 0.5)) * CELL;
        double ang = Math.PI * 2 * Hashing.unit(Hashing.mix(k, 3, 0, 0));
        double a = A_MIN + (A_MAX - A_MIN) * Hashing.unit(Hashing.mix(k, 4, 0, 0));
        double b = a * (ASPECT_MIN + (ASPECT_MAX - ASPECT_MIN) * Hashing.unit(Hashing.mix(k, 5, 0, 0)));
        double S = S_MIN + (S_MAX - S_MIN) * Hashing.unit(Hashing.mix(k, 6, 0, 0));
        double phase = Hashing.unit(Hashing.mix(k, 7, 0, 0));
        double h0 = H_MIN + (H_MAX - H_MIN) * Hashing.unit(Hashing.mix(k, 8, 0, 0));
        double slope = SLOPE_MAX * (2 * Hashing.unit(Hashing.mix(k, 9, 0, 0)) - 1);
        double nu = 1000 * Hashing.unit(Hashing.mix(k, 10, 0, 0)), nv = 1000 * Hashing.unit(Hashing.mix(k, 11, 0, 0));
        return new Country(ci, cj, true, ox, oz, ang, a, b, S, phase, h0, slope, nu, nv);
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
     * the massif, over {@link #RHO_TUBE} the tubes. -9 where no country is near.
     */
    double rho(Country c, double x, double z) {
        if (!c.on) return -9;
        double u = c.u(x, z), v = c.v(x, z);
        double r0 = Math.sqrt((v / c.a) * (v / c.a) + (u / c.b) * (u / c.b));
        if (r0 > R_FAR) return 1 - r0;   // beyond the apron's reach: the warp no longer matters
        double wu = u + c.rimWarp * rimA.noise2(x / RIM_SCALE + c.nu, z / RIM_SCALE), wv = v + c.rimWarp * rimB.noise2(x / RIM_SCALE, z / RIM_SCALE + c.nv);
        return 1 - Math.sqrt((wv / c.a) * (wv / c.a) + (wu / c.b) * (wu / c.b));
    }

    /**
     * Over 0 in a tube country's massif: the Galleries keep rock there from floor to vault. About the distance in blocks
     * from the massif's rim (rho over the unwarped ellipse's gradient, so the tips read true too); -SOLID with no country.
     */
    public double massif(int x, int z) { return massifAt(x + 0.5, z + 0.5); }

    /** {@link #massif} at any point (the Galleries read it at their column cells' sites). */
    public double massifAt(double x, double z) {
        Country c = countryAt(x, z);
        if (!c.on) return -SOLID;
        return blocks(c, x, z, rho(c, x, z));
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
     * The apron round a tube country, 0..1: 1 from the massif's rim out past the tubes' ends, 0 from
     * {@link #APRON_OUT} blocks out. The Galleries pull their floor toward {@link #apronFloorAt} by this weight and keep
     * {@link #APRON_HEAD} of headroom over it, so the tubes' mouths open at the cavern's floor (a climate-style blend).
     */
    public double apronAt(double x, double z) {
        Country c = countryAt(x, z);
        if (!c.on) return 0;
        double m = blocks(c, x, z, rho(c, x, z));
        return smooth((m + APRON_OUT) / APRON_FADE);
    }

    /** The floor height (absolute y) the apron pulls the cavern's floor to: the tubes' own floor tendency there. */
    public double apronFloorAt(double x, double z) {
        Country c = countryAt(x, z);
        if (!c.on) return W + H_MIN;
        double yc = W + c.h0 + H_AMP * heightN.noise2(x / H_SCALE + c.nu, z / H_SCALE + c.nv) + c.slope * c.v(x, z);
        double Rv = R_MEAN * VSTRETCH;
        yc = Math.max(yc, W + FLOOR_MIN + FLOOR_CUT * Rv);
        yc = Math.min(yc, W + VAULT_MAX - Rv);
        return yc - FLOOR_CUT * Rv - APRON_DROP;
    }

    /** 0 outside any tube country, 1 well inside: the Obsidian Tubes' climate weight. */
    public double region(int x, int z) {
        Country c = countryAt(x + 0.5, z + 0.5);
        if (!c.on) return 0;
        return smooth((rho(c, x + 0.5, z + 0.5) - RHO_TUBE) / (0.1 - RHO_TUBE));
    }

    // ================================================================================================ the tube at a point
    /** What the tube field is at a point (x, z) of the plane: the nearest line, its twin split, the section's size. */
    static final class Pt {
        boolean on;          // a tube line is near
        double rho;
        int line;            // the line's index k in its country
        double s;            // signed distance from the line (blocks)
        double o;            // the twins' offset either side of the line (0: one tube)
        double d;            // distance from the nearer twin's axis (blocks)
        double R, Rv;        // half-width and vault height of the section (before the end fade and ribs)
        double yc;           // axis height (absolute y)
        double phi;          // along-coordinate of the axis point nearest (v there)
        double ribP, ribD, ribK; // rib spacing, depth, strength 0..1
        double ribQ;         // blocks from the nearest rib's middle (set by rib())
        double fade;         // end fade 0..1
        Country c;
    }

    /** Fills p with the tube field at (x, z) (block-centre coordinates). */
    void at(double x, double z, Pt p) {
        p.on = false;
        Country c = countryAt(x, z);
        p.c = c;
        if (!c.on) { p.rho = -9; return; }
        double rho = rho(c, x, z);
        p.rho = rho;
        if (rho <= RHO_TUBE) return;
        double u = c.u(x, z), v = c.v(x, z);
        double A = WARP_A * c.S;
        double nu = c.nu, nv = c.nv;
        double U = u + A * warpN.noise2(u / WARP_LU + nu, v / WARP_LV + nv);
        double e = 0.5;
        double dUdu = 1 + A * (warpN.noise2((u + e) / WARP_LU + nu, v / WARP_LV + nv) - warpN.noise2((u - e) / WARP_LU + nu, v / WARP_LV + nv)) / (2 * e);
        double dUdv = A * (warpN.noise2(u / WARP_LU + nu, (v + e) / WARP_LV + nv) - warpN.noise2(u / WARP_LU + nu, (v - e) / WARP_LV + nv)) / (2 * e);
        double g = Math.sqrt(dUdu * dUdu + dUdv * dUdv);
        double f = U / c.S - c.phase;
        int k = (int) Math.floor(f);
        double s = (f - k - 0.5) * c.S / g;
        // the line's own values
        long lk = Hashing.mix(seed, c.ci * 31L + c.cj, k, 991);
        double R = R_MIN + (R_MAX - R_MIN) * Math.pow(Hashing.unit(lk), 0.9);
        R *= 1 + R_WANDER * radN.noise2(v / R_WANDER_L + nv, k * 5.31 + nu);
        double tw = twinN.noise2(v / TWIN_L + nv, k * 7.77 + 3.1 + nu);
        double pier = PIER_MIN + (PIER_MAX - PIER_MIN) * Hashing.unit(Hashing.mix(lk, 1, 0, 0));
        double o = (R + pier) * smooth((tw - T_ON) / T_RAMP);
        double as = Math.abs(s);
        double d = Math.abs(as - o);
        // the nearest point of the twin's axis, and the along-coordinate there (ribs stand square across the tube)
        double nU = dUdu / g, nV = dUdv / g;           // the line's normal in the (u, v) frame
        double off = s - Math.signum(s) * o;           // signed distance from the nearer twin's axis
        double phi = v - off * nV;
        double fade = smooth((rho - RHO_TUBE) / END_FADE);
        double yc = W + c.h0 + H_AMP * heightN.noise2(x / H_SCALE + nu, z / H_SCALE + nv) + c.slope * v;
        double Rv = R * VSTRETCH;
        yc = Math.max(yc, W + FLOOR_MIN + FLOOR_CUT * Rv);
        yc = Math.min(yc, W + VAULT_MAX - Rv);
        p.on = true;
        p.line = k;
        p.s = s;
        p.o = o;
        p.d = d;
        p.R = R * fade;
        p.Rv = Rv * fade;
        p.yc = yc;
        p.phi = phi;
        p.fade = fade;
        p.ribP = RIB_P_MIN + (RIB_P_MAX - RIB_P_MIN) * Hashing.unit(Hashing.mix(lk, 2, 0, 0));
        p.ribD = RIB_D_MIN + (RIB_D_MAX - RIB_D_MIN) * Hashing.unit(Hashing.mix(lk, 3, 0, 0));
        p.ribK = smooth((ribN.noise2(v / RIB_L + nv, k * 3.73 + nu) - RIB_OFF) / RIB_RAMP);
    }

    /** The smooth floor of the section at a point (absolute y): flat in the middle, a bowl toward the walls. */
    double floorY(Pt p, double x, double z) {
        double t = p.R > 1e-6 ? p.d / p.R : 9;
        double yf = p.yc - FLOOR_CUT * p.Rv + FLOOR_WOBBLE * floorN.noise2(x / 9.0, z / 9.0);
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

    /** The rib's share (0..1) at along-coordinate phi of a line. */
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
    /** A column's tube: the open rows F..C-1 (none when C <= F), the pool rows, the smooth section for the lining. */
    static final class Col {
        int x, z;
        boolean valid;
        boolean near;        // the tube field reaches this column (within the lining's reach)
        int F, C;            // open rows [F, C)
        int waterLo, waterHi; // water rows [waterLo, waterHi) (empty when equal)
        int spanLo, spanHi;  // rows the per-block pass must look at
        // smooth section at the block centre, for shellDepth
        double yc, d, R, Rv, Rr, Rvr, yfs, rho, rib;
        int cq, cr;          // the column cell
        final Pt pb = new Pt(), pc = new Pt();
        final int[] cell = new int[2], rows = new int[2];
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
        int x = c.x, z = c.z;
        c.near = false;
        c.rib = 0;
        c.F = 0;
        c.C = 0;
        c.waterLo = c.waterHi = 0;
        c.spanLo = 1;
        c.spanHi = 0;
        Pt pb = c.pb;
        at(x + 0.5, z + 0.5, pb);
        c.rho = pb.rho;
        if (!pb.on) return;
        double rib = rib(pb);
        c.rib = rib;
        c.yc = pb.yc;
        c.d = pb.d;
        c.R = pb.R;
        c.Rv = pb.Rv;
        c.Rr = Math.max(0, pb.R - RIB_WALL * pb.ribD * rib);
        c.Rvr = Math.max(0, pb.Rv - pb.ribD * rib);
        c.yfs = floorY(pb, x + 0.5, z + 0.5);
        if (pb.d - pb.R > SHELL_BIOME + 3) return;   // too far out for the lining
        c.near = true;
        c.spanLo = (int) Math.floor(c.yfs) - 8;
        c.spanHi = (int) Math.ceil(pb.yc + pb.Rv) + 8;
        if (!rowsOf(x, z, pb, rib, c.pc, c.cell, c.rows)) return;
        c.cq = c.cell[0];
        c.cr = c.cell[1];
        c.F = c.rows[0];
        c.C = c.rows[1];
        pool(c, x, z);
    }

    /**
     * The open rows {F, C} of block column (x, z) without pools, from its column cell's floor and vault and the rib cut
     * at the block: false where the tube does not open the column. pb is the field at the block, rib its rib share.
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
        // the floor, per column cell: steps with column-shaped risers
        cellOf(x, z, cell);
        int q = cell[0], r = cell[1];
        double sx = siteX(q, r), sz = siteZ(q, r);
        at(sx, sz, pc);
        if (!pc.on) return false;
        int F = (int) Math.round(floorY(pc, sx, sz));
        // the fringe of short column ends hanging from the vault in the bays between the ribs
        boolean bay = pb.ribK <= 0 || pb.ribQ > RIB_W * 0.5 + RIB_EDGE + FRINGE_CLEAR;
        if (bay && pb.d < HANG_REACH * pb.R) {
            double hu = h(x, z, 5);
            int hang = hu < FRINGE_P3 ? 3 : (hu < FRINGE_P2 ? 2 : (hu < FRINGE_P ? 1 : 0));
            if (h(q, r, 3) < HANG_P) hang += 1;
            yv -= hang;
        }
        int C = (int) Math.round(yv);
        if (C - F < 2) return false;
        out[0] = F;
        out[1] = C;
        return true;
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
     * columns in its disc. Its water's top row is one under the lowest floor in its disc and the ring of a block round it,
     * so every block beside the water at its rows is rock.
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
            if (o != null && o[0] > dr[0] && Math.hypot(o[1] - cx, o[2] - cz) < rd + o[3] + 2) return v;
        }
        Pt p = new Pt();
        at(cx, cz, p);
        if (!p.on || p.rho < RHO_POOL || p.fade < 1 || p.d > DIP_MID * p.R) return v;
        int[] cell = new int[2];
        int F0 = Integer.MAX_VALUE, open = 0;
        int ext = (int) Math.ceil(rd + 1.5);
        int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        for (int dx = -ext; dx <= ext; dx++) for (int dz = -ext; dz <= ext; dz++) {
            double ddx = bx + dx + 0.5 - cx, ddz = bz + dz + 0.5 - cz, dd = Math.sqrt(ddx * ddx + ddz * ddz);
            if (dd > rd + 1.5) continue;
            int[] fc = openFloor(bx + dx, bz + dz, cell);
            if (fc == null) continue;                  // rock (the wall or a rib's jamb): it seals the pool
            F0 = Math.min(F0, fc[0]);
            if (dd <= rd) open++;
        }
        if (open < 3 || F0 - 3 < W + Terrain.FLOOD_RISE + 1) return v;
        v[0] = 1;
        v[1] = F0;
        v[2] = (int) Math.round(cx * 16);
        v[3] = (int) Math.round(cz * 16);
        v[4] = (int) Math.round(rd * 16);
        return v;
    }

    /** The floor and vault {F, C} of block column (bx, bz) without pools, or null where the tube does not open it. */
    private int[] openFloor(int bx, int bz, int[] cell) {
        Pt pb = new Pt(), pc = new Pt();
        at(bx + 0.5, bz + 0.5, pb);
        if (!pb.on) return null;
        int[] out = new int[2];
        return rowsOf(bx, bz, pb, rib(pb), pc, cell, out) ? out : null;
    }

    private void pool(Col c, int x, int z) {
        int gi0 = (int) Math.floor((x + 0.5) / DIP_SP), gj0 = (int) Math.floor((z + 0.5) / DIP_SP);
        for (int gi = gi0 - 1; gi <= gi0 + 1; gi++) for (int gj = gj0 - 1; gj <= gj0 + 1; gj++) {
            int[] v = dip(gi, gj);
            if (v[0] == 0) continue;
            double cx = v[2] / 16.0, cz = v[3] / 16.0, rd = v[4] / 16.0;
            double dd = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
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
     * Galleries' field takes {@code min(cave, tube)}. Rock reads the distance to the section's smooth envelope, so the
     * deep rock and the surface pass split as they do at any cave wall. SOLID where no tube is near.
     */
    public double tube(int x, int y, int z) {
        Col c = column(x, z);
        if (!c.near) return SOLID;
        if (y >= c.F && y < c.C) {
            if (y >= c.waterLo && y < c.waterHi) return -0.5;
            return -(Math.min(y - c.F, c.C - 1 - y) + 0.5);
        }
        return Math.max(0.5, envelope(c, y));
    }

    /** Distance (about blocks) from the smooth ribbed section's envelope, negative inside it. */
    static double envelope(Col c, int y) {
        double Rr = c.Rr, Rvr = c.Rvr;
        // at a rib on a fading tube end Rvr reaches 0 before Rr: 0 x infinity would be NaN
        if (Rr <= 1e-6 || Rvr <= 1e-6) return SOLID;
        double dy = (y + 0.5 - c.yc) / Rvr, dx = c.d / Rr;
        double sec = (Math.sqrt(dx * dx + dy * dy) - 1) * Math.min(Rr, Rvr);
        return Math.max(sec, c.yfs - (y + 0.5));
    }

    /**
     * Depth into the rock from the tube's smooth (ribbed) section: about 0 at the tube's surface, rising into the rock;
     * under 0 for rock standing inside the smooth section (hanging column ends, floor steps, the ribs' edges); 1e9 where
     * no tube is near. The lining's palette reads it (obsidian skin, basalt and scoria behind).
     */
    public double shellDepth(int x, int y, int z) {
        Col c = column(x, z);
        if (!c.near) return 1e9;
        return envelope(c, y);
    }

    /** True for a block of still water in a pool's dip. */
    public boolean pool(int x, int y, int z) {
        Col c = column(x, z);
        return c.near && y >= c.waterLo && y < c.waterHi;
    }

    /** The rows the per-block pass must look at in column (x, z): [spanLo, spanHi]; empty when spanLo > spanHi. */
    public int spanLo(int x, int z) { Col c = column(x, z); return c.near ? c.spanLo : 1; }
    public int spanHi(int x, int z) { Col c = column(x, z); return c.near ? c.spanHi : 0; }

    /** True in the tube's lining or air: the Obsidian Tubes' biome, where the cave itself is rock. */
    public boolean inLining(int x, int y, int z) {
        Col c = column(x, z);
        return c.near && envelope(c, y) < SHELL_BIOME;
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
            if (!p.on || p.line != k || p.rho < RHO_POOL) continue;
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
}
