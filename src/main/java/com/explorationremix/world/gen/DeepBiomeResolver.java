package com.explorationremix.world.gen;

import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.plan.LayerPlans;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/**
 * Below the pack's floor the biome comes from the layer plan. Above it, the pack's own choice stands, except that
 * vanilla's dripstone caves and deep dark become Rootvaults because both moved down into the layers.
 */
public final class DeepBiomeResolver implements BiomeResolver {
    private final BiomeResolver inner;

    public DeepBiomeResolver(BiomeResolver inner) { this.inner = inner; }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        int y = QuartPos.toBlock(qy);
        if (y < DeepWorld.packFloor()) {
            LayerPlans plans = DeepWorld.plans();
            if (plans != null) {
                return DeepBiomes.holder(plans.biomeAt(QuartPos.toBlock(qx) + 2, y + 2, QuartPos.toBlock(qz) + 2));
            }
            return DeepBiomes.holder(null);
        }
        Holder<Biome> b = inner.getNoiseBiome(qx, qy, qz, sampler);
        if (ERConfig.INSTANCE.replaceVanillaCaveBiomes.get()) {
            Holder<Biome> rv = DeepBiomes.rootvaults();
            if (rv != null && (b.is(Biomes.DRIPSTONE_CAVES) || b.is(Biomes.DEEP_DARK))) return rv;
        }
        return b;
    }
}
