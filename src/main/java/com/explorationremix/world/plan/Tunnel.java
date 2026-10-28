package com.explorationremix.world.plan;

/** A planned tunnel: a segment of air with a radius that can differ at each end and a vertical stretch. */
public record Tunnel(Segment seg, double r1, double r2, double yScale, Kind kind) {
    public enum Kind { ROUTE, LINK, RAMP }

    /** Signed distance to the tunnel's surface, negative inside. */
    public double dist(double x, double y, double z) {
        double t = seg.closestT3D(x, y, z);
        double dx = x - seg.xAt(t), dy = (y - seg.yAt(t)) / yScale, dz = z - seg.zAt(t);
        double r = r1 + (r2 - r1) * t;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
    }

    public double maxRadius() { return Math.max(r1, r2); }
    public double reach() { return maxRadius() * Math.max(1.0, yScale) + 6; }
}
