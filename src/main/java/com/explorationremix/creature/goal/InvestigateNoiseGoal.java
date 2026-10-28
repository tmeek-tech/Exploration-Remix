package com.explorationremix.creature.goal;

import com.explorationremix.creature.entity.RemixCreature;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** With no prey in sight, walk toward the last noise heard and linger there a moment. Lures hold the creature longer. */
public class InvestigateNoiseGoal extends Goal {
    private final RemixCreature mob;
    private final double speed;
    private Vec3 goal;
    private int linger;
    private int stuck;

    public InvestigateNoiseGoal(RemixCreature mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null || !mob.hasFreshNoise()) return false;
        Vec3 p = mob.noisePos();
        return p != null && !RemixCreature.isRefuge(BlockPos.containing(p));
    }

    @Override
    public boolean canContinueToUse() {
        return mob.getTarget() == null && goal != null && (linger > 0 || (!mob.getNavigation().isDone() && stuck < 100));
    }

    @Override
    public void start() {
        goal = mob.noisePos();
        linger = 0; stuck = 0;
        if (goal != null) mob.getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        mob.consumeNoise();
        goal = null;
    }

    @Override
    public void tick() {
        if (goal == null) return;
        Vec3 fresh = mob.noisePos();
        if (fresh != null && fresh.distanceToSqr(goal) > 4 && linger == 0) {      // a newer noise: retarget
            goal = fresh;
            mob.getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
        }
        mob.getLookControl().setLookAt(goal.x, goal.y + 0.5, goal.z);
        if (linger > 0) { linger--; if (linger % 20 == 0) mob.getLookControl().setLookAt(goal.x + mob.getRandom().nextInt(7) - 3, goal.y, goal.z + mob.getRandom().nextInt(7) - 3); return; }
        if (mob.distanceToSqr(goal) < 4.0 || mob.getNavigation().isDone()) {
            if (mob.getNavigation().isDone() && mob.distanceToSqr(goal) >= 4.0) stuck += 10;
            linger = mob.noiseIsLure() ? 160 : 40;
            mob.getNavigation().stop();
        } else stuck++;
    }
}
