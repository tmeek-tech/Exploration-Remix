package com.explorationremix.creature;

import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.ForgeEventFactory;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Vanilla's night-time trickle is gone. Instead the surface gets occasional clusters: a small hunting party that
 * appears together in the dark near a player, at night or under a dark forest's canopy. Lush caves and Rootvaults
 * get the odd creeper. Caves above the first band are otherwise safe.
 */
public final class ClusterDirector {
    private static final Map<UUID, Long> NEXT = new HashMap<>();
    private static final EntityType<?>[] CLUSTER_MOBS = {EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
            EntityType.SKELETON, EntityType.SKELETON, EntityType.SKELETON, EntityType.SPIDER, EntityType.SPIDER, EntityType.CREEPER};
    private ClusterDirector() {}

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 100 != 0) return;
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        for (ServerPlayer p : level.players()) {
            if (p.isCreative() || p.isSpectator() || !p.isAlive()) continue;
            if (DeepWorld.installed() && p.getBlockY() < DeepWorld.packFloor()) continue;
            long next = NEXT.getOrDefault(p.getUUID(), -1L);
            if (next < 0) { NEXT.put(p.getUUID(), now + interval(level.random)); continue; }
            if (now < next) continue;
            BlockPos pos = p.blockPosition();
            Holder<Biome> biome = level.getBiome(pos);
            if (biome.is(Biomes.LUSH_CAVES) || biome.is(ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, DeepBiomes.ROOTVAULTS))) {
                spawnCluster(level, p, 1 + level.random.nextInt(2), true);
                NEXT.put(p.getUUID(), now + ERConfig.INSTANCE.creeperCaveIntervalSeconds.get() * 20L);
                continue;
            }
            boolean surface = level.canSeeSky(pos) || pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) - 6;
            boolean darkForest = biome.is(Biomes.DARK_FOREST);
            if (!surface || !(darkForest || level.isNight())) { NEXT.put(p.getUUID(), now + 200); continue; }
            int size = ERConfig.INSTANCE.clusterSizeMin.get() + level.random.nextInt(Math.max(1, ERConfig.INSTANCE.clusterSizeMax.get() - ERConfig.INSTANCE.clusterSizeMin.get() + 1));
            spawnCluster(level, p, size, false);
            NEXT.put(p.getUUID(), now + interval(level.random));
        }
    }

    private static long interval(RandomSource random) {
        int min = ERConfig.INSTANCE.clusterIntervalMinSeconds.get(), max = Math.max(min, ERConfig.INSTANCE.clusterIntervalMaxSeconds.get());
        return (min + random.nextInt(max - min + 1)) * 20L;
    }

    @Nullable
    private static BlockPos findDark(ServerLevel level, ServerPlayer p, boolean cave) {
        RandomSource random = level.random;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int tries = 0; tries < 16; tries++) {
            double angle = random.nextDouble() * Math.PI * 2, dist = cave ? 14 + random.nextDouble() * 14 : 24 + random.nextDouble() * 16;
            int x = (int) Math.floor(p.getX() + Math.cos(angle) * dist), z = (int) Math.floor(p.getZ() + Math.sin(angle) * dist);
            pos.set(x, p.getBlockY() + 6, z);
            if (!level.hasChunkAt(pos)) continue;
            if (!cave) pos.setY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            else {
                boolean found = false;
                for (int dy = 0; dy < 14; dy++) {
                    if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir() && !level.getBlockState(pos.below()).isAir()) { found = true; break; }
                    pos.move(0, -1, 0);
                }
                if (!found) continue;
            }
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) continue;
            if (level.getMaxLocalRawBrightness(pos) > 7) continue;
            if (!level.getBlockState(pos.below()).getFluidState().isEmpty()) continue;
            return pos.immutable();
        }
        return null;
    }

    private static void spawnCluster(ServerLevel level, ServerPlayer p, int size, boolean creepers) {
        BlockPos at = findDark(level, p, creepers);
        if (at == null) return;
        RandomSource random = level.random;
        for (int i = 0; i < size; i++) {
            EntityType<?> type = creepers ? EntityType.CREEPER : CLUSTER_MOBS[random.nextInt(CLUSTER_MOBS.length)];
            BlockPos spot = at.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
            if (!level.getBlockState(spot).isAir() || !level.getBlockState(spot.above()).isAir()) spot = at;
            if (!(type.create(level) instanceof Mob mob)) continue;
            mob.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
            if (!level.noCollision(mob)) { mob.discard(); continue; }
            ForgeEventFactory.onFinalizeSpawn(mob, level, level.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null, null);
            mob.setTarget(p);
            level.addFreshEntity(mob);
        }
    }
}
