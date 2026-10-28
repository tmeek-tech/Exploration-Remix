package com.explorationremix.combat;

import com.explorationremix.config.ERConfig;
import com.explorationremix.creature.NoiseBus;
import com.explorationremix.network.CombatFxPacket;
import com.explorationremix.network.ERNetwork;
import com.explorationremix.rune.RuneIds;
import com.explorationremix.rune.cast.RuneCaster;
import com.explorationremix.rune.cast.SafeLanding;
import com.explorationremix.rune.cast.TriggerContext;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * The mod's own combat layer on top of vanilla (and Better Combat's swings): momentum hits, poise and stagger,
 * wall slams, element reactions, and hit feel. No dodge, no parry, no stamina bar.
 */
public final class CombatEvents {
    private CombatEvents() {}

    private static double weaponWeight(ItemStack s) {
        if (s.getItem() instanceof AxeItem) return 1.6;
        if (s.getItem() instanceof TridentItem) return 1.4;
        if (s.getItem() instanceof SwordItem) return 1.0;
        return 0.7;
    }

    /** Vanilla's falling critical hit is replaced by momentum, so the two never stack. */
    @SubscribeEvent
    public static void onCrit(CriticalHitEvent e) {
        if (e.isVanillaCritical()) {
            e.setDamageModifier(1.0F);
            e.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHurt(LivingHurtEvent e) {
        LivingEntity victim = e.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        CombatTracker.State vs = CombatTracker.of(victim);
        long now = level.getGameTime();
        boolean rune = RuneCaster.isCasting();

        // Staggered creatures take extra damage (hunters do not).
        if (vs.staggerTicks > 0 && !Poise.isHunter(victim) && !(victim instanceof Player))
            e.setAmount(e.getAmount() * ERConfig.INSTANCE.staggerDamageMultiplier.get().floatValue());

        if (e.getSource().getEntity() instanceof ServerPlayer attacker && e.getSource().is(DamageTypes.PLAYER_ATTACK) && attacker != victim) {
            ItemStack weapon = attacker.getMainHandItem();
            double weight = weaponWeight(weapon);
            // Momentum: sprinting, dashing, leaping, or falling into the hit.
            Vec3 v = attacker.getDeltaMovement();
            double speed = Math.sqrt(v.x * v.x + v.z * v.z) + Math.max(0, -v.y) * 0.8;
            if (attacker.isSprinting()) speed = Math.max(speed, 0.28);
            if (SafeLanding.hasMomentum(attacker)) speed = Math.max(speed, 0.6);
            if (attacker.fallDistance > 1.5F) speed = Math.max(speed, 0.3 + attacker.fallDistance * 0.08);
            boolean momentum = speed >= 0.26;
            float bonus = 0;
            if (momentum) {
                bonus = (float) (speed * ERConfig.INSTANCE.momentumDamagePerSpeed.get());
                e.setAmount(e.getAmount() + bonus);
                level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getEyeY(), victim.getZ(), 8, 0.3, 0.3, 0.3, 0.2);
                level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.6F, 1.1F);
                if (!rune) RuneCaster.fireTool(attacker, weapon, RuneIds.ON_MOMENTUM_HIT, TriggerContext.of(attacker, victim).withDamage(e.getAmount()));
            }
            // Frozen creatures shatter under a heavy hit.
            if (CombatTracker.isFrozen(victim) && (weight >= 1.4 || e.getAmount() >= 7)) {
                e.setAmount(e.getAmount() * 2.5F);
                vs.frozenUntil = 0;
                level.sendParticles(ParticleTypes.SNOWFLAKE, victim.getX(), victim.getEyeY(), victim.getZ(), 40, 0.5, 0.6, 0.5, 0.2);
                level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 0.8F);
            }
            // Poise: weapon weight, finishers and momentum wear it down.
            boolean finisher = CombatTracker.isFinisher(attacker);
            double poise = e.getAmount() * weight * (finisher ? 2.0 : 1.0) + (momentum ? speed * ERConfig.INSTANCE.momentumPoisePerSpeed.get() : 0);
            boolean broke = Poise.damage(victim, poise, attacker, weapon);
            // Hit feel: hit-stop and a kick on heavy hits, finishers, momentum and stagger breaks.
            if (broke || finisher || momentum || e.getAmount() >= 7) {
                float kick = (float) Math.min(1.0, (broke ? 0.6 : 0.3) + e.getAmount() / 20.0);
                Vec3 dir = victim.position().subtract(attacker.position()).normalize();
                ERNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> attacker),
                        new CombatFxPacket(broke ? ERConfig.INSTANCE.hitStopTicks.get() : Math.max(1, ERConfig.INSTANCE.hitStopTicks.get() / 2), kick, (float) dir.x, (float) dir.z));
            }
            NoiseBus.emit(level, victim.position(), 10 + weight * 3, attacker);
        }

        // Falling debris from a Blast hurts whatever it lands on (vanilla handles the damage; we add poise).
        if (e.getSource().is(DamageTypes.FALLING_BLOCK) && e.getSource().getDirectEntity() instanceof FallingBlockEntity f && f.getTags().contains("er_debris")) {
            Poise.damage(victim, e.getAmount() * 2, null, null);
        }
    }

    /** Knockback is remembered so a wall hit right after it counts as a slam. */
    @SubscribeEvent
    public static void onKnockback(LivingKnockBackEvent e) {
        if (e.getStrength() <= 0.3F) return;
        CombatTracker.State s = CombatTracker.of(e.getEntity());
        s.slamUntil = e.getEntity().level().getGameTime() + 12;
        s.slamSpeed = e.getStrength();
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent e) {
        LivingEntity le = e.getEntity();
        if (!(le.level() instanceof ServerLevel level)) return;
        CombatTracker.State s = CombatTracker.peek(le);
        if (s == null) return;
        long now = level.getGameTime();
        Poise.tick(le);
        // Wet: water, rain and bubbles keep a creature wet for a few seconds afterwards.
        if (le.isInWaterRainOrBubble()) s.wetUntil = now + 100;
        // Frozen solid: no movement until it thaws or shatters.
        if (now < s.frozenUntil) {
            Vec3 d = le.getDeltaMovement();
            le.setDeltaMovement(0, Math.min(0, d.y), 0);
            if (now % 4 == 0) level.sendParticles(ParticleTypes.SNOWFLAKE, le.getX(), le.getEyeY(), le.getZ(), 1, 0.3, 0.3, 0.3, 0.0);
        }
        // Ember spreads between packed creatures, never to blocks.
        if (now < s.emberUntil && now % 10 == 0) {
            double r = ERConfig.INSTANCE.emberSpreadRange.get();
            for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, le.getBoundingBox().inflate(r), o -> o != le && o.isAlive() && !(o instanceof Player))) {
                CombatTracker.State os = CombatTracker.of(other);
                if (now < os.emberUntil) continue;
                other.setSecondsOnFire(3);
                os.emberUntil = now + 60;
                os.emberSource = s.emberSource;
                level.sendParticles(ParticleTypes.FLAME, other.getX(), other.getEyeY(), other.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
            }
        }
        // Wall slam: a creature in knockback that stops against a wall takes impact damage by speed.
        if (now <= s.slamUntil) {
            double speed = Math.sqrt(s.lastDelta.x * s.lastDelta.x + s.lastDelta.z * s.lastDelta.z);
            if (le.horizontalCollision && speed >= ERConfig.INSTANCE.wallSlamMinSpeed.get()) {
                float dmg = (float) (speed * ERConfig.INSTANCE.wallSlamDamagePerSpeed.get());
                s.slamUntil = 0;
                le.hurt(level.damageSources().flyIntoWall(), dmg);
                Poise.damage(le, dmg * 3, null, null);
                level.sendParticles(ParticleTypes.POOF, le.getX(), le.getEyeY(), le.getZ(), 10, 0.3, 0.3, 0.3, 0.05);
                level.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.GENERIC_BIG_FALL, SoundSource.HOSTILE, 0.8F, 1.0F);
                NoiseBus.emit(level, le.position(), 12, le);
            }
        }
        s.lastDelta = le.getDeltaMovement();
    }
}
