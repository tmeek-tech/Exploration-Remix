package com.explorationremix.client;

/**
 * The numbers of an eruption as each client plays it (0.9.0, spec 5.5), kept apart from Minecraft so a test can follow
 * them, as {@link Shake} and {@link SmokePuff} are: how hard the fountain throws at each moment of the burst, how the
 * roar swells and dies, how loud the boom and the roar are from afar, when the vent's rumble may still start, how a lava
 * bomb's whistle rises and falls, and how the far particles are thinned and grown; and (the pack's look fixes) how many
 * lava gobs, fireballs and ash puffs make the spectacle, how the jet fans out, how long a gob flies, how the start's
 * flash dies and how hard the plume is thrown. Time is in ticks into the burst (the server's game time against the
 * eruption's start, or the held moment), so a slow client and a held eruption play the same moment as the server.
 */
final class Fountain {
    private Fountain() {}

    /** The fountain throws at full strength for this many ticks from the burst's start, then gusts. */
    static final int FULL_TICKS = 20;
    /** While it gusts it throws between this share of its strength and all of it. */
    static final double GUST_LOW = 0.6;
    /** It dies away over the burst's last this many ticks. */
    static final int FADE_TICKS = 30;
    /** The roar swells over this many ticks from the burst's start and dies away over its last {@link #ROAR_FADE}. */
    static final int ROAR_RISE = 10, ROAR_FADE = 40;
    /** Spatter thrown up a tick at full strength (times the eruption's strength and its size): the fine spray round the gobs. */
    static final double SPATTER = 24;
    /** Flames and embers at the mouth a tick at full strength. */
    static final double FLAMES = 6, EMBERS = 10;
    /**
     * A gob of spatter flies under this much of the particles' gravity (each tick its upward speed loses 0.04 times it)
     * and keeps this share of its speed each tick: the particle's own physics ({@code CaveParticles.Spatter}).
     */
    static final double SPATTER_GRAVITY = 0.9, SPATTER_FRICTION = 0.98;
    /**
     * The fountain's spatter leaves the crater floor at this speed, in blocks a tick, times the root of the eruption's
     * size, within {@link #SPATTER_CONE} degrees of straight up: under the particle's own gravity and drag it climbs 10
     * to 26 blocks (14 to 36 for a woken eruption 1.5 times as big).
     */
    static final double SPEED_MIN = 1.0, SPEED_MAX = 1.75, SPATTER_CONE = 15;
    /** Nearer the camera than this, particles go through the game's own limits; beyond it they are forced and thinned by hand. */
    static final double NEAR = 32;
    /** Beyond {@link #NEAR}, the forced particles thin out with the distance from this far. */
    static final double THIN_FROM = 48;
    /** A plume puff every this many ticks of a fountain of size 1 (fewer ticks apart as it grows). */
    static final double PLUME_TICKS = 5;
    /** The plume puffs on this long after the burst, ticks. */
    static final int PLUME_TAIL = 200;
    /** A lava bomb's burst nearer the player than this jolts the view, at most this many degrees. */
    static final double BOMB_JOLT_REACH = 32, BOMB_JOLT = 0.4;
    /** The vent's rumble starts only with at least this many ticks of the warning still to come (or half of a shorter one). */
    static final int RUMBLE_LEFT = 20;

    // ------------------------------------------------------------------------------------------------ the spectacle
    // (0.9.0 pack look fixes: the fountain read as a spark spray, the plume and the warning as the Caldera's own smoke)

    /**
     * Lava gobs ({@code CaveParticles.LavaGob}, half a block to a block across) thrown up a tick at full strength (times
     * the eruption's strength and size): the fountain's body, climbing as the spatter does and raining back down round
     * the stack.
     */
    static final double GOBS = 24;
    /** Most gobs leave within this many degrees of straight up (the jet); {@link #SPRAY_SHARE} of them within {@link #SPRAY_CONE} (the fan). */
    static final double CORE_CONE = 9, SPRAY_CONE = 26, SPRAY_SHARE = 0.35;
    /** A gob lives until it has fallen this far under the crater floor it left (down the stack's flanks), ticks capped at {@link #GOB_LIFE_MAX}. */
    static final double GOB_DROP = 24;
    static final int GOB_LIFE_MAX = 160;
    /** Fireballs at the mouth a tick at full strength, while the fountain throws. */
    static final double FIRE = 3;
    /** The burst's start: a flash of fire and lava at the mouth dying away over this many ticks. */
    static final int FLASH_TICKS = 16;
    /** At the flash's height: fireballs and gobs (thrown wider, up to {@link #FLASH_CONE} degrees) a tick, on top of the fountain's own. */
    static final double FLASH_FIRE = 16, FLASH_GOBS = 40, FLASH_CONE = 45;
    /** Dark ash puffs a tick at full strength (times the eruption's size): the plume's column. */
    static final double ASH = 1.0;
    /** A plume puff leaves the mouth this fast, blocks a tick (times the root of the strength), then slows to the smoke's own rise. */
    static final double ASH_THRUST_MIN = 0.45, ASH_THRUST_MAX = 0.8;
    /** The warning: dark ash puffs a tick, leaving the mouth this fast (it billows 7 to 15 blocks over the rim, then creeps on). */
    static final double WARN_ASH = 1.2, WARN_THRUST_MIN = 0.2, WARN_THRUST_MAX = 0.45;
    /**
     * The warning: the chance a tick of a lava pop (a spot of the crater floor flings a few gobs up 4 to 11 blocks, over
     * the crater's rim, with a flare of fire at the mouth), and fire flickering at the mouth a tick.
     */
    static final double POP_CHANCE = 0.2;
    static final int POP_GOBS = 8;
    static final double POP_SPEED_MIN = 0.7, POP_SPEED_MAX = 1.0, WARN_FIRE = 0.3;

    /**
     * The start flash's share {@code tb} ticks into the burst: 1 at its first tick, easing to nothing over
     * {@link #FLASH_TICKS} ({@code (1 - tb / 16)^2}); none before the burst or after.
     */
    static double flash(double tb) {
        if (tb < 0 || tb >= FLASH_TICKS) return 0;
        double u = 1 - tb / FLASH_TICKS;
        return u * u;
    }

    /**
     * A gob's tilt from straight up, degrees, for draws {@code u} and {@code w} (0 to 1): {@link #SPRAY_SHARE} of them
     * ({@code u} under it) fan out within {@link #SPRAY_CONE}, the rest stay in the jet within {@link #CORE_CONE}, each
     * spread evenly over the cone's disc (the square root of {@code w}).
     */
    static double tilt(double u, double w) { return (u < SPRAY_SHARE ? SPRAY_CONE : CORE_CONE) * Math.sqrt(Math.max(0, w)); }

    /**
     * Ticks a gob thrown up at {@code vy} blocks a tick takes under the spatter's physics to fall {@code drop} blocks under
     * where it left (it has then come back down past the crater floor and down the flanks): its life, so it rains back
     * down rather than going out at its top. At most {@link #GOB_LIFE_MAX}.
     */
    static int flightTicks(double vy, double drop) {
        double y = 0, v = vy;
        for (int t = 1; t <= GOB_LIFE_MAX; t++) {
            v -= 0.04 * SPATTER_GRAVITY;
            y += v;
            v *= SPATTER_FRICTION;
            if (y <= -drop) return t;
        }
        return GOB_LIFE_MAX;
    }

    /** A plume puff's speed leaving the mouth, blocks a tick, for {@code u} from 0 to 1, of an eruption at {@code strength}. */
    static double ashThrust(double u, double strength) {
        return (ASH_THRUST_MIN + (ASH_THRUST_MAX - ASH_THRUST_MIN) * u) * Math.sqrt(Math.max(0, strength));
    }

    /**
     * How far a smoke puff thrust up at {@code vy} climbs on its thrust alone, blocks: its speed eases toward the smoke's
     * own slow rise at {@link SmokePuff#RESPONSE} a tick, so the thrust carries it about {@code vy / 0.03}.
     */
    static double thrustRise(double vy) { return vy / SmokePuff.RESPONSE; }

    /**
     * Whether the vent's rumble may still start {@code t} ticks into an eruption that warns for {@code pre} ticks: with
     * at least {@link #RUMBLE_LEFT} ticks of the warning to come, or, for a warning shorter than twice that (warnSeconds
     * under 2), half of it, so a short warning still rumbles when it is heard from its start. Never once the burst has
     * begun, and never with no warning at all.
     */
    static boolean rumbleInTime(double t, int pre) {
        double left = pre - t;
        return left > 0 && left >= Math.min(RUMBLE_LEFT, pre / 2.0);
    }

    /**
     * How hard the fountain throws {@code t} ticks into a burst of {@code burst} ticks, 0 to 1: in full for its first
     * {@link #FULL_TICKS}, then gusting between {@link #GUST_LOW} and 1 (a slow wander drawn from the eruption's
     * {@code seed}, so every client and every frame of a held moment agree), dying away over its last
     * {@link #FADE_TICKS}. Nothing outside the burst.
     */
    static double intensity(double t, int burst, long seed) {
        if (t < 0 || t >= burst) return 0;
        double k = t < FULL_TICKS ? 1 : gust(t - FULL_TICKS, seed);
        return k * Math.min(1, (burst - t) / FADE_TICKS);
    }

    /**
     * The gust {@code t} ticks after the burst's first full second: two slow incommensurate waves (about a second and
     * two and a half seconds long) between {@link #GUST_LOW} and 1, starting from 1 so it never drops at once.
     */
    static double gust(double t, long seed) {
        double a = Shake.phase((int) (seed ^ seed >>> 32), 11), b = Shake.phase((int) (seed ^ seed >>> 32), 12);
        double w = 0.6 * Math.sin(0.31 * t + a) + 0.4 * Math.sin(0.13 * t + b);
        // from 1 at the end of the full second, easing into the wander over the next second
        double into = Math.min(1, t / FULL_TICKS);
        double g = 1 - (1 - GUST_LOW) * 0.5 * (1 + w);
        return 1 + (g - 1) * into;
    }

    /** The roar's share of its loudness {@code t} ticks into a burst of {@code burst} ticks: up over 10 ticks, down over the last 40. */
    static double roarEnvelope(double t, int burst) {
        if (t < 0 || t >= burst) return 0;
        return Math.min(1, t / ROAR_RISE) * Math.min(1, (burst - t) / ROAR_FADE);
    }

    /** The boom's volume heard {@code d} blocks from the vent, of an eruption heard {@code range} blocks off: never under 0.15. */
    static double boomVolume(double d, int range) { return clamp(1.2 - d / Math.max(1, range), 0.15, 1); }

    /** The roar's volume heard {@code d} blocks from the vent, as far as the boom: never under 0.1 within its range. */
    static double roarVolume(double d, int range) { return clamp(1.2 - d / Math.max(1, range), 0.1, 1); }

    /**
     * The share of a spawner's particles shown at {@code d} blocks from the camera: all of them within {@link #NEAR}
     * (the game thins those itself by the Particles option); beyond it {@code share} ({@link SmokeBudget#share}, the
     * option's own share for forced particles), thinning further as {@code 48 / d} past {@link #THIN_FROM}.
     */
    static double farShare(double d, double share) {
        if (d <= NEAR) return 1;
        return share * Math.min(1, THIN_FROM / d);
    }

    /** How many times its size a far spatter is drawn at {@code d} blocks, so it keeps more of its size: {@code max(1, d / 32)}. */
    static double farSize(double d) { return Math.max(1, d / NEAR); }

    /**
     * How many times its size a far eruption's ash puff is drawn at {@code d} blocks: {@code sqrt(farSize(d))}, about
     * twice at 120 blocks, so the dark column keeps its width through the haze without swamping the view.
     */
    static double farAshSize(double d) { return Math.sqrt(farSize(d)); }

    /** Ticks between two plume puffs of a fountain {@code scale} times its size: {@code round(5 / scale)}, at least 1. */
    static int plumeEvery(double scale) { return Math.max(1, (int) Math.round(PLUME_TICKS / Math.max(0.01, scale))); }

    /** A spatter's launch speed, blocks a tick, for {@code u} from 0 to 1, of a fountain {@code scale} times its size. */
    static double speed(double u, double scale) { return (SPEED_MIN + (SPEED_MAX - SPEED_MIN) * u) * Math.sqrt(Math.max(0, scale)); }

    /**
     * How high a spatter thrown straight up at {@code speed} climbs under its own physics, in blocks: each tick its
     * upward speed loses {@code 0.04 * gravity}, it moves, and it keeps {@link #SPATTER_FRICTION} of its speed (the
     * order of {@code Particle.tick}).
     */
    static double apex(double speed) {
        double y = 0, v = speed, top = 0;
        for (int t = 0; t < 400; t++) {
            v -= 0.04 * SPATTER_GRAVITY;
            y += v;
            v *= SPATTER_FRICTION;
            if (y > top) top = y;
            if (v <= 0 && y < top) break;
        }
        return top;
    }

    /** The view's jolt, in degrees, of a lava bomb's burst {@code d} blocks from the player: up to 0.4 next to it, none from 32. */
    static double bombJolt(double d) { return d >= BOMB_JOLT_REACH ? 0 : BOMB_JOLT * (1 - Math.max(0, d) / BOMB_JOLT_REACH); }

    /** A lava bomb's whistle's pitch at upward speed {@code vy} (blocks a tick): higher while it climbs, falling as it comes down. */
    static double whistlePitch(double vy) { return 0.95 + 0.15 * clamp(vy, -1, 1); }

    /** {@code n} particles as a whole count, the fraction kept by the draw {@code u} (0 to 1), so a share of one still shows now and then. */
    static int count(double n, double u) {
        if (!(n > 0)) return 0;
        int whole = (int) n;
        return whole + (u < n - whole ? 1 : 0);
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
}
