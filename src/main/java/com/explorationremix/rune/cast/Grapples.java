package com.explorationremix.rune.cast;

import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

/**
 * Reel pulling its caster to a wall ({@link Pull} steers). The way goes straight to the wall; when the wall's top is
 * within reach above the point caught, it goes on up to the edge and over onto the top, and otherwise they hang at the
 * point a moment, then drop. A point caught on a floor is stood on; one on a ceiling is hung under.
 *
 * <p>The pull is set at the end of each of the player's ticks, after the server has moved them, so it reaches their
 * client as it is. Sneaking lets go and keeps the speed they have; so does something in the way, or taking too long.
 *
 * <p>One pull each time they touch the ground (or water), so a sheer face can't be climbed pull after pull and a
 * fall can't be stopped again and again. A pull adds nothing to a fall, but a fall already under way when it started
 * still counts when they land.
 */
final class Grapples {
    /** How long they hang at a wall with no top within reach. */
    static final int HANG_TICKS = 12;
    /** How far above the point caught a wall's top may be and still be climbed onto. */
    static final double CLIMB = 2.6;
    /** How far from a face they hang: half a player's width, and a little. */
    private static final double OFF_FACE = 0.36;
    /** How far onto a top they are set down, past its edge. */
    private static final double ONTO = 0.8;

    /** One pull under way. */
    static final class Grapple {
        final ServerLevel level;
        /** Where their feet go, in order. */
        final Vec3[] way;
        /** The point caught, and the block it holds on to. */
        final Vec3 hold;
        final BlockPos caught;
        /** Ends standing (on a top, a floor) rather than hanging. */
        final boolean stand;
        final double speed;
        final int colour;
        final long start, until;
        final Pull.Progress progress = new Pull.Progress();
        /** The speeds set lately, the newest last: on a slow connection some are still on their way. */
        final ArrayDeque<Vec3> sent = new ArrayDeque<>();
        /** How far they had already fallen when the pull caught them: that fall still counts. */
        final float fall;
        int next;
        long hangUntil = -1;
        boolean sneaking;

        Grapple(ServerLevel level, Vec3[] way, Vec3 hold, BlockPos caught, boolean stand, double speed, int colour, long start, long until,
                boolean sneaking, float fall) {
            this.fall = fall;
            this.level = level;
            this.way = way;
            this.hold = hold;
            this.caught = caught;
            this.stand = stand;
            this.speed = speed;
            this.colour = colour;
            this.start = start;
            this.until = until;
            this.sneaking = sneaking;
        }
    }

    private Grapples() {}

    /**
     * Starts pulling the player to {@code at}, a point on a block (a carrier stopped there, or they aimed at it), in
     * sight and within reach. False if it can't: too near or too far, or not a block seen from where they are.
     */
    static boolean start(ServerPlayer p, Vec3 at, int swift, int colour) {
        ServerLevel level = p.serverLevel();
        if (p.isPassenger() || p.isFallFlying() || p.isSpectator() || p.isSleeping()) return false;
        Vec3 chest = p.getEyePosition().add(0, -0.3, 0);
        Vec3 to = at.subtract(chest);
        double dist = to.length();
        if (dist < 1.5 || dist > Instants.GRAPPLE_RANGE + 4 * swift) return false;
        // the block the pull reaches, and the face it reaches it by, as seen from where they are
        BlockHitResult face = FlyingRune.clip(level, chest, at.add(to.scale(1.0 / dist)), p);
        if (face.getType() == HitResult.Type.MISS || face.getLocation().distanceTo(at) > 1.0) return false;
        BlockPos caught = face.getBlockPos();
        Direction side = face.getDirection();
        Vec3 hit = face.getLocation();
        Vec3[] way;
        boolean stand;
        if (side == Direction.UP) {
            way = new Vec3[]{new Vec3(hit.x, hit.y + 0.02, hit.z)};
            stand = true;
        } else if (side == Direction.DOWN) {
            way = new Vec3[]{new Vec3(hit.x, hit.y - 1.9, hit.z)};
            stand = false;
        } else {
            double nx = side.getStepX(), nz = side.getStepZ();
            // across the face, kept to the caught block's width so the body fits in front of it
            double ax = nx == 0 ? Mth.clamp(hit.x, caught.getX() + 0.3, caught.getX() + 0.7) : hit.x;
            double az = nz == 0 ? Mth.clamp(hit.z, caught.getZ() + 0.3, caught.getZ() + 0.7) : hit.z;
            Double top = top(level, caught, side, hit.y);
            if (top != null) {
                way = new Vec3[]{new Vec3(ax + nx * OFF_FACE, top + 0.08, az + nz * OFF_FACE),
                        new Vec3(ax - nx * ONTO, top + 0.08, az - nz * ONTO)};
                stand = true;
            } else {
                way = new Vec3[]{new Vec3(ax + nx * OFF_FACE, hit.y - 1.3, az + nz * OFF_FACE)};
                stand = false;
            }
        }
        double length = 0.0;
        Vec3 from = p.position();
        for (Vec3 w : way) {
            length += w.distanceTo(from);
            from = w;
        }
        double speed = Pull.SPEED * (1.0 + 0.25 * swift);
        long now = level.getGameTime();
        Caster c = CastService.caster(p);
        if (c.airPullUsed) return false;
        c.launch = null;
        long until = now + Pull.ticks(length, speed) + Pull.lead(p.latency);
        c.pull = new Grapple(level, way, hit, caught, stand, speed, colour, now, until, p.isShiftKeyDown(), p.fallDistance);
        c.airPullUsed = true;
        c.pullTick = now;
        return true;
    }

    /**
     * The height of a wall's top above the point caught on its face: the floor of the first open block up the caught
     * column, when it is within reach, there is room to stand on it, and the face in front is open all the way up.
     */
    @Nullable
    private static Double top(ServerLevel level, BlockPos caught, Direction side, double hitY) {
        for (int k = 1; k <= 4; k++) {
            BlockPos b = caught.above(k);
            if (solid(level, b)) continue;
            BlockPos under = b.below();
            VoxelShape s = level.getBlockState(under).getCollisionShape(level, under);
            if (s.isEmpty() || s.max(Direction.Axis.Y) > 1.0) return null;
            double top = under.getY() + s.max(Direction.Axis.Y);
            if (top - hitY > CLIMB || solid(level, b.above())) return null;
            BlockPos front = caught.relative(side);
            for (int y = Mth.floor(hitY); y <= b.getY() + 1; y++) {
                if (solid(level, new BlockPos(front.getX(), y, front.getZ()))) return null;
            }
            return top;
        }
        return null;
    }

    private static boolean solid(ServerLevel level, BlockPos b) {
        return !level.getBlockState(b).getCollisionShape(level, b).isEmpty();
    }

    /** Each tick of the player's while pulled, at its end: on along the way, hanging, or letting go. */
    static void tick(ServerPlayer p, Caster c, long now) {
        Grapple g = c.pull;
        if (g.level != p.level() || !p.isAlive() || p.isSpectator() || p.isPassenger() || p.isFallFlying() || p.isSleeping() || now > g.until) {
            letGo(p, c);
            return;
        }
        boolean sneak = p.isShiftKeyDown(), pressed = sneak && !g.sneaking;
        g.sneaking = sneak;
        if (pressed) {
            letGo(p, c);
            return;
        }
        p.fallDistance = g.fall;
        if ((now - g.start) % 2 == 0) {
            RuneFx.line(g.level, p.position().add(0, p.getBbHeight() * 0.6, 0), g.hold, RuneFx.dust(g.colour, 0.55F), 0.7);
        }
        if (g.hangUntil >= 0) {
            if (now >= g.hangUntil || !solid(g.level, g.caught)) {
                letGo(p, c);
                return;
            }
            set(p, g, Vec3.ZERO);
            return;
        }
        Vec3 feet = ahead(p, g);
        Vec3 aim = g.way[g.next];
        double dist = aim.distanceTo(feet);
        while (g.next < g.way.length - 1 && dist < Pull.PASS) {
            g.next++;
            g.progress.reset();
            aim = g.way[g.next];
            dist = aim.distanceTo(feet);
        }
        if (g.next == g.way.length - 1 && dist < Pull.ARRIVE) {
            set(p, g, Vec3.ZERO);
            if (g.stand) letGo(p, c);
            else g.hangUntil = now + HANG_TICKS;
            return;
        }
        if (!g.progress.update(dist)) {
            letGo(p, c);
            return;
        }
        double[] v = Pull.step(aim.x - feet.x, aim.y - feet.y, aim.z - feet.z, g.speed, (int) (now - g.start));
        set(p, g, new Vec3(v[0], v[1], v[2]));
    }

    /**
     * Where their feet will be when this tick's speed reaches them: where the server has them (its own move by the last
     * speed included) and, on a slow connection, on by the speeds still on their way, as far as blocks let them go.
     */
    private static Vec3 ahead(ServerPlayer p, Grapple g) {
        int lead = Pull.lead(p.latency);
        if (lead <= 0 || g.sent.size() < 2) return p.position();
        Vec3 more = Vec3.ZERO;
        Iterator<Vec3> it = g.sent.descendingIterator();
        it.next();
        for (int j = 0; j < lead && it.hasNext(); j++) more = more.add(it.next());
        if (more.lengthSqr() < 1.0E-6) return p.position();
        return p.position().add(Entity.collideBoundingBox(p, more, p.getBoundingBox(), p.level(), List.of()));
    }

    /** The pull ends; they keep the speed they have, and a fall counts on from what it was when the pull caught them. */
    static void letGo(ServerPlayer p, Caster c) {
        if (c.pull != null) p.fallDistance = c.pull.fall;
        c.pull = null;
    }

    private static void set(ServerPlayer p, Grapple g, Vec3 v) {
        p.setDeltaMovement(v);
        p.hurtMarked = true;
        g.sent.addLast(v);
        while (g.sent.size() > Pull.MAX_LEAD + 1) g.sent.removeFirst();
    }
}
