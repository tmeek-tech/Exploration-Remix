package com.explorationremix.creature.body;

import java.util.Arrays;

/**
 * The rock round a body for one tick: the collision boxes of every block in a region, and the faces of them that lie
 * open to the air. A body asks it two things.
 *
 * <p>{@link #field}: how far a point is from the rock, and which way is away from it. Faces lying in one plane (a
 * floor of many blocks, say) count as one surface, so on a flat floor the answer is the plain height above it with
 * "away" straight up, however the blocks under it are split; past a surface's edge "away" turns smoothly round the
 * edge. Where two surfaces meet in a hollow (a floor and a wall, a floor and a step) the two are blended, so the way
 * out turns smoothly through the corner instead of flipping from one to the other: a body held a set distance out
 * from the rock is held on a rounded fillet there, bridging the corner the way a lizard's body does.
 *
 * <p>{@link #clip}: where a straight reach first meets rock, and the face it meets (for feet).
 *
 * <p>Plain numbers, no game classes, so it can be tested on its own. Not for more than one thread.
 */
public final class TerrainPatch {
    /** Faces closer to the same plane than this (blocks) are one surface. */
    private static final double SAME_PLANE = 1.0 / 4096.0;
    private static final int BOX = 6, FACE = 8;

    private int ox, oy, oz, sx, sy, sz;
    private int[] cellStart = new int[0], cellCount = new int[0];
    private double[] boxes = new double[BOX * 256];
    private int boxCount;
    /** Per face: axis (0 x, 1 y, 2 z), sign of its outward normal (+1/-1), plane, the rect on the other two axes, group. */
    private double[] faces = new double[FACE * 512];
    private int faceCount;
    private long[] groupKeys = new long[64];
    private int groupCount;
    // per-query scratch, by group
    private double[] gd = new double[64], gx = new double[64], gy = new double[64], gz = new double[64];
    private int[] touched = new int[64];
    private int touchedCount;
    private final Terrain.BoxSink sink = this::addBox;

    /** Reads the boxes of every block from (x0, y0, z0) to (x1, y1, z1) inclusive, and finds their open faces. */
    public void build(Terrain t, int x0, int y0, int z0, int x1, int y1, int z1) {
        ox = x0; oy = y0; oz = z0;
        sx = Math.max(1, x1 - x0 + 1); sy = Math.max(1, y1 - y0 + 1); sz = Math.max(1, z1 - z0 + 1);
        int cells = sx * sy * sz;
        if (cellStart.length < cells) { cellStart = new int[cells]; cellCount = new int[cells]; }
        boxCount = 0;
        for (int x = 0; x < sx; x++)
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++) {
                    int c = (x * sy + y) * sz + z;
                    cellStart[c] = boxCount;
                    t.boxes(ox + x, oy + y, oz + z, sink);
                    cellCount[c] = boxCount - cellStart[c];
                }
        faceCount = 0;
        groupCount = 0;
        for (int b = 0; b < boxCount; b++) {
            int o = BOX * b;
            double x0b = boxes[o], y0b = boxes[o + 1], z0b = boxes[o + 2], x1b = boxes[o + 3], y1b = boxes[o + 4], z1b = boxes[o + 5];
            face(0, -1, x0b, y0b, y1b, z0b, z1b);
            face(0, 1, x1b, y0b, y1b, z0b, z1b);
            face(1, -1, y0b, x0b, x1b, z0b, z1b);
            face(1, 1, y1b, x0b, x1b, z0b, z1b);
            face(2, -1, z0b, x0b, x1b, y0b, y1b);
            face(2, 1, z1b, x0b, x1b, y0b, y1b);
        }
    }

    private void addBox(double x0, double y0, double z0, double x1, double y1, double z1) {
        if (x1 - x0 < 1.0E-6 || y1 - y0 < 1.0E-6 || z1 - z0 < 1.0E-6) return;
        if (boxes.length < BOX * (boxCount + 1)) boxes = Arrays.copyOf(boxes, boxes.length * 2);
        int o = BOX * boxCount++;
        boxes[o] = x0; boxes[o + 1] = y0; boxes[o + 2] = z0; boxes[o + 3] = x1; boxes[o + 4] = y1; boxes[o + 5] = z1;
    }

    /**
     * A box's face: kept if any of five points on it (its middle and four near its corners) has open air just outside;
     * a face pressed against another block (most of a cave's rock) is left out. {@code a} and {@code b} are the face's
     * two other axes in order (y, z for an x face; x, z for a y face; x, y for a z face).
     */
    private void face(int axis, int sign, double plane, double a0, double a1, double b0, double b1) {
        if (a1 - a0 < 1.0E-6 || b1 - b0 < 1.0E-6) return;
        double out = plane + sign * 1.0E-3;
        double ia = (a1 - a0) * 0.15, ib = (b1 - b0) * 0.15;
        boolean open = false;
        double[] as = {(a0 + a1) * 0.5, a0 + ia, a1 - ia, a0 + ia, a1 - ia};
        double[] bs = {(b0 + b1) * 0.5, b0 + ib, b0 + ib, b1 - ib, b1 - ib};
        for (int i = 0; i < 5 && !open; i++) {
            double px, py, pz;
            switch (axis) {
                case 0 -> { px = out; py = as[i]; pz = bs[i]; }
                case 1 -> { px = as[i]; py = out; pz = bs[i]; }
                default -> { px = as[i]; py = bs[i]; pz = out; }
            }
            if (!solid(px, py, pz)) open = true;
        }
        if (!open) return;
        if (faces.length < FACE * (faceCount + 1)) faces = Arrays.copyOf(faces, faces.length * 2);
        int o = FACE * faceCount++;
        faces[o] = axis; faces[o + 1] = sign; faces[o + 2] = plane;
        faces[o + 3] = a0; faces[o + 4] = a1; faces[o + 5] = b0; faces[o + 6] = b1;
        faces[o + 7] = group(axis, sign, plane);
    }

    private int group(int axis, int sign, double plane) {
        long key = (Math.round(plane / SAME_PLANE) << 3) | ((long) axis << 1) | (sign > 0 ? 1L : 0L);
        for (int g = 0; g < groupCount; g++) if (groupKeys[g] == key) return g;
        if (groupCount == groupKeys.length) {
            int n = groupKeys.length * 2;
            groupKeys = Arrays.copyOf(groupKeys, n);
            gd = Arrays.copyOf(gd, n); gx = Arrays.copyOf(gx, n); gy = Arrays.copyOf(gy, n); gz = Arrays.copyOf(gz, n);
            touched = Arrays.copyOf(touched, n);
        }
        groupKeys[groupCount] = key;
        return groupCount++;
    }

    public int faceCount() { return faceCount; }
    public int boxCount() { return boxCount; }

    /** Whether the point lies inside any box (looking in its own cell and the one below, for boxes taller than a block). */
    public boolean solid(double px, double py, double pz) {
        int cx = (int) Math.floor(px) - ox, cy = (int) Math.floor(py) - oy, cz = (int) Math.floor(pz) - oz;
        for (int dy = 0; dy >= -1; dy--) {
            int y = cy + dy;
            if (cx < 0 || cx >= sx || y < 0 || y >= sy || cz < 0 || cz >= sz) continue;
            int c = (cx * sy + y) * sz + cz;
            for (int b = cellStart[c], e = b + cellCount[c]; b < e; b++) {
                int o = BOX * b;
                // a point on the face between two boxes belongs to one of them (the one it starts, as a block's own
                // coordinates do), so a line of joints along a block edge is never taken for open air
                if (px >= boxes[o] && px < boxes[o + 3] && py >= boxes[o + 1] && py < boxes[o + 4] && pz >= boxes[o + 2] && pz < boxes[o + 5])
                    return true;
            }
        }
        return false;
    }

    /**
     * How far (px, py, pz) lies from the rock and which way is away from it, counting only rock within {@code reach}:
     * {@code out} gets the distance (negative inside rock: how deep), then the unit direction away. Surfaces are
     * blended over about {@code soft} blocks where they meet in a hollow (a soft minimum of their distances). False,
     * and {@code out} untouched, when no rock lies within reach.
     */
    public boolean field(double px, double py, double pz, double reach, double soft, double[] out) {
        if (solid(px, py, pz)) return escape(px, py, pz, out);
        touchedCount = 0;
        double r2 = reach * reach;
        for (int f = 0; f < faceCount; f++) {
            int o = FACE * f;
            int axis = (int) faces[o];
            double sign = faces[o + 1], plane = faces[o + 2];
            double pa, pb, pn;
            switch (axis) {
                case 0 -> { pn = px; pa = py; pb = pz; }
                case 1 -> { pn = py; pa = px; pb = pz; }
                default -> { pn = pz; pa = px; pb = py; }
            }
            double above = (pn - plane) * sign;
            if (above < -1.0E-4 || above > reach) continue;
            double ca = clamp(pa, faces[o + 3], faces[o + 4]), cb = clamp(pb, faces[o + 5], faces[o + 6]);
            double da = pa - ca, db = pb - cb, dn = pn - plane;
            double d2 = da * da + db * db + dn * dn;
            if (d2 > r2) continue;
            double d = Math.sqrt(d2);
            int g = (int) faces[o + 7];
            boolean first = true;
            for (int i = 0; i < touchedCount; i++) if (touched[i] == g) { first = false; break; }
            if (first) { touched[touchedCount++] = g; gd[g] = Double.MAX_VALUE; }
            if (d >= gd[g]) continue;
            gd[g] = d;
            double vn, va, vb;
            if (d < 1.0E-7) { vn = sign; va = 0; vb = 0; }
            else { vn = dn / d; va = da / d; vb = db / d; }
            switch (axis) {
                case 0 -> { gx[g] = vn; gy[g] = va; gz[g] = vb; }
                case 1 -> { gy[g] = vn; gx[g] = va; gz[g] = vb; }
                default -> { gz[g] = vn; gx[g] = va; gy[g] = vb; }
            }
        }
        if (touchedCount == 0) return false;
        double min = Double.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < touchedCount; i++) {
            int g = touched[i];
            if (gd[g] < min) { min = gd[g]; best = g; }
        }
        double sum = 0, nx = 0, ny = 0, nz = 0;
        for (int i = 0; i < touchedCount; i++) {
            int g = touched[i];
            double w = Math.exp(-(gd[g] - min) / soft);
            sum += w;
            nx += w * gx[g]; ny += w * gy[g]; nz += w * gz[g];
        }
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-4) { nx = gx[best]; ny = gy[best]; nz = gz[best]; len = 1.0; }
        out[0] = min - soft * Math.log(sum);
        out[1] = nx / len; out[2] = ny / len; out[3] = nz / len;
        return true;
    }

    /** Inside rock: the nearest open face straight out along one axis, its depth (as a negative distance) and normal. */
    private boolean escape(double px, double py, double pz, double[] out) {
        double best = 2.0;
        int bf = -1;
        for (int f = 0; f < faceCount; f++) {
            int o = FACE * f;
            int axis = (int) faces[o];
            double sign = faces[o + 1], plane = faces[o + 2];
            double pa, pb, pn;
            switch (axis) {
                case 0 -> { pn = px; pa = py; pb = pz; }
                case 1 -> { pn = py; pa = px; pb = pz; }
                default -> { pn = pz; pa = px; pb = py; }
            }
            double depth = (plane - pn) * sign;
            if (depth < 0 || depth >= best) continue;
            if (pa < faces[o + 3] || pa > faces[o + 4] || pb < faces[o + 5] || pb > faces[o + 6]) continue;
            best = depth;
            bf = f;
        }
        if (bf < 0) {
            out[0] = -0.5; out[1] = 0; out[2] = 1; out[3] = 0;
            return true;
        }
        int o = FACE * bf;
        int axis = (int) faces[o];
        double sign = faces[o + 1];
        out[0] = -best;
        out[1] = axis == 0 ? sign : 0; out[2] = axis == 1 ? sign : 0; out[3] = axis == 2 ? sign : 0;
        return true;
    }

    /**
     * Where the straight line from a to b first meets rock: {@code out} gets how far along (0 to 1), the outward normal
     * of the face it meets, and 1 when a itself lies in rock (0 otherwise; then the normal is the way back out along the
     * line). False when the line meets no rock.
     */
    public boolean clip(double ax, double ay, double az, double bx, double by, double bz, double[] out) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int x0 = (int) Math.floor(Math.min(ax, bx)) - ox, x1 = (int) Math.floor(Math.max(ax, bx)) - ox;
        int y0 = (int) Math.floor(Math.min(ay, by)) - oy - 1, y1 = (int) Math.floor(Math.max(ay, by)) - oy;
        int z0 = (int) Math.floor(Math.min(az, bz)) - oz, z1 = (int) Math.floor(Math.max(az, bz)) - oz;
        double bestT = Double.MAX_VALUE;
        int bestAxis = -1;
        double bestSign = 0;
        boolean inside = false;
        for (int x = Math.max(0, x0); x <= Math.min(sx - 1, x1); x++)
            for (int y = Math.max(0, y0); y <= Math.min(sy - 1, y1); y++)
                for (int z = Math.max(0, z0); z <= Math.min(sz - 1, z1); z++) {
                    int c = (x * sy + y) * sz + z;
                    for (int b = cellStart[c], e = b + cellCount[c]; b < e; b++) {
                        int o = BOX * b;
                        double tIn = -Double.MAX_VALUE, tOut = Double.MAX_VALUE;
                        int inAxis = -1;
                        double inSign = 0;
                        boolean miss = false;
                        for (int k = 0; k < 3 && !miss; k++) {
                            double a = k == 0 ? ax : k == 1 ? ay : az, d = k == 0 ? dx : k == 1 ? dy : dz;
                            double lo = boxes[o + k], hi = boxes[o + 3 + k];
                            if (Math.abs(d) < 1.0E-12) {
                                // running along a face: in the box it starts (as solid() counts it), never between two
                                if (a < lo || a >= hi) miss = true;
                                continue;
                            }
                            double t0 = (lo - a) / d, t1 = (hi - a) / d;
                            double near = Math.min(t0, t1), far = Math.max(t0, t1);
                            if (near > tIn) { tIn = near; inAxis = k; inSign = d > 0 ? -1 : 1; }
                            if (far < tOut) tOut = far;
                            if (tIn > tOut) miss = true;
                        }
                        if (miss || tOut < 0 || tIn > 1) continue;
                        if (tIn < 0) {
                            // the line starts inside this box
                            if (!inside || bestT > 0) { inside = true; bestT = 0; bestAxis = -1; }
                            continue;
                        }
                        if (!inside && tIn < bestT) { bestT = tIn; bestAxis = inAxis; bestSign = inSign; }
                    }
                }
        if (inside) {
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            out[0] = 0; out[1] = -dx / l; out[2] = -dy / l; out[3] = -dz / l; out[4] = 1;
            return true;
        }
        if (bestAxis < 0) return false;
        out[0] = bestT;
        out[1] = bestAxis == 0 ? bestSign : 0; out[2] = bestAxis == 1 ? bestSign : 0; out[3] = bestAxis == 2 ? bestSign : 0;
        out[4] = 0;
        return true;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
