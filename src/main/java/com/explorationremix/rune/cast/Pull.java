package com.explorationremix.rune.cast;

/**
 * Reel pulling a player along a way of points (to a wall, and over onto its top): each tick they are sent straight at
 * the next point, the pull building up over its first ticks and easing off near the point so they stop there rather
 * than shoot past. It ends when they get there, when they stop getting nearer (something is in the way), or when it
 * has taken longer than the way should.
 *
 * <p>The server steers from where it last heard the player was, moved on by its own copy of their speed; with a slow
 * connection they are further on than that by the speeds still on their way, so those are added ({@link #lead}: one
 * tick's speed for every 50 ms the connection takes there and back). Plain numbers only, so it can be checked without
 * a game.
 */
public final class Pull {
    /** Blocks a tick at full pull. */
    public static final double SPEED = 1.2;
    /** The first tick's pull, and how much it grows each tick after. */
    static final double START = 0.5, BUILD = 0.35;
    /** Near a point the pull is at most {@code NEAR0 + NEAR * distance} a tick. */
    static final double NEAR0 = 0.25, NEAR = 0.5;
    /** Near enough to a point on the way to make for the next, and to the last to stop. */
    public static final double PASS = 0.6, ARRIVE = 0.35;
    /** A pull must get this much nearer within {@link #STALL_TICKS} ticks, or something is in the way. */
    static final double PROGRESS = 0.05;
    static final int STALL_TICKS = 4;
    /** Ticks to spare beyond the way's length at full pull: building up, easing off, climbing a face. */
    static final int SPARE_TICKS = 12;
    /** The most ticks of speed added for a slow connection. */
    static final int MAX_LEAD = 6;

    private Pull() {}

    /** How far the pull moves them this tick, {@code age} ticks in, {@code dist} from the point. */
    public static double speed(double full, int age, double dist) {
        return Math.max(0.0, Math.min(full, Math.min(START + BUILD * Math.max(0, age), NEAR0 + NEAR * dist)));
    }

    /** The speed this tick that takes {@code dx, dy, dz} (from them to the point) as far as it goes, as x, y, z. */
    public static double[] step(double dx, double dy, double dz, double full, int age) {
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-6) return new double[]{0.0, 0.0, 0.0};
        double s = speed(full, age, dist) / dist;
        return new double[]{dx * s, dy * s, dz * s};
    }

    /** How many ticks of speed, besides the last, the player is further on than the server last heard: by round trip. */
    public static int lead(int roundTripMs) {
        return Math.max(0, Math.min(MAX_LEAD, Math.round(roundTripMs / 50.0F)));
    }

    /** The most ticks a pull along a way this long may take. */
    public static int ticks(double length, double full) {
        return (int) Math.ceil(length / Math.max(0.1, full)) + SPARE_TICKS;
    }

    /** Whether a pull is still getting nearer to the point it is making for. */
    public static final class Progress {
        private double best = Double.MAX_VALUE;
        private int since;

        /** A new point to make for. */
        public void reset() {
            best = Double.MAX_VALUE;
            since = 0;
        }

        /** Notes this tick's distance to the point; false once it has gone {@link #STALL_TICKS} ticks without getting nearer. */
        public boolean update(double dist) {
            if (dist < best - PROGRESS) {
                best = dist;
                since = 0;
                return true;
            }
            return ++since <= STALL_TICKS;
        }
    }
}
