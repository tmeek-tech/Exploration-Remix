package com.explorationremix.creature.groups;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.groups.MobGroupRules.Home;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where each kind of group lives, read from the world: the biome tags {@code explorationremix:mob_homes/woods},
 * {@code dark_oak} and {@code green_caves}, each biome's own monster list as the biome and the pack's biome modifiers
 * left it (husks for deserts, strays for snowy land, drowned for rivers and the sea, endermen for the lone enderman),
 * and the trees round a spot.
 */
final class MobHomes {
    private MobHomes() {}

    /** Woods and savannas: zombies and skeletons. */
    static final TagKey<Biome> WOODS = tag("woods");
    /** Dark oak forests: spider packs and witch covens. */
    static final TagKey<Biome> DARK_OAK = tag("dark_oak");
    /** The green Upper Caves: creepers and slimes. */
    static final TagKey<Biome> GREEN_CAVES = tag("green_caves");

    /** Canopy samples: a square of this many columns a side, this far apart, round the spot. */
    static final int CANOPY_SIDE = 5, CANOPY_STEP = 4;

    private static TagKey<Biome> tag(String name) {
        return TagKey.create(Registries.BIOME, new ResourceLocation(ExplorationRemix.MOD_ID, "mob_homes/" + name));
    }

    /** A biome's own monster weights, by type (vanilla's spawning reads its list through a filter; this reads round it). */
    static final class Weights {
        private final Map<EntityType<?>, Integer> byType = new HashMap<>();

        int of(EntityType<?> type) { return byType.getOrDefault(type, 0); }

        boolean has(EntityType<?> type) { return of(type) > 0; }
    }

    private static final Map<Biome, Weights> WEIGHTS = Collections.synchronizedMap(new WeakHashMap<>());

    static Weights weights(Biome biome) {
        return WEIGHTS.computeIfAbsent(biome, b -> {
            Weights w = new Weights();
            List<MobSpawnSettings.SpawnerData> own = b.getMobSettings().getMobs(MobCategory.MONSTER).unwrap();
            for (MobSpawnSettings.SpawnerData d : own) w.byType.merge(d.type, d.getWeight().asInt(), Integer::sum);
            return w;
        });
    }

    static void forget() { WEIGHTS.clear(); }

    /**
     * The home of the ground at {@code ground} (the first open block over the column's ground, as the
     * {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES} heightmap has it), or null.
     */
    static Home surfaceHome(ServerLevel level, BlockPos ground, double canopyMin) {
        Holder<Biome> b = level.getBiome(ground);
        Weights w = weights(b.value());
        boolean darkOak = b.is(DARK_OAK), husks = w.has(EntityType.HUSK), woods = b.is(WOODS);
        // only looked at where it decides the home
        double canopy = woods && !darkOak && !husks ? canopy(level, ground) : 0.0;
        return MobGroupRules.surfaceHome(darkOak, husks, woods, canopy, canopyMin, w.has(EntityType.STRAY));
    }

    /** The home of a spot in a cave, or null. */
    static Home caveHome(ServerLevel level, BlockPos at) {
        return MobGroupRules.caveHome(level.getBiome(at).is(GREEN_CAVES));
    }

    /** The home of a spot in surface water {@code depth} deep, or null. */
    static Home waterHome(ServerLevel level, BlockPos at, int depth) {
        return MobGroupRules.waterHome(weights(level.getBiome(at).value()).has(EntityType.DROWNED), depth);
    }

    /** Whether a lone enderman may come out at {@code at}: where the biome's own list has endermen. */
    static boolean endermen(ServerLevel level, BlockPos at) {
        return weights(level.getBiome(at).value()).has(EntityType.ENDERMAN);
    }

    /** The share of the columns round {@code at} with leaves over the ground. */
    static double canopy(ServerLevel level, BlockPos at) {
        int covered = 0, sampled = 0, half = CANOPY_SIDE / 2;
        for (int i = -half; i <= half; i++)
            for (int j = -half; j <= half; j++) {
                int x = at.getX() + i * CANOPY_STEP, z = at.getZ() + j * CANOPY_STEP;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                sampled++;
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)) covered++;
            }
        return MobGroupRules.canopy(covered, sampled);
    }
}
