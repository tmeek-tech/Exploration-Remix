package com.explorationremix.combat.foes;

/**
 * The numbers of vanilla's hostile mobs' fights (0.23.6, Taran 2026-11-27), with no game in them so they can be tested:
 * how long each blow winds up and how a mob comes back from it, how far a lunge or a pounce carries, where an archer
 * aims, and where the mobs waiting their turn stand round their quarry.
 * <p>
 * The picks behind them (cards of 27 Nov, the recommended ones built while open): blows half again as hard, a quarter
 * more health, wind-ups of about half a second with a glint and a sound, two mobs striking at a time while the rest
 * circle, and one new move for each kind.
 */
public final class FoeRules {
    private FoeRules() {}

    /** Their blows hit this much harder (the card's recommended answer: half again; a zombie's 3 is 4.5 on Normal). */
    public static final double DAMAGE = 1.5;
    /** And they have this much more health (a quarter more; a zombie's 20 is 25). */
    public static final double HEALTH = 1.25;
    /** Mobs that may wind up a blow at one quarry at a time (the card's recommended answer: the rest circle and wait their turn). */
    public static final int STRIKERS = 2;

    // ------------------------------------------------------------------------------------------------ melee moves

    /**
     * One melee move's timings (ticks) and reach: the wind-up (the tell), the strike (the lunge, during which the blow
     * lands once it is within reach), the recovery after a hit and after a miss (a miss leaves a longer opening), the
     * wait before the next wind-up, how far off (blocks, middle to middle) a mob starts its wind-up, how far its lunge
     * can carry it, and how far from its quarry it waits its turn.
     */
    public record Melee(FoeMove move, int windup, int strike, int recover, int whiff, int coolMin, int coolMax, double start, double lunge,
                        double ring) {
        /** The wind-up as the server's setting scales it (1: the picks). */
        public int windup(double scale) { return FoeRules.scaled(windup, scale); }

        public int cooldown(double roll) { return coolMin + (int) Math.floor(Math.max(0.0, Math.min(0.999, roll)) * (coolMax - coolMin + 1)); }
    }

    /** A zombie's slam: half a second drawn back, a lurch of about two blocks, a long stumble if it misses. */
    public static final Melee ZOMBIE = new Melee(FoeMove.SLAM, 10, 4, 10, 24, 14, 26, 3.3, 2.0, 3.9);
    /** A skeleton with nothing to shoot with swipes as a zombie does, a little quicker, with a shorter reach. */
    public static final Melee SKELETON = new Melee(FoeMove.SWIPE, 9, 3, 9, 18, 12, 22, 2.6, 1.3, 3.6);
    /** A vindicator's chop: slower to come (the card's recommended answer: a heavy blow winds up longer), no lurch to speak of. */
    public static final Melee VINDICATOR = new Melee(FoeMove.CHOP, 14, 3, 12, 22, 16, 28, 2.6, 1.1, 3.8);
    /** An enderman's blow from overhead: a little longer than a zombie's, a long reach. */
    public static final Melee ENDERMAN = new Melee(FoeMove.CLAW, 12, 3, 10, 20, 18, 30, 3.0, 1.6, 4.2);
    /** A skeleton's kick at a player who reached it: quick, and it only pushes. */
    public static final Melee KICK = new Melee(FoeMove.KICK, 8, 3, 8, 14, 50, 80, 2.1, 0.8, 0.0);
    /** A spider's bite up close (a cave spider's is quicker, {@link #QUICK}). */
    public static final Melee BITE = new Melee(FoeMove.BITE, 9, 3, 9, 16, 12, 22, 1.9, 0.8, 5.2);

    /** Small mobs (cave spiders) wind up this much quicker. */
    public static final double QUICK = 0.75;

    /** A wind-up of {@code base} ticks as {@code scale} sets it; never under 3 ticks. */
    public static int scaled(int base, double scale) { return Math.max(3, (int) Math.round(base * scale)); }

    /**
     * The speed (blocks a tick, held each tick of the strike) of a lunge across a gap of {@code gap} blocks to within
     * reach, in {@code ticks}: a little past the gap, at most {@code most}; nothing when already within reach.
     */
    public static double lungeSpeed(double gap, double most, int ticks) {
        if (gap <= 0.0 || ticks <= 0) return 0.0;
        return Math.min(most, gap + 0.3) / ticks;
    }

    // ------------------------------------------------------------------------------------------------ pounces

    /** A spider's pounce: from this far (blocks), up to {@link #POUNCE_FAR}. */
    public static final double POUNCE_NEAR = 2.4, POUNCE_FAR = 5.0;
    /** The crouch before it (ticks), the most it flies before it counts as landed, and how long it waits after. */
    public static final int POUNCE_WINDUP = 10, POUNCE_FLIGHT = 24, POUNCE_COOL_MIN = 30, POUNCE_COOL_MAX = 50;
    /** A pounce's lift (blocks a tick, straight up), a little more the further it goes. */
    public static double pounceLift(double d) { return 0.34 + 0.025 * Math.max(0.0, d); }

    /** A spider this far above its quarry (blocks), and this near it along the floor, lets go onto it. */
    public static final double DROP_ABOVE = 2.2, DROP_NEAR = 3.5;

    /** The air's drag along the floor (each tick a creature is off the ground, its speed along is kept this much). */
    public static final double AIR_DRAG = 0.91;

    /**
     * How much of its speed along a creature keeps after the tick it sets off in: the ground's friction still holds it
     * that tick ({@code friction} of the block under it, 0.6 for most; times the air's drag), none once it is off.
     */
    public static double firstDrag(boolean onGround, double friction) { return onGround ? friction * AIR_DRAG : AIR_DRAG; }

    /**
     * The speed along the floor (blocks a tick) that a leap with lift {@code lift} needs to come down {@code d} blocks
     * away, where the ground is {@code dy} blocks above where it set off (below: negative), as the game moves it: the
     * first tick it goes the whole of it, then keeps {@code firstDrag} of it (see {@link #firstDrag}), then
     * {@link #AIR_DRAG} a tick; up and down, 0.08 of gravity and 0.98 a tick. Worked out by flying it: what one unit of
     * speed along carries over the flight, divided into the distance.
     */
    public static double leapSpeed(double d, double dy, double lift, double firstDrag) {
        double y = 0.0, v = lift, along = 0.0, h = 1.0;
        for (int t = 0; t < 80; t++) {
            y += v;
            along += h;
            h *= t == 0 ? firstDrag : AIR_DRAG;
            v = (v - 0.08) * 0.98;
            if (v < 0.0 && y <= dy) break;
        }
        return along <= 0.0 ? 0.0 : Math.max(0.0, d) / along;
    }

    // ------------------------------------------------------------------------------------------------ archers

    /** An arrow's speed off a skeleton's bow (blocks a tick, vanilla's), and how much of a target's own speed is led. */
    public static final double ARROW_SPEED = 1.6, LEAD = 0.85;
    /** The draw (ticks, vanilla's), and the tell that much before the release. */
    public static final int DRAW = 20, SHOT_TELL = 8;
    /** An archer backs off a player who comes nearer than this (blocks), and kicks one nearer than {@link #KICK}'s start. */
    public static final double BACK_OFF = 6.0;
    /** It shoots from up to this far (blocks, vanilla's), and closes in from further. */
    public static final double BOW_RANGE = 15.0;
    /** How hard a kick throws a player (blocks a tick along the floor, and up): about three blocks for one standing. */
    public static final double KICK_PUSH = 0.6, KICK_LIFT = 0.3;

    /**
     * Ticks an arrow at {@code speed} takes to fly {@code d} blocks, with the game's drag on it (0.99 a tick); an
     * arrow can't reach past {@code speed / 0.01}, so a far shot counts as {@code 60} ticks.
     */
    public static double flightTicks(double d, double speed) {
        double reach = 1.0 - d * 0.01 / speed;
        if (reach <= 0.05) return 60.0;
        return Math.min(60.0, Math.log(reach) / Math.log(0.99));
    }

    /**
     * Where an archer at {@code (sx, sy, sz)} aims at a target at {@code (tx, ty, tz)} moving at {@code (vx, vy, vz)}
     * (blocks a tick): where the target will be when the arrow gets there, {@link #LEAD} of the way (worked out twice,
     * since the flight's length depends on where it aims). Falling and jumping aren't led (only along the floor).
     */
    public static double[] lead(double sx, double sy, double sz, double tx, double ty, double tz, double vx, double vz, double speed) {
        double px = tx, pz = tz;
        for (int i = 0; i < 2; i++) {
            double d = Math.sqrt((px - sx) * (px - sx) + (pz - sz) * (pz - sz));
            double t = flightTicks(d, speed) * LEAD;
            px = tx + vx * t;
            pz = tz + vz * t;
        }
        return new double[]{px, ty, pz};
    }

    // ------------------------------------------------------------------------------------------------ waiting a turn

    /**
     * Where the mobs waiting their turn stand round their quarry, by rank among them (degrees from where the quarry
     * faces): behind it first, then its flanks, its front last. The ones behind are the danger.
     */
    static final double[] SLOTS = {180.0, 135.0, 225.0, 90.0, 270.0, 45.0, 315.0, 0.0};

    public static double slotDegrees(int rank) {
        if (rank < SLOTS.length) return SLOTS[Math.max(0, rank)];
        // more than eight: between those already taken
        return (SLOTS[rank % SLOTS.length] + 22.5 * (1 + rank / SLOTS.length)) % 360.0;
    }

    /**
     * The point {@code ring} blocks from {@code (qx, qz)} at {@code degrees} from the quarry's facing {@code yaw} (the
     * game's: 0 faces +z, 90 faces -x), along the floor.
     */
    public static double[] slotPoint(double qx, double qz, float yaw, double degrees, double ring) {
        double a = Math.toRadians(yaw + degrees);
        // the game's facing at yaw a is (-sin a, cos a)
        return new double[]{qx - Math.sin(a) * ring, qz + Math.cos(a) * ring};
    }

    // ------------------------------------------------------------------------------------------------ slimes

    /** A slime's squash before its slam (ticks), from up to this far (blocks), and the wait after one. */
    public static final int SQUASH = 8, SLAM_COOL_MIN = 28, SLAM_COOL_MAX = 46;
    public static final double SLAM_FROM = 5.0;

    /** A slime's slam lifts it this much (blocks a tick), more for a big one. */
    public static double slamLift(int size) { return 0.42 + 0.04 * Math.min(4, size); }

    /** And hurts what is within this of the middle of where it lands, along the floor (blocks). */
    public static double slamReach(int size, double width) { return width * 0.5 + 0.9 + 0.15 * Math.min(4, size); }

    /** How hard it throws what it hits (blocks a tick along the floor, and up). */
    public static double slamPush(int size) { return 0.45 + 0.1 * Math.min(4, size); }

    public static final double SLAM_LIFT_PUSH = 0.3;

    // ------------------------------------------------------------------------------------------------ creepers and endermen

    /** A lit creeper runs at its quarry this much faster than it walks (the card's recommended answer), while it is further than this. */
    public static final double CREEPER_RUSH = 1.4, CREEPER_RUSH_FROM = 1.3;

    /** An enderman blinks behind a quarry between these distances (blocks), now and then (ticks between). */
    public static final double BLINK_NEAR = 4.0, BLINK_FAR = 16.0, BLINK_BEHIND = 2.2;
    public static final int BLINK_COOL_MIN = 90, BLINK_COOL_MAX = 160;

    // ------------------------------------------------------------------------------------------------ witches

    /** A witch keeps between these distances of its quarry (blocks), and throws every so often (ticks, vanilla's). */
    public static final double WITCH_NEAR = 6.0, WITCH_FAR = 10.0;
    public static final int WITCH_WINDUP = 12, WITCH_INTERVAL = 60;
}
