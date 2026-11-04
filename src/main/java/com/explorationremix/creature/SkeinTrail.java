package com.explorationremix.creature;

/**
 * The way the Skein's head has come: a line of points under its head, on the rock it crossed, each with the outward
 * normal of that rock, newest last. Its plates are drawn, and its body sections placed, at fixed distances back along
 * this line, so the long body follows the head's exact path like a train: over an edge, round a corner, up a wall and
 * across the ceiling, each plate lying on the rock it is crossing.
 *
 * <p>Where the head goes onto other rock (from the floor onto a wall, say), the points either side lie on different
 * planes, and the straight line between them would cut through the rock of an outer edge or the air of an inner
 * corner. The corner where the two planes meet goes in between, with a normal half way between theirs, so the body
 * bends over the edge where the head did.
 *
 * <p>The head itself is kept apart from the line: it moves every tick, and a point is only added once it has gone
 * {@link #SPACING} from the last one. A step longer than {@link #JUMP} (a teleport) lays the trail afresh, straight
 * back from the head. The trail keeps at least its {@code keep} length (more for a moment after a long step), so
 * every plate always has a place to lie.
 *
 * <p>It can be written out as samples at given distances back from the head and read back ({@link #write}, {@link
 * #read}): the Skein saves its body so, and sends it to each client that starts tracking it.
 *
 * <p>Plain numbers, no game classes, so it can be tested on its own.
 */
public final class SkeinTrail {
    /** A new point once the head is this far (blocks) from the last one. */
    public static final double SPACING = 0.12;
    /** A trail laid straight has its points this far apart. */
    static final double LAID_SPACING = 0.5;
    /** A step longer than this between two records is a teleport: the trail is laid afresh. */
    public static final double JUMP = 4.0;
    /** Normals whose dot product is below this lie on different rock: a corner goes in between. */
    static final double TURNED = 0.7;
    /** The most points kept (a corner adds one, so the line never runs out of room even at the smallest steps). */
    static final int CAP = 512;
    /**
     * How much it counts against a place on the line, looking for the one nearest a point, to lie away from the place
     * preferred (per block along the line, squared, against the squared distance): enough to choose between two
     * stretches about as near, where the head ran back over its own way.
     */
    static final double PREFER = 0.02;
    private static final int STRIDE = 6;

    private final double keep;
    private final double[] pts = new double[CAP * STRIDE];
    /**
     * How far along the line each point is (blocks, from the oldest point it was laid with): rising towards the head,
     * and never changed while the point is kept, so a place on the line stays put as the head walks on.
     */
    private final double[] along = new double[CAP];
    private int size;
    private final double[] head = new double[STRIDE];
    private boolean laid;
    /** Counts the times it was laid afresh: laid straight, read back, or after a teleport. */
    private int lays;
    private double lastStep;

    /** A trail that keeps at least {@code keep} blocks behind the head. */
    public SkeinTrail(double keep) {
        this.keep = keep;
    }

    public boolean laid() { return laid; }
    /** How far the head went at the last record (the renderer draws the body that far back between ticks). */
    public double lastStep() { return lastStep; }

    /** A number that changes whenever the trail is laid afresh (a body following it must be laid afresh too). */
    public int lays() { return lays; }
    /** Points on the line (the head not counted). */
    public int size() { return size; }
    public double keep() { return keep; }

    /**
     * Lays the trail straight back from the head at (x, y, z) along (bx, by, bz), on rock facing (ux, uy, uz): how a
     * Skein lies when it is set down, before it has walked anywhere.
     */
    public void lay(double x, double y, double z, double bx, double by, double bz, double ux, double uy, double uz) {
        double bl = Math.sqrt(bx * bx + by * by + bz * bz);
        if (bl < 1.0E-6) { bx = 0; by = 0; bz = 1; bl = 1; }
        // points half a block apart, so the oldest go one by one as the head walks on
        int n = (int) Math.ceil((keep + 0.5) / LAID_SPACING);
        size = 0;
        for (int i = n; i >= 0; i--) {
            double d = i * LAID_SPACING;
            add(x + bx / bl * d, y + by / bl * d, z + bz / bl * d, ux, uy, uz);
        }
        set(head, 0, x, y, z, ux, uy, uz);
        laid = true;
        lastStep = 0.0;
        lays++;
    }

    /**
     * The trail as samples at {@code arcs} (blocks back from the head, rising, the first 0: the head itself), to save or
     * send: for each, six floats, its position less (ox, oy, oz) and its rock's normal. Positions are taken relative to
     * a point near the head so that floats keep them to a hair however far out in the world it is. Nothing (an empty
     * array) for a trail not laid yet.
     */
    public float[] write(double[] arcs, double ox, double oy, double oz) {
        if (!laid) return new float[0];
        double[] s = new double[STRIDE * arcs.length];
        sampleAll(arcs, s);
        float[] out = new float[s.length];
        for (int o = 0; o < s.length; o += STRIDE) {
            out[o] = (float) (s[o] - ox);
            out[o + 1] = (float) (s[o + 1] - oy);
            out[o + 2] = (float) (s[o + 2] - oz);
            out[o + 3] = (float) s[o + 3];
            out[o + 4] = (float) s[o + 4];
            out[o + 5] = (float) s[o + 5];
        }
        return out;
    }

    /**
     * Lays the trail through samples {@link #write} gave (relative to (ox, oy, oz)): the first is the head, the rest the
     * line back from it, so the body lies again as it did when they were taken. False, and the trail left as it was,
     * when they are no trail: fewer than two, not whole samples, not numbers, or a normal of no length.
     */
    public boolean read(float[] data, double ox, double oy, double oz) {
        int n = data.length / STRIDE;
        if (n < 2 || n > CAP || data.length != n * STRIDE) return false;
        for (int o = 0; o < data.length; o += STRIDE) {
            for (int i = 0; i < STRIDE; i++) if (!Float.isFinite(data[o + i])) return false;
            if (sq(data[o + 3]) + sq(data[o + 4]) + sq(data[o + 5]) < 0.25) return false;
        }
        size = 0;
        for (int o = (n - 1) * STRIDE; o > 0; o -= STRIDE) {
            double l = Math.sqrt(sq(data[o + 3]) + sq(data[o + 4]) + sq(data[o + 5]));
            add(data[o] + ox, data[o + 1] + oy, data[o + 2] + oz, data[o + 3] / l, data[o + 4] / l, data[o + 5] / l);
        }
        double l = Math.sqrt(sq(data[3]) + sq(data[4]) + sq(data[5]));
        set(head, 0, data[0] + ox, data[1] + oy, data[2] + oz, data[3] / l, data[4] / l, data[5] / l);
        laid = true;
        lastStep = 0.0;
        lays++;
        return true;
    }

    /**
     * The head is now at (x, y, z), on rock facing (ux, uy, uz) (unit length). A point goes on the line once the head
     * is {@link #SPACING} from the last one, or at once when it has gone onto other rock, after the corner between.
     */
    public void record(double x, double y, double z, double ux, double uy, double uz) {
        if (!laid) { lay(x, y, z, 0, 0, 1, ux, uy, uz); return; }
        double step = Math.sqrt(sq(x - head[0]) + sq(y - head[1]) + sq(z - head[2]));
        if (step > JUMP) {
            // a teleport: lay it afresh, behind the head the way it was last heading
            int a = (size - 1) * STRIDE, b = Math.max(0, size - 2) * STRIDE;
            lay(x, y, z, pts[b] - pts[a], pts[b + 1] - pts[a + 1], pts[b + 2] - pts[a + 2], ux, uy, uz);
            return;
        }
        lastStep = step;
        if (step == 0.0 && head[3] == ux && head[4] == uy && head[5] == uz) return;   // where it was: nothing changes
        int last = (size - 1) * STRIDE;
        double dot = pts[last + 3] * ux + pts[last + 4] * uy + pts[last + 5] * uz;
        if (dot < TURNED) {
            double[] c = corner(pts[last], pts[last + 1], pts[last + 2], pts[last + 3], pts[last + 4], pts[last + 5], x, y, z, ux, uy, uz);
            if (c != null) {
                double nx = pts[last + 3] + ux, ny = pts[last + 4] + uy, nz = pts[last + 5] + uz;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1.0E-6) { nx = ux; ny = uy; nz = uz; nl = 1.0; }
                add(c[0], c[1], c[2], nx / nl, ny / nl, nz / nl);
            }
            add(x, y, z, ux, uy, uz);
        } else if (Math.sqrt(sq(x - pts[last]) + sq(y - pts[last + 1]) + sq(z - pts[last + 2])) >= SPACING) {
            add(x, y, z, ux, uy, uz);
        }
        set(head, 0, x, y, z, ux, uy, uz);
        trim();
    }

    /**
     * Where the plane through p1 with normal n1 meets the plane through p2 with normal n2, on the way from p1 to p2
     * (along the first plane, then the second): the edge the head went round. Null when the planes are (nearly)
     * parallel or the meeting lies out of the way (the two points were not either side of one edge).
     */
    static double[] corner(double x1, double y1, double z1, double n1x, double n1y, double n1z,
                           double x2, double y2, double z2, double n2x, double n2y, double n2z) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double gap = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (gap < 1.0E-6) return null;
        // the way across the first plane towards p2
        double along = dx * n1x + dy * n1y + dz * n1z;
        double tx = dx - n1x * along, ty = dy - n1y * along, tz = dz - n1z * along;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl < 1.0E-6) return null;
        tx /= tl; ty /= tl; tz /= tl;
        double rate = tx * n2x + ty * n2y + tz * n2z;
        if (Math.abs(rate) < 0.2) return null;
        double a = (dx * n2x + dy * n2y + dz * n2z) / rate;
        if (a < -0.05 || a > gap + 1.0) return null;
        double cx = x1 + tx * a, cy = y1 + ty * a, cz = z1 + tz * a;
        if (Math.sqrt(sq(cx - x2) + sq(cy - y2) + sq(cz - z2)) > gap + 1.0) return null;
        return new double[]{cx, cy, cz};
    }

    /**
     * The box round the stretch of body between two samples (positions and normals at at[a..a+5] and at[b..b+5], as
     * {@link #sampleAll} gives them), as {minX, minY, minZ, maxX, maxY, maxZ} in out: a slab {@code thick} thick and
     * {@code wide} wide lying along the stretch, each end lifted half its thickness off its own rock, squared to the
     * world's axes. So it is thin across the rock and wide across the body whichever way the stretch runs, along a
     * floor or straight up a wall (where a box kept upright would cover only its own height of the body).
     */
    public static void stretchBox(double[] at, int a, int b, double wide, double thick, double[] out) {
        double h = thick / 2.0, w = wide / 2.0;
        double ax = at[a] + at[a + 3] * h, ay = at[a + 1] + at[a + 4] * h, az = at[a + 2] + at[a + 5] * h;
        double bx = at[b] + at[b + 3] * h, by = at[b + 1] + at[b + 4] * h, bz = at[b + 2] + at[b + 5] * h;
        // the rock's normal along the stretch (half way between its ends', over an edge)
        double nx = at[a + 3] + at[b + 3], ny = at[a + 4] + at[b + 4], nz = at[a + 5] + at[b + 5];
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0E-6) { nx = at[b + 3]; ny = at[b + 4]; nz = at[b + 5]; nl = Math.max(1.0E-6, Math.sqrt(nx * nx + ny * ny + nz * nz)); }
        nx /= nl; ny /= nl; nz /= nl;
        // the way across the body: square to the stretch and the normal, or every way along the rock for no stretch
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double sx = ny * dz - nz * dy, sy = nz * dx - nx * dz, sz = nx * dy - ny * dx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl > 1.0E-6) { sx = Math.abs(sx) / sl; sy = Math.abs(sy) / sl; sz = Math.abs(sz) / sl; }
        else { sx = 1.0 - Math.abs(nx); sy = 1.0 - Math.abs(ny); sz = 1.0 - Math.abs(nz); }
        double ex = Math.abs(nx) * h + sx * w, ey = Math.abs(ny) * h + sy * w, ez = Math.abs(nz) * h + sz * w;
        out[0] = Math.min(ax, bx) - ex;
        out[1] = Math.min(ay, by) - ey;
        out[2] = Math.min(az, bz) - ez;
        out[3] = Math.max(ax, bx) + ex;
        out[4] = Math.max(ay, by) + ey;
        out[5] = Math.max(az, bz) + ez;
    }

    /** Drops the oldest points while the rest still reach {@code keep} behind the head. */
    private void trim() {
        double total = length();
        while (size > 2) {
            double first = dist(0, 1);
            if (total - first < keep && size < CAP) break;
            System.arraycopy(pts, STRIDE, pts, 0, (size - 1) * STRIDE);
            System.arraycopy(along, 1, along, 0, size - 1);
            size--;
            total -= first;
        }
    }

    /** The trail's whole length, from the head back to its oldest point. */
    public double length() {
        double total = Math.sqrt(sq(head[0] - pts[(size - 1) * STRIDE]) + sq(head[1] - pts[(size - 1) * STRIDE + 1])
                + sq(head[2] - pts[(size - 1) * STRIDE + 2]));
        for (int i = size - 1; i > 0; i--) total += dist(i - 1, i);
        return total;
    }

    /** The point {@code s} blocks back along the trail from the head: its position (0..2) and normal (3..5) in out. */
    public void sample(double s, double[] out) {
        sampleAll(new double[]{s}, out);
    }

    /**
     * The points at each of {@code arcs} (blocks back from the head, in rising order) in one walk down the line: for
     * the k-th, its position at out[6k..6k+2] and its rock's normal (unit length) at out[6k+3..6k+5]. Past the oldest
     * point the line goes on straight, the way its last stretch ran.
     */
    public void sampleAll(double[] arcs, double[] out) {
        int k = 0;
        double walked = 0.0;
        // the stretch from (a) to (b), a nearer the head; first the head to the newest point
        double[] a = head;
        int ai = 0;
        int bi = size - 1;
        while (k < arcs.length) {
            double s = Math.max(0.0, arcs[k]);
            if (bi < 0) {
                // past the oldest point: on along the oldest stretch's line
                double[] from = size > 1 ? pts : head;
                int fi = size > 1 ? STRIDE : 0;
                double ox = pts[0] - from[fi], oy = pts[1] - from[fi + 1], oz = pts[2] - from[fi + 2];
                double l = Math.sqrt(ox * ox + oy * oy + oz * oz);
                if (l < 1.0E-9) { ox = 0; oy = 0; oz = 1; l = 1; }
                double past = s - walked;
                out[6 * k] = pts[0] + ox / l * past;
                out[6 * k + 1] = pts[1] + oy / l * past;
                out[6 * k + 2] = pts[2] + oz / l * past;
                normal(pts, 3, pts, 3, 0.0, out, 6 * k + 3);
                k++;
                continue;
            }
            int b = bi * STRIDE;
            double len = Math.sqrt(sq(pts[b] - a[ai]) + sq(pts[b + 1] - a[ai + 1]) + sq(pts[b + 2] - a[ai + 2]));
            if (s <= walked + len) {
                double t = len < 1.0E-9 ? 0.0 : (s - walked) / len;
                out[6 * k] = a[ai] + (pts[b] - a[ai]) * t;
                out[6 * k + 1] = a[ai + 1] + (pts[b + 1] - a[ai + 1]) * t;
                out[6 * k + 2] = a[ai + 2] + (pts[b + 2] - a[ai + 2]) * t;
                normal(a, ai + 3, pts, b + 3, t, out, 6 * k + 3);
                k++;
                continue;
            }
            walked += len;
            a = pts;
            ai = b;
            bi--;
        }
    }

    // ------------------------------------------------------------------------------------------------ places on the line

    /**
     * How far along the line the head is: places on the line are reckoned so ({@link #at}), from a
     * point that stays put while the trail is kept (they rise towards the head, and a place stays where it is on the
     * line as the head walks on), until it is laid afresh ({@link #lays}).
     */
    public double headAlong() {
        int o = (size - 1) * STRIDE;
        return along[size - 1] + Math.sqrt(sq(head[0] - pts[o]) + sq(head[1] - pts[o + 1]) + sq(head[2] - pts[o + 2]));
    }

    /**
     * The point {@code u} along the line (as {@link #headAlong}): its position at out[off..off+2] and its rock's normal
     * (unit length) at out[off+3..off+5]. Beyond the head, the head; past the oldest point, on straight the way the
     * oldest stretch ran.
     */
    public void at(double u, double[] out, int off) {
        double h = headAlong();
        int last = size - 1;
        if (u >= h) {
            System.arraycopy(head, 0, out, off, STRIDE);
            return;
        }
        if (u >= along[last]) {
            double len = h - along[last];
            double t = len < 1.0E-9 ? 1.0 : (u - along[last]) / len;
            lerp(pts, last * STRIDE, head, 0, t, out, off);
            return;
        }
        if (u <= along[0]) {
            double[] to = size > 1 ? pts : head;
            int ti = size > 1 ? STRIDE : 0;
            double ox = pts[0] - to[ti], oy = pts[1] - to[ti + 1], oz = pts[2] - to[ti + 2];
            double l = Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (l < 1.0E-9) { ox = 0; oy = 0; oz = 1; l = 1; }
            double past = along[0] - u;
            out[off] = pts[0] + ox / l * past;
            out[off + 1] = pts[1] + oy / l * past;
            out[off + 2] = pts[2] + oz / l * past;
            normal(pts, 3, pts, 3, 0.0, out, off + 3);
            return;
        }
        int i = below(u);
        double len = along[i + 1] - along[i];
        lerp(pts, i * STRIDE, pts, (i + 1) * STRIDE, len < 1.0E-9 ? 0.0 : (u - along[i]) / len, out, off);
    }

    /** The point before {@code u} (as {@link #headAlong}): the last i with along[i] at most u (u within the points). */
    private int below(double u) {
        int lo = 0, hi = size - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (along[mid] <= u) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Position and normal t of the way from the sample at (p, i) to the one at (q, j), into out at o. */
    private static void lerp(double[] p, int i, double[] q, int j, double t, double[] out, int o) {
        out[o] = p[i] + (q[j] - p[i]) * t;
        out[o + 1] = p[i + 1] + (q[j + 1] - p[i + 1]) * t;
        out[o + 2] = p[i + 2] + (q[j + 2] - p[i + 2]) * t;
        normal(p, i + 3, q, j + 3, t, out, o + 3);
    }

    /** The normal t of the way from the normal at (p, i) to the one at (q, j), made unit length. */
    private static void normal(double[] p, int i, double[] q, int j, double t, double[] out, int o) {
        double x = p[i] + (q[j] - p[i]) * t, y = p[i + 1] + (q[j + 1] - p[i + 1]) * t, z = p[i + 2] + (q[j + 2] - p[i + 2]) * t;
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1.0E-6) { x = q[j]; y = q[j + 1]; z = q[j + 2]; l = Math.max(1.0E-6, Math.sqrt(x * x + y * y + z * z)); }
        out[o] = x / l;
        out[o + 1] = y / l;
        out[o + 2] = z / l;
    }

    private void add(double x, double y, double z, double ux, double uy, double uz) {
        if (size == CAP) {
            System.arraycopy(pts, STRIDE, pts, 0, (size - 1) * STRIDE);
            System.arraycopy(along, 1, along, 0, size - 1);
            size--;
        }
        int o = size * STRIDE;
        along[size] = size == 0 ? 0.0 : along[size - 1] + Math.sqrt(sq(x - pts[o - STRIDE]) + sq(y - pts[o - STRIDE + 1]) + sq(z - pts[o - STRIDE + 2]));
        set(pts, o, x, y, z, ux, uy, uz);
        size++;
    }

    private static void set(double[] a, int o, double x, double y, double z, double ux, double uy, double uz) {
        a[o] = x; a[o + 1] = y; a[o + 2] = z; a[o + 3] = ux; a[o + 4] = uy; a[o + 5] = uz;
    }

    private double dist(int i, int j) {
        int a = i * STRIDE, b = j * STRIDE;
        return Math.sqrt(sq(pts[a] - pts[b]) + sq(pts[a + 1] - pts[b + 1]) + sq(pts[a + 2] - pts[b + 2]));
    }

    private static double sq(double v) { return v * v; }
}
