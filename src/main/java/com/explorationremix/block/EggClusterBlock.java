package com.explorationremix.block;

import com.explorationremix.creature.NestDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A gate nest's egg cluster. Break every cluster of a nest and the gate falls quiet for the rest of the run. */
public class EggClusterBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 10, 14);

    public EggClusterBlock(Properties properties) { super(properties); }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel sl) NestDirector.onClusterBroken(sl, pos);
        super.onRemove(state, level, pos, newState, moved);
    }
}
