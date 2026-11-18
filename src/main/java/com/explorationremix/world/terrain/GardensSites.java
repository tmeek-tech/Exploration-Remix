package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.explorationremix.world.terrain.GardensNoise.hash2;

/**
 * Where the Deep Gardens' big hollow vents stand (the previews' vents.sites and deep_vents): clusters of two levels
 * per band, from cell noise on each storey's own fields. Beehives ring the bigger domes at the bottom, stacks stand in
 * the vent halls and out in the other halls in the middle, stumps sit on the seep beds at the top; the deep vents stand
 * on the deep sites. Between clusters the lower key wins; every vent needs a crack that opens onto floor.
 */
final class GardensSites {
    /** The storey fields the sites read, by world column (one pass of {@link GardensStoreys}). */
    interface Fields {
        float floor(int k, int x, int z);

        float ceil(int k, int x, int z);

        boolean foot(int k, int x, int z);

        boolean rooms(int k, int x, int z);

        float wh(int k, int x, int z);

        float wb(int k, int x, int z);

        float m0(int k, int x, int z);

        boolean hall(int k, int x, int z);

        float mb(int k, int x, int z);

        boolean sheet(int k, int x, int z);

        float domeH(int k, int x, int z);

        float bump(int k, int x, int z);

        float hot(int x, int z);

        /** The vent halls that lift storey k in this pass, whose cluster could reach the box. */
        List<GardensPlan.VHall> vhalls(int k, double minX, double minZ, double maxX, double maxZ);

        /** Storey k's domes (deep ones included) whose cluster could reach the box. */
        List<GardensPlan.Dome> domes(int k, double minX, double minZ, double maxX, double maxZ);
    }

    static final double VH_BONUS = 0.15, TOP_OFF = 0.10;

    /**
     * A vent's site, before its table row: what vents.sites and deep_vents put in each vent's dict. Its x and z are
     * measured from the sites' origin ({@link GardensSites#ox}, {@link GardensSites#oz}).
     */
    static final class Site {
        int form, k, family, j, ns, i, kk, ys, yLo, hand, gStand;
        double x, z, R, Rf, H, key, a0;
        long key24;
        boolean live, twin, deepFailed;
        /** Up to three crack angles, NaN where a crack is shut. */
        final double[] cracks = {Double.NaN, Double.NaN, Double.NaN};
        int ncr;
        GardensPlan.DeepSite deep;
        /** The canonical order: group (0 beehives, 1 stacks and outliers, 2 stumps, 3 deep), storey, kind, cell, member. */
        long[] order;

        boolean sameCluster(Site o) { return ns == o.ns && k == o.k && i == o.i && kk == o.kk && family == o.family; }

        /** A copy the door rounds may change (they shut cracks). */
        Site copy() {
            Site s = new Site();
            s.form = form;
            s.k = k;
            s.family = family;
            s.j = j;
            s.ns = ns;
            s.i = i;
            s.kk = kk;
            s.ys = ys;
            s.yLo = yLo;
            s.hand = hand;
            s.gStand = gStand;
            s.x = x;
            s.z = z;
            s.R = R;
            s.Rf = Rf;
            s.H = H;
            s.key = key;
            s.a0 = a0;
            s.key24 = key24;
            s.live = live;
            s.twin = twin;
            s.deepFailed = deepFailed;
            System.arraycopy(cracks, 0, s.cracks, 0, 3);
            s.ncr = ncr;
            s.deep = deep;
            s.order = order;
            return s;
        }

        int compareTo(Site o) {
            for (int a = 0; a < order.length; a++) if (order[a] != o.order[a]) return Long.compare(order[a], o.order[a]);
            return 0;
        }
    }

    final GardensPlan plan;
    final Fields st;
    /**
     * The origin the sites' positions are measured from: the patch's corner in the preview's rules (so every sum rounds
     * as the preview's did), else the world's.
     */
    final int ox, oz;
    /** Why candidates fell (vents.sites' Counter), for the checks. */
    final Map<String, Integer> why = new HashMap<>();

    GardensSites(GardensPlan plan, Fields st) {
        this.plan = plan;
        this.st = st;
        ox = plan.patch ? plan.px0 : 0;
        oz = plan.patch ? plan.pz0 : 0;
    }

    void count(String w) { why.merge(w, 1, Integer::sum); }

    double hv(int ns, int f, int k, int j, long i, long kk) { return GardensNoise.hv(plan.seed, ns, f, k, j, i, kk); }

    static double uSlot(long key24, int m) { return hash2(key24, m, 1); }

    // ================================================================================================ the fit test

    /** fit's result: ys, y_lo, H. */
    record Fit(int ys, int yLo, double H) {}

    /** The world column of a position, clipped into the patch in the preview's rules. */
    int ix(double x) { return (plan.patch ? Math.max(0, Math.min(plan.psx - 1, (int) Math.floor(x))) : (int) Math.floor(x)) + ox; }

    int iz(double z) { return (plan.patch ? Math.max(0, Math.min(plan.psz - 1, (int) Math.floor(z))) : (int) Math.floor(z)) + oz; }

    /** The world column of a position. */
    int cx(double x) { return (int) Math.floor(x) + ox; }

    int cz(double z) { return (int) Math.floor(z) + oz; }

    /** Whether a point is in the world the sites may use (the patch, in the preview's rules). */
    boolean inside(double x, double z) { return !plan.patch || (x >= 0 && x < plan.psx && z >= 0 && z < plan.psz); }

    float h(int k, int x, int z) { return st.ceil(k, x, z) - st.floor(k, x, z); }

    /** vents.fit: the 2D fit test (5.2), or null with the reason counted under {@code tag}. */
    Fit fit(int k, double cx, double cz, double Hw, double Rf, double rng, double minH, double clear, int ringbad, String[] reason) {
        if (plan.patch && !(Rf + 4 <= cx && cx < plan.psx - Rf - 4 && Rf + 4 <= cz && cz < plan.psz - Rf - 4)) {
            reason[0] = "edge";
            return null;
        }
        if (plan.gateDist(cx + ox, cz + oz) < 46) {
            reason[0] = "gate";
            return null;
        }
        int ix = cx(cx), iz = cz(cz);
        if (!st.rooms(k, ix, iz)) {
            reason[0] = "notroom";
            return null;
        }
        double[][] pts = GardensPlan.sitePoints(cx, cz, Rf);
        float fmax = -Float.MAX_VALUE, fmin = Float.MAX_VALUE;
        for (double[] p : pts) {
            float f = st.floor(k, ix(p[0]), iz(p[1]));
            fmax = Math.max(fmax, f);
            fmin = Math.min(fmin, f);
        }
        if (fmax - fmin > (float) rng) {
            reason[0] = "range";
            return null;
        }
        int ys = (int) Math.floor(fmax) + 1, yLo = (int) Math.floor(fmin);
        int bad = 0;
        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4;
            double x = cx + (Rf + 3) * Math.cos(ang), z = cz + (Rf + 3) * Math.sin(ang);
            if (!inside(x, z) || !st.foot(k, cx(x), cz(z)) || h(k, cx(x), cz(z)) < 4f) bad++;
        }
        if (bad > ringbad) {
            reason[0] = "ring";
            return null;
        }
        float fk = st.floor(k, ix, iz), hk = h(k, ix, iz);
        float top = k < GardensPlan.K - 1 ? st.floor(k + 1, ix, iz) : 235f;
        if (top - (fk + hk) < 4f) {
            reason[0] = "merge";
            return null;
        }
        if (k > 0)
            for (double[] p : pts) {
                int jx = ix(p[0]), jz = iz(p[1]);
                if (st.foot(k - 1, jx, jz) && st.ceil(k - 1, jx, jz) > st.floor(k, jx, jz) - 4f) {
                    reason[0] = "plug";
                    return null;
                }
            }
        float room = fk + hk - (float) ys - (float) clear;
        double H = room < (float) Hw ? room : Hw;
        if (H < minH) {
            reason[0] = "low";
            return null;
        }
        reason[0] = "ok";
        return new Fit(ys, yLo, H);
    }

    /** vents.cracks_ok: which crack angles open onto the storey's floor (2, 4 and 6 out from the fit radius). */
    boolean[] cracksOk(int k, double cx, double cz, double Rf, double[] angles, int n) {
        boolean[] ok = new boolean[n];
        for (int a = 0; a < n; a++) {
            boolean good = true;
            for (int dd = 2; dd <= 6 && good; dd += 2) {
                double x = cx + (Rf + dd) * Math.cos(angles[a]), z = cz + (Rf + dd) * Math.sin(angles[a]);
                if (!inside(x, z) || !st.foot(k, cx(x), cz(z)) || h(k, cx(x), cz(z)) < 4f) good = false;
            }
            ok[a] = good;
        }
        return ok;
    }

    /** vents.crack_angles: the angles of a vent's cracks and its a0. */
    static double[] crackAngles(int form, long key24, double R, double[] a0out) {
        double u3 = uSlot(key24, 3), a0 = 2 * Math.PI * uSlot(key24, 8);
        a0out[0] = a0;
        if (form == 3) {
            double[] out = new double[3];
            for (int c = 0; c < 3; c++) out[c] = a0 + 2 * Math.PI * c / 3 + 0.7 * (uSlot(key24, 20 + c) - 0.5);
            return out;
        }
        int n = form == 0 ? 1 + (int) (1.999 * u3) : (form == 1 ? 1 + (int) (2.999 * u3) : 2 + (int) (1.999 * u3));
        if (form == 1 && (1.05 + 0.2 * uSlot(key24, 17)) * R - 2.0 < 4.0) n = Math.min(n, 2);
        double[] out = new double[n];
        for (int c = 0; c < n; c++) out[c] = a0 + 2 * Math.PI * c / n + 0.5 * (uSlot(key24, 20 + c) - 0.5);
        return out;
    }

    /** _member_geom: (rin at the foot, lump amplitude) for the satellite spacing. */
    static double[] memberGeom(int form, double R) {
        if (form == 0) return new double[]{R - 1.4, 0.1 * R + 0.2};
        if (form == 1) return new double[]{1.15 * R - 2.0, 0.1 * R + 0.3};
        double w = Math.max(1.6, Math.min(2.6, 0.3 * R));
        return new double[]{R - w, 0.1 * R + 0.3};
    }

    // ================================================================================================ clusters

    interface FitArgs {
        /** Rf, Hw, rng, minH, clear (live), clear (dead), ringbad. */
        double[] at(int j, double sx, double sz, double R);
    }

    interface Live {
        boolean at(int ix, int iz, double y);
    }

    /** vents.place_cluster: a cluster's members, each fitted; satellites are spaced round the anchor. */
    List<Site> placeCluster(int form, int k, double siteX, double siteZ, int n, double[] Rs, double[] centre, FitArgs fa,
                            Live lf, int i, int kk, int ns, long group, long sub) {
        List<Site> placed = new ArrayList<>();
        double ax = siteX, az = siteZ;
        String[] reason = new String[1];
        for (int j = 0; j < n; j++) {
            double R = Rs[j];
            boolean twin = false;
            double sx, sz;
            if (j == 0) {
                sx = siteX;
                sz = siteZ;
            } else {
                double R0 = Rs[0];
                twin = hv(ns, 20, k, j, i, kk) < 0.25;
                boolean ok = false;
                sx = sz = 0;
                for (int t = 0; t < 4; t++) {
                    double beta;
                    if (centre != null && Math.hypot(centre[0] - ax, centre[1] - az) > 2) {
                        double thc = Math.atan2(centre[1] - az, centre[0] - ax);
                        beta = thc + 2.4 * (hv(ns, 21, k, j + 8 * t, i, kk) - 0.5);
                    } else beta = 2 * Math.PI * hv(ns, 21, k, j + 8 * t, i, kk);
                    double ud = hv(ns, 22, k, j, i, kk), ue = hv(ns, 23, k, j, i, kk);
                    double[] g0 = memberGeom(form, R0), gj = memberGeom(form, R);
                    double gap0 = g0[0] + gj[0] + g0[1] + gj[1] + 2;
                    double d = twin ? gap0 : Math.max((0.85 + 0.15 * ud) * (R0 + R) + 2 * ue, gap0);
                    sx = ax + d * Math.cos(beta);
                    sz = az + d * Math.sin(beta);
                    boolean clash = false;
                    for (Site q : placed) {
                        double[] gq = memberGeom(form, q.R);
                        if (Math.hypot(sx - q.x, sz - q.z) < gq[0] + gj[0] + gq[1] + gj[1] + 2) clash = true;
                    }
                    if (!clash) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    count("sat-clash");
                    continue;
                }
            }
            double[] a = fa.at(j, sx, sz, R);
            double Rf = a[0], Hw = a[1], rng = a[2], minH = a[3], clearL = a[4], clearD = a[5];
            int ringbad = (int) a[6];
            int cix = ix(sx), ciz = iz(sz);
            boolean live = lf.at(cix, ciz, st.floor(k, cix, ciz));
            Fit res = fit(k, sx, sz, Hw, Rf, rng, minH, live ? clearL : clearD, ringbad + (j > 0 ? 1 : 0), reason);
            count(form + (j > 0 ? "s" : "a") + "-" + reason[0]);
            if (res == null) continue;
            live = lf.at(cix, ciz, res.ys);
            double key = hv(5, ns, k, j, i, kk);
            Site s = new Site();
            s.form = form;
            s.k = k;
            s.x = sx;
            s.z = sz;
            s.R = R;
            s.Rf = Rf;
            s.H = res.H;
            s.ys = res.ys;
            s.yLo = res.yLo;
            s.live = live;
            s.key = key;
            s.key24 = (long) (key * (1 << 24));
            s.twin = twin;
            s.ns = ns;
            s.i = i;
            s.kk = kk;
            s.family = ns;
            s.j = j;
            s.order = new long[]{group, k, sub, i, kk, j};
            placed.add(s);
        }
        return placed;
    }

    boolean liveAt(int ix, int iz, double y, double off, float bonus) {
        float v = st.hot(ix, iz) + (float) GardensPlan.gOf(y);
        if (bonus != 0f) v = v + bonus;
        return v > (float) (0.30 - off);
    }

    /** Beehives round dome d of storey k (bottom band): the bigger domes, two-level clusters. */
    List<Site> beehives(int k, GardensPlan.Dome d) {
        if (d.deep() || d.r() < 14) return List.of();
        int i = d.i(), kk = d.kk();
        if (hv(4, 0, k, 0, i, kk) > 0.85) return List.of();
        double r = d.r(), cx = d.x() - ox, cz = d.z() - oz;
        int n = Math.min(3, 1 + (int) (hv(4, 1, k, 0, i, kk) * (1.0 + r / 14.0)));
        double a = 2 * Math.PI * hv(4, 2, k, 0, i, kk);
        double off = (0.15 + 0.30 * hv(4, 3, k, 0, i, kk)) * r;
        double R0 = Math.max(5.0, Math.min(9.0, 0.30 * r));
        double[] Rs = new double[n];
        Rs[0] = R0;
        for (int j = 1; j < n; j++) Rs[j] = 0.65 * R0;
        FitArgs fa = (j, sx, sz, R) -> {
            float hcol = st.domeH(k, ix(sx), iz(sz));
            return new double[]{1.45 * R, 0.75f * hcol, 9, j == 0 ? 10 : 8, 6, 5, 1};
        };
        return placeCluster(2, k, cx + off * Math.cos(a), cz + off * Math.sin(a), n, Rs, new double[]{cx, cz}, fa,
                (ix, iz, y) -> liveAt(ix, iz, y, 0, 0f), i, kk, 4, 0, 0);
    }

    /** A vent hall's stack (middle band): up to three chimneys round the hall's lift. */
    List<Site> stacks(GardensPlan.VHall vh) {
        int k = vh.k(), i = vh.i(), kk = vh.kk();
        double u = vh.u();
        int n = Math.min(3, 1 + (int) (hv(1, 4, k, 0, i, kk) * (2.5 + 1.5 * u)));
        double a = 2 * Math.PI * hv(1, 5, k, 0, i, kk), off = 3.0 * hv(1, 6, k, 0, i, kk);
        double[] c = hallCentre(vh);
        double[] Hws = new double[n];
        Hws[0] = 14 + 18 * Math.pow(hv(1, 7, k, 0, i, kk), 1.6);
        for (int j = 1; j < n; j++) Hws[j] = Math.max(10.0, Hws[0] * (0.55 + 0.3 * hv(1, 7, k, j, i, kk)));
        double[] Rs = new double[n];
        for (int j = 0; j < n; j++) Rs[j] = 2.8 + 0.12 * Hws[j];
        FitArgs fa = (j, sx, sz, R) -> new double[]{1.15 * R + 1.5 + 0.08 * Hws[j], Hws[j], 9, j == 0 ? 12 : 10, 6, 5, 1};
        return placeCluster(1, k, c[0] + off * Math.cos(a), c[1] + off * Math.sin(a), n, Rs, c, fa, (ix, iz, y) -> liveAt(ix, iz, y, 0, (float) VH_BONUS * st.bump(k, ix, iz)), i, kk, 1, 1, 0);
    }

    /**
     * A vent hall's centre in the local frame, as the preview had it: the cell's point in the frame, then the slide
     * (the plan slid it in world coordinates, which rounds the last bit differently).
     */
    double[] hallCentre(GardensPlan.VHall vh) {
        int k = vh.k(), i = vh.i(), kk = vh.kk();
        double G = GardensPlan.VH_G;
        double u1 = hv(1, 1, k, 0, i, kk), u2 = hv(1, 2, k, 0, i, kk);
        double cx0 = GardensPlan.vhSx0(k) + (i + 0.15 + 0.7 * u1) * G, cz0 = GardensPlan.vhSz0(k) + (kk + 0.15 + 0.7 * u2) * G;
        long a = Math.round((vh.x() - cx0) / 5), b = Math.round((vh.z() - cz0) / 5);
        return new double[]{(cx0 - ox) + 5 * a, (cz0 - oz) + 5 * b};
    }

    static final double OUT_G = 46, STUMP_G = 44;

    /** An outlier cell's stack (middle band): slid to the hall noise's peak among the hall rooms. */
    List<Site> outliers(int k, int i, int kk) {
        if (hv(2, 0, k, 0, i, kk) > 0.45) return List.of();
        double G = OUT_G, sx0 = 13.0 * k, sz0 = -19.0 * k;
        double u1 = hv(2, 1, k, 0, i, kk), u2 = hv(2, 2, k, 0, i, kk);
        double cx0 = sx0 + (i + 0.15 + 0.7 * u1) * G - ox, cz0 = sz0 + (kk + 0.15 + 0.7 * u2) * G - oz;
        float best = -9f;
        double cx = Double.NaN, cz = Double.NaN;
        for (int a = -4; a <= 4; a++)
            for (int b = -4; b <= 4; b++) {
                double x = cx0 + 5 * a, z = cz0 + 5 * b;
                if (!inside(x, z)) continue;
                int jx = cx(x), jz = cz(z);
                if (st.hall(k, jx, jz) && st.m0(k, jx, jz) > best) {
                    best = st.m0(k, jx, jz);
                    cx = x;
                    cz = z;
                }
            }
        if (Double.isNaN(cx)) {
            count("1-noroom");
            return List.of();
        }
        if (st.wh(k, cx(cx), cz(cz)) < 0.5f) return List.of();
        int n = 1 + (int) (2.0 * hv(2, 4, k, 0, i, kk));
        double[] Hws = new double[n], Rs = new double[n];
        for (int j = 0; j < n; j++) {
            Hws[j] = 10 + 8 * hv(2, 7, k, j, i, kk);
            Rs[j] = 2.8 + 0.12 * Hws[j];
        }
        FitArgs fa = (j, sx, sz, R) -> new double[]{1.15 * R + 1.5 + 0.08 * Hws[j], Hws[j], 9, 10, 6, 5, 1};
        return placeCluster(1, k, cx, cz, n, Rs, null, fa, (ix, iz, y) -> liveAt(ix, iz, y, 0, 0f), i, kk, 2, 1, 1);
    }

    /** A stump cell's cluster (top band): slid to the bed noise's peak on the seep beds. */
    List<Site> stumps(int k, int i, int kk) {
        if (hv(3, 0, k, 0, i, kk) > 0.7) return List.of();
        double G = STUMP_G, sx0 = 17.0 * k, sz0 = -31.0 * k;
        double u1 = hv(3, 1, k, 0, i, kk), u2 = hv(3, 2, k, 0, i, kk);
        double cx0 = sx0 + (i + 0.15 + 0.7 * u1) * G - ox, cz0 = sz0 + (kk + 0.15 + 0.7 * u2) * G - oz;
        float best = -9f;
        double cx = Double.NaN, cz = Double.NaN;
        for (int a = -4; a <= 4; a++)
            for (int b = -4; b <= 4; b++) {
                double x = cx0 + 5 * a, z = cz0 + 5 * b;
                if (!inside(x, z)) continue;
                int jx = cx(x), jz = cz(z);
                if (st.sheet(k, jx, jz) && st.mb(k, jx, jz) > best) {
                    best = st.mb(k, jx, jz);
                    cx = x;
                    cz = z;
                }
            }
        if (Double.isNaN(cx)) {
            count("0-noroom");
            return List.of();
        }
        if (st.wb(k, cx(cx), cz(cz)) < 0.5f) return List.of();
        int n = 1 + (int) (2.2 * hv(3, 4, k, 0, i, kk));
        double[] Rs = new double[n];
        for (int j = 0; j < n; j++) Rs[j] = 3.4 + 1.4 * hv(3, 9, k, j, i, kk);
        FitArgs fa = (j, sx, sz, R) -> {
            float hb = h(k, ix(sx), iz(sz));
            return new double[]{1.3 * R, hb < 16f ? 9.0 : 16.0, 6, 4, 2.5, 2.5, 2};
        };
        return placeCluster(0, k, cx, cz, n, Rs, null, fa, (ix, iz, y) -> liveAt(ix, iz, y, TOP_OFF, 0f), i, kk, 3, 2, 0);
    }

    /** The final rule between clusters (vents.sites' tail): lower key wins, none near a deep axis, every vent a crack. */
    List<Site> resolve(List<Site> cands, List<GardensPlan.DeepSite> deeps, java.util.function.Predicate<Site> wanted) {
        List<Site> out = new ArrayList<>();
        for (Site v : cands) {
            if (!wanted.test(v)) continue;
            boolean lose = false;
            for (Site q : cands) {
                if (q == v || q.k != v.k || q.sameCluster(v)) continue;
                if (Math.hypot(v.x - q.x, v.z - q.z) < 1.15 * (v.R + q.R) + 4 && q.key < v.key) {
                    lose = true;
                    break;
                }
            }
            if (lose) {
                count("conflict");
                continue;
            }
            boolean near = false;
            for (GardensPlan.DeepSite d : deeps) if (Math.hypot(v.x - (d.x() - ox), v.z - (d.z() - oz)) < 36) near = true;
            if (near) {
                count("deep36");
                continue;
            }
            double[] a0 = new double[1];
            double[] angs = crackAngles(v.form, v.key24, v.R, a0);
            boolean[] ok = cracksOk(v.k, v.x, v.z, v.Rf, angs, angs.length);
            boolean any = false;
            for (boolean o : ok) any |= o;
            if (!any) {
                count("nocrack");
                continue;
            }
            v.ncr = angs.length;
            for (int c = 0; c < angs.length; c++) v.cracks[c] = ok[c] ? angs[c] : Double.NaN;
            v.a0 = a0[0];
            out.add(v);
        }
        return out;
    }

    /**
     * vents.sites' candidates: every cluster member that fits, from the clusters whose cells lie over the box (the
     * caller pads it by what the final rule reads), in the canonical order.
     */
    List<Site> candidates(double minX, double minZ, double maxX, double maxZ) {
        List<Site> cands = new ArrayList<>();
        for (int k = 0; k < GardensPlan.K; k++)
            for (GardensPlan.Dome d : st.domes(k, minX, minZ, maxX, maxZ)) cands.addAll(beehives(k, d));
        for (int k = 0; k < GardensPlan.K; k++) {
            for (GardensPlan.VHall vh : st.vhalls(k, minX, minZ, maxX, maxZ)) cands.addAll(stacks(vh));
            if (plan.patch && !plan.halls[k]) continue;
            double sx0 = 13.0 * k, sz0 = -19.0 * k;
            for (int i = (int) Math.floor((minX - sx0) / OUT_G) - 1; i <= (int) Math.floor((maxX - sx0) / OUT_G) + 1; i++)
                for (int kk = (int) Math.floor((minZ - sz0) / OUT_G) - 1; kk <= (int) Math.floor((maxZ - sz0) / OUT_G) + 1; kk++)
                    cands.addAll(outliers(k, i, kk));
        }
        for (int k = 0; k < GardensPlan.K; k++) {
            if (plan.patch && !plan.beds[k]) continue;
            double sx0 = 17.0 * k, sz0 = -31.0 * k;
            for (int i = (int) Math.floor((minX - sx0) / STUMP_G) - 1; i <= (int) Math.floor((maxX - sx0) / STUMP_G) + 1; i++)
                for (int kk = (int) Math.floor((minZ - sz0) / STUMP_G) - 1; kk <= (int) Math.floor((maxZ - sz0) / STUMP_G) + 1; kk++)
                    cands.addAll(stumps(k, i, kk));
        }
        cands.sort(Site::compareTo);
        return cands;
    }

    /** One window of storeys as the fields the sites read (the vent halls and domes are the window's own lists). */
    static Fields of(GardensStoreys s) {
        return new Fields() {
            int q(int x, int z) { return s.idx(x, z); }

            public float floor(int k, int x, int z) { return s.floor[k][q(x, z)]; }

            public float ceil(int k, int x, int z) { return s.ceil[k][q(x, z)]; }

            public boolean foot(int k, int x, int z) { return s.foot[k][q(x, z)]; }

            public boolean rooms(int k, int x, int z) { return s.rooms[k][q(x, z)]; }

            public float wh(int k, int x, int z) { return s.wh[k][q(x, z)]; }

            public float wb(int k, int x, int z) { return s.wb[k][q(x, z)]; }

            public float m0(int k, int x, int z) { return s.m0[k][q(x, z)]; }

            public boolean hall(int k, int x, int z) { return s.hall[k][q(x, z)]; }

            public float mb(int k, int x, int z) { return s.mb[k][q(x, z)]; }

            public boolean sheet(int k, int x, int z) { return s.sheet[k][q(x, z)]; }

            public float domeH(int k, int x, int z) { return s.dh[k][q(x, z)]; }

            public float bump(int k, int x, int z) { return s.bump[k][q(x, z)]; }

            public float hot(int x, int z) { return s.hot[q(x, z)]; }

            public List<GardensPlan.VHall> vhalls(int k, double minX, double minZ, double maxX, double maxZ) {
                List<GardensPlan.VHall> out = new ArrayList<>();
                for (GardensPlan.VHall v : s.vhalls.get(k))
                    if (v.x() >= minX && v.x() < maxX && v.z() >= minZ && v.z() < maxZ) out.add(v);
                return out;
            }

            public List<GardensPlan.Dome> domes(int k, double minX, double minZ, double maxX, double maxZ) {
                List<GardensPlan.Dome> out = new ArrayList<>();
                for (GardensPlan.Dome d : s.domes.get(k)) if (!d.deep()) out.add(d);
                return out;
            }
        };
    }

    /** vents.deep_vents: a live beehive with the round profile on each deep site (a plain one where the gap is short). */
    Site deepVent(GardensPlan.DeepSite d) {
        double key = hv(5, 6, 0, 0, d.i(), d.kk());
        long key24 = (long) (key * (1 << 24));
        Site v = new Site();
        v.R = d.R();
        v.Rf = d.Rf();
        v.ys = (int) Math.floor(d.fmax()) + 1;
        v.yLo = (int) Math.floor(d.fmin());
        v.H = Math.min(30.0, d.hd() - 6 - (v.ys - d.f0()));
        v.form = 3;
        v.k = 0;
        v.x = d.x() - ox;
        v.z = d.z() - oz;
        v.live = true;
        v.key = key;
        v.key24 = key24;
        v.ns = 6;
        v.i = d.i();
        v.kk = d.kk();
        v.family = 6;
        v.j = 0;
        v.hand = d.hand();
        v.a0 = d.a0();
        v.gStand = d.gStand();
        v.deep = d;
        v.order = new long[]{3, 0, 0, d.i(), d.kk(), 0};
        if (v.H < 22) {
            v.form = 2;
            v.family = 4;
            v.deepFailed = true;
        }
        double[] angs = new double[3];
        if (v.form == 3) {
            double phiP = d.a0() + d.hand() * Math.PI / 3;
            for (int c = 0; c < 3; c++)
                angs[c] = phiP + d.hand() * (Math.toRadians(140.0 + 80.0 * c) + 0.35 * (uSlot(key24, 20 + c) - 0.5));
        } else {
            for (int c = 0; c < 3; c++) angs[c] = d.a0() + 2 * Math.PI * c / 3 + 0.7 * (uSlot(key24, 20 + c) - 0.5);
        }
        boolean[] ok = cracksOk(0, v.x, v.z, v.Rf, angs, 3);
        boolean any = ok[0] || ok[1] || ok[2];
        v.ncr = 3;
        for (int c = 0; c < 3; c++) v.cracks[c] = (ok[c] || !any) ? angs[c] : Double.NaN;
        return v;
    }
}
