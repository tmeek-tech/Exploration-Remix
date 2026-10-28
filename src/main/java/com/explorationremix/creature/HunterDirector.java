package com.explorationremix.creature;

import com.explorationremix.config.ERConfig;
import com.explorationremix.creature.entity.AmbusherEntity;
import com.explorationremix.creature.entity.BurrowerEntity;
import com.explorationremix.creature.entity.RemixCreature;
import com.explorationremix.registry.EREntities;
import com.explorationremix.run.RunManager;
import com.explorationremix.run.RunState;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a layer's hunters on every player's trail: a couple per player, spawned out of sight, replaced a while
 * after they die, retired when the player leaves the layer or the run ends. Refuges get no hunters at all.
 */
public final class HunterDirector {
    private static final Map<UUID, Long> NEXT_SPAWN = new HashMap<>();
    private HunterDirector() {}

    public static void tick(ServerPlayer p) {
        if (p.tickCount % 40 != 0) return;
        RunState s = RunManager.state(p);
        if (!s.active || !DeepWorld.installed()) return;
        ServerLevel level = p.serverLevel();
        Layer layer = DeepWorld.layerAt(p.getBlockY());
        List<RemixCreature> mine = level.getEntitiesOfClass(RemixCreature.class, p.getBoundingBox().inflate(160), c -> c.isHunter() && c.runId() == s.runId);
        for (RemixCreature c : mine) {
            if (c.distanceToSqr(p) > 150 * 150 || DeepWorld.layerAt(c.getBlockY()) != layer) c.discard();
        }
        if (layer == null) return;                                   // in a band: nothing hunts in the gates
        mine.removeIf(c -> c.isRemoved());
        if (mine.size() >= ERConfig.INSTANCE.huntersPerPlayerPerLayer.get()) return;
        long now = level.getGameTime();
        if (now < NEXT_SPAWN.getOrDefault(p.getUUID(), 0L)) return;
        if (RemixCreature.isRefuge(p.blockPosition())) return;
        EntityType<? extends RemixCreature> type = pickType(layer, level.random);
        BlockPos at = findSpawn(level, p, type, layer);
        if (at == null) { NEXT_SPAWN.put(p.getUUID(), now + 100); return; }
        RemixCreature mob = type.create(level);
        if (mob == null) return;
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        mob.setRunId(s.runId);
        mob.setPersistenceRequired();
        if (mob instanceof BurrowerEntity b) b.startBurrowed();
        if (mob instanceof AmbusherEntity a) a.startHanging();
        level.addFreshEntity(mob);
        NEXT_SPAWN.put(p.getUUID(), now + 200);
    }

    /** A dead hunter is replaced only after the configured wait. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof RemixCreature c) || !c.isHunter() || c.runId() == 0 || !(c.level() instanceof ServerLevel level)) return;
        long until = level.getGameTime() + ERConfig.INSTANCE.hunterRespawnSeconds.get() * 20L;
        for (ServerPlayer p : level.players()) {
            if (RunManager.currentRun(p) == c.runId()) NEXT_SPAWN.merge(p.getUUID(), until, Math::max);
        }
    }

    static EntityType<? extends RemixCreature> pickType(Layer layer, RandomSource random) {
        return switch (layer.index()) {
            case 0 -> EREntities.CLIMBER.get();
            case 1 -> random.nextFloat() < 0.3F ? EREntities.CLIMBER.get() : EREntities.AMBUSHER.get();
            case 2 -> random.nextFloat() < 0.3F ? EREntities.AMBUSHER.get() : EREntities.BURROWER.get();
            default -> random.nextFloat() < 0.4F ? EREntities.BURROWER.get() : EREntities.SMASHER.get();
        };
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
    }

    private static boolean air(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isAir();
    }

    private static boolean outOfSight(ServerLevel level, ServerPlayer p, Vec3 at) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = at.subtract(eye);
        if (p.getViewVector(1.0F).dot(dir.normalize()) < 0.2) return true;        // behind the player
        HitResult hit = level.clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    @Nullable
    static BlockPos findSpawn(ServerLevel level, ServerPlayer p, EntityType<? extends RemixCreature> type, Layer layer) {
        RandomSource random = level.random;
        boolean ceiling = type == EREntities.AMBUSHER.get();
        boolean stone = type == EREntities.BURROWER.get();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int tries = 0; tries < 14; tries++) {
            double angle = random.nextDouble() * Math.PI * 2, dist = 26 + random.nextDouble() * 18;
            int x = (int) Math.floor(p.getX() + Math.cos(angle) * dist), z = (int) Math.floor(p.getZ() + Math.sin(angle) * dist);
            pos.set(x, p.getBlockY() + 12, z);
            if (!level.hasChunkAt(pos)) continue;
            int top = Math.min(layer.top() - 2, p.getBlockY() + 12), bottom = Math.max(layer.bottom() + 2, p.getBlockY() - 14);
            BlockPos found = null;
            for (int y = top; y >= bottom; y--) {
                pos.setY(y);
                if (ceiling) {
                    if (solid(level, pos.above(2)) && air(level, pos) && air(level, pos.above())) { found = pos.immutable(); break; }
                } else if (stone) {
                    if (air(level, pos.above()) && solid(level, pos) && solid(level, pos.below()) && solid(level, pos.below(2))) { found = pos.below().immutable(); break; }
                } else {
                    if (air(level, pos) && air(level, pos.above()) && solid(level, pos.below())) { found = pos.immutable(); break; }
                }
            }
            if (found == null || RemixCreature.isRefuge(found)) continue;
            Vec3 at = Vec3.atBottomCenterOf(found);
            if (!stone && !level.noCollision(type.getAABB(at.x, at.y, at.z))) continue;
            if (!outOfSight(level, p, at.add(0, 1, 0))) continue;
            LivingEntity near = level.getNearestPlayer(at.x, at.y, at.z, 20, false);
            if (near != null && near != p) continue;
            return found;
        }
        return null;
    }
}
