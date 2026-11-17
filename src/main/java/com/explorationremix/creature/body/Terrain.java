package com.explorationremix.creature.body;

/**
 * What a body needs to know of the world round it: the solid boxes of each block (the boxes a creature collides with,
 * so a slab is half a block, a carpet a sliver, and plants, vines and water nothing at all). Plain numbers, so tests
 * can build a world of their own (see LevelTerrain for the game's).
 */
public interface Terrain {
    /** Hands {@code sink} each collision box of the block at (x, y, z), in world coordinates (a full block: one unit box). */
    void boxes(int x, int y, int z, BoxSink sink);

    interface BoxSink {
        void box(double x0, double y0, double z0, double x1, double y1, double z1);
    }
}
