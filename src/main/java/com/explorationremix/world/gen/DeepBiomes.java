package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.mixin.accessor.MultiNoiseBiomeSourceAccessor;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Biome holders for the deep world and the Rootvaults, resolved once per server start. */
public final class DeepBiomes {
    public static final ResourceLocation ROOTVAULTS = new ResourceLocation(ExplorationRemix.MOD_ID, "rootvaults");

    private static final EnumMap<DeepBiome, Holder<Biome>> HOLDERS = new EnumMap<>(DeepBiome.class);
    private static Set<Holder<Biome>> all = Set.of();
    @Nullable private static Holder<Biome> fallback;
    @Nullable private static Holder<Biome> rootvaults;
    private static boolean replaceCaveBiomes = true;
    private static Set<Holder<Biome>> lastBase, lastUnion;
    @Nullable private static volatile Climate.ParameterList<Holder<Biome>> withoutDeepDark;

    private DeepBiomes() {}

    public static ResourceLocation id(DeepBiome b) {
        return b.id.indexOf(':') >= 0 ? new ResourceLocation(b.id) : new ResourceLocation(ExplorationRemix.MOD_ID, b.id);
    }

    public static synchronized void resolve(Registry<Biome> registry) {
        HOLDERS.clear();
        Set<Holder<Biome>> set = new LinkedHashSet<>();
        fallback = registry.getHolder(Biomes.DRIPSTONE_CAVES).orElse(null);
        for (DeepBiome b : DeepBiome.values()) {
            Holder<Biome> h = registry.getHolder(ResourceKey.create(Registries.BIOME, id(b))).orElse(null);
            if (h == null) {
                ExplorationRemix.LOGGER.error("Deep biome {} is missing from the biome registry; its areas fall back to dripstone caves", id(b));
                continue;
            }
            HOLDERS.put(b, h);
            set.add(h);
        }
        rootvaults = registry.getHolder(ResourceKey.create(Registries.BIOME, ROOTVAULTS)).orElse(null);
        if (rootvaults == null) ExplorationRemix.LOGGER.error("Biome {} is missing; the deep dark stays above the first band", ROOTVAULTS);
        replaceCaveBiomes = ERConfig.INSTANCE.replaceVanillaCaveBiomes.get();
        if (rootvaults != null && replaceCaveBiomes) set.add(rootvaults);
        all = Set.copyOf(set);
        lastBase = null;
        lastUnion = null;
    }

    public static Holder<Biome> holder(@Nullable DeepBiome b) {
        Holder<Biome> h = b == null ? null : HOLDERS.get(b);
        return h != null ? h : fallback;
    }

    /** The Rootvaults, or null when they are switched off or missing. */
    @Nullable
    public static Holder<Biome> rootvaults() { return replaceCaveBiomes ? rootvaults : null; }

    /** The biome source's own biomes plus the deep ones, so their features are sorted and placed. */
    public static synchronized Set<Holder<Biome>> union(Set<Holder<Biome>> base) {
        if (all.isEmpty()) return base;
        if (base == lastBase && lastUnion != null) return lastUnion;
        Set<Holder<Biome>> out = new LinkedHashSet<>(base);
        out.addAll(all);
        lastBase = base;
        lastUnion = Set.copyOf(out);
        return lastUnion;
    }

    /**
     * Keeps a copy of the overworld biome source's climate table without the deep dark, so that above the old floor the
     * pack's caves get what the pack would have put there if the deep dark did not exist.
     */
    public static void keepWithoutDeepDark(BiomeSource source) {
        withoutDeepDark = null;
        if (!(source instanceof MultiNoiseBiomeSource m)) {
            ExplorationRemix.LOGGER.warn("The overworld biome source is {}, not multi-noise; above the old floor the deep dark becomes the surface biome", source.getClass().getName());
            return;
        }
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> all = ((MultiNoiseBiomeSourceAccessor) m).er$parameters().values();
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> kept = all.stream().filter(p -> !p.getSecond().is(Biomes.DEEP_DARK)).toList();
        if (kept.isEmpty()) return;
        withoutDeepDark = new Climate.ParameterList<>(kept);
        ExplorationRemix.LOGGER.info("Above the old floor the deep dark gives way to the pack's own caves ({} of {} climate entries kept)", kept.size(), all.size());
    }

    /**
     * What the pack would put at a quart if the deep dark did not exist: the same climate looked up without it, or the
     * surface biome over the column when the biome source has no climate table.
     */
    public static Holder<Biome> withoutDeepDark(Holder<Biome> deepDark, int qx, int qy, int qz, Climate.Sampler sampler, int x, int z) {
        Climate.ParameterList<Holder<Biome>> list = withoutDeepDark;
        if (list != null) return list.findValue(sampler.sample(qx, qy, qz));
        SurfaceOracle o = DeepWorld.oracle();
        return o != null ? o.biome(x, z) : deepDark;
    }

    public static void clear() {
        synchronized (DeepBiomes.class) {
            HOLDERS.clear();
            all = Set.of();
            fallback = null;
            rootvaults = null;
            lastBase = null;
            lastUnion = null;
            withoutDeepDark = null;
        }
    }
}
