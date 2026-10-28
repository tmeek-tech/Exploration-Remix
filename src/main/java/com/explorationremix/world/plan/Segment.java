package com.explorationremix.world.plan;

/** A straight 3D segment of a planned route or tunnel. */
public record Segment(double x1, double y1, double z1, double x2, double y2, double z2) {

    public double length() {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Parameter t in [0,1] of the closest point on the segment to (x,z), measured in XZ only. */
    public double closestTXZ(double x, double z) {
        double dx = x2 - x1, dz = z2 - z1;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-9) return 0;
        double t = ((x - x1) * dx + (z - z1) * dz) / len2;
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    public double closestT3D(double x, double y, double z) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len2 = dx * dx + dy * dy + dz * dz;
        if (len2 < 1e-9) return 0;
        double t = ((x - x1) * dx + (y - y1) * dy + (z - z1) * dz) / len2;
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    public double xAt(double t) { return x1 + (x2 - x1) * t; }
    public double yAt(double t) { return y1 + (y2 - y1) * t; }
    public double zAt(double t) { return z1 + (z2 - z1) * t; }

    public double minX() { return Math.min(x1, x2); }
    public double maxX() { return Math.max(x1, x2); }
    public double minZ() { return Math.min(z1, z2); }
    public double maxZ() { return Math.max(z1, z2); }
}
