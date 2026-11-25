package com.explorationremix.combat.guard;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.combat.Stagger;
import com.explorationremix.combat.swing.Weapons;
import com.explorationremix.creature.MossbackEntity;
import com.explorationremix.mixin.accessor.ProjectileAccessor;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.rune.cast.CastService;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A sword held up in guard (Taran 2026-11-25), on the server: the player's game says when it goes up (SwingNet), and it
 * is held up as any item in use is, until the button is let go. Blows from in front are taken on the blade
 * ({@link GuardRules}): a creature's own blow from within reach, or an arrow (one that pierces goes through, as it does
 * a shield). A blocked blow does a third of its damage, pushes half as hard and costs the sword a point of wear; a
 * parried one (just after the guard went up) does nothing, throws the creature back a step and leaves it reeling, and
 * knocks rune energy loose as a full hit would; a parried arrow flies back at its shooter. A boss's blows can't be
 * taken on a blade (the Hollow Choir's slams and tolls reach from afar), nor a Mossback's shaking while it holds you.
 * A shield in the other hand still blocks everything, the vanilla way.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class Guard {
    /** How long a sword can stay up in one go (ticks): an hour, so in practice for as long as the button is held. */
    static final int HELD = 72000;

    private static final Map<UUID, GuardRules.State> MAP = new HashMap<>();
    /** A blow being taken on a blade now: whose, its source (its damage is cut once), and the tick (its push is halved). */
    @Nullable private static ServerPlayer blocking;
    @Nullable private static DamageSource blockingSource;
    private static long blockingTick = Long.MIN_VALUE;
    /** A blocked arrow's third is being dealt: not taken on the blade again. */
    private static boolean dealing;

    private Guard() {}

    /** Whether {@code p} holds a sword up in guard now (their use of the sword in the main hand). */
    public static boolean guarding(Player p) {
        return p.isUsingItem() && p.getUsedItemHand() == InteractionHand.MAIN_HAND && Weapons.guards(p.getUseItem());
    }

    private static GuardRules.State state(ServerPlayer p) { return MAP.computeIfAbsent(p.getUUID(), u -> new GuardRules.State()); }

    /** The player's game: their sword goes up in guard. */
    public static void raise(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || p.isUsingItem() || !Weapons.guards(p.getMainHandItem())) return;
        p.startUsingItem(InteractionHand.MAIN_HAND);
        if (!guarding(p)) return;
        GuardRules.raise(state(p), p.level().getGameTime());
        // for the players round (the player's own game played it as it went up)
        p.level().playSound(p, p.getX(), p.getEyeY() - 0.3, p.getZ(), ERSounds.GUARD_RAISE.get(), SoundSource.PLAYERS, 0.6F,
                0.95F + p.getRandom().nextFloat() * 0.1F);
    }

    /** A sword has no use of its own: put up in guard, it is in use until the button is let go (both sides). */
    @SubscribeEvent
    public static void useStart(LivingEntityUseItemEvent.Start e) {
        if (e.getDuration() <= 0 && e.getEntity() instanceof Player && Weapons.guards(e.getItem())) e.setDuration(HELD);
    }

    // ------------------------------------------------------------------------------------------------ blows

    /**
     * A blow is about to land on a player: if it comes at their raised sword, it is parried (none of it lands) or
     * blocked. After the handlers that cancel blows outright (a reeling creature can't strike).
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void attacked(LivingAttackEvent e) {
        if (dealing || !(e.getEntity() instanceof ServerPlayer p) || e.getAmount() <= 0.0F || !guarding(p)) return;
        DamageSource src = e.getSource();
        if (src.is(DamageTypeTags.BYPASSES_SHIELD) || src.is(DamageTypeTags.IS_EXPLOSION) || src.is(DamageTypes.THORNS)
                || src.is(DamageTypes.SONIC_BOOM)) return;
        Entity direct = src.getDirectEntity();
        if (direct instanceof AbstractArrow arrow) {
            // an arrow from in front glances off the blade (one parried never got here: it was turned back as it struck)
            if (arrow.getPierceLevel() > 0 || !arrowInFront(p, arrow)) return;
            e.setCanceled(true);
            blockArrow(p, src, e.getAmount(), arrow);
            return;
        }
        if (!(direct instanceof LivingEntity attacker) || src.getEntity() != attacker || src.is(DamageTypeTags.IS_PROJECTILE)) return;
        if (attacker.getType().is(Tags.EntityTypes.BOSSES) || (attacker instanceof MossbackEntity m && m.held() == p)) return;
        if (!near(p, attacker) || !inFront(p, attacker)) return;
        long now = p.level().getGameTime();
        if (GuardRules.parries(state(p), now)) {
            e.setCanceled(true);
            parry(p, attacker);
            return;
        }
        blocking = p;
        blockingSource = src;
        blockingTick = now;
        blockFx(p, attacker.getEyePosition(), e.getAmount());
    }

    /** A blocked blow does a third of its damage, and wears the blade. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void hurt(LivingHurtEvent e) {
        if (blocking == null || blockingSource == null || e.getEntity() != blocking || e.getSource() != blockingSource) return;
        e.setAmount(GuardRules.blocked(e.getAmount()));
        blockingSource = null;
        wear(blocking);
    }

    /** And pushes half as hard (the blow's own push, and its weapon's knockback). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void knockBack(LivingKnockBackEvent e) {
        if (blocking != null && e.getEntity() == blocking && blocking.level().getGameTime() == blockingTick) {
            e.setStrength(e.getStrength() * GuardRules.BLOCK_PUSH);
        }
    }

    /** An arrow strikes a player as their guard has just gone up: it is turned back at whoever loosed it. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void impact(ProjectileImpactEvent e) {
        if (!(e.getProjectile() instanceof AbstractArrow arrow) || arrow.level().isClientSide()) return;
        if (!(e.getRayTraceResult() instanceof EntityHitResult hit) || !(hit.getEntity() instanceof ServerPlayer p) || !guarding(p)) return;
        if (arrow.getPierceLevel() > 0 || !arrowInFront(p, arrow) || !GuardRules.parries(state(p), p.level().getGameTime())) return;
        e.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
        turnBack(p, arrow);
    }

    // ------------------------------------------------------------------------------------------------ what a guard does

    /** Where the blade is held: in front of the player's chest, towards where they look. */
    static Vec3 blade(ServerPlayer p) { return p.getEyePosition().add(p.getLookAngle().scale(0.65)).add(0.0, -0.35, 0.0); }

    private static boolean near(ServerPlayer p, LivingEntity attacker) {
        AABB box = attacker.getBoundingBox();
        Vec3 eye = p.getEyePosition(), c = p.position().add(0.0, p.getBbHeight() * 0.5, 0.0);
        double dx = Math.max(0.0, Math.max(box.minX - c.x, c.x - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - eye.y, Math.max(p.getY() - box.maxY, 0.0)));
        double dz = Math.max(0.0, Math.max(box.minZ - c.z, c.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz <= GuardRules.REACH * GuardRules.REACH;
    }

    /** In front: the look runs into it, or its middle is within the guard's angle of the look. */
    private static boolean inFront(ServerPlayer p, LivingEntity attacker) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        AABB box = attacker.getBoundingBox().inflate(0.2);
        if (box.contains(eye) || box.clip(eye, eye.add(look.scale(GuardRules.REACH + 2.0))).isPresent()) return true;
        Vec3 d = box.getCenter().subtract(eye);
        return GuardRules.inFront(look.x, look.y, look.z, d.x, d.y, d.z);
    }

    private static boolean arrowInFront(ServerPlayer p, AbstractArrow arrow) {
        Vec3 v = arrow.getDeltaMovement(), look = p.getLookAngle();
        if (v.lengthSqr() < 1.0E-6) return false;
        return GuardRules.inFront(look.x, look.y, look.z, -v.x, -v.y, -v.z);
    }

    private static void wear(ServerPlayer p) {
        p.getMainHandItem().hurtAndBreak(1, p, pl -> pl.broadcastBreakEvent(InteractionHand.MAIN_HAND));
    }

    /** A blow taken on the blade: a dull clash and sparks off it, seen and heard by everyone near. */
    private static void blockFx(ServerPlayer p, Vec3 from, float amount) {
        Vec3 at = blade(p);
        float k = Mth.clamp(amount / 8.0F, 0.25F, 1.0F);
        RuneFx.block(p.serverLevel(), at, from.subtract(at), k, p.getId());
        p.level().playSound(null, at.x, at.y, at.z, ERSounds.GUARD_BLOCK.get(), SoundSource.PLAYERS, 0.7F + 0.3F * k,
                0.9F + p.getRandom().nextFloat() * 0.2F);
    }

    /** An arrow glances off the blade (it falls away, as off a shield); a third of it still gets through. */
    private static void blockArrow(ServerPlayer p, DamageSource src, float amount, AbstractArrow arrow) {
        blockFx(p, arrow.position().subtract(arrow.getDeltaMovement().scale(4.0)), amount);
        wear(p);
        blocking = p;
        blockingSource = null;
        blockingTick = p.level().getGameTime();
        dealing = true;
        try {
            p.hurt(src, GuardRules.blocked(amount));
        } finally {
            dealing = false;
        }
    }

    /** A creature's blow parried: a bright clash, and it is thrown back a step, reeling, its energy knocked loose. */
    private static void parry(ServerPlayer p, LivingEntity attacker) {
        ServerLevel l = p.serverLevel();
        Vec3 at = blade(p);
        RuneFx.parry(l, at, attacker.getBoundingBox().getCenter().subtract(at), p.getId());
        l.playSound(null, at.x, at.y, at.z, ERSounds.GUARD_PARRY.get(), SoundSource.PLAYERS, 1.0F, 0.95F + p.getRandom().nextFloat() * 0.1F);
        attacker.knockback(GuardRules.PARRY_PUSH, p.getX() - attacker.getX(), p.getZ() - attacker.getZ());
        attacker.hurtMarked = true;
        // a player's game hears of a push a tick late otherwise, and with half of it gone
        if (attacker instanceof ServerPlayer other) other.connection.send(new ClientboundSetEntityMotionPacket(other));
        // reeling for 1.5 s, open to harder hits, a cave hunter too (as a strong attack's interrupt leaves it)
        Stagger.interrupt(attacker);
        CastService.parried(p, attacker);
    }

    /** A parried arrow flies back at whoever loosed it (or straight back the way it came), faster, as the player's own. */
    private static void turnBack(ServerPlayer p, AbstractArrow arrow) {
        Vec3 v = arrow.getDeltaMovement();
        Vec3 back = v.lengthSqr() < 1.0E-6 ? p.getLookAngle() : v.normalize().reverse();
        Vec3 from = p.getEyePosition().add(0.0, -0.3, 0.0).add(back.scale(0.9));
        double speed = Math.max(1.2, v.length()) * 1.1;
        Entity shooter = arrow.getOwner();
        Vec3 way = back;
        if (shooter != null && shooter != p && shooter.isAlive() && shooter.level() == p.level() && shooter.distanceToSqr(p) < 48.0 * 48.0) {
            Vec3 d = shooter.getBoundingBox().getCenter().subtract(from);
            double t = d.length() / speed;
            // an arrow falls as it flies: aimed above by as much as it will drop on the way
            way = d.add(0.0, 0.025 * t * t, 0.0).normalize();
        }
        arrow.setPos(from.x, from.y, from.z);
        arrow.setDeltaMovement(way.scale(speed));
        float yaw = (float) (Mth.atan2(way.x, way.z) * Mth.RAD_TO_DEG), pitch = (float) (Mth.atan2(way.y, way.horizontalDistance()) * Mth.RAD_TO_DEG);
        arrow.setYRot(yaw);
        arrow.setXRot(pitch);
        arrow.yRotO = yaw;
        arrow.xRotO = pitch;
        if (!(arrow instanceof ThrownTrident)) {
            // the player's arrow now (its hit is theirs), still picked up only as it could be before; it can't strike them on its way out
            AbstractArrow.Pickup pickup = arrow.pickup;
            arrow.setOwner(p);
            arrow.pickup = pickup;
            ((ProjectileAccessor) arrow).er$setLeftOwner(false);
        }
        arrow.setCritArrow(true);
        arrow.hasImpulse = true;
        Vec3 at = blade(p);
        RuneFx.parry(p.serverLevel(), at, way, p.getId());
        p.level().playSound(null, at.x, at.y, at.z, ERSounds.GUARD_PARRY.get(), SoundSource.PLAYERS, 1.0F, 1.05F + p.getRandom().nextFloat() * 0.1F);
        if (shooter instanceof LivingEntity le && shooter != p) CastService.parried(p, le);
    }

    // ------------------------------------------------------------------------------------------------ the rest

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        blocking = null;
        blockingSource = null;
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        MAP.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent e) {
        MAP.clear();
        blocking = null;
        blockingSource = null;
    }
}
