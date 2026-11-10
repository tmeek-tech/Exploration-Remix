package com.explorationremix.rune.cast;

/**
 * Reel pulling a player along a way of points (to a wall, and over onto its top): each tick they are sent straight at
 * the next point, the pull building up over its first ticks and easing off near the point so they stop there rather
 * than shoot past. It ends when they get there, when they stop getting nearer (something is in the way), or when it
 * has taken longer than the way should.
 *
 * <p>The server decides the pull and its way ({@link Grapples}); the player's own game steers it ({@link Steer}), each
 * of its ticks from where the player really is, as it moves them anyway. Steered from the server, a pull would only
 * ever know where the player was a round trip ago, and a game that stutters takes in a new speed only once a frame,
 * so the pull would shoot past or let go early. Plain numbers only, so it can be checked without a game.
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
    /** How far below a point on the way they may be and still make for the next: an edge is cleared, not clipped. */
    static final double BELOW = 0.15;
    /** A pull must get this much nearer within {@link #STALL_TICKS} ticks, or something is in the way. */
    static final double PROGRESS = 0.05;
    static final int STALL_TICKS = 4;
    /** Ticks to spare beyond the way's length at full pull: building up, easing off, climbing over an edge. */
    static final int SPARE_TICKS = 12;
    /** How long they hang at a wall with no top within reach. */
    public static final int HANG_TICKS = 12;

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

    /** The most ticks a pull along a way this long may take, before any hang at its end. */
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

    /**
     * One pull, steered on the pulled player's own game: each tick, from where their feet are, the speed to set. The
     * points are where their feet go, x, y, z each, in order; at the last they stand ({@code stand}) and the pull ends,
     * or they hang there for {@link #HANG_TICKS} ticks, then drop.
     */
    public static final class Steer {
        public final int id;
        final double[][] way;
        final boolean stand;
        final double full;
        final int maxTicks;
        private final Progress progress = new Progress();
        private int next, age, hang = -1;
        private boolean over;

        public Steer(int id, double[][] way, boolean stand, double full, int maxTicks) {
            this.id = id;
            this.way = way;
            this.stand = stand;
            this.full = full;
            this.maxTicks = maxTicks;
            if (way.length == 0) over = true;
        }

        /** Done: they stood, hung their time, stopped getting nearer, or took too long. */
        public boolean over() { return over; }

        /**
         * This tick's speed for feet at {@code x, y, z}, or null to leave their speed as it is (the pull has let go,
         * and they carry on as they were going).
         */
        public double[] tick(double x, double y, double z) {
            if (over) return null;
            if (hang >= 0) {
                if (hang-- > 0) return new double[]{0.0, 0.0, 0.0};
                over = true;
                return null;
            }
            if (age > maxTicks) {
                over = true;
                return null;
            }
            int last = way.length - 1;
            double[] aim = way[next];
            double dist = dist(aim, x, y, z);
            while (next < last && dist < PASS && y > aim[1] - BELOW) {
                next++;
                progress.reset();
                aim = way[next];
                dist = dist(aim, x, y, z);
            }
            if (next == last && dist < ARRIVE) {
                if (stand) over = true;
                else hang = HANG_TICKS - 1;
                return new double[]{0.0, 0.0, 0.0};
            }
            if (!progress.update(dist)) {
                over = true;
                return null;
            }
            return step(aim[0] - x, aim[1] - y, aim[2] - z, full, age++);
        }

        private static double dist(double[] a, double x, double y, double z) {
            double dx = a[0] - x, dy = a[1] - y, dz = a[2] - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
}
