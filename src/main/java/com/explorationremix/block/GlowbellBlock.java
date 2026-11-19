package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The glowbell. Struck by a rune it rings (0.18.7, RunePlants): a ring of light goes out from it, and the creatures it
 * passes glow through the rock for a few seconds. Then it rests ({@code resting}): its glow dims to an ember of itself
 * for {@link #REST} ticks, when it can't ring, and brightens again.
 */
public class GlowbellBlock extends CavePlantBlock {
    public static final BooleanProperty RESTING = BooleanProperty.create("resting");
    /** How long a rung glowbell rests (ticks). */
    public static final int REST = 200;
    /** Its light, awake and resting. */
    public static final int LIGHT = 9, RESTING_LIGHT = 2;

    public GlowbellBlock(Properties properties, VoxelShape shape) {
        super(properties.lightLevel(s -> s.getValue(RESTING) ? RESTING_LIGHT : LIGHT), shape);
        registerDefaultState(stateDefinition.any().setValue(RESTING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RESTING);
    }

    /** Rung: it dims, and wakes {@link #REST} ticks on. */
    public static void rest(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state.setValue(RESTING, true), Block.UPDATE_ALL);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!level.isClientSide && state.getValue(RESTING) && !(old.is(this) && old.getValue(RESTING))) level.scheduleTick(pos, this, REST);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(RESTING)) return;
        level.setBlock(pos, state.setValue(RESTING, false), Block.UPDATE_ALL);
        // awake again: a glint at its mouth
        level.sendParticles(ParticleTypes.WAX_OFF, pos.getX() + 0.5, pos.getY() + 0.55, pos.getZ() + 0.5, 3, 0.15, 0.15, 0.15, 0.0);
    }
}
