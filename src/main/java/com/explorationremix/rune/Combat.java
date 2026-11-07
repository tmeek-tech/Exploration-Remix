package com.explorationremix.rune;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a player is in combat: something hurt them in the last few seconds ({@code combatSeconds}). Runes can be
 * rearranged anywhere, but only out of combat. Kept on the server only; the rune screen is told how long is left.
 */
public final class Combat {
    private static final Map<UUID, Long> LAST_HURT = new ConcurrentHashMap<>();

    private Combat() {}

    /** Something hurt the player now. */
    public static void hurt(Player p) { LAST_HURT.put(p.getUUID(), p.level().getGameTime()); }

    /** Ticks until the player is out of combat (0: out of combat now). */
    public static int ticksUntilFree(Player p) {
        Long t = LAST_HURT.get(p.getUUID());
        if (t == null) return 0;
        long left = t + RuneConfig.get(RuneConfig.INSTANCE.combatSeconds) * 20L - p.level().getGameTime();
        if (left <= 0 || left > 20L * 3600) {
            LAST_HURT.remove(p.getUUID());
            return 0;
        }
        return (int) left;
    }

    public static void forget(Player p) { LAST_HURT.remove(p.getUUID()); }
}
