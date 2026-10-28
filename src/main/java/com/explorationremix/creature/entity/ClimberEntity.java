package com.explorationremix.creature.entity;

import com.explorationremix.config.ERConfig;
import com.explorationremix.creature.goal.InvestigateNoiseGoal;
import com.explorationremix.creature.goal.RemixHuntGoal;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * The Lantern Deeps' hunter: long-limbed, patient, and able to climb any wall. It creeps while you are not looking,
 * freezes when you turn, and lunges when it is close enough.
 */
public class ClimberEntity extends RemixCreature {
    private static final EntityDataAccessor<Boolean> CLIMBING = SynchedEntityData.defineId(ClimberEntity.class, EntityDataSerializers.BOOLEAN);

    public ClimberEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setMaxUpStep(1.0F);
    }

    public static AttributeSupplier.Builder attributes() { return baseAttributes(30.0, 0.3, 6.0, 2.0, 0.2, 32.0); }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(CLIMBING, false);
    }

    @Override
    protected PathNavigation createNavigation(Level level) { return new WallClimberNavigation(this, level); }

    @Override
    public boolean isHunter() { return true; }

    public boolean isClimbing() { return this.entityData.get(CLIMBING); }

    @Override
    public boolean onClimbable() { return isClimbing(); }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new RemixHuntGoal(this, 1.0, 1.45, true, 5.0, 12));
        this.goalSelector.addGoal(4, new InvestigateNoiseGoal(this, 1.0));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            this.entityData.set(CLIMBING, this.horizontalCollision);
            if (tickCount == 1) getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(ERConfig.INSTANCE.hunterSightRange.get());
        }
    }

    @Override protected SoundEvent getAmbientSound() { return random.nextFloat() < 0.3F ? SoundEvents.SPIDER_AMBIENT : null; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.SPIDER_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.SPIDER_DEATH; }
    @Override public float getVoicePitch() { return 0.65F; }
}
