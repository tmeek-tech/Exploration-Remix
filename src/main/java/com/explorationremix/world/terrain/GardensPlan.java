package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.explorationremix.world.terrain.GardensMath.clipF;
import static com.explorationremix.world.terrain.GardensMath.pull;
import static com.explorationremix.world.terrain.GardensMath.rampF;
import static com.explorationremix.world.terrain.GardensNoise.fbm2;
import static com.explorationremix.world.terrain.GardensNoise.hash2;
import static com.explorationremix.world.terrain.GardensNoise.hv;
import static com.explorationremix.world.terrain.GardensNoise.n2;

/**
 * The Deep Gardens' plan (round 2, built 2026-11-18 from the offline previews in project files
 * {@code deep-gardens/shape-options-r2}): seven storeys of floors from one noise frame, each a blend of three styles by
 * where its floor sits against two heat-lifted borders: Vent Domes under the low border (the bottom band), Chimney Halls
 * between, Seep Beds over the high border (the top band). This class holds what is a pure function of a column (the
 * frame, the styles' noise) and of a cell (the vent halls, domes and deep sites); {@link GardensStoreys} turns them
 * into each storey's floor and ceiling over a window.
 *
 * <p>Rows are the layer's own: row 0 is the layer's bottom block, row 239 its top. Columns are world x and z.
 */
final class GardensPlan {
    static final int K = 7, SY = 240, SEAL = 4;
    static final float[] BASES = {18f, 62f, 104f, 146f, 178f, 201f, 224f};
    static final float[] SIG = {1f, 1f, 1f, 1f, 0.5f, 0.5f, 0.5f}, TAU = {0f, 0f, 0f, 0.5f, 1f, 1f, 1f}, UND = {4f, 4f, 4f, 4f, 3f, 3f, 3f};
    /** A vent hall's radius base, the tallest hall, the tallest ordinary dome, the rock a hall's cup keeps under the storey above. */
    static final double RV0 = 20, HALL_CAP = 38, DOME_CAP = 24, CUP_GAP = 8;
    /** The lower gate's dome radius; the tunnels' scale. */
    static final double GATE_R = 24, TUN_SCALE = 70;
    /**
     * Which storeys each style is worked out on in full, as the preview did over its patch (a style is worked out where
     * any column of the patch had its weight): there its rooms may stand a little past where its weight fades, at the
     * noise's peaks. On the other storeys a style only stands where its weight is above zero.
     */
    static final boolean[] HALLS = {false, true, true, true, true, false, false};
    static final boolean[] BEDS = {false, false, false, true, true, true, true};
    static final boolean[] DOMES = {true, true, true, true, true, true, true};

    /** This plan's style activity per storey (the game's: {@link #HALLS}, {@link #BEDS}, {@link #DOMES}). */
    final boolean[] halls, beds, domes;

    final int seed;
    final GardensGates gates;
    /** The offline preview's patch rules (sites and slides only inside the patch), for comparing with its arrays. */
    final boolean patch;
    final int px0, pz0, psx, psz;

    private final Map<Integer, int[]> perms = new ConcurrentHashMap<>();
    final int[] W, WB, P610, P611, P602, P603, P1201, P1202, P601, P730, P600, P3958;
    final int[][] PUND = new int[K][], PR = new int[K - 1][], PM0 = new int[K][], PCV = new int[K][], PDD = new int[K][], PWN = new int[K][];
    final int[][] PMB = new int[K][], PPIL = new int[K][], PHN = new int[K][], PCOL = new int[K][], PT1 = new int[K][], PT2 = new int[K][], PTW = new int[K][];

    GardensPlan(int seed, GardensGates gates) {
        this(seed, gates, false, 0, 0, 0, 0, HALLS, BEDS, DOMES);
    }

    GardensPlan(int seed, GardensGates gates, boolean patch, int px0, int pz0, int psx, int psz, boolean[] halls,
                boolean[] beds, boolean[] domes) {
        this.seed = seed;
        this.halls = halls.clone();
        this.beds = beds.clone();
        this.domes = domes.clone();
        this.gates = gates;
        this.patch = patch;
        this.px0 = px0;
        this.pz0 = pz0;
        this.psx = psx;
        this.psz = psz;
        W = perm(seed + 699);
        WB = perm(seed + 1299);
        P610 = perm(seed + 610);
        P611 = perm(seed + 611);
        P602 = perm(seed + 602);
        P603 = perm(seed + 603);
        P1201 = perm(seed + 1201);
        P1202 = perm(seed + 1202);
        P601 = perm(seed + 601);
        P730 = perm(seed + 730);
        P600 = perm(seed + 600);
        P3958 = perm(seed + 3958);
        for (int k = 0; k < K; k++) {
            PUND[k] = perm(seed + 620 + k);
            if (k < K - 1) PR[k] = perm(seed + 610 + k);
            PM0[k] = perm(seed + 630 + k);
            PCV[k] = perm(seed + 680 + k);
            PDD[k] = perm(seed + 1130 + k);
            PWN[k] = perm(seed + 1140 + k);
            PMB[k] = perm(seed + 740 + k);
            PPIL[k] = perm(seed + 750 + k);
            PHN[k] = perm(seed + 760 + k);
            PCOL[k] = perm(seed + 780 + k);
            PT1[k] = perm(seed + 1400 + k);
            PT2[k] = perm(seed + 1450 + k);
            PTW[k] = perm(seed + 1500 + k);
        }
    }

    int[] perm(int s) { return perms.computeIfAbsent(s, GardensNoise::perm); }

    /** The game's seed folded to the previews' 31 bits (8675309 stays 8675309). */
    static int foldSeed(long worldSeed) { return (int) (worldSeed ^ (worldSeed >>> 32)) & 0x7FFFFFFF; }

    // ============================================================================================ one column

    /**
     * Everything about one column that is a pure function of its position: the frame (each storey's base floor and the
     * two borders), the heat, the mound field, each storey's style weights and the noise its rooms are cut from.
     */
    static final class Col {
        float hl, hot, mound, lb, hb, big;
        final float[] f = new float[K], wd = new float[K], wh = new float[K], wb = new float[K];
        final float[] td = new float[K], tw = new float[K], m0 = new float[K], cv = new float[K], wn = new float[K];
        final float[] mb = new float[K], pil = new float[K], col = new float[K], hn = new float[K];
        float cap6;
        /** Scratch for the noise's slope. */
        final double[] tmp = new double[3];
        /** The nearest landing of each kind within reach (null if none), for the pulls. */
        GardensGates.Gate entry, exit;
    }

    /** Fills c for column (wx, wz); {@code mask} picks the storeys whose room noise is wanted (bit k). */
    void column(int wx, int wz, Col c, int mask) { column(wx, wz, c, mask, false); }

    /**
     * {@link #column(int, int, Col, int)}; {@code lean} leaves out what only the storeys' heights and the dressing read
     * (heat, mounds, the hall and bed height noises, the wall noise, the top's cap), for the footprint's inputs.
     */
    void column(int wx, int wz, Col c, int mask, boolean lean) {
        double x = wx, z = wz;
        // shared warps
        double wa90 = n2(W, x / 90.0, z / 90.0), wb90 = n2(W, x / 90.0 + 41.0, z / 90.0 - 17.0);
        double wa110 = 0, wb110 = 0;
        if (mask != 0) {
            wa110 = n2(W, x / 110.0, z / 110.0);
            wb110 = n2(W, x / 110.0 + 41.0, z / 110.0 - 17.0);
        }
        double wB1 = n2(WB, x / 120.0, z / 120.0), wB2 = n2(WB, x / 120.0 + 41.0, z / 120.0 - 17.0);
        // frame
        float hl = (float) fbm2(P610, x / 95.0, z / 95.0, 2);
        c.hl = hl;
        if (!lean) {
            c.hot = hl + 0.5f * (float) n2(P611, x / 17.0, z / 17.0);
            float ma = (float) fbm2(P602, x / 80.0, z / 80.0, 2), mb2 = (float) n2(P603, x / 26.0, z / 26.0);
            c.mound = (float) Math.pow(rampF(ma + 0.45f * mb2, -0.05, 0.55), (double) 1.4f);
        }
        double lx = x + 20.0 * wB1, lz = z + 20.0 * wB2;
        float fl = (float) fbm2(P1201, lx / 150.0, lz / 150.0, 2), fh = (float) fbm2(P1202, lx / 165.0, lz / 165.0, 2);
        float lb = clipF(100f + 14f * fl + 18f * (hl - 0.13f), 76f, 132f);
        float hb = clipF(Math.max(lb + 44f, 166f + 12f * fh + 12f * (hl - 0.13f)), 148f, 190f);
        c.lb = lb;
        c.hb = hb;
        float stack = 14f * (float) fbm2(P601, x / 280.0, z / 280.0, 2);
        float T = 16f * (float) fbm2(P730, x / 260.0, z / 260.0, 2);
        float[] f0 = new float[K], R = new float[K - 1];
        for (int k = 0; k < K; k++) f0[k] = BASES[k] + SIG[k] * stack + TAU[k] * T + UND[k] * (float) fbm2(PUND[k], x / 60.0, z / 60.0, 2);
        for (int k = 0; k < K - 1; k++) R[k] = rampF((float) fbm2(PR[k], x / (k < 3 ? 220.0 : 170.0), z / (k < 3 ? 220.0 : 170.0), 2), 0.0, k < 3 ? 0.4 : 0.45);
        List<GardensGates.Gate> gs = gates.near(x, z, 90);
        c.entry = null;
        c.exit = null;
        double de = 1e9, dx = 1e9;
        for (GardensGates.Gate g : gs) {
            double d = Math.hypot(g.x() - x, g.z() - z);
            if (g.entry() && d < de) { de = d; c.entry = g; }
            if (!g.entry() && d < dx) { dx = d; c.exit = g; }
        }
        for (int k = 0; k < K; k++) {
            float up = k < K - 1 ? 0.5f * (f0[k + 1] - f0[k]) * R[k] : 0f;
            float dn = k > 0 ? 0.5f * (f0[k] - f0[k - 1]) * R[k - 1] : 0f;
            float f = f0[k] + up - dn;
            if (k == K - 1 && c.entry != null) {
                float pe = pull(x, z, c.entry.x(), c.entry.z(), 20.0, 70.0);
                f = f + ((float) c.entry.floor() - f) * pe;
            }
            if (k == 0 && c.exit != null) {
                float px = pull(x, z, c.exit.x(), c.exit.z(), 22.0, 80.0);
                f = f + ((float) c.exit.floor() - f) * px;
            }
            c.f[k] = f;
            float wd = 1f - rampF(f - lb, -5, 5), wb = rampF(f - hb, -5, 5);
            c.wd[k] = wd;
            c.wb[k] = wb;
            c.wh[k] = clipF(1f - wd - wb, 0f, 1f);
        }
        boolean anyHall = false;
        for (int k = 0; k < K; k++) if ((mask >> k & 1) != 0 && hallsHere(k, c.wh[k])) anyHall = true;
        c.big = anyHall ? rampF((float) fbm2(P600, x / 210.0, z / 210.0, 2), -0.3, 0.6) : 0f;
        double tx = x + 12.0 * wa90, tz = z + 12.0 * wb90;
        for (int k = 0; k < K; k++) {
            if ((mask >> k & 1) == 0) continue;
            float td1 = (float) (patch ? zld(PT1[k], TUN_SCALE, tx, tz) : zldA(PT1[k], TUN_SCALE, tx, tz, c.tmp));
            float td2 = (float) (patch ? zld(PT2[k], TUN_SCALE * 92.0 / 70.0, tx, tz) : zldA(PT2[k], TUN_SCALE * 92.0 / 70.0, tx, tz, c.tmp));
            c.td[k] = Math.min(td1, td2 + 0.5f);
            c.tw[k] = 2.4f * (1f + 0.35f * (float) n2(PTW[k], x / 24.0, z / 24.0));
            if (hallsHere(k, c.wh[k])) {
                float m = (float) fbm2(PM0[k], (x + 16.0 * wa90) / 56.0, (z + 16.0 * wb90) / 56.0, 3);
                c.m0[k] = m - 0.14f + 0.18f * c.big - 0.6f * (1f - c.wh[k]);
                if (!lean) c.cv[k] = 1f + 0.25f * (float) fbm2(PCV[k], x / 20.0, z / 20.0, 2);
            } else {
                c.m0[k] = -9f;
                c.cv[k] = 1f;
            }
            if (!lean) c.wn[k] = 0.12f * (float) n2(PWN[k], x / 11.0, z / 11.0);
            if (bedsHere(k, c.wb[k])) {
                float m = (float) fbm2(PMB[k], (x + 18.0 * wa110) / 85.0, (z + 18.0 * wb110) / 85.0, 3);
                float mbk = m + 0.10f - 0.6f * (1f - c.wb[k]);
                if (k == K - 1 && c.entry != null) {
                    float inner6 = pull(x, z, c.entry.x(), c.entry.z(), 10.0, 21.0);
                    float ring6 = pull(x, z, c.entry.x(), c.entry.z(), 30.0, 44.0) - inner6;
                    mbk = mbk - 0.6f * rampF(c.f[k] - 224f, 0, 6) + 0.3f * inner6 - 0.6f * ring6;
                } else if (k == K - 1) {
                    mbk = mbk - 0.6f * rampF(c.f[k] - 224f, 0, 6);
                }
                c.mb[k] = mbk;
                c.pil[k] = (float) fbm2(PPIL[k], x / 10.0, z / 10.0, 2);
                c.col[k] = (k == 3 || k == 5) ? rampF((float) fbm2(PCOL[k], x / 120.0, z / 120.0, 2), 0.22, 0.55) : 0f;
                if (!lean) c.hn[k] = 4.6f + 5.9f * rampF((float) fbm2(PHN[k], x / 50.0, z / 50.0, 2), -0.35, 0.45);
            } else {
                c.mb[k] = -9f;
                c.pil[k] = 1f;
                c.col[k] = 0f;
                c.hn[k] = 4.6f;
            }
        }
        if (!lean) c.cap6 = 233f + 1.5f * (float) n2(P3958, x / 9.0, z / 9.0);
    }

    /** Whether storey k works out halls at a column with this hall weight. */
    boolean hallsHere(int k, float wh) { return halls[k] || (k > 0 && wh > 0f); }

    boolean bedsHere(int k, float wb) { return beds[k] || (k > 0 && wb > 0f); }

    /** fields.zero_line_dist at a (warped) column: distance to the zero line of a 2D noise, first order. */
    static double zld(int[] p, double scale, double wx, double wz) {
        double e = 0.75;
        double v = n2(p, wx / scale, wz / scale);
        double gx = (n2(p, (wx + e) / scale, wz / scale) - n2(p, (wx - e) / scale, wz / scale)) / (2 * e);
        double gz = (n2(p, wx / scale, (wz + e) / scale) - n2(p, wx / scale, (wz - e) / scale)) / (2 * e);
        double g = Math.sqrt(gx * gx + gz * gz);
        return Math.abs(v) / Math.max(g, 0.3 / scale);
    }

    /** zld with the noise's own slope in place of central differences (the game's form: the same zero lines, cheaper). */
    static double zldA(int[] p, double scale, double wx, double wz, double[] tmp) {
        GardensNoise.n2d(p, wx / scale, wz / scale, tmp);
        double gx = tmp[1] / scale, gz = tmp[2] / scale;
        double g = Math.sqrt(gx * gx + gz * gz);
        return Math.abs(tmp[0]) / Math.max(g, 0.3 / scale);
    }

    /** The frame alone at a column (cheap enough for the sites' few points): every storey's base floor and weights. */
    Col frameAt(int wx, int wz) {
        Col c = new Col();
        column(wx, wz, c, 0);
        return c;
    }

    // ============================================================================================ cells

    /** A vent hall: a hall lifted round a spot of the room noise on hot ground, where a stack of vents may stand. */
    record VHall(int k, double x, double z, double h0, double u, int i, int kk) {
        long key() { return cellKey(k, i, kk); }
    }

    /** A cell's key: its storey and indices packed, then mixed (a bijection) so hash tables spread them evenly. */
    static long cellKey(int k, int i, int kk) { return mix64(((long) k << 56) ^ ((long) (i & 0xFFFFFFF) << 28) ^ (kk & 0xFFFFFFFL)); }

    /** splitmix64's finaliser: a bijection on longs that scatters every bit over the whole word. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private final Map<Long, Object> vhallCand = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    /** Cells of the vent halls on storey k: size and offset. */
    static final double VH_G = 64.0;

    static double vhSx0(int k) { return 29.0 * k; }

    static double vhSz0(int k) { return -41.0 * k; }

    /** The vent hall candidate of a cell (gen3.build2d), or null: slid to the room noise's peak, on hot ground, away from the gates. */
    VHall vhallCandidate(int k, int i, int kk) {
        long key = cellKey(k, i, kk);
        Object o = vhallCand.get(key);
        if (o == null) {
            VHall v = computeVHall(k, i, kk);
            o = v == null ? NONE : v;
            vhallCand.putIfAbsent(key, o);
        }
        return o == NONE ? null : (VHall) o;
    }

    private VHall computeVHall(int k, int i, int kk) {
        double h0 = hv(seed, 1, 0, k, 0, i, kk);
        double u1 = hv(seed, 1, 1, k, 0, i, kk), u2 = hv(seed, 1, 2, k, 0, i, kk), u = hv(seed, 1, 3, k, 0, i, kk);
        double G = VH_G, sx0 = vhSx0(k), sz0 = vhSz0(k);
        double cx0 = sx0 + (i + 0.15 + 0.7 * u1) * G, cz0 = sz0 + (kk + 0.15 + 0.7 * u2) * G;
        Col c = new Col();
        column(clampX(floorI(cx0)), clampZ(floorI(cz0)), c, 0);
        if (h0 > 0.8 * (double) rampF(c.hl, -0.15, 0.25)) return null;
        float best = -9f;
        double cx = cx0, cz = cz0;
        int bit = 1 << k;
        for (int a = -4; a <= 4; a++)
            for (int b = -4; b <= 4; b++) {
                double x = cx0 + 5 * a, z = cz0 + 5 * b;
                if (patch && !inPatch(x, z)) continue;
                column(floorI(x), floorI(z), c, bit);
                if (c.m0[k] > best) {
                    best = c.m0[k];
                    cx = x;
                    cz = z;
                }
            }
        column(clampX(floorI(cx)), clampZ(floorI(cz)), c, 0);
        if (best < -0.05f || c.wh[k] < 0.5f || !hallsHere(k, c.wh[k])) return null;
        if (gateDist(cx, cz) < 60) return null;
        return new VHall(k, cx, cz, h0, u, i, kk);
    }

    /** gen3: a candidate is kept unless another within 30 has a lower h0. */
    boolean vhallKept(VHall v) {
        for (int di = -1; di <= 1; di++)
            for (int dk = -1; dk <= 1; dk++) {
                if (di == 0 && dk == 0) continue;
                VHall q = vhallCandidate(v.k, v.i + di, v.kk + dk);
                if (q != null && Math.hypot(v.x - q.x, v.z - q.z) < 30 && q.h0 < v.h0) return false;
            }
        return true;
    }

    /** Every kept vent hall of storey k whose cell could put it within {@code pad} of the box. */
    List<VHall> vhallsNear(int k, double minX, double minZ, double maxX, double maxZ, double pad) {
        List<VHall> out = new ArrayList<>();
        if (k == 0 && !halls[0]) return out;
        double G = VH_G, sx0 = vhSx0(k), sz0 = vhSz0(k);
        int i0 = (int) Math.floor((minX - pad - sx0) / G) - 1, i1 = (int) Math.floor((maxX + pad - sx0) / G) + 1;
        int k0 = (int) Math.floor((minZ - pad - sz0) / G) - 1, k1 = (int) Math.floor((maxZ + pad - sz0) / G) + 1;
        for (int i = i0; i <= i1; i++)
            for (int kk = k0; kk <= k1; kk++) {
                VHall v = vhallCandidate(k, i, kk);
                if (v == null) continue;
                if (v.x < minX - pad || v.x > maxX + pad || v.z < minZ - pad || v.z > maxZ + pad) continue;
                if (vhallKept(v)) out.add(v);
            }
        return out;
    }

    /** A dome site (gen3.build2d's cells of 38, round 1's hashes), or a deep site's dome on storey 0. */
    record Dome(int k, double x, double z, double r, int i, int kk, double hc, boolean deep) {}

    private final Map<Long, Object> domeCells = new ConcurrentHashMap<>();
    static final double DOME_G = 38.0;

    static double dmSx0(int k) { return 37.0 * k; }

    static double dmSz0(int k) { return -23.0 * k; }

    Dome domeSite(int k, int i, int kk) {
        long key = cellKey(k, i, kk);
        Object o = domeCells.get(key);
        if (o == null) {
            Dome d = computeDome(k, i, kk);
            o = d == null ? NONE : d;
            domeCells.putIfAbsent(key, o);
        }
        return o == NONE ? null : (Dome) o;
    }

    private Dome computeDome(int k, int i, int kk) {
        double G = DOME_G, sx0 = dmSx0(k), sz0 = dmSz0(k);
        long s = seed + 1150L + k * 131L;
        double h0 = hash2(s, i * 7919L, kk * 104729L);
        double cxw = sx0 + (i + 0.5) * G, czw = sz0 + (kk + 0.5) * G;
        double dens = 0.55 + 0.35 * n2(PDD[k], cxw / 160.0, czw / 160.0);
        if (h0 > dens) return null;
        double u1 = hash2(s + 1, i * 7919L, kk * 104729L), u2 = hash2(s + 2, i * 7919L, kk * 104729L), u3 = hash2(s + 3, i * 7919L, kk * 104729L);
        double cx = sx0 + (i + 0.15 + 0.7 * u1) * G, cz = sz0 + (kk + 0.15 + 0.7 * u2) * G;
        double r = Math.min(27.0, 7.5 * Math.pow(1.0 - u3 * 0.999, -1.0 / 1.7));
        if (!domes[k]) return null;
        Col c = frameAt(clampX(floorI(cx)), clampZ(floorI(cz)));
        if (c.wd[k] < 0.5f) return null;
        if (patch && !(cx - px0 >= -r && cx - px0 < psx + r && cz - pz0 >= -r && cz - pz0 < psz + r)) return null;
        double tt = Math.max(0, Math.min(1, (r - 12) / 4)), hf = 0.95 + 0.15 * tt * tt * (3 - 2 * tt);
        return new Dome(k, cx, cz, r, i, kk, Math.min(hf * r, DOME_CAP), false);
    }

    /** Every dome of storey k whose disc could reach the box (deep sites' domes included on storey 0). */
    List<Dome> domesNear(int k, double minX, double minZ, double maxX, double maxZ, double pad) {
        List<Dome> out = new ArrayList<>();
        double G = DOME_G, sx0 = dmSx0(k), sz0 = dmSz0(k), reach = pad + 33;
        int i0 = (int) Math.floor((minX - reach - sx0) / G) - 1, i1 = (int) Math.floor((maxX + reach - sx0) / G) + 1;
        int k0 = (int) Math.floor((minZ - reach - sz0) / G) - 1, k1 = (int) Math.floor((maxZ + reach - sz0) / G) + 1;
        for (int i = i0; i <= i1; i++)
            for (int kk = k0; kk <= k1; kk++) {
                Dome d = domeSite(k, i, kk);
                if (d == null) continue;
                double rr = d.r * 1.2 + pad;
                if (d.x + rr < minX || d.x - rr > maxX || d.z + rr < minZ || d.z - rr > maxZ) continue;
                out.add(d);
            }
        if (k == 0 && domes[0]) {
            for (DeepSite d : deepSitesNear(minX, minZ, maxX, maxZ, pad + 33)) {
                double rr = d.rd * 1.2 + pad;
                if (d.x + rr < minX || d.x - rr > maxX || d.z + rr < minZ || d.z - rr > maxZ) continue;
                out.add(new Dome(0, d.x, d.z, d.rd, d.i, d.kk, d.hd, true));
            }
        }
        return out;
    }

    /** A deep site (gen3.deep_sites): low, flat storey-0 floor with a tall gap over it, far from the gates. */
    record DeepSite(double x, double z, double h0, double R, double Rf, double rd, double f0, double fmax, double fmin,
                    double gap, double hd, int i, int kk, int hand, double a0, int gStand) {}

    private final Map<Long, Object> deepCells = new ConcurrentHashMap<>();
    static final double DEEP_G = 150.0;

    DeepSite deepCandidate(int i, int kk) {
        long key = cellKey(0, i, kk);
        Object o = deepCells.get(key);
        if (o == null) {
            DeepSite d = computeDeep(i, kk);
            o = d == null ? NONE : d;
            deepCells.putIfAbsent(key, o);
        }
        return o == NONE ? null : (DeepSite) o;
    }

    private DeepSite computeDeep(int i, int kk) {
        double Gd = DEEP_G;
        double h0 = hash2(seed + 3700L, i, kk);
        double u1 = hash2(seed + 3701L, i, kk), u2 = hash2(seed + 3702L, i, kk);
        double cx = (i + 0.2 + 0.6 * u1) * Gd, cz = (kk + 0.2 + 0.6 * u2) * Gd;
        if (patch && !(cx - px0 >= 40 && cx - px0 < psx - 40 && cz - pz0 >= 40 && cz - pz0 < psz - 40)) return null;
        if (h0 >= 0.7) return null;
        double R = 10 + 1.5 * hash2(seed + 3706L, i, kk), Rf = 1.45 * R;
        double[][] pts = sitePoints(cx, cz, Rf);
        double fmax = -1e9, fmin = 1e9;
        for (double[] p : pts) {
            Col c = frameAt(clampX(floorI(p[0])), clampZ(floorI(p[1])));
            fmax = Math.max(fmax, c.f[0]);
            fmin = Math.min(fmin, c.f[0]);
        }
        Col cc = frameAt(clampX(floorI(cx)), clampZ(floorI(cz)));
        double gap = (double) (cc.f[1] - cc.f[0]);
        double dX = 1e9, dE = 1e9;
        for (GardensGates.Gate g : gates.near(cx, cz, 120)) {
            double d = Math.hypot(cx - g.x(), cz - g.z());
            if (g.entry()) dE = Math.min(dE, d);
            else dX = Math.min(dX, d);
        }
        if (cc.f[0] <= 40 && fmax - fmin <= 7 && gap >= 36 && dX >= 80 && dE >= 60) {
            double rd = 24 + 3 * hash2(seed + 3703L, i, kk);
            return new DeepSite(cx, cz, h0, R, Rf, rd, cc.f[0], fmax, fmin, gap, Math.min(Math.min(1.3 * rd, 40.0), gap - 8.0), i, kk,
                    hash2(seed + 3704L, i, kk) < 0.5 ? 1 : -1, 2 * Math.PI * hash2(seed + 3705L, i, kk),
                    -22 - (int) (8 * hash2(seed + 3707L, i, kk)));
        }
        return null;
    }

    /** gen3.deep_sites' keep: no other within 80 with a lower h0. */
    boolean deepKept(DeepSite s) {
        for (int di = -1; di <= 1; di++)
            for (int dk = -1; dk <= 1; dk++) {
                if (di == 0 && dk == 0) continue;
                DeepSite q = deepCandidate(s.i + di, s.kk + dk);
                if (q != null && Math.hypot(s.x - q.x, s.z - q.z) < 80 && q.h0 < s.h0) return false;
            }
        return true;
    }

    List<DeepSite> deepSitesNear(double minX, double minZ, double maxX, double maxZ, double pad) {
        List<DeepSite> out = new ArrayList<>();
        int i0 = (int) Math.floor((minX - pad) / DEEP_G) - 1, i1 = (int) Math.floor((maxX + pad) / DEEP_G) + 1;
        int k0 = (int) Math.floor((minZ - pad) / DEEP_G) - 1, k1 = (int) Math.floor((maxZ + pad) / DEEP_G) + 1;
        for (int i = i0; i <= i1; i++)
            for (int kk = k0; kk <= k1; kk++) {
                DeepSite d = deepCandidate(i, kk);
                if (d == null) continue;
                if (d.x < minX - pad || d.x > maxX + pad || d.z < minZ - pad || d.z > maxZ + pad) continue;
                if (deepKept(d)) out.add(d);
            }
        return out;
    }

    /** The 17 points of a site's floor test: the centre, 8 at half the radius, 8 just past it (rotated a sixteenth). */
    static double[][] sitePoints(double cx, double cz, double Rf) {
        double[][] p = new double[17][];
        p[0] = new double[]{cx, cz};
        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4;
            p[1 + a] = new double[]{cx + Rf / 2 * Math.cos(ang), cz + Rf / 2 * Math.sin(ang)};
        }
        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4 + Math.PI / 8;
            p[9 + a] = new double[]{cx + (Rf + 2) * Math.cos(ang), cz + (Rf + 2) * Math.sin(ang)};
        }
        return p;
    }

    // ============================================================================================ gate plumbing

    private record GateKey(double x, double z, boolean entry, int k, boolean second) {}

    private final Map<GateKey, GardensStoreys.GateWays> gateWays = new ConcurrentHashMap<>();

    /** The plumbing round a gate on its storey (k 0 for the way down, 6 for the way in), per pass; cached. */
    GardensStoreys.GateWays gateWays(GardensGates.Gate g, int k, java.util.function.Predicate<VHall> use) {
        GateKey key = new GateKey(g.x(), g.z(), g.entry(), k, use != null);
        GardensStoreys.GateWays gw = gateWays.get(key);
        if (gw == null) {
            gw = GardensStoreys.gateWays(this, g, k, use);
            GardensStoreys.GateWays prev = gateWays.putIfAbsent(key, gw);
            if (prev != null) gw = prev;
        }
        return gw;
    }

    // ============================================================================================ helpers

    /**
     * A column's offset from a point along one axis in float32, as numpy takes {@code Xg - cx}: both relative to the
     * patch's corner in the preview's rules, else to the point's own block (so the result never depends on a window).
     */
    float rel(int w, double c, boolean isX) {
        double o = patch ? (isX ? px0 : pz0) : Math.floor(c);
        return (float) (w - o) - (float) (c - o);
    }

    /** numpy's float32 {@code hypot(Xg - cx, Zg - cz)} at column (wx, wz). */
    float dist(int wx, int wz, double cx, double cz) {
        float dx = rel(wx, cx, true), dz = rel(wz, cz, false);
        return (float) Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    double gateDist(double x, double z) {
        double d = 1e9;
        for (GardensGates.Gate g : gates.near(x, z, 200)) d = Math.min(d, Math.hypot(x - g.x(), z - g.z()));
        return d;
    }

    static int floorI(double v) { return (int) Math.floor(v); }

    boolean inPatch(double x, double z) { return x >= px0 && x < px0 + psx && z >= pz0 && z < pz0 + psz; }

    /** In the patch rules, a column clipped into the patch; otherwise as it is. */
    int clampX(int x) { return patch ? Math.max(px0, Math.min(px0 + psx - 1, x)) : x; }

    int clampZ(int z) { return patch ? Math.max(pz0, Math.min(pz0 + psz - 1, z)) : z; }

    /** Heat by depth (gen3.g_of): +0.34 at row 20, +0.04 at 120, -0.34 at 206, flat beyond. */
    static double gOf(double y) {
        // as numpy's interp takes it: slope * (y - x_j) + y_j, the end values outside
        if (y < 20) return 0.34;
        if (y < 120) return (0.04 - 0.34) / (120.0 - 20.0) * (y - 20.0) + 0.34;
        if (y < 206) return (-0.34 - 0.04) / (206.0 - 120.0) * (y - 120.0) + 0.04;
        return -0.34;
    }
}
