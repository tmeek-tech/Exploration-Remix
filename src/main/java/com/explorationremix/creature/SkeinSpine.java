package com.explorationremix.creature;

import javax.annotation.Nullable;

/**
 * The line the Skein's long body is drawn along: a smooth curve that follows the way its head has come ({@link
 * SkeinTrail}) but never folds. The head may snap round as sharply as it likes; the body comes after it in a curve.
 *
 * <p>First the trail's corners are rounded: each point is moved towards the average of the trail round it (a Gaussian
 * of {@link #ROUND} blocks), so a turn is cut on its inside, the way a spline through the path would run; nearer the
 * head than {@link #ROUND_FROM} less so, so the neck stays on the head. Then the body is laid along that as a chain of
 * joints {@link #LINK} apart, from the head backwards. Each joint heads for the point a little beyond the one nearest
 * the joint before ({@link #LOOK} links on, on the same rock), but may turn from the way the joint before it ran by no
 * more than {@link #BEND} (more in the neck, {@link #NECK}). Where the trail runs straight or turns gently the chain
 * lies on the rounded trail; where the head turned back on itself, sharper than any rounding can make gentle, the
 * chain swings round in a loop and comes back onto the trail behind the turn, the way a long body follows a sharp turn
 * of its head. Its first joint starts out the way the head points, so the neck bends after the head and the body
 * after the neck.
 *
 * <p>Bends are reckoned along the rock only. Over an edge, from a floor onto a wall, the way the last joint ran is
 * carried round onto the new rock first, so the body pours over edges exactly as the trail does; only its turns across
 * the rock are smoothed. A joint never strays more than {@link #DRIFT} from the trail, nor into solid rock where a
 * check for it is given: it is pulled back towards the trail until it is clear.
 *
 * <p>Plain numbers, no game classes, so it can be tested on its own.
 */
public final class SkeinSpine {
    /** How far apart the joints are (blocks). */
    public static final double LINK = 0.15;
    /** How many links further back along the trail each joint heads for. */
    static final int LOOK = 3;
    /** The most a joint turns from the one before, along the rock (radians a link): about 14 degrees a plate. */
    static final double BEND = Math.toRadians(7.0);
    /** The neck turns more freely: the most for each of the first links behind the head. */
    static final double[] NECK = {Math.toRadians(45), Math.toRadians(30), Math.toRadians(20), Math.toRadians(12)};
    /** How widely the trail's corners are rounded (the Gaussian's width, blocks), and from how far behind the head in full. */
    static final double ROUND = 0.6, ROUND_FROM = 0.6;
    /** How many samples either side the rounding reads (three widths). */
    static final int ROUND_REACH = (int) Math.ceil(3.0 * ROUND / LINK);
    /** The furthest a joint strays from the trail (blocks). */
    static final double DRIFT = 1.2;
    /** Normals whose dot product is at least this are one rock (the trail's corners over an edge are not). */
    static final double SAME_ROCK = 0.95;
    /** How far off the rock a joint is checked for solid rock (its body lies on the rock, not in it). */
    static final double CHECK_LIFT = 0.3;
    private static final int STRIDE = 6;

    /** Whether a point is in solid rock. */
    public interface Solid {
        boolean at(double x, double y, double z);
    }

    private final int joints;
    private final double[] arcs, raw, out;
    /** The trail's samples as they were before the rounding (the head went there, so they are clear of the rock). */
    private final double[] orig, weights;
    private final double[] at = new double[6], aim = new double[6], v = new double[3];
    /** Which joints the drift limit or the rock pulled back towards the trail (they may bend more than the limit). */
    final boolean[] held;
    // what the chain was last laid from: laid from the same again, it would lie the same, so it is kept (it lies still
    // most of the time)
    @Nullable private SkeinTrail builtOn;
    private int builtVersion;
    private double builtFrom, builtFx, builtFy, builtFz;
    private boolean builtChecked;

    /** A chain of {@code joints} joints, the first at the head. */
    public SkeinSpine(int joints) {
        this.joints = Math.max(2, joints);
        this.arcs = new double[this.joints + LOOK + ROUND_REACH];
        this.raw = new double[STRIDE * arcs.length];
        this.orig = new double[STRIDE * arcs.length];
        this.weights = new double[ROUND_REACH + 1];
        for (int k = 0; k <= ROUND_REACH; k++) weights[k] = Math.exp(-0.5 * Math.pow(k * LINK / ROUND, 2));
        this.out = new double[STRIDE * this.joints];
        this.held = new boolean[this.joints];
    }

    public int joints() { return joints; }
    /** How far along the chain its last joint is. */
    public double length() { return (joints - 1) * LINK; }
    /** Joint i's position (0..2) and its rock's normal (3..5), at out[6i..6i+5]. */
    public double[] data() { return out; }

    /**
     * Lays the chain from the point {@code from} blocks back along {@code trail} (the head, or where the head was drawn
     * this frame), its neck starting out along (fx, fy, fz) (the way the head points; anything along the rock's normal
     * is ignored, and none at all means the way the trail runs). {@code solid} keeps it out of rock (null: no check).
     * Laid again from the same trail, unchanged, and the same way, it keeps the chain it has (the rock is not looked at
     * again until it moves).
     */
    public void build(SkeinTrail trail, double from, double fx, double fy, double fz, @Nullable Solid solid) {
        if (trail == builtOn && trail.version() == builtVersion && from == builtFrom && fx == builtFx && fy == builtFy && fz == builtFz
                && (solid != null) == builtChecked) return;
        builtOn = trail;
        builtVersion = trail.version();
        builtFrom = from;
        builtFx = fx;
        builtFy = fy;
        builtFz = fz;
        builtChecked = solid != null;
        int n = arcs.length;
        for (int i = 0; i < n; i++) arcs[i] = from + i * LINK;
        trail.sampleAll(arcs, raw);
        System.arraycopy(raw, 0, orig, 0, raw.length);
        round();
        System.arraycopy(raw, 0, out, 0, STRIDE);
        // the way back from the head: against the way it points, flat on its rock; or the way the trail runs back
        double nx = raw[3], ny = raw[4], nz = raw[5];
        double bx = -fx, by = -fy, bz = -fz;
        double along = bx * nx + by * ny + bz * nz;
        bx -= nx * along; by -= ny * along; bz -= nz * along;
        double bl = Math.sqrt(bx * bx + by * by + bz * bz);
        if (bl < 1.0E-4) {
            bx = raw[STRIDE] - raw[0]; by = raw[STRIDE + 1] - raw[1]; bz = raw[STRIDE + 2] - raw[2];
            bl = Math.sqrt(bx * bx + by * by + bz * bz);
            if (bl < 1.0E-9) { bx = 0; by = 0; bz = 1; bl = 1; }
        }
        bx /= bl; by /= bl; bz /= bl;
        double pnx = nx, pny = ny, pnz = nz;
        // how far down the trail the last joint has got (in samples, fractional): the trail point nearest it
        double m = 0.0;
        for (int j = 1; j < joints; j++) {
            int p = STRIDE * (j - 1);
            point(m, at);
            double qx = at[3], qy = at[4], qz = at[5];
            // what it heads for: the trail a few links on from where it has got to, if that is still this rock
            double tm = Math.min(m + LOOK, n - 1);
            point(tm, aim);
            while (tm > m + 1.0 && aim[3] * qx + aim[4] * qy + aim[5] * qz < SAME_ROCK) {
                tm = Math.max(m + 1.0, tm - 1.0);
                point(tm, aim);
            }
            double dx = aim[0] - out[p], dy = aim[1] - out[p + 1], dz = aim[2] - out[p + 2];
            // the way the joint before ran, carried round onto this rock
            v[0] = bx; v[1] = by; v[2] = bz;
            carry(v, pnx, pny, pnz, qx, qy, qz);
            double hx = v[0], hy = v[1], hz = v[2];
            double ha = hx * qx + hy * qy + hz * qz;
            hx -= qx * ha; hy -= qy * ha; hz -= qz * ha;
            double hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
            double dn = dx * qx + dy * qy + dz * qz;
            double tx = dx - qx * dn, ty = dy - qy * dn, tz = dz - qz * dn;
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (hl > 1.0E-6 && tl > 1.0E-6) {
                hx /= hl; hy /= hl; hz /= hl;
                // the turn from the way before to the way it wants, about the rock's normal
                double cx = hy * tz - hz * ty, cy = hz * tx - hx * tz, cz = hx * ty - hy * tx;
                double turn = Math.atan2(cx * qx + cy * qy + cz * qz, hx * tx + hy * ty + hz * tz);
                double most = j - 1 < NECK.length ? NECK[j - 1] : BEND;
                if (Math.abs(turn) > most) {
                    double a = Math.copySign(most, turn), cos = Math.cos(a), sin = Math.sin(a);
                    // h turned by a about q (h lies across q, so Rodrigues loses its last term)
                    double kx = qy * hz - qz * hy, ky = qz * hx - qx * hz, kz = qx * hy - qy * hx;
                    tx = (hx * cos + kx * sin) * tl;
                    ty = (hy * cos + ky * sin) * tl;
                    tz = (hz * cos + kz * sin) * tl;
                }
            }
            dx = tx + qx * dn; dy = ty + qy * dn; dz = tz + qz * dn;
            double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dl < 1.0E-9) { dx = bx; dy = by; dz = bz; dl = 1.0; }
            double jx = out[p] + dx / dl * LINK, jy = out[p + 1] + dy / dl * LINK, jz = out[p + 2] + dz / dl * LINK;
            // never far from the trail: within DRIFT of the trail point nearest it
            m = nearest(jx, jy, jz, m, n);
            point(m, at);
            double rx = at[0], ry = at[1], rz = at[2];
            qx = at[3]; qy = at[4]; qz = at[5];
            double ox = jx - rx, oy = jy - ry, oz = jz - rz, ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
            held[j] = false;
            if (ol > DRIFT) {
                held[j] = true;
                double k = DRIFT / ol;
                jx = rx + ox * k; jy = ry + oy * k; jz = rz + oz * k;
            }
            // nor in the rock: back towards where the head went (clear of it) until it is out of it
            if (solid != null && solid.at(jx + qx * CHECK_LIFT, jy + qy * CHECK_LIFT, jz + qz * CHECK_LIFT)) {
                origPoint(m, at);
                rx = at[0]; ry = at[1]; rz = at[2];
                double lo = 0.0, hi = 1.0;
                for (int it = 0; it < 5; it++) {
                    double mid = (lo + hi) / 2.0;
                    double mx = rx + (jx - rx) * mid, my = ry + (jy - ry) * mid, mz = rz + (jz - rz) * mid;
                    if (solid.at(mx + qx * CHECK_LIFT, my + qy * CHECK_LIFT, mz + qz * CHECK_LIFT)) hi = mid;
                    else lo = mid;
                }
                jx = rx + (jx - rx) * lo; jy = ry + (jy - ry) * lo; jz = rz + (jz - rz) * lo;
                held[j] = true;
            }
            int r = STRIDE * j;
            out[r] = jx; out[r + 1] = jy; out[r + 2] = jz;
            out[r + 3] = qx; out[r + 4] = qy; out[r + 5] = qz;
            bx = jx - out[p]; by = jy - out[p + 1]; bz = jz - out[p + 2];
            bl = Math.sqrt(bx * bx + by * by + bz * bz);
            if (bl > 1.0E-9) { bx /= bl; by /= bl; bz /= bl; }
            pnx = qx; pny = qy; pnz = qz;
        }
    }

    /**
     * Rounds the trail's corners: each sample's position moves to the Gaussian average of the samples round it on the
     * same rock (reading no further than the first sample on other rock either way, so it never averages a floor with
     * the floor beyond a step), the head's own sample not at all, those nearer it than {@link #ROUND_FROM} only in part.
     */
    private void round() {
        int n = arcs.length;
        for (int j = 1; j < n; j++) {
            int c = STRIDE * j;
            double sx = 0, sy = 0, sz = 0, sw = 0;
            for (int dir = -1; dir <= 1; dir += 2) {
                for (int k = dir < 0 ? 0 : 1; k <= ROUND_REACH; k++) {
                    int i = j + dir * k;
                    if (i < 0 || i >= n) break;
                    int o = STRIDE * i;
                    if (orig[o + 3] * orig[c + 3] + orig[o + 4] * orig[c + 4] + orig[o + 5] * orig[c + 5] < SAME_ROCK) break;
                    double w = weights[k];
                    sx += orig[o] * w; sy += orig[o + 1] * w; sz += orig[o + 2] * w; sw += w;
                }
            }
            double s = Math.min(1.0, (arcs[j] - arcs[0]) / ROUND_FROM);
            s = s * s * (3.0 - 2.0 * s);
            raw[c] = orig[c] + (sx / sw - orig[c]) * s;
            raw[c + 1] = orig[c + 1] + (sy / sw - orig[c + 1]) * s;
            raw[c + 2] = orig[c + 2] + (sz / sw - orig[c + 2]) * s;
        }
    }

    /** The unrounded trail's position at fractional index u, in o[0..2]. */
    private void origPoint(double u, double[] o) {
        int last = arcs.length - 1;
        int i = Math.max(0, Math.min((int) Math.floor(u), last - 1));
        double t = Math.max(0.0, Math.min(1.0, u - i));
        int a = STRIDE * i, b = STRIDE * (i + 1);
        for (int k = 0; k < 3; k++) o[k] = orig[a + k] + (orig[b + k] - orig[a + k]) * t;
    }

    /** The (rounded) trail's sample at fractional index u: position and normal (unit length) in o. */
    private void point(double u, double[] o) {
        int last = arcs.length - 1;
        int i = Math.max(0, Math.min((int) Math.floor(u), last - 1));
        double t = Math.max(0.0, Math.min(1.0, u - i));
        int a = STRIDE * i, b = STRIDE * (i + 1);
        for (int k = 0; k < 6; k++) o[k] = raw[a + k] + (raw[b + k] - raw[a + k]) * t;
        double l = Math.sqrt(o[3] * o[3] + o[4] * o[4] + o[5] * o[5]);
        if (l < 1.0E-6) { o[3] = raw[b + 3]; o[4] = raw[b + 4]; o[5] = raw[b + 5]; l = 1.0; }
        o[3] /= l; o[4] /= l; o[5] /= l;
    }

    /**
     * The trail point nearest (x, y, z), as a fractional sample index, looked for a little way either side of {@code
     * from} (the last joint's: the body never jumps to another stretch of trail that happens to lie alongside).
     */
    private double nearest(double x, double y, double z, double from, int n) {
        int i0 = Math.max(0, (int) Math.floor(from) - 1), i1 = Math.min(n - 2, (int) Math.floor(from) + LOOK + 2);
        double best = from, bestD = Double.MAX_VALUE;
        for (int i = i0; i <= i1; i++) {
            int a = STRIDE * i, b = STRIDE * (i + 1);
            double sx = raw[b] - raw[a], sy = raw[b + 1] - raw[a + 1], sz = raw[b + 2] - raw[a + 2];
            double ss = sx * sx + sy * sy + sz * sz;
            double t = ss < 1.0E-12 ? 0.0 : ((x - raw[a]) * sx + (y - raw[a + 1]) * sy + (z - raw[a + 2]) * sz) / ss;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = raw[a] + sx * t - x, py = raw[a + 1] + sy * t - y, pz = raw[a + 2] + sz * t - z;
            double d = px * px + py * py + pz * pz;
            if (d < bestD - 1.0E-12) { bestD = d; best = i + t; }
        }
        return Math.max(from, best);
    }

    /**
     * The point {@code s} blocks along the chain from the head: its position (0..2) and normal (3..5, unit length) at
     * o[off..off+5]. Between joints it lies on the straight between them; past the last, on along the last link.
     */
    public void sample(double s, double[] o, int off) {
        double u = Math.max(0.0, s) / LINK;
        int i = Math.min((int) Math.floor(u), joints - 2);
        double t = u - i;
        int a = STRIDE * i, b = STRIDE * (i + 1);
        for (int k = 0; k < 3; k++) o[off + k] = out[a + k] + (out[b + k] - out[a + k]) * t;
        double tn = Math.min(1.0, t);
        double x = out[a + 3] + (out[b + 3] - out[a + 3]) * tn, y = out[a + 4] + (out[b + 4] - out[a + 4]) * tn,
                z = out[a + 5] + (out[b + 5] - out[a + 5]) * tn;
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1.0E-6) { x = out[b + 3]; y = out[b + 4]; z = out[b + 5]; l = 1.0; }
        o[off + 3] = x / l; o[off + 4] = y / l; o[off + 5] = z / l;
    }

    /** The points at each of {@code arcs} (blocks along the chain), six numbers each in o, as {@link #sample}. */
    public void sampleAll(double[] arcs, double[] o) {
        for (int k = 0; k < arcs.length; k++) sample(arcs[k], o, STRIDE * k);
    }

    /** v carried round from rock facing (ax, ay, az) to rock facing (bx, by, bz): turned as the one normal turns into the other. */
    static void carry(double[] v, double ax, double ay, double az, double bx, double by, double bz) {
        double c = ax * bx + ay * by + az * bz;
        if (c > 0.9999) return;
        double kx = ay * bz - az * by, ky = az * bx - ax * bz, kz = ax * by - ay * bx;
        double s = Math.sqrt(kx * kx + ky * ky + kz * kz);
        if (s < 1.0E-6) return;          // opposite rock: nothing sensible to carry it round by
        kx /= s; ky /= s; kz /= s;
        double x = v[0], y = v[1], z = v[2];
        double kd = kx * x + ky * y + kz * z;
        double crx = ky * z - kz * y, cry = kz * x - kx * z, crz = kx * y - ky * x;
        v[0] = x * c + crx * s + kx * kd * (1 - c);
        v[1] = y * c + cry * s + ky * kd * (1 - c);
        v[2] = z * c + crz * s + kz * kd * (1 - c);
    }

}
