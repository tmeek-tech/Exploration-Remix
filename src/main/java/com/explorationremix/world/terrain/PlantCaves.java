package com.explorationremix.world.terrain;

/**
 * Where the pack's ordinary caves in the Upper Caves grow plants: lush caves, or the Frost Fern Grotto under cold
 * ground. A growth field over the world, above zero where a cave becomes a plant cave: broad patches and smaller ones
 * across the map (about a third of the caves), plus a band 25 to 65 blocks under the ground (its depth wanders) where
 * nine caves in ten grow plants, so a way down from the surface crosses a plant cave almost anywhere. Over all the
 * caves from 18 to 120 blocks down, about half are plant caves.
 */
public final class PlantCaves {
    /** Plant caves start this far under the ground (the entrances hold the rock above). */
    public static final int MIN_DEPTH = 18;

    private final Perlin broadN, patchN, bandN;

    public PlantCaves(long seed) {
        long s = seed ^ 0x2545F4914F6CDD1DL;
        broadN = new Perlin(Hashing.mix(s, 1, 0, 0));
        patchN = new Perlin(Hashing.mix(s, 2, 0, 0));
        bandN = new Perlin(Hashing.mix(s, 3, 0, 0));
    }

    /** The growth field at a block {@code depth} blocks under the ground: a plant cave where it is above zero. */
    public double growth(int x, int y, int z, int depth) {
        if (depth < MIN_DEPTH) return -1;
        double dc = 45 + 20 * bandN.noise2(x / 300.0 + 7, z / 300.0);
        double band = (depth - dc) / 22.0;
        return 0.6 * broadN.noise2(x / 220.0, z / 220.0) + 0.25 * patchN.noise2(x / 70.0, z / 70.0)
                + 0.5 * Math.exp(-band * band) - 0.15;
    }
}
