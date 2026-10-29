package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A plant that grows only under water, like seagrass: lilac waterweed and lantern weed on Lantern Lake's bed, ember
 * anemones along the hot pools' rims. It is always waterlogged and holds on to its water: a bucket can't take it out,
 * and when the plant is broken or loses its floor, the water stays.
 */
public class SubmergedPlantBlock extends PoolPlantBlock {
    public SubmergedPlantBlock(Properties properties, VoxelShape shape) {
        super(properties, shape);
        registerDefaultState(stateDefinition.any().setValue(WATERLOGGED, true));
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return state.getValue(WATERLOGGED) && super.canSurvive(state, level, pos);
    }

    /** Only into still water, a whole block of it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        FluidState fluid = ctx.getLevel().getFluidState(ctx.getClickedPos());
        return fluid.is(FluidTags.WATER) && fluid.getAmount() == 8 ? defaultBlockState() : null;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        BlockState next = super.updateShape(state, dir, other, level, pos, otherPos);
        return next.isAir() && state.getValue(WATERLOGGED) ? Blocks.WATER.defaultBlockState() : next;
    }

    @Override
    public boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public ItemStack pickupBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        return ItemStack.EMPTY;
    }
}
