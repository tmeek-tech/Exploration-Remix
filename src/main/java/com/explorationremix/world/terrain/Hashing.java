package com.explorationremix.world.terrain;

/** Small, fast, deterministic hashing for world-plan randomness. */
public final class Hashing {
    private Hashing() {}

    public static long mix(long seed, long a, long b, long c) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = (h ^ a) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 31) ^ b) * 0x94D049BB133111EBL;
        h = (h ^ (h >>> 29) ^ c) * 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 32);
    }

    /** Uniform double in [0, 1). */
    public static double unit(long h) {
        return ((h >>> 11) & ((1L << 53) - 1)) * 0x1.0p-53;
    }

    public static double range(long h, double min, double max) {
        return min + unit(h) * (max - min);
    }
}
