package com.explorationremix.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Per-entity combat state the mod's combat layer reads: poise, the stagger timer, a Better Combat finisher window,
 * and element marks (wet, frozen, ember). Everything here is transient and never saved.
 */
public final class CombatTracker {
    public static final class State {
        public double poise = -1;          // -1 until initialised from max health
        public int staggerTicks;
        public long finisherWindowUntil;   // game time; a hit before this counts as a combo finisher
        public long wetUntil;
        public long frozenUntil;
        public long emberUntil;
        public UUID emberSource;
        public long lastHitTime;
        public int comboCount;
        public long slamUntil;        // a shove or knockback is in flight; a wall hit before this slams
        public double slamSpeed;
        public net.minecraft.world.phys.Vec3 lastDelta = net.minecraft.world.phys.Vec3.ZERO;
    }

    private static final Map<LivingEntity, State> STATES = new WeakHashMap<>();
    private CombatTracker() {}

    public static State of(LivingEntity e) {
        synchronized (STATES) { return STATES.computeIfAbsent(e, k -> new State()); }
    }

    public static State peek(LivingEntity e) {
        synchronized (STATES) { return STATES.get(e); }
    }

    /** Better Combat told us the player's next hit is a combo finisher. */
    public static void markFinisherWindow(Player p) {
        of(p).finisherWindowUntil = p.level().getGameTime() + 10;
    }

    public static boolean isFinisher(Player p) {
        State s = peek(p);
        return s != null && p.level().getGameTime() <= s.finisherWindowUntil;
    }

    public static boolean isStaggered(LivingEntity e) {
        State s = peek(e);
        return s != null && s.staggerTicks > 0;
    }

    public static boolean isWet(LivingEntity e) { State s = peek(e); return s != null && e.level().getGameTime() < s.wetUntil || e.isInWaterOrRain(); }
    public static boolean isFrozen(LivingEntity e) { State s = peek(e); return s != null && e.level().getGameTime() < s.frozenUntil; }
    public static boolean isEmber(LivingEntity e) { State s = peek(e); return s != null && e.level().getGameTime() < s.emberUntil; }
}
