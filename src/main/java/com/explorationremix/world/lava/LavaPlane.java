package com.explorationremix.world.lava;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * The Basalt Galleries' risen lava as a plane (0.21.6, Taran: "a smoothly rising plane instead of real blocks"): one
 * level surface over the whole layer whose height follows the cycle ({@link LavaCycle#height}) between whole blocks,
 * with no lava blocks under it. Everything under its surface down to the lava table's own lava counts as lava for the
 * things in it: an entity there is in lava as it would be in vanilla lava (set alight, hurt, slowed, able to swim up,
 * items burning; {@code mixin.EntityLavaPlaneMixin}), the camera sees lava fog ({@code mixin.client.CameraMixin}) and
 * the quakes, debris, eruptions and the terrain reset read its rows as under the sea. Each client draws it
 * ({@code client.LavaPlaneRenderer}).
 *
 * <p>There are two: the server's ({@link #SERVER}, set by {@link LavaFlood} every overworld tick) and the client's
 * ({@link #CLIENT}, set from each {@link LavaNet.Sync}); a single-player game has both, each read only by its own side's
 * thread. Between two settings a plane counts on from the cycle tick it was given with the game time, so it moves
 * smoothly every tick and every frame (a client passes the partial tick).
 */
public final class LavaPlane {
    /** The server's plane (the overworld's), and this client's. */
    public static final LavaPlane SERVER = new LavaPlane(), CLIENT = new LavaPlane();

    /** How the plane stands: its cycle and band, and the cycle tick at a game time. */
    private record At(LavaCycle cycle, int tableY, int top, int bottom, long cycleTick, long gameTime, boolean frozen) {}

    /** The last height asked for at a whole game time, for the many entities that ask in one tick. */
    private record Cached(At at, long gameTime, double height) {}

    @Nullable private volatile At at;
    @Nullable private volatile Cached cached;

    private LavaPlane() {}

    /**
     * The plane follows {@code cycle} over the band from the lava table at {@code tableY} (the Galleries' rows are
     * {@code bottom} to {@code top}), the cycle standing at {@code cycleTick} at game time {@code gameTime} and counting
     * on with it unless {@code frozen}.
     */
    public void set(LavaCycle cycle, int tableY, int top, int bottom, long cycleTick, long gameTime, boolean frozen) {
        At a = at;
        // a setting that changes nothing keeps the cache (the server sets it every tick)
        if (a != null && a.cycle == cycle && a.tableY == tableY && a.top == top && a.bottom == bottom && a.frozen == frozen
                && (frozen ? a.cycleTick == cycleTick : a.cycleTick - a.gameTime == cycleTick - gameTime)) return;
        at = new At(cycle, tableY, top, bottom, cycleTick, gameTime, frozen);
        cached = null;
    }

    /** No plane: the cycle does not run (no Galleries, turned off, another dimension, logged out, stopped). */
    public void off() {
        at = null;
        cached = null;
    }

    /** True while the cycle runs and the plane follows it. */
    public boolean on() { return at != null; }

    /**
     * The plane of {@code level}'s side when {@code level} is the overworld and the cycle runs, else null. Only the
     * overworld has the Galleries.
     */
    @Nullable
    public static LavaPlane of(Level level) {
        if (level.dimension() != Level.OVERWORLD) return null;
        LavaPlane p = level.isClientSide ? CLIENT : SERVER;
        return p.on() ? p : null;
    }

    /** The lava table's Y (its own lava is the row under it), or MIN_VALUE when off. */
    public int tableY() {
        At a = at;
        return a == null ? Integer.MIN_VALUE : a.tableY;
    }

    /** The cycle the plane follows, or null when off. */
    @Nullable
    public LavaCycle cycle() {
        At a = at;
        return a == null ? null : a.cycle;
    }

    /** The cycle tick (with the partial tick's fraction) at game time {@code gameTime}, or 0 when off. */
    public double cycleTick(long gameTime, float partial) {
        At a = at;
        if (a == null) return 0;
        return a.frozen ? a.cycleTick : a.cycleTick + (double) (gameTime - a.gameTime) + partial;
    }

    /** How many blocks the plane stands over its table at game time {@code gameTime} plus {@code partial}: 0 when off. */
    public double height(long gameTime, float partial) {
        At a = at;
        if (a == null) return 0;
        if (partial == 0) {
            Cached c = cached;
            if (c != null && c.at == a && c.gameTime == gameTime) return c.height;
            double h = a.cycle.height(cycleTick(gameTime, 0));
            cached = new Cached(a, gameTime, h);
            return h;
        }
        return a.cycle.height(cycleTick(gameTime, partial));
    }

    /**
     * The Y of the plane's surface at game time {@code gameTime} plus {@code partial}, or NaN while it is down at its
     * table (the table's own lava is real blocks then) or off. It starts level with the table's lava (whose surface is
     * {@link LavaCycle#SURFACE_DROP} under the table's Y) and stands {@link LavaCycle#rise} blocks over it at full.
     */
    public double surface(long gameTime, float partial) {
        At a = at;
        if (a == null) return Double.NaN;
        double h = height(gameTime, partial);
        return h > 0 ? a.tableY - LavaCycle.SURFACE_DROP + h : Double.NaN;
    }

    /** The bottom of the plane's lava: the surface of the table's own lava, or NaN when off. */
    public double floor() {
        At a = at;
        return a == null ? Double.NaN : a.tableY - LavaCycle.SURFACE_DROP;
    }

    /**
     * How deep the plane's lava stands over the bottom of {@code box} at game time {@code gameTime} (the fluid height
     * vanilla gives an entity in lava): 0 when the plane is down or off, or the box is wholly over its surface or under
     * its floor (in the table's own lava or below it, where the real blocks answer).
     */
    public double depth(AABB box, long gameTime) {
        At a = at;
        if (a == null) return 0;
        double s = surface(gameTime, 0);
        if (!(s > box.minY) || box.maxY <= a.tableY - LavaCycle.SURFACE_DROP) return 0;
        return s - box.minY;
    }

    /** True when the point at Y {@code y} is under the plane's surface and over its floor at game time {@code gameTime} plus {@code partial}. */
    public boolean under(double y, long gameTime, float partial) {
        At a = at;
        if (a == null) return false;
        double s = surface(gameTime, partial);
        return s > y && y >= a.tableY - LavaCycle.SURFACE_DROP;
    }
}
