package com.explorationremix.creature.climb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;

/**
 * Navigation over floors, walls and ceilings ({@link ClimbNodeEvaluator}). Waypoints are not pulled down to the
 * ground as vanilla's are, and a waypoint on a wall or ceiling is moved against the rock so the creature's box touches
 * it the whole way (the box is narrower than the cells it fills).
 */
public class ClimbNavigation extends net.minecraft.world.entity.ai.navigation.PathNavigation {
    public ClimbNavigation(Mob mob, Level level) {
        super(mob, level);
        setMaxVisitedNodesMultiplier(2.5F);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new ClimbNodeEvaluator();
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    @Override
    protected Vec3 getTempMobPos() {
        return mob.position();
    }

    @Override
    protected boolean canUpdatePath() {
        return true;
    }

    @Override
    protected double getGroundY(Vec3 v) {
        return v.y;
    }

    @Override
    protected boolean canMoveDirectly(Vec3 from, Vec3 to) {
        return false;
    }

    @Override
    public boolean isStableDestination(BlockPos pos) {
        byte k = ClimbNodeEvaluator.classify(level, pos.getX(), pos.getY(), pos.getZ(), 2, 1, 2);
        return k >= ClimbNodeEvaluator.FLOOR;
    }

    @Override
    public void tick() {
        ++this.tick;
        if (this.hasDelayedRecomputation) this.recomputePath();
        if (this.isDone()) return;
        followThePath();
        if (this.isDone()) return;
        Vec3 next = this.path.getNextEntityPos(this.mob);
        Vec3 wanted = againstRock(this.path, next);
        this.mob.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, this.speedModifier);
    }

    @Override
    protected void followThePath() {
        Vec3 pos = getTempMobPos();
        // close enough to the next node in all three directions: on to the one after it
        float reach = Math.max(0.55F, this.mob.getBbWidth() / 2.0F);
        Vec3 next = againstRock(this.path, this.path.getNextEntityPos(this.mob));
        if (Math.abs(pos.x - next.x) < reach && Math.abs(pos.z - next.z) < reach && Math.abs(pos.y - next.y) < 0.75) {
            this.path.advance();
        }
        doStuckDetection(pos);
    }

    /** The waypoint's box pressed against the rock its node clings to (walls and ceilings only). */
    private Vec3 againstRock(Path path, Vec3 at) {
        Vec3i n = path.getNextNodePos();
        byte k = ClimbNodeEvaluator.classify(level, n.getX(), n.getY(), n.getZ(), 2, 1, 2);
        double half = mob.getBbWidth() / 2.0;
        double gapSide = 1.0 - half;                     // between the box and the cells' edge on each side
        if (k == ClimbNodeEvaluator.CEILING) return new Vec3(at.x, n.getY() + 1.0 - mob.getBbHeight(), at.z);
        if (k != ClimbNodeEvaluator.WALL) return at;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double x = at.x, z = at.z;
        if (solidColumn(p, n.getX() - 1, n.getY(), n.getZ(), 0, 1)) x -= gapSide;
        else if (solidColumn(p, n.getX() + 2, n.getY(), n.getZ(), 0, 1)) x += gapSide;
        if (solidColumn(p, n.getX(), n.getY(), n.getZ() - 1, 1, 0)) z -= gapSide;
        else if (solidColumn(p, n.getX(), n.getY(), n.getZ() + 2, 1, 0)) z += gapSide;
        return new Vec3(x, at.y, z);
    }

    private boolean solidColumn(BlockPos.MutableBlockPos p, int x, int y, int z, int stepX, int stepZ) {
        for (int i = 0; i < 2; i++) {
            p.set(x + stepX * i, y, z + stepZ * i);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return true;
        }
        return false;
    }

    /** Whether the mob could stand, cling or swim with its box's low corner at the given node. */
    public boolean walkableAt(int x, int y, int z) {
        return ClimbNodeEvaluator.classify(level, x, y, z, 2, 1, 2) >= ClimbNodeEvaluator.FLOOR;
    }

    public static int nodeX(double x) { return Mth.floor(x - 0.5); }
}
