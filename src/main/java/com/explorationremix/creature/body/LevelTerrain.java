package com.explorationremix.creature.body;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The game's rock for a body: each block's collision boxes as the level has them (a slab half a block, a carpet a
 * sliver, plants, vines and water nothing). Where no chunk is loaded there is nothing.
 */
public final class LevelTerrain implements Terrain {
    private final BlockGetter level;
    private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

    public LevelTerrain(BlockGetter level) {
        this.level = level;
    }

    @Override
    public void boxes(int x, int y, int z, BoxSink sink) {
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
