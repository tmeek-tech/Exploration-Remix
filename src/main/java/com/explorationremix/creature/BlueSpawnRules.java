package com.explorationremix.creature;

/**
 * The rules of the Blue Reaches' spawner ({@link BlueReachesSpawner}), as plain numbers so they can be tested without
 * a world: which players it works round, when a Skein or a swarm of fireflies moves in near one, how far off it brings
 * them out, and which places each may live in.
 *
 * <p>The Skeins ({@link SkeinHomes}) and the firefly swarms ({@link FireflyHomes}, 0.19.2) are set down with the world
 * and stay. Round a player in the Blue Reaches (in Survival or Adventure) the spawner brings a new Skein out only now
 * and then, where none is left within {@link #REFILL_RADIUS} blocks (and never where {@link #SKEIN_MAX} are within
 * {@link #SKEIN_RADIUS} already), and a new swarm now and then where fewer than {@link #swarmsKept} are left within
 * {@link #SWARM_RADIUS} (six in the Deep Blue Rift and the Asurine Geodes): a swarm a Skein ate comes back slowly, out
 * of sight, and the caves of a world made before 0.19.2 fill in. Each comes out {@link #NEAR} blocks or further from
 * every player, out of every player's sight, and within the radius it is counted in (a sphere: height counts too), so
 * it counts as soon as it is out. The Asurine Geodes are a refuge: fireflies live there, Skeins never. A swarm is 30 to
 * 60 fireflies, and now and then ({@link #HUGE_SWARMS}) a huge one of 120 to 160 (0.16.0, Taran's pick).
 *
 * <p>The Deep Dark is neither's (0.19.8, Taran's ask: "keep the skein and the blue fireflies from spawning in the Deep
 * Dark"): it is left to the Warden, dark and still. No Skein or swarm is set down there with the world or brought out
 * there, and a player standing in it is kept no swarms round them; the spawner still looks round them for the rift's
 * Skeins, which come out only on the rift's own rock.
 */
public final class BlueSpawnRules {
    private BlueSpawnRules() {}

    /** The Blue Reaches' biomes, and everywhere else. */
    public enum Place { RIFT, GEODES, DEEP_DARK, OTHER }

    public static final String RIFT_ID = "explorationremix:deep_blue_rift", GEODES_ID = "explorationremix:asurine_geodes",
            DEEP_DARK_ID = "minecraft:deep_dark";
    /** The Blue Reaches' heights when the deep world cannot say (its first layer, between the first two bands). */
    public static final int TOP = -81, BOTTOM = -272;

    /** A new Skein comes out within this of the player it is for, and never where this many are within it already. */
    public static final double SKEIN_RADIUS = 48.0;
    public static final int SKEIN_MAX = 3;
    /**
     * The slow refill (0.17.0): with no Skein left within this of a player, the caves round them have lost theirs (or
     * were made before Skeins were set down with the world), and one may come out. The Skeins set down with the world
     * leave several within it almost everywhere, so a refill comes only where they are gone.
     */
    public static final double REFILL_RADIUS = 96.0;
    /** The chance, at each look (every two seconds), that one comes out for such a player: about one every two minutes. */
    public static final double REFILL_CHANCE = 1.0 / 60.0;
    /** Swarms within this radius; the counts are by the player's place. */
    public static final double SWARM_RADIUS = 40.0;
    /**
     * The chance, at each look (every two seconds), that a swarm comes out for a player with fewer than
     * {@link #swarmsKept} near: about one a minute while the caves round them are short of their swarms.
     */
    public static final double SWARM_REFILL_CHANCE = 1.0 / 30.0;
    /** Things come out at least this far from every player, and no further than these from the one they are for. */
    public static final double NEAR = 20.0, SKEIN_FAR = 46.0, SWARM_FAR = 38.0;
    /** A swarm is {@link #SWARM_MIN_COUNT} to {@link #SWARM_MAX_COUNT} fireflies, or this share of them a huge one of {@link #HUGE_MIN_COUNT} to {@link #HUGE_MAX_COUNT}. */
    public static final int SWARM_MIN_COUNT = 30, SWARM_MAX_COUNT = 60, HUGE_MIN_COUNT = 120, HUGE_MAX_COUNT = 160;
    public static final double HUGE_SWARMS = 0.125;

    public static Place place(String biomeId) {
        return switch (biomeId) {
            case RIFT_ID -> Place.RIFT;
            case GEODES_ID -> Place.GEODES;
            case DEEP_DARK_ID -> Place.DEEP_DARK;
            default -> Place.OTHER;
        };
    }

    public static boolean inBand(double y, int top, int bottom) {
        return y <= top + 1 && y >= bottom;
    }

    /** Whether the spawner works round this player: in Survival or Adventure, in one of the Blue Reaches' biomes. */
    public static boolean playerCounts(boolean spectator, boolean creative, Place place, double y, int top, int bottom) {
        return !spectator && !creative && place != Place.OTHER && inBand(y, top, bottom);
    }

    /** Skeins live in the Deep Blue Rift only: the refuge is never a Skein's, and since 0.19.8 nor is the Deep Dark. */
    public static boolean skeinsLive(Place p) {
        return p == Place.RIFT;
    }

    /** Swarms live in the rift and the refuge (its geodes glimmer with them), never in the Deep Dark (0.19.8). */
    public static boolean swarmsLive(Place p) {
        return p == Place.RIFT || p == Place.GEODES;
    }

    /**
     * Whether to look for a spot to bring a Skein out for a player with {@code nearby} within {@link #REFILL_RADIUS}:
     * only when there is none, and then now and then ({@code roll} under {@link #REFILL_CHANCE}), or at every look
     * once one is {@code due} (the roll came up but no spot out of every player's sight was found yet: the time
     * between them is the roll's, not how hard spots are to find there); never in Peaceful or without mob spawning
     * ({@code mobSpawning}: the doMobSpawning rule, and the server spawning monsters at all).
     */
    public static boolean wantSkein(int nearby, boolean peaceful, boolean mobSpawning, boolean due, double roll) {
        return !peaceful && mobSpawning && nearby == 0 && (due || roll < REFILL_CHANCE);
    }

    /**
     * The swarms round a player in this place (within {@link #SWARM_RADIUS}) the world is made with, about: six to ten
     * in the rift and the geodes (0.19.2, Taran's pick: twice as many as the old spawner kept there), none in the Deep
     * Dark (0.19.8). The spawner brings one out where fewer than the first are left; the second is the most it ever
     * brings one out among.
     */
    public static int swarmsKept(Place p) {
        return swarmsLive(p) ? 6 : 0;
    }

    public static int swarmsMax(Place p) {
        return swarmsLive(p) ? 10 : 0;
    }

    /**
     * The swarms round a player standing in the Deep Blue Rift's great chasm (0.20.8, Taran's ask: "The big Deep Blue
     * Rift has too many blue fireflies but the smaller tunnels have the perfect amount"), as the chasm keeps only some
     * of its swarms ({@link FireflyHomes#CHASM_KEEP}): it leaves half the spots in it 9 or more within
     * {@link #SWARM_RADIUS}, a tenth 5 or fewer (in the test pack). The spawner brings one out only where fewer than the
     * first are left, so it tops the chasm up only where a Skein has eaten its swarms, never back to what it had.
     */
    public static final int CHASM_KEPT = 4, CHASM_MAX = 9;

    /** {@link #swarmsKept(Place)} for a player in {@code p}; {@code chasm}, standing in the rift's great chasm. */
    public static int swarmsKept(Place p, boolean chasm) {
        return chasm && p == Place.RIFT ? CHASM_KEPT : swarmsKept(p);
    }

    /** {@link #swarmsMax(Place)} for a player in {@code p}; {@code chasm}, standing in the rift's great chasm. */
    public static int swarmsMax(Place p, boolean chasm) {
        return chasm && p == Place.RIFT ? CHASM_MAX : swarmsMax(p);
    }

    /**
     * Whether to look for a spot to bring a swarm out for a player in {@code p} with {@code nearby} within
     * {@link #SWARM_RADIUS}: only when fewer than {@link #swarmsKept} are left, and then now and then ({@code roll}
     * under {@link #SWARM_REFILL_CHANCE}), or at every look once one is {@code due} (the roll came up but no spot out
     * of every player's sight was found yet); never without mob spawning ({@code mobSpawning}: the doMobSpawning rule,
     * and the server spawning animals at all).
     */
    public static boolean wantSwarm(Place p, int nearby, boolean mobSpawning, boolean due, double roll) {
        return wantSwarm(p, false, nearby, mobSpawning, due, roll);
    }

    /** {@link #wantSwarm(Place, int, boolean, boolean, double)} for a player standing in the rift's great chasm or not ({@code chasm}). */
    public static boolean wantSwarm(Place p, boolean chasm, int nearby, boolean mobSpawning, boolean due, double roll) {
        return mobSpawning && nearby < swarmsKept(p, chasm) && (due || roll < SWARM_REFILL_CHANCE);
    }

    /**
     * Whether a Skein may come out at a spot: a place it lives in, in the Blue Reaches, at least {@link #NEAR} from every
     * player, within {@link #SKEIN_RADIUS} of the player it is for ({@code fromPlayer}, straight through the rock, up and
     * down too: the sphere it is counted in), and with fewer than {@link #SKEIN_MAX} Skeins already within
     * {@link #SKEIN_RADIUS} of it.
     */
    public static boolean skeinSpot(Place p, double y, int top, int bottom, double nearestPlayer, double fromPlayer, int skeinsThere) {
        return skeinsLive(p) && inBand(y, top, bottom) && nearestPlayer >= NEAR && fromPlayer <= SKEIN_RADIUS && skeinsThere < SKEIN_MAX;
    }

    /**
     * Whether a swarm may come out at a spot: a place swarms live in, in the Blue Reaches, at least {@link #NEAR} from
     * every player, and within {@link #SWARM_RADIUS} of the player it is for ({@code fromPlayer}, up and down too, as for
     * a Skein).
     */
    public static boolean swarmSpot(Place p, double y, int top, int bottom, double nearestPlayer, double fromPlayer) {
        return swarmsLive(p) && inBand(y, top, bottom) && nearestPlayer >= NEAR && fromPlayer <= SWARM_RADIUS;
    }

    /**
     * {@link #swarmSpot(Place, double, int, int, double, double)}, and a spot in the rift's great chasm ({@code inChasm})
     * only for a player standing in it too ({@code forChasm}, 0.20.8): the chasm keeps its own few swarms, and a player
     * in the tunnels by it is never brought swarms out there.
     */
    public static boolean swarmSpot(Place p, boolean inChasm, boolean forChasm, double y, int top, int bottom, double nearestPlayer, double fromPlayer) {
        return (!inChasm || forChasm) && swarmSpot(p, y, top, bottom, nearestPlayer, fromPlayer);
    }

    /** How far from its player a spot is tried: {@link #NEAR} to {@code far}, by a uniform {@code roll}. */
    public static double ringDistance(double roll, double far) {
        return NEAR + Math.max(0.0, Math.min(1.0, roll)) * (far - NEAR);
    }

    /**
     * How many fireflies a new swarm has, by a uniform {@code roll}: under {@link #HUGE_SWARMS} a huge swarm, else an
     * ordinary one, each size of its range as likely as the next.
     */
    public static int swarmSize(double roll) {
        double r = Math.max(0.0, Math.min(1.0, roll));
        if (r < HUGE_SWARMS) return between(r / HUGE_SWARMS, HUGE_MIN_COUNT, HUGE_MAX_COUNT);
        return between((r - HUGE_SWARMS) / (1.0 - HUGE_SWARMS), SWARM_MIN_COUNT, SWARM_MAX_COUNT);
    }

    private static int between(double u, int lo, int hi) {
        return lo + Math.min(hi - lo, (int) (u * (hi - lo + 1)));
    }
}
