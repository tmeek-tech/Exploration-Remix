package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Tube worms on a vent's steep wall (0.20.6; Taran's card pick, "Lower half, on ledges and walls"): the tubes grow
 * out of the rock a little way and curve up, so the plumes open upwards as the floor's do. {@link #FACING} is the way
 * they lean out, away from the wall they hold to. Everything else as {@link TubeWormsBlock} (whose item places them
 * against a wall); no offset, so they stay on their wall.
 */
public class WallTubeWormsBlock extends TubeWormsBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** The tubes' room: the half of the block against the wall. */
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        SHAPES.put(Direction.NORTH, Block.box(1, 0, 8, 15, 15, 16));
        SHAPES.put(Direction.SOUTH, Block.box(1, 0, 0, 15, 15, 8));
        SHAPES.put(Direction.EAST, Block.box(0, 0, 1, 8, 15, 15));
        SHAPES.put(Direction.WEST, Block.box(8, 0, 1, 16, 15, 15));
    }

    public WallTubeWormsBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPES.get(state.getValue(FACING)); }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction out = state.getValue(FACING);
        BlockPos on = pos.relative(out.getOpposite());
        return level.getBlockState(on).isFaceSturdy(level, on, out);
    }

    /** Against the wall looked at most nearly, if it holds them. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState s = defaultBlockState();
        for (Direction d : ctx.getNearestLookingDirections()) {
            if (!d.getAxis().isHorizontal()) continue;
            s = s.setValue(FACING, d.getOpposite());
            if (s.canSurvive(ctx.getLevel(), ctx.getClickedPos())) return s;
        }
        return null;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) { return state.setValue(FACING, rot.rotate(state.getValue(FACING))); }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
}
