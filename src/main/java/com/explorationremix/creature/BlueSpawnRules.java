package com.explorationremix.creature;

/**
 * The rules of the Blue Reaches' spawner ({@link BlueReachesSpawner}), as plain numbers so they can be tested without
 * a world: which players it works round, how many Skeins and firefly swarms it keeps near each, how far off it brings
 * them out, and which places each may live in.
 *
 * <p>Round a player in the Blue Reaches (in Survival or Adventure) it keeps one or two Skeins within {@link
 * #SKEIN_RADIUS} blocks, never more than {@link #SKEIN_MAX} there, and three to five swarms within {@link
 * #SWARM_RADIUS} (one or two in the Deep Dark, where the fireflies are few). Each comes out {@link #NEAR} blocks or
 * further from every player, and within the radius it is counted in (a sphere: height counts too), so it counts as
 * soon as it is out. The Asurine Geodes are a refuge: fireflies live there, Skeins never.
 */
public final class BlueSpawnRules {
    private BlueSpawnRules() {}

    /** The Blue Reaches' biomes, and everywhere else. */
    public enum Place { RIFT, GEODES, DEEP_DARK, OTHER }

    public static final String RIFT_ID = "explorationremix:deep_blue_rift", GEODES_ID = "explorationremix:asurine_geodes",
            DEEP_DARK_ID = "minecraft:deep_dark";
    /** The Blue Reaches' heights when the deep world cannot say (its first layer, between the first two bands). */
    public static final int TOP = -81, BOTTOM = -272;

    /** Skeins: one is always wanted, a second now and then, never more than three in the radius. */
    public static final double SKEIN_RADIUS = 48.0;
    public static final int SKEIN_KEEP = 1, SKEIN_WANT = 2, SKEIN_MAX = 3;
    /** The chance, at each look, that a second Skein comes out while there is only one. */
    public static final double SKEIN_TOP_UP = 0.2;
    /** Swarms within this radius; the counts are by the player's place. */
    public static final double SWARM_RADIUS = 40.0;
    public static final double SWARM_TOP_UP = 0.3;
    /** Things come out at least this far from every player, and no further than these from the one they are for. */
    public static final double NEAR = 20.0, SKEIN_FAR = 46.0, SWARM_FAR = 38.0;
    /** A swarm in the Deep Dark comes out only at this share of the spots found there. */
    public static final double DEEP_DARK_SWARMS = 0.35;
    /** A swarm is 8 to 24 fireflies. */
    public static final int SWARM_MIN_COUNT = 8, SWARM_MAX_COUNT = 24;

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

    /** The refuge is never a Skein's. */
    public static boolean skeinsLive(Place p) {
        return p == Place.RIFT || p == Place.DEEP_DARK;
    }

    public static boolean swarmsLive(Place p) {
        return p != Place.OTHER;
    }

    /**
     * Whether to bring out a Skein for a player with {@code nearby} within {@link #SKEIN_RADIUS}: always below one,
     * now and then ({@code roll} under {@link #SKEIN_TOP_UP}) below two; never in Peaceful or without mob spawning
     * ({@code mobSpawning}: the doMobSpawning rule, and the server spawning monsters at all).
     */
    public static boolean wantSkein(int nearby, boolean peaceful, boolean mobSpawning, double roll) {
        if (peaceful || !mobSpawning || nearby >= SKEIN_MAX) return false;
        if (nearby < SKEIN_KEEP) return true;
        return nearby < SKEIN_WANT && roll < SKEIN_TOP_UP;
    }

    /** The fewest swarms kept round a player in this place (they are few in the Deep Dark). */
    public static int swarmsKept(Place p) {
        return p == Place.DEEP_DARK ? 1 : p == Place.OTHER ? 0 : 3;
    }

    public static int swarmsMax(Place p) {
        return p == Place.DEEP_DARK ? 2 : p == Place.OTHER ? 0 : 5;
    }

    /**
     * Whether to bring out a swarm for a player in {@code p} with {@code nearby} within {@link #SWARM_RADIUS}; never
     * without mob spawning ({@code mobSpawning}: the doMobSpawning rule, and the server spawning animals at all).
     */
    public static boolean wantSwarm(Place p, int nearby, boolean mobSpawning, double roll) {
        if (!mobSpawning || nearby >= swarmsMax(p)) return false;
        if (nearby < swarmsKept(p)) return true;
        return roll < SWARM_TOP_UP;
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
     * a Skein); {@code roll} thins them out in the Deep Dark.
     */
    public static boolean swarmSpot(Place p, double y, int top, int bottom, double nearestPlayer, double fromPlayer, double roll) {
        if (!swarmsLive(p) || !inBand(y, top, bottom) || nearestPlayer < NEAR || fromPlayer > SWARM_RADIUS) return false;
        return p != Place.DEEP_DARK || roll < DEEP_DARK_SWARMS;
    }

    /** How far from its player a spot is tried: {@link #NEAR} to {@code far}, by a uniform {@code roll}. */
    public static double ringDistance(double roll, double far) {
        return NEAR + Math.max(0.0, Math.min(1.0, roll)) * (far - NEAR);
    }

    /** How many fireflies a new swarm has. */
    public static int swarmSize(double roll) {
        return SWARM_MIN_COUNT + Math.min(SWARM_MAX_COUNT - SWARM_MIN_COUNT, (int) (roll * (SWARM_MAX_COUNT - SWARM_MIN_COUNT + 1)));
    }
}
