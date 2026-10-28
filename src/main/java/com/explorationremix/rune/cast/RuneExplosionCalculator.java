package com.explorationremix.rune.cast;

import com.explorationremix.registry.ERTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Optional;

/** Blast breaks layer stone as if it were ordinary rock. Band stone keeps its full resistance and never breaks. */
public final class RuneExplosionCalculator extends ExplosionDamageCalculator {
    @Override
    public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, FluidState fluid) {
        if (state.is(ERTags.LAYER_STONE)) return Optional.of(1.2F);
        return super.getBlockExplosionResistance(explosion, level, pos, state, fluid);
    }
}
