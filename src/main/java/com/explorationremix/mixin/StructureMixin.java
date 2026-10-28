package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No vanilla or modded structure may start below the pack's floor; the deep world builds its own ruins. */
@Mixin(Structure.class)
public abstract class StructureMixin {
    @Inject(method = "isValidBiome", at = @At("HEAD"), cancellable = true)
    private static void er$noDeepStructures(Structure.GenerationStub stub, Structure.GenerationContext ctx, CallbackInfoReturnable<Boolean> cir) {
        if (DeepWorld.installed() && stub.position().getY() < DeepWorld.packFloor()
                && ctx.chunkGenerator() instanceof NoiseBasedChunkGenerator g && DeepWorld.isDeepGenerator(g)) {
            cir.setReturnValue(false);
        }
    }
}
