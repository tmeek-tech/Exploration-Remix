package com.explorationremix.world.terrain;

/**
 * Configurable numbers for one deep layer. Space sizes are starting values, not limits: each layer reads them as its
 * own kind of space (halls and lakes, rifts and geodes, terraced caverns, galleries).
 *
 * @param id          config and command name of the layer
 * @param height      blocks from the layer's top to its bottom
 * @param fluidDepth  the water (or lava) table sits this many blocks above the layer's bottom
 * @param lava        true when the layer's fluid is lava
 * @param heightMin   smallest height of the layer's main spaces
 * @param heightMax   largest height of the layer's main spaces
 * @param widthMin    smallest width of the layer's signature spaces
 * @param widthMax    largest width of the layer's signature spaces
 */
public record LayerSettings(String id, int height, int fluidDepth, boolean lava,
                            double heightMin, double heightMax, double widthMin, double widthMax) {
}
