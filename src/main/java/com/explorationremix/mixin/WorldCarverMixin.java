package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No lava in the Upper Caves: the cave and canyon carvers fill everything they cut below their lava level (8 blocks
 * over the old floor) with lava, past the aquifer; in the deepened overworld they fill it with water, as the aquifer
 * does round them.
 */
@Mixin(WorldCarver.class)
public abstract class WorldCarverMixin {
    @Inject(method = "getCarveState", at = @At("RETURN"), cancellable = true)
    private void er$waterNotLava(CarvingContext ctx, CarverConfiguration config, BlockPos pos, Aquifer aquifer, CallbackInfoReturnable<BlockState> cir) {
        BlockState s = cir.getReturnValue();
        if (s == null || !s.is(Blocks.LAVA) || !DeepWorld.installed() || ctx.getMinGenY() != DeepWorld.floorY()) return;
        cir.setReturnValue(DeepMaterials.WATER);
    }
}
