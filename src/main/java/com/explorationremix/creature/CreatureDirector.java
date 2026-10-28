package com.explorationremix.creature;

import com.explorationremix.creature.entity.FlockerEntity;
import com.explorationremix.creature.entity.RemixCreature;
import com.explorationremix.run.RunData;
import com.explorationremix.run.RunState;
import com.explorationremix.world.DeepWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Routes every noise to the creatures that can hear it, makes the player's own actions audible, and retires
 * creatures whose run has ended. The hunter, nest, flock and cluster directors hang off the same tick.
 */
public final class CreatureDirector {
    static { NoiseBus.subscribe(CreatureDirector::onNoise); }
    private CreatureDirector() {}

    /** Forces the noise subscription to exist. */
    public static void init() {}

    static void onNoise(NoiseBus.Noise n) {
        ServerLevel level = n.level();
        double reach = n.loudness() * 1.6 + 4;
        AABB box = new AABB(n.pos(), n.pos()).inflate(reach);
        for (RemixCreature c : level.getEntitiesOfClass(RemixCreature.class, box)) {
            double eff = SoundReach.effective(level, n.pos(), c.position().add(0, c.getEyeHeight(), 0), n.loudness());
            if (eff > 0) c.hear(n, eff);
        }
        for (FlockerEntity f : level.getEntitiesOfClass(FlockerEntity.class, box)) {
            double eff = SoundReach.effective(level, n.pos(), f.position(), n.loudness());
            if (eff > 6) f.alarm(n.pos(), n.source() instanceof Player);
        }
    }

    /** True while any player's run with this id is still going. */
    public static boolean runActive(MinecraftServer server, long runId) {
        if (runId == 0) return false;
        for (RunState s : RunData.get(server).all().values()) if (s.active && s.runId == runId) return true;
        return false;
    }

    /** Creatures bound to a run vanish when it ends; nest creatures vanish when their nest has no visitor. */
    public static void checkLifetime(RemixCreature c) {
        if (!(c.level() instanceof ServerLevel level)) return;
        if (c.runId() != 0 && !runActive(level.getServer(), c.runId())) { c.discard(); return; }
        if (c.runId() != 0 && level.getNearestPlayer(c, 160) == null) c.discard();
    }

    // ------------------------------------------------------------------ the player's own noise

    private static boolean deep(Player p) {
        return DeepWorld.installed() && p.level().dimension() == Level.OVERWORLD && p.getBlockY() < DeepWorld.packFloor();
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer p) || p.tickCount % 10 != 0) return;
        if (!deep(p) || p.isSpectator()) return;
        double loud = p.isSprinting() ? 10 : (p.isShiftKeyDown() || !p.onGround() ? 0 : (p.getDeltaMovement().horizontalDistanceSqr() > 0.003 ? 4 : 0));
        if (loud > 0) NoiseBus.emit(p.serverLevel(), p.position(), loud, p);
        HunterDirector.tick(p);
        NestDirector.tick(p);
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && deep(p)) NoiseBus.emit(p.serverLevel(), p.position(), 6, p);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p && deep(p)) NoiseBus.emit(p.serverLevel(), Vec3.atCenterOf(e.getPos()), 18, p);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate e) {
        if (e.getLevel() instanceof ServerLevel level) NoiseBus.emit(level, e.getExplosion().getPosition(), 64, e.getExplosion().getExploder());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ClusterDirector.tick(e.getServer());
    }
}
