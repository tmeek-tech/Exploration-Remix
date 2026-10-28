package com.explorationremix.creature.entity;

import com.explorationremix.creature.NoiseBus;
import com.explorationremix.creature.goal.InvestigateNoiseGoal;
import com.explorationremix.creature.goal.RemixHuntGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * The Blue Reaches' hunter: blind, patient, and hanging from the ceiling. It creeps along the roof toward what it
 * hears and drops on whoever walks beneath it. After a fight it climbs back up and waits again.
 */
public class AmbusherEntity extends RemixCreature {
    public static final int GROUND = 0, HANGING = 1, DROPPING = 2, RETREATING = 3;
    private int groundTicks;
    private int retreatTicks;

    public AmbusherEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setMaxUpStep(1.0F);
    }

    public static AttributeSupplier.Builder attributes() { return baseAttributes(50.0, 0.3, 10.0, 4.0, 0.6, 24.0); }

    @Override public boolean isHunter() { return true; }
    @Override public boolean isBlind() { return true; }
    @Override public boolean isHanging() { return animState() == HANGING; }

    public void startHanging() { setAnimState(HANGING); setNoGravity(true); }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new RemixHuntGoal(this, 1.0, 1.3, false, 4.0, 15) {
            @Override public boolean canUse() { return animState() == GROUND && super.canUse(); }
            @Override public boolean canContinueToUse() { return animState() == GROUND && super.canContinueToUse(); }
            @Override protected void onAttack(LivingEntity t) {
                super.onAttack(t);
                t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1));
            }
        });
        this.goalSelector.addGoal(4, new InvestigateNoiseGoal(this, 1.0) {
            @Override public boolean canUse() { return animState() == GROUND && super.canUse(); }
        });
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public void hear(NoiseBus.Noise noise, double effective) {
        if (animState() == HANGING) {
            // Hanging, it only remembers where the sound came from and crawls that way.
            if (noise.source() instanceof Player p && distanceToSqr(p) < 49 && p.getY() < getY() && canTargetEntity(p)) drop(p);
            else super.hear(noise, effective);
            setTarget(null);
            return;
        }
        super.hear(noise, effective);
    }

    private boolean ceilingAbove(double x, double y, double z) {
        BlockPos above = BlockPos.containing(x, y + getBbHeight() + 0.2, z);
        return level().getBlockState(above).isFaceSturdy(level(), above, net.minecraft.core.Direction.DOWN);
    }

    private boolean spaceAt(double x, double y, double z) {
        return level().noCollision(this, getType().getDimensions().makeBoundingBox(x, y, z));
    }

    private void drop(LivingEntity target) {
        setAnimState(DROPPING);
        setNoGravity(false);
        setTarget(target);
        level().playSound(null, blockPosition(), SoundEvents.WARDEN_AGITATED, SoundSource.HOSTILE, 1.2F, 0.6F);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        switch (animState()) {
            case HANGING -> {
                setDeltaMovement(Vec3.ZERO);
                if (!ceilingAbove(getX(), getY(), getZ())) { setAnimState(GROUND); setNoGravity(false); return; }
                if (tickCount % 10 == 0) {
                    Player p = level().getNearestPlayer(getX(), getY(), getZ(), 7.0, e -> e instanceof Player pl && canTargetEntity(pl) && pl.getY() < getY() - 0.5 && !isRefuge(pl.blockPosition()));
                    if (p != null) { drop(p); return; }
                }
                Vec3 goal = noisePos();
                if (goal != null && hasFreshNoise()) {
                    Vec3 dir = goal.subtract(position()).multiply(1, 0, 1);
                    if (dir.lengthSqr() > 1.0) {
                        dir = dir.normalize().scale(0.1);
                        double nx = getX() + dir.x, nz = getZ() + dir.z;
                        if (ceilingAbove(nx, getY(), nz) && spaceAt(nx, getY(), nz)) setPos(nx, getY(), nz);
                        else {
                            // the roof ends: try one step up or down along it
                            if (ceilingAbove(nx, getY() + 1, nz) && spaceAt(nx, getY() + 1, nz)) setPos(nx, getY() + 1, nz);
                            else if (ceilingAbove(nx, getY() - 1, nz) && spaceAt(nx, getY() - 1, nz)) setPos(nx, getY() - 1, nz);
                            else consumeNoise();
                        }
                        setYRot((float) (Math.atan2(dir.z, dir.x) * 180 / Math.PI) - 90);
                        setYBodyRot(getYRot());
                    } else consumeNoise();
                }
            }
            case DROPPING -> {
                if (onGround() || isInWater()) {
                    setAnimState(GROUND);
                    groundTicks = 0;
                    level().playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.5F, 1.4F);
                    if (level() instanceof ServerLevel sl) {
                        NoiseBus.emit(sl, position(), 30, this);
                        for (LivingEntity le : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(2.5), e -> e != this && canTargetEntity(e))) {
                            le.hurt(damageSources().mobAttack(this), 6.0F);
                            le.push((le.getX() - getX()) * 0.4, 0.3, (le.getZ() - getZ()) * 0.4);
                        }
                    }
                }
            }
            case GROUND -> {
                groundTicks++;
                boolean done = getTarget() == null ? groundTicks > 100 : groundTicks > 400;
                if (done) {
                    setAnimState(RETREATING);
                    retreatTicks = 0;
                    setTarget(null);
                    getNavigation().stop();
                    setNoGravity(true);
                }
            }
            case RETREATING -> {
                retreatTicks++;
                setDeltaMovement(Vec3.ZERO);
                double ny = getY() + 0.25;
                if (spaceAt(getX(), ny, getZ())) setPos(getX(), ny, getZ());
                if (ceilingAbove(getX(), getY(), getZ())) { setAnimState(HANGING); return; }
                if (retreatTicks > 160) { setAnimState(GROUND); setNoGravity(false); groundTicks = 0; }
            }
            default -> {}
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }

    @Override
    public boolean canBeCollidedWith() { return false; }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean r = super.hurt(source, amount);
        if (r && animState() == HANGING && !level().isClientSide) {
            Entity e = source.getEntity();
            if (e instanceof LivingEntity le && canTargetEntity(le)) drop(le); else { setAnimState(GROUND); setNoGravity(false); }
        }
        return r;
    }

    @Override protected SoundEvent getAmbientSound() { return animState() == HANGING ? null : SoundEvents.WARDEN_AMBIENT; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.WARDEN_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.WARDEN_DEATH; }
    @Override public float getVoicePitch() { return 1.3F; }
    @Override protected float getSoundVolume() { return 0.7F; }
    @Nullable @Override public LivingEntity getTarget() { return super.getTarget(); }
}
