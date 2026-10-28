package com.explorationremix.world.plan;

/**
 * Cheap seeded value noise with smooth interpolation. Used for plan-level decisions (which hunting biome a patch
 * belongs to, how wide a hall is here) so the plan stays independent of Minecraft's noise registry.
 */
public final class PlanNoise {
    private final long seed;

    public PlanNoise(long seed) { this.seed = seed; }

    private double lattice(long x, long y, long z) {
        return Hashing.unit(Hashing.mix(seed, x, y, z)) * 2.0 - 1.0;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    /** 2D value noise in [-1, 1]. */
    public double noise2(double x, double z) {
        long x0 = (long) Math.floor(x), z0 = (long) Math.floor(z);
        double fx = smooth(x - x0), fz = smooth(z - z0);
        double a = lattice(x0, 0, z0), b = lattice(x0 + 1, 0, z0);
        double c = lattice(x0, 0, z0 + 1), d = lattice(x0 + 1, 0, z0 + 1);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    /** 3D value noise in [-1, 1]. */
    public double noise3(double x, double y, double z) {
        long x0 = (long) Math.floor(x), y0 = (long) Math.floor(y), z0 = (long) Math.floor(z);
        double fx = smooth(x - x0), fy = smooth(y - y0), fz = smooth(z - z0);
        double c000 = lattice(x0, y0, z0), c100 = lattice(x0 + 1, y0, z0);
        double c010 = lattice(x0, y0 + 1, z0), c110 = lattice(x0 + 1, y0 + 1, z0);
        double c001 = lattice(x0, y0, z0 + 1), c101 = lattice(x0 + 1, y0, z0 + 1);
        double c011 = lattice(x0, y0 + 1, z0 + 1), c111 = lattice(x0 + 1, y0 + 1, z0 + 1);
        double x00 = lerp(c000, c100, fx), x10 = lerp(c010, c110, fx);
        double x01 = lerp(c001, c101, fx), x11 = lerp(c011, c111, fx);
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    /** Two octaves of 2D noise, still in [-1, 1]. */
    public double fbm2(double x, double z, double scale) {
        return (noise2(x / scale, z / scale) * 0.7 + noise2(x / scale * 2.3 + 17.1, z / scale * 2.3 - 5.7) * 0.3);
    }

    public double fbm3(double x, double y, double z, double scale) {
        return noise3(x / scale, y / scale, z / scale) * 0.7
                + noise3(x / scale * 2.1 + 11.3, y / scale * 2.1 - 3.1, z / scale * 2.1 + 7.9) * 0.3;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
