package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.creature.entity.ArmoredEntity;
import com.explorationremix.creature.entity.RemixCreature;
import com.explorationremix.creature.entity.SpitterEntity;
import com.explorationremix.registry.EREntities;
import com.explorationremix.run.RunManager;
import com.explorationremix.run.RunState;
import com.explorationremix.run.RunXp;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.explorationremix.world.plan.CellPlan;
import com.explorationremix.world.plan.Gate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every gate's lower landing holds a nest. While a player on a run is near and the nest still has egg clusters, it
 * keeps a few defenders alive, hatching replacements from the clusters that are left. Break them all and the gate is
 * open for that run; the nest regrows when the run ends.
 */
public final class NestDirector {
    private static final Map<String, Long> NEXT_HATCH = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();
    private NestDirector() {}

    /** The nest that guards the layer a position is in (or the layer below the band it is in). */
    @Nullable
    public static Gate nestGateAt(double x, int y, double z) {
        if (!DeepWorld.installed() || DeepWorld.plans() == null || y >= DeepWorld.packFloor()) return null;
        Layer layer = DeepWorld.layerAt(y);
        if (layer == null) {
            int band = DeepWorld.bandAt(y);
            if (band == 0 || band > DeepWorld.layers().size()) return null;
            layer = DeepWorld.layers().get(band - 1);
        }
        CellPlan plan = DeepWorld.plans().cellAt(layer.index(), x, z);
        return plan.entryGate;
    }

    public static String keyOf(Gate g) { return NestRegrowth.key(g.band(), g.cellX(), g.cellZ()); }

    public static Layer layerOf(Gate g) { return DeepWorld.layers().get(g.band() - 1); }

    public static void tick(ServerPlayer p) {
        if (p.tickCount % 20 != 0) return;
        RunState s = RunManager.state(p);
        if (!s.active) return;
        Gate g = nestGateAt(p.getX(), p.getBlockY(), p.getZ());
        if (g == null) return;
        Vec3 center = new Vec3(g.lowerX(), g.lowerY(), g.lowerZ());
        double d = center.distanceTo(p.position());
        if (d > 64) return;
        ServerLevel level = p.serverLevel();
        NestRegrowth data = NestRegrowth.get(level.getServer());
        String key = keyOf(g);
        NestRegrowth.Entry e = data.entry(key);
        Layer layer = layerOf(g);
        BlockPos centerPos = BlockPos.containing(center);
        if (e.layout.isEmpty()) NestBuilder.build(level, key, e, centerPos, layer, data);
        else NestBuilder.ensure(level, e, layer);
        if (e.clusters.isEmpty() || e.open()) return;              // broken: quiet until the breaking run ends
        if (d > 44) return;
        long now = level.getGameTime();
        List<RemixCreature> alive = level.getEntitiesOfClass(RemixCreature.class, new AABB(centerPos).inflate(40), c -> key.equals(c.nestKey()));
        if (WARNED.add(key + "#" + s.runId)) {
            p.displayClientMessage(Component.translatable("run.explorationremix.nest_near").withStyle(ChatFormatting.RED), true);
            NoiseBus.emit(level, center, 20, null);
        }
        int want = ERConfig.INSTANCE.nestActiveEnemies.get() + layer.index();
        if (alive.size() >= want) return;
        if (now < NEXT_HATCH.getOrDefault(key, 0L)) return;
        NEXT_HATCH.put(key, now + (alive.isEmpty() ? 10 : 60));
        BlockPos at = e.clusters.get(level.random.nextInt(e.clusters.size()));
        if (!level.hasChunkAt(at)) return;
        EntityType<? extends RemixCreature> type = pick(layer, alive);
        RemixCreature mob = type.create(level);
        if (mob == null) return;
        mob.moveTo(at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        mob.setNestKey(key);
        mob.setPersistenceRequired();
        mob.setTarget(p);
        level.addFreshEntity(mob);
        level.sendParticles(ParticleTypes.ITEM_SLIME, at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, 12, 0.3, 0.2, 0.3, 0.05);
        level.playSound(null, at, SoundEvents.TURTLE_EGG_HATCH, SoundSource.HOSTILE, 1.0F, 0.6F);
    }

    private static EntityType<? extends RemixCreature> pick(Layer layer, List<RemixCreature> alive) {
        int armored = 0, spitters = 0;
        for (RemixCreature c : alive) { if (c instanceof ArmoredEntity) armored++; else if (c instanceof SpitterEntity) spitters++; }
        int wantArmored = Math.min(2, layer.index());
        int wantSpitters = 1 + layer.index() / 2;
        if (armored < wantArmored) return EREntities.ARMORED.get();
        if (spitters < wantSpitters) return EREntities.SPITTER.get();
        return EREntities.SWARMER.get();
    }

    /** An egg cluster block was destroyed. */
    public static void onClusterBroken(ServerLevel level, BlockPos pos) {
        Gate g = nestGateAt(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (g == null) return;
        String key = keyOf(g);
        NestRegrowth data = NestRegrowth.get(level.getServer());
        NestRegrowth.Entry e = data.entryOrNull(key);
        if (e == null || !e.clusters.contains(pos)) return;
        ServerPlayer breaker = null;
        for (Player p : level.getEntitiesOfClass(Player.class, new AABB(pos).inflate(24))) {
            if (p instanceof ServerPlayer sp && RunManager.currentRun(sp) != 0L && (breaker == null || sp.distanceToSqr(Vec3.atCenterOf(pos)) < breaker.distanceToSqr(Vec3.atCenterOf(pos)))) breaker = sp;
        }
        if (breaker == null) return;                                   // no run nearby: the cluster simply grows back
        RunState s = RunManager.state(breaker);
        boolean open = data.breakCluster(key, s.runId, pos);
        NoiseBus.emit(level, Vec3.atCenterOf(pos), 28, breaker);
        level.sendParticles(ParticleTypes.ITEM_SLIME, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 20, 0.4, 0.3, 0.4, 0.1);
        if (open) {
            Layer layer = layerOf(g);
            int xp = ERConfig.INSTANCE.xpPerGate.get() * (layer.index() + 1);
            RunXp.award(breaker, xp, s);
            breaker.displayClientMessage(Component.translatable("run.explorationremix.nest_open").withStyle(ChatFormatting.GOLD), true);
            level.playSound(null, pos, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 0.5F, 1.6F);
            ExplorationRemix.LOGGER.info("Nest {} broken by run {}", key, s.runId);
        } else {
            breaker.displayClientMessage(Component.translatable("run.explorationremix.nest_cluster", e.clusters.size()).withStyle(ChatFormatting.YELLOW), true);
        }
    }
}
