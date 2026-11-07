package com.explorationremix.world.biome;

import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERBiomeModifiers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;

/**
 * Gives biomes the deep world places far under the ground a dark sky and fog: their special effects' sky and fog colours
 * only, nothing that generates (0.8.0). Vanilla's deep dark, which lies in the Blue Reaches (its caverns and ancient
 * cities), has a daylight blue sky and a pale fog, as vanilla puts it just over the bottom of the world, where the game
 * darkens fog and sky anyway; hundreds of blocks over the bottom nothing darkens them, so past the edge of the drawn world
 * (at a short render distance) the caverns opened onto a bright sky, under a shader pack with its clouds. With the deep
 * world off the deep dark is vanilla's, and so are its colours.
 */
public record DarkSkyModifier(HolderSet<Biome> biomes, int skyColor, int fogColor) implements BiomeModifier {
    public static final Codec<DarkSkyModifier> CODEC = RecordCodecBuilder.create(i -> i.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(DarkSkyModifier::biomes),
            Codec.INT.fieldOf("sky_color").forGetter(DarkSkyModifier::skyColor),
            Codec.INT.fieldOf("fog_color").forGetter(DarkSkyModifier::fogColor)
    ).apply(i, DarkSkyModifier::new));

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.MODIFY || !ERConfig.INSTANCE.worldEnabled.get() || !biomes.contains(biome)) return;
        builder.getSpecialEffects().skyColor(skyColor).fogColor(fogColor);
    }

    @Override
    public Codec<? extends BiomeModifier> codec() { return ERBiomeModifiers.DARK_SKY.get(); }
}
