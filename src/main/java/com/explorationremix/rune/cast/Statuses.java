package com.explorationremix.rune.cast;

import com.explorationremix.rune.engine.Reactions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.Tags;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What the elements leave on a creature for a while: wet (Tide, or standing in water or rain), frozen (Frost on the
 * wet), held in place (stunned by Storm on the wet, or rooted by the freeze), and a spell of immunity after a hold, so
 * no creature can be kept held. Server only and never saved: everything here wears off within seconds.
 *
 * <p>A held creature skips its own ticks (it stands frozen in place and cannot attack); players and bosses are never
 * held.
 */
public final class Statuses {
    private static final class S {
        long wetUntil, frozenUntil, heldUntil, immuneUntil;
    }

    private static final Map<LivingEntity, S> MAP = new WeakHashMap<>();

    private Statuses() {}

    private static long now(LivingEntity e) { return e.level().getGameTime(); }

    private static S of(LivingEntity e) { return MAP.computeIfAbsent(e, k -> new S()); }

    public static boolean wet(LivingEntity e) {
        S s = MAP.get(e);
        return e.isInWaterRainOrBubble() || (s != null && s.wetUntil > now(e));
    }

    public static boolean frozen(LivingEntity e) {
        S s = MAP.get(e);
        return e.isFullyFrozen() || (s != null && s.frozenUntil > now(e));
    }

    public static boolean held(LivingEntity e) {
        S s = MAP.get(e);
        return s != null && s.heldUntil > now(e);
    }

    public static boolean immune(LivingEntity e) {
        S s = MAP.get(e);
        return s != null && s.immuneUntil > now(e);
    }

    /** Hunters (bosses, and anything the game counts as one) shrug holds off faster; bosses are never held. */
    public static boolean hunter(LivingEntity e) { return e.getMaxHealth() >= 30.0F; }

    public static boolean holdable(LivingEntity e) { return !(e instanceof Player) && !e.getType().is(Tags.EntityTypes.BOSSES); }

    public static void soak(LivingEntity e) {
        of(e).wetUntil = now(e) + Reactions.WET_TICKS;
        e.clearFire();
    }

    public static void dry(LivingEntity e) {
        S s = MAP.get(e);
        if (s != null) s.wetUntil = 0;
    }

    /** Frozen solid for {@code ticks}: rooted, and the frost look (the game's own freezing, which thaws by itself). */
    public static void freeze(LivingEntity e, int ticks) {
        S s = of(e);
        s.frozenUntil = now(e) + ticks + 20;
        s.wetUntil = 0;
        e.setTicksFrozen(Math.max(e.getTicksFrozen(), e.getTicksRequiredToFreeze() + 2 * ticks));
        hold(e, ticks);
    }

    public static void thaw(LivingEntity e) {
        S s = MAP.get(e);
        if (s != null) {
            s.frozenUntil = 0;
            s.heldUntil = Math.min(s.heldUntil, now(e));
        }
        e.setTicksFrozen(0);
    }

    /** Held in place for {@code ticks}, then immune to holds for a while. Players and bosses are never held. */
    public static void hold(LivingEntity e, int ticks) {
        if (!holdable(e) || ticks <= 0) return;
        S s = of(e);
        long t = now(e);
        s.heldUntil = t + ticks;
        s.immuneUntil = t + ticks + Reactions.IMMUNE_TICKS;
        if (e instanceof net.minecraft.world.entity.Mob m) m.getNavigation().stop();
        e.setDeltaMovement(0, Math.min(0, e.getDeltaMovement().y), 0);
        e.hurtMarked = true;
    }

    /**
     * A held creature's tick, in place of its own (the caller cancels its tick): the few things that must still run,
     * its hurt flash and hit immunity counting down, so melee still lands on it.
     */
    public static void heldTick(LivingEntity e) {
        if (e.invulnerableTime > 0) e.invulnerableTime--;
        if (e.hurtTime > 0) e.hurtTime--;
        if (e.level() instanceof ServerLevel sl && (e.tickCount + e.getId()) % 6 == 0) {
            boolean ice = frozen(e);
            sl.sendParticles(ice ? ParticleTypes.SNOWFLAKE : ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ(),
                    2, e.getBbWidth() * 0.4, e.getBbHeight() * 0.3, e.getBbWidth() * 0.4, 0.01);
        }
    }

    /** Drips from a wet creature now and then. */
    public static void wetTick(LivingEntity e) {
        S s = MAP.get(e);
        if (s == null || s.wetUntil <= now(e) || !(e.level() instanceof ServerLevel sl)) return;
        if ((e.tickCount + e.getId()) % 8 == 0) {
            sl.sendParticles(ParticleTypes.DRIPPING_WATER, e.getX(), e.getY() + e.getBbHeight() * 0.8, e.getZ(), 1, e.getBbWidth() * 0.3, 0.1,
                    e.getBbWidth() * 0.3, 0.0);
        }
    }

    public static boolean any(LivingEntity e) { return MAP.containsKey(e); }

    public static void clear() { MAP.clear(); }
}
