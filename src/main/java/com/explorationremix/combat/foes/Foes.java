package com.explorationremix.combat.foes;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.SwellGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.ZombieAttackGoal;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Vanilla's hostile mobs fight the new way (0.23.8, Taran 2026-11-27: "make vanilla hostile mobs harder and give them
 * better animations"). As each one joins a world it gets a quarter more health and half again the blow, and its attack
 * goal is swapped for one of ours: every blow now winds up first (the mob's own voice, a rush of air, a glint at its
 * eyes, and a pose its body takes, see client.foes), so it can be parried, blocked, stepped out of, or stopped by a
 * strong attack, as the cave hunters' are; each kind has one move of its own; and a group takes turns at its quarry
 * ({@link FoeTurns}) while the rest circle it.
 * <p>
 * Here: the mobs' joining, the moves' phases (told to every game round, which poses the mob for them), the tells, and
 * what the combat layer asks (is it winding up, stop its strike).
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class Foes {
    /** The health and blow modifiers, the same for every mob, so a mob loaded again keeps the one it has. */
    static final UUID HEALTH_ID = UUID.fromString("6f1d8a2e-5b4c-4e0a-9a51-2c7e1b9d0a35");
    static final UUID DAMAGE_ID = UUID.fromString("0c3b7f4a-8e29-4d61-b5a0-9e4f2d1c7b86");

    /** What a mob is doing now, as the combat layer asks it. Never saved: a move lasts a second or two. */
    static final class Act {
        FoeMove move = FoeMove.NONE;
        FoeMove.Phase phase = FoeMove.Phase.IDLE;
        /** A strong attack landed in its wind-up: the strike is to be dropped. */
        boolean interrupted;
    }

    private static final Map<LivingEntity, Act> ACTS = new WeakHashMap<>();

    private Foes() {}

    // ------------------------------------------------------------------------------------------------ settings

    public static boolean enabled(FoeKind k) {
        try {
            return ERConfig.INSTANCE.foesEnabled.get() && ERConfig.INSTANCE.foesKinds.get().contains(k.key);
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    static double damage() { return read(() -> ERConfig.INSTANCE.foesDamage.get(), FoeRules.DAMAGE); }

    static double health() { return read(() -> ERConfig.INSTANCE.foesHealth.get(), FoeRules.HEALTH); }

    static double windupScale() { return read(() -> ERConfig.INSTANCE.foesWindup.get(), 1.0); }

    static boolean glint() { return read(() -> ERConfig.INSTANCE.foesGlint.get() ? 1.0 : 0.0, 1.0) > 0.5; }

    static int strikers() {
        if (read(() -> ERConfig.INSTANCE.foesStrikeTogether.get() ? 1.0 : 0.0, 0.0) > 0.5) return 0;
        return (int) read(() -> (double) ERConfig.INSTANCE.foesStrikers.get(), FoeRules.STRIKERS);
    }

    private static double read(Supplier<Double> s, double fallback) {
        try {
            return s.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------------------------------------ moves

    private static Act act(LivingEntity e) { return ACTS.computeIfAbsent(e, k -> new Act()); }

    /** {@code m} starts {@code phase} of {@code move}, for {@code ticks}: noted, and told to every game that sees it. */
    static void phase(Mob m, FoeMove move, FoeMove.Phase phase, int ticks) {
        Act a = act(m);
        a.move = move;
        a.phase = phase;
        if (phase != FoeMove.Phase.WINDUP) a.interrupted = false;
        FoeNet.move(m, move, phase, ticks);
    }

    /** Whether {@code e} is winding up a blow now (a strong attack landing on it stops the blow, see CombatEvents). */
    public static boolean windingUp(LivingEntity e) {
        Act a = ACTS.get(e);
        return a != null && a.phase == FoeMove.Phase.WINDUP;
    }

    /** What {@code e} is doing now, in words (for the debug command). */
    static String doing(LivingEntity e) {
        Act a = ACTS.get(e);
        if (a == null || a.phase == FoeMove.Phase.IDLE) return "no move";
        return a.move.name().toLowerCase(Locale.ROOT) + " " + a.phase.name().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code e} is winding up or striking now (an enderman doesn't flit away mid blow). */
    public static boolean inMove(LivingEntity e) {
        Act a = ACTS.get(e);
        return a != null && (a.phase == FoeMove.Phase.WINDUP || a.phase == FoeMove.Phase.STRIKE);
    }

    /**
     * A strong attack caught {@code e} winding up: its blow is dropped (the caller staggers it, as with the hunters), and
     * every game sees its wind-up broken off.
     */
    public static void interrupt(LivingEntity e) {
        Act a = ACTS.get(e);
        if (a == null || a.phase != FoeMove.Phase.WINDUP) return;
        a.interrupted = true;
        a.phase = FoeMove.Phase.IDLE;
        FoeNet.move(e, a.move, FoeMove.Phase.IDLE, 0);
    }

    /**
     * A pillager about to loose a bolt at its quarry ({@link FoeRules#SHOT_TELL} ticks from now, see the mixin on the
     * crossbow goal): the tell at its crossbow, as a skeleton's shot has.
     */
    public static void boltTell(Mob m) {
        if (m.level().isClientSide || m.getType() != EntityType.PILLAGER || !enabled(FoeKind.PILLAGER)) return;
        LivingEntity t = m.getTarget();
        if (t == null || !m.getSensing().hasLineOfSight(t)) return;
        Vec3 at = m.getEyePosition().add(m.getViewVector(1.0F).scale(0.8)).add(0.0, -0.3, 0.0);
        tellAt(m, at, t.getEyePosition().subtract(at), 1.2F);
        phase(m, FoeMove.BOLT, FoeMove.Phase.WINDUP, FoeRules.SHOT_TELL + 1);
    }

    /** Whether {@code m}'s wind-up was interrupted since it last asked (once). */
    static boolean takeInterrupt(Mob m) {
        Act a = ACTS.get(m);
        if (a == null || !a.interrupted) return false;
        a.interrupted = false;
        return true;
    }

    // ------------------------------------------------------------------------------------------------ tells

    /**
     * {@code m} winds up {@code move} for {@code ticks}: its own voice, a rush of air under it stretched to the wind-up's
     * length, and (unless the server turned it off) a glint at its eyes, as the cave hunters' wind-ups have.
     */
    static void tell(Mob m, FoeMove move, int ticks) {
        if (!(m.level() instanceof ServerLevel l)) return;
        SoundEvent voice = voice(m, move);
        float pitch = voicePitch(m, move);
        if (voice != null) l.playSound(null, m.getX(), m.getEyeY(), m.getZ(), voice, SoundSource.HOSTILE, 1.15F, pitch * (0.95F + m.getRandom().nextFloat() * 0.1F));
        // the rush lasts half a second at pitch 1: stretched to the wind-up's length
        float stretch = Mth.clamp(10.0F / Math.max(1, ticks), 0.6F, 1.6F);
        l.playSound(null, m.getX(), m.getEyeY(), m.getZ(), ERSounds.FOE_WINDUP.get(), SoundSource.HOSTILE, 0.9F, stretch);
        if (glint()) RuneFx.windup(l, m.getEyePosition(), m.getViewVector(1.0F), glintSize(m));
    }

    /** A tell at a weapon rather than the eyes: an arrow or a bolt about to go, at {@code at}, pointing {@code dir}. */
    static void tellAt(Mob m, Vec3 at, Vec3 dir, float pitch) {
        if (!(m.level() instanceof ServerLevel l)) return;
        l.playSound(null, at.x, at.y, at.z, ERSounds.FOE_DRAW.get(), SoundSource.HOSTILE, 1.0F, pitch * (0.95F + m.getRandom().nextFloat() * 0.1F));
        if (glint()) RuneFx.windup(l, at, dir, 0.8F);
    }

    private static float glintSize(Mob m) {
        if (m.getType() == EntityType.CAVE_SPIDER) return 0.6F;
        if (m.getType() == EntityType.SPIDER || m.getType() == EntityType.SLIME) return 0.8F;
        if (m.getType() == EntityType.ENDERMAN) return 1.15F;
        return 1.0F;
    }

    @Nullable
    private static SoundEvent voice(Mob m, FoeMove move) {
        EntityType<?> t = m.getType();
        if (t == EntityType.ZOMBIE) return SoundEvents.ZOMBIE_AMBIENT;
        if (t == EntityType.HUSK) return SoundEvents.HUSK_AMBIENT;
        if (t == EntityType.ZOMBIE_VILLAGER) return SoundEvents.ZOMBIE_VILLAGER_AMBIENT;
        if (t == EntityType.DROWNED) return m.isInWater() ? SoundEvents.DROWNED_AMBIENT_WATER : SoundEvents.DROWNED_AMBIENT;
        if (t == EntityType.SKELETON) return SoundEvents.SKELETON_AMBIENT;
        if (t == EntityType.STRAY) return SoundEvents.STRAY_AMBIENT;
        if (t == EntityType.SPIDER || t == EntityType.CAVE_SPIDER) return SoundEvents.SPIDER_AMBIENT;
        if (t == EntityType.ENDERMAN) return SoundEvents.ENDERMAN_SCREAM;
        if (t == EntityType.WITCH) return SoundEvents.WITCH_CELEBRATE;
        if (t == EntityType.VINDICATOR) return SoundEvents.VINDICATOR_AMBIENT;
        if (t == EntityType.SLIME) return SoundEvents.SLIME_SQUISH;
        return null;
    }

    private static float voicePitch(Mob m, FoeMove move) {
        EntityType<?> t = m.getType();
        if (t == EntityType.CAVE_SPIDER) return 1.45F;
        if (t == EntityType.SPIDER) return 1.2F;
        if (t == EntityType.SLIME) return 0.55F + 0.1F * (4 - Math.min(4, ((Slime) m).getSize()));
        if (t == EntityType.ENDERMAN) return 1.25F;
        if (m.isBaby()) return 1.4F;
        return 0.8F;
    }

    // ------------------------------------------------------------------------------------------------ joining

    /** A mob joins a world: one of the kinds that fight the new way gets its health, its blow and its goals. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void joined(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.isCanceled() || !(e.getEntity() instanceof Mob mob)) return;
        FoeKind k = FoeKind.of(mob);
        if (k == null) return;
        if (!enabled(k)) {
            plain(mob);
            return;
        }
        stats(mob, k, !e.loadedFromDisk());
        goals(mob, k);
    }

    /** Health a quarter more, blows half again (as set); a mob new to the world comes at its full new health. */
    static void stats(Mob mob, FoeKind k, boolean fresh) {
        modifier(mob.getAttribute(Attributes.MAX_HEALTH), HEALTH_ID, "Exploration Remix: tougher", health() - 1.0);
        modifier(mob.getAttribute(Attributes.ATTACK_DAMAGE), DAMAGE_ID, "Exploration Remix: harder blows", k.harder ? damage() - 1.0 : 0.0);
        if (fresh) mob.setHealth(mob.getMaxHealth());
        else if (mob.getHealth() > mob.getMaxHealth()) mob.setHealth(mob.getMaxHealth());
    }

    /** A kind turned off: what an earlier load gave it goes. */
    private static void plain(Mob mob) {
        modifier(mob.getAttribute(Attributes.MAX_HEALTH), HEALTH_ID, "", 0.0);
        modifier(mob.getAttribute(Attributes.ATTACK_DAMAGE), DAMAGE_ID, "", 0.0);
        if (mob.getHealth() > mob.getMaxHealth()) mob.setHealth(mob.getMaxHealth());
    }

    /** Sets (or with 0 takes off) the multiplier {@code id} on {@code a}; saved with the mob, so it loads with its health. */
    private static void modifier(@Nullable AttributeInstance a, UUID id, String name, double amount) {
        if (a == null) return;
        AttributeModifier m = a.getModifier(id);
        if (Math.abs(amount) < 1.0E-6) {
            if (m != null) a.removeModifier(id);
            return;
        }
        if (m != null && Math.abs(m.getAmount() - amount) < 1.0E-6) return;
        if (m != null) a.removeModifier(id);
        a.addPermanentModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /** One of our goals, so a mob that loads twice isn't given two. */
    interface FoeGoal {}

    /** Swaps vanilla's attack goal of each kind for ours, at the same place among its goals. */
    static void goals(Mob mob, FoeKind k) {
        if (!(mob instanceof PathfinderMob pm)) return;
        switch (k) {
            case ZOMBIE -> {
                Predicate<LivingEntity> ok = mob instanceof Drowned d ? d::okTarget : null;
                swap(mob, g -> g instanceof ZombieAttackGoal, 2, () -> new FoeMeleeGoal(pm, FoeRules.ZOMBIE, 1.0, ok, false));
            }
            case SKELETON -> {
                if (mob instanceof AbstractSkeleton s) reassessed(s);
            }
            case SPIDER, CAVE_SPIDER -> swap(mob, g -> g instanceof LeapAtTargetGoal || g instanceof MeleeAttackGoal, 3,
                    () -> new FoePounceGoal(pm, k == FoeKind.CAVE_SPIDER));
            case CREEPER -> {
                if (mob instanceof Creeper c) swap(mob, g -> g instanceof SwellGoal, 2, () -> new FoeSwellGoal(c));
            }
            case ENDERMAN -> swap(mob, g -> g instanceof MeleeAttackGoal, 2, () -> new FoeMeleeGoal(pm, FoeRules.ENDERMAN, 1.0, null, true));
            case WITCH -> {
                if (mob instanceof Witch w) swap(mob, g -> g instanceof RangedAttackGoal, 2, () -> new FoeThrowGoal(w));
            }
            case VINDICATOR -> swap(mob, g -> g instanceof MeleeAttackGoal, 4, () -> new FoeMeleeGoal(pm, FoeRules.VINDICATOR, 1.0, null, false));
            default -> {}
        }
    }

    /**
     * A skeleton weighs up its weapon (as it comes into the world, loads, or is handed something): vanilla gives it its
     * bow goal or its melee goal; ours does both, so theirs go again.
     */
    public static void reassessed(AbstractSkeleton s) {
        if (s.level() == null || s.level().isClientSide) return;
        FoeKind k = FoeKind.of(s);
        if (k == null || !enabled(k)) return;
        swap(s, g -> g instanceof RangedBowAttackGoal || g instanceof MeleeAttackGoal, 4, () -> new FoeBowGoal(s));
    }

    private static void swap(Mob mob, Predicate<Goal> old, int priority, Supplier<? extends Goal> made) {
        List<Goal> gone = new ArrayList<>(2);
        boolean ours = false;
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) {
            if (w.getGoal() instanceof FoeGoal) ours = true;
            else if (old.test(w.getGoal())) gone.add(w.getGoal());
        }
        // one at a time, so a running one is stopped as it goes
        for (Goal g : gone) mob.goalSelector.removeGoal(g);
        if (!ours) mob.goalSelector.addGoal(priority, made.get());
    }

    // ------------------------------------------------------------------------------------------------ slimes and the rest

    @SubscribeEvent
    public static void livingTick(LivingEvent.LivingTickEvent e) {
        if (e.getEntity() instanceof Slime s && !s.level().isClientSide && s.getType() == EntityType.SLIME) FoeSlimes.tick(s);
    }

    /** How much of a leap's speed along {@code e} keeps after the tick it sets off in ({@link FoeRules#firstDrag}). */
    static double firstDrag(LivingEntity e) {
        if (!e.onGround()) return FoeRules.AIR_DRAG;
        BlockPos below = BlockPos.containing(e.getX(), e.getY() - 0.500001, e.getZ());
        return FoeRules.firstDrag(true, e.level().getBlockState(below).getFriction(e.level(), below, e));
    }

    /** Whether {@code p} can be fought: alive, and neither creative nor a spectator. */
    static boolean fair(@Nullable LivingEntity t) {
        if (t == null || !t.isAlive() || t.isRemoved()) return false;
        return !(t instanceof Player p) || !p.isCreative() && !p.isSpectator();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent e) {
        ACTS.clear();
        FoeTurns.clear();
        FoeSlimes.clear();
    }
}
