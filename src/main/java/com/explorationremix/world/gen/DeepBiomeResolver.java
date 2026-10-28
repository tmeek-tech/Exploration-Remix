package com.explorationremix.world.gen;

import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/**
 * Below the pack's floor the biome comes from the terrain. Above it, the pack's own choice stands, except that
 * vanilla's dripstone caves and deep dark become Rootvaults because both moved down into the layers.
 */
public final class DeepBiomeResolver implements BiomeResolver {
    private final BiomeResolver inner;
    private final Terrain terrain;
    private final Holder<Biome> rootvaults;

    public DeepBiomeResolver(BiomeResolver inner, Terrain terrain) {
        this.inner = inner;
        this.terrain = terrain;
        this.rootvaults = DeepBiomes.rootvaults();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        int y = QuartPos.toBlock(qy);
        if (y < terrain.shape.packFloor) {
            return DeepBiomes.holder(terrain.biomeAt(QuartPos.toBlock(qx) + 2, y + 2, QuartPos.toBlock(qz) + 2));
        }
        Holder<Biome> b = inner.getNoiseBiome(qx, qy, qz, sampler);
        if (rootvaults != null && (b.is(Biomes.DRIPSTONE_CAVES) || b.is(Biomes.DEEP_DARK))) return rootvaults;
        return b;
    }
}
