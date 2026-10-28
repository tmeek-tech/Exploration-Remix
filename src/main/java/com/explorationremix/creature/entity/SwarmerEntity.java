package com.explorationremix.creature.entity;

import com.explorationremix.creature.goal.InvestigateNoiseGoal;
import com.explorationremix.creature.goal.RemixHuntGoal;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/** The nest's numbers: small, fast, fragile, and never alone. They rush without hesitation. */
public class SwarmerEntity extends RemixCreature {
    public SwarmerEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setMaxUpStep(1.0F);
    }

    public static AttributeSupplier.Builder attributes() { return baseAttributes(10.0, 0.34, 3.0, 0.0, 0.0, 24.0); }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new RemixHuntGoal(this, 1.0, 1.5, false, 4.0, 8));
        this.goalSelector.addGoal(4, new InvestigateNoiseGoal(this, 1.0));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override protected SoundEvent getAmbientSound() { return SoundEvents.SPIDER_AMBIENT; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.SPIDER_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.SPIDER_DEATH; }
    @Override public float getVoicePitch() { return 1.4F + random.nextFloat() * 0.2F; }
    @Override protected float getSoundVolume() { return 0.6F; }
}
