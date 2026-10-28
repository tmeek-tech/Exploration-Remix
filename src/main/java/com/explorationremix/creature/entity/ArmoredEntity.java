package com.explorationremix.creature.entity;

import com.explorationremix.creature.goal.InvestigateNoiseGoal;
import com.explorationremix.creature.goal.RemixHuntGoal;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** The nest's wall: slow, heavy, and plated at the front. Get behind it, stagger it, or blast it. */
public class ArmoredEntity extends RemixCreature {
    public ArmoredEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setMaxUpStep(1.0F);
    }

    public static AttributeSupplier.Builder attributes() { return baseAttributes(40.0, 0.2, 7.0, 10.0, 1.0, 20.0); }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new RemixHuntGoal(this, 1.0, 1.25, false, 3.5, 15));
        this.goalSelector.addGoal(4, new InvestigateNoiseGoal(this, 0.9));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.5));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    /** Hits that land on the front plates mostly bounce off. Explosions and the mod's debris do not care. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && !source.is(DamageTypeTags.IS_EXPLOSION) && !source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            Vec3 from = source.getSourcePosition();
            if (from != null) {
                Vec3 toSource = from.subtract(position()).multiply(1, 0, 1);
                if (toSource.lengthSqr() > 0.01 && getLookAngle().multiply(1, 0, 1).normalize().dot(toSource.normalize()) > 0.45) {
                    amount *= 0.35F;
                    level().playSound(null, blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.6F, 0.6F);
                }
            }
        }
        return super.hurt(source, amount);
    }

    @Override protected SoundEvent getAmbientSound() { return SoundEvents.SPIDER_AMBIENT; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.SPIDER_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.SPIDER_DEATH; }
    @Override public float getVoicePitch() { return 0.6F; }
}
