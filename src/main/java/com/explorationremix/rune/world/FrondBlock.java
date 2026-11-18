package com.explorationremix.rune.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * A struck silver fern's fronds, unrolled down a wall (RunePlants): a curtain of them lying against the rock, or a
 * strand hanging free where the rock falls away, or below a hanging frond. {@code facing} points away from the wall it
 * lies on, or is DOWN for a strand hanging free; {@code bottom} marks a column's lowest block, drawn with the fronds'
 * ends, and {@code up} the highest block of a column beside the fern's, drawn with the fronds' tops.
 * You climb it as you would vines, and walk through it. It drops nothing, and goes back to air when the fronds roll up
 * (TempBlock).
 */
public class FrondBlock extends TempBlock {
    public static final DirectionProperty FACING = DirectionProperty.create("facing", Direction.NORTH, Direction.SOUTH, Direction.WEST,
            Direction.EAST, Direction.DOWN);
    public static final BooleanProperty TIP = BlockStateProperties.BOTTOM;
    public static final BooleanProperty TOP = BlockStateProperties.UP;
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        // a thin sheet against the rock (the rock to the facing's back), like vines; free, a slim column
        SHAPES.put(Direction.NORTH, Block.box(0, 0, 13, 16, 16, 16));
        SHAPES.put(Direction.SOUTH, Block.box(0, 0, 0, 16, 16, 3));
        SHAPES.put(Direction.WEST, Block.box(13, 0, 0, 16, 16, 16));
        SHAPES.put(Direction.EAST, Block.box(0, 0, 0, 3, 16, 16));
        SHAPES.put(Direction.DOWN, Block.box(4, 0, 4, 12, 16, 12));
    }

    public FrondBlock(Properties props) {
        super(Kind.FROND, props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN).setValue(TIP, false).setValue(TOP, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TIP, TOP);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** Climbed like vines, by anyone in it. */
    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) { return true; }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // now and then a silver glint drifts off the fronds, so a curtain reads in a dark rift
        if (random.nextInt(10) != 0) return;
        Direction f = state.getValue(FACING);
        double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.8, y = pos.getY() + random.nextDouble(), z = pos.getZ() + 0.5
                + (random.nextDouble() - 0.5) * 0.8;
        if (f != Direction.DOWN) {
            // just off the rock it lies on
            x = pos.getX() + 0.5 - f.getStepX() * 0.38 + (f.getStepX() == 0 ? (random.nextDouble() - 0.5) * 0.8 : 0.0);
            z = pos.getZ() + 0.5 - f.getStepZ() * 0.38 + (f.getStepZ() == 0 ? (random.nextDouble() - 0.5) * 0.8 : 0.0);
        }
        level.addParticle(net.minecraft.core.particles.ParticleTypes.WAX_OFF, x, y, z, 0.0, -0.01, 0.0);
    }
}
