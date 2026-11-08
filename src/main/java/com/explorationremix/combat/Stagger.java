package com.explorationremix.combat;

import com.explorationremix.rune.cast.RuneHits;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraftforge.common.Tags;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Each creature's hidden poise, on the server. Hits from players wear it (Poise says how much); once it runs out the
 * creature reels: for {@link Poise#STAGGER_TICKS} it does nothing (its mind stops, a creeper's fuse falls back, a drawn
 * bow is let down), and players' hits on it land {@link Poise#STAGGER_BONUS} times harder. The cave hunters and big
 * creatures only reel briefly, with no bonus. After a stagger its poise is full again and can't be worn for a while,
 * so no creature can be kept reeling. Players and bosses never stagger. Never saved: it all wears off in seconds.
 */
public final class Stagger {
    private static final class S {
        /** Poise left as of {@code since}; below 0: full. */
        float poise = -1.0F;
        long since, lastHit = Long.MIN_VALUE / 4;
        long reelUntil, immuneUntil;
        boolean bonus;
    }

    private static final Map<LivingEntity, S> MAP = new WeakHashMap<>();

    private Stagger() {}

    private static long now(LivingEntity e) { return e.level().getGameTime(); }

    /** Whether {@code e} has poise to break at all: a creature with a mind to stop, never a player or a boss. */
    public static boolean staggerable(LivingEntity e) {
        return e instanceof Mob && !e.level().isClientSide && e.isAlive() && !e.getType().is(Tags.EntityTypes.BOSSES);
    }

    /** Whether {@code e} is reeling now (server side; the client is only shown it, and never asks here). */
    public static boolean reeling(LivingEntity e) {
        if (e.level().isClientSide || MAP.isEmpty()) return false;
        S s = MAP.get(e);
        return s != null && s.reelUntil > now(e);
    }

    /** What a player's hit on {@code e} is multiplied by now: more while it reels (not a hunter). */
    public static float bonus(LivingEntity e) {
        S s = MAP.get(e);
        return s != null && s.bonus && s.reelUntil > now(e) ? Poise.STAGGER_BONUS : 1.0F;
    }

    /** Its poise as it stands now, and the most it has (for commands and tests in the world). */
    public static float poise(LivingEntity e) {
        S s = MAP.get(e);
        float max = Poise.max(e.getMaxHealth());
        return s == null || s.poise < 0.0F ? max : Poise.at(s.poise, max, s.since, s.lastHit, now(e));
    }

    /**
     * Wears {@code amount} of {@code e}'s poise. True if that broke it: it reels from now. Nothing while it reels, or
     * just after.
     */
    public static boolean wear(LivingEntity e, float amount) {
        if (amount <= 0.0F || !staggerable(e)) return false;
        long now = now(e);
        S s = MAP.computeIfAbsent(e, k -> new S());
        if (s.reelUntil > now || s.immuneUntil > now) return false;
        float left = poise(e) - amount;
        s.lastHit = now;
        s.since = now;
        if (left > 0.0F) {
            s.poise = left;
            return false;
        }
        boolean hunter = RuneHits.hunter(e);
        int ticks = hunter ? Poise.HUNTER_REEL_TICKS : Poise.STAGGER_TICKS;
        s.poise = -1.0F;
        s.reelUntil = now + ticks;
        s.immuneUntil = now + ticks + Poise.IMMUNE_TICKS;
        s.bonus = !hunter;
        reel(e, ticks);
        return true;
    }

    /** Its poise breaks: whatever it was about to do stops, its head drops, and everyone near sees and hears it. */
    private static void reel(LivingEntity e, int ticks) {
        if (e instanceof Mob m) {
            m.getNavigation().stop();
            m.setXRot(Mth.clamp(m.getXRot() + 25.0F, -90.0F, 90.0F));
        }
        // a drawn bow is let down, a potion half drunk is put away
        if (e.isUsingItem()) e.stopUsingItem();
        if (e instanceof Creeper c) c.setSwellDir(-1);
        if (e.level() instanceof ServerLevel l) {
            RuneFx.stagger(l, e.position().add(0, e.getBbHeight() * 0.6, 0), e.getId(), e.getBbWidth(), ticks);
        }
    }

    /** Each tick of a creature that reels: a creeper's fuse keeps falling back (its own tick would light it again). */
    public static void tick(LivingEntity e) {
        if (MAP.isEmpty() || !reeling(e)) return;
        if (e instanceof Creeper c && !c.isIgnited()) c.setSwellDir(-1);
    }

    public static void clear() { MAP.clear(); }
}
