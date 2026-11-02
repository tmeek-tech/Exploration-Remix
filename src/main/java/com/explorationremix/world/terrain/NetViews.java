package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.Kind;
import com.explorationremix.world.terrain.Net.Way;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The Basalt Galleries' views through their tunnels, kept under the render distance while the plan is made. The halls
 * are 80 to 150 across and meant to be seen whole; what runs past 160 blocks (a sky hole under the shader) is a view
 * that leaves a hall through a tunnel: across a hall and on down a tunnel whose mouth lines up with it, from one tunnel
 * across a hall into another on the far side, or through a tunnel that is nearly straight from one hall to the next.
 * <p>
 * The open space planned so far is kept on a raster one block a cell, each cell with the span of heights open there
 * (from the lowest floor to the highest ceiling of the rooms and tunnels over it, lava counting as open, since a view
 * runs over it): a room's floor and ceiling as the blocks will have them, a tunnel's vault at that distance from its
 * line. A tunnel about to be embedded is tested with the straight lines through a few cross-sections of it (its two
 * mouths, where it leaves its rooms, and every {@link #WINDOW_STEP} blocks between): each line is followed both ways
 * over the raster while it is open, and then the longest stretch of it that a straight ray at some pitch (up to
 * {@link #PITCH_MAX}) runs along inside the open spans is its view; none may be longer than {@link #LIMIT}. Every long
 * view through a tunnel crosses one of these sections, so once each tunnel passes when it is made (against everything
 * made before it, which every later tunnel is checked against in turn), no view through any tunnel is too long. A
 * tunnel's floor climbs and falls and its vault narrows to the walls: a flat test (each tunnel as wide as it is
 * anywhere and as tall) would find views of 200 to 370 blocks in cells where none passes 150.
 */
final class NetViews {
    /**
     * The longest straight view (blocks) a line through a tunnel may give: under the 160 past which the shader shows
     * the sky, by what the raster cannot see (the walls' roughness, the 4 x 8 x 4 interpolation of the blocks).
     */
    static final double LIMIT = 155;
    /** Blocks between a tunnel's cross-sections tested between its mouths. */
    static final double WINDOW_STEP = 20;
    /**
     * The lines through each cross-section: offsets across it (shares of the half-width) and angles off its axis, one
     * step apart near the axis and two beyond {@link #FINE_ANGLE}. A mouth's lines fan out across the room it opens
     * on; between the mouths a line far off the axis meets the tunnel's wall within a few blocks.
     */
    static final double[] MOUTH_OFFSETS = {-0.75, -0.38, 0, 0.38, 0.75}, MID_OFFSETS = {-0.6, 0, 0.6};
    static final double MOUTH_ANGLE = Math.toRadians(70), MID_ANGLE = Math.toRadians(36), FINE_ANGLE = Math.toRadians(40);
    static final double ANGLE_STEP = Math.toRadians(2);
    /** The steepest ray tested, and the step between pitches (a view up or down a hall's height is no tunnel's). */
    static final double PITCH_MAX = Math.toRadians(25), PITCH_STEP = Math.toRadians(5);
    /**
     * A tunnel counts as open out to this share of its half-width, with the span of its vault at {@link #VAULT_EDGE}
     * of it from there out. Its blocks are open out to 0.9 of the half-width at eye height in the middle of their
     * spread, but the walls' roughness takes them from 0.65 to 1.3 of it, and a view grazing a tunnel's walls through
     * a short bend can run 170 blocks from hall to hall.
     */
    static final double VAULT_Q = 1.15, VAULT_EDGE = 0.9;
    /** Under this much head room a cell is not open. */
    static final double MIN_SPAN = 1.0;
    /** The most cells a line is followed through each way. */
    private static final int MAX_CELLS = 1024;

    private final Network w;
    /** The raster: its corner in world blocks and its side; per cell how many spaces cover it and their span. */
    private final int x0, z0, size;
    private final short[] cnt;
    private final float[] lo, hi;
    /** Each space's cells and spans (computed once), and the spaces counted in the raster now. */
    private final IdentityHashMap<Object, Space> spaces = new IdentityHashMap<>();
    private final Set<Object> on = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * The last way tested and not counted, and its space: kept for when it is laid, not with the rest (most are not).
     */
    private Way tried;
    private Space triedSpace;
    /**
     * Spaces that are no part of the plan's rooms or ways but open all the same (the lava basin, the far side of each
     * portal).
     */
    private final List<Object> extra = new ArrayList<>();
    /** Scratch for one line: per cell along it, where the line leaves it, the middle of its stretch and its span. */
    private final double[] ft = new double[MAX_CELLS], fl = new double[MAX_CELLS], fh = new double[MAX_CELLS], fm = new double[MAX_CELLS];
    private final double[] bt = new double[MAX_CELLS], bl = new double[MAX_CELLS], bh = new double[MAX_CELLS], bm = new double[MAX_CELLS];
    private final double[] fLo = new double[MAX_CELLS], fHi = new double[MAX_CELLS], bLo = new double[MAX_CELLS], bHi = new double[MAX_CELLS];
    private final double[] slopes;

    /** One space's cells and the span of heights open in each, and the shape they were made for ({@link #shape}). */
    private record Space(int[] cells, float[] lo, float[] hi, long shape) {}

    NetViews(Network w, int x0, int z0, int size) {
        this.w = w;
        this.x0 = x0;
        this.z0 = z0;
        this.size = size;
        cnt = new short[size * size];
        lo = new float[size * size];
        hi = new float[size * size];
        int np = (int) Math.round(PITCH_MAX / PITCH_STEP);
        slopes = new double[2 * np + 1];
        // level first: most long views are
        for (int i = 0; i <= 2 * np; i++) slopes[i] = Math.tan(((i + 1) / 2) * (i % 2 == 0 ? 1 : -1) * PITCH_STEP);
    }

    /** Adds a room or a way that stands for open space but is not in the plan's lists. */
    void addExtra(Object o) { extra.add(o); }

    /**
     * Counts every kept room, every way but the rooms' inner walks, and the extras, and no longer counts what has gone.
     */
    void sync(List<Chamber> rooms, List<Way> ways) {
        Set<Object> want = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Chamber c : rooms) if (c.keep) want.add(c);
        for (Way wy : ways) if (!wy.inner) want.add(wy);
        want.addAll(extra);
        boolean gone = false;
        for (Object o : new ArrayList<>(on)) if (!want.contains(o) || spaces.get(o).shape != shape(o)) { on.remove(o); gone = true; }
        if (gone) {
            // a space taken away: the spans are made again from what is left
            Arrays.fill(cnt, (short) 0);
            for (Object o : on) add(space(o));
        }
        for (Object o : want) if (on.add(o)) add(space(o));
    }

    private void add(Space s) {
        for (int j = 0; j < s.cells.length; j++) {
            int i = s.cells[j];
            if (cnt[i] == 0) { lo[i] = s.lo[j]; hi[i] = s.hi[j]; }
            else { lo[i] = Math.min(lo[i], s.lo[j]); hi[i] = Math.max(hi[i], s.hi[j]); }
            cnt[i]++;
        }
    }

    private Space space(Object o) {
        Space s = spaces.get(o);
        long sh = shape(o);
        if (s == null || s.shape != sh) {
            s = o == tried && triedSpace.shape == sh ? triedSpace : o instanceof Chamber ch ? roomSpace(ch, sh) : waySpace((Way) o, sh);
            spaces.put(o, s);
        }
        return s;
    }

    /**
     * What a space's cells follow from (a room's place, size and heights, a way's ends and points), so a change is
     * seen.
     */
    private static long shape(Object o) {
        long h = 17;
        if (o instanceof Chamber c) {
            for (double v : new double[]{c.x, c.z, c.rx, c.rz, c.rot, c.f0, c.h, c.base, c.rise, c.pillars.length}) h = h * 31 + Double.hashCode(v);
        } else {
            Way wy = (Way) o;
            h = h * 31 + wy.n;
            for (int i = 0; i < wy.n; i += Math.max(1, wy.n / 8)) h = (h * 31 + Double.hashCode(wy.x[i])) * 31 + Double.hashCode(wy.f[i]);
            h = (h * 31 + Double.hashCode(wy.x[wy.n - 1])) * 31 + Double.hashCode(wy.z[wy.n - 1]);
        }
        return h;
    }

    /**
     * A room's cells: where its footprint is under 1, less a column hall's columns, each with the room's floor and
     * ceiling there. The footprint, floor and ceiling are read on a lattice two blocks apart, and the footprint block
     * by block where the lattice changes (with the span of a lattice point inside), as a hall is up to 150 across.
     */
    private Space roomSpace(Chamber c, long shape) {
        double R = c.R() + 2;
        int ia = Math.max(0, (int) Math.floor(c.x - R) - x0), ib = Math.min(size - 1, (int) Math.ceil(c.x + R) - x0);
        int ka = Math.max(0, (int) Math.floor(c.z - R) - z0), kb = Math.min(size - 1, (int) Math.ceil(c.z + R) - z0);
        if (ia > ib || ka > kb) return new Space(new int[0], new float[0], new float[0], shape);
        int ni = (ib - ia) / 2 + 2, nk = (kb - ka) / 2 + 2;
        double[] ee = new double[ni * nk];
        float[] lf = new float[ni * nk], lc = new float[ni * nk];
        for (int i = 0; i < ni; i++) for (int k = 0; k < nk; k++) {
            double x = x0 + ia + 2 * i + 0.5, z = z0 + ka + 2 * k + 0.5, e = w.e(c, x, z);
            ee[i * nk + k] = e;
            if (e < 1) { lf[i * nk + k] = (float) w.floorAt(c, e, x, z); lc[i * nk + k] = (float) w.ceilAt(c, e, x, z); }
        }
        int cap = (ib - ia + 1) * (kb - ka + 1), n = 0;
        int[] cells = new int[cap];
        float[] l = new float[cap], h = new float[cap];
        for (int i = 0; i + 1 < ni; i++) for (int k = 0; k + 1 < nk; k++) {
            int p = i * nk + k;
            boolean a = ee[p] < 1, b = ee[p + nk] < 1, cc = ee[p + 1] < 1, dd = ee[p + nk + 1] < 1;
            if (!a && !b && !cc && !dd) continue;
            boolean all = a && b && cc && dd;
            // the span from a lattice point inside
            int q = a ? p : (b ? p + nk : (cc ? p + 1 : p + nk + 1));
            for (int di = 0; di < 2; di++) for (int dk = 0; dk < 2; dk++) {
                int gi = ia + 2 * i + di, gk = ka + 2 * k + dk;
                if (gi > ib || gk > kb) continue;
                double x = x0 + gi + 0.5, z = z0 + gk + 0.5;
                double e = all ? ee[p] : w.e(c, x, z);
                if (e >= 1 || inPillar(c, x, z)) continue;
                float f = lf[q], ce = lc[q];
                if (ce - f < MIN_SPAN) continue;
                cells[n] = gi * size + gk;
                l[n] = f;
                h[n] = ce;
                n++;
            }
        }
        return new Space(Arrays.copyOf(cells, n), Arrays.copyOf(l, n), Arrays.copyOf(h, n), shape);
    }

    /** The length of (x, z) (Math.hypot guards against overflow at many times the cost). */
    private static double len(double x, double z) { return Math.sqrt(x * x + z * z); }

    /** True inside one of a column hall's columns, which stand from the lava's bed to the vault. */
    private static boolean inPillar(Chamber c, double x, double z) {
        if (c.kind != Kind.HALL) return false;
        for (int p = 0; p + 2 < c.pillars.length; p += 3)
            if (len(x - c.pillars[p], z - c.pillars[p + 1]) < c.pillars[p + 2] - 0.5) return true;
        return false;
    }

    /**
     * A way's cells: out to {@link #VAULT_Q} of its half-width from its line, with the span of heights its vault
     * ({@link Network#vault}, or a round tube's circle) has open at the cell's distance from the line (where two of its
     * segments reach a cell, the wider).
     */
    private Space waySpace(Way wy, long shape) {
        int cap = 256, n = 0;
        int[] cells = new int[cap];
        float[] l = new float[cap], h = new float[cap];
        int segs = Math.max(1, wy.n - 1);
        // per cell of the way's box, 1 + its place in the list, or 0
        double wm = 0, xa = 1e18, xb = -1e18, za = 1e18, zb = -1e18;
        for (int i = 0; i < wy.n; i++) {
            wm = Math.max(wm, wy.w[i]);
            xa = Math.min(xa, wy.x[i]); xb = Math.max(xb, wy.x[i]);
            za = Math.min(za, wy.z[i]); zb = Math.max(zb, wy.z[i]);
        }
        wm = wm * VAULT_Q + 2;
        int gx = Math.max(0, (int) Math.floor(xa - wm) - x0), gz = Math.max(0, (int) Math.floor(za - wm) - z0);
        int gw = Math.max(1, Math.min(size - 1, (int) Math.ceil(xb + wm) - x0) - gx + 1), gh = Math.max(1, Math.min(size - 1, (int) Math.ceil(zb + wm) - z0) - gz + 1);
        int[] slot = new int[gw * gh];
        for (int s = 0; s < segs; s++) {
            int t = Math.min(s + 1, wy.n - 1);
            double ax = wy.x[s], az = wy.z[s], bx = wy.x[t], bz = wy.z[t];
            double reach = Math.max(wy.w[s], wy.w[t]) * VAULT_Q + 1;
            int ia = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach) - x0), ib = Math.min(size - 1, (int) Math.ceil(Math.max(ax, bx) + reach) - x0);
            int ka = Math.max(0, (int) Math.floor(Math.min(az, bz) - reach) - z0), kb = Math.min(size - 1, (int) Math.ceil(Math.max(az, bz) + reach) - z0);
            double dx = bx - ax, dz = bz - az, len2 = dx * dx + dz * dz;
            for (int i = ia; i <= ib; i++) for (int k = ka; k <= kb; k++) {
                double px = x0 + i + 0.5, pz = z0 + k + 0.5;
                double q = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / len2));
                double ex = px - ax - dx * q, ez = pz - az - dz * q, d = Math.sqrt(ex * ex + ez * ez);
                double half = wy.w[s] + (wy.w[t] - wy.w[s]) * q;
                if (d >= half * VAULT_Q) continue;
                double f = wy.f[s] + (wy.f[t] - wy.f[s]) * q, hg = wy.hgt[s] + (wy.hgt[t] - wy.hgt[s]) * q;
                double u, v;
                double r = Math.min(d / half, VAULT_EDGE);
                if (wy.round) {
                    double cy = f + 0.85 * half, hh = Math.sqrt(Math.max(0, (half + 0.5) * (half + 0.5) - r * r * half * half));
                    u = Math.max(f, cy - hh);
                    v = cy + hh;
                } else {
                    u = f + (r > 0.35 ? (r - 0.35) * (r - 0.35) * half * 0.9 : 0);
                    v = f + hg * Math.sqrt(Math.max(0, 1 - r * r));
                }
                if (v - u < MIN_SPAN) continue;
                int idx = i * size + k, si = (i - gx) * gh + (k - gz), at = slot[si] - 1;
                if (at >= 0) {
                    l[at] = (float) Math.min(l[at], u);
                    h[at] = (float) Math.max(h[at], v);
                    continue;
                }
                if (n == cap) {
                    cap *= 2;
                    cells = Arrays.copyOf(cells, cap);
                    l = Arrays.copyOf(l, cap);
                    h = Arrays.copyOf(h, cap);
                }
                cells[n] = idx;
                l[n] = (float) u;
                h[n] = (float) v;
                slot[si] = ++n;
            }
        }
        return new Space(Arrays.copyOf(cells, n), Arrays.copyOf(l, n), Arrays.copyOf(h, n), shape);
    }

    /**
     * True when no straight view through way {@code wy} (not yet in the plan) runs longer than {@link #LIMIT} across
     * the open space counted now and the way itself; {@code a} and {@code b} are the rooms at its two ends (either may
     * be null).
     */
    boolean clear(Way wy, Chamber a, Chamber b) {
        return longest(wy, a, b, LIMIT * Math.cos(PITCH_MAX), LIMIT) <= LIMIT;
    }

    /**
     * The longest view through way wy's cross-sections, or the first found over {@code stop}, leaving out the lines
     * no longer than {@code flat} across the plan: at its mouths (a block and a half past where its line leaves each
     * room) and every {@link #WINDOW_STEP} blocks between; one in the middle of a way too short to leave its rooms. A
     * way already counted is tested in the plan as it stands.
     */
    double longest(Way wy, Chamber a, Chamber b, double flat, double stop) {
        boolean counted = on.contains(wy);
        Space mine;
        if (counted) mine = space(wy);
        else {
            if (tried != wy || triedSpace.shape != shape(wy)) { tried = wy; triedSpace = waySpace(wy, shape(wy)); }
            mine = triedSpace;
        }
        float[] keepLo = null, keepHi = null;
        short[] keepCnt = null;
        if (!counted) {
            keepLo = new float[mine.cells.length];
            keepHi = new float[mine.cells.length];
            keepCnt = new short[mine.cells.length];
            for (int j = 0; j < mine.cells.length; j++) { int i = mine.cells[j]; keepLo[j] = lo[i]; keepHi[j] = hi[i]; keepCnt[j] = cnt[i]; }
            add(mine);
        }
        try {
            double[] s = new double[wy.n];
            for (int i = 1; i < wy.n; i++) s[i] = s[i - 1] + len(wy.x[i] - wy.x[i - 1], wy.z[i] - wy.z[i - 1]);
            double sa = 0, sb = s[wy.n - 1];
            if (a != null) for (int i = 0; i < wy.n; i++) if (w.e(a, wy.x[i], wy.z[i]) >= 1) { sa = s[i] + 1.5; break; }
            if (b != null) for (int i = wy.n - 1; i >= 0; i--) if (w.e(b, wy.x[i], wy.z[i]) >= 1) { sb = s[i] - 1.5; break; }
            List<double[]> at = new ArrayList<>();
            List<Double> mouths = new ArrayList<>(), mids = new ArrayList<>();
            if (sb - sa < 4) mouths.add((sa + sb) * 0.5);
            else {
                mouths.add(sa);
                mouths.add(sb);
                int k = (int) Math.floor((sb - sa) / WINDOW_STEP);
                for (int j = 1; j <= k; j++) mids.add(sa + (sb - sa) * j / (k + 1));
            }
            double best = 0;
            for (double m : mouths) {
                best = Math.max(best, window(wy, s, m, MOUTH_ANGLE, MOUTH_OFFSETS, flat, stop));
                if (best > stop) return best;
            }
            for (double m : mids) {
                best = Math.max(best, window(wy, s, m, MID_ANGLE, MID_OFFSETS, flat, stop));
                if (best > stop) return best;
            }
            return best;
        } finally {
            if (!counted) for (int j = 0; j < mine.cells.length; j++) { int i = mine.cells[j]; lo[i] = keepLo[j]; hi[i] = keepHi[j]; cnt[i] = keepCnt[j]; }
        }
    }

    /**
     * The longest view through way wy's cross-section at arc length {@code at} (stops once one is over {@code stop}).
     */
    private double window(Way wy, double[] s, double at, double maxAngle, double[] offsets, double flat, double stop) {
        int i = 0;
        while (i + 2 < wy.n && s[i + 1] < at) i++;
        int j1 = Math.min(i + 1, wy.n - 1);
        double seg = Math.max(1e-9, s[j1] - s[i]), q = Math.max(0, Math.min(1, (at - s[i]) / seg));
        double px = wy.x[i] + (wy.x[j1] - wy.x[i]) * q, pz = wy.z[i] + (wy.z[j1] - wy.z[i]) * q;
        double half = wy.w[i] + (wy.w[j1] - wy.w[i]) * q;
        // the axis from the line 2 blocks either side
        int i0 = i, i1 = j1;
        while (i0 > 0 && at - s[i0] < 2) i0--;
        while (i1 < wy.n - 1 && s[i1] - at < 2) i1++;
        double tx = wy.x[i1] - wy.x[i0], tz = wy.z[i1] - wy.z[i0], tl = len(tx, tz);
        if (tl < 1e-9) return 0;
        tx /= tl; tz /= tl;
        double best = 0;
        // nearest the axis first: the long views mostly are
        for (double a = 0; a <= maxAngle + 1e-9; a += a < FINE_ANGLE - 1e-9 ? ANGLE_STEP : 2 * ANGLE_STEP) for (int sg = a == 0 ? 1 : -1; sg <= 1; sg += 2) {
            double th = sg * a, c = Math.cos(th), sn = Math.sin(th);
            double dx = tx * c - tz * sn, dz = tx * sn + tz * c;
            for (double o : offsets) {
                double ox = px - tz * o * half, oz = pz + tx * o * half;
                double run = view(ox, oz, dx, dz, flat, stop);
                if (run > best) best = run;
                if (best > stop) return best;
            }
        }
        return best;
    }

    /**
     * The longest straight view along the line through (x, z) along the unit (dx, dz): the line followed both ways
     * while its cells are open, then for each pitch the longest stretch through (x, z) over which a ray at that pitch
     * stays inside every cell's span (its length along the ray). 0 when the stretch is no longer than {@code flat}
     * across the plan; at {@code stop} times the cosine of {@link #PITCH_MAX}, no ray along a shorter one is longer
     * than {@code stop}.
     */
    private double view(double x, double z, double dx, double dz, double flat, double stop) {
        double cap = Math.min(stop + 1, 400);
        int nf = cells(x, z, dx, dz, cap, ft, fl, fh, fm);
        if (nf == 0) return 0;
        int nb = cells(x, z, -dx, -dz, cap, bt, bl, bh, bm);
        // the first cell each way is the one the line starts in
        double total = ft[nf - 1] + (nb > 0 ? bt[nb - 1] : 0);
        if (total <= flat) return 0;
        double best = 0;
        for (double sl : slopes) {
            double cos = 1 / Math.sqrt(1 + sl * sl);
            if (total / cos <= best) continue;
            // forward: the heights at the start that keep the ray inside cells 0..j
            double lw = -1e9, up = 1e9;
            int jMax = -1;
            for (int j = 0; j < nf; j++) {
                lw = Math.max(lw, fl[j] - sl * fm[j]);
                up = Math.min(up, fh[j] - sl * fm[j]);
                if (lw > up) break;
                fLo[j] = lw; fHi[j] = up; jMax = j;
            }
            if (jMax < 0) continue;
            // back: the same for cells 1..k behind the start (k = 0: none past the start's own)
            lw = -1e9; up = 1e9;
            int kMax = 0;
            bLo[0] = lw; bHi[0] = up;
            for (int k = 1; k < nb; k++) {
                lw = Math.max(lw, bl[k] + sl * bm[k]);
                up = Math.min(up, bh[k] + sl * bm[k]);
                if (lw > up) break;
                bLo[k] = lw; bHi[k] = up; kMax = k;
            }
            // two pointers: the further forward the ray reaches, the less far back it can
            int k = kMax;
            for (int j = 0; j <= jMax; j++) {
                while (k > 0 && Math.max(fLo[j], bLo[k]) > Math.min(fHi[j], bHi[k])) k--;
                double run = (ft[j] + (nb > 0 ? bt[k] : 0)) / cos;
                if (run > best) best = run;
            }
            if (best > stop) return best;
        }
        return best;
    }

    /** The longest straight view along the line through (x, z) along the unit (dx, dz), for the tests. */
    double lineView(double x, double z, double dx, double dz) {
        return view(x, z, dx, dz, 0, 1e9);
    }

    /** The open span of heights over the raster cell at (x, z), or null where nothing is open, for the tests. */
    float[] spanAt(double x, double z) {
        int i = (int) Math.floor(x - x0), k = (int) Math.floor(z - z0);
        if (i < 0 || k < 0 || i >= size || k >= size || cnt[i * size + k] <= 0) return null;
        return new float[]{lo[i * size + k], hi[i * size + k]};
    }

    /**
     * Follows the line from (x, z) along (dx, dz) through open cells, at most {@code cap} blocks: per cell where the
     * line leaves it (t), the middle of its stretch (m) and the cell's span. Returns the number of cells.
     */
    private int cells(double x, double z, double dx, double dz, double cap, double[] t, double[] l, double[] h, double[] m) {
        double fx = x - x0, fz = z - z0;
        int i = (int) Math.floor(fx), k = (int) Math.floor(fz);
        int si = dx > 0 ? 1 : -1, sk = dz > 0 ? 1 : -1;
        double inf = Double.POSITIVE_INFINITY, ax = Math.abs(dx), az = Math.abs(dz);
        double ti = ax < 1e-12 ? inf : (dx > 0 ? i + 1 - fx : fx - i) / ax, tk = az < 1e-12 ? inf : (dz > 0 ? k + 1 - fz : fz - k) / az;
        double di = ax < 1e-12 ? inf : 1 / ax, dk = az < 1e-12 ? inf : 1 / az;
        double tIn = 0;
        int n = 0;
        while (n < MAX_CELLS) {
            if (i < 0 || k < 0 || i >= size || k >= size) return n;
            int idx = i * size + k;
            if (cnt[idx] <= 0) return n;
            double tOut = Math.min(Math.min(ti, tk), cap);
            t[n] = tOut;
            m[n] = (tIn + tOut) * 0.5;
            l[n] = lo[idx];
            h[n] = hi[idx];
            n++;
            if (tOut >= cap) return n;
            if (ti <= tk) { i += si; ti += di; }
            else { k += sk; tk += dk; }
            tIn = tOut;
        }
        return n;
    }
}
