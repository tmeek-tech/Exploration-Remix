package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * A two-block plant of the vent field in a neon colour (0.20.2: the tall filament grass), as {@link NeonPlantBlock} is
 * one block high: both halves carry the colour, and spores lift off its upper half.
 */
public class TallNeonPlantBlock extends TallCavePlantBlock {
    private final int spores;

    public TallNeonPlantBlock(Properties properties, VoxelShape lower, VoxelShape upper, int spores) {
        super(properties, lower, upper);
        this.spores = spores;
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(NeonBlock.NEON, Neon.MAGENTA));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(NeonBlock.NEON);
    }

    /** The upper half in the lower half's colour (vanilla's places the default state's, magenta). */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        BlockPos up = pos.above();
        level.setBlock(up, copyWaterloggedFrom(level, up, state.setValue(HALF, DoubleBlockHalf.UPPER)), 3);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) { return NeonBlock.stack(this, state.getValue(NeonBlock.NEON)); }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (spores > 0 && state.getValue(HALF) == DoubleBlockHalf.UPPER && random.nextInt(spores) == 0)
            NeonPlantBlock.spore(level, pos, state.getValue(NeonBlock.NEON), 0.6, random);
    }

    /** Sets this plant down at {@code pos} in a colour, both halves (flags as {@link Level#setBlock}). */
    public void placeAt(Level level, BlockPos pos, Neon neon, int flags) {
        BlockState lower = defaultBlockState().setValue(NeonBlock.NEON, neon);
        level.setBlock(pos, lower, flags);
        level.setBlock(pos.above(), lower.setValue(HALF, DoubleBlockHalf.UPPER), flags);
    }
}
