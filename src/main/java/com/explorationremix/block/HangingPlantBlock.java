package com.explorationremix.block;

import com.explorationremix.registry.ERTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Something that hangs from the block above it: from a solid ceiling, or from the plants tagged
 * {@code explorationremix:hanging_supports} (lantern pods hang among the wisteria).
 */
public class HangingPlantBlock extends Block {
    private final VoxelShape shape;

    public HangingPlantBlock(Properties properties, VoxelShape shape) {
        super(properties);
        this.shape = shape;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shape;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos up = pos.above();
        BlockState above = level.getBlockState(up);
        return above.isFaceSturdy(level, up, Direction.DOWN) || above.is(ERTags.HANGING_SUPPORTS);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        if (dir == Direction.UP && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, dir, other, level, pos, otherPos);
    }
}
