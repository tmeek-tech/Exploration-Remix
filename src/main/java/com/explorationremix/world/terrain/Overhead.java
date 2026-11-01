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
     * The lowest ground anywhere in the 4x4 column over (x, z), for what must stay under the ground in all sixteen of its
     * block columns (the plant caves). The game reads it at the corners of the column's noise cell; a made-up world
     * answers its ground.
     */
    default int low(int x, int z) { return at(x, z).y(); }

    /**
     * The top solid block between grid heights {@code y} (density {@code d > 0}) and {@code y + h} (density
     * {@code up <= 0}), with the density straight between them as the world interpolates it.
     */
    static int topBetween(int y, int h, double d, double up) {
        return y + Math.min(h - 1, (int) Math.ceil(h * d / (d - up)) - 1);
    }

    /**
     * The pack's own final density at a block, before the deep world changed it: positive in rock, negative in the
     * pack's caves (and in the air over the ground). Used to aim tunnels at the pack's caves. Not cached: ask sparingly.
     * A made-up world without caves answers 1 everywhere under its ground.
     */
    default double packDensity(int x, int y, int z) { return y <= at(x, z).y() ? 1.0 : -1.0; }

    /** The sea level: ground below it lies under water in swamps and wetlands even where no ocean or river is. */
    default int seaLevel() { return 63; }

    /** What covers the ground, for the entrances into the caves (see {@code Entrances}). */
    enum Cover {
        /** Trees: Root Vault clusters. */
        FOREST,
        /** Grass without trees (plains, meadows, savannas, snowy plains): sinkholes. */
        PLAINS,
        /** Sand: oasis hollows. */
        DESERT,
        /** Red sand and terracotta: oasis hollows in red rock. */
        BADLANDS,
        /** Anything else: no entrance of its own. */
        OTHER
    }

    /**
     * @param y        the ground: the top solid block of the pack's terrain (trees and water not counted)
     * @param trees    the kind of trees the surface biome grows, {@link RootKind#NONE} for none
     * @param wet      an ocean, river or beach: nothing is carved under it
     * @param ravineOk a ravine may open to the sky here: dry land well above sea level, not a swamp, and no village or
     *                 other surface structure expected in its biome
     * @param cover    what covers the ground ({@link Cover#FOREST} wherever trees grow)
     * @param cold     the surface biome is cold: snow falls on it (taigas, groves, snowy and frozen biomes)
     */
    record Surface(int y, RootKind trees, boolean wet, boolean ravineOk, Cover cover, boolean cold) {
        /** A warm surface whose cover follows its trees: a forest, or nothing in particular. */
        public Surface(int y, RootKind trees, boolean wet, boolean ravineOk) {
            this(y, trees, wet, ravineOk, trees.trees() ? Cover.FOREST : Cover.OTHER, false);
        }
    }
}
