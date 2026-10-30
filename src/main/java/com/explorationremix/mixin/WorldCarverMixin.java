package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepMaterials;
import com.explorationremix.world.terrain.Entrances;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No lava in the Upper Caves: the cave and canyon carvers fill everything they cut below their lava level (8 blocks
 * over the old floor) with lava, past the aquifer; in the deepened overworld they ask the aquifer instead, which gives
 * water there (see {@link NoiseBasedAquiferMixin}), or air where the deep world keeps the caves dry, as round the first
 * gate. The carvers also leave the rock that holds an entrance's water (see {@link Entrances#seals}), so no tunnel
 * hangs a pool over its air.
 */
@Mixin(WorldCarver.class)
public abstract class WorldCarverMixin {
    @Inject(method = "getCarveState", at = @At("HEAD"), cancellable = true)
    private void er$keepSeals(CarvingContext ctx, CarverConfiguration config, BlockPos pos, Aquifer aquifer, CallbackInfoReturnable<BlockState> cir) {
        if (!DeepWorld.installed() || ctx.getMinGenY() != DeepWorld.floorY()) return;
        Entrances e = DeepWorld.entrances();
        if (e != null && e.seals(pos.getX(), pos.getY(), pos.getZ()) && e.carve(pos.getX(), pos.getY(), pos.getZ()) > 0) cir.setReturnValue(null);
    }

    @Inject(method = "getCarveState", at = @At("RETURN"), cancellable = true)
    private void er$waterNotLava(CarvingContext ctx, CarverConfiguration config, BlockPos pos, Aquifer aquifer, CallbackInfoReturnable<BlockState> cir) {
        BlockState s = cir.getReturnValue();
        if (s == null || !s.is(Blocks.LAVA) || !DeepWorld.installed() || ctx.getMinGenY() != DeepWorld.floorY()) return;
        BlockState a = aquifer.computeSubstance(new DensityFunction.SinglePointContext(pos.getX(), pos.getY(), pos.getZ()), 0.0);
        cir.setReturnValue(a != null && a.is(Blocks.LAVA) ? DeepMaterials.WATER : a);
    }
}
