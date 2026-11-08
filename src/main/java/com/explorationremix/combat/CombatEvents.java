package com.explorationremix.combat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The combat layer on the vanilla swing (Forge bus, server side): poise worn by players' hits and the stagger when it
 * breaks, momentum hits, wall slams, and the flash and sparks where a melee hit lands. Taran's picks (2026-11-08):
 * hidden poise, momentum, slams and hit feel; no dodge, parry or stamina, and blocking does only what it always did.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class CombatEvents {
    /** What a rune's hit does to poise, set by the runes around each hit they deal (1 when not set). */
    private static float runePoise = 1.0F;

    private CombatEvents() {}

    /** Runes: the hit about to be dealt wears poise times {@code factor} (an Orb more, a Field's pulse little). */
    public static void runePoise(float factor) { runePoise = factor; }

    // ------------------------------------------------------------------------------------------------ swings

    @SubscribeEvent
    public static void attack(AttackEntityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && !(p instanceof FakePlayer) && e.getTarget() instanceof LivingEntity t) {
            Fighters.swing(p, t, p.getAttackStrengthScale(0.5F));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void crit(CriticalHitEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        boolean crit = e.getResult() == Event.Result.ALLOW || (e.getResult() == Event.Result.DEFAULT && e.isVanillaCritical());
        if (crit) Fighters.crit(p);
    }

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase == TickEvent.Phase.START && e.player instanceof ServerPlayer p && !(p instanceof FakePlayer)) Fighters.tick(p);
    }

    // ------------------------------------------------------------------------------------------------ hits

    /**
     * A player's hit is landing on a creature: a reeling one takes more, a melee hit carries the player's momentum, and
     * the hit wears the creature's poise. Last of all, so the damage is what other mods left it at.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hurt(LivingHurtEvent e) {
        LivingEntity t = e.getEntity();
        if (e.isCanceled() || t.level().isClientSide() || e.getAmount() <= 0.0F) return;
        DamageSource src = e.getSource();
        // players fight players as they always have
        if (t instanceof Player || !(src.getEntity() instanceof ServerPlayer p) || p instanceof FakePlayer) return;
        float amount = e.getAmount();
        float poise;
        if (src.is(DamageTypes.PLAYER_ATTACK) && src.getDirectEntity() == p && Fighters.swungAt(p, t)) {
            float strength = Fighters.swingStrength(p, t);
            boolean crit = Fighters.crit(p, t);
            float momentum = strength >= 0.9F ? Poise.momentum(Fighters.closing(p, t)) : 0.0F;
            if (momentum > 0.0F) {
                amount *= Poise.momentumDamage(momentum);
                Fighters.pushNext(p, t, Poise.momentumPush(momentum));
            }
            poise = Poise.melee(amount, weapon(p.getMainHandItem().getItem()), strength, crit, momentum);
            feel(p, t, strength, crit, momentum);
        } else if (src.is(DamageTypes.PLAYER_ATTACK)) {
            // a sweep's edge, on the creatures beside the one swung at
            poise = amount * 0.5F;
        } else if (src.is(RuneRegistry.SLAM_DAMAGE)) {
            poise = Poise.slam(amount);
        } else if (src.is(RuneRegistry.RUNE_DAMAGE)) {
            poise = amount * runePoise;
        } else if (src.getDirectEntity() instanceof AbstractArrow) {
            poise = Poise.arrow(amount);
        } else {
            poise = 0.0F;
        }
        amount *= Stagger.bonus(t);
        e.setAmount(amount);
        Stagger.wear(t, poise);
    }

    private static Poise.Weapon weapon(Item item) {
        if (item instanceof AxeItem) return Poise.Weapon.AXE;
        if (item instanceof SwordItem) return Poise.Weapon.SWORD;
        return Poise.Weapon.OTHER;
    }

    /** Where a melee hit lands: a flash and sparks, and for a momentum hit a shockwave and a heavier sound. */
    private static void feel(ServerPlayer p, LivingEntity t, float strength, boolean crit, float momentum) {
        if (!(t.level() instanceof ServerLevel l)) return;
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        AABB box = t.getBoundingBox().inflate(0.1);
        Vec3 at = box.clip(eye, eye.add(look.scale(8.0))).orElse(box.getCenter());
        Vec3 way = at.subtract(eye);
        way = way.lengthSqr() < 1.0E-6 ? look : way.normalize();
        float k = strength < 0.9F ? 0.35F * strength : crit ? 1.5F : 1.0F;
        RuneFx.hit(l, at, way, k + momentum);
        if (momentum >= 0.1F) RuneFx.momentum(l, at, way, momentum);
    }

    /** A knockback: a momentum hit's extra push goes on it, and a creature a player knocked back is watched for a slam. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void knockBack(LivingKnockBackEvent e) {
        LivingEntity t = e.getEntity();
        if (e.isCanceled() || t.level().isClientSide()) return;
        if (!(t.getLastHurtByMob() instanceof ServerPlayer p) || t.getLastHurtByMobTimestamp() != t.tickCount || p instanceof FakePlayer) return;
        float push = Fighters.takePush(p, t);
        if (push > 0.0F) e.setStrength(e.getStrength() + push);
        Slams.knocked(t, p);
    }

    /** A creature that reels can't strike (its mind has stopped, but some creatures strike on touch). */
    @SubscribeEvent
    public static void attackBy(LivingAttackEvent e) {
        if (e.getEntity().level().isClientSide()) return;
        if (e.getSource().getEntity() instanceof LivingEntity att && e.getSource().getDirectEntity() == att && Stagger.reeling(att)) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void livingTick(LivingEvent.LivingTickEvent e) {
        LivingEntity t = e.getEntity();
        if (t.level().isClientSide()) return;
        Slams.tick(t);
        Stagger.tick(t);
    }

    // ------------------------------------------------------------------------------------------------ the rest

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) Fighters.forget(p);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent e) {
        Fighters.clear();
        Stagger.clear();
        Slams.clear();
    }
}
