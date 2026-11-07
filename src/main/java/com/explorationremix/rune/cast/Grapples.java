package com.explorationremix.rune.cast;

import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.net.RuneNet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * Reel pulling its caster to a wall. The way goes straight to the wall; when the wall's top is within reach above the
 * point caught, it goes on up to the edge and over onto the top, and otherwise they hang at the point a moment, then
 * drop. A point caught on a floor is stood on; one on a ceiling is hung under.
 *
 * <p>The server decides the pull and its way and sends it to the player's game, which steers it ({@link Pull.Steer})
 * and says when it is done. The server lets go itself on a sneak, a throw, the block caught being broken, a fall
 * well below the way, or the pull taking far too long, and tells the game to stop.
 *
 * <p>One pull each time they touch the ground (or water), so a sheer face can't be climbed pull after pull and a
 * fall can't be stopped again and again. A pull adds nothing to a fall, but a fall already under way when it started
 * still counts when they land.
 */
public final class Grapples {
    /** How far above the point caught a wall's top may be and still be climbed onto. */
    static final double CLIMB = 2.6;
    /** How far from a face they hang: half a player's width, and a little. */
    private static final double OFF_FACE = 0.36;
    /** How far onto a top they are set down, past its edge; and how far above the top the edge is cleared. */
    private static final double ONTO = 0.8, CLEAR = 0.25;
    /**
     * Ticks the server waits beyond twice the pull's own time for the player's game to say it is done. Only a backstop
     * (a game that never says): a fall well below the way ends a pull anyway, so a long wait buys nothing.
     */
    private static final int SLACK_TICKS = 100;
    /** How far below the way, and where it started, they may get before the server lets go: they are falling, not pulled. */
    private static final double DROP_BELOW = 2.0;

    /** One pull under way. */
    static final class Grapple {
        final int id;
        final ServerLevel level;
        /** The point caught, and the block it holds on to. */
        final Vec3 hold;
        final BlockPos caught;
        final int colour;
        final long start, until;
        /** The lowest their feet should get: the way's lowest point, or where they started. */
        final double lowest;
        /** How far they had already fallen when the pull caught them: that fall still counts. */
        final float fall;
        boolean sneaking;

        Grapple(int id, ServerLevel level, Vec3 hold, BlockPos caught, int colour, long start, long until, double lowest, boolean sneaking,
                float fall) {
            this.id = id;
            this.level = level;
            this.hold = hold;
            this.caught = caught;
            this.colour = colour;
            this.start = start;
            this.until = until;
            this.lowest = lowest;
            this.sneaking = sneaking;
            this.fall = fall;
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
        Caster c = CastService.caster(p);
        if (c.airPullUsed) return false;
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
                way = new Vec3[]{new Vec3(ax + nx * OFF_FACE, top + CLEAR, az + nz * OFF_FACE),
                        new Vec3(ax - nx * ONTO, top + 0.08, az - nz * ONTO)};
                stand = true;
            } else {
                way = new Vec3[]{new Vec3(ax + nx * OFF_FACE, hit.y - 1.3, az + nz * OFF_FACE)};
                stand = false;
            }
        }
        double length = 0.0, lowest = p.getY();
        Vec3 from = p.position();
        double[][] points = new double[way.length][];
        for (int i = 0; i < way.length; i++) {
            Vec3 w = way[i];
            length += w.distanceTo(from);
            from = w;
            lowest = Math.min(lowest, w.y);
            points[i] = new double[]{w.x, w.y, w.z};
        }
        double speed = Pull.SPEED * (1.0 + 0.25 * swift);
        int ticks = Pull.ticks(length, speed);
        long now = level.getGameTime();
        // the player's game takes half a round trip to hear of it, and as long again to say it is done; a game that
        // stutters or falls behind takes longer
        int roundTrip = Mth.clamp(Mth.ceil(p.latency / 50.0F), 0, 20);
        long until = now + 2L * (ticks + (stand ? 0 : Pull.HANG_TICKS)) + roundTrip + SLACK_TICKS;
        c.launch = null;
        if (c.pull != null) letGo(p, c);
        int id = ++c.pullIds;
        c.pull = new Grapple(id, level, hit, caught, colour, now, until, lowest, p.isShiftKeyDown(), p.fallDistance);
        c.airPullUsed = true;
        c.pullTick = now;
        RuneNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new RuneNet.PullStart(id, stand, speed, ticks, points));
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

    /** Each tick of the player's while pulled, at its end: the tether, the fall held where it was, or letting go. */
    static void tick(ServerPlayer p, Caster c, long now) {
        Grapple g = c.pull;
        if (g.level != p.level() || !p.isAlive() || p.isSpectator() || p.isPassenger() || p.isFallFlying() || p.isSleeping() || now > g.until
                || p.getY() < g.lowest - DROP_BELOW || !solid(g.level, g.caught)) {
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
    }

    /** The player's game says pull {@code id} is done: it stood them on the top, hung its time, or met something in the way. */
    public static void done(ServerPlayer p, int id) {
        Caster c = CastService.caster(p);
        if (c.pull != null && c.pull.id == id) end(p, c);
    }

    /** The server lets go: the pull ends, and the player's game is told to stop steering it. */
    static void letGo(ServerPlayer p, Caster c) {
        if (c.pull == null) return;
        int id = c.pull.id;
        end(p, c);
        RuneNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new RuneNet.PullStop(id));
    }

    /** The pull ends; they keep the speed they have, and a fall counts on from what it was when the pull caught them. */
    private static void end(ServerPlayer p, Caster c) {
        p.fallDistance = c.pull.fall;
        c.pull = null;
    }

    /** The fall that counts for a player landing while pulled: only what they had fallen before it caught them. */
    public static float landing(ServerPlayer p, float distance) {
        Caster c = CastService.casterOrNull(p);
        return c == null || c.pull == null ? distance : Math.min(distance, c.pull.fall);
    }
}
