package com.explorationremix.run;

import com.explorationremix.config.ERConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * XP is a run currency. Every vanilla XP source is shut at this one choke point; only {@link #award} gets through.
 */
public final class RunXp {
    private static final ThreadLocal<Boolean> AWARDING = ThreadLocal.withInitial(() -> false);
    private RunXp() {}

    /** Total spendable points a player holds, from vanilla level and progress. */
    public static int points(Player p) {
        int total = 0;
        for (int level = 0; level < p.experienceLevel; level++) total += xpForLevel(level);
        total += Math.round(p.experienceProgress * p.getXpNeededForNextLevel());
        return total;
    }

    private static int xpForLevel(int level) {
        if (level >= 30) return 112 + (level - 30) * 9;
        return level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
    }

    public static void award(ServerPlayer p, int amount, RunState state) {
        if (amount <= 0) return;
        AWARDING.set(true);
        try { p.giveExperiencePoints(amount); } finally { AWARDING.set(false); }
        if (state != null) state.xpEarned += amount;
    }

    public static boolean spend(ServerPlayer p, int amount, RunState state) {
        if (points(p) < amount) return false;
        AWARDING.set(true);
        try { p.giveExperiencePoints(-amount); } finally { AWARDING.set(false); }
        if (state != null) state.xpSpent += amount;
        return true;
    }

    public static void clear(ServerPlayer p) {
        AWARDING.set(true);
        try {
            p.experienceLevel = 0;
            p.experienceProgress = 0;
            p.totalExperience = 0;
            p.giveExperiencePoints(0);     // forces a client sync
        } finally { AWARDING.set(false); }
    }

    private static boolean blocking() { return ERConfig.INSTANCE.blockXpOrbs.get() && !AWARDING.get(); }

    @SubscribeEvent
    public static void onXpChange(PlayerXpEvent.XpChange e) {
        if (blocking()) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLevelChange(PlayerXpEvent.LevelChange e) {
        if (blocking()) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPickup(PlayerXpEvent.PickupXp e) {
        if (ERConfig.INSTANCE.blockXpOrbs.get()) {
            e.setCanceled(true);
            e.getOrb().discard();
        }
    }

    @SubscribeEvent
    public static void onOrbJoin(EntityJoinLevelEvent e) {
        if (ERConfig.INSTANCE.blockXpOrbs.get() && e.getEntity() instanceof ExperienceOrb && !e.loadedFromDisk()) e.setCanceled(true);
    }
}
