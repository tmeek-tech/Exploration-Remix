package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.climb.ClimbMoveControl;
import com.explorationremix.creature.climb.ClimbNavigation;
import com.explorationremix.creature.climb.ClimbNodeEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;
import java.util.Map;

/**
 * For /remix mossback watch: how a Mossback gets on as it goes about, over a number of ticks. A stall is a time it was
 * sent somewhere (a path to walk, free to steer) and stayed within {@link #REACH} of one spot for {@link #STALL} ticks
 * or more: stuck on something. Each stall is logged as it starts, with the rock round it (every block with a collision
 * shape, and the shape when it is not a whole block) and its path, so the spot can be found and built again, and once
 * more as it ends. At the end (and every minute) a summary: ticks sent somewhere, blocks covered, stalls by length,
 * times wedged, falls, ways given up.
 */
final class MossbackWatch {
    /** Ticks within {@link #REACH} of one spot, while sent somewhere, that make a stall. */
    static final int STALL = 40;
    static final double REACH = 1.0;

    final int length;
    /** Keep it on the move: no waiting in the plants, no long pauses between legs. */
    final boolean roam;
    /** Instead of its own mind, it hunts a spot that moves on whenever it gets there (a chase's paths and pace, over and over). */
    final boolean hunt;
    @javax.annotation.Nullable Vec3 decoy;
    int decoyTicks;
    /** A replay (/remix mossback replay): one goal that never moves on, walked to at a roaming or a hunting pace. */
    @javax.annotation.Nullable Vec3 goal;
    boolean chase;
    int arrivedAt = -1;
    int ticks() { return ticks; }
    private int ticks, sentTicks, stalls, stallTicks, longest, falls, givenUp, timedOut0, failed0, wayOuts0, noWay0, climbs0;
    private final int[] lengths = new int[3];
    private double covered;
    @javax.annotation.Nullable private Vec3 anchor;
    private int anchorAt, stallFrom, stallClimbs;
    private double stallPath;
    private boolean stalled, wasAirborne, wasStuck;

    /** Every watch running, summed up as each ends (one line when the last has ended). */
    private static int running, allLizards, allStalls, allStallTicks, allLongest, allFalls, allGivenUp, allTimedOut, allFailed, allWayOuts, allNoWay, allSent;
    private static double allCovered;
    private static final int[] allLengths = new int[3];

    MossbackWatch(MossbackEntity m, int length, boolean roam, boolean hunt) {
        this.length = length;
        this.roam = roam || hunt;
        this.hunt = hunt;
        if (m.getNavigation() instanceof ClimbNavigation n) { timedOut0 = n.timeouts(); failed0 = n.failedMoves(); }
        if (m.getMoveControl() instanceof ClimbMoveControl mc) { wayOuts0 = mc.waysOut(); noWay0 = mc.noWayOut(); climbs0 = mc.climbs(); }
        if (running == 0) {
            allLizards = allStalls = allStallTicks = allLongest = allFalls = allGivenUp = allTimedOut = allFailed = allWayOuts = allNoWay = allSent = 0;
            allCovered = 0;
            java.util.Arrays.fill(allLengths, 0);
        }
        running++;
    }

    private static boolean moving(byte s) {
        return s == MossbackEntity.ROAM || s == MossbackEntity.WAIT || s == MossbackEntity.CHASE || s == MossbackEntity.SEARCH
                || s == MossbackEntity.CARRY || s == MossbackEntity.HOME;
    }

    /** Server, after its move. Returns false once the watch is over. */
    boolean tick(MossbackEntity m) {
        ticks++;
        Vec3 p = m.position();
        double step = Math.sqrt(sq(p.x - m.xo) + sq(p.y - m.yo) + sq(p.z - m.zo));
        covered += step;
        PathNavigation nav = m.getNavigation();
        boolean air = m.airborne();
        if (air && !wasAirborne) falls++;
        wasAirborne = air;
        boolean stuck = nav.isStuck();
        if (stuck && !wasStuck) givenUp++;
        wasStuck = stuck;
        boolean sent = !nav.isDone() && m.canSteer() && moving(m.state()) && !air;
        if (sent) sentTicks++;
        if (!sent) {
            endStall(m, nav.isStuck() ? "its way given up as stuck" : nav.isDone() ? "its path ended" : "no longer steering");
            anchor = null;
        } else if (anchor == null || p.distanceTo(anchor) > REACH) {
            endStall(m, "moved on");
            anchor = p;
            anchorAt = ticks;
            stallPath = 0;
            stallClimbs = climbs(m);
        } else {
            stallPath += step;
            if (!stalled && ticks - anchorAt >= STALL) startStall(m);
        }
        boolean done = ticks >= length || (goal != null && arrivedAt >= 0);
        if (goal != null && done)
            ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[mossback replay] #%d to %s %s: %s after %d ticks, %d stalls (%d ticks, longest %d), %.2f blocks off; at %s",
                    m.getId(), fmt(goal), chase ? "hunting" : "roaming", arrivedAt >= 0 ? "arrived" : "NOT there", ticks, stalls + (stalled ? 1 : 0),
                    stallTicks + (stalled ? ticks - stallFrom : 0), Math.max(longest, stalled ? ticks - stallFrom : 0), m.position().distanceTo(goal), fmt(m.position())));
        // a stall still open at the end counts in the summary
        if (done) endStall(m, "watch over");
        if (done || ticks % 1200 == 0) summary(m, done);
        if (done) finish(m);
        return !done;
    }

    private int climbs(MossbackEntity m) { return m.getMoveControl() instanceof ClimbMoveControl mc ? mc.climbs() : 0; }

    private void startStall(MossbackEntity m) {
        stalled = true;
        stallFrom = anchorAt;
        stalls++;
        ExplorationRemix.LOGGER.info("[mossback stall] #{} at {} for {} ticks: {}", m.getId(), fmt(m.position()), ticks - anchorAt, scene(m));
    }

    private void endStall(MossbackEntity m, String why) {
        if (!stalled) return;
        stalled = false;
        int len = ticks - stallFrom;
        stallTicks += len;
        longest = Math.max(longest, len);
        lengths[len < 100 ? 0 : len < 300 ? 1 : 2]++;
        ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[mossback stall end] #%d after %d ticks (%.2f blocks to and fro, %d ticks climbing straight up): %s; at %s",
                m.getId(), len, stallPath, climbs(m) - stallClimbs, why, fmt(m.position())));
    }

    private void summary(MossbackEntity m, boolean done) {
        int timed = m.getNavigation() instanceof ClimbNavigation n ? n.timeouts() - timedOut0 : 0;
        int failed = m.getNavigation() instanceof ClimbNavigation n ? n.failedMoves() - failed0 : 0;
        int out = 0, none = 0;
        if (m.getMoveControl() instanceof ClimbMoveControl mc) { out = mc.waysOut() - wayOuts0; none = mc.noWayOut() - noWay0; }
        ExplorationRemix.LOGGER.info(String.format(Locale.ROOT,
                "[mossback watch] #%d %s %d ticks%s: sent somewhere %d ticks, covered %.1f blocks, %d stalls (%d of 2-5 s, %d of 5-15 s, %d longer; "
                        + "%d ticks in all, longest %d), %d ways out when wedged (%d times none), %d falls, %d ways given up as stuck, %d timed out, "
                        + "%d moves given up as ones it could not make; now %s",
                m.getId(), done ? "done" : "so far", ticks, hunt ? " (hunting a moving spot)" : roam ? " (roaming)" : "", sentTicks, covered, stalls, lengths[0], lengths[1], lengths[2],
                stallTicks, longest, out, none, falls, givenUp, timed, failed, m.describe()));
    }

    /** Ended early (stopped, or the lizard is gone): what it saw still counts in the total. */
    void drop(MossbackEntity m) {
        endStall(m, "watch stopped");
        summary(m, true);
        finish(m);
    }

    private void finish(MossbackEntity m) {
        running = Math.max(0, running - 1);
        allLizards++;
        allStalls += stalls;
        allStallTicks += stallTicks;
        allLongest = Math.max(allLongest, longest);
        allFalls += falls;
        allGivenUp += givenUp;
        allSent += sentTicks;
        allCovered += covered;
        for (int i = 0; i < 3; i++) allLengths[i] += lengths[i];
        if (m.getNavigation() instanceof ClimbNavigation n) { allTimedOut += n.timeouts() - timedOut0; allFailed += n.failedMoves() - failed0; }
        if (m.getMoveControl() instanceof ClimbMoveControl mc) { allWayOuts += mc.waysOut() - wayOuts0; allNoWay += mc.noWayOut() - noWay0; }
        if (running == 0)
            ExplorationRemix.LOGGER.info(String.format(Locale.ROOT,
                    "[mossback watch total] %d lizards: sent somewhere %d ticks, covered %.1f blocks, %d stalls (%d of 2-5 s, %d of 5-15 s, %d longer; "
                            + "%d ticks in all, longest %d), %d ways out when wedged (%d times none), %d falls, %d ways given up as stuck, %d timed out, "
                            + "%d moves given up as ones it could not make",
                    allLizards, allSent, allCovered, allStalls, allLengths[0], allLengths[1], allLengths[2], allStallTicks, allLongest,
                    allWayOuts, allNoWay, allFalls, allGivenUp, allTimedOut, allFailed));
    }

    /** What it is doing and what is round it, for a stall's first line. */
    private static String scene(MossbackEntity m) {
        StringBuilder b = new StringBuilder();
        b.append(m.describe());
        Vec3 v = m.getDeltaMovement();
        b.append(String.format(Locale.ROOT, "; pos %.4f %.4f %.4f, motion %.3f %.3f %.3f%s%s", m.getX(), m.getY(), m.getZ(), v.x, v.y, v.z,
                m.onGround() ? ", on ground" : "", m.horizontalCollision ? ", against something" : ""));
        if (m.getMoveControl().hasWanted() || m.getMoveControl() instanceof ClimbMoveControl)
            b.append(String.format(Locale.ROOT, ", wants %.3f %.3f %.3f", m.getMoveControl().getWantedX(), m.getMoveControl().getWantedY(), m.getMoveControl().getWantedZ()));
        Level level = m.level();
        Path p = m.getNavigation().getPath();
        if (p != null) {
            b.append("; path");
            int from = Math.max(0, p.getNextNodeIndex() - 1), to = Math.min(p.getNodeCount(), p.getNextNodeIndex() + 4);
            for (int i = from; i < to; i++) {
                var n = p.getNode(i);
                byte k = ClimbNodeEvaluator.classify(level, n.x, n.y, n.z, 2, 1, 2);
                b.append(i == p.getNextNodeIndex() ? " >" : " ").append(n.x).append(',').append(n.y).append(',').append(n.z).append(':').append(KINDS[k]);
            }
            var end = p.getEndNode();
            if (end != null) b.append(" ... end ").append(end.x).append(',').append(end.y).append(',').append(end.z).append(p.canReach() ? "" : " (short)");
        }
        // the rock round its box: every cell with a collision shape or a fluid
        AABB box = m.getBoundingBox();
        int x0 = (int) Math.floor(box.minX - 1.5), x1 = (int) Math.floor(box.maxX + 1.5);
        int y0 = (int) Math.floor(box.minY - 1.0), y1 = (int) Math.floor(box.maxY + 1.5);
        int z0 = (int) Math.floor(box.minZ - 1.5), z1 = (int) Math.floor(box.maxZ + 1.5);
        b.append(String.format(Locale.ROOT, "; blocks %d %d %d to %d %d %d:", x0, y0, z0, x1, y1, z1));
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    BlockState s = level.getBlockState(c.set(x, y, z));
                    VoxelShape shape = s.getCollisionShape(level, c);
                    boolean fluid = !s.getFluidState().isEmpty();
                    if (shape.isEmpty() && !fluid && !s.blocksMotion() && s.isAir()) continue;
                    if (shape.isEmpty() && !fluid) {
                        // plants and the like: named, so a spot can be read, but marked as passable
                        b.append(' ').append(x).append(',').append(y).append(',').append(z).append('=').append(name(s)).append("~");
                        continue;
                    }
                    b.append(' ').append(x).append(',').append(y).append(',').append(z).append('=').append(name(s));
                    if (fluid) b.append("(fluid ").append(String.format(Locale.ROOT, "%.2f", s.getFluidState().getHeight(level, c))).append(')');
                    boolean whole = shape == Shapes.block()
                            || (!shape.isEmpty() && !Shapes.joinIsNotEmpty(shape, Shapes.block(), net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME));
                    if (!shape.isEmpty() && !whole) {
                        b.append('{');
                        boolean first = true;
                        for (AABB a : shape.toAabbs()) {
                            if (!first) b.append(';');
                            first = false;
                            b.append(String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f %.3f %.3f", a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ));
                        }
                        b.append('}');
                    }
                }
        return b.toString();
    }

    private static final String[] KINDS = {"blocked", "open", "floor", "wall", "ceiling", "water"};

    private static String name(BlockState s) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        if (id.startsWith("minecraft:")) id = id.substring(10);
        StringBuilder b = new StringBuilder(id);
        boolean any = false;
        for (Map.Entry<Property<?>, Comparable<?>> e : s.getValues().entrySet()) {
            String k = e.getKey().getName();
            // only what changes a block's shape
            if (!(k.equals("type") || k.equals("half") || k.equals("facing") || k.equals("layers") || k.equals("tilt") || k.equals("thickness")
                    || k.equals("vertical_direction") || k.equals("axis") || k.equals("shape") || k.equals("hanging") || k.equals("bottom"))) continue;
            b.append(any ? ',' : '[').append(k).append('=').append(e.getValue());
            any = true;
        }
        if (any) b.append(']');
        return b.toString();
    }

    private static String fmt(Vec3 p) { return String.format(Locale.ROOT, "%.2f %.2f %.2f", p.x, p.y, p.z); }

    private static double sq(double v) { return v * v; }
}
