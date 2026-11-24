package com.explorationremix.creature.body;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * The game's rock for a body: each block's collision boxes as the level has them (a slab half a block, a carpet a
 * sliver, plants, vines and water nothing). Made {@code loadedOnly}, a block in a chunk that is not loaded is nothing,
 * and no chunk is ever loaded to read one (for a body reaching far from where it is ticked: the giant Skein's, 0.21.5).
 */
public final class LevelTerrain implements Terrain {
    private final BlockGetter level;
    @Nullable private final LevelReader loaded;
    private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
    private int lastX = Integer.MIN_VALUE, lastZ;
    private boolean lastLoaded;

    public LevelTerrain(BlockGetter level) {
        this(level, false);
    }

    public LevelTerrain(BlockGetter level, boolean loadedOnly) {
        this.level = level;
        this.loaded = loadedOnly && level instanceof LevelReader r ? r : null;
    }

    /** Forgets which chunks were loaded (once a tick: a chunk may have loaded or gone since). */
    public void fresh() {
        lastX = Integer.MIN_VALUE;
    }

    @Override
    public void boxes(int x, int y, int z, BoxSink sink) {
        if (loaded != null) {
            int cx = x >> 4, cz = z >> 4;
            if (cx != lastX || cz != lastZ) {
                lastX = cx;
                lastZ = cz;
                lastLoaded = loaded.hasChunk(cx, cz);
            }
            if (!lastLoaded) return;
        }
        at.set(x, y, z);
        BlockState state = level.getBlockState(at);
        if (state.isAir()) return;
        VoxelShape shape = state.getCollisionShape(level, at);
        if (shape.isEmpty()) return;
        if (shape == Shapes.block()) {
            sink.box(x, y, z, x + 1, y + 1, z + 1);
            return;
        }
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> sink.box(x + x0, y + y0, z + z0, x + x1, y + y1, z + z1));
    }
}
