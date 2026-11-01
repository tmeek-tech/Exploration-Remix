package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Bracket fungus growing out of a wall. {@code facing} points away from the wall it grows on. The shelves are firm
 * enough to stand on, so a wall of them is a way up. With an outline of its own it is anything else that grows out of
 * a wall, like the hanging silver ferns.
 */
public class ShelfFungusBlock extends HorizontalDirectionalBlock {
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        SHAPES.put(Direction.NORTH, Block.box(1, 5, 6, 15, 9, 16));
        SHAPES.put(Direction.SOUTH, Block.box(1, 5, 0, 15, 9, 10));
        SHAPES.put(Direction.EAST, Block.box(0, 5, 1, 10, 9, 15));
        SHAPES.put(Direction.WEST, Block.box(6, 5, 1, 16, 9, 15));
    }

    private final Map<Direction, VoxelShape> shapes;

    public ShelfFungusBlock(Properties properties) {
        this(properties, SHAPES);
    }

    /**
     * Something else growing out of a wall: {@code north} is its outline when it grows out of a wall to its south, and
     * the other three sides are turned from it. Plant properties (no collision) make it a plant you walk through.
     */
    public ShelfFungusBlock(Properties properties, VoxelShape north) {
        this(properties, turned(north));
    }

    private ShelfFungusBlock(Properties properties, Map<Direction, VoxelShape> shapes) {
        super(properties);
        this.shapes = shapes;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /** The outline facing north, and turned to face east, south and west, as the blockstate turns the model. */
    private static Map<Direction, VoxelShape> turned(VoxelShape north) {
        AABB b = north.bounds();
        double x0 = b.minX * 16, y0 = b.minY * 16, z0 = b.minZ * 16, x1 = b.maxX * 16, y1 = b.maxY * 16, z1 = b.maxZ * 16;
        Map<Direction, VoxelShape> m = new EnumMap<>(Direction.class);
        m.put(Direction.NORTH, Block.box(x0, y0, z0, x1, y1, z1));
        m.put(Direction.SOUTH, Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0));
        m.put(Direction.EAST, Block.box(16 - z1, y0, x0, 16 - z0, y1, x1));
        m.put(Direction.WEST, Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0));
        return m;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos wall = pos.relative(facing.getOpposite());
        return level.getBlockState(wall).isFaceSturdy(level, wall, facing);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        for (Direction dir : ctx.getNearestLookingDirections()) {
            if (dir.getAxis().isHorizontal()) {
                BlockState state = defaultBlockState().setValue(FACING, dir.getOpposite());
                if (state.canSurvive(ctx.getLevel(), ctx.getClickedPos())) return state;
            }
        }
        return null;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        if (dir == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, dir, other, level, pos, otherPos);
    }
}
