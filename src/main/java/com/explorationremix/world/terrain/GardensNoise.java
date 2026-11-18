package com.explorationremix.world.terrain;

/**
 * The Deep Gardens' noise (round 2, 2026-11-18), ported exactly from the offline previews ({@code scripts/noise.py} in
 * project files {@code deep-gardens/shape-options-r2}): Ken Perlin's improved noise over permutations shuffled the way
 * numpy's legacy {@code RandomState(seed).permutation(256)} shuffles them (MT19937, init_genrand, masked rejection), so a
 * world of the preview's seed grows the caves the page showed. Everything is a pure function of the seed and the position.
 */
public final class GardensNoise {
    private GardensNoise() {}

    // ============================================================================================ permutations

    /** {@code np.random.RandomState(seed & 0x7FFFFFFF).permutation(256)}, doubled to 512 entries (noise.perm). */
    public static int[] perm(long seed) {
        Mt mt = new Mt((int) (seed & 0x7FFFFFFFL));
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i >= 1; i--) {
            int j = mt.interval(i);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        int[] out = new int[512];
        System.arraycopy(p, 0, out, 0, 256);
        System.arraycopy(p, 0, out, 256, 256);
        return out;
    }

    /** MT19937 as numpy seeds it from an integer (init_genrand) and draws from it for a shuffle (random_interval). */
    static final class Mt {
        private final int[] mt = new int[624];
        private int idx;

        Mt(int seed) {
            mt[0] = seed;
            for (int i = 1; i < 624; i++) mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
            idx = 624;
        }

        int next() {
            if (idx >= 624) {
                for (int k = 0; k < 624; k++) {
                    int y = (mt[k] & 0x80000000) | (mt[(k + 1) % 624] & 0x7fffffff);
                    int v = mt[(k + 397) % 624] ^ (y >>> 1);
                    if ((y & 1) != 0) v ^= 0x9908b0df;
                    mt[k] = v;
                }
                idx = 0;
            }
            int y = mt[idx++];
            y ^= y >>> 11;
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            y ^= y >>> 18;
            return y;
        }

        /** A uniform integer in [0, max] by masked rejection (numpy's random_interval for max under 2^32). */
        int interval(int max) {
            if (max == 0) return 0;
            int mask = max;
            mask |= mask >>> 1;
            mask |= mask >>> 2;
            mask |= mask >>> 4;
            mask |= mask >>> 8;
            mask |= mask >>> 16;
            long v;
            do {
                v = (next() & 0xFFFFFFFFL) & mask;
            } while (v > max);
            return (int) v;
        }
    }

    // ============================================================================================ noise

    private static double fade(double t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }

    private static double grad(int h, double x, double y, double z) {
        h &= 15;
        double u = h < 8 ? x : y;
        double v;
        if (h < 4) v = y;
        else if (h == 12 || h == 14) v = x;
        else v = z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** Ken Perlin's improved noise, about -1..1 (noise.n3). */
    public static double n3(int[] p, double x, double y, double z) {
        double fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
        int X = ((int) (long) fx) & 255, Y = ((int) (long) fy) & 255, Z = ((int) (long) fz) & 255;
        x -= fx;
        y -= fy;
        z -= fz;
        double u = fade(x), v = fade(y), w = fade(z);
        int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z;
        int B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
        double g1 = grad(p[AA], x, y, z), g2 = grad(p[BA], x - 1, y, z);
        double g3 = grad(p[AB], x, y - 1, z), g4 = grad(p[BB], x - 1, y - 1, z);
        double g5 = grad(p[AA + 1], x, y, z - 1), g6 = grad(p[BA + 1], x - 1, y, z - 1);
        double g7 = grad(p[AB + 1], x, y - 1, z - 1), g8 = grad(p[BB + 1], x - 1, y - 1, z - 1);
        double l1 = g1 + u * (g2 - g1), l2 = g3 + u * (g4 - g3);
        double l3 = g5 + u * (g6 - g5), l4 = g7 + u * (g8 - g7);
        double m1 = l1 + v * (l2 - l1), m2 = l3 + v * (l4 - l3);
        return m1 + w * (m2 - m1);
    }

    private static final double C35 = 0.8191520442889918, S35 = 0.573576436351046;

    /** A slice of the 3D noise away from the lattice planes, turned 35 degrees (noise.n2). */
    public static double n2(int[] p, double x, double z) {
        return n3(p, C35 * x - S35 * z, 0.4183, S35 * x + C35 * z) * 1.25;
    }

    public static double fbm2(int[] p, double x, double z, int octaves) {
        double a = 1, f = 1, t = 0, norm = 0;
        for (int i = 0; i < octaves; i++) {
            t += a * n2(p, x * f + 17.3 * i, z * f - 9.1 * i);
            norm += a;
            a *= 0.5;
            f *= 2.03;
        }
        return t / norm * 1.35;
    }

    public static double fbm3(int[] p, double x, double y, double z, int octaves) {
        double a = 1, f = 1, t = 0, norm = 0;
        for (int i = 0; i < octaves; i++) {
            t += a * n3(p, x * f + 17.3 * i, y * f + 4.7 * i, z * f - 9.1 * i);
            norm += a;
            a *= 0.5;
            f *= 2.03;
        }
        return t / norm * 1.35;
    }

    public static double smooth(double t) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }

    public static double ramp(double v, double lo, double hi) { return smooth((v - lo) / (hi - lo)); }

    public static double clip(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** noise.hash2: a 32-bit mix of a seed and two integers, as a unit double (int64 arithmetic, as numba runs it). */
    public static double hash2(long seed, long i, long j) {
        long h = (seed * 0x9E3779B1L + i * 0x85EBCA77L + j * 0xC2B2AE3DL) & 0xFFFFFFFFL;
        h ^= h >>> 15;
        h = (h * 0x2C1B3C6DL) & 0xFFFFFFFFL;
        h ^= h >>> 12;
        h = (h * 0x297A2D39L) & 0xFFFFFFFFL;
        h ^= h >>> 15;
        return h / 4294967296.0;
    }

    /** gen3.hv: the namespaced cell hash of the vents' and halls' sites (spec 5.3). */
    public static double hv(long seed, int ns, int f, int k, int j, long i, long kk) {
        return hash2(seed + ((long) ns << 20) + ((long) f << 12) + ((long) k << 6) + j, 7919L * i, 104729L * kk);
    }

    /**
     * A fractal sum at a column, optionally domain-warped first by a shared warp noise (fields.fbm2_grid at one column):
     * octaves 0 or less is a single n2 without the fractal's gain.
     */
    public static double field(int[] p, double scale, int octaves, double wx, double wz, int[] warp, double warpAmp, double warpScale) {
        if (warpAmp > 0) {
            double a = warpAmp * n2(warp, wx / warpScale, wz / warpScale);
            double b = warpAmp * n2(warp, wx / warpScale + 41.0, wz / warpScale - 17.0);
            wx += a;
            wz += b;
        }
        return octaves <= 0 ? n2(p, wx / scale, wz / scale) : fbm2(p, wx / scale, wz / scale, octaves);
    }

    /** fields.zero_line_dist at one column: distance (blocks, first order) to the zero line of a warped 2D noise. */
    public static double zeroLineDist(int[] p, double scale, double x, double z, int[] warp, double warpAmp, double warpScale) {
        double wx = x, wz = z;
        if (warpAmp > 0) {
            wx += warpAmp * n2(warp, x / warpScale, z / warpScale);
            wz += warpAmp * n2(warp, x / warpScale + 41.0, z / warpScale - 17.0);
        }
        double e = 0.75;
        double v = n2(p, wx / scale, wz / scale);
        double gx = (n2(p, (wx + e) / scale, wz / scale) - n2(p, (wx - e) / scale, wz / scale)) / (2 * e);
        double gz = (n2(p, wx / scale, (wz + e) / scale) - n2(p, wx / scale, (wz - e) / scale)) / (2 * e);
        double g = Math.sqrt(gx * gx + gz * gz);
        return Math.abs(v) / Math.max(g, 0.3 / scale);
    }
}
