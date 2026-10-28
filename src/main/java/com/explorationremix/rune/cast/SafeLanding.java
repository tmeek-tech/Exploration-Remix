package com.explorationremix.rune.cast;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Leap and Dash promise a soft landing and count as momentum for a short while. */
public final class SafeLanding {
    private static final Map<UUID, Long> SAFE_UNTIL = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> MOMENTUM_UNTIL = new ConcurrentHashMap<>();
    private SafeLanding() {}

    public static void grant(Player p, int ticks) {
        SAFE_UNTIL.put(p.getUUID(), p.level().getGameTime() + ticks);
        MOMENTUM_UNTIL.put(p.getUUID(), p.level().getGameTime() + 15);
    }

    public static boolean isSafe(LivingEntity e) {
        Long t = SAFE_UNTIL.get(e.getUUID());
        return t != null && e.level().getGameTime() <= t;
    }

    public static void consume(LivingEntity e) { SAFE_UNTIL.remove(e.getUUID()); }

    public static boolean hasMomentum(LivingEntity e) {
        Long t = MOMENTUM_UNTIL.get(e.getUUID());
        return t != null && e.level().getGameTime() <= t;
    }
}
