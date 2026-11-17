package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * A smooth curve through a chain of joints, measured by distance along the chain: at each joint it runs the way from
 * the joint before to the joint after, so it bends smoothly through every joint (no corners for a skin laid along it to
 * crease at). Each point also gets a way out of the rock (the joints' own, blended along the curve and squared to it).
 * Past either end it carries straight on.
 */
final class SpineCurve {
    private final double[] arc;
    private final int n;
    private final double[] mx, my, mz;
    private Vector3d[] p, u;

    SpineCurve(double[] arc) {
        this.arc = arc;
        this.n = arc.length;
        mx = new double[n]; my = new double[n]; mz = new double[n];
    }

    /** Takes the joints and their ways out for the evaluations that follow (the arrays are read, not copied). */
    void set(Vector3d[] p, Vector3d[] u) {
        this.p = p;
        this.u = u;
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            double ds = arc[b] - arc[a];
            mx[i] = (p[b].x - p[a].x) / ds;
            my[i] = (p[b].y - p[a].y) / ds;
            mz[i] = (p[b].z - p[a].z) / ds;
        }
    }

    /**
     * At {@code s} along the chain: the point, the way along the curve toward its far end (unit) and the way out of
     * the rock (unit, square to the way along).
     */
    void eval(double s, Vector3d pos, Vector3d along, Vector3d out) {
        double over = 0;
        if (s < arc[0]) { over = s - arc[0]; s = arc[0]; }
        else if (s > arc[n - 1]) { over = s - arc[n - 1]; s = arc[n - 1]; }
        int i = 0;
        while (i < n - 2 && s > arc[i + 1]) i++;
        double h = arc[i + 1] - arc[i];
        double t = (s - arc[i]) / h;
        double t2 = t * t, t3 = t2 * t;
        double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
        double d00 = 6 * t2 - 6 * t, d10 = 3 * t2 - 4 * t + 1, d01 = -6 * t2 + 6 * t, d11 = 3 * t2 - 2 * t;
        Vector3d a = p[i], b = p[i + 1];
        int j = i + 1;
        double x = h00 * a.x + h10 * h * mx[i] + h01 * b.x + h11 * h * mx[j];
        double y = h00 * a.y + h10 * h * my[i] + h01 * b.y + h11 * h * my[j];
        double z = h00 * a.z + h10 * h * mz[i] + h01 * b.z + h11 * h * mz[j];
        double dx = (d00 * a.x + d01 * b.x) / h + d10 * mx[i] + d11 * mx[j];
        double dy = (d00 * a.y + d01 * b.y) / h + d10 * my[i] + d11 * my[j];
        double dz = (d00 * a.z + d01 * b.z) / h + d10 * mz[i] + d11 * mz[j];
        pos.set(x + dx * over, y + dy * over, z + dz * over);
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0E-9) along.set(b).sub(a).normalize();
        else along.set(dx / dl, dy / dl, dz / dl);
        // the way out: the two joints' blended, squared to the curve
        Vector3d ua = u[i], ub = u[j];
        double ox = ua.x + (ub.x - ua.x) * t, oy = ua.y + (ub.y - ua.y) * t, oz = ua.z + (ub.z - ua.z) * t;
        double d = ox * along.x + oy * along.y + oz * along.z;
        ox -= along.x * d; oy -= along.y * d; oz -= along.z * d;
        double ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (ol < 1.0E-6) {
            // the way out lies along the curve: any square way will do
            if (Math.abs(along.y) < 0.9) { ox = -along.x * along.y; oy = 1 - along.y * along.y; oz = -along.z * along.y; }
            else { ox = 1 - along.x * along.x; oy = -along.y * along.x; oz = -along.z * along.x; }
            ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
        }
        out.set(ox / ol, oy / ol, oz / ol);
    }
}
