package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomes;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** The deep biomes must be "possible" for the overworld's biome source or their features never generate. */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceMixin {
    @Inject(method = "possibleBiomes", at = @At("RETURN"), cancellable = true)
    private void er$possibleBiomes(CallbackInfoReturnable<Set<Holder<Biome>>> cir) {
        if (DeepWorld.installed() && DeepWorld.isDeepBiomeSource((BiomeSource) (Object) this)) {
            cir.setReturnValue(DeepBiomes.union(cir.getReturnValue()));
        }
    }
}
