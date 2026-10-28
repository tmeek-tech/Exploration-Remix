package com.explorationremix.combat;

import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERTags;
import com.explorationremix.rune.RuneIds;
import com.explorationremix.rune.cast.RuneCaster;
import com.explorationremix.rune.cast.TriggerContext;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * Hidden poise. Heavy weapons, finishers, momentum hits, Heavy and Shove wear it down; it refills when the creature
 * is left alone. Breaking it staggers the creature: it reels for a while and takes extra damage. Hunters only reel
 * briefly and take no bonus, so a stagger buys an escape, not a kill.
 */
public final class Poise {
    private Poise() {}

    public static double max(LivingEntity e) {
        return ERConfig.INSTANCE.poiseBase.get() + ERConfig.INSTANCE.poisePerHealth.get() * e.getMaxHealth();
    }

    public static boolean isHunter(LivingEntity e) { return e.getType().is(ERTags.HUNTERS); }

    /** Returns true when this damage broke the creature's poise. */
    public static boolean damage(LivingEntity e, double amount, @Nullable ServerPlayer attacker, @Nullable ItemStack tool) {
        if (amount <= 0 || e instanceof Player || !e.isAlive()) return false;
        CombatTracker.State s = CombatTracker.of(e);
        if (s.poise < 0) s.poise = max(e);
        if (s.staggerTicks > 0) return false;
        s.poise -= amount;
        s.lastHitTime = e.level().getGameTime();
        if (s.poise > 0) {
            if (s.poise < max(e) * 0.3 && e.level() instanceof ServerLevel sl)
                sl.sendParticles(ParticleTypes.CRIT, e.getX(), e.getEyeY(), e.getZ(), 3, 0.3, 0.2, 0.3, 0.02);
            return false;
        }
        stagger(e, attacker, tool);
        return true;
    }

    public static void stagger(LivingEntity e, @Nullable ServerPlayer attacker, @Nullable ItemStack tool) {
        CombatTracker.State s = CombatTracker.of(e);
        boolean hunter = isHunter(e);
        s.staggerTicks = hunter ? ERConfig.INSTANCE.hunterReelTicks.get() : ERConfig.INSTANCE.staggerTicks.get();
        s.poise = max(e);
        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, s.staggerTicks, 7, false, false));
        e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, s.staggerTicks, 1, false, false));
        if (e.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.CRIT, e.getX(), e.getEyeY() + 0.3, e.getZ(), 12, 0.4, 0.2, 0.4, 0.1);
            sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.35F, 1.6F);
        }
        if (attacker != null && !RuneCaster.isCasting()) {
            ItemStack weapon = tool != null ? tool : attacker.getMainHandItem();
            RuneCaster.fireTool(attacker, weapon, RuneIds.ON_STAGGER, TriggerContext.of(attacker, e));
        }
    }

    /** Called every tick for creatures with state: refills poise and counts the stagger down. */
    public static void tick(LivingEntity e) {
        CombatTracker.State s = CombatTracker.peek(e);
        if (s == null) return;
        if (s.staggerTicks > 0) {
            s.staggerTicks--;
            if (s.staggerTicks % 5 == 0 && e.level() instanceof ServerLevel sl)
                sl.sendParticles(ParticleTypes.CRIT, e.getX(), e.getEyeY() + 0.4, e.getZ(), 2, 0.3, 0.1, 0.3, 0.0);
        } else if (s.poise >= 0 && s.poise < max(e) && e.level().getGameTime() - s.lastHitTime > 60) {
            s.poise = Math.min(max(e), s.poise + max(e) / 100.0);
        }
    }
}
