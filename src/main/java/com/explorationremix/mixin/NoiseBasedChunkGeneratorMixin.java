package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomeResolver;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes biome placement below the pack's floor through the layer plan. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Redirect(method = "doCreateBiomes",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V"))
    private void er$fillBiomes(ChunkAccess chunk, BiomeResolver resolver, Climate.Sampler sampler) {
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        if (DeepWorld.installed() && DeepWorld.isDeepGenerator(self)) {
            chunk.fillBiomesFromNoise(new DeepBiomeResolver(resolver), sampler);
        } else {
            chunk.fillBiomesFromNoise(resolver, sampler);
        }
    }
}
