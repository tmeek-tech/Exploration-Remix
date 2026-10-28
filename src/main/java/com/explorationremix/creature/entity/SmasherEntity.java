package com.explorationremix.creature.entity;

import com.explorationremix.combat.CombatTracker;
import com.explorationremix.creature.NoiseBus;
import com.explorationremix.creature.goal.InvestigateNoiseGoal;
import com.explorationremix.creature.goal.RemixHuntGoal;
import com.explorationremix.network.CombatFxPacket;
import com.explorationremix.network.ERNetwork;
import com.explorationremix.registry.ERTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.EnumSet;

/**
 * The Basalt Galleries' hunter: huge, slow, and happy to charge. A charge that hits throws you across the cave;
 * one that meets a wall breaks what it can and leaves the smasher reeling, open to a counter.
 */
public class SmasherEntity extends RemixCreature {
    public static final int NORMAL = 0, WINDUP = 1, CHARGING = 2, STUNNED = 3;
    private Vec3 chargeDir = Vec3.ZERO;
    private int stateTicks;
    private int chargeCooldown = 60;

    public SmasherEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setMaxUpStep(1.5F);
    }

    public static AttributeSupplier.Builder attributes() { return baseAttributes(80.0, 0.24, 12.0, 6.0, 0.9, 32.0); }

    @Override public boolean isHunter() { return true; }
    @Override public boolean isCharging() { return animState() == CHARGING || animState() == WINDUP; }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new ChargeGoal());
        this.goalSelector.addGoal(2, new RemixHuntGoal(this, 1.0, 1.2, false, 4.5, 20) {
            @Override public boolean canUse() { return animState() == NORMAL && super.canUse(); }
            @Override public boolean canContinueToUse() { return animState() == NORMAL && super.canContinueToUse(); }
            @Override protected void onAttack(LivingEntity t) {
                super.onAttack(t);
                t.push((t.getX() - getX()) * 0.5, 0.35, (t.getZ() - getZ()) * 0.5);
            }
        });
        this.goalSelector.addGoal(4, new InvestigateNoiseGoal(this, 0.9) {
            @Override public boolean canUse() { return animState() == NORMAL && super.canUse(); }
        });
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    private void shake(float strength, double radius) {
        if (!(level() instanceof ServerLevel sl)) return;
        for (ServerPlayer p : sl.getEntitiesOfClass(ServerPlayer.class, getBoundingBox().inflate(radius))) {
            Vec3 d = p.position().subtract(position()).normalize();
            ERNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new CombatFxPacket(0, strength, (float) d.x, (float) d.z));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (chargeCooldown > 0) chargeCooldown--;
        switch (animState()) {
            case NORMAL -> {
                if (tickCount % 10 == 0 && getDeltaMovement().horizontalDistanceSqr() > 0.004 && onGround()) {
                    shake(0.35F, 10);
                    if (level() instanceof ServerLevel sl) NoiseBus.emit(sl, position(), 8, this);
                }
            }
            case WINDUP -> {
                stateTicks++;
                getNavigation().stop();
                LivingEntity t = getTarget();
                if (t != null) getLookControl().setLookAt(t, 30, 30);
                if (stateTicks == 1) level().playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.5F, 0.6F);
                if (stateTicks >= 20) {
                    if (t == null) { setAnimState(NORMAL); return; }
                    chargeDir = t.position().subtract(position()).multiply(1, 0, 1).normalize();
                    setAnimState(CHARGING);
                    stateTicks = 0;
                }
            }
            case CHARGING -> {
                stateTicks++;
                LivingEntity t = getTarget();
                if (t != null) {
                    Vec3 want = t.position().subtract(position()).multiply(1, 0, 1).normalize();
                    chargeDir = chargeDir.add(want.subtract(chargeDir).scale(0.06)).normalize();
                }
                setDeltaMovement(chargeDir.x * 0.78, getDeltaMovement().y, chargeDir.z * 0.78);
                setYRot((float) (Math.atan2(chargeDir.z, chargeDir.x) * 180 / Math.PI) - 90);
                setYBodyRot(getYRot());
                if (tickCount % 4 == 0) shake(0.5F, 12);
                // run into something living
                if (level() instanceof ServerLevel sl) {
                    for (LivingEntity le : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(0.6), e -> e != this && canTargetEntity(e))) {
                        le.hurt(damageSources().mobAttack(this), 14.0F);
                        le.push(chargeDir.x * 1.8, 0.5, chargeDir.z * 1.8);
                        NoiseBus.emit(sl, position(), 30, this);
                        sl.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.6F, 0.8F);
                        setAnimState(NORMAL);
                        chargeCooldown = 120;
                        return;
                    }
                }
                if (horizontalCollision) {
                    crash();
                    return;
                }
                if (stateTicks > 55) { setAnimState(NORMAL); chargeCooldown = 100; }
            }
            case STUNNED -> {
                stateTicks++;
                getNavigation().stop();
                setDeltaMovement(getDeltaMovement().multiply(0.2, 1, 0.2));
                if (stateTicks > 60) { setAnimState(NORMAL); chargeCooldown = 100; }
            }
            default -> {}
        }
    }

    /** The wall won: break what breaks, reel for three seconds. */
    private void crash() {
        if (level() instanceof ServerLevel sl) {
            BlockPos base = blockPosition();
            Vec3 front = position().add(chargeDir.scale(getBbWidth() * 0.5 + 0.8));
            int cx = (int) Math.floor(front.x), cz = (int) Math.floor(front.z);
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = new BlockPos(cx + dx, base.getY() + dy, cz + dz);
                BlockState s = sl.getBlockState(p);
                float hardness = s.getDestroySpeed(sl, p);
                if (s.isAir() || hardness < 0 || hardness > 1.5F || s.is(ERTags.LAYER_STONE)) continue;
                sl.destroyBlock(p, true, this);
            }
            sl.playSound(null, base, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.0F, 0.5F);
            NoiseBus.emit(sl, position(), 40, this);
            shake(1.0F, 16);
        }
        setAnimState(STUNNED);
        stateTicks = 0;
        CombatTracker.State s = CombatTracker.of(this);
        s.staggerTicks = 60;
    }

    /** Charge when the prey is in the open, a few blocks off, and the last charge is forgotten. */
    private final class ChargeGoal extends Goal {
        ChargeGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override public boolean canUse() {
            LivingEntity t = getTarget();
            if (t == null || animState() != NORMAL || chargeCooldown > 0 || !onGround()) return false;
            double d = distanceTo(t);
            return d > 6 && d < 22 && getSensing().hasLineOfSight(t) && !isRefuge(t.blockPosition()) && Math.abs(t.getY() - getY()) < 3;
        }

        @Override public boolean canContinueToUse() { return animState() == WINDUP || animState() == CHARGING; }
        @Override public void start() { setAnimState(WINDUP); stateTicks = 0; getNavigation().stop(); }
        @Override public void stop() { if (animState() == WINDUP || animState() == CHARGING) setAnimState(NORMAL); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (animState() == CHARGING && !level().isClientSide) amount *= 0.5F;
        return super.hurt(source, amount);
    }

    @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }
    @Override protected SoundEvent getAmbientSound() { return SoundEvents.RAVAGER_AMBIENT; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.RAVAGER_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.RAVAGER_DEATH; }
    @Override public float getVoicePitch() { return 0.7F; }
    @Override protected float getSoundVolume() { return 1.3F; }
    @Override public AABB getBoundingBoxForCulling() { return super.getBoundingBoxForCulling().inflate(1.0); }
}
