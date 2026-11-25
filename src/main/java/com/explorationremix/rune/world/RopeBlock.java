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

/**
 * A Rope arrow's rope of light (BowRunes): a glowing strand hanging from where the arrow stuck down to the floor. You
 * climb it as you climb a ladder, and walk through it; {@code fast} (the rune's second phase) is climbed twice as fast
 * (WallClimb), and {@code end} marks the bottom block, drawn with a frayed end. A rope that reaches a wall's lip or hangs
 * from a ledge's edge has {@code lip} on its top block, level with the top: drawn bending over onto it, toward
 * {@code facing}, so it lies on the top instead of standing up past it. It drops nothing, and goes back to air when its
 * time is up (TempBlock).
 */
public class RopeBlock extends TempBlock {
    public static final BooleanProperty FAST = BooleanProperty.create("fast");
    public static final BooleanProperty END = BooleanProperty.create("end");
    public static final BooleanProperty LIP = BooleanProperty.create("lip");
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Block.box(6.5, 0, 6.5, 9.5, 16, 9.5);

    public RopeBlock(Properties props) {
        super(Kind.ROPE, props);
        registerDefaultState(stateDefinition.any().setValue(FAST, false).setValue(END, false).setValue(LIP, false).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FAST, END, LIP, FACING);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }

    /** Climbed like a ladder, by anyone in it. */
    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) { return true; }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // now and then a glint of light runs off the strand, so a rope reads from across a dark cave
        if (random.nextInt(8) != 0) return;
        level.addParticle(net.minecraft.core.particles.ParticleTypes.WAX_OFF, pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.2,
                pos.getY() + random.nextDouble(), pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.2, 0.0, -0.02, 0.0);
    }
}
