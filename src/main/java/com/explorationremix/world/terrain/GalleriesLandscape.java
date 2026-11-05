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

    GalleriesLandscape(long seed, int lavaTable) {
        this.seed = seed;
        this.W = lavaTable;
        long s = seed ^ 0x5851F42D4C957F2DL;
        warpA = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpB = new Perlin(Hashing.mix(s, 2, 0, 0));
        elevN = new Perlin(Hashing.mix(s, 4, 0, 0));
        massN = new Perlin(Hashing.mix(s, 40, 0, 0));
        hillN = new Perlin(Hashing.mix(s, 52, 0, 0));
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

    /** {@link #patch(int, int)} from the thread's cache pc (one look-up of it for the many patches a column reads). */
    private Patch patch(Patch[] pc, int i, int j) {
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
        if (n - M_T <= 0) return false;
        Climate cl = new Climate();
        climateWarped(w[0], w[1], cl);
        return n - M_SUB * (cl.wCal + cl.wCaus) - M_T > 0;
    }
}
