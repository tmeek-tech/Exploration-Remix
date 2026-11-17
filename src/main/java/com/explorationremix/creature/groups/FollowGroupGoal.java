package com.explorationremix.creature.groups;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * A group member that has strayed past {@link MobGroupRules#LEASH} from its leader walks back to it until it is within
 * {@link MobGroupRules#CLOSE}, unless it has something to fight. It sits just before the mob's own stroll, so the group
 * drifts where the leader strolls and each member still idles about near it.
 */
final class FollowGroupGoal extends Goal {
    /** Walking speed, as a share of the mob's own (its stroll's, for most kinds). */
    static final double SPEED = 1.0;
    /** Paths not found in a row before it gives up for a while (the leader is out of reach). */
    static final int GIVE_UP = 3;
    /** How long it rests after giving up (ticks). */
    static final int REST = 200;
    /** It looks for a new path this often while walking (ticks). */
    static final int REPATH = 10;

    private final PathfinderMob mob;
    @Nullable private Mob leader;
    private int repath, failures;
    private long restUntil;

    FollowGroupGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.level().getGameTime() < restUntil) return false;
        if (mob.getTarget() != null || mob.isPassenger() || mob.isLeashed()) return false;
        Mob l = MobGroups.leaderOf(mob);
        if (l == null || l == mob || l.level() != mob.level()) return false;
        if (!MobGroupRules.strayed(mob.distanceTo(l))) return false;
        leader = l;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        Mob l = leader;
        return l != null && l.isAlive() && l.level() == mob.level() && mob.getTarget() == null && !mob.isPassenger()
                && failures < GIVE_UP && MobGroups.leaderOf(mob) == l && !MobGroupRules.caughtUp(mob.distanceTo(l));
    }

    @Override
    public void start() {
        repath = 0;
        failures = 0;
    }

    @Override
    public void stop() {
        if (failures >= GIVE_UP) restUntil = mob.level().getGameTime() + REST;
        leader = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (leader == null || --repath > 0) return;
        repath = adjustedTickDelay(REPATH);
        if (mob.getNavigation().moveTo(leader, SPEED)) failures = 0;
        else failures++;
    }
}
