package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.explorationremix.world.terrain.GardensMath.clipF;
import static com.explorationremix.world.terrain.GardensMath.pull;
import static com.explorationremix.world.terrain.GardensMath.rampF;
import static com.explorationremix.world.terrain.GardensPlan.CEIL_MAX;
import static com.explorationremix.world.terrain.GardensPlan.CUP_GAP;
import static com.explorationremix.world.terrain.GardensPlan.GATE_R;
import static com.explorationremix.world.terrain.GardensPlan.HALL_CAP;
import static com.explorationremix.world.terrain.GardensPlan.K;
import static com.explorationremix.world.terrain.GardensPlan.RV0;
import static com.explorationremix.world.terrain.GardensPlan.SEAL;

/**
 * Every storey's 2D fields over a window of columns (the previews' gen3.build2d): each storey's floor and ceiling, its
 * footprint, and the style fields the vents and the dressing read. The halls' heights come from a distance transform
 * of their footprint, so a column's values depend on the columns round it: a window is exact over its middle, a margin
 * in from its edges (the caller keeps a margin as wide as {@code edtCap} plus the blur's reach).
 *
 * <p>Arrays are column-major over the window: index {@code i * nz + j} for column (x0 + i, z0 + j).
 */
final class GardensStoreys {
    final GardensPlan plan;
    final int x0, z0, nx, nz, n;

    /** Per column: heat, the vent mounds, the low border. */
    final float[] hot, mound, lb;
    /** Per storey and column. */
    final float[][] f = new float[K][], floor = new float[K][], ceil = new float[K][], wd = new float[K][],
            wh = new float[K][], wb = new float[K][], m0 = new float[K][], mb = new float[K][], dh = new float[K][],
            hbed = new float[K][], bump = new float[K][], oc = new float[K][];
    final boolean[][] foot = new boolean[K][], rooms = new boolean[K][], sheet = new boolean[K][],
            hall = new boolean[K][], tun = new boolean[K][];
    /** The vent halls that lift each storey here, and its domes. */
    final List<List<GardensPlan.VHall>> vhalls = new ArrayList<>();
    final List<List<GardensPlan.Dome>> domes = new ArrayList<>();
    /** What the gate plumbing did on each storey here (for the checks). */
    final List<GateWays> gateWays = new ArrayList<>();
    /** A list of world columns instead of a window (the game's lookups at a few columns), or null. */
    final int[] xs, zs;

    /**
     * The game's distance fields of the halls' and beds' footprints, worked out once per tile from a margin round it
     * (so they never depend on the window asking): capped and blurred as a window's own would be, far enough in.
     */
    interface Edt {
        float hall(int k, int x, int z);

        float sheet(int k, int x, int z);
    }

    /**
     * @param use     which vent halls lift their storey (null: every kept one, the first pass)
     * @param edtCap  the halls' distance transform is cut at this (the window's margin must cover it)
     * @param openOnly stop once the open footprint is known (no heights; for the gate plumbing's own windows)
     */
    GardensStoreys(GardensPlan plan, int x0, int z0, int nx, int nz, Predicate<GardensPlan.VHall> use, float edtCap,
                   boolean openOnly) {
        this(plan, x0, z0, nx, nz, null, null, use, edtCap, openOnly, null);
    }

    /** The game's window: the distance fields come from {@code edt}, so the window needs no margin for them. */
    GardensStoreys(GardensPlan plan, int x0, int z0, int nx, int nz, Predicate<GardensPlan.VHall> use, Edt edt) {
        this(plan, x0, z0, nx, nz, null, null, use, 40f, false, edt);
    }

    /** The game's lookups at a list of columns (any order, any spread), with the distance fields from {@code edt}. */
    GardensStoreys(GardensPlan plan, int[] xs, int[] zs, Predicate<GardensPlan.VHall> use, Edt edt) {
        this(plan, minOf(xs), minOf(zs), xs.length, 1, xs, zs, use, 40f, false, edt);
    }

    private static int minOf(int[] a) {
        int m = Integer.MAX_VALUE;
        for (int v : a) m = Math.min(m, v);
        return m;
    }

    private static int maxOf(int[] a) {
        int m = Integer.MIN_VALUE;
        for (int v : a) m = Math.max(m, v);
        return m;
    }

    /** Column q's world x and z. */
    int X(int q) { return xs != null ? xs[q] : x0 + q / nz; }

    int Z(int q) { return zs != null ? zs[q] : z0 + q % nz; }

    /** Runs {@code body} on every column in the box xa..xb, za..zb (inclusive) that this window or list holds. */
    private void forBox(int xa, int xb, int za, int zb, java.util.function.IntConsumer body) {
        if (xs == null) {
            int ia = Math.max(0, xa - x0), ib = Math.min(nx - 1, xb - x0);
            int ja = Math.max(0, za - z0), jb = Math.min(nz - 1, zb - z0);
            for (int i = ia; i <= ib; i++)
                for (int j = ja; j <= jb; j++) body.accept(i * nz + j);
            return;
        }
        for (int q = 0; q < n; q++) {
            int x = xs[q], z = zs[q];
            if (x >= xa && x <= xb && z >= za && z <= zb) body.accept(q);
        }
    }

    private java.util.Map<Long, Integer> colIndex;

    /** The list's index of a world column, or -1. */
    private int listIndex(int x, int z) {
        if (colIndex == null) {
            colIndex = new java.util.HashMap<>();
            for (int q = 0; q < n; q++) colIndex.putIfAbsent(((long) xs[q] << 32) | (zs[q] & 0xFFFFFFFFL), q);
        }
        Integer q = colIndex.get(((long) x << 32) | (z & 0xFFFFFFFFL));
        return q == null ? -1 : q;
    }

    private GardensStoreys(GardensPlan plan, int x0, int z0, int nx, int nz, int[] xs, int[] zs,
                           Predicate<GardensPlan.VHall> use, float edtCap, boolean openOnly, Edt edtSrc) {
        this.plan = plan;
        this.x0 = x0;
        this.z0 = z0;
        this.nx = nx;
        this.nz = nz;
        this.n = nx * nz;
        this.xs = xs;
        this.zs = zs;
        hot = new float[n];
        mound = new float[n];
        lb = new float[n];
        float[] big = new float[n], cap6 = new float[n];
        float[][] td = new float[K][n], tw = new float[K][n], cv = new float[K][n], pil = new float[K][n],
                col = new float[K][n], hn = new float[K][n];
        for (int k = 0; k < K; k++) {
            f[k] = new float[n];
            wd[k] = new float[n];
            wh[k] = new float[n];
            wb[k] = new float[n];
            m0[k] = new float[n];
            mb[k] = new float[n];
        }
        GardensPlan.Col c = new GardensPlan.Col();
        int all = (1 << K) - 1;
        for (int q = 0; q < n; q++) {
            plan.column(X(q), Z(q), c, all);
            {
                hot[q] = c.hot;
                mound[q] = c.mound;
                lb[q] = c.lb;
                big[q] = c.big;
                cap6[q] = c.cap6;
                for (int k = 0; k < K; k++) {
                    f[k][q] = c.f[k];
                    wd[k][q] = c.wd[k];
                    wh[k][q] = c.wh[k];
                    wb[k][q] = c.wb[k];
                    td[k][q] = c.td[k];
                    tw[k][q] = c.tw[k];
                    m0[k][q] = c.m0[k];
                    cv[k][q] = c.cv[k];
                    mb[k][q] = c.mb[k];
                    pil[k][q] = c.pil[k];
                    col[k][q] = c.col[k];
                    hn[k][q] = c.hn[k];
                }
            }
        }
        double minX = x0, minZ = z0, maxX = xs != null ? maxOf(xs) : x0 + nx - 1, maxZ = zs != null ? maxOf(zs) : z0 + nz - 1;
        for (int k = 0; k < K; k++) {
            float[] fk = f[k];
            // tunnels, shared by every style
            boolean[] tunK = new boolean[n];
            float[] htun = new float[n];
            for (int q = 0; q < n; q++) {
                tunK[q] = td[k][q] < tw[k][q];
                htun[q] = 4f + 1.4f * clipF(1f - td[k][q] / tw[k][q], 0f, 1f);
            }
            // halls, lifted round the vent halls
            boolean[] hallK = new boolean[n];
            float[] hh = new float[n], heaveH = new float[n], bumpK = new float[n];
            List<GardensPlan.VHall> vh = new ArrayList<>();
            for (GardensPlan.VHall v : plan.vhallsNear(k, minX, minZ, maxX, maxZ, RV0 + 6 + 1))
                if (use == null || use.test(v)) vh.add(v);
            vhalls.add(vh);
            float[] ucap = new float[n], cup = new float[n];
            for (GardensPlan.VHall v : vh) {
                double rv = RV0 + 6 * v.u();
                float rvF = (float) rv, uF = (float) v.u(), cupH = (float) (22 + 16 * v.u());
                forBox((int) Math.floor(v.x() - rv - 1), (int) Math.ceil(v.x() + rv + 1),
                        (int) Math.floor(v.z() - rv - 1), (int) Math.ceil(v.z() + rv + 1), q -> {
                            float dd = plan.dist(X(q), Z(q), v.x(), v.z()) / rvF;
                            float b = rampF(1f - dd, 0, 1);
                            if (b > bumpK[q]) bumpK[q] = b;
                            float ub = uF * b;
                            if (ub > ucap[q]) ucap[q] = ub;
                            float cu = dd < 1f ? cupH * (float) Math.sqrt(clipF(1f - dd * dd, 0f, 1f)) : 0f;
                            if (cu > cup[q]) cup[q] = cu;
                        });
            }
            boolean anyHall = false;
            for (int q = 0; q < n; q++) {
                if (k < K - 1) cup[q] = Math.min(cup[q], Math.max(f[k + 1][q] - fk[q] - (float) CUP_GAP, 0f));
                float m = m0[k][q] + 0.35f * bumpK[q];
                hallK[q] = m > 0f;
                anyHall |= hallK[q];
            }
            bump[k] = bumpK;
            if (anyHall && !openOnly) {
                float[] edt;
                if (edtSrc != null) {
                    edt = new float[n];
                    for (int q = 0; q < n; q++) if (hallK[q]) edt[q] = edtSrc.hall(k, X(q), Z(q));
                } else {
                    boolean[] fh = new boolean[n];
                    for (int q = 0; q < n; q++) fh[q] = hallK[q] || tunK[q];
                    edt = GardensMath.gaussian(GardensMath.edt(fh, nx, nz, edtCap), nx, nz, 1.5);
                }
                for (int q = 0; q < n; q++) {
                    if (!hallK[q]) continue;
                    float e = edt[q];
                    float a = Math.min(Math.max(12f + 26f * big[q], 20f + 20f * ucap[q]),
                            (2.4f + 1.15f * (float) Math.pow(Math.max(e, 0f), (double) 0.92f)) * cv[k][q]);
                    float h = Math.max(a, Math.min(cup[q], 2.4f + 2.2f * e));
                    h = Math.min(h, (float) HALL_CAP);
                    hh[q] = h;
                    heaveH[q] = 4f * mound[q] * clipF((h - 9f) / 10f, 0f, 1f) * (1f - bumpK[q]);
                }
            }
            // domes, cut to a cave's shape by the storey's noise (GardensPlan.domeAt), worked out where they reach
            float[] dhK = new float[n], ocK = new float[n];
            List<GardensPlan.Dome> ds = plan.domesNear(k, minX, minZ, maxX, maxZ, 0);
            domes.add(ds);
            DomeCols dc = new DomeCols(k);
            for (GardensPlan.Dome d : ds) {
                double b = GardensPlan.domeReach(d.r());
                float[] o1 = new float[1];
                forBox((int) Math.floor(d.x() - b), (int) Math.floor(d.x() + b) + 1,
                        (int) Math.floor(d.z() - b), (int) Math.floor(d.z() + b) + 1, q -> {
                            int o = dc.at(q);
                            float h = GardensPlan.domeAt(d, X(q), Z(q), dc.noise, o, dc.cap[q], o1);
                            if (o1[0] > ocK[q]) ocK[q] = o1[0];
                            if (h > 0f) dhK[q] = dhK[q] > 0f ? (float) GardensPlan.smax(dhK[q], h, GardensPlan.KS) : h;
                        });
            }
            if (k == 0 && plan.domes[0]) {
                // the lower gate's own dome, ringed by rock from GATE_R + 2 out past the plumbing's ring (GW_R)
                for (GardensGates.Gate g : plan.gates.near(minX + (maxX - minX) / 2, minZ + (maxZ - minZ) / 2,
                        Math.hypot(maxX - minX, maxZ - minZ) / 2 + GATE_BOX + 2)) {
                    if (g.entry()) continue;
                    forBox((int) Math.floor(g.x() - GATE_BOX), (int) Math.ceil(g.x() + GATE_BOX), (int) Math.floor(g.z() - GATE_BOX),
                            (int) Math.ceil(g.z() + GATE_BOX), q -> {
                                int wx = X(q), wz = Z(q);
                                float ringX = pull(wx, wz, g.x(), g.z(), RING_A, RING_B) - pull(wx, wz, g.x(), g.z(), GATE_R, GATE_R + 4.0);
                                if (ringX > 0.5f) {
                                    dhK[q] = 0f;
                                    return;
                                }
                                int o = dc.at(q);
                                float gd = GardensPlan.gateDomeAt(g, wx, wz, dc.noise, o);
                                if (gd > 0f) dhK[q] = dhK[q] > 0f ? (float) GardensPlan.smax(dhK[q], gd, GardensPlan.KS) : gd;
                            });
                }
            }
            oc[k] = ocK;
            // beds
            boolean[] sheetK = new boolean[n];
            float[] hbedK = new float[n];
            boolean anySheet = false;
            for (int q = 0; q < n; q++) {
                sheetK[q] = mb[k][q] > 0f && (pil[k][q] < 0.42f || col[k][q] > 0.35f);
                anySheet |= sheetK[q];
            }
            if (anySheet && !openOnly) {
                float[] edtb;
                if (edtSrc != null) {
                    edtb = new float[n];
                    for (int q = 0; q < n; q++) if (sheetK[q]) edtb[q] = edtSrc.sheet(k, X(q), Z(q));
                } else edtb = GardensMath.gaussian(GardensMath.edt(sheetK, nx, nz, 40f), nx, nz, 1.2);
                for (int q = 0; q < n; q++) {
                    if (!sheetK[q]) continue;
                    float h = Math.min(hn[k][q], 1.5f + 1.3f * edtb[q]);
                    if (k < K - 1 && col[k][q] > 0f) {
                        float gap = Math.max(f[k + 1][q] - fk[q], 0f);
                        h = Math.max(h, Math.min(Math.min(col[k][q] * (gap + 0.6f * hn[k][q]), 30f), 2f * edtb[q] + 4f));
                    }
                    hbedK[q] = h;
                }
            } else if (anySheet) {
                for (int q = 0; q < n; q++) if (sheetK[q]) hbedK[q] = 1.5f;
            }
            // the gates' plumbing: at most three ways cross a gate's ring
            if (k == 0 || k == K - 1) {
                boolean[] open = new boolean[n];
                for (int q = 0; q < n; q++) open[q] = hallK[q] || dhK[q] > 2.5f || sheetK[q] || tunK[q];
                if (openOnly) {
                    // the caller works the plumbing out on this window itself
                } else {
                    // as far as a lifeline can run: to the corner of the plumbing's 84-block window (the plugs stay
                    // within GW_PLUG_OUT), so a single column and a window round it answer alike
                    for (GardensGates.Gate g : plan.gates.near(minX + (maxX - minX) / 2, minZ + (maxZ - minZ) / 2,
                            Math.hypot(maxX - minX, maxZ - minZ) / 2 + LIFE_REACH)) {
                        if (g.entry() != (k == K - 1)) continue;
                        GateWays gw = plan.gateWays(g, k, use);
                        if (gw == null) continue;
                        boolean touched = false;
                        if (gw.lifeline != null) {
                            // a tunnel joins a gate's room that no way leaves to the caves outside its ring
                            double[] l = gw.lifeline;
                            int pad = (int) Math.ceil(LIFE_W) + 1;
                            int[] hit = {0};
                            forBox((int) Math.floor(Math.min(l[0], l[2])) - pad, (int) Math.ceil(Math.max(l[0], l[2])) + pad,
                                    (int) Math.floor(Math.min(l[1], l[3])) - pad, (int) Math.ceil(Math.max(l[1], l[3])) + pad, q -> {
                                        double d = lifeDist(l, X(q), Z(q));
                                        if (d >= LIFE_W) return;
                                        tunK[q] = true;
                                        htun[q] = Math.max(htun[q], 4f + 1.4f * (float) (1.0 - d / LIFE_W));
                                        hit[0]++;
                                    });
                            touched = hit[0] > 0;
                        }
                        for (long cell : gw.plugged) {
                            int q;
                            if (xs != null) {
                                q = listIndex((int) (cell >> 32), (int) cell);
                                if (q < 0) continue;
                            } else {
                                int i = (int) (cell >> 32) - x0, j = (int) cell - z0;
                                if (i < 0 || j < 0 || i >= nx || j >= nz) continue;
                                q = i * nz + j;
                            }
                            hallK[q] = false;
                            tunK[q] = false;
                            sheetK[q] = false;
                            dhK[q] = 0f;
                            touched = true;
                        }
                        if (xs != null && touched) {
                            // a list of columns can hold the same column twice
                            for (int q = 0; q < n; q++) {
                                int qq = listIndex(xs[q], zs[q]);
                                if (qq == q) continue;
                                hallK[q] = hallK[qq];
                                tunK[q] = tunK[qq];
                                htun[q] = htun[qq];
                                sheetK[q] = sheetK[qq];
                                dhK[q] = dhK[qq];
                            }
                        }
                        if (touched || (xs == null && gw.inside(x0, z0, nx, nz))) gateWays.add(gw);
                    }
                }
            }
            // footprint, floor and ceiling
            boolean[] footK = new boolean[n], roomsK = new boolean[n];
            float[] fl = new float[n], ce = new float[n];
            for (int q = 0; q < n; q++) {
                boolean rm = hallK[q] || dhK[q] > 2.5f || (sheetK[q] && hbedK[q] > 1f);
                roomsK[q] = rm;
                footK[q] = rm || tunK[q];
                float hk = Math.max(Math.max(hallK[q] ? hh[q] : 0f, dhK[q]), Math.max(hbedK[q], tunK[q] ? htun[q] : 0f));
                float heaveB = 5f * mound[q] * wb[k][q];
                float flo = Math.max(fk[q] + wh[k][q] * heaveH[q] + heaveB, SEAL + 1f);
                float cei = fk[q] + hk + heaveB;
                if (k == K - 1) cei = smin(cei, cap6[q], 2f);
                cei = footK[q] ? Math.min(cei, CEIL_MAX) : flo - 5f;
                fl[q] = flo;
                ce[q] = cei;
            }
            floor[k] = fl;
            ceil[k] = ce;
            foot[k] = footK;
            rooms[k] = roomsK;
            sheet[k] = sheetK;
            hall[k] = hallK;
            tun[k] = tunK;
            dh[k] = dhK;
            hbed[k] = hbedK;
        }
    }

    /** gen3.smin in float32: a soft minimum, rounded over k. */
    static float smin(float a, float b, float k) {
        float m = Math.min(a, b);
        return m - k * (float) Math.log((float) Math.exp(-(a - m) / k) + (float) Math.exp(-(b - m) / k));
    }

    /**
     * The ring of rock round the lower gate's dome: from GATE_R + 2 out to where {@code pull(RING_A, RING_B)} falls to a
     * half (39), past the plumbing's ring ({@link #GW_R} and its plugs); the dome loop's box round the gate.
     */
    static final double RING_A = 32, RING_B = 46, GATE_BOX = 48;

    /** A storey's dome noise over this window's columns, worked out on first use (only where a dome reaches), and its caps. */
    private final class DomeCols {
        final int k;
        float[] noise, cap;
        private boolean[] done;

        DomeCols(int k) { this.k = k; }

        /** Column q's offset into {@link #noise}, its noise and cap worked out on first use. */
        int at(int q) {
            if (noise == null) {
                noise = new float[n * GardensPlan.DN];
                cap = new float[n];
                done = new boolean[n];
            }
            if (!done[q]) {
                plan.domeNoise(k, X(q), Z(q), noise, q * GardensPlan.DN);
                cap[q] = GardensPlan.domeCap(f[k][q], k < K - 1 ? f[k + 1][q] : CEIL_MAX);
                done[q] = true;
            }
            return q * GardensPlan.DN;
        }
    }

    int idx(int wx, int wz) { return (wx - x0) * nz + (wz - z0); }

    boolean contains(int wx, int wz) { return wx >= x0 && wz >= z0 && wx < x0 + nx && wz < z0 + nz; }

    /** The walls' roughness of the domes (round 3: 2.6, was 1.3, so the carve's noise makes ledges and overhangs). */
    static final float ROUGH_DOMES = 2.6f;

    /** The roughness each storey's walls take: each column its own style's. */
    float rough(int k, int q) { return 2.4f * wh[k][q] + ROUGH_DOMES * wd[k][q] + 1.1f * wb[k][q]; }

    /** The storey's band at a column: 0 domes (bottom), 1 halls (middle), 2 beds (top); ties to the lower. */
    int dominant(int k, int q) {
        float a = wd[k][q], b = wh[k][q], c = wb[k][q];
        int best = 0;
        float bv = a;
        if (b > bv) { best = 1; bv = b; }
        if (c > bv) best = 2;
        return best;
    }

    /** gen3.carve's oil levels: each dip's spill level, at most its style's depth over the floor; edges drain. */
    float[][] pools() {
        float[][] out = new float[K][];
        for (int k = 0; k < K; k++) {
            float[] fl = new float[n];
            boolean[] ft = new boolean[n];
            for (int q = 0; q < n; q++) {
                fl[q] = (float) Math.floor(floor[k][q]);
                ft[q] = foot[k][q] && ceil[k][q] - floor[k][q] > 3f;
            }
            float[] spill = GardensMath.poolLevels(fl, ft, nx, nz, 99f);
            float[] p = new float[n];
            for (int q = 0; q < n; q++) {
                float depth = 1.2f * wd[k][q] + 1.6f * wh[k][q] + 2.5f * wb[k][q];
                p[q] = Math.min(spill[q], floor[k][q] + depth);
            }
            out[k] = p;
        }
        return out;
    }

    // ================================================================================================ gate plumbing

    /**
     * gen3.gate_ways' result for one gate on one storey: the crossings, the kept ones, the plugged columns; and, where no
     * way crosses the ring at all, the lifeline that joins the gate to the caves outside it.
     */
    static final class GateWays {
        final GardensGates.Gate gate;
        final int k;
        final double[] crossings, kept;
        final long[] plugged;
        /** A straight tunnel from the gate's own floor to the nearest open floor that leaves the ring ({x0, z0, x1, z1}), or null. */
        final double[] lifeline;

        GateWays(GardensGates.Gate gate, int k, double[] crossings, double[] kept, long[] plugged) {
            this(gate, k, crossings, kept, plugged, null);
        }

        GateWays(GardensGates.Gate gate, int k, double[] crossings, double[] kept, long[] plugged, double[] lifeline) {
            this.gate = gate;
            this.k = k;
            this.crossings = crossings;
            this.kept = kept;
            this.plugged = plugged;
            this.lifeline = lifeline;
        }

        boolean inside(int x0, int z0, int nx, int nz) {
            return gate.x() >= x0 && gate.x() < x0 + nx && gate.z() >= z0 && gate.z() < z0 + nz;
        }
    }

    static final double GW_R = 36.0, GW_PLUG_IN = 34.0, GW_PLUG_OUT = 38.0;
    static final int GW_KEEP = 3;

    /** The plumbing round gate g on storey k, from an open-only window round it. */
    static GateWays gateWays(GardensPlan plan, GardensGates.Gate g, int k, Predicate<GardensPlan.VHall> use) {
        int gx0 = (int) Math.floor(g.x()) - 41, gz0 = (int) Math.floor(g.z()) - 41, w = 84;
        GardensStoreys s = new GardensStoreys(plan, gx0, gz0, w, w, use, 0f, true);
        boolean[] open = new boolean[s.n];
        for (int q = 0; q < s.n; q++) open[q] = s.hall[k][q] || s.dh[k][q] > 2.5f || s.sheet[k][q] || s.tun[k][q];
        return ways(plan, open, gx0, gz0, w, w, g, k);
    }

    /** gate_ways on an open footprint over a window that holds the gate's whole ring. */
    static GateWays ways(GardensPlan plan, boolean[] open, int x0, int z0, int nx, int nz, GardensGates.Gate g, int k) {
        int n = nx * nz;
        float[] rr = new float[n], aa = new float[n];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j;
                float dx = plan.rel(x0 + i, g.x(), true), dz = plan.rel(z0 + j, g.z(), false);
                rr[q] = (float) Math.sqrt((double) dx * dx + (double) dz * dz);
                float a = (float) Math.atan2(dz, dx) * (180f / (float) Math.PI);
                a = a % 360f;
                if (a < 0f) a += 360f;
                aa[q] = a;
            }
        boolean[] inside = new boolean[n];
        for (int q = 0; q < n; q++) inside[q] = open[q] && rr[q] <= GW_R + 0.5;
        int[] lab = label8(inside, nx, nz);
        int nl = 0;
        for (int v : lab) nl = Math.max(nl, v);
        int[] cnt = new int[nl + 1];
        for (int q = 0; q < n; q++) if (rr[q] < 6f && lab[q] > 0) cnt[lab[q]]++;
        int core = 0;
        for (int l = 1; l <= nl; l++) if (cnt[l] > cnt[core]) core = l;
        if (core == 0) return new GateWays(g, k, new double[0], new double[0], new long[0], lifeline(open, lab, 0, rr, x0, z0, nx, nz, g));
        boolean[] band = new boolean[n];
        for (int q = 0; q < n; q++) band[q] = lab[q] == core && rr[q] >= GW_R - 1.0 && rr[q] <= GW_R + 0.5;
        int[] bl = label8(band, nx, nz);
        int nb = 0;
        for (int v : bl) nb = Math.max(nb, v);
        if (nb == 0) return new GateWays(g, k, new double[0], new double[0], new long[0], lifeline(open, lab, core, rr, x0, z0, nx, nz, g));
        double[] cr = new double[nb];
        float[] lo = new float[nb], hi = new float[nb];
        for (int b = 1; b <= nb; b++) {
            double ss = 0, cs = 0;
            int m = 0;
            float l = 1e9f, h = -1e9f;
            for (int q = 0; q < n; q++) {
                if (bl[q] != b) continue;
                double a = Math.toRadians(aa[q]);
                ss += Math.sin(a);
                cs += Math.cos(a);
                m++;
                l = Math.min(l, aa[q]);
                h = Math.max(h, aa[q]);
            }
            double ang = Math.toDegrees(Math.atan2(ss / m, cs / m)) % 360.0;
            if (ang < 0) ang += 360.0;
            cr[b - 1] = ang;
            lo[b - 1] = l;
            hi[b - 1] = h;
        }
        if (nb <= GW_KEEP) return new GateWays(g, k, cr, cr.clone(), new long[0]);
        // keep the three that spread best round the gate
        double best = -1;
        int[] bsub = null;
        for (int a = 0; a < nb; a++)
            for (int b = a + 1; b < nb; b++)
                for (int c = b + 1; c < nb; c++) {
                    double[] an = {cr[a], cr[b], cr[c]};
                    java.util.Arrays.sort(an);
                    double mg = Math.min(Math.min(an[1] - an[0], an[2] - an[1]), an[0] + 360.0 - an[2]);
                    if (mg > best + 1e-6) {
                        best = mg;
                        bsub = new int[]{a, b, c};
                    }
                }
        List<Long> plug = new ArrayList<>();
        boolean[] plugM = new boolean[n];
        for (int c = 0; c < nb; c++) {
            if (c == bsub[0] || c == bsub[1] || c == bsub[2]) continue;
            float l = lo[c], h = hi[c];
            boolean wrap = h - l > 180f;
            if (wrap) {
                l = 1e9f;
                h = -1e9f;
                for (int q = 0; q < n; q++) {
                    if (bl[q] != c + 1) continue;
                    float a2 = aa[q] > 180f ? aa[q] - 360f : aa[q];
                    l = Math.min(l, a2);
                    h = Math.max(h, a2);
                }
            }
            for (int q = 0; q < n; q++) {
                if (!open[q] || rr[q] < GW_PLUG_IN || rr[q] > GW_PLUG_OUT) continue;
                float da = wrap ? ((aa[q] + 180f) % 360f) - 180f : aa[q];
                if (da >= l - 4f && da <= h + 4f) plugM[q] = true;
            }
        }
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++)
                if (plugM[i * nz + j]) plug.add(((long) (x0 + i) << 32) | ((z0 + j) & 0xFFFFFFFFL));
        long[] pl = new long[plug.size()];
        for (int i = 0; i < pl.length; i++) pl[i] = plug.get(i);
        double[] kept = {cr[bsub[0]], cr[bsub[1]], cr[bsub[2]]};
        return new GateWays(g, k, cr, kept, pl);
    }

    /** The lifeline's half width: as wide as a storey's tunnels. */
    static final double LIFE_W = 2.6;
    /** How far from its gate a lifeline's columns can lie: the plumbing window's corner (42 * sqrt 2), its width, a column. */
    static final double LIFE_REACH = 64;

    /**
     * The game's lifeline for a gate whose room no way leaves (0.19.1; the previews' gates all had ways, so they never
     * take one): the footprint inside the ring is the gate's core ({@code lab == core} of the in-ring labels, or the
     * gate's own column when nothing is open by it), and the target is the nearest open column outside the core whose
     * own patch of the window's footprint runs out past the ring, so the tunnel ends in caves that go on. Null when
     * nothing open leaves the ring anywhere in the window.
     */
    static double[] lifeline(boolean[] open, int[] lab, int core, float[] rr, int x0, int z0, int nx, int nz, GardensGates.Gate g) {
        int n = nx * nz;
        int[] all = label8(open, nx, nz);
        int na = 0;
        for (int v : all) na = Math.max(na, v);
        float[] reach = new float[na + 1];
        for (int q = 0; q < n; q++) if (all[q] > 0) reach[all[q]] = Math.max(reach[all[q]], rr[q]);
        // the core's columns (or the gate's own)
        List<Integer> src = new ArrayList<>();
        if (core > 0) {
            for (int q = 0; q < n; q++) if (lab[q] == core) src.add(q);
        } else {
            int i = (int) Math.floor(g.x()) - x0, j = (int) Math.floor(g.z()) - z0;
            if (i >= 0 && j >= 0 && i < nx && j < nz) src.add(i * nz + j);
        }
        if (src.isEmpty()) return null;
        java.util.Set<Integer> coreAll = new java.util.HashSet<>();
        for (int q : src) if (all[q] > 0) coreAll.add(all[q]);
        double best = Double.MAX_VALUE;
        int bs = -1, bt = -1;
        for (int t = 0; t < n; t++) {
            if (all[t] == 0 || coreAll.contains(all[t]) || reach[all[t]] < GW_R + 2.0) continue;
            int ti = t / nz, tj = t % nz;
            for (int q : src) {
                int di = q / nz - ti, dj = q % nz - tj;
                double d = (double) di * di + (double) dj * dj;
                if (d < best || (d == best && (q < bs || (q == bs && t < bt)))) {
                    best = d;
                    bs = q;
                    bt = t;
                }
            }
        }
        if (bt < 0) return null;
        return new double[]{x0 + bs / nz + 0.5, z0 + bs % nz + 0.5, x0 + bt / nz + 0.5, z0 + bt % nz + 0.5};
    }

    /** Distance from a column's centre to a lifeline's segment. */
    static double lifeDist(double[] l, int x, int z) {
        double px = x + 0.5, pz = z + 0.5, dx = l[2] - l[0], dz = l[3] - l[1], len2 = dx * dx + dz * dz;
        double t = len2 > 0 ? Math.max(0, Math.min(1, ((px - l[0]) * dx + (pz - l[1]) * dz) / len2)) : 0;
        return Math.hypot(px - l[0] - t * dx, pz - l[1] - t * dz);
    }

    /** scipy.ndimage.label with the full 3x3 structure: labels 1.. in the order their first cell comes in a raster scan. */
    static int[] label8(boolean[] m, int nx, int nz) {
        int n = nx * nz;
        int[] lab = new int[n];
        int[] stack = new int[n];
        int next = 0;
        for (int s = 0; s < n; s++) {
            if (!m[s] || lab[s] != 0) continue;
            next++;
            int sp = 0;
            stack[sp++] = s;
            lab[s] = next;
            while (sp > 0) {
                int q = stack[--sp];
                int i = q / nz, j = q - i * nz;
                for (int di = -1; di <= 1; di++)
                    for (int dj = -1; dj <= 1; dj++) {
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                        int e = a * nz + b;
                        if (m[e] && lab[e] == 0) {
                            lab[e] = next;
                            stack[sp++] = e;
                        }
                    }
            }
        }
        return lab;
    }
}
