package com.explorationremix.creature.body;

/**
 * How near a point lies to the rock, and which way is out (0.21.5, for the giant Skein's body): the nearest point of
 * any collision box round it, or, inside rock, the way out. Plain numbers over a {@link Terrain}; not for more than one
 * thread at once (it keeps its scratch).
 */
public final class RockClearance {
    private final double[] best = new double[4];
    private double cx, cy, cz, bestD2;
    private boolean inside;
    private final Terrain.BoxSink sink = this::box;

    /**
     * The nearest rock to (x, y, z) within {@code reach}: true, with its distance at out[0] (0 inside rock) and the
     * unit way from it to the point at out[1..3] (inside rock, none: out[1..3] zero); false when none is that near.
     */
    public boolean nearest(Terrain t, double x, double y, double z, double reach, double[] out) {
        cx = x; cy = y; cz = z;
        bestD2 = reach * reach;
        inside = false;
        best[0] = -1;
        int x0 = (int) Math.floor(x - reach), x1 = (int) Math.floor(x + reach);
        int y0 = (int) Math.floor(y - reach) - 1, y1 = (int) Math.floor(y + reach);
        int z0 = (int) Math.floor(z - reach), z1 = (int) Math.floor(z + reach);
        for (int bx = x0; bx <= x1; bx++)
            for (int by = y0; by <= y1; by++)
                for (int bz = z0; bz <= z1; bz++) {
                    t.boxes(bx, by, bz, sink);
                    if (inside) break;
                }
        if (inside) {
            out[0] = 0.0; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0;
            return true;
        }
        if (best[0] < 0) return false;
        double d = Math.sqrt(bestD2);
        out[0] = d;
        if (d < 1.0E-9) { out[1] = 0; out[2] = 0; out[3] = 0; }
        else { out[1] = (cx - best[1]) / d; out[2] = (cy - best[2]) / d; out[3] = (cz - best[3]) / d; }
        return true;
    }

    private void box(double x0, double y0, double z0, double x1, double y1, double z1) {
        if (inside) return;
        if (cx > x0 && cx < x1 && cy > y0 && cy < y1 && cz > z0 && cz < z1) { inside = true; return; }
        double qx = cx < x0 ? x0 : Math.min(cx, x1), qy = cy < y0 ? y0 : Math.min(cy, y1), qz = cz < z0 ? z0 : Math.min(cz, z1);
        double d2 = (cx - qx) * (cx - qx) + (cy - qy) * (cy - qy) + (cz - qz) * (cz - qz);
        if (d2 < bestD2) {
            bestD2 = d2;
            best[0] = 1; best[1] = qx; best[2] = qy; best[3] = qz;
        }
    }

    /** Whether (x, y, z) lies inside any rock. */
    public boolean inRock(Terrain t, double x, double y, double z) {
        double[] o = new double[4];
        return nearest(t, x, y, z, 0.01, o) && o[0] <= 0.0;
    }

    /**
     * Keeps a body's line clear of the rock: the point {@code lift} out from (x, y, z) along its back (nx, ny, nz, unit)
     * is moved out of any rock it lies in (along its back), then out to {@code lift} from the nearest rock, and the
     * point under it (back down along its back) written into {@code out[0..2]}; its back, tipped a little towards the
     * way it was pushed, into out[3..5]. Returns how far it was moved (0: it lay clear).
     */
    public double clear(Terrain t, double x, double y, double z, double nx, double ny, double nz, double lift, double[] out) {
        double[] o = new double[4];
        double px = x + nx * lift, py = y + ny * lift, pz = z + nz * lift;
        double sx = px, sy = py, sz = pz;
        // in rock: out along its back first (up to three blocks)
        if (nearest(t, px, py, pz, 0.02, o) && o[0] <= 0.0) {
            boolean free = false;
            for (int i = 1; i <= 30; i++) {
                double qx = px + nx * 0.1 * i, qy = py + ny * 0.1 * i, qz = pz + nz * 0.1 * i;
                if (!(nearest(t, qx, qy, qz, 0.02, o) && o[0] <= 0.0)) { px = qx; py = qy; pz = qz; free = true; break; }
            }
            if (!free) { out[0] = x; out[1] = y; out[2] = z; out[3] = nx; out[4] = ny; out[5] = nz; return 0.0; }
        }
        // then out to its lift from the nearest rock (twice: a push off one face may bring it near another)
        for (int it = 0; it < 3; it++) {
            if (!nearest(t, px, py, pz, lift, o)) break;
            if (o[0] <= 0.0) break;
            double m = lift - o[0];
            if (m < 1.0E-4) break;
            px += o[1] * m; py += o[2] * m; pz += o[3] * m;
        }
        double mx = px - sx, my = py - sy, mz = pz - sz, moved = Math.sqrt(mx * mx + my * my + mz * mz);
        double bx = nx, by = ny, bz = nz;
        if (moved > 1.0E-4) {
            double k = 0.6 * Math.min(1.0, moved / lift);
            bx += mx / moved * k; by += my / moved * k; bz += mz / moved * k;
            double bl = Math.sqrt(bx * bx + by * by + bz * bz);
            if (bl > 1.0E-6) { bx /= bl; by /= bl; bz /= bl; } else { bx = nx; by = ny; bz = nz; }
        }
        out[0] = px - nx * lift; out[1] = py - ny * lift; out[2] = pz - nz * lift;
        out[3] = bx; out[4] = by; out[5] = bz;
        return moved;
    }

    private final double[] wide = new double[6], side = new double[4];

    /**
     * As {@link #clear}, for a body {@code half} blocks wide either side of its line: the line is cleared, then the
     * points {@code half} out to either side of it (along (sx, sy, sz), square to its back) are kept {@code room} from
     * the rock, the joint moved away from any they come nearer (a wall beside it, a ledge it lies along), and the line
     * cleared again. Where it lies in a cleft narrower than itself the two sides' pushes meet in the middle. Returns
     * how far it was moved in all.
     */
    public double clearWide(Terrain t, double x, double y, double z, double nx, double ny, double nz,
                            double sx, double sy, double sz, double lift, double half, double room, double[] out) {
        double moved = clear(t, x, y, z, nx, ny, nz, lift, wide);
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0E-6) { System.arraycopy(wide, 0, out, 0, 6); return moved; }
        sx /= sl; sy /= sl; sz /= sl;
        double cx = wide[0] + wide[3] * lift, cy = wide[1] + wide[4] * lift, cz = wide[2] + wide[5] * lift;
        double px = 0, py = 0, pz = 0;
        for (int k = -1; k <= 1; k += 2) {
            double qx = cx + sx * half * k, qy = cy + sy * half * k, qz = cz + sz * half * k;
            if (!nearest(t, qx, qy, qz, room, side)) continue;
            if (side[0] > 0.0) {
                double m = room - side[0];
                px += side[1] * m; py += side[2] * m; pz += side[3] * m;
                continue;
            }
            // that side lies in rock: in towards the line until it is out
            for (int i = 1; i <= 10; i++) {
                double f = half * 0.1 * i;
                if (!inRock(t, qx - sx * f * k, qy - sy * f * k, qz - sz * f * k)) {
                    px -= sx * (f + room) * k; py -= sy * (f + room) * k; pz -= sz * (f + room) * k;
                    break;
                }
            }
        }
        double pl = Math.sqrt(px * px + py * py + pz * pz);
        if (pl < 1.0E-4) { System.arraycopy(wide, 0, out, 0, 6); return moved; }
        if (pl > half) { px *= half / pl; py *= half / pl; pz *= half / pl; pl = half; }
        clear(t, wide[0] + px, wide[1] + py, wide[2] + pz, wide[3], wide[4], wide[5], lift, out);
        double ex = out[0] - x, ey = out[1] - y, ez = out[2] - z;
        return Math.max(moved, Math.sqrt(ex * ex + ey * ey + ez * ez));
    }
}
