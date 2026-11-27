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
 * it the whole way (the box is narrower than the cells it fills). The cells a node fills come from the mob's box, as
 * the node evaluator reads them: 2 x 1 x 2 for the Mossback and the Skein, 3 x 2 x 3 for the giant Skein (0.18.8).
 */
public class ClimbNavigation extends net.minecraft.world.entity.ai.navigation.PathNavigation {
    /** The cells its box fills across (x and z) and up, as the node evaluator counts them. */
    private final int cw, ch;

    public ClimbNavigation(Mob mob, Level level) {
        super(mob, level);
        setMaxVisitedNodesMultiplier(2.5F);
        this.cw = Mth.floor(mob.getBbWidth() + 1.0F);
        this.ch = Mth.floor(mob.getBbHeight() + 1.0F);
    }

    /** The kind of the node with low corner (x, y, z), for this mob's box. */
    private byte kindAt(int x, int y, int z) {
        return ClimbNodeEvaluator.classify(level, x, y, z, cw, ch, cw);
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
        return kindAt(pos.getX(), pos.getY(), pos.getZ()) >= ClimbNodeEvaluator.FLOOR;
    }

    @Override
    public void tick() {
        ++this.tick;
        if (this.hasDelayedRecomputation) this.recomputePath();
        if (this.isDone()) {
            stepTicks = 0;
            return;
        }
        followThePath();
        if (this.isDone() || failing()) return;
        Vec3 next = this.path.getNextEntityPos(this.mob);
        Vec3 wanted = againstRock(this.path, next);
        // a wall climbed from the floor: it walks to the wall's foot first, then up it (aimed straight at the node up the
        // wall from a block or two out, it floated up to it through the air, and fell when that took too long); also
        // while it tops a step or slab at the foot, when it touches nothing for a moment but its path has just left a
        // floor
        if (wanted.y > mob.getY() + 0.5 && mob instanceof ClimbMoveControl.Climber c && (c.onFloor() || leftFloor())) {
            Vec3i n = this.path.getNextNodePos();
            if (kindAt(n.getX(), n.getY(), n.getZ()) == ClimbNodeEvaluator.WALL
                    && Mth.square(wanted.x - mob.getX()) + Mth.square(wanted.z - mob.getZ()) > FOOT * FOOT)
                wanted = new Vec3(wanted.x, mob.getY(), wanted.z);
        }
        this.mob.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, this.speedModifier);
    }

    /** Paths given up because the next node took too long to reach (vanilla's timeout), for /remix mossback watch. */
    private int timeouts;
    public int timeouts() { return timeouts; }

    /**
     * A move of its path it has been on this long while it steers (from the node it passed last to the next one) is one
     * it cannot make: something the nodes misjudge holds it there. That move is left out of its paths this long.
     */
    static final int GIVE_UP_TICKS = 80, AVOID_TICKS = 600;
    private long stepFrom = Long.MAX_VALUE, stepTo = Long.MAX_VALUE;
    private int stepTicks, failedMoves;

    /** Moves it gave up as ones it could not make, for /remix mossback watch. */
    public int failedMoves() { return failedMoves; }

    /**
     * Whether it has been on the same move too long, steering all the while: that move is left out of its paths for a
     * while and the way is found again from where it is (round it, or anywhere else when there is no way round). Its
     * own mind sends it on new paths as it pleases (a hunter every half second); the move is the same one so long as
     * it has not got past it, so the count runs on across them. A path's first node is where it starts, so before it
     * gets there the move is from the node it is in now. True when no way is left.
     */
    private boolean failing() {
        if (!(mob instanceof ClimbMoveControl.Climber c) || !c.canSteer() || c.airborne()) {
            stepTicks = 0;
            return false;
        }
        int i = this.path.getNextNodeIndex();
        Node to = this.path.getNode(i);
        BlockPos from = i > 0 ? this.path.getNode(i - 1).asBlockPos()
                : BlockPos.containing(mob.getX() - cw * 0.5 + 0.5, mob.getY() + 0.05, mob.getZ() - cw * 0.5 + 0.5);
        long f = from.asLong(), t = BlockPos.asLong(to.x, to.y, to.z);
        if (f != stepFrom || t != stepTo) {
            stepFrom = f;
            stepTo = t;
            stepTicks = 0;
            return false;
        }
        if (++stepTicks < GIVE_UP_TICKS) return false;
        stepTicks = 0;
        failedMoves++;
        if (f != t && this.nodeEvaluator instanceof ClimbNodeEvaluator e) e.avoid(from, to.asBlockPos(), level.getGameTime() + AVOID_TICKS);
        recomputePath();
        return this.isDone();
    }

    @Override
    protected void doStuckDetection(Vec3 pos) {
        Path before = this.path;
        super.doStuckDetection(pos);
        // vanilla's timeout drops the path without calling it stuck
        if (before != null && this.path == null && !this.isStuck()) timeouts++;
    }

    /** How near the foot of a wall (across) it walks before it climbs. */
    private static final double FOOT = 0.05;

    /** Whether the node the path passed last is a floor, so the next one starts a climb from the floor. */
    private boolean leftFloor() {
        int i = this.path.getNextNodeIndex();
        if (i <= 0) return false;
        Node p = this.path.getNode(i - 1);
        return kindAt(p.x, p.y, p.z) == ClimbNodeEvaluator.FLOOR;
    }

    @Override
    protected void followThePath() {
        Vec3 pos = getTempMobPos();
        // close enough to the next node in all three directions: on to the one after it (closer when the move after it
        // goes up or down: onto or off a slab, a step or a wall, that move is only sure to be clear of rock from where the
        // box stands at the node, and half its width short of it the box was caught on the step's corner, or under a lip)
        double reach = Math.max(0.55F, this.mob.getBbWidth() / 2.0F);
        Vec3 next = againstRock(this.path, this.path.getNextEntityPos(this.mob));
        int i = this.path.getNextNodeIndex();
        if (i + 1 < this.path.getNodeCount()
                && Math.abs(againstRock(this.path.getNode(i + 1).asBlockPos(), this.path.getEntityPosAtNode(this.mob, i + 1)).y - next.y) > 0.1)
            reach = CLOSE;
        // swimming it floats a little above a water node
        Vec3i n = this.path.getNextNodePos();
        double reachY = this.mob.isInWater()
                && kindAt(n.getX(), n.getY(), n.getZ()) == ClimbNodeEvaluator.WATER ? 1.2 : 0.75;
        if (Math.abs(pos.x - next.x) < reach && Math.abs(pos.z - next.z) < reach && Math.abs(pos.y - next.y) < reachY) {
            this.path.advance();
        }
        doStuckDetection(pos);
    }

    /** How near a node (across) it gets before it heads on, when the move after it goes up or down. */
    private static final double CLOSE = 0.35;

    private Vec3 againstRock(Path path, Vec3 at) {
        return againstRock(path.getNextNodePos(), at);
    }

    /**
     * The waypoint's box pressed against the rock its node clings to (walls and ceilings), or standing where it will
     * stand on a floor: on a slab in its cells, or down on a slab under them.
     */
    private Vec3 againstRock(Vec3i n, Vec3 at) {
        byte k = kindAt(n.getX(), n.getY(), n.getZ());
        double half = mob.getBbWidth() / 2.0;
        double gapSide = cw / 2.0 - half;                // between the box and the cells' edge on each side
        if (k == ClimbNodeEvaluator.CEILING) return new Vec3(at.x, n.getY() + ch - mob.getBbHeight(), at.z);
        if (k == ClimbNodeEvaluator.FLOOR) {
            // a slab or snow layer in its cells lifts the box: it aims where it will stand, on top (aimed at the node's
            // floor half a block lower, it climbs a hair up the slab's side and hangs there, clinging to it)
            double lift = ClimbNodeEvaluator.lift(level, n.getX(), n.getY(), n.getZ(), cw, cw);
            if (lift > 0.0) return new Vec3(at.x, at.y + lift, at.z);
            // standing on slabs under its cells, the box is lower than the node: aimed at the node, it floated up
            // through the air half a block over them, and often fell
            double top = ClimbNodeEvaluator.support(level, n.getX(), n.getY(), n.getZ(), cw, cw);
            return top > 0.0 && top < 1.0 ? new Vec3(at.x, at.y - 1.0 + top, at.z) : at;
        }
        if (k != ClimbNodeEvaluator.WALL) return at;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double x = at.x, z = at.z;
        if (solidColumn(p, n.getX() - 1, n.getY(), n.getZ(), 0, 1)) x -= gapSide;
        else if (solidColumn(p, n.getX() + cw, n.getY(), n.getZ(), 0, 1)) x += gapSide;
        if (solidColumn(p, n.getX(), n.getY(), n.getZ() - 1, 1, 0)) z -= gapSide;
        else if (solidColumn(p, n.getX(), n.getY(), n.getZ() + cw, 1, 0)) z += gapSide;
        return new Vec3(x, at.y, z);
    }

    private boolean solidColumn(BlockPos.MutableBlockPos p, int x, int y, int z, int stepX, int stepZ) {
        for (int i = 0; i < cw; i++) {
            p.set(x + stepX * i, y, z + stepZ * i);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return true;
        }
        return false;
    }

    /** Whether the mob could stand, cling or swim with its box's low corner at the given node. */
    public boolean walkableAt(int x, int y, int z) {
        return kindAt(x, y, z) >= ClimbNodeEvaluator.FLOOR;
    }

    public static int nodeX(double x) { return Mth.floor(x - 0.5); }
}
