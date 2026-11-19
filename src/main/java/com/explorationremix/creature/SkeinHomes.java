package com.explorationremix.creature;

import com.explorationremix.world.terrain.Hashing;

/**
 * Where the Blue Reaches' Skeins live from the start (0.17.0): the rules of the world-gen feature that sets them down
 * as each chunk is made ({@code world.feature.SkeinsFeature}), as plain numbers so they can be tested without a world.
 * Like the farm animals a new world starts with, they are there before any player comes, they stay where they are
 * when everyone leaves, and they go only when killed. {@link BlueReachesSpawner} brings a new one out now and then
 * where the caves round a player have none left ({@link BlueSpawnRules#wantSkein}).
 *
 * <p>A chunk is a Skein's home by a roll of the seed and its position ({@link #HOME_CHANCE}). Its Skein lies on the
 * rock of a cave in the chunk, inside the Blue Reaches, in the Deep Blue Rift (never in the Asurine Geodes, the refuge,
 * and since 0.19.8 never in the Deep Dark, Taran's ask), where its body has room to lie straight back from its head:
 * the first spot that will do of {@link #TRIES} random ones in the chunk, each looked for up and down from its height
 * as the spawner does. A chunk with no cave in reach of its tries has none, so the Skeins live where the caves are big.
 */
public final class SkeinHomes {
    private SkeinHomes() {}

    /**
     * The share of chunks that are a Skein's home. A little over half of the Blue Reaches' chunks have a cave a try
     * finds (54% in the test seed's first 600 chunks), so this gives about one Skein in every ten chunks, one every 50
     * blocks or so: about as many round a player as the old spawner kept there, one or two within 48 blocks (Taran's
     * pick, 17 Nov 2026).
     */
    public static final double HOME_CHANCE = 0.18;
    /** Random spots tried in a home chunk before it is given up. */
    public static final int TRIES = 16;
    /** Tries keep this far inside the chunk, so a Skein's box and the rock it reads stay in reach of the chunk's generation. */
    public static final int EDGE = 2;
    /** How far up and down from a try's height it looks for rock to set the Skein on (the spawner's scan). */
    public static final int SCAN = 10;

    private static final long SALT = 0x536B65696E486F6DL;      // "SkeinHom"

    /** Whether the chunk is a Skein's home. */
    public static boolean home(long seed, int chunkX, int chunkZ) {
        return Hashing.unit(Hashing.mix(seed ^ SALT, chunkX, chunkZ, 0)) < HOME_CHANCE;
    }

    /** The seed of the chunk's own random tries, so a world made again from its seed puts each Skein back where it was. */
    public static long chunkSeed(long seed, int chunkX, int chunkZ) {
        return Hashing.mix(seed ^ SALT, chunkX, chunkZ, 1);
    }

    /** The height of the scan's {@code k}th step out from {@code y0}, down and up by turns: y0, y0 - 1, y0 + 1, y0 - 2, ... */
    public static int scanY(int y0, int k) {
        return y0 + ((k & 1) == 0 ? k / 2 : -(k + 1) / 2);
    }
}
