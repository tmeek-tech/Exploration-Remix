package com.explorationremix.combat;

import com.explorationremix.mixin.accessor.ServerGamePacketListenerAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the server knows of each player's swings: where they were the last few ticks (a player's own moves come from
 * their game, so their speed is read from where they went), how charged their last swing was, whether it was a
 * critical hit, and a momentum push waiting for the hit's knockback. Never saved.
 * <p>
 * Where they were is taken at the start of each of their ticks: in the middle of it the server tries its own move of
 * them with the speed it last knew (a Dash's, say) and puts them back after, so a place read then is one they never
 * were. Their speed is how far they went over how many moves their game sent meanwhile (one each of its ticks while
 * they move), not over the server's ticks: a game that runs slowly or a connection that lags sends its moves in
 * bunches, and the server can see a player stand still for a tick and then cover two.
 */
public final class Fighters {
    /** Ticks of positions kept: speed is read over this many. */
    private static final int SPAN = 5;
    /** Faster than this (blocks a tick) is a teleport, not a run. */
    private static final double TELEPORT = 4.0;

    static final class F {
        final Vec3[] pos = new Vec3[SPAN];
        final long[] tick = new long[SPAN];
        final int[] moves = new int[SPAN];
        int next;
        int swingTarget = -1;
        long swingTick = -1;
        float swingStrength;
        long critTick = -1;
        /** A momentum hit's extra push, for the knockback that follows it this tick. */
        int pushTarget = -1;
        long pushTick = -1;
        float push;
    }

    private static final Map<UUID, F> MAP = new HashMap<>();

    private Fighters() {}

    private static F of(ServerPlayer p) { return MAP.computeIfAbsent(p.getUUID(), u -> new F()); }

    /** The start of the player's tick: where their game says they are now. */
    public static void tick(ServerPlayer p) {
        F f = of(p);
        f.pos[f.next] = p.position();
        f.tick[f.next] = p.level().getGameTime();
        f.moves[f.next] = p.connection == null ? 0 : ((ServerGamePacketListenerAccessor) p.connection).er$movePackets();
        f.next = (f.next + 1) % SPAN;
    }

    /**
     * How fast the player has been moving (blocks a tick of their game), from the oldest position kept to the newest:
     * nothing if their game sent no moves meanwhile.
     */
    public static Vec3 velocity(ServerPlayer p) {
        F f = MAP.get(p.getUUID());
        if (f == null) return Vec3.ZERO;
        long now = p.level().getGameTime();
        int oldest = -1, newest = -1;
        for (int i = 0; i < SPAN; i++) {
            if (f.pos[i] == null || f.tick[i] > now || now - f.tick[i] > SPAN) continue;
            if (oldest < 0 || f.tick[i] < f.tick[oldest]) oldest = i;
            if (newest < 0 || f.tick[i] > f.tick[newest]) newest = i;
        }
        if (oldest < 0) return Vec3.ZERO;
        int moves = f.moves[newest] - f.moves[oldest];
        if (moves <= 0) return Vec3.ZERO;
        Vec3 v = f.pos[newest].subtract(f.pos[oldest]).scale(1.0 / moves);
        return v.length() > TELEPORT ? Vec3.ZERO : v;
    }

    /** How fast the player was closing on {@code target} (blocks a tick, along the line between their middles). */
    public static double closing(ServerPlayer p, LivingEntity target) {
        Vec3 to = target.getBoundingBox().getCenter().subtract(p.getBoundingBox().getCenter());
        if (to.lengthSqr() < 1.0E-6) return 0.0;
        return velocity(p).dot(to.normalize());
    }

    /** The player swings at {@code target} (before the hit is worked out): how charged the swing is. */
    public static void swing(ServerPlayer p, LivingEntity target, float strength) {
        F f = of(p);
        f.swingTarget = target.getId();
        f.swingTick = p.level().getGameTime();
        f.swingStrength = strength;
    }

    /** Whether the melee hit on {@code target} now is the swing at it (not a sweep's edge, nor a hit nobody saw swung). */
    public static boolean swungAt(ServerPlayer p, LivingEntity target) {
        F f = MAP.get(p.getUUID());
        return f != null && f.swingTick == p.level().getGameTime() && f.swingTarget == target.getId();
    }

    /** How charged the swing that is hitting {@code target} now was (1 full); 1 if it wasn't seen. */
    public static float swingStrength(ServerPlayer p, LivingEntity target) {
        F f = MAP.get(p.getUUID());
        if (f == null || f.swingTick != p.level().getGameTime() || f.swingTarget != target.getId()) return 1.0F;
        return f.swingStrength;
    }

    public static void crit(ServerPlayer p) { of(p).critTick = p.level().getGameTime(); }

    public static boolean crit(ServerPlayer p, LivingEntity target) {
        F f = MAP.get(p.getUUID());
        return f != null && f.critTick == p.level().getGameTime() && f.swingTarget == target.getId();
    }

    /** A momentum hit on {@code target}: the knockback that follows this tick is this much stronger. */
    public static void pushNext(ServerPlayer p, LivingEntity target, float push) {
        F f = of(p);
        f.pushTarget = target.getId();
        f.pushTick = p.level().getGameTime();
        f.push = push;
    }

    /** The extra push owed to {@code target}'s knockback now, once: 0 if none. */
    public static float takePush(ServerPlayer p, LivingEntity target) {
        F f = MAP.get(p.getUUID());
        if (f == null || f.pushTick != p.level().getGameTime() || f.pushTarget != target.getId()) return 0.0F;
        f.pushTarget = -1;
        return f.push;
    }

    public static void forget(ServerPlayer p) { MAP.remove(p.getUUID()); }

    public static void clear() { MAP.clear(); }
}
