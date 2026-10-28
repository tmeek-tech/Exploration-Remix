package com.explorationremix.creature.entity;

import com.explorationremix.creature.goal.InvestigateNoiseGoal;
import com.explorationremix.creature.goal.RemixHuntGoal;
import com.explorationremix.registry.EREntities;
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
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** The nest's artillery: keeps its distance and spits something that slows you down. */
public class SpitterEntity extends RemixCreature {
    public SpitterEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setMaxUpStep(1.0F);
    }

    public static AttributeSupplier.Builder attributes() { return baseAttributes(16.0, 0.27, 2.0, 1.0, 0.0, 26.0); }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SpitGoal());
        this.goalSelector.addGoal(3, new RemixHuntGoal(this, 1.0, 1.2, false, 2.5, 20) {
            @Override public boolean canUse() { return super.canUse() && mob.getTarget() != null && mob.distanceToSqr(mob.getTarget()) < 9; }
        });
        this.goalSelector.addGoal(4, new InvestigateNoiseGoal(this, 0.9));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    public void spitAt(LivingEntity target) {
        SpitProjectile spit = new SpitProjectile(EREntities.SPIT.get(), this, level());
        double dx = target.getX() - getX(), dy = target.getY(0.4) - spit.getY(), dz = target.getZ() - getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        spit.shoot(dx, dy + flat * 0.18, dz, 1.3F, 4.0F);
        level().playSound(null, blockPosition(), SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 1.0F, 0.7F);
        level().addFreshEntity(spit);
    }

    /** Hold a ring 5 to 11 blocks from the target, back away when it closes in, spit every two seconds or so. */
    private final class SpitGoal extends Goal {
        private int cooldown;

        SpitGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override public boolean canUse() {
            LivingEntity t = getTarget();
            return t != null && t.isAlive() && distanceToSqr(t) >= 9 && !RemixCreature.isRefuge(t.blockPosition());
        }

        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            LivingEntity t = getTarget();
            if (t == null) return;
            double d = distanceTo(t);
            getLookControl().setLookAt(t, 30.0F, 30.0F);
            if (d < 5.5) {
                if (tickCount % 10 == 0) {
                    Vec3 away = DefaultRandomPos.getPosAway(SpitterEntity.this, 8, 4, t.position());
                    if (away != null) getNavigation().moveTo(away.x, away.y, away.z, 1.1);
                }
            } else if (d > 11.0 || !getSensing().hasLineOfSight(t)) {
                if (tickCount % 10 == 0) getNavigation().moveTo(t, 1.0);
            } else getNavigation().stop();
            if (--cooldown <= 0 && getSensing().hasLineOfSight(t) && d <= 14.0) {
                spitAt(t);
                cooldown = 40 + random.nextInt(20);
            }
        }
    }

    @Override protected SoundEvent getAmbientSound() { return SoundEvents.SPIDER_AMBIENT; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.SPIDER_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.SPIDER_DEATH; }
    @Override public float getVoicePitch() { return 1.1F; }
}
