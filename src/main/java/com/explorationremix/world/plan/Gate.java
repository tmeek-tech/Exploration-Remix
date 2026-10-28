package com.explorationremix.world.plan;

/**
 * A gate: a twisting passage through one band. The tube runs from the upper landing (in the layer or pack caves
 * above the band) to the lower landing (in the layer below), as a cubic Bezier curve in XZ while y runs linearly.
 * Landings are flattened ellipsoids ({@link #LANDING_Y_SCALE}) whose floor and ceiling touch the band.
 */
public record Gate(int band, int cellX, int cellZ, double upperX, double upperY, double upperZ,
                   double lowerX, double lowerY, double lowerZ, double c1x, double c1z, double c2x, double c2z,
                   double radius, double landingRadius) {

    public static final double LANDING_Y_SCALE = 1.6;

    /** Vertical half-extent of a landing. */
    public double landingHalfHeight() { return landingRadius / LANDING_Y_SCALE; }

    /** Point on the tube at parameter t. */
    public double px(double t) { return bez(upperX, c1x, c2x, lowerX, t); }
    public double py(double t) { return upperY + (lowerY - upperY) * t; }
    public double pz(double t) { return bez(upperZ, c1z, c2z, lowerZ, t); }

    private static double bez(double p0, double p1, double p2, double p3, double t) {
        double u = 1 - t;
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
    }

    /** Horizontal distance from the tube's centre line at the given y, or MAX if y is outside the tube's span. */
    public double distToTube(double x, double y, double z) {
        if (y > upperY + 2 || y < lowerY - 2) return Double.MAX_VALUE;
        double t = (y - upperY) / (lowerY - upperY);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        double dx = x - px(t), dz = z - pz(t);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Signed distance to the upper landing surface, negative inside. */
    public double distToUpperLanding(double x, double y, double z) {
        return Math.sqrt(sq(x - upperX) + sq((y - upperY) * LANDING_Y_SCALE) + sq(z - upperZ)) - landingRadius;
    }

    /** Signed distance to the lower landing surface, negative inside. */
    public double distToLowerLanding(double x, double y, double z) {
        return Math.sqrt(sq(x - lowerX) + sq((y - lowerY) * LANDING_Y_SCALE) + sq(z - lowerZ)) - landingRadius;
    }

    /** Signed distance (blocks) to the nearest landing surface: negative inside. */
    public double distToLandings(double x, double y, double z) {
        return Math.min(distToUpperLanding(x, y, z), distToLowerLanding(x, y, z));
    }

    /** Horizontal distance from the upper landing's centre. */
    public double distXZUpper(double x, double z) { return Math.sqrt(sq(x - upperX) + sq(z - upperZ)); }
    public double distXZLower(double x, double z) { return Math.sqrt(sq(x - lowerX) + sq(z - lowerZ)); }

    private static double sq(double v) { return v * v; }
}
