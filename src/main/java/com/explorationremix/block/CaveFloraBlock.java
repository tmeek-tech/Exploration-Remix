package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A cave plant that grows on any sturdy floor or ceiling, in air or under water. */
public class CaveFloraBlock extends BushBlock implements SimpleWaterloggedBlock {
    public enum Attach { FLOOR, CEILING }

    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final VoxelShape FLOOR_SHAPE = Block.box(2, 0, 2, 14, 13, 14);
    private static final VoxelShape CEILING_SHAPE = Block.box(2, 3, 2, 14, 16, 14);
    private final Attach attach;

    public CaveFloraBlock(Properties properties, Attach attach) {
        super(properties);
        this.attach = attach;
        registerDefaultState(stateDefinition.any().setValue(WATERLOGGED, false));
    }

    public Attach attach() { return attach; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return attach == Attach.CEILING ? CEILING_SHAPE : FLOOR_SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction dir = attach == Attach.CEILING ? Direction.UP : Direction.DOWN;
        BlockPos support = pos.relative(dir);
        return level.getBlockState(support).isFaceSturdy(level, support, dir.getOpposite());
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }
}
