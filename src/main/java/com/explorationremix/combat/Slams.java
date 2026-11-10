package com.explorationremix.combat;

import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Wall slams: a creature a player knocked back (a hit, a rune's push) is watched for a moment; if its flight ends dead
 * against a wall fast enough, it is slammed: hurt for the speed it hit at (credited to the player), with a crack of
 * the wall's own sound, dust off the wall and a shake for those near. Players are never slammed.
 */
public final class Slams {
    /** Ticks a knocked creature is watched. */
    private static final int WATCH_TICKS = 15;

    private static final class W {
        ServerPlayer by;
        long until;
        Vec3 v = Vec3.ZERO;
    }

    private static final Map<LivingEntity, W> WATCH = new WeakHashMap<>();
    /** Set while a slam hurts, so its own knockback isn't taken for a new one. */
    private static boolean slamming;

    private Slams() {}

    /** {@code e} was just knocked back by {@code by}: watched for a slam. */
    public static void knocked(LivingEntity e, ServerPlayer by) {
        if (slamming || e instanceof Player || e.level().isClientSide || !e.isAlive()) return;
        W w = WATCH.computeIfAbsent(e, k -> new W());
        w.by = by;
        w.until = e.level().getGameTime() + WATCH_TICKS;
    }

    /**
     * The start of a watched creature's tick. Its last move has been made: if that move stopped it dead against a wall
     * (the wall took away the speed it had going in), it is slammed. Otherwise its speed is noted for the next.
     */
    public static void tick(LivingEntity e) {
        if (WATCH.isEmpty()) return;
        W w = WATCH.get(e);
        if (w == null) return;
        if (e.level().getGameTime() > w.until || !e.isAlive() || w.by.isRemoved() || w.by.level() != e.level()) {
            WATCH.remove(e);
            return;
        }
        Vec3 v = e.getDeltaMovement();
        if (e.horizontalCollision) {
            // the speed the wall took: what it had along each way the wall stopped dead
            double ix = Math.abs(w.v.x) > 0.02 && Math.abs(v.x) < 1.0E-4 ? w.v.x : 0.0;
            double iz = Math.abs(w.v.z) > 0.02 && Math.abs(v.z) < 1.0E-4 ? w.v.z : 0.0;
            double speed = Math.sqrt(ix * ix + iz * iz);
            if (speed >= Poise.SLAM_FROM) {
                WATCH.remove(e);
                slam(e, w.by, new Vec3(ix, 0.0, iz).scale(1.0 / speed), speed);
                return;
            }
        }
        w.v = v;
    }

    private static void slam(LivingEntity e, ServerPlayer by, Vec3 into, double speed) {
        if (!(e.level() instanceof ServerLevel l)) return;
        float damage = Poise.slamDamage(speed);
        Vec3 at = e.position().add(0, e.getBbHeight() * 0.5, 0).add(into.scale(e.getBbWidth() * 0.5 + 0.02));
        BlockPos wallPos = BlockPos.containing(at.add(into.scale(0.25)));
        BlockState wall = l.getBlockState(wallPos);
        SoundEvent crack = wall.isAir() ? net.minecraft.sounds.SoundEvents.STONE_BREAK : wall.getSoundType(l, wallPos, e).getBreakSound();
        Vec3 v0 = e.getDeltaMovement();
        int immunity = e.invulnerableTime;
        e.invulnerableTime = 0;
        slamming = true;
        try {
            e.hurt(new DamageSource(l.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(RuneRegistry.SLAM_DAMAGE), by, by), damage);
        } finally {
            slamming = false;
        }
        e.invulnerableTime = Math.max(immunity, 0);
        // no knockback from the slam itself: a little bounce back off the wall
        e.setDeltaMovement(v0.x - into.x * speed * 0.2, Math.max(v0.y, 0.05), v0.z - into.z * speed * 0.2);
        e.hurtMarked = true;
        RuneFx.slam(l, at, into.scale(-1), Poise.slamShare(speed), crack);
    }

    public static void clear() { WATCH.clear(); }
}
