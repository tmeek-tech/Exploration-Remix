package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Biome holders for the deep world, resolved once per server start. */
public final class DeepBiomes {
    public static final ResourceLocation ROOTVAULTS = new ResourceLocation(ExplorationRemix.MOD_ID, "rootvaults");

    private static final Map<ResourceLocation, Holder<Biome>> HOLDERS = new HashMap<>();
    private static Set<Holder<Biome>> all = Set.of();
    @Nullable private static Holder<Biome> fallback;
    @Nullable private static Holder<Biome> rootvaults;

    private DeepBiomes() {}

    public static void resolve(Registry<Biome> registry) {
        HOLDERS.clear();
        Set<Holder<Biome>> set = new LinkedHashSet<>();
        fallback = registry.getHolder(Biomes.DRIPSTONE_CAVES).orElse(null);
        for (Layer layer : DeepWorld.layers()) {
            add(registry, set, layer.refugeBiome());
            add(registry, set, layer.routeBiome());
            for (ResourceLocation id : layer.huntingBiomes()) add(registry, set, id);
        }
        rootvaults = add(registry, set, ROOTVAULTS);
        all = Set.copyOf(set);
        ExplorationRemix.LOGGER.info("Deep biomes resolved: {}", HOLDERS.keySet());
    }

    @Nullable
    private static Holder<Biome> add(Registry<Biome> registry, Set<Holder<Biome>> set, ResourceLocation id) {
        Holder<Biome> h = registry.getHolder(ResourceKey.create(Registries.BIOME, id)).orElse(null);
        if (h == null) {
            ExplorationRemix.LOGGER.error("Deep biome {} is missing from the biome registry; its areas fall back to dripstone caves", id);
            return null;
        }
        HOLDERS.put(id, h);
        set.add(h);
        return h;
    }

    public static Holder<Biome> holder(@Nullable ResourceLocation id) {
        Holder<Biome> h = id == null ? null : HOLDERS.get(id);
        return h != null ? h : fallback;
    }

    @Nullable
    public static Holder<Biome> rootvaults() { return rootvaults; }

    public static Set<Holder<Biome>> union(Set<Holder<Biome>> base) {
        if (all.isEmpty()) return base;
        Set<Holder<Biome>> out = new LinkedHashSet<>(base);
        out.addAll(all);
        return Set.copyOf(out);
    }

    public static boolean isDeep(Holder<Biome> biome) { return all.contains(biome); }
}
