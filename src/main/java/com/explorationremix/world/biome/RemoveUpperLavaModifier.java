package com.explorationremix.world.biome;

import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERBiomeModifiers;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;

import java.util.Set;

/**
 * No lava in the Upper Caves: takes away the features that hide lava in the rock above the old floor, where nothing
 * turns it to water (with the deep world on). Named by id, so a pack without the mod that adds one loads as well: WWOO's volcanic windswept
 * hills put up to a hundred blobs of lava a chunk into their stone, which pour into any cave or stair cut into them.
 */
public record RemoveUpperLavaModifier() implements BiomeModifier {
    public static final Codec<RemoveUpperLavaModifier> CODEC = Codec.unit(RemoveUpperLavaModifier::new);
    private static final Set<ResourceLocation> LAVA = Set.of(new ResourceLocation("wythers", "ores/ore_lava_volcanic"));

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.REMOVE || !ERConfig.INSTANCE.worldEnabled.get()) return;
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            builder.getGenerationSettings().getFeatures(step).removeIf(h -> h.unwrapKey().map(k -> LAVA.contains(k.location())).orElse(false));
        }
    }

    @Override
    public Codec<? extends BiomeModifier> codec() { return ERBiomeModifiers.REMOVE_UPPER_LAVA.get(); }
}
