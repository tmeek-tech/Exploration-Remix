package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.mixin.accessor.MultiNoiseBiomeSourceAccessor;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Entrances;
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

/** Biome holders for the deep world, the Upper Caves' entrances and plant caves, resolved once per server start. */
public final class DeepBiomes {
    public static final ResourceLocation ROOTVAULTS = new ResourceLocation(ExplorationRemix.MOD_ID, "rootvaults");
    public static final ResourceLocation FROZEN_ROOTVAULTS = new ResourceLocation(ExplorationRemix.MOD_ID, "frozen_rootvaults");
    public static final ResourceLocation SINKHOLE = new ResourceLocation(ExplorationRemix.MOD_ID, "sinkhole");
    public static final ResourceLocation OASIS_HOLLOW = new ResourceLocation(ExplorationRemix.MOD_ID, "oasis_hollow");
    public static final ResourceLocation FROST_FERN_GROTTO = new ResourceLocation(ExplorationRemix.MOD_ID, "frost_fern_grotto");

    private static final EnumMap<DeepBiome, Holder<Biome>> HOLDERS = new EnumMap<>(DeepBiome.class);
    private static Set<Holder<Biome>> all = Set.of();
    @Nullable private static Holder<Biome> fallback;
    @Nullable private static Holder<Biome> rootvaults;
    private static final EnumMap<Entrances.Area, Holder<Biome>> AREAS = new EnumMap<>(Entrances.Area.class);
    @Nullable private static Holder<Biome> frostGrotto, lushCaves;
    private static boolean replaceCaveBiomes = true;
    /** Placements of the surface biome's ores let into the Upper Caves' biomes since the world loaded (for checks). */
    public static final java.util.concurrent.atomic.AtomicLong SURFACE_ORES = new java.util.concurrent.atomic.AtomicLong();
    private static Set<Holder<Biome>> lastBase, lastUnion;
    @Nullable private static volatile Climate.ParameterList<Holder<Biome>> withoutDeepDark;

    private DeepBiomes() {}

    public static ResourceLocation id(DeepBiome b) {
        return b.id.indexOf(':') >= 0 ? new ResourceLocation(b.id) : new ResourceLocation(ExplorationRemix.MOD_ID, b.id);
    }

    public static synchronized void resolve(Registry<Biome> registry) {
        HOLDERS.clear();
        SURFACE_ORES.set(0);
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
        AREAS.clear();
        ResourceLocation[] ids = {ROOTVAULTS, FROZEN_ROOTVAULTS, SINKHOLE, OASIS_HOLLOW};
        for (Entrances.Area a : Entrances.Area.values()) {
            Holder<Biome> h = registry.getHolder(ResourceKey.create(Registries.BIOME, ids[a.ordinal()])).orElse(null);
            if (h == null) {
                ExplorationRemix.LOGGER.error("Biome {} is missing; those entrances keep the pack's biomes", ids[a.ordinal()]);
                continue;
            }
            AREAS.put(a, h);
            if (replaceCaveBiomes) set.add(h);
        }
        frostGrotto = registry.getHolder(ResourceKey.create(Registries.BIOME, FROST_FERN_GROTTO)).orElse(null);
        if (frostGrotto == null) ExplorationRemix.LOGGER.error("Biome {} is missing; lush caves stay under cold ground", FROST_FERN_GROTTO);
        else if (replaceCaveBiomes) set.add(frostGrotto);
        lushCaves = registry.getHolder(Biomes.LUSH_CAVES).orElse(null);
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

    /** The biome of an entrance's area, or null when the entrances are switched off or the biome is missing. */
    @Nullable
    public static Holder<Biome> area(Entrances.Area a) { return replaceCaveBiomes ? AREAS.get(a) : null; }

    /** The Frost Fern Grotto, or null when switched off or missing. */
    @Nullable
    public static Holder<Biome> frostGrotto() { return replaceCaveBiomes ? frostGrotto : null; }

    /** Vanilla's lush caves (the pack's copy), or null when switched off or missing. */
    @Nullable
    public static Holder<Biome> lushCaves() { return replaceCaveBiomes ? lushCaves : null; }

    /**
     * True for the biomes the Upper Caves give the pack's caves and the rock round them, in place of the surface biome
     * carried underground: the entrances' biomes, the Frost Fern Grotto and lush caves.
     */
    public static boolean upperCave(Holder<Biome> h) {
        if (!replaceCaveBiomes) return false;
        return h == frostGrotto || h == lushCaves || h.is(Biomes.LUSH_CAVES) || AREAS.containsValue(h);
    }

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
            AREAS.clear();
            frostGrotto = null;
            lushCaves = null;
            lastBase = null;
            lastUnion = null;
            withoutDeepDark = null;
        }
    }
}
