package com.explorationremix.world.terrain;

import java.util.HashMap;
import java.util.Map;

import static com.explorationremix.world.terrain.GardensNoise.n2;
import static com.explorationremix.world.terrain.GardensNoise.n3;
import static com.explorationremix.world.terrain.GardensNoise.ramp;
import static com.explorationremix.world.terrain.GardensVents.DS;
import static com.explorationremix.world.terrain.GardensVents.n1;

/**
 * Where a deep vent's throat comes out into the Wasteland's top tunnels (Deep Gardens round 2: the preview's exits.py
 * under the rule it settled on, "ring or outlet, risk 9"), searched against the Wasteland as the game makes it, so a
 * throat always meets a real tunnel. It reads only the seed and the site, so every chunk sees the same answer.
 *
 * <p>For each exit row g from Y -549 down to Y -589 (layer rows -21..-61), the throat's table is built down to g with
 * a level run of a whole turn, and g is taken when:
 * <ol>
 *   <li>no throat air on the way down (before the level run) touches the Wasteland's air;</li>
 *   <li>the level run's first touch lies on a run of at least 3 one-block samples of its outer side where the
 *   Wasteland has air at g..g+2 over rock (a floor within a block of g); or, when the ring meets no tunnel so, the
 *   outlet that unwinds from the ring (Taran's "Make them wander": an S of at most 36 blocks, leaving every 2 blocks
 *   along the level run) meets one so;</li>
 *   <li>no lava lies within 6 blocks of the touch;</li>
 *   <li>the junction walks: no spot of the level run or outlet has a walk-off drop of 2 or more beside it, and at
 *   least 9 rows of rock lie between it and the turn above.</li>
 * </ol>
 * The highest g wins. A site with none becomes an ordinary live vent ({@link GardensSites#deepVent}).
 *
 * <p>Coordinates: block x and z are the world's; the vent's centre and the curves are in the table's frame (the world
 * less {@link GardensVents#ox}); rows are the layer's (0 = its bottom row), slab index e = row + {@link #E_OFF}.
 */
final class GardensExits implements GardensField.Exits {
    /** The Wasteland slab searched: layer rows -72..-17 (Y -600..-545); slab index e = row + E_OFF. */
    static final int E_OFF = 72, SLAB = 56, SLAB_TOP = -17;
    /** The exit rows tried (layer rows, top down) and the floor samples a run needs. */
    static final int G_HI = -21, G_LO = -61, NEED = 3;
    /** The unwinding outlet: its longest reach before the first touch, its step, meander, unwinding and S-bend. */
    static final double OUT_MAX = 36.0, OUT_STEP = 0.25, OUT_BEND = 0.10, OUT_BEND_L = 11.0, OUT_UNWIND = 8.0, OUT_ARC = 0.032;
    /** The least rock between the junction and the turn above it. */
    static final int MIN_GAP = 9;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** The Wasteland's own blocks, with no throat carved: a view for one search. */
    interface Waste {
        /** A view for one search on one thread (it may keep what it reads). */
        View open();

        interface View {
            /** Column (x, z) over the slab's rows: 0 air, 1 rock, 2 lava, index e = row + E_OFF. */
            void column(int x, int z, byte[] out);
        }
    }

    private final GardensVents gv;
    private final Waste waste;

    GardensExits(GardensVents gv, Waste waste) {
        this.gv = gv;
        this.waste = waste;
    }

    /** A deep vent as the search reads it: centre (table frame), top row, ring radius, start angle, hand, noise. */
    record Site(double cx, double cz, double ys, double Rh, double phiP, double d, int[] p) {
        static Site of(GardensVents gv, GardensVents.Vent v) {
            return new Site(v.cx, v.cz, v.t[6], v.t[36], v.t[35], v.t[34], gv.throatPerm(v.site));
        }

        /** The throat's table down to row g with a level run of levelLen (exits.table_for). */
        double[][] table(int g, double levelLen) {
            return GardensVents.throatTable(p, ys, g, Rh, DS, levelLen, phiP, d);
        }
    }

    // ================================================================================================ the slab

    /** One search's view of the slab: columns read on first use, as bit masks over e (air, lava, rock, touch). */
    private final class Slab {
        private final Waste.View view = waste.open();
        private final Map<Long, long[]> cols = new HashMap<>();
        private final byte[] buf = new byte[SLAB];

        long[] col(int x, int z) {
            long k = ((long) x << 32) | (z & 0xFFFFFFFFL);
            long[] c = cols.get(k);
            if (c == null) {
                view.column(x, z, buf);
                long air = 0, lava = 0;
                for (int e = 0; e < SLAB; e++) {
                    if (buf[e] == 0) air |= 1L << e;
                    else if (buf[e] == 2) lava |= 1L << e;
                }
                c = new long[]{air, lava, -1L};
                cols.put(k, c);
            }
            return c;
        }

        boolean air(int x, int e, int z) { return (col(x, z)[0] >>> e & 1) != 0; }

        /** Rock (not lava) at slab index e. */
        boolean rock(int x, int e, int z) {
            long[] c = col(x, z);
            return ((c[0] | c[1]) >>> e & 1) == 0;
        }

        /**
         * Which slab indices of a column hold a block that is the Wasteland's air or face-touches it (bit e), plus bit
         * 56 for the row just over the slab, which touches the slab's top row.
         */
        long touch(int x, int z) {
            long[] c = col(x, z);
            if (c[2] == -1L) {
                long a = c[0];
                long t = a | (a << 1) | (a >>> 1) | col(x + 1, z)[0] | col(x - 1, z)[0] | col(x, z + 1)[0] | col(x, z - 1)[0];
                c[2] = t & ((1L << (SLAB + 1)) - 1);
            }
            return c[2];
        }

        boolean touches(int x, int y, int z) {
            int e = y + E_OFF;
            return e >= 0 && e <= SLAB && (touch(x, z) >>> e & 1) != 0;
        }

        /** exits._sample_ok: Wasteland air at g'..g'+2 over rock at g'-1, for some g' within 1 of g. */
        boolean sampleOk(int x, int z, int g) {
            for (int gg : new int[]{g, g + 1, g - 1}) {
                int e = gg + E_OFF;
                if (e - 1 < 0 || e + 2 >= SLAB) continue;
                if (rock(x, e - 1, z) && air(x, e, z) && air(x, e + 1, z) && air(x, e + 2, z)) return true;
            }
            return false;
        }

        /** exits._lava_near: lava within 6 blocks (a cube) of a block. */
        boolean lavaNear(int x, int y, int z) {
            int e = y + E_OFF, lo = Math.max(0, e - 6), hi = Math.min(SLAB - 1, e + 6);
            if (lo > hi) return false;
            long rows = ((1L << (hi - lo + 1)) - 1) << lo;
            for (int xx = x - 6; xx <= x + 6; xx++)
                for (int zz = z - 6; zz <= z + 6; zz++) if ((col(xx, zz)[1] & rows) != 0) return true;
            return false;
        }

        /** The box's solid (walking) rule: slab rock or lava, and all rock over the slab (the band). */
        boolean solidRaw(int x, int e, int z) { return e < 0 || e >= SLAB || !air(x, e, z); }
    }

    /** The touch the search found first: how many, the least table (or curve) index, and that block. */
    private static final class Hit {
        int n, minI = Integer.MAX_VALUE, x, y, z;

        void add(int i, int x, int y, int z) {
            n++;
            if (i < minI) {
                minI = i;
                this.x = x;
                this.y = y;
                this.z = z;
            }
        }
    }

    // ================================================================================================ the search

    @Override
    public GardensField.Exit find(GardensPlan.DeepSite d, GardensVents.Vent v) {
        Site s = Site.of(gv, v);
        Slab w = new Slab();
        double turn = 2 * Math.PI * s.Rh;
        for (int g = G_HI; g >= G_LO; g--) {
            double[][] tab = s.table(g, turn);
            int iLevel = -1;
            for (int i = 0; i < tab.length; i++)
                if (tab[i][0] <= g + 1e-6) {
                    iLevel = i;
                    break;
                }
            if (iLevel < 0) continue;
            Hit c = contacts(w, s, tab);
            // a breach on the way down stays a breach for every lower g (their tables share the same descent)
            if (c.n > 0 && c.minI < iLevel) return null;
            GardensField.Exit e = c.n > 0 ? tryRing(w, s, tab, g, iLevel, c) : null;
            if (e == null) e = tryOutlets(w, s, tab, g, iLevel, c);
            if (e != null) return e;
        }
        return null;
    }

    /**
     * exits._contacts over the slab's rows: every throat block that is the Wasteland's air or face-touches it, as the
     * least table index and the first such block in x, z, y order. Only blocks near the table's centre line can be
     * throat, and only blocks next to air can touch it, so only those are tried.
     */
    private Hit contacts(Slab w, Site s, double[][] tab) {
        Hit h = new Hit();
        double rh = tab[0][1];
        int r = (int) (rh + 13 + GardensVents.DRIFT_MAX + 1);
        int bx0 = (int) Math.floor(s.cx) - r, bz0 = (int) Math.floor(s.cz) - r;
        int nx = 2 * r + 1, nz = 2 * r + 1;
        long[] mask = new long[nx * nz];
        for (int i = 0; i < tab.length; i++) {
            int yb = (int) Math.floor(tab[i][0] + 0.5);
            int lo = Math.max(yb, -E_OFF), hi = Math.min(yb + 9, SLAB_TOP);
            if (lo > hi) continue;
            long bits = ((1L << (hi - lo + 1)) - 1) << (lo + E_OFF);
            double psi = s.phiP + s.d * (i * DS) / rh;
            double px = s.cx + tab[i][4] + tab[i][1] * Math.cos(psi), pz = s.cz + tab[i][5] + tab[i][1] * Math.sin(psi);
            mark(mask, bx0, bz0, nx, nz, px, pz, tab[i][2] / 2 + 3.0, bits);
        }
        for (int a = 0; a < nx; a++)
            for (int c = 0; c < nz; c++) {
                long m = mask[a * nz + c];
                if (m == 0) continue;
                int x = bx0 + a + gv.ox, z = bz0 + c + gv.oz;
                m &= w.touch(x, z);
                while (m != 0) {
                    int e = Long.numberOfTrailingZeros(m);
                    m &= m - 1;
                    int y = e - E_OFF;
                    long t = gv.tubeHit(tab, s.cx, s.cz, s.phiP, s.d, rh, x, y, z);
                    if (t >= 0) h.add((int) (t >> 16), x, y, z);
                }
            }
        return h;
    }

    /** Sets bits on every column of the box whose middle lies within r of (px, pz) (the table's frame). */
    private static void mark(long[] mask, int bx0, int bz0, int nx, int nz, double px, double pz, double r, long bits) {
        int a0 = Math.max(0, (int) Math.floor(px - r) - bx0), a1 = Math.min(nx - 1, (int) Math.floor(px + r) - bx0);
        int c0 = Math.max(0, (int) Math.floor(pz - r) - bz0), c1 = Math.min(nz - 1, (int) Math.floor(pz + r) - bz0);
        double r2 = r * r;
        for (int a = a0; a <= a1; a++) {
            double dx = bx0 + a + 0.5 - px;
            for (int c = c0; c <= c1; c++) {
                double dz = bz0 + c + 0.5 - pz;
                if (dx * dx + dz * dz <= r2) mask[a * nz + c] |= bits;
            }
        }
    }

    /** exits._runs_ok: a run of at least {@code need} passing samples within 2 of the touch. */
    private static boolean runsOk(double[] at, boolean[] ok, int n, double sc, int need) {
        int k = 0;
        while (k < n) {
            if (!ok[k]) {
                k++;
                continue;
            }
            int k2 = k;
            while (k2 + 1 < n && ok[k2 + 1]) k2++;
            if (k2 - k + 1 >= need && at[k] - 2 <= sc && sc <= at[k2] + 2) return true;
            k = k2 + 1;
        }
        return false;
    }

    /** exits.try_ring: the first touch lies on the level run's ring, on a run of floor samples. */
    private GardensField.Exit tryRing(Slab w, Site s, double[][] tab, int g, int iLevel, Hit c) {
        double sLevel = iLevel * DS, sC = c.minI * DS;
        double L = tab.length * DS - sLevel;
        int n = (int) L;
        double[] at = new double[n];
        boolean[] ok = new boolean[n];
        for (int j = 0; j < n; j++) {
            double sv = sLevel + j;
            int i = Math.min((int) (sv / DS), tab.length - 1);
            double psi = s.phiP + s.d * sv / s.Rh;
            double rho = tab[i][1] + tab[i][2] / 2 + 1.0;
            int x = (int) Math.floor(s.cx + tab[i][4] + rho * Math.cos(psi)) + gv.ox;
            int z = (int) Math.floor(s.cz + tab[i][5] + rho * Math.sin(psi)) + gv.oz;
            at[j] = sv;
            ok[j] = w.sampleOk(x, z, g);
        }
        if (!runsOk(at, ok, n, sC, NEED)) return null;
        if (w.lavaNear(c.x, c.y, c.z)) return null;
        double level = sC - sLevel + 4.0;
        int[] sg = validate(w, s, s.table(g, level), null, g);
        if (sg[0] > 0 || sg[1] < MIN_GAP) return null;
        return new GardensField.Exit(g, level, Double.NaN, Double.NaN);
    }

    /** exits.try_outlets: departures every 2 blocks along the level run, before any touch the ring makes. */
    private GardensField.Exit tryOutlets(Slab w, Site s, double[][] tab, int g, int iLevel, Hit ring) {
        double sLevel = iLevel * DS, turn = 2 * Math.PI * s.Rh;
        double sRingC = ring.n > 0 ? ring.minI * DS : 1e9;
        for (double sDep = sLevel + 4.0; sDep <= sLevel + turn && sDep + 2.0 < sRingC; sDep += 2.0) {
            double[][] cur = outletCurve(s, tab, sDep, OUT_MAX + 8.0);
            Hit c = outletContacts(w, cur, g);
            if (c.n == 0) continue;
            double tC = c.minI * OUT_STEP;
            if (tC > OUT_MAX) continue;
            double t0 = Math.max(0.0, tC - 2.0);
            int n = (int) Math.ceil(tC + 7.0 - t0);
            double[] at = new double[n];
            boolean[] ok = new boolean[n];
            for (int k = 0; k < n; k++) {
                double t = t0 + k;
                int j = Math.min((int) (t / OUT_STEP), cur.length - 1);
                at[k] = t;
                ok[k] = w.sampleOk((int) Math.floor(cur[j][0]) + gv.ox, (int) Math.floor(cur[j][1]) + gv.oz, g);
            }
            if (!runsOk(at, ok, n, tC, NEED)) continue;
            if (w.lavaNear(c.x, c.y, c.z)) continue;
            double level = sDep - sLevel + 2.0;
            double[][] ftab = s.table(g, level);
            double[][] fcur = outletCurve(s, ftab, sDep, tC + 4.0);
            int[] sg = validate(w, s, ftab, fcur, g);
            if (sg[0] > 0 || sg[1] < MIN_GAP) continue;
            return new GardensField.Exit(g, level, sDep, tC + 4.0);
        }
        return null;
    }

    // ================================================================================================ the outlet

    /** exits._tw_th: the table's width and height at centre-line distance s (its noise, carried on past its end). */
    private static double[] twTh(int[] p, double s) {
        double pk = n1(p, s / 31.0 + 50.0);
        double bump = s > 30.0 ? ramp(pk, 0.55, 0.75) : 0.0;
        return new double[]{4.6 + 0.6 * n1(p, s / 9.0 + 3.0) + 2.5 * bump, 5.0 + 0.8 * n1(p, s / 7.0 + 7.0) + 2.0 * bump};
    }

    /** exits._ring_xz: the centre line's (x, z) at helix parameter s, the axis drift included. */
    private static double[] ringXZ(Site s, double[][] tab, double sv) {
        int i = Math.min(Math.max((int) (sv / DS), 0), tab.length - 1);
        double psi = s.phiP + s.d * sv / s.Rh;
        return new double[]{s.cx + tab[i][4] + tab[i][1] * Math.cos(psi), s.cz + tab[i][5] + tab[i][1] * Math.sin(psi)};
    }

    /**
     * exits.outlet_curve: the outlet leaving the ring at sDep, as rows of (x, z, t, width, height) every OUT_STEP. It
     * leaves along the ring's real tangent, keeps the ring's curl fading over 8 blocks (an unwinding arm), then bends
     * steadily against it (an S) and meanders.
     */
    static double[][] outletCurve(Site s, double[][] tab, double sDep, double length) {
        int i0 = Math.min((int) (sDep / DS), tab.length - 1);
        double[] a = ringXZ(s, tab, sDep), b = ringXZ(s, tab, sDep - 1.0);
        double x = a[0], z = a[1];
        double beta = Math.atan2(z - b[1], x - b[0]);
        double r0 = tab[i0][1];
        double arc = -s.d * OUT_ARC * (1.0 + 0.2 * n1(s.p, 7.7));
        int n = (int) (length / OUT_STEP) + 1;
        double[][] out = new double[n][];
        for (int j = 0; j < n; j++) {
            double t = j * OUT_STEP;
            double[] tt = twTh(s.p, sDep + t);
            out[j] = new double[]{x, z, t, tt[0], tt[1]};
            double kappa = s.d * Math.exp(-t / OUT_UNWIND) / r0 + arc * ramp(t, 6.0, 14.0)
                    + OUT_BEND * n1(s.p, (sDep + t) / OUT_BEND_L + 71.0) * ramp(t, 2.0, 8.0);
            beta += kappa * OUT_STEP;
            x += Math.cos(beta) * OUT_STEP;
            z += Math.sin(beta) * OUT_STEP;
        }
        return out;
    }

    /**
     * exits._outlet_air's footprint at one column: the nearest centre-line sample (the first of equals), or -1 where
     * the column is not the outlet's (or is nearest its start, which the ring keeps); {@code out} gets the sample's
     * base height there (before the roof's noise).
     */
    static int outletColumn(double[][] cur, int[] ptube, int fx, int fz, int x, int z, double[] out) {
        double best = 1e18;
        int jb = -1;
        double qx = fx + 0.5, qz = fz + 0.5;
        for (int j = 0; j < cur.length; j++) {
            double dx = qx - cur[j][0], dz = qz - cur[j][1];
            double dd = dx * dx + dz * dz;
            if (dd < best) {
                best = dd;
                jb = j;
            }
        }
        if (jb <= 0) return -1;
        double e = Math.sqrt(best), tw = cur[jb][3], th = cur[jb][4];
        double rough = 0.6 * n2(ptube, (x + 0.5) / 4.5, (z + 0.5) / 4.5);
        if (e >= tw / 2 + rough) return -1;
        double q = e / (tw / 2 + 0.5), q2 = q * q;
        out[0] = th * Math.sqrt(Math.max(0.0, 1.0 - q2 * q2));
        return jb;
    }

    /** exits._outlet_air's rows: the outlet's air at row y of a column whose base height is h0, its floor at g. */
    static boolean outletRow(int[] ptube, double h0, int g, int x, int y, int z) {
        double hh = h0 + 0.5 * n3(ptube, x / 4.5, y / 4.5 + 9.0, z / 4.5);
        return y >= g && y < g + hh;
    }

    /** exits._outlet_contacts: the outlet's blocks (rows g..g+10) that are the Wasteland's air or touch it. */
    private Hit outletContacts(Slab w, double[][] cur, int g) {
        Hit h = new Hit();
        double mnx = Double.MAX_VALUE, mxx = -Double.MAX_VALUE, mnz = Double.MAX_VALUE, mxz = -Double.MAX_VALUE;
        for (double[] c : cur) {
            mnx = Math.min(mnx, c[0]);
            mxx = Math.max(mxx, c[0]);
            mnz = Math.min(mnz, c[1]);
            mxz = Math.max(mxz, c[1]);
        }
        int bx0 = (int) Math.floor(mnx) - 6, bx1 = (int) Math.floor(mxx) + 7, bz0 = (int) Math.floor(mnz) - 6, bz1 = (int) Math.floor(mxz) + 7;
        int nx = bx1 - bx0, nz = bz1 - bz0;
        long rows = ((1L << 11) - 1) << (g + E_OFF);
        long[] mask = new long[nx * nz];
        for (int j = 1; j < cur.length; j++) mark(mask, bx0, bz0, nx, nz, cur[j][0], cur[j][1], cur[j][3] / 2 + 2.5, rows);
        double[] h0 = new double[1];
        for (int a = 0; a < nx; a++)
            for (int c = 0; c < nz; c++) {
                long m = mask[a * nz + c];
                if (m == 0) continue;
                int fx = bx0 + a, fz = bz0 + c, x = fx + gv.ox, z = fz + gv.oz;
                m &= w.touch(x, z);
                if (m == 0) continue;
                int jb = outletColumn(cur, gv.ptube, fx, fz, x, z, h0);
                if (jb < 0) continue;
                while (m != 0) {
                    int e = Long.numberOfTrailingZeros(m);
                    m &= m - 1;
                    int y = e - E_OFF;
                    if (outletRow(gv.ptube, h0[0], g, x, y, z)) h.add(jb, x, y, z);
                }
            }
        return h;
    }

    // ================================================================================================ the walk-out bars

    /**
     * exits.validate: (side-drop spots, least rock between turns) for a candidate with its final table and outlet, on a
     * box of the slab (and the solid band over it) with the candidate carved in.
     */
    private int[] validate(Slab w, Site s, double[][] tab, double[][] cur, int g) {
        double pad = s.Rh + 12 + GardensVents.DRIFT_MAX + 1;
        double mnx = s.cx - pad, mxx = s.cx + pad, mnz = s.cz - pad, mxz = s.cz + pad;
        boolean outlet = cur != null && cur.length > 1;
        if (outlet)
            for (double[] c : cur) {
                mnx = Math.min(mnx, c[0] - 8);
                mxx = Math.max(mxx, c[0] + 8);
                mnz = Math.min(mnz, c[1] - 8);
                mxz = Math.max(mxz, c[1] + 8);
            }
        int bx0 = (int) Math.floor(mnx), bx1 = (int) Math.floor(mxx) + 1, bz0 = (int) Math.floor(mnz), bz1 = (int) Math.floor(mxz) + 1;
        int e1 = Math.min(SLAB + 16, g + E_OFF + 48);
        int nx = bx1 - bx0, nz = bz1 - bz0, ne = e1;
        boolean[] thr = new boolean[nx * ne * nz];
        boolean[] cand = new boolean[nx * ne * nz];
        // the throat's candidate blocks near its centre line, then the exact rule on each
        double rh = s.Rh;
        for (int i = 0; i < tab.length; i++) {
            int yb = (int) Math.floor(tab[i][0] + 0.5);
            int lo = Math.max(yb, -E_OFF), hi = Math.min(yb + 9, e1 - 1 - E_OFF);
            if (lo > hi) continue;
            double psi = s.phiP + s.d * (i * DS) / tab[0][1];
            double px = s.cx + tab[i][4] + tab[i][1] * Math.cos(psi), pz = s.cz + tab[i][5] + tab[i][1] * Math.sin(psi);
            markBox(cand, bx0, bz0, nx, ne, nz, px, pz, tab[i][2] / 2 + 3.0, lo + E_OFF, hi + E_OFF);
        }
        if (outlet)
            for (int j = 1; j < cur.length; j++)
                markBox(cand, bx0, bz0, nx, ne, nz, cur[j][0], cur[j][1], cur[j][3] / 2 + 2.5, Math.max(0, g + E_OFF), Math.min(ne - 1, g + 10 + E_OFF));
        double[] h0 = new double[1];
        for (int a = 0; a < nx; a++)
            for (int c = 0; c < nz; c++) {
                int fx = bx0 + a, fz = bz0 + c, x = fx + gv.ox, z = fz + gv.oz;
                int jb = -2;
                for (int b = 0; b < ne; b++) {
                    int q = (a * ne + b) * nz + c;
                    if (!cand[q]) continue;
                    int y = b - E_OFF;
                    boolean in = gv.tubeHit(tab, s.cx, s.cz, s.phiP, s.d, tab[0][1], x, y, z) >= 0;
                    if (!in && outlet && y >= g && y <= g + 10) {
                        if (jb == -2) jb = outletColumn(cur, gv.ptube, fx, fz, x, z, h0);
                        in = jb >= 0 && outletRow(gv.ptube, h0[0], g, x, y, z);
                    }
                    thr[q] = in;
                }
            }
        // least rock between turns: per column, between consecutive runs of throat
        int gap = 999;
        for (int a = 0; a < nx; a++)
            for (int c = 0; c < nz; c++) {
                int lastTop = -1, b = 0;
                while (b < ne) {
                    if (thr[(a * ne + b) * nz + c]) {
                        int st = b;
                        while (b < ne && thr[(a * ne + b) * nz + c]) b++;
                        if (lastTop >= 0) gap = Math.min(gap, st - lastTop - 1);
                        lastTop = b - 1;
                    } else b++;
                }
            }
        // side drops: a throat spot of the level run or outlet with a walk-off drop of 2 or more beside it
        int side = 0;
        for (int a = 0; a < nx; a++)
            for (int c = 0; c < nz; c++)
                for (int b = 1; b <= Math.min(ne - 3, g + 2 + E_OFF); b++) {
                    if (!thr[(a * ne + b) * nz + c]) continue;
                    if (!solid(w, thr, bx0, bz0, nx, ne, nz, a, b - 1, c) || solid(w, thr, bx0, bz0, nx, ne, nz, a, b + 1, c)) continue;
                    if (dropBeside(w, thr, bx0, bz0, nx, ne, nz, a, b, c)) side++;
                }
        return new int[]{side, gap};
    }

    /** Marks rows lo..hi (box indices) of every column whose middle lies within r of (px, pz). */
    private static void markBox(boolean[] cand, int bx0, int bz0, int nx, int ne, int nz, double px, double pz, double r, int lo, int hi) {
        int a0 = Math.max(0, (int) Math.floor(px - r) - bx0), a1 = Math.min(nx - 1, (int) Math.floor(px + r) - bx0);
        int c0 = Math.max(0, (int) Math.floor(pz - r) - bz0), c1 = Math.min(nz - 1, (int) Math.floor(pz + r) - bz0);
        double r2 = r * r;
        for (int a = a0; a <= a1; a++) {
            double dx = bx0 + a + 0.5 - px;
            for (int c = c0; c <= c1; c++) {
                double dz = bz0 + c + 0.5 - pz;
                if (dx * dx + dz * dz > r2) continue;
                for (int b = lo; b <= hi; b++) cand[(a * ne + b) * nz + c] = true;
            }
        }
    }

    private boolean solid(Slab w, boolean[] thr, int bx0, int bz0, int nx, int ne, int nz, int a, int b, int c) {
        if (thr[(a * ne + b) * nz + c]) return false;
        return w.solidRaw(bx0 + a + gv.ox, b, bz0 + c + gv.oz);
    }

    /** walk.neighbours' walk-off moves from a standing spot: whether any drops 2 or more (at most 60). */
    private boolean dropBeside(Slab w, boolean[] thr, int bx0, int bz0, int nx, int ne, int nz, int a, int b, int c) {
        for (int[] dir : DIRS) {
            int dx = dir[0], dz = dir[1], na = a + dx, nc = c + dz;
            if (na < 0 || nc < 0 || na >= nx || nc >= nz) continue;
            if (b + 1 >= ne || solid(w, thr, bx0, bz0, nx, ne, nz, na, b, nc) || solid(w, thr, bx0, bz0, nx, ne, nz, na, b + 1, nc)) continue;
            if (dx != 0 && dz != 0 && (solid(w, thr, bx0, bz0, nx, ne, nz, a + dx, b, c) || solid(w, thr, bx0, bz0, nx, ne, nz, a + dx, b + 1, c)
                    || solid(w, thr, bx0, bz0, nx, ne, nz, a, b, c + dz) || solid(w, thr, bx0, bz0, nx, ne, nz, a, b + 1, c + dz))) continue;
            int ny = b;
            while (ny >= 1 && !solid(w, thr, bx0, bz0, nx, ne, nz, na, ny - 1, nc)) {
                ny--;
                if (b - ny > 60) break;
            }
            if (ny < 1 || b - ny > 60) continue;
            if (ny - b <= -2) return true;
        }
        return false;
    }
}
