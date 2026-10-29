package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Long views in the Blue Reaches' deep dark. Rays from walkable spots at pitches up to 40 degrees must meet rock within
 * 160 blocks, and Taran keeps 0.2.0's caverns at their size, so the views are cut with the deep dark's own shape, 0.2.0's
 * hourglass pillar: round ones, and fins, hourglass pillars drawn out along a short segment, which stand across the
 * lines they cut.
 * <p>
 * The test is two-dimensional: a raster of where a tier is open is swept with families of straight lines, and every
 * free run (open raster cells, no pillar or fin across it) longer than {@link #LMAX} needs a new one on it. They are
 * chosen greedily from candidates, each time the one that cuts the most long runs for its footprint, until no long run
 * is left or none of the rest crosses a candidate. A ray of 160 blocks at a pitch up to 20 degrees runs at least 150
 * blocks across the map, so 140 leaves ten for the raster; steeper rays meet the tier's floor or ceiling first (a tier is
 * at most 54 tall), except where the rift opens over it, which the planner marks open for both tiers.
 * <p>
 * A shape cuts a sampled line when the line passes within its waist (0.75 r, the narrowest part of the hourglass) less
 * {@code shrink}, the farthest a real ray strays from the nearest sampled line over a run, so the rays between the
 * sampled lines are cut too.
 */
final class NetSight {
    /** Longest free run (blocks) a sampled line may keep. */
    static final double LMAX = 140;

    private NetSight() {}

    /** Where a tier is open, at {@code g} blocks a raster cell: see[i * nz + k] != 0. */
    static final class Raster {
        final double x0, z0, g;
        final int nx, nz;
        final byte[] see;

        Raster(double x0, double z0, double g, int nx, int nz) {
            this.x0 = x0;
            this.z0 = z0;
            this.g = g;
            this.nx = nx;
            this.nz = nz;
            see = new byte[nx * nz];
        }

        double px(int i) { return x0 + g * (i + 0.5); }
        double pz(int k) { return z0 + g * (k + 0.5); }
    }

    /**
     * Hourglass pillars and fins: segment (ax, az)-(bx, bz) (a point for a round pillar) and the hourglass radius r
     * (waist 0.75 r, foot 1.65 r round the segment).
     */
    static final class Shapes {
        double[] ax = new double[16], az = new double[16], bx = new double[16], bz = new double[16], r = new double[16];
        int n;

        int add(double ax, double az, double bx, double bz, double r) {
            if (n == this.ax.length) {
                int m = n * 2;
                this.ax = Arrays.copyOf(this.ax, m); this.az = Arrays.copyOf(this.az, m);
                this.bx = Arrays.copyOf(this.bx, m); this.bz = Arrays.copyOf(this.bz, m); this.r = Arrays.copyOf(this.r, m);
            }
            this.ax[n] = ax; this.az[n] = az; this.bx[n] = bx; this.bz[n] = bz; this.r[n] = r;
            return n++;
        }

        int add(double[] s) { return add(s[0], s[1], s[2], s[3], s[4]); }

        double[] get(int i) { return new double[]{ax[i], az[i], bx[i], bz[i], r[i]}; }

        /** The footprint on the floor (1.65 r round the segment). */
        double area(int i) {
            double f = 1.65 * r[i];
            return Math.hypot(bx[i] - ax[i], bz[i] - az[i]) * 2 * f + Math.PI * f * f;
        }

        /** Distance from (x, z) to shape i's segment. */
        double dist(int i, double x, double z) { return segDist(x, z, ax[i], az[i], bx[i], bz[i]); }
    }

    /** Distance from (x, z) to the segment (ax, az)-(bx, bz). */
    static double segDist(double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        double t = l2 < 1e-12 ? 0 : ((x - ax) * dx + (z - az) * dz) / l2;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        double ex = x - ax - dx * t, ez = z - az - dz * t;
        return Math.sqrt(ex * ex + ez * ez);
    }

    /** Distance between two segments. */
    static double segSeg(double ax, double az, double bx, double bz, double cx, double cz, double dx, double dz) {
        double ux = bx - ax, uz = bz - az, vx = dx - cx, vz = dz - cz;
        double den = ux * vz - uz * vx;
        if (Math.abs(den) > 1e-12) {
            double s = ((cx - ax) * vz - (cz - az) * vx) / den, t = ((cx - ax) * uz - (cz - az) * ux) / den;
            if (s >= 0 && s <= 1 && t >= 0 && t <= 1) return 0;
        }
        return Math.min(Math.min(segDist(ax, az, cx, cz, dx, dz), segDist(bx, bz, cx, cz, dx, dz)),
                Math.min(segDist(cx, cz, ax, az, bx, bz), segDist(dx, dz, ax, az, bx, bz)));
    }

    /**
     * Distance (blocks) from each cell of an nx x nz raster of g blocks a cell to the nearest cell with obst != 0: a
     * two-pass chamfer with weights g and g * sqrt(2); 0 on the obstacles, and far when there are none.
     */
    static float[] distanceTo(byte[] obst, int nx, int nz, double g) {
        float[] d = new float[nx * nz];
        float far = 1e6f, a = (float) g, b = (float) (g * Math.sqrt(2));
        for (int c = 0; c < d.length; c++) d[c] = obst[c] != 0 ? 0 : far;
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            int c = i * nz + k;
            if (d[c] == 0) continue;
            float m = d[c];
            if (i > 0) {
                m = Math.min(m, d[c - nz] + a);
                if (k > 0) m = Math.min(m, d[c - nz - 1] + b);
                if (k < nz - 1) m = Math.min(m, d[c - nz + 1] + b);
            }
            if (k > 0) m = Math.min(m, d[c - 1] + a);
            d[c] = m;
        }
        for (int i = nx - 1; i >= 0; i--) for (int k = nz - 1; k >= 0; k--) {
            int c = i * nz + k;
            if (d[c] == 0) continue;
            float m = d[c];
            if (i < nx - 1) {
                m = Math.min(m, d[c + nz] + a);
                if (k > 0) m = Math.min(m, d[c + nz - 1] + b);
                if (k < nz - 1) m = Math.min(m, d[c + nz + 1] + b);
            }
            if (k < nz - 1) m = Math.min(m, d[c + 1] + a);
            d[c] = m;
        }
        return d;
    }

    /** What a run of the breaker found and did. */
    static final class Stats {
        /** leftIn: runs left with at least 30 blocks inside the focus box. */
        int lines, longRuns, candidates, placed, fins, leftRuns, leftIn;
        /** area: the chosen shapes' footprints; open: the open floor they stand among (both square blocks). */
        double leftMax, area, open;

        void add(Stats o) {
            lines += o.lines; longRuns += o.longRuns; candidates += o.candidates; placed += o.placed; fins += o.fins;
            leftRuns += o.leftRuns; leftIn += o.leftIn;
            leftMax = Math.max(leftMax, o.leftMax);
            area += o.area;
            open += o.open;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "%d lines, %d long runs, %d candidates, %d placed (%d fins, footprint %.0f = %.1f%% of %.0f open), %d runs left (%d with 30 inside, longest %.0f)",
                    lines, longRuns, candidates, placed, fins, area, open > 0 ? 100 * area / open : 0, open, leftRuns, leftIn, leftMax);
        }
    }

    /** One long free run of a line: the line (direction index, offset) and its span along the line. */
    private static final class Run {
        int dir;
        double o, sa, sb;
        /** Where the line crosses the focus box. */
        double fa, fb;
        int[] cand;
        int nc;
        boolean active = true;

        void addCand(int s) {
            if (cand == null) cand = new int[8];
            else if (nc == cand.length) cand = Arrays.copyOf(cand, nc * 2);
            cand[nc++] = s;
        }
    }

    /**
     * The shapes that may cut each line of one direction, in compressed rows: line j (offset j * off along the
     * direction's left normal from the reference point) lists the shapes whose blocking reach covers its offset.
     */
    private static final class Lines {
        final int j0;
        final int[] start, items;

        Lines(Shapes s, double[] rb, boolean[] dead, double cx, double cz, double nx, double nz, double off, int j0, int j1) {
            this.j0 = j0;
            int n = s.n, nl = j1 - j0 + 1;
            int[] ja = new int[n], jb = new int[n];
            int[] cnt = new int[nl + 1];
            for (int i = 0; i < n; i++) {
                ja[i] = 1;
                jb[i] = 0;
                if (rb[i] <= 0 || (dead != null && dead[i])) continue;
                double qa = (s.ax[i] - cx) * nx + (s.az[i] - cz) * nz, qb = (s.bx[i] - cx) * nx + (s.bz[i] - cz) * nz;
                int a = Math.max(j0, (int) Math.ceil((Math.min(qa, qb) - rb[i]) / off));
                int b = Math.min(j1, (int) Math.floor((Math.max(qa, qb) + rb[i]) / off));
                if (a > b) continue;
                ja[i] = a;
                jb[i] = b;
                for (int j = a; j <= b; j++) cnt[j - j0 + 1]++;
            }
            for (int j = 0; j < nl; j++) cnt[j + 1] += cnt[j];
            start = cnt;
            items = new int[cnt[nl]];
            int[] cur = Arrays.copyOf(cnt, nl);
            for (int i = 0; i < n; i++) for (int j = ja[i]; j <= jb[i]; j++) items[cur[j - j0]++] = i;
        }
    }

    /**
     * Where shape i cuts the line (cx, cz) + o (-dz, dx) + s (dx, dz): the arc length s of the cut, or NaN when the line
     * passes rb or farther from the shape's segment; out[0] gets half the length of line blocked there.
     */
    static double cut(Shapes sh, int i, double rb, double cx, double cz, double dx, double dz, double o, double[] out) {
        if (rb <= 0) return Double.NaN;
        double ax = sh.ax[i] - cx, az = sh.az[i] - cz, bx = sh.bx[i] - cx, bz = sh.bz[i] - cz;
        double da = -ax * dz + az * dx - o, db = -bx * dz + bz * dx - o;
        double sa = ax * dx + az * dz, sb = bx * dx + bz * dz;
        if (da * db <= 0) {
            // the line crosses the segment
            double t = Math.abs(da - db) < 1e-12 ? 0.5 : da / (da - db);
            out[0] = rb;
            return sa + (sb - sa) * t;
        }
        double d = Math.min(Math.abs(da), Math.abs(db));
        if (d >= rb) return Double.NaN;
        out[0] = Math.sqrt(rb * rb - d * d);
        return Math.abs(da) < Math.abs(db) ? sa : sb;
    }

    /**
     * Chooses shapes among the candidates so that no sampled line keeps a free run longer than {@code lmax}. The lines
     * run in the directions {@code ang}, {@code off} blocks apart, and only those crossing the focus box (the area the
     * candidates belong to) are swept, and on them only the runs that reach into it.
     *
     * @param ras    where the tier is open
     * @param ang    line directions (radians)
     * @param cx     reference point of the line offsets
     * @param off    spacing of the lines
     * @param focus  {x0, z0, x1, z1}: the box the candidates lie in
     * @param blk    the pillars already there
     * @param cand   the candidates
     * @param shrink the farthest a ray strays from the nearest sampled line where it is cut: a shape blocks a line
     *               within 0.75 r - shrink of its segment
     * @param gap    two chosen shapes' segments stay at least gap times the sum of their feet (1.65 r) apart
     * @return the chosen candidates, in the order chosen
     */
    static int[] choose(Raster ras, double[] ang, double cx, double cz, double off, double[] focus,
                        Shapes blk, Shapes cand, double lmax, double shrink, double gap, Stats st) {
        int nd = ang.length, nc = cand.n;
        double g = ras.g, inv = 1 / g;
        double X0 = ras.x0, Z0 = ras.z0, X1 = ras.x0 + ras.nx * g, Z1 = ras.z0 + ras.nz * g;
        int rnx = ras.nx, rnz = ras.nz;
        byte[] see = ras.see;
        double[] brb = new double[blk.n], crb = new double[nc], cost = new double[nc];
        for (int b = 0; b < blk.n; b++) brb[b] = 0.75 * blk.r[b] - shrink;
        for (int c = 0; c < nc; c++) { crb[c] = 0.75 * cand.r[c] - shrink; cost[c] = cand.area(c); }
        boolean[] dead = new boolean[nc];
        for (int c = 0; c < nc; c++) if (crb[c] <= 0.25) dead[c] = true; else st.candidates++;
        List<Run> runs = new ArrayList<>();
        int[] count = new int[nc];
        int[][] candRuns = new int[nc][];
        int[] candRunN = new int[nc];
        double[] tmpI = new double[64], half = new double[1], clip = new double[2];
        for (int a = 0; a < nd; a++) {
            double dx = Math.cos(ang[a]), dz = Math.sin(ang[a]), nx = -dz, nz = dx;
            double q0 = Double.MAX_VALUE, q1 = -Double.MAX_VALUE;
            for (int m = 0; m < 4; m++) {
                double q = (focus[(m & 1) == 0 ? 0 : 2] - cx) * nx + (focus[(m & 2) == 0 ? 1 : 3] - cz) * nz;
                q0 = Math.min(q0, q);
                q1 = Math.max(q1, q);
            }
            int j0 = (int) Math.ceil(q0 / off), j1 = (int) Math.floor(q1 / off);
            if (j0 > j1) continue;
            Lines bl = new Lines(blk, brb, null, cx, cz, nx, nz, off, j0, j1);
            Lines cl = new Lines(cand, crb, dead, cx, cz, nx, nz, off, j0, j1);
            for (int j = j0; j <= j1; j++) {
                double o = j * off;
                double ox = cx + o * nx, oz = cz + o * nz;
                if (!clipLine(ox, oz, dx, dz, focus[0], focus[1], focus[2], focus[3], clip)) continue;
                double flo = clip[0], fhi = clip[1];
                if (!clipLine(ox, oz, dx, dz, X0, Z0, X1, Z1, clip)) continue;
                double lo = clip[0], hi = clip[1];
                if (hi - lo < lmax) continue;
                st.lines++;
                int row = j - j0;
                int nS = (int) ((hi - lo) * inv);
                double fi = (ox + (lo + 0.5 * g) * dx - X0) * inv, fk = (oz + (lo + 0.5 * g) * dz - Z0) * inv;
                int start = -1;
                for (int s = 0; s <= nS; s++) {
                    boolean open = false;
                    if (s < nS) {
                        int i = (int) fi, k = (int) fk;
                        open = i >= 0 && k >= 0 && i < rnx && k < rnz && see[i * rnz + k] != 0;
                        fi += dx;
                        fk += dz;
                    }
                    if (open) { if (start < 0) start = s; continue; }
                    if (start < 0) continue;
                    double runA = lo + start * g, runB = lo + s * g;
                    start = -1;
                    if (runB - runA < lmax || runB < flo || runA > fhi) continue;
                    // the pillars already there cut the run into pieces
                    int nI = 0;
                    for (int q = bl.start[row]; q < bl.start[row + 1]; q++) {
                        int b = bl.items[q];
                        double sc = cut(blk, b, brb[b], cx, cz, dx, dz, o, half);
                        if (Double.isNaN(sc) || sc + half[0] < runA || sc - half[0] > runB) continue;
                        if (nI + 2 > tmpI.length) tmpI = Arrays.copyOf(tmpI, tmpI.length * 2);
                        tmpI[nI++] = sc - half[0];
                        tmpI[nI++] = sc + half[0];
                    }
                    for (double[] piece : pieces(runA, runB, tmpI, nI)) {
                        if (piece[1] - piece[0] < lmax || piece[1] < flo || piece[0] > fhi) continue;
                        Run r = new Run();
                        r.dir = a; r.o = o; r.sa = piece[0]; r.sb = piece[1]; r.fa = flo; r.fb = fhi;
                        for (int q = cl.start[row]; q < cl.start[row + 1]; q++) {
                            int c = cl.items[q];
                            double sc = cut(cand, c, crb[c], cx, cz, dx, dz, o, half);
                            if (Double.isNaN(sc) || sc < r.sa || sc > r.sb) continue;
                            r.addCand(c);
                        }
                        runs.add(r);
                        st.longRuns++;
                        int ri = runs.size() - 1;
                        for (int m = 0; m < r.nc; m++) {
                            int c = r.cand[m];
                            count[c]++;
                            candRuns[c] = push(candRuns[c], candRunN[c]++, ri);
                        }
                    }
                }
            }
        }
        // greedy: the candidate that cuts the most runs for its footprint, again and again
        int[] chosen = new int[16];
        int nChosen = 0;
        while (true) {
            int best = -1;
            double bestScore = 0;
            for (int c = 0; c < nc; c++) {
                if (dead[c] || count[c] == 0) continue;
                double sc = count[c] / cost[c];
                if (sc > bestScore) { bestScore = sc; best = c; }
            }
            if (best < 0) break;
            if (nChosen == chosen.length) chosen = Arrays.copyOf(chosen, nChosen * 2);
            chosen[nChosen++] = best;
            dead[best] = true;
            st.area += cost[best];
            if (cand.ax[best] != cand.bx[best] || cand.az[best] != cand.bz[best]) st.fins++;
            // candidates too close to the new one go
            double bxa = Math.min(cand.ax[best], cand.bx[best]), bxb = Math.max(cand.ax[best], cand.bx[best]);
            for (int c = 0; c < nc; c++) {
                if (dead[c]) continue;
                double lim = gap * 1.65 * (cand.r[c] + cand.r[best]);
                if (Math.min(cand.ax[c], cand.bx[c]) > bxb + lim || Math.max(cand.ax[c], cand.bx[c]) < bxa - lim) continue;
                if (segSeg(cand.ax[c], cand.az[c], cand.bx[c], cand.bz[c], cand.ax[best], cand.az[best], cand.bx[best], cand.bz[best]) < lim) dead[c] = true;
            }
            int[] its = candRuns[best];
            int nIts = candRunN[best];
            for (int m = 0; m < nIts; m++) {
                Run r = runs.get(its[m]);
                if (!r.active) continue;
                r.active = false;
                for (int q = 0; q < r.nc; q++) count[r.cand[q]]--;
                double dx = Math.cos(ang[r.dir]), dz = Math.sin(ang[r.dir]);
                double at = cut(cand, best, crb[best], cx, cz, dx, dz, r.o, half);
                double h = half[0];
                double[][] parts = {{r.sa, at - h}, {at + h, r.sb}};
                for (double[] part : parts) {
                    if (part[1] - part[0] < lmax || part[1] < r.fa || part[0] > r.fb) continue;
                    Run nr = new Run();
                    nr.dir = r.dir; nr.o = r.o; nr.sa = part[0]; nr.sb = part[1]; nr.fa = r.fa; nr.fb = r.fb;
                    for (int q = 0; q < r.nc; q++) {
                        int c = r.cand[q];
                        if (dead[c]) continue;
                        double sc = cut(cand, c, crb[c], cx, cz, dx, dz, r.o, half);
                        if (Double.isNaN(sc) || sc < part[0] || sc > part[1]) continue;
                        nr.addCand(c);
                    }
                    runs.add(nr);
                    int ri = runs.size() - 1;
                    for (int q = 0; q < nr.nc; q++) {
                        int c = nr.cand[q];
                        count[c]++;
                        candRuns[c] = push(candRuns[c], candRunN[c]++, ri);
                    }
                    // a piece no candidate can cut stays, and is counted below
                }
            }
        }
        // what is left has no live candidate on it
        for (Run r : runs) {
            if (!r.active) continue;
            st.leftRuns++;
            st.leftMax = Math.max(st.leftMax, r.sb - r.sa);
            if (Math.min(r.sb, r.fb) - Math.max(r.sa, r.fa) >= 30) st.leftIn++;
        }
        st.placed += nChosen;
        return Arrays.copyOf(chosen, nChosen);
    }

    /** Clips the line (ox, oz) + s (dx, dz) to a box: out = {s in, s out}; false when it misses the box. */
    static boolean clipLine(double ox, double oz, double dx, double dz, double xa, double za, double xb, double zb, double[] out) {
        double lo = -1e18, hi = 1e18;
        if (Math.abs(dx) < 1e-12) { if (ox < xa || ox >= xb) return false; }
        else { double t1 = (xa - ox) / dx, t2 = (xb - ox) / dx; lo = Math.max(lo, Math.min(t1, t2)); hi = Math.min(hi, Math.max(t1, t2)); }
        if (Math.abs(dz) < 1e-12) { if (oz < za || oz >= zb) return false; }
        else { double t1 = (za - oz) / dz, t2 = (zb - oz) / dz; lo = Math.max(lo, Math.min(t1, t2)); hi = Math.min(hi, Math.max(t1, t2)); }
        out[0] = lo;
        out[1] = hi;
        return hi > lo;
    }

    /** The parts of [a, b] outside the intervals iv[0..n) (pairs). */
    private static List<double[]> pieces(double a, double b, double[] iv, int n) {
        List<double[]> out = new ArrayList<>();
        if (n == 0) { out.add(new double[]{a, b}); return out; }
        int m = n / 2;
        double[][] sorted = new double[m][];
        for (int i = 0; i < m; i++) sorted[i] = new double[]{iv[2 * i], iv[2 * i + 1]};
        Arrays.sort(sorted, (p, q) -> Double.compare(p[0], q[0]));
        double cur = a;
        for (double[] s : sorted) {
            if (s[0] > cur) out.add(new double[]{cur, Math.min(s[0], b)});
            cur = Math.max(cur, s[1]);
            if (cur >= b) break;
        }
        if (cur < b) out.add(new double[]{cur, b});
        return out;
    }

    private static int[] push(int[] a, int n, int v) {
        if (a == null) a = new int[4];
        else if (n == a.length) a = Arrays.copyOf(a, n * 2);
        a[n] = v;
        return a;
    }
}
