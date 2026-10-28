package com.explorationremix.world.gen;

import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/**
 * Below the pack's floor the biome comes from the terrain. Above it, the Silver Fern Rift and the Ivory Root Cavern
 * take the spaces carved for them, and otherwise the pack's own choice stands, except that vanilla's deep dark becomes
 * Rootvaults because the deep dark moved down into the Blue Reaches.
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
        int x = QuartPos.toBlock(qx) + 2, y = QuartPos.toBlock(qy) + 2, z = QuartPos.toBlock(qz) + 2;
        if (y < terrain.shape.packFloor) return DeepBiomes.holder(terrain.biomeAt(x, y, z));
        DeepBiome upper = terrain.upperBiome(x, y, z);
        if (upper != null) return DeepBiomes.holder(upper);
        Holder<Biome> b = inner.getNoiseBiome(qx, qy, qz, sampler);
        if (rootvaults != null && b.is(Biomes.DEEP_DARK)) return rootvaults;
        return b;
    }
}
