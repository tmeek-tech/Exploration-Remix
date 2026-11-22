package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Small neon mushrooms (0.20.2, Taran's card pick: "Clusters round their stems"): one to three ({@link #MUSHROOMS}) in
 * a giant's colour, crowding round its stem and dotting the floor under its cap. Like vanilla's sea pickles, a cluster
 * takes another mushroom of its colour placed on it, up to three, and drops one for each.
 */
public class NeonMushroomsBlock extends NeonPlantBlock {
    public static final IntegerProperty MUSHROOMS = IntegerProperty.create("mushrooms", 1, 3);
    private static final VoxelShape[] SHAPES = {Block.box(4, 0, 4, 12, 9, 12), Block.box(2, 0, 2, 14, 8, 14), Block.box(2, 0, 2, 14, 9, 14)};

    public NeonMushroomsBlock(Properties properties, int spores) {
        super(properties, SHAPES[0], spores);
        registerDefaultState(stateDefinition.any().setValue(NeonBlock.NEON, Neon.MAGENTA).setValue(MUSHROOMS, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MUSHROOMS);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        Vec3 off = state.getOffset(level, pos);
        return SHAPES[state.getValue(MUSHROOMS) - 1].move(off.x, off.y, off.z);
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext ctx) {
        // only a mushroom of the cluster's own colour joins it
        if (!ctx.isSecondaryUseActive() && ctx.getItemInHand().is(asItem()) && state.getValue(MUSHROOMS) < 3
                && NeonBlock.neon(ctx.getItemInHand()) == state.getValue(NeonBlock.NEON)) return true;
        return super.canBeReplaced(state, ctx);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState there = ctx.getLevel().getBlockState(ctx.getClickedPos());
        if (there.is(this)) return there.setValue(MUSHROOMS, Math.min(3, there.getValue(MUSHROOMS) + 1));
        return super.getStateForPlacement(ctx);
    }
}
