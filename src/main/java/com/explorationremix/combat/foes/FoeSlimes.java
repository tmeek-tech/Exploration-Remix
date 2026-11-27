package com.explorationremix.combat.foes;

import com.explorationremix.combat.Stagger;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Slimes (not magma cubes) since 0.23.6: they hop after you as they always did, but touching one no longer hurts. Its
 * blow is the slam: within {@link FoeRules#SLAM_FROM} of its quarry it squashes down ({@link FoeRules#SQUASH}: the tell),
 * leaps, and comes down on it, hurting and throwing what stands within reach of where it lands, a big one further and
 * harder. The smallest slimes do no harm, as before. Run from each slime's tick (it has no goal of ours: vanilla's hop
 * steers it, and is held still while it slams, see the mixin on its move control).
 */
public final class FoeSlimes {
    private static final class S {
        FoeMove.Phase phase = FoeMove.Phase.IDLE;
        int left, cool = 20, air;
        /** Where the leap under way comes down (set as it sets off). */
        double aimX, aimY, aimZ;
        long lastTick = Long.MIN_VALUE;
        LivingEntity quarry;
    }

    private static final Map<Slime, S> MAP = new WeakHashMap<>();

    private FoeSlimes() {}

    /** Whether {@code s} is in its slam now: its own hops are held. */
    public static boolean holding(Slime s) {
        if (MAP.isEmpty()) return false;
        S st = MAP.get(s);
        return st != null && st.phase != FoeMove.Phase.IDLE;
    }

    /** Whether touching {@code s} is harmless: a slime that fights the new way (its slam is its only blow). */
    public static boolean harmlessTouch(Slime s) {
        return !s.level().isClientSide && s.getType() == net.minecraft.world.entity.EntityType.SLIME && Foes.enabled(FoeKind.SLIME);
    }

    static void tick(Slime s) {
        if (!Foes.enabled(FoeKind.SLIME) || s.isTiny() || s.isNoAi()) {
            if (!MAP.isEmpty()) MAP.remove(s);
            return;
        }
        S st = MAP.computeIfAbsent(s, k -> new S());
        long now = s.level().getGameTime();
        boolean resumed = st.lastTick != Long.MIN_VALUE && now - st.lastTick > 2L;
        st.lastTick = now;
        if (Stagger.reeling(s) || resumed || Foes.takeInterrupt(s)) {
            // reeling (a parry, a strong attack in its squash): the slam is gone
            if (st.phase != FoeMove.Phase.IDLE) stop(s, st);
            if (Stagger.reeling(s)) return;
        }
        LivingEntity t = s.getTarget();
        if (st.quarry != null && st.quarry != t) {
            FoeTurns.give(s, st.quarry);
            if (st.phase != FoeMove.Phase.IDLE) stop(s, st);
        }
        st.quarry = t;
        switch (st.phase) {
            case IDLE -> {
                if (st.cool > 0) st.cool--;
                if (st.cool > 0 || !Foes.fair(t) || !s.onGround() || s.isInWater()) return;
                if (s.distanceToSqr(t) > FoeRules.SLAM_FROM * FoeRules.SLAM_FROM || !s.hasLineOfSight(t)) return;
                if (Math.abs(t.getY() - s.getY()) > 2.5 || !FoeTurns.take(s, t, Foes.strikers())) return;
                int ticks = FoeRules.scaled(FoeRules.SQUASH, Foes.windupScale());
                to(s, st, FoeMove.Phase.WINDUP, ticks);
                Foes.tell(s, FoeMove.SQUASH, ticks);
            }
            case WINDUP -> {
                if (!Foes.fair(t)) {
                    stop(s, st);
                    return;
                }
                FoeTurns.take(s, t, Foes.strikers());
                face(s, t);
                Vec3 dm = s.getDeltaMovement();
                s.setDeltaMovement(dm.x * 0.5, dm.y, dm.z * 0.5);
                if (--st.left <= 0) leap(s, st, t);
            }
            case STRIKE -> {
                if (++st.air == 1) aimAgain(s, st);
                boolean down = st.air > 2 && (s.onGround() || s.isInWater());
                if (down || st.air > 40) {
                    if (down) slam(s);
                    to(s, st, FoeMove.Phase.RECOVER, 10);
                }
            }
            case RECOVER -> {
                if (--st.left <= 0) {
                    if (t != null) FoeTurns.give(s, t);
                    st.cool = FoeRules.SLAM_COOL_MIN + s.getRandom().nextInt(FoeRules.SLAM_COOL_MAX - FoeRules.SLAM_COOL_MIN + 1);
                    to(s, st, FoeMove.Phase.IDLE, 0);
                }
            }
        }
    }

    private static void to(Slime s, S st, FoeMove.Phase p, int ticks) {
        st.phase = p;
        st.left = ticks;
        Foes.phase(s, FoeMove.SQUASH, p, ticks);
    }

    private static void stop(Slime s, S st) {
        if (st.quarry != null) FoeTurns.give(s, st.quarry);
        st.cool = Math.max(st.cool, 15);
        to(s, st, FoeMove.Phase.IDLE, 0);
    }

    private static void face(Slime s, LivingEntity t) {
        float yaw = (float) (Mth.atan2(t.getZ() - s.getZ(), t.getX() - s.getX()) * Mth.RAD_TO_DEG) - 90.0F;
        s.setYRot(yaw);
        s.setYHeadRot(yaw);
        s.setYBodyRot(yaw);
    }

    /** Up and at where its quarry is now: high, and fast enough along to come down on it. */
    private static void leap(Slime s, S st, LivingEntity t) {
        double dx = t.getX() - s.getX(), dz = t.getZ() - s.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        Vec3 dir = flat < 1.0E-4 ? Vec3.directionFromRotation(0.0F, s.getYRot()) : new Vec3(dx / flat, 0.0, dz / flat);
        double lift = FoeRules.slamLift(s.getSize());
        double to = Math.max(0.0, flat - 0.2);
        double along = FoeRules.leapSpeed(to, t.getY() - s.getY(), lift, Foes.firstDrag(s));
        s.setDeltaMovement(dir.x * along, lift, dir.z * along);
        s.hasImpulse = true;
        st.aimX = s.getX() + dir.x * to;
        st.aimY = t.getY();
        st.aimZ = s.getZ() + dir.z * to;
        st.air = 0;
        to(s, st, FoeMove.Phase.STRIKE, 40);
        s.playSound(s.getSize() > 1 ? SoundEvents.SLIME_JUMP : SoundEvents.SLIME_JUMP_SMALL, 1.0F, 0.75F + s.getRandom().nextFloat() * 0.1F);
    }

    /**
     * Its first tick off the ground: set again for where it was to come down, from where it is now (the ground may have
     * held it more or less than reckoned). Not at where its quarry has gone since: a slam seen coming can be stepped out of.
     */
    private static void aimAgain(Slime s, S st) {
        Vec3 dm = s.getDeltaMovement();
        double dx = st.aimX - s.getX(), dz = st.aimZ - s.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.05 || dx * dm.x + dz * dm.z < 0.0) return;
        double along = FoeRules.leapSpeed(flat, st.aimY - s.getY(), dm.y, FoeRules.AIR_DRAG);
        s.setDeltaMovement(dx / flat * along, dm.y, dz / flat * along);
    }

    /** It comes down: what stands within reach is hurt and thrown off, the ground shakes. */
    private static void slam(Slime s) {
        if (!(s.level() instanceof ServerLevel l)) return;
        int size = s.getSize();
        double reach = FoeRules.slamReach(size, s.getBbWidth());
        float damage = (float) s.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double push = FoeRules.slamPush(size);
        LivingEntity target = s.getTarget();
        boolean any = false;
        for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, s.getBoundingBox().inflate(reach, 1.0, reach))) {
            // players, and whatever else it was after (an iron golem); never its own kind
            if (e == s || !(e instanceof Player || e == target) || !Foes.fair(e)) continue;
            double dx = e.getX() - s.getX(), dz = e.getZ() - s.getZ();
            if (dx * dx + dz * dz > reach * reach || e.getY() > s.getY() + 1.5 || e.getY() + e.getBbHeight() < s.getY() - 0.5) continue;
            if (!s.hasLineOfSight(e)) continue;
            Vec3 was = e.getDeltaMovement();
            if (!e.hurt(s.damageSources().mobAttack(s), damage)) continue;
            any = true;
            double flat = Math.sqrt(dx * dx + dz * dz);
            Vec3 away = flat < 1.0E-3 ? Vec3.directionFromRotation(0.0F, s.getYRot()) : new Vec3(dx / flat, 0.0, dz / flat);
            Vec3 v = new Vec3(away.x * push, FoeRules.SLAM_LIFT_PUSH, away.z * push);
            if (e instanceof ServerPlayer sp && sp.connection != null) {
                double share = sp.onGround() ? 1.0 : 0.65;
                sp.setDeltaMovement(was);
                sp.hurtMarked = false;
                sp.connection.send(new ClientboundSetEntityMotionPacket(sp.getId(), new Vec3(v.x * share, v.y, v.z * share)));
            } else {
                e.setDeltaMovement(v);
                e.hurtMarked = true;
            }
        }
        if (any) s.playSound(SoundEvents.SLIME_ATTACK, 1.0F, (s.getRandom().nextFloat() - s.getRandom().nextFloat()) * 0.2F + 1.0F);
        BlockPos below = BlockPos.containing(s.getX(), s.getY() - 0.2, s.getZ());
        BlockState ground = l.getBlockState(below);
        SoundEvent crack = ground.isAir() ? SoundEvents.SLIME_SQUISH : ground.getSoundType(l, below, s).getBreakSound();
        RuneFx.smash(l, s.position().add(0.0, 0.05, 0.0), Vec3.directionFromRotation(0.0F, s.getYRot()), 0.3F + 0.15F * Math.min(4, size),
                crack, Block.getId(ground), s.getId());
    }

    static void clear() { MAP.clear(); }
}
