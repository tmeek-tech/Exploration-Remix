package com.explorationremix.world.plan;

/** A carved chamber: an ellipsoid of air. Kind decides the biome role and what gets built in it. */
public record Chamber(Kind kind, double x, double y, double z, double radius, double yScale, int ruinIndex) {
    public enum Kind { REFUGE, ROUTE_ALCOVE, HUNTING_RUIN, BEDROCK_LANDING, CATHEDRAL }

    /** Signed distance to the ellipsoid surface, negative inside. */
    public double dist(double px, double py, double pz) {
        double dx = px - x, dy = (py - y) / yScale, dz = pz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) - radius;
    }

    public double distXZ(double px, double pz) {
        double dx = px - x, dz = pz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean hasRuin() { return ruinIndex >= 0; }
    public int floorY() { return (int) Math.floor(y - radius * yScale) + 1; }
    public int ceilingY() { return (int) Math.ceil(y + radius * yScale) - 1; }
    public double halfHeight() { return radius * yScale; }
    public double reach() { return radius * Math.max(1.0, yScale) + 6; }
}
