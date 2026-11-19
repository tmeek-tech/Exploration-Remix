package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.climb.ClimbMoveControl;
import com.explorationremix.creature.climb.ClimbNodeEvaluator;
import com.explorationremix.world.terrain.Route;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * For {@code /remix giant roam} (a dev check, 0.19.3): the giant crawls leg after leg about its rift with no rests,
 * and this counts how well it holds on and how smoothly it goes: the blocks it crawls, the ticks it spends on floors,
 * walls and ceilings, with no rock in reach and with its box off the rock, its falls (and how far each dropped it),
 * how often the side its pose follows changes, the ticks it is wedged, sudden jumps of the point under its head, and
 * how much its head turns for each block it crawls. Each fall is logged with what it was doing; the totals go to the
 * server log when the check ends.
 */
final class GiantRoamTest {
    private static final String[] KIND = {"blocked", "open", "floor", "wall", "ceiling", "water"};

    int left;
    private int ticks, legs, nearLegs, failedLegs, switches, gripChanges, airTicks, offTicks, falls, wedged, jumps, stalls;
    @Nullable private Direction lastGrip;
    private final int[] onFace = new int[7];
    private double crawled, dropped, maxDrop, turn;
    private boolean falling;
    private double fallFromY;
    private int fallStart, lastFaceTick, stallRun, stallLogs, wedgeRun, wedgeLogs, sideLogs, jumpLogs, flipBacks, lastSwitchTick;
    private byte sideBefore = -2;
    private byte lastSurface = -2;
    private Direction lastFace;
    private Vec3 lastFoot, lastForward;

    void start(int ticks) {
        left = ticks;
        this.ticks = legs = nearLegs = failedLegs = switches = gripChanges = airTicks = offTicks = falls = wedged = jumps = stalls = 0;
        lastGrip = null;
        java.util.Arrays.fill(onFace, 0);
        crawled = dropped = maxDrop = turn = 0.0;
        falling = false;
        stallRun = stallLogs = wedgeRun = wedgeLogs = sideLogs = jumpLogs = flipBacks = lastSwitchTick = 0;
        sideBefore = -2;
        lastSurface = -2;
        lastFace = null;
        lastFoot = lastForward = null;
    }

    boolean on() { return left > 0; }
    void leg(boolean found) { leg(found, ""); }

    /** A short crawl over the rock round it set out on, as no leg along its rift had a way (why; the first dozen are logged). */
    void nearLeg(String why) {
        legs++;
        if (nearLegs++ < 12) ExplorationRemix.LOGGER.info("[giant near] " + why);
    }

    /** A leg of its crawl set out on, or none found (why each try failed; the first dozen are logged). */
    void leg(boolean found, String why) {
        if (found) legs++;
        else if (failedLegs++ < 12) ExplorationRemix.LOGGER.info("[giant noway] " + why);
    }

    void tick(GiantSkeinEntity g) {
        ticks++;
        Direction s = g.surface(), hold = g.poseRock(), grip = g.grip();
        if (ticks > 1 && grip != lastGrip) gripChanges++;
        lastGrip = grip;
        onFace[s == null ? 6 : s.get3DDataValue()]++;
        byte cur = (byte) (hold == null ? -1 : hold.get3DDataValue());
        if (lastSurface != -2 && cur != lastSurface) {
            switches++;
            // back to the side it had just before, within half a second: it flips to and fro
            if (cur == sideBefore && ticks - lastSwitchTick <= 10) flipBacks++;
            if (sideLogs++ < 25) ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[giant side] %s to %s after %d ticks; %s",
                    side(lastSurface), side(cur), ticks - lastSwitchTick, why(g)));
            sideBefore = lastSurface;
            lastSwitchTick = ticks;
        }
        lastSurface = cur;
        if (s != null) { lastFace = s; lastFaceTick = ticks; }
        else airTicks++;
        if (!g.boxOnRock()) offTicks++;
        boolean air = g.airborne();
        if (air && !falling) {
            falling = true;
            fallFromY = g.getY();
            fallStart = ticks;
            falls++;
            ExplorationRemix.LOGGER.info("[giant fall] " + why(g));
        } else if (!air && falling) {
            falling = false;
            double d = Math.max(0.0, fallFromY - g.getY());
            dropped += d;
            maxDrop = Math.max(maxDrop, d);
            ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[giant land] after %d ticks, %.1f blocks down, onto %s at %.1f %.1f %.1f",
                    ticks - fallStart, d, s == null ? "water" : s.getName(), g.getX(), g.getY(), g.getZ()));
        }
        if (!falling) crawled += g.movedLastTick();
        if (g.getMoveControl() instanceof ClimbMoveControl m && m.wedged() > 0) {
            wedged++;
            if (++wedgeRun == 5 && wedgeLogs++ < 15) ExplorationRemix.LOGGER.info("[giant wedge] " + why(g));
        } else wedgeRun = 0;
        Vec3 f = g.footPoint();
        if (f != null) {
            if (lastFoot != null && f.distanceTo(lastFoot) > 0.6) {
                jumps++;
                if (jumpLogs++ < 15) ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[giant jump] head point %.2f %.2f %.2f to %.2f %.2f %.2f; %s",
                        lastFoot.x, lastFoot.y, lastFoot.z, f.x, f.y, f.z, why(g)));
            }
            lastFoot = f;
        }
        Vec3 fw = g.lookDir();
        if (lastForward != null) turn += Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, lastForward.dot(fw)))));
        lastForward = fw;
        if (g.state() == GiantSkeinEntity.CRAWL && !g.getNavigation().isDone() && g.movedLastTick() < 0.01) {
            stalls++;
            // a long stall is logged with what it was doing (the first dozen)
            if (++stallRun == 60 && stallLogs++ < 12) ExplorationRemix.LOGGER.info("[giant stall] " + why(g));
        } else stallRun = 0;
        if (--left == 0) ExplorationRemix.LOGGER.info("[giant roam] " + report());
    }

    private static String side(byte b) { return b < 0 ? "nothing" : Direction.from3DDataValue(b).getName(); }

    /** What it was doing as it began to fall: where, its state, its last rock, its path's next node, its rock round it. */
    private String why(GiantSkeinEntity g) {
        Direction pose = g.poseRock(), grip = g.grip();
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "tick %d at %.2f %.2f %.2f, %s, last on %s %d ticks ago, grips %s, posed on %s",
                ticks, g.getX(), g.getY(), g.getZ(), GiantSkeinEntity.STATE_NAMES[Math.max(0, g.state())],
                lastFace == null ? "nothing" : lastFace.getName(), ticks - lastFaceTick, grip == null ? "nothing" : grip.getName(),
                pose == null ? "nothing" : pose.getName()));
        Path p = g.getNavigation().getPath();
        if (p != null && !p.isDone()) {
            Vec3i n = p.getNextNodePos();
            byte k = ClimbNodeEvaluator.classify(g.level(), n.getX(), n.getY(), n.getZ(), 3, 2, 3);
            b.append(String.format(Locale.ROOT, ", next node %d %d %d (%s, %d of %d)", n.getX(), n.getY(), n.getZ(),
                    k >= 0 && k < KIND.length ? KIND[k] : "?", p.getNextNodeIndex(), p.getNodeCount()));
        } else b.append(", no path");
        MoveControl mc = g.getMoveControl();
        b.append(String.format(Locale.ROOT, ", wants %.2f %.2f %.2f, moving %.3f %.3f %.3f, speed %.3f, wedged %d", mc.getWantedX(), mc.getWantedY(), mc.getWantedZ(),
                g.getDeltaMovement().x, g.getDeltaMovement().y, g.getDeltaMovement().z, g.getSpeed(),
                mc instanceof ClimbMoveControl m ? m.wedged() : -1));
        AABB box = g.getBoundingBox();
        b.append(", rock within 3:");
        for (Direction d : Direction.values()) {
            double gap = GiantSkeinEntity.rockGap(g.level(), g, box, d, 3.0);
            b.append(' ').append(d.getName().charAt(0)).append('=').append(Double.isNaN(gap) ? "-" : String.format(Locale.ROOT, "%.1f", gap));
        }
        return b.toString();
    }

    /** Why the rift's wall was not found on {@code side} at arc length {@code s}, height {@code y} (unloaded rock, or none). */
    static String noWall(LevelReader level, Route r, double s, double side, double y) {
        double[] c = r.pointAt(s), d = r.directionAt(s);
        double px = -d[1] * side, pz = d[0] * side, reach = r.riftHalfAt(s) + 12.0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (double v = 0.0; v <= reach; v += 0.5) {
            p.set(Mth.floor(c[0] + px * v), Mth.floor(y), Mth.floor(c[1] + pz * v));
            if (!GiantSkeinEntity.loaded(level, p))
                return String.format(Locale.ROOT, "no wall at %.0f along, %.0f high: unloaded at %s", s, y, p.toShortString());
        }
        return String.format(Locale.ROOT, "no wall at %.0f along, %.0f high: no rock out to %.0f from the middle at %.0f %.0f", s, y, reach, c[0], c[1]);
    }

    String report() {
        double per = crawled > 1.0 ? 100.0 / crawled : 0.0;
        int n = Math.max(1, ticks);
        return String.format(Locale.ROOT,
                "%d ticks, crawled %.0f blocks in %d legs (%d of them short ones round it, %d found no way); floor %.0f%% wall %.0f%% ceiling %.0f%% no rock in reach %.0f%% box off the rock %.0f%%; "
                        + "falls %d (%.1f per 100 blocks), dropped %.0f blocks in all, %.1f at most; side changes %d (%.1f per 100 blocks, grip changes %d); "
                        + "flips back %d; wedged %d ticks; head point jumps %d; head turns %.1f degrees a block; stalled %d ticks",
                ticks, crawled, legs, nearLegs, failedLegs, 100.0 * onFace[0] / n,
                100.0 * (onFace[2] + onFace[3] + onFace[4] + onFace[5]) / n, 100.0 * onFace[1] / n, 100.0 * onFace[6] / n, 100.0 * offTicks / n,
                falls, falls * per, dropped, maxDrop, switches, switches * per, gripChanges, flipBacks, wedged, jumps, crawled > 1.0 ? turn / crawled : 0.0, stalls);
    }
}
