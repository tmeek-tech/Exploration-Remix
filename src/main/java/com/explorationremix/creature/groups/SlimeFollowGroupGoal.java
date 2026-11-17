package com.explorationremix.creature.groups;

import com.explorationremix.mixin.accessor.SlimeMoveControlAccessor;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Slime;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * A slime that has strayed from its group hops back towards its leader. Slimes do not walk paths: they face a way and
 * keep hopping (their own keep-on-jumping goal), so this turns it to face the leader, in place of its random turns, as
 * vanilla's slime attack does with a target.
 */
final class SlimeFollowGroupGoal extends Goal {
    private final Slime slime;
    @Nullable private Mob leader;

    SlimeFollowGroupGoal(Slime slime) {
        this.slime = slime;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (slime.getTarget() != null || slime.isPassenger() || !(slime.getMoveControl() instanceof SlimeMoveControlAccessor)) return false;
        Mob l = MobGroups.leaderOf(slime);
        if (l == null || l == slime || l.level() != slime.level()) return false;
        if (!MobGroupRules.strayed(slime.distanceTo(l))) return false;
        leader = l;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        Mob l = leader;
        return l != null && l.isAlive() && l.level() == slime.level() && slime.getTarget() == null && !slime.isPassenger()
                && MobGroups.leaderOf(slime) == l && !MobGroupRules.caughtUp(slime.distanceTo(l));
    }

    @Override
    public void stop() {
        leader = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (leader == null) return;
        slime.lookAt(leader, 10.0F, 10.0F);
        if (slime.getMoveControl() instanceof SlimeMoveControlAccessor move) move.er$setDirection(slime.getYRot(), false);
    }
}
