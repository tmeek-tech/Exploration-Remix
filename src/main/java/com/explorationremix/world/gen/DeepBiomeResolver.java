package com.explorationremix.world.gen;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Entrances;
import com.explorationremix.world.terrain.Overhead;
import com.explorationremix.world.terrain.PlantCaves;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/**
 * Below the pack's floor the biome comes from the terrain. Above it: the Silver Fern Rift and the Ivory Root Cavern
 * take the spaces carved for them, and the entrances theirs (the Root Vaults, frozen near their mouths under snowy
 * forests, the sinkholes and the oasis hollows). Elsewhere the pack's own choice stands, with three changes: where it
 * would put the deep dark (which moved down into the Blue Reaches) it gets what the pack would have chosen without the
 * deep dark; lush caves under cold ground become the Frost Fern Grotto; and the pack's ordinary caves (its surface
 * biome carried underground) become plant caves wherever the growth field says so ({@link PlantCaves}), lush caves or,
 * under cold ground, the Frost Fern Grotto. The pack's other cave biomes (dripstone caves) keep their places.
 */
public final class DeepBiomeResolver implements BiomeResolver {
    private static final TagKey<Biome> FORGE_CAVE = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "is_cave"));
    private static final TagKey<Biome> FORGE_UNDERGROUND = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "is_underground"));

    private final BiomeResolver inner;
    private final Terrain terrain;
    private final Entrances entrances;
    private final Overhead overhead;
    private final PlantCaves plants;
    private final Holder<Biome> frost, lush;
    private final boolean replace;

    public DeepBiomeResolver(BiomeResolver inner, Terrain terrain) {
        this.inner = inner;
        this.terrain = terrain;
        this.entrances = DeepWorld.entrances();
        this.overhead = DeepWorld.oracle();
        this.plants = DeepWorld.plants();
        this.frost = DeepBiomes.frostGrotto();
        this.lush = DeepBiomes.lushCaves();
        this.replace = DeepBiomes.rootvaults() != null;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        int x = QuartPos.toBlock(qx) + 2, y = QuartPos.toBlock(qy) + 2, z = QuartPos.toBlock(qz) + 2;
        if (y < terrain.shape.packFloor) return DeepBiomes.holder(terrain.biomeAt(x, y, z));
        DeepBiome upper = terrain.upperBiome(x, y, z);
        if (upper != null) return DeepBiomes.holder(upper);
        if (entrances != null) {
            Entrances.Area a = entrances.areaAt(x, y, z);
            if (a != null) {
                Holder<Biome> h = DeepBiomes.area(a);
                if (h != null) return h;
            }
        }
        Holder<Biome> b = inner.getNoiseBiome(qx, qy, qz, sampler);
        if (!replace) return b;
        if (b.is(Biomes.DEEP_DARK)) b = DeepBiomes.withoutDeepDark(b, qx, qy, qz, sampler, x, z);
        return plants(b, x, y, z);
    }

    /**
     * Lush caves under cold ground become the Frost Fern Grotto; ordinary caves become plant caves where they grow. The
     * surface biome's own ores still go into their rock (see {@code BiomeFilterMixin}).
     */
    private Holder<Biome> plants(Holder<Biome> b, int x, int y, int z) {
        if (overhead == null || plants == null) return b;
        boolean isLush = b.is(Biomes.LUSH_CAVES);
        if (!isLush && (b.is(FORGE_CAVE) || b.is(FORGE_UNDERGROUND) || b.is(Biomes.DRIPSTONE_CAVES))) return b;
        Overhead.Surface s = overhead.at(x, z);
        if (isLush) return s.cold() && frost != null ? frost : b;
        if (y < terrain.shape.packFloor + 10 || plants.growth(x, y, z, s.y() - y) <= 0) return b;
        if (s.cold()) return frost != null ? frost : b;
        return lush != null ? lush : b;
    }
}
