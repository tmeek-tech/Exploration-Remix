package com.explorationremix.combat.recovery;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.combat.foes.FoeNet;
import com.explorationremix.config.ERConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A player's health comes back once they have gone a while unhurt (0.23.8, {@link RecoveryRules}): any harm (or, as set,
 * only blows) starts the wait again, so it takes a few hits in a row to bring someone down. It costs a little food and
 * needs more than three drumsticks; the natural regeneration rule turned off turns it off too, and food's own slow
 * healing goes (see the mixin on FoodData), as set. Their hearts ripple while it works (FoeNet tells their game).
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class Recovery {
    private static final class R {
        long lastHurt = Long.MIN_VALUE / 4;
        boolean healing;
    }

    private static final Map<UUID, R> MAP = new HashMap<>();

    private Recovery() {}

    // ------------------------------------------------------------------------------------------------ settings

    private static <T> T read(Supplier<T> s, T fallback) {
        try {
            return s.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    static boolean enabled() { return read(() -> ERConfig.INSTANCE.recoveryEnabled.get(), true); }

    /** Whether food's slow healing is off while this is on (FoodData asks, every tick of every player). */
    public static boolean replacesFoodHealing() {
        return read(() -> ERConfig.INSTANCE.recoveryEnabled.get() && ERConfig.INSTANCE.recoveryReplacesFoodHealing.get(), false);
    }

    private static boolean onlyBlows() { return read(() -> ERConfig.INSTANCE.recoveryOnlyBlows.get(), false); }

    private static int delay() { return RecoveryRules.delayTicks(read(() -> ERConfig.INSTANCE.recoveryDelay.get(), RecoveryRules.DELAY_SECONDS)); }

    private static double hearts() { return read(() -> ERConfig.INSTANCE.recoveryHearts.get(), RecoveryRules.HEARTS_PER_SECOND); }

    private static double cost() { return read(() -> ERConfig.INSTANCE.recoveryFoodCost.get(), RecoveryRules.FOOD_COST); }

    private static int minFood() { return read(() -> ERConfig.INSTANCE.recoveryMinFood.get(), RecoveryRules.MIN_FOOD); }

    private static R state(ServerPlayer p) { return MAP.computeIfAbsent(p.getUUID(), u -> new R()); }

    // ------------------------------------------------------------------------------------------------ harm

    /** A creature's (or a player's) blow, a shot, a thrown potion or a blast: what counts when only blows stop it. */
    static boolean blow(DamageSource src) {
        return src.getEntity() instanceof LivingEntity || src.is(DamageTypeTags.IS_EXPLOSION);
    }

    /** Harm landing on a player (after everything else made of it what it would): the wait starts again. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hurt(LivingHurtEvent e) {
        if (e.getAmount() <= 0.0F || !(e.getEntity() instanceof ServerPlayer p) || p instanceof FakePlayer) return;
        if (onlyBlows() && !blow(e.getSource())) return;
        R r = state(p);
        r.lastHurt = p.server.getTickCount();
        if (r.healing) {
            r.healing = false;
            FoeNet.healing(p, false);
        }
    }

    // ------------------------------------------------------------------------------------------------ healing

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer p) || p instanceof FakePlayer) return;
        R r = state(p);
        boolean on = heals(p, r);
        if (on) {
            float step = Math.min(RecoveryRules.perTick(hearts()), p.getMaxHealth() - p.getHealth());
            if (step > 0.0F) {
                p.heal(step);
                p.causeFoodExhaustion(RecoveryRules.exhaustion(step, cost()));
            }
        }
        if (on != r.healing) {
            r.healing = on;
            FoeNet.healing(p, on);
        }
    }

    private static boolean heals(ServerPlayer p, R r) {
        if (!enabled() || !p.isAlive() || p.isSpectator() || !p.isHurt()) return false;
        if (!p.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION)) return false;
        if (p.getFoodData().getFoodLevel() < minFood()) return false;
        return RecoveryRules.due(p.server.getTickCount(), r.lastHurt, delay());
    }

    /** For the debug command: ticks since {@code p} was last harmed, and whether their health is coming back. */
    public static long sinceHurt(ServerPlayer p) { return p.server.getTickCount() - state(p).lastHurt; }

    public static boolean healing(ServerPlayer p) { return state(p).healing; }

    // ------------------------------------------------------------------------------------------------ the rest

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        // their game starts without the ripple: told again on the next tick it works
        if (e.getEntity() instanceof ServerPlayer p) state(p).healing = false;
    }

    @SubscribeEvent
    public static void respawned(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            R r = state(p);
            if (r.healing) FoeNet.healing(p, false);
            r.healing = false;
        }
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        MAP.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent e) {
        MAP.clear();
    }
}
