package com.explorationremix.world.terrain;

import static com.explorationremix.world.terrain.GalleriesField.*;

/**
 * The Basalt Galleries' landscape as it stands before any tube is cut: the large shapes' warp, the elevation with its
 * hills, the climate patches (Caldera, Causeway, Scoria Canyons) with the basins' shores and the refuge mounds, and the
 * standing massifs' noise. Built before both fields, so each reads it and neither reads the other to make it: the
 * Obsidian Tubes take their floors' heights from the landscape at their mouths and know the Galleries' standing rock
 * their runs go on through ({@link TubeField}), and the Galleries take their floor, climate and massifs from it
 * ({@link GalleriesField} hands its landscape, patch, climate, basin and mound queries on to it and shares its noise).
 * Nothing here reads a tube. Pure and immutable: every value is a function of the seed and the position (the patch
 * cache is per thread and holds only such values), so any number of worldgen threads may call in.
 */
final class GalleriesLandscape {
    final long seed;
    /** The lava table (first block over the lava): heights are over it. */
    final int W;
    /** The noise the Galleries share (the same seeds as before the landscape had its own class: bit for bit). */
    final Perlin warpA, warpB, elevN, massN, hillN;
    /** The halls' walls (0.20.4): their thickness, and the fine warp that makes their faces ragged. */
    final Perlin wallN, wallWa, wallWb;

    GalleriesLandscape(long seed, int lavaTable) {
        this.seed = seed;
        this.W = lavaTable;
        long s = seed ^ 0x5851F42D4C957F2DL;
        warpA = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpB = new Perlin(Hashing.mix(s, 2, 0, 0));
        elevN = new Perlin(Hashing.mix(s, 4, 0, 0));
        massN = new Perlin(Hashing.mix(s, 40, 0, 0));
        hillN = new Perlin(Hashing.mix(s, 52, 0, 0));
        wallN = new Perlin(Hashing.mix(s, 70, 0, 0));
        wallWa = new Perlin(Hashing.mix(s, 71, 0, 0));
        wallWb = new Perlin(Hashing.mix(s, 72, 0, 0));
    }

    /** The large shapes' warped point only (out[0..1]; {@link GalleriesField#warp} adds the Jacobian). */
    void warpXZ(double x, double z, double[] out) {
        out[0] = x + WARP_A * warpA.noise2(x / WARP_P, z / WARP_P);
        out[1] = z + WARP_A * warpB.noise2(x / WARP_P, z / WARP_P);
    }

    // ============================================================================================ climate
    private final ThreadLocal<Patch[]> patches = ThreadLocal.withInitial(() -> new Patch[64]);

    /**
     * The patch of lattice cell (i, j), from a small per-thread cache. Its kind follows the landscape at its middle, so
     * the basins sit in low ground and the canyons are cut into high ground: a lowland patch is a Caldera or a Causeway
     * (hashed), a highland one Scoria Canyons (CAN_SHARE of them), and the ground between has none.
     */
    Patch patch(int i, int j) { return patch(patches.get(), i, j); }

    /** The thread's patch cache, for {@link #patch(Patch[], int, int)} (a loop over many patches looks it up once). */
    Patch[] patchCache() { return patches.get(); }

    /** {@link #patch(int, int)} from the thread's cache pc (one look-up of it for the many patches a column reads). */
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

    /** The climate at column (x, z): the three patch kinds' weights, the strongest patch and its kind ({@link Climate}). */
    void climate(double x, double z, Climate out) {
        climateWarped(x + WARP_A * warpA.noise2(x / WARP_P, z / WARP_P), z + WARP_A * warpB.noise2(x / WARP_P, z / WARP_P), out);
    }

    /** {@link #climate} at the warped point (wx, wz) ({@link #warpXZ}). */
    void climateWarped(double wx, double wz, Climate out) {
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

    // ============================================================================================ the landscape
    /**
     * The landscape at (x, z) (rows, unrounded): the elevation with its hills, the sub-biomes' basins and the refuge
     * mounds. {@code w} must hold the warped point at (x, z) ({@link #warpXZ}).
     */
    double landscape(double x, double z, double[] w) { return landscape(x, z, w, null); }

    /**
     * {@link #landscape(double, double, double[])}, and in base[0] (when base is not null) the ground the halls' vaults
     * stand over: the same without the refuge mounds (a mound is a hill on a hall's floor, not a higher hall).
     */
    double landscape(double x, double z, double[] w, double[] base) {
        double wx = w[0], wz = w[1];
        double Fs = W + ELEV_MID + ELEV_AMP * elevN.fbm2(wx / ELEV_P, wz / ELEV_P, 2) + HL_A * hillN.fbm2(wx / HL_P, wz / HL_P, 2);
        double[] pc = patchCone(w);
        double cone = pc[0], cap = pc[1];
        Fs = Math.min(Fs, cone);
        if (base != null) base[0] = Fs;
        // the refuge mounds stand over the low ground, no higher than the basins' shores allow
        if (Fs < W + MD_TOP0 + MD_TOP1) Fs = Math.max(Fs, Math.min(cap, mounds(w)));
        return Fs;
    }

    /** The landscape at (x, z) with its own warp (the tubes' mouths read it). */
    double floor(double x, double z) {
        double[] w = new double[2];
        warpXZ(x, z, w);
        return landscape(x, z, w);
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

    /** The highest refuge mound floor at the warped point w (rows, unrounded), or -inf ({@link GalleriesField#MD_G}). */
    double mounds(double[] w) {
        double wx = w[0], wz = w[1];
        int gi = (int) Math.floor(wx / MD_G), gj = (int) Math.floor(wz / MD_G);
        double best = -1e9;
        Mound[] mc = moundCache.get();
        for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
            Mound m = mound(mc, gi + di, gj + dj);
            if (!m.on) continue;
            double d = len(wx - m.cx, wz - m.cz);
            best = Math.max(best, m.top - m.sl * Math.max(0, d - m.r));
        }
        return best;
    }

    /** A refuge mound's draw in its lattice cell (i, j) of MD_G blocks: whether one stands, its middle, top, radius, flanks. */
    private static final class Mound {
        int i, j;
        boolean valid, on;
        double cx, cz, top, r, sl;
    }

    /** The mounds' draws a thread has read of late (a 5 x 5 window of cells is read per column: 16 x 16 slots). */
    private final ThreadLocal<Mound[]> moundCache = ThreadLocal.withInitial(() -> {
        Mound[] a = new Mound[256];
        for (int k = 0; k < a.length; k++) a[k] = new Mound();
        return a;
    });

    /** Mound cell (i, j)'s draw (hashed from the seed and the cell, as each column read it before the cache). */
    private Mound mound(Mound[] mc, int i, int j) {
        Mound m = mc[(i & 15) << 4 | (j & 15)];
        if (m.valid && m.i == i && m.j == j) return m;
        m.valid = true;
        m.i = i;
        m.j = j;
        long h = Hashing.mix(seed, 53001, i, j);
        m.on = Hashing.unit(h) < MD_P;
        if (!m.on) return m;
        m.cx = (i + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * MD_G;
        m.cz = (j + 0.15 + 0.7 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * MD_G;
        m.top = W + MD_TOP0 + MD_TOP1 * Hashing.unit(Hashing.mix(h, 3, 0, 0));
        m.r = MD_R0 + MD_R1 * Hashing.unit(Hashing.mix(h, 4, 0, 0));
        m.sl = MD_S0 + (MD_S1 - MD_S0) * Hashing.unit(Hashing.mix(h, 5, 0, 0));
        return m;
    }

    // ============================================================================================ the halls
    /**
     * The halls (0.20.4, Taran: "The caves should be small enough to read as caves but still be the biggest caves in the
     * game"): a jittered lattice of HALL_G blocks in the large shapes' warped plane, each point a hall's middle; every
     * place belongs to the nearest one, and between two halls stands a wall along the line where they meet (half of the
     * difference of the distances to the two nearest middles is the distance to that line), its half thickness
     * {@link #wallRaw} from a slow noise, so a wall is thick in places and thin in others and breaks where the thickness
     * falls under 0. Read in the plane warped again by a fine noise (WALL_WA over WALL_WP), so the walls' faces are
     * ragged rather than straight. In the Caldera's and the Causeway's basins the walls fade out (each basin is one great
     * hall, the biggest). Pure: a function of the seed and the place.
     */
    static final double HALL_G = 130, HALL_J = 0.7, WALL_WA = 15, WALL_WP = 55;
    /**
     * Each hall's reach: its distance is read less HALL_W0..HALL_W1 blocks (by its hash), so a big hall pushes its walls
     * out into its neighbours' and the walls between halls of two sizes curve.
     */
    static final double HALL_W0 = -15, HALL_W1 = 15;
    /**
     * A wall's half thickness: WALL_T0 + WALL_T1 x a 2-octave noise of period WALL_P, at most WALL_CAP of the distance
     * between the two halls' middles (so no small hall is all wall); in a basin WALL_BASIN less per its weight.
     */
    static final double WALL_T0 = 8, WALL_T1 = 20, WALL_P = 70, WALL_BASIN = 40, WALL_MIN = 4, WALL_CAP = 0.16;
    /**
     * The doorways: every two neighbouring halls share one, a gap across the wall between them on a line parallel to the
     * one between their middles, set off it by up to DOOR_OFF blocks either way (by the pair's hash), where the wall thins
     * by DOOR_D in a band of DOOR_W0..DOOR_W0 + DOOR_W1 blocks either side of that line, so it breaks in a gap of about 10
     * to 35 blocks: every hall opens into each of its neighbours. (0.20.4's first doorways, three rays out of each hall's
     * middle, met the walls at random and left whole halls shut: in JoinTest's box of seed 2 the main walk component held
     * 55 % of the Galleries' dry places, 96 % on main.) Read for the pair of the nearest two halls, fading out within
     * DOOR_FADE of a corner where a third hall comes as near (so the walls stay whole where the pair read changes).
     */
    static final double DOOR_D = 48, DOOR_OFF = 10, DOOR_W0 = 8, DOOR_W1 = 9, DOOR_FADE = 16;
    /** A hall's height over its landscape at the middle: HALL_H0..HALL_H0 + HALL_H1 rows, the bigger halls the taller. */
    static final double HALL_H0 = 46, HALL_H1 = 40;

    /** The hall lattice point (i, j)'s hash (its middle: {@link #siteX}, {@link #siteZ}). */
    long hallHash(int i, int j) { return Hashing.mix(seed, 71001, i, j); }

    static double siteX(int i, long h) { return (i + 0.5 + HALL_J * (Hashing.unit(Hashing.mix(h, 1, 0, 0)) - 0.5)) * HALL_G; }

    static double siteZ(int j, long h) { return (j + 0.5 + HALL_J * (Hashing.unit(Hashing.mix(h, 2, 0, 0)) - 0.5)) * HALL_G; }

    /**
     * The halls at the warped point w (climate cl): out[0] the blocks from the nearest wall's face (under 0 inside a
     * wall), out[1] the vault's distance: the blocks from the wall's line less its half thickness before the
     * {@link #WALL_MIN} step, so it runs on smoothly past a wall's end and grows into a doorway or a break (its vault
     * rises there: an arch between two halls, high where the gap is wide), out[2] the hall's height (rows over the
     * landscape at its middle) and out[3] the hall's hash (as a double's bits). {@code give}: blocks the wall thins here
     * besides its doorways (round the holes, {@link GalleriesField#give}).
     */
    void hall(double[] w, Climate cl, double[] out, double give) {
        double wx = w[0] + WALL_WA * wallWa.noise2(w[0] / WALL_WP, w[1] / WALL_WP);
        double wz = w[1] + WALL_WA * wallWb.noise2(w[0] / WALL_WP, w[1] / WALL_WP);
        int gi = (int) Math.floor(wx / HALL_G), gj = (int) Math.floor(wz / HALL_G);
        double d1 = 1e18, d2 = 1e18, d3 = 1e18, ax = 0, az = 0, bx = 0, bz = 0, r1 = 0;
        long h1 = 0, h2 = 0;
        for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
            long h = hallHash(gi + di, gj + dj);
            double sx = siteX(gi + di, h), sz = siteZ(gj + dj, h), r = hallReach(h);
            double d = len(wx - sx, wz - sz) - r;
            if (d < d1) {
                d3 = d2;
                d2 = d1; h2 = h1; bx = ax; bz = az;
                d1 = d; h1 = h; ax = sx; az = sz; r1 = r;
            } else if (d < d2) {
                d3 = d2;
                d2 = d; h2 = h; bx = sx; bz = sz;
            } else if (d < d3) d3 = d;
        }
        double e = 0.5 * (d2 - d1);
        double t = Math.min(wallRaw(w, cl), WALL_CAP * len(bx - ax, bz - az)) - give;
        double door = door(wx, wz, ax, az, h1, bx, bz, h2);
        if (door > 0) t -= DOOR_D * door * smooth(Math.min(1, (d3 - d2) / DOOR_FADE));
        out[0] = e - (t > 0 ? WALL_MIN + t : t);
        out[1] = e - t;
        double size = (r1 - HALL_W0) / (HALL_W1 - HALL_W0);
        out[2] = HALL_H0 + HALL_H1 * (0.5 * size + 0.5 * Hashing.unit(Hashing.mix(h1, 3, 0, 0)));
        out[3] = Double.longBitsToDouble(h1 & 0x7FEFFFFFFFFFFFFFL);
    }

    /**
     * The doorway of the halls of middles (ax, az) and (bx, bz), hashes ha and hb, at the (twice warped) point (x, z): 1
     * on its line, 0 out of its band. The same whichever hall is read first.
     */
    static double door(double x, double z, double ax, double az, long ha, double bx, double bz, long hb) {
        if (ha > hb) {
            double tx = ax, tz = az;
            long th = ha;
            ax = bx; az = bz; ha = hb;
            bx = tx; bz = tz; hb = th;
        }
        double dx = bx - ax, dz = bz - az, l = len(dx, dz);
        if (l < 1e-9) return 0;
        long hp = Hashing.mix(ha, hb, 7, 0);
        double across = ((x - ax) * dz - (z - az) * dx) / l - DOOR_OFF * (2 * Hashing.unit(Hashing.mix(hp, 1, 0, 0)) - 1);
        double hw = DOOR_W0 + DOOR_W1 * Hashing.unit(Hashing.mix(hp, 2, 0, 0));
        return Math.abs(across) < hw ? 1 - smooth(Math.abs(across) / hw) : 0;
    }

    /** Hall {@code h}'s reach (blocks its distance is read less). */
    static double hallReach(long h) { return HALL_W0 + (HALL_W1 - HALL_W0) * Hashing.unit(Hashing.mix(h, 4, 0, 0)); }

    /** A wall's half thickness at the warped point w before the doorways and the WALL_MIN step (under 0: it breaks). */
    double wallRaw(double[] w, Climate cl) {
        return WALL_T0 + WALL_T1 * wallN.fbm2(w[0] / WALL_P, w[1] / WALL_P, 2) - WALL_BASIN * Math.max(cl.wCal, cl.wCaus);
    }

    /** Whether a hall's wall stands at the warped point w (climate cl), or comes within {@code margin} blocks. */
    boolean wallNear(double[] w, Climate cl, double margin) {
        double[] o = new double[4];
        hall(w, cl, o, 0);
        return o[0] < margin;
    }

    // ============================================================================================ standing rock
    /**
     * The massifs' noise at the warped point w less its threshold, the basins' weakening in (over 0: a massif stands, rock
     * from floor to vault; {@link GalleriesField#M_T}). {@code cl} is the climate at the point.
     */
    double massif(double[] w, Climate cl) {
        return massN.fbm2(w[0] / M_P, w[1] / M_P, 2) - M_SUB * (cl.wCal + cl.wCaus) - M_T;
    }

    /** {@link #massif(double[], Climate)} at (x, z) with its own warp and climate. */
    double massif(double x, double z) {
        double[] w = new double[2];
        warpXZ(x, z, w);
        Climate cl = new Climate();
        climateWarped(w[0], w[1], cl);
        return massif(w, cl);
    }

    /**
     * Whether a massif stands at (x, z) ({@link #massif(double, double)} over 0; the tubes' runs read it): the climate is
     * read only where the noise passes the threshold, as the basins only weaken it.
     */
    boolean massifStands(double x, double z) {
        double[] w = new double[2];
        warpXZ(x, z, w);
        double n = massN.fbm2(w[0] / M_P, w[1] / M_P, 2);
        Climate cl = new Climate();
        climateWarped(w[0], w[1], cl);
        // 0.20.4: a hall's wall is standing rock as a massif is (a tube's run goes on through it)
        if (wallNear(w, cl, 0)) return true;
        if (n - M_T <= 0) return false;
        return n - M_SUB * (cl.wCal + cl.wCaus) - M_T > 0;
    }
}
