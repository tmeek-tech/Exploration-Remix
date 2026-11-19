package com.explorationremix.world.feature;

import com.explorationremix.creature.BlueSpawnRules;
import com.explorationremix.creature.SkeinEntity;
import com.explorationremix.creature.SkeinHomes;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Blue Reaches' Skeins, set down as each chunk is made (0.17.0), so they are there before anyone comes and stay
 * when everyone leaves, as the animals a world starts with do: a chunk that is a Skein's home ({@link SkeinHomes}) gets
 * its Skein on the rock of one of its caves, in the Deep Blue Rift, never in the Asurine Geodes nor (since 0.19.8) in
 * the Deep Dark, with room for its body to lie straight back from its head. Vanilla's own world-gen spawning only sets
 * animals on the ground under the sky, so the caves' creatures need this.
 *
 * <p>Runs last in every chunk (band 1 is the Deep Blue Rift under every column, which carries it everywhere, as it
 * does the den lairs), once the chunk's plants, vines and ways are in. A Skein made here is kept in the new chunk and
 * comes into the world with it; its body is laid on its first tick.
 */
public final class SkeinsFeature extends Feature<NoneFeatureConfiguration> {
    /** For /remix skein homes: home chunks made since the game started, and the Skeins set down in them. */
    public static final AtomicLong HOMES = new AtomicLong(), PLACED = new AtomicLong();

    public SkeinsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        if (!SkeinHomes.home(t.seed, cp.x, cp.z)) return false;
        HOMES.incrementAndGet();
        int top = t.shape.top(Terrain.BLUE), bottom = t.shape.bottom(Terrain.BLUE);
        RandomSource r = RandomSource.create(SkeinHomes.chunkSeed(t.seed, cp.x, cp.z));
        EntityType<SkeinEntity> type = EREntities.SKEIN.get();
        // a node is two blocks across: its low corner keeps EDGE inside the chunk on the low side, its far cells on the high side
        int span = 16 - 2 * SkeinHomes.EDGE - 1;
        for (int i = 0; i < SkeinHomes.TRIES; i++) {
            int x = cp.getMinBlockX() + SkeinHomes.EDGE + r.nextInt(span);
            int z = cp.getMinBlockZ() + SkeinHomes.EDGE + r.nextInt(span);
            int y0 = bottom + 1 + r.nextInt(Math.max(1, top - bottom - 1));
            SkeinEntity.Placement spot = spot(level, type, x, y0, z, top, bottom);
            if (spot != null && setDown(level, type, spot, r)) {
                PLACED.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    /**
     * The rock nearest {@code y0} up and down column (x, z) that a Skein can be set down on, as the spawner looks: the
     * first found, kept only if it lies in the Blue Reaches, in a place Skeins live, with room for the body.
     */
    @Nullable
    private static SkeinEntity.Placement spot(WorldGenLevel level, EntityType<SkeinEntity> type, int x, int y0, int z, int top, int bottom) {
        for (int k = 0; k <= 2 * SkeinHomes.SCAN; k++) {
            int y = SkeinHomes.scanY(y0, k);
            if (y < bottom || y > top) continue;
            SkeinEntity.Placement p = SkeinEntity.placement(level, type, new BlockPos(x, y, z));
            if (p == null) continue;
            if (!BlueSpawnRules.inBand(p.pos().y, top, bottom)) return null;
            String biome = level.getBiome(BlockPos.containing(p.pos())).unwrapKey().map(key -> key.location().toString()).orElse("");
            if (!BlueSpawnRules.skeinsLive(BlueSpawnRules.place(biome))) return null;
            if (SkeinEntity.openLength(level, type, p) < SkeinEntity.BODY_ROOM) return null;
            return p;
        }
        return null;
    }

    private static boolean setDown(WorldGenLevel level, EntityType<SkeinEntity> type, SkeinEntity.Placement spot, RandomSource r) {
        SkeinEntity s = type.create(level.getLevel());
        if (s == null) return false;
        Vec3 heading = SkeinEntity.openHeading(level, type, spot, r::nextDouble);
        s.setDownAtGeneration(spot.pos(), spot.rock(), heading);
        s.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(spot.pos())), MobSpawnType.CHUNK_GENERATION, null, null);
        level.addFreshEntityWithPassengers(s);
        return true;
    }
}
