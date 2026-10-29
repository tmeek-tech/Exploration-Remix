package com.explorationremix.world.terrain;

/**
 * Seeded gradient (Perlin) noise in two and three dimensions. Output is roughly in [-1, 1]. Gradient noise has no
 * visible lattice, unlike value noise, so cave walls and stone patches never line up with the block grid.
 */
public final class Perlin {
    private final int[] p = new int[512];
    private final double ox, oy, oz;

    public Perlin(long seed) {
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) perm[i] = i;
        long s = seed;
        for (int i = 255; i > 0; i--) {
            s = Hashing.mix(s, i, 0x51, 0x7F);
            int j = (int) Long.remainderUnsigned(s, i + 1L);
            int t = perm[i]; perm[i] = perm[j]; perm[j] = t;
        }
        for (int i = 0; i < 512; i++) p[i] = perm[i & 255];
        ox = Hashing.unit(Hashing.mix(seed, 1, 2, 3)) * 256.0;
        oy = Hashing.unit(Hashing.mix(seed, 4, 5, 6)) * 256.0;
        oz = Hashing.unit(Hashing.mix(seed, 7, 8, 9)) * 256.0;
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static int floor(double v) { int i = (int) v; return v < i ? i - 1 : i; }

    private static double grad3(int h, double x, double y, double z) {
        switch (h & 15) {
            case 0: return x + y;
            case 1: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x + z;
            case 5: return -x + z;
            case 6: return x - z;
            case 7: return -x - z;
            case 8: return y + z;
            case 9: return -y + z;
            case 10: return y - z;
            case 11: return -y - z;
            case 12: return x + y;
            case 13: return -y + z;
            case 14: return -x + y;
            default: return -y - z;
        }
    }

    private static double grad2(int h, double x, double y) {
        switch (h & 7) {
            case 0: return x + y;
            case 1: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x * 1.4142;
            case 5: return -x * 1.4142;
            case 6: return y * 1.4142;
            default: return -y * 1.4142;
        }
    }

    /** 3D gradient noise, roughly in [-1, 1]. */
    public double noise3(double x, double y, double z) {
        x += ox; y += oy; z += oz;
        int X = floor(x), Y = floor(y), Z = floor(z);
        double fx = x - X, fy = y - Y, fz = z - Z;
        X &= 255; Y &= 255; Z &= 255;
        double u = fade(fx), v = fade(fy), w = fade(fz);
        int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z;
        int B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
        return lerp(w,
                lerp(v, lerp(u, grad3(p[AA], fx, fy, fz), grad3(p[BA], fx - 1, fy, fz)),
                        lerp(u, grad3(p[AB], fx, fy - 1, fz), grad3(p[BB], fx - 1, fy - 1, fz))),
                lerp(v, lerp(u, grad3(p[AA + 1], fx, fy, fz - 1), grad3(p[BA + 1], fx - 1, fy, fz - 1)),
                        lerp(u, grad3(p[AB + 1], fx, fy - 1, fz - 1), grad3(p[BB + 1], fx - 1, fy - 1, fz - 1))));
    }

    /** 2D gradient noise, roughly in [-1, 1]. */
    public double noise2(double x, double z) {
        x += ox; z += oz;
        int X = floor(x), Z = floor(z);
        double fx = x - X, fz = z - Z;
        X &= 255; Z &= 255;
        double u = fade(fx), v = fade(fz);
        int a = p[X] + Z, b = p[X + 1] + Z;
        return 0.75 * lerp(v, lerp(u, grad2(p[a], fx, fz), grad2(p[b], fx - 1, fz)),
                lerp(u, grad2(p[a + 1], fx, fz - 1), grad2(p[b + 1], fx - 1, fz - 1)));
    }

    /** Fractal 2D noise: {@code octaves} layers, each twice the frequency and half the weight. Normalised to about [-1, 1]. */
    public double fbm2(double x, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise2(x * f + i * 31.7, z * f - i * 17.3);
            norm += amp;
            amp *= 0.5;
            f *= 2.03;
        }
        return sum / norm * 1.35;
    }

    /** Fractal 3D noise, normalised to about [-1, 1]. */
    public double fbm3(double x, double y, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise3(x * f + i * 31.7, y * f + i * 11.1, z * f - i * 17.3);
            norm += amp;
            amp *= 0.5;
            f *= 2.03;
        }
        return sum / norm * 1.3;
    }
}
