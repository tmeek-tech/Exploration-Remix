package com.explorationremix.creature.entity;

import com.explorationremix.creature.NoiseBus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Cave flockers roost in groups under the ceiling. Startle one and the whole flock bursts into the air, shrieking,
 * and every hunter within earshot comes to look. They settle again after a while. They never bite.
 */
public class FlockerEntity extends PathfinderMob implements CreatureAnim {
    private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(FlockerEntity.class, EntityDataSerializers.BOOLEAN);
    private static int alarmDepth = 0;
    private int flyTicks;
    private int flyFor;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 fleeFrom;

    public FlockerEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setNoGravity(true);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 3.0).add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.FOLLOW_RANGE, 16.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(FLYING, false);
    }

    @Override protected void registerGoals() {}
    @Override public boolean isFlying() { return this.entityData.get(FLYING); }
    @Override public boolean isHanging() { return !isFlying(); }
    @Override public boolean removeWhenFarAway(double d) { return false; }
    @Override public boolean requiresCustomPersistence() { return true; }
    @Override public boolean shouldDropExperience() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override protected void doPush(net.minecraft.world.entity.Entity e) {}
    @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }

    private boolean ceilingAbove() {
        BlockPos above = BlockPos.containing(getX(), getY() + getBbHeight() + 0.1, getZ());
        return level().getBlockState(above).isFaceSturdy(level(), above, Direction.DOWN);
    }

    /** Something startled the flock. */
    public void alarm(Vec3 from, boolean byPlayer) {
        if (level().isClientSide) return;
        if (isFlying()) { fleeFrom = from; return; }
        this.entityData.set(FLYING, true);
        flyTicks = 0;
        flyFor = 300 + random.nextInt(400);
        fleeFrom = from;
        velocity = position().subtract(from).multiply(1, 0.3, 1).normalize().scale(0.25).add(0, -0.1, 0);
        level().playSound(null, blockPosition(), SoundEvents.BAT_TAKEOFF, SoundSource.NEUTRAL, 0.8F, 0.9F + random.nextFloat() * 0.3F);
        if (level() instanceof ServerLevel sl && alarmDepth == 0) {
            alarmDepth++;
            try {
                NoiseBus.emit(sl, position(), 40, byPlayer ? null : this);
                for (FlockerEntity f : sl.getEntitiesOfClass(FlockerEntity.class, getBoundingBox().inflate(7))) if (f != this && !f.isFlying()) f.alarm(from, byPlayer);
            } finally { alarmDepth--; }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean r = super.hurt(source, amount);
        if (r && !level().isClientSide) alarm(source.getSourcePosition() != null ? source.getSourcePosition() : position(), source.getEntity() instanceof Player);
        return r;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (!isFlying()) {
            setDeltaMovement(Vec3.ZERO);
            if (!ceilingAbove()) { alarm(position().add(0, -1, 0), false); return; }
            if (tickCount % 20 == 0) {
                Player p = level().getNearestPlayer(getX(), getY(), getZ(), 5.0, e -> e instanceof Player pl && !pl.isSpectator());
                if (p != null) alarm(p.position(), true);
            }
            return;
        }
        flyTicks++;
        // boids: stay with the flock, keep a little apart, share a heading, avoid walls, flee the scare
        List<FlockerEntity> mates = level().getEntitiesOfClass(FlockerEntity.class, getBoundingBox().inflate(6), f -> f != this && f.isFlying());
        Vec3 cohesion = Vec3.ZERO, separation = Vec3.ZERO, alignment = Vec3.ZERO;
        for (FlockerEntity m : mates) {
            cohesion = cohesion.add(m.position());
            alignment = alignment.add(m.velocity);
            Vec3 away = position().subtract(m.position());
            double d = away.length();
            if (d < 1.5 && d > 0.01) separation = separation.add(away.scale(1 / (d * d)));
        }
        Vec3 steer = Vec3.ZERO;
        if (!mates.isEmpty()) {
            cohesion = cohesion.scale(1.0 / mates.size()).subtract(position());
            alignment = alignment.scale(1.0 / mates.size());
            steer = steer.add(cohesion.scale(0.015)).add(separation.scale(0.03)).add(alignment.scale(0.08));
        }
        if (fleeFrom != null && flyTicks < 120) {
            Vec3 away = position().subtract(fleeFrom);
            if (away.lengthSqr() < 100) steer = steer.add(away.normalize().scale(0.05));
        }
        // walls: probe a few blocks ahead
        Vec3 ahead = velocity.lengthSqr() > 0.0001 ? velocity.normalize() : new Vec3(0, 0, 1);
        for (int i = 1; i <= 3; i++) {
            BlockPos probe = BlockPos.containing(position().add(ahead.scale(i)));
            if (!level().getBlockState(probe).getCollisionShape(level(), probe).isEmpty()) {
                steer = steer.add(new Vec3(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5).scale(0.2)).subtract(ahead.scale(0.08 * (4 - i)));
                break;
            }
        }
        // a little lift, a little wander
        steer = steer.add(0, 0.006, 0).add(new Vec3(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5).scale(0.03));
        velocity = velocity.add(steer);
        if (velocity.length() > 0.32) velocity = velocity.normalize().scale(0.32);
        if (velocity.length() < 0.12) velocity = velocity.lengthSqr() < 1e-6 ? new Vec3(0.1, 0.05, 0.1) : velocity.normalize().scale(0.12);
        if (flyTicks > flyFor) {
            // look for a roof to settle under
            for (int i = 1; i <= 8; i++) {
                BlockPos up = BlockPos.containing(getX(), getY() + i, getZ());
                if (!level().getBlockState(up).getCollisionShape(level(), up).isEmpty()) {
                    velocity = new Vec3(velocity.x * 0.5, 0.18, velocity.z * 0.5);
                    break;
                }
            }
            if (ceilingAbove()) {
                this.entityData.set(FLYING, false);
                velocity = Vec3.ZERO;
                setDeltaMovement(Vec3.ZERO);
                return;
            }
        }
        move(MoverType.SELF, velocity);
        if (horizontalCollision || verticalCollision) velocity = velocity.multiply(horizontalCollision ? -0.3 : 1, verticalCollision ? -0.3 : 1, horizontalCollision ? -0.3 : 1);
        setYRot((float) (Math.atan2(velocity.z, velocity.x) * 180 / Math.PI) - 90);
        setYBodyRot(getYRot());
        if (flyTicks % 25 == 0 && level() instanceof ServerLevel sl) {
            NoiseBus.emit(sl, position(), 14, this);
            if (random.nextFloat() < 0.5F) sl.playSound(null, blockPosition(), SoundEvents.BAT_AMBIENT, SoundSource.NEUTRAL, 0.5F, 1.2F);
        }
    }

    @Override protected SoundEvent getAmbientSound() { return isFlying() ? null : (random.nextFloat() < 0.1F ? SoundEvents.BAT_AMBIENT : null); }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.BAT_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.BAT_DEATH; }
    @Override protected float getSoundVolume() { return 0.4F; }
    @Override public float getVoicePitch() { return 1.3F; }
}
