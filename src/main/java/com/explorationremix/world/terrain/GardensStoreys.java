package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.explorationremix.world.terrain.GardensMath.clipF;
import static com.explorationremix.world.terrain.GardensMath.pull;
import static com.explorationremix.world.terrain.GardensMath.rampF;
import static com.explorationremix.world.terrain.GardensPlan.CUP_GAP;
import static com.explorationremix.world.terrain.GardensPlan.DOME_CAP;
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

    /**
     * @param use     which vent halls lift their storey (null: every kept one, the first pass)
     * @param edtCap  the halls' distance transform is cut at this (the window's margin must cover it)
     * @param openOnly stop once the open footprint is known (no heights; for the gate plumbing's own windows)
     */
    GardensStoreys(GardensPlan plan, int x0, int z0, int nx, int nz, Predicate<GardensPlan.VHall> use, float edtCap,
                   boolean openOnly) {
        this.plan = plan;
        this.x0 = x0;
        this.z0 = z0;
        this.nx = nx;
        this.nz = nz;
        this.n = nx * nz;
        hot = new float[n];
        mound = new float[n];
        lb = new float[n];
        float[] big = new float[n], cap6 = new float[n];
        float[][] td = new float[K][n], tw = new float[K][n], cv = new float[K][n], wn = new float[K][n], pil = new float[K][n],
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
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j;
                plan.column(x0 + i, z0 + j, c, all);
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
                    wn[k][q] = c.wn[k];
                    mb[k][q] = c.mb[k];
                    pil[k][q] = c.pil[k];
                    col[k][q] = c.col[k];
                    hn[k][q] = c.hn[k];
                }
            }
        double minX = x0, minZ = z0, maxX = x0 + nx - 1, maxZ = z0 + nz - 1;
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
                int ia = Math.max(0, (int) Math.floor(v.x() - rv - 1) - x0), ib = Math.min(nx - 1, (int) Math.ceil(v.x() + rv + 1) - x0);
                int ja = Math.max(0, (int) Math.floor(v.z() - rv - 1) - z0), jb = Math.min(nz - 1, (int) Math.ceil(v.z() + rv + 1) - z0);
                for (int i = ia; i <= ib; i++)
                    for (int j = ja; j <= jb; j++) {
                        int q = i * nz + j;
                        float dd = plan.dist(x0 + i, z0 + j, v.x(), v.z()) / rvF;
                        float b = rampF(1f - dd, 0, 1);
                        if (b > bumpK[q]) bumpK[q] = b;
                        float ub = uF * b;
                        if (ub > ucap[q]) ucap[q] = ub;
                        float cu = dd < 1f ? cupH * (float) Math.sqrt(clipF(1f - dd * dd, 0f, 1f)) : 0f;
                        if (cu > cup[q]) cup[q] = cu;
                    }
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
                boolean[] fh = new boolean[n];
                for (int q = 0; q < n; q++) fh[q] = hallK[q] || tunK[q];
                float[] edt = GardensMath.gaussian(GardensMath.edt(fh, nx, nz, edtCap), nx, nz, 1.5);
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
            // domes
            float[] dhK = new float[n], ocK = new float[n];
            List<GardensPlan.Dome> ds = plan.domesNear(k, minX, minZ, maxX, maxZ, 0);
            domes.add(ds);
            for (GardensPlan.Dome d : ds) {
                double r = d.r();
                int ia = Math.max(0, (int) Math.floor(d.x() - r * 1.2) - x0), ib = Math.min(nx - 1, (int) Math.floor(d.x() + r * 1.2) + 1 - x0);
                int ja = Math.max(0, (int) Math.floor(d.z() - r * 1.2) - z0), jb = Math.min(nz - 1, (int) Math.floor(d.z() + r * 1.2) + 1 - z0);
                double tt = Math.max(0, Math.min(1, (r - 12) / 4)), hfr = (0.95 + 0.15 * tt * tt * (3 - 2 * tt)) * r;
                float rF = (float) r, hcF = (float) d.hc();
                for (int i = ia; i <= ib; i++)
                    for (int j = ja; j <= jb; j++) {
                        int q = i * nz + j;
                        float dd = plan.dist(x0 + i, z0 + j, d.x(), d.z()) / rF * (1f + wn[k][q]);
                        float hl;
                        if (dd < 1f) {
                            float s = (float) Math.sqrt(clipF(1f - dd * dd, 0f, 1f));
                            hl = d.deep() ? hcF * s : (float) Math.min(hfr * (double) s, DOME_CAP);
                        } else hl = 0f;
                        if (hl > dhK[q]) dhK[q] = hl;
                        float o = clipF(1f - dd / 0.65f, 0f, 1f);
                        if (o > ocK[q]) ocK[q] = o;
                    }
            }
            if (k == 0 && plan.domes[0]) {
                // the lower gate's own dome, ringed by rock past 24-28
                for (GardensGates.Gate g : plan.gates.near(minX + (maxX - minX) / 2, minZ + (maxZ - minZ) / 2,
                        Math.hypot(maxX - minX, maxZ - minZ) / 2 + 48)) {
                    if (g.entry()) continue;
                    int ia = Math.max(0, (int) Math.floor(g.x() - 46) - x0), ib = Math.min(nx - 1, (int) Math.ceil(g.x() + 46) - x0);
                    int ja = Math.max(0, (int) Math.floor(g.z() - 46) - z0), jb = Math.min(nz - 1, (int) Math.ceil(g.z() + 46) - z0);
                    float gdH = (float) (0.9 * GATE_R);
                    for (int i = ia; i <= ib; i++)
                        for (int j = ja; j <= jb; j++) {
                            int q = i * nz + j;
                            int wx = x0 + i, wz = z0 + j;
                            float ringX = pull(wx, wz, g.x(), g.z(), 30.0, 44.0) - pull(wx, wz, g.x(), g.z(), GATE_R, GATE_R + 4.0);
                            if (ringX > 0.5f) {
                                dhK[q] = 0f;
                                continue;
                            }
                            float dd = plan.dist(wx, wz, g.x(), g.z()) / (float) GATE_R;
                            float gd = dd < 1f ? gdH * (float) Math.sqrt(clipF(1f - dd * dd, 0f, 1f)) : 0f;
                            if (gd > dhK[q]) dhK[q] = gd;
                        }
                }
            }
            for (int q = 0; q < n; q++) if (dhK[q] > 0f) dhK[q] = Math.max(dhK[q], 3.2f);
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
                float[] edtb = GardensMath.gaussian(GardensMath.edt(sheetK, nx, nz, 40f), nx, nz, 1.2);
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
                    for (GardensGates.Gate g : plan.gates.near(minX + (maxX - minX) / 2, minZ + (maxZ - minZ) / 2,
                            Math.hypot(maxX - minX, maxZ - minZ) / 2 + 40)) {
                        if (g.entry() != (k == K - 1)) continue;
                        GateWays gw = plan.gateWays(g, k, use);
                        if (gw == null) continue;
                        boolean touched = false;
                        for (long cell : gw.plugged) {
                            int i = (int) (cell >> 32) - x0, j = (int) cell - z0;
                            if (i < 0 || j < 0 || i >= nx || j >= nz) continue;
                            int q = i * nz + j;
                            hallK[q] = false;
                            tunK[q] = false;
                            sheetK[q] = false;
                            dhK[q] = 0f;
                            touched = true;
                        }
                        if (touched || gw.inside(x0, z0, nx, nz)) gateWays.add(gw);
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
                cei = footK[q] ? Math.min(cei, 235f) : flo - 5f;
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

    int idx(int wx, int wz) { return (wx - x0) * nz + (wz - z0); }

    boolean contains(int wx, int wz) { return wx >= x0 && wz >= z0 && wx < x0 + nx && wz < z0 + nz; }

    /** The roughness each storey's walls take: each column its own style's. */
    float rough(int k, int q) { return 2.4f * wh[k][q] + 1.3f * wd[k][q] + 1.1f * wb[k][q]; }

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

    /** gen3.gate_ways' result for one gate on one storey: the crossings, the kept ones, the plugged columns. */
    static final class GateWays {
        final GardensGates.Gate gate;
        final int k;
        final double[] crossings, kept;
        final long[] plugged;

        GateWays(GardensGates.Gate gate, int k, double[] crossings, double[] kept, long[] plugged) {
            this.gate = gate;
            this.k = k;
            this.crossings = crossings;
            this.kept = kept;
            this.plugged = plugged;
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
        if (core == 0) return new GateWays(g, k, new double[0], new double[0], new long[0]);
        boolean[] band = new boolean[n];
        for (int q = 0; q < n; q++) band[q] = lab[q] == core && rr[q] >= GW_R - 1.0 && rr[q] <= GW_R + 0.5;
        int[] bl = label8(band, nx, nz);
        int nb = 0;
        for (int v : bl) nb = Math.max(nb, v);
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
