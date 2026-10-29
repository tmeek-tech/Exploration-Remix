package com.explorationremix.world.terrain;

/**
 * What lies over a column: the pack's ground and what grows on it. The game answers from the pack's own noise and
 * biomes ({@code SurfaceOracle}); tests answer from a made-up world. Answers must be deterministic, cheap after the
 * first ask for a column, and safe to ask from any thread.
 */
public interface Overhead {
    /** The surface over (x, z). The game answers per 4x4 column (the biome grid). Never null. */
    Surface at(int x, int z);

    /**
     * @param y        the ground: the top solid block of the pack's terrain (trees and water not counted)
     * @param trees    the kind of trees the surface biome grows, {@link RootKind#NONE} for none
     * @param wet      an ocean, river or beach: nothing is carved under it
     * @param ravineOk a ravine may open to the sky here: dry land well above sea level, not a swamp, and no village or
     *                 other surface structure expected in its biome
     */
    record Surface(int y, RootKind trees, boolean wet, boolean ravineOk) {}
}
