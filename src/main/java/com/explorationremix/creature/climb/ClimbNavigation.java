package com.explorationremix.creature.climb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
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
        // a wall climbed from the floor: it walks to the wall's foot first, then up it (aimed straight at the node up the
        // wall from a block or two out, it floated up to it through the air, and fell when that took too long); also
        // while it tops a step or slab at the foot, when it touches nothing for a moment but its path has just left a
        // floor
        if (wanted.y > mob.getY() + 0.5 && mob instanceof ClimbMoveControl.Climber c && (c.onFloor() || leftFloor())) {
            Vec3i n = this.path.getNextNodePos();
            if (ClimbNodeEvaluator.classify(level, n.getX(), n.getY(), n.getZ(), 2, 1, 2) == ClimbNodeEvaluator.WALL
                    && Mth.square(wanted.x - mob.getX()) + Mth.square(wanted.z - mob.getZ()) > FOOT * FOOT)
                wanted = new Vec3(wanted.x, mob.getY(), wanted.z);
        }
        this.mob.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, this.speedModifier);
    }

    /** How near the foot of a wall (across) it walks before it climbs. */
    private static final double FOOT = 0.05;

    /** Whether the node the path passed last is a floor, so the next one starts a climb from the floor. */
    private boolean leftFloor() {
        int i = this.path.getNextNodeIndex();
        if (i <= 0) return false;
        Node p = this.path.getNode(i - 1);
        return ClimbNodeEvaluator.classify(level, p.x, p.y, p.z, 2, 1, 2) == ClimbNodeEvaluator.FLOOR;
    }

    @Override
    protected void followThePath() {
        Vec3 pos = getTempMobPos();
        // close enough to the next node in all three directions: on to the one after it
        float reach = Math.max(0.55F, this.mob.getBbWidth() / 2.0F);
        Vec3 next = againstRock(this.path, this.path.getNextEntityPos(this.mob));
        // swimming it floats a little above a water node
        Vec3i n = this.path.getNextNodePos();
        double reachY = this.mob.isInWater()
                && ClimbNodeEvaluator.classify(level, n.getX(), n.getY(), n.getZ(), 2, 1, 2) == ClimbNodeEvaluator.WATER ? 1.2 : 0.75;
        if (Math.abs(pos.x - next.x) < reach && Math.abs(pos.z - next.z) < reach && Math.abs(pos.y - next.y) < reachY) {
            this.path.advance();
        }
        doStuckDetection(pos);
    }

    /**
     * The waypoint's box pressed against the rock its node clings to (walls and ceilings), or standing where it will
     * stand on a floor: on a slab in its cells, or down on a slab under them.
     */
    private Vec3 againstRock(Path path, Vec3 at) {
        Vec3i n = path.getNextNodePos();
        byte k = ClimbNodeEvaluator.classify(level, n.getX(), n.getY(), n.getZ(), 2, 1, 2);
        double half = mob.getBbWidth() / 2.0;
        double gapSide = 1.0 - half;                     // between the box and the cells' edge on each side
        if (k == ClimbNodeEvaluator.CEILING) return new Vec3(at.x, n.getY() + 1.0 - mob.getBbHeight(), at.z);
        if (k == ClimbNodeEvaluator.FLOOR) {
            // a slab or snow layer in its cells lifts the box: it aims where it will stand, on top (aimed at the node's
            // floor half a block lower, it climbs a hair up the slab's side and hangs there, clinging to it)
            double lift = ClimbNodeEvaluator.lift(level, n.getX(), n.getY(), n.getZ(), 2, 2);
            if (lift > 0.0) return new Vec3(at.x, at.y + lift, at.z);
            // standing on slabs under its cells, the box is lower than the node: aimed at the node, it floated up
            // through the air half a block over them, and often fell
            double top = ClimbNodeEvaluator.support(level, n.getX(), n.getY(), n.getZ(), 2, 2);
            return top > 0.0 && top < 1.0 ? new Vec3(at.x, at.y - 1.0 + top, at.z) : at;
        }
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
