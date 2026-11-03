package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Bones and scraps lying round a Mossback den: rib bones and vertebrae, a cracked skull, feathers and fur, or the
 * mossy scales and torn hide the lizards shed. A sign that a den is near, so it is thin litter on the floor and nothing
 * else: no collision at all (the lizards' paths and everything else walk through it), a one-pixel outline to aim at,
 * and it lies only on a sturdy floor, like a carpet. {@link #LOOK} picks which of the four it shows and {@link #FACING}
 * turns it, so a scatter of them never repeats one picture.
 */
public class BoneScrapsBlock extends HorizontalDirectionalBlock {
    /** Which litter it shows: 0 ribs and vertebrae, 1 a skull fragment, 2 feathers and fur, 3 shed scales and hide. */
    public static final IntegerProperty LOOK = IntegerProperty.create("look", 0, 3);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 1, 16);

    public BoneScrapsBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LOOK, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, LOOK); }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }

    /** Placed by hand: turned to face the player, its look taken from where it lies (the same on client and server). */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        int look = (int) Math.floorMod(Mth.getSeed(ctx.getClickedPos()) >> 16, 4L);
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite()).setValue(LOOK, look);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        if (dir == Direction.DOWN && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, dir, other, level, pos, otherPos);
    }
}
