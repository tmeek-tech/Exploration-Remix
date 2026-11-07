package com.explorationremix.rune.cast;

/**
 * Throwing a player through the air to a point, with the game's own fall and drag for a player off the ground: each
 * tick they move by their speed, then fall speed grows by {@value #FALL} and is kept at {@value #DRAG_Y}, and the
 * flat speed is kept at {@value #DRAG_FLAT}.
 *
 * <p>A point above them is reached at the top of the throw, so they land on a ledge rather than slamming into its
 * side; a point level or below is reached on the way down, sooner the nearer it is. Plain numbers only, so it can be
 * checked without a game.
 */
public final class Throw {
    static final double FALL = 0.08, DRAG_Y = 0.98, DRAG_FLAT = 0.91;
    /** How far above the point aimed at their feet should top out (clear of a ledge's edge). */
    public static final double LEDGE_CLEAR = 1.0;
    /** The fastest a throw goes, flat and up. */
    public static final double MAX_FLAT = 2.0, MAX_UP = 2.2, MAX_DOWN = 1.0;
    static final int MIN_TICKS = 4, MAX_TICKS = 16;

    /** The speed to throw at, and how many ticks until they get there. */
    public record Result(double vx, double vy, double vz, int ticks) {}

    private Throw() {}

    /** The throw that carries a player's feet by {@code dx, dy, dz}. */
    public static Result to(double dx, double dy, double dz) { return to(dx, dy, dz, 1.0); }

    /** As {@link #to(double, double, double)}, {@code quick} times sooner where the distance sets the time (Swift). */
    public static Result to(double dx, double dy, double dz, double quick) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        int byDistance = clamp((int) Math.round((MIN_TICKS + 0.5 * Math.sqrt(flat * flat + dy * dy)) / Math.max(1.0, quick)), MIN_TICKS - 1, MAX_TICKS);
        int t;
        double vy;
        double top = dy > 0.5 ? speedToTop(dy) : -1.0;
        int topTicks = top > 0 ? ticksToTop(top) : 0;
        if (top > 0 && topTicks >= byDistance) {
            // high and near: arrive at the top of the throw
            vy = top;
            t = Math.max(1, topTicks);
        } else {
            // far, level or below: arrive on a fixed clock, coming down onto it if need be
            t = byDistance;
            double r0 = rise(0.0, t), r1 = rise(1.0, t);
            vy = (dy - r0) / (r1 - r0);
        }
        vy = Math.max(-MAX_DOWN, Math.min(MAX_UP, vy));
        double vflat = flat < 1.0E-6 ? 0.0 : Math.min(MAX_FLAT, flat * (1.0 - DRAG_FLAT) / (1.0 - Math.pow(DRAG_FLAT, t)));
        double vx = flat < 1.0E-6 ? 0.0 : dx / flat * vflat, vz = flat < 1.0E-6 ? 0.0 : dz / flat * vflat;
        return new Result(vx, vy, vz, t);
    }

    /** How high a throw up at {@code vy} is after {@code ticks} ticks. */
    static double rise(double vy, int ticks) {
        double y = 0.0;
        for (int k = 0; k < ticks; k++) {
            y += vy;
            vy = (vy - FALL) * DRAG_Y;
        }
        return y;
    }

    /** How flat a throw at {@code v} has gone after {@code ticks} ticks. */
    static double along(double v, int ticks) {
        double x = 0.0;
        for (int k = 0; k < ticks; k++) {
            x += v;
            v *= DRAG_FLAT;
        }
        return x;
    }

    /** Ticks a throw up at {@code vy} keeps rising. */
    static int ticksToTop(double vy) {
        int t = 0;
        while (vy > 0.0 && t < 200) {
            vy = (vy - FALL) * DRAG_Y;
            t++;
        }
        return t;
    }

    /** The highest a throw up at {@code vy} goes. */
    static double topHeight(double vy) { return rise(vy, ticksToTop(vy)); }

    /** The speed up that tops out {@code height} above. */
    static double speedToTop(double height) {
        double lo = 0.0, hi = 4.0;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) * 0.5;
            if (topHeight(mid) < height) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(hi, v); }
}
