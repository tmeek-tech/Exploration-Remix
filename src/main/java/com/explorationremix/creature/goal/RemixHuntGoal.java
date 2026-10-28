package com.explorationremix.creature.goal;

import com.explorationremix.creature.entity.RemixCreature;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * A melee hunt with an animal's rhythm: creep closer while unseen, freeze and watch when the prey looks back,
 * lunge when close, then recover. Nest creatures skip the hesitation and simply rush.
 */
public class RemixHuntGoal extends Goal {
    protected enum Phase { STALK, HESITATE, LUNGE, RECOVER }

    protected final RemixCreature mob;
    private final double stalkSpeed, lungeSpeed;
    private final boolean hesitates;
    private final double lungeRange;
    private final int recoverTicks;
    protected Phase phase = Phase.STALK;
    private int phaseTicks;
    private int lungeTicks;

    public RemixHuntGoal(RemixCreature mob, double stalkSpeed, double lungeSpeed, boolean hesitates, double lungeRange, int recoverTicks) {
        this.mob = mob;
        this.stalkSpeed = stalkSpeed;
        this.lungeSpeed = lungeSpeed;
        this.hesitates = hesitates;
        this.lungeRange = lungeRange;
        this.recoverTicks = recoverTicks;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        return t != null && t.isAlive() && !RemixCreature.isRefuge(t.blockPosition());
    }

    @Override
    public boolean canContinueToUse() { return canUse(); }

    @Override
    public void start() { phase = Phase.STALK; phaseTicks = 0; lungeTicks = 0; }

    @Override
    public void stop() { mob.getNavigation().stop(); phase = Phase.STALK; }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    protected boolean preyLooksAtMob(LivingEntity t) {
        Vec3 toMob = mob.position().subtract(t.position());
        if (toMob.lengthSqr() < 0.01) return true;
        return t.getViewVector(1.0F).dot(toMob.normalize()) > 0.6 && t.hasLineOfSight(mob);
    }

    @Override
    public void tick() {
        LivingEntity t = mob.getTarget();
        if (t == null) return;
        double dist = mob.distanceTo(t);
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
        switch (phase) {
            case STALK -> {
                boolean seen = hesitates && preyLooksAtMob(t);
                if (mob.tickCount % 10 == 0 || mob.getNavigation().isDone()) mob.getNavigation().moveTo(t, seen ? stalkSpeed * 0.7 : stalkSpeed);
                if (dist < lungeRange) { phase = Phase.LUNGE; lungeTicks = 0; }
                else if (seen && dist > lungeRange + 2 && mob.getRandom().nextFloat() < 0.04F) {
                    phase = Phase.HESITATE; phaseTicks = 10 + mob.getRandom().nextInt(25); mob.getNavigation().stop();
                }
            }
            case HESITATE -> {
                mob.getNavigation().stop();
                if (--phaseTicks <= 0 || !preyLooksAtMob(t)) phase = Phase.STALK;
                if (dist < lungeRange * 0.8) { phase = Phase.LUNGE; lungeTicks = 0; }
            }
            case LUNGE -> {
                if (lungeTicks % 4 == 0) mob.getNavigation().moveTo(t, lungeSpeed);
                lungeTicks++;
                if (mob.distanceToSqr(t) <= reachSqr(t)) {
                    mob.swing(InteractionHand.MAIN_HAND);
                    onAttack(t);
                    phase = Phase.RECOVER; phaseTicks = recoverTicks;
                } else if (lungeTicks > 50) phase = Phase.STALK;
            }
            case RECOVER -> {
                mob.getNavigation().stop();
                if (--phaseTicks <= 0) phase = Phase.STALK;
            }
        }
    }

    protected void onAttack(LivingEntity t) { mob.doHurtTarget(t); }

    protected double reachSqr(LivingEntity t) {
        double w = mob.getBbWidth();
        return (w * 2.0F * w * 2.0F + t.getBbWidth());
    }
}
