package com.explorationremix.world.biome;

import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERBiomeModifiers;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;

/** Strips Create's striated stone layers from every biome when the config asks for it. */
public record RemoveCreateStriationsModifier() implements BiomeModifier {
    public static final Codec<RemoveCreateStriationsModifier> CODEC = Codec.unit(RemoveCreateStriationsModifier::new);
    private static final ResourceLocation STRIATED = new ResourceLocation("create", "striated_ores_overworld");

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.REMOVE || !ERConfig.INSTANCE.removeCreateStriations.get()) return;
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            builder.getGenerationSettings().getFeatures(step).removeIf(h -> h.unwrapKey().map(k -> k.location().equals(STRIATED)).orElse(false));
        }
    }

    @Override
    public Codec<? extends BiomeModifier> codec() { return ERBiomeModifiers.REMOVE_CREATE_STRIATIONS.get(); }

    @SuppressWarnings("unused")
    private static boolean isStriated(Holder<PlacedFeature> h) { return h.unwrapKey().map(k -> k.location().equals(STRIATED)).orElse(false); }
}
