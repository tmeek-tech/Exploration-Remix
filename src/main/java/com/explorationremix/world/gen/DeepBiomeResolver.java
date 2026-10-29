package com.explorationremix.world.gen;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Rootvaults;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/**
 * Below the pack's floor the biome comes from the terrain. Above it: the Silver Fern Rift and the Ivory Root Cavern
 * take the spaces carved for them, the Rootvaults take their grottos (only under trees, 10 to 40 blocks down), and
 * otherwise the pack's own choice stands, except that where it would put the deep dark (which moved down into the
 * Blue Reaches) it gets what the pack would have chosen without the deep dark: its ordinary caves.
 */
public final class DeepBiomeResolver implements BiomeResolver {
    private final BiomeResolver inner;
    private final Terrain terrain;
    private final Holder<Biome> rootvaults;
    private final Rootvaults roots;

    public DeepBiomeResolver(BiomeResolver inner, Terrain terrain) {
        this.inner = inner;
        this.terrain = terrain;
        this.rootvaults = DeepBiomes.rootvaults();
        this.roots = DeepWorld.roots();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        int x = QuartPos.toBlock(qx) + 2, y = QuartPos.toBlock(qy) + 2, z = QuartPos.toBlock(qz) + 2;
        if (y < terrain.shape.packFloor) return DeepBiomes.holder(terrain.biomeAt(x, y, z));
        DeepBiome upper = terrain.upperBiome(x, y, z);
        if (upper != null) return DeepBiomes.holder(upper);
        if (rootvaults != null && roots != null && roots.biomeAt(x, y, z)) return rootvaults;
        Holder<Biome> b = inner.getNoiseBiome(qx, qy, qz, sampler);
        if (rootvaults != null && b.is(Biomes.DEEP_DARK)) return DeepBiomes.withoutDeepDark(b, qx, qy, qz, sampler, x, z);
        return b;
    }
}
