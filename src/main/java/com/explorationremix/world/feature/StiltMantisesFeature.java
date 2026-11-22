package com.explorationremix.world.feature;

import com.explorationremix.creature.MantisHerds;
import com.explorationremix.creature.StiltMantisEntity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Deep Gardens' stilt mantis herds, set down as each chunk is made (0.20.0), so they graze their groves before
 * anyone comes and stay when everyone leaves, as the animals a world starts with do. Where the herds live is worked out
 * from the vent field's plan ({@link MantisHerds}): the chunk holding a herd's home sets its 2 to 4 mantises down round
 * the home, on floors with room over them (glow mat first), a few blocks apart, all inside the chunk.
 *
 * <p>Runs last in the vent field's chunks (top layer), once the chunk's blocks are all in.
 */
public final class StiltMantisesFeature extends Feature<NoneFeatureConfiguration> {
    /** For /remix mantis homes: herd homes met since the game started, and the mantises set down at them. */
    public static final AtomicLong HERDS = new AtomicLong(), PLACED = new AtomicLong();
    /** A herd stands this far round its home at the most (blocks), and its members this far apart at the least. */
    static final int SPREAD = 6;
    static final double APART = 2.5;

    public StiltMantisesFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed() || t.gardens == null) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        // a chunk lies inside one cell (16 divides the cell's 64)
        boolean any = false;
        for (MantisHerds.Home h : MantisHerds.homes(t, MantisHerds.cell(cp.getMinBlockX()), MantisHerds.cell(cp.getMinBlockZ()))) {
            if ((h.x() >> 4) != cp.x || (h.z() >> 4) != cp.z) continue;
            HERDS.incrementAndGet();
            any |= setDown(level, h, cp) > 0;
        }
        return any;
    }

    /** Sets a herd down round its home; the number set down (fewer than its size where its grove is cramped). */
    static int setDown(WorldGenLevel level, MantisHerds.Home h, ChunkPos cp) {
        EntityType<StiltMantisEntity> type = EREntities.STILT_MANTIS.get();
        RandomSource r = RandomSource.create(h.id() * 0x9E3779B97F4A7C15L + 0x3a7);
        List<BlockPos> taken = new ArrayList<>();
        int placed = 0;
        for (int i = 0; i < h.size(); i++) {
            BlockPos spot = null;
            for (int k = 0; k < 16 && spot == null; k++) {
                int x = h.x(), z = h.z();
                if (i > 0 || k > 0) {
                    double a = r.nextDouble() * Math.PI * 2.0, d = 2.0 + r.nextDouble() * (SPREAD - 2.0);
                    x += (int) Math.round(Math.cos(a) * d);
                    z += (int) Math.round(Math.sin(a) * d);
                }
                x = Math.max(cp.getMinBlockX(), Math.min(cp.getMaxBlockX(), x));
                z = Math.max(cp.getMinBlockZ(), Math.min(cp.getMaxBlockZ(), z));
                BlockPos at = StiltMantisEntity.stand(level, x, h.y(), z, 4);
                if (at == null) continue;
                boolean clear = true;
                for (BlockPos o : taken) if (o.distSqr(at) < APART * APART) clear = false;
                if (clear) spot = at;
            }
            if (spot == null) continue;
            StiltMantisEntity m = type.create(level.getLevel());
            if (m == null) break;
            float yaw = r.nextFloat() * 360.0F;
            m.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, yaw, 0.0F);
            m.setYHeadRot(yaw);
            m.setYBodyRot(yaw);
            m.joinHerd(h);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.CHUNK_GENERATION, null, null);
            level.addFreshEntityWithPassengers(m);
            taken.add(spot);
            placed++;
            PLACED.incrementAndGet();
        }
        return placed;
    }
}
