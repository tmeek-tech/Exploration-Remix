package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A plant two blocks tall that grows on any floor with a solid top, like {@link CavePlantBlock}: stone ledges as well
 * as moss and dirt. Both halves show the same texture variant, because DoublePlantBlock seeds the model choice of both
 * from the lower half's position.
 */
public class TallCavePlantBlock extends DoublePlantBlock {
    private final VoxelShape lower, upper;

    public TallCavePlantBlock(Properties properties, VoxelShape lower, VoxelShape upper) {
        super(properties);
        this.lower = lower;
        this.upper = upper;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        Vec3 off = state.getOffset(level, pos);
        return (state.getValue(HALF) == DoubleBlockHalf.LOWER ? lower : upper).move(off.x, off.y, off.z);
    }

    @Override
    protected boolean mayPlaceOn(BlockState below, BlockGetter level, BlockPos pos) {
        return below.isFaceSturdy(level, pos, Direction.UP);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        BlockPos below = pos.below();
        return mayPlaceOn(level.getBlockState(below), level, below);
    }
}
