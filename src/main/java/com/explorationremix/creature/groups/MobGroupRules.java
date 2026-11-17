package com.explorationremix.creature.groups;

/**
 * The rules of vanilla's hostile mobs in groups ({@link MobGroupSpawner}), as plain numbers so they can be tested
 * without a world: where each kind of group lives, how big it is, how many are kept round a player, and how a group
 * holds together.
 *
 * <p>Taran's ask (2026-11-17) and card answers: creepers and slimes in the green Upper Caves (lush caves, the Frost Fern
 * Grotto, Root Vaults, frozen Root Vaults and Silver Fern Rifts); zombies and skeletons together on the surface in woods
 * and savannas; spiders and witches, in separate groups, in dark oak forests; husks in deserts, strays on snowy open
 * land and drowned in rivers and oceans, each in groups of their own kind; nowhere else any of vanilla's hostile mobs
 * but a lone enderman now and then. Groups of 3 to 5 (witch covens of 2), two or three of each home round a player, that
 * roam together and fight together.
 */
public final class MobGroupRules {
    private MobGroupRules() {}

    /** Where a group lives. */
    public enum Home { WOODS, DARK_OAK, GREEN_CAVES, DESERT, SNOW, WATER }

    /** What a group is made of, and the home it lives in. */
    public enum Kind {
        /** Zombies and skeletons together, a skeleton leading (it heads for shade by day, and the zombies keep near it). */
        UNDEAD(Home.WOODS),
        SPIDERS(Home.DARK_OAK),
        WITCHES(Home.DARK_OAK),
        CREEPERS(Home.GREEN_CAVES),
        SLIMES(Home.GREEN_CAVES),
        HUSKS(Home.DESERT),
        STRAYS(Home.SNOW),
        DROWNED(Home.WATER);

        public final Home home;

        Kind(Home home) { this.home = home; }

        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    // ------------------------------------------------------------------------------------------------ distances

    /** A group counts as round a player while its leader is within this many blocks (a sphere). */
    public static final double COUNT_RADIUS = 96.0;
    /** Vanilla's: nothing comes out within this many blocks of a player or of the world's spawn point. */
    public static final double NEAR = 24.0;
    /** Vanilla's: a mob further than this from every player is removed at once, so none comes out further. */
    public static final double DESPAWN = 128.0;
    /** Vanilla's: within this many blocks of a player a mob is never removed. */
    public static final double KEEP = 32.0;
    /** Surface groups come out on a ring this far round the player (across the ground). */
    public static final double SURFACE_FAR = 80.0;
    /** Cave groups come out this far round the player across, and up to {@link #CAVE_HEIGHT} above or below. */
    public static final double CAVE_NEAR = 12.0, CAVE_FAR = 64.0, CAVE_HEIGHT = 32.0;

    // ------------------------------------------------------------------------------------------------ counts

    /** Groups of one home kept round a player, and the most there (Taran: "a few, 2 or 3 near you"). */
    public static final int KEPT = 2, MOST = 3;
    /**
     * The chance, at each look (once a second), that a home with fewer than {@link #KEPT} groups round a player gets a
     * new one, and that one with {@link #KEPT} gets its third. Only spots dark enough take a group (in the open at
     * night vanilla's light rule turns down about three leaders in four), so a look tries up to
     * {@link #GROUP_TRIES} places of the home. Groups that idle more than 32 blocks from every player despawn after
     * about a minute, as vanilla's monsters do, so a home round a player is short of a group now and then and fills
     * again in some 15 seconds.
     */
    public static final double FILL_CHANCE = 0.2, TOP_UP_CHANCE = 0.012;
    /** Places of the wanted home tried at one look. */
    public static final int GROUP_TRIES = 4;
    /** Endermen: at most this many round a player (in a sphere of {@link #DESPAWN}), and the chance at each look. */
    public static final int ENDERMEN_MOST = 1;
    public static final double ENDERMAN_CHANCE = 0.02;

    // ------------------------------------------------------------------------------------------------ sizes

    /** A group is {@link #SIZE_MIN} to {@link #SIZE_MAX} (Taran: "medium, 3 to 5"); a witch coven is two. */
    public static final int SIZE_MIN = 3, SIZE_MAX = 5, COVEN = 2;
    /** One dark oak group in this many is a witch coven; the rest are spider packs. */
    public static final double WITCH_SHARE = 0.25;
    /** One green cave group in this many is slimes; the rest are creepers. */
    public static final double SLIME_SHARE = 0.5;
    /** A cave spider's share of a dark oak pack is its share of the biome's spider spawns times this. */
    public static final double CAVE_SPIDER_DAMPING = 0.5;

    // ------------------------------------------------------------------------------------------------ holding together

    /** A member further than this from its leader walks back to it, until it is within {@link #CLOSE}. */
    public static final double LEASH = 8.0, CLOSE = 4.0;
    /** When one member turns on a player, the others within this many blocks of it turn on that player too. */
    public static final double ALERT = 24.0;
    /** Members come out this many blocks round the first across, and this far up or down. */
    public static final int SPREAD = 4, SPREAD_UP = 2, SPREAD_DOWN = 3;
    /** Trees must cover at least this share of the ground round a spot for it to count as the woods. */
    public static final double WOODS_CANOPY = 0.08;

    // ------------------------------------------------------------------------------------------------ rules

    /**
     * The home of a spot on the surface (at the ground of its column, out from under any cave roof), or null when none
     * of the groups lives there: dark oak forests first, then deserts, then the woods where trees cover enough of the
     * ground round it ({@code canopy} of at least {@code canopyMin}), then snowy land with strays.
     */
    public static Home surfaceHome(boolean darkOak, boolean husks, boolean woods, double canopy, double canopyMin, boolean strays) {
        if (darkOak) return Home.DARK_OAK;
        if (husks) return Home.DESERT;
        if (woods && canopy >= canopyMin) return Home.WOODS;
        if (strays) return Home.SNOW;
        return null;
    }

    /** The home of a spot in a cave, or null. */
    public static Home caveHome(boolean greenCave) {
        return greenCave ? Home.GREEN_CAVES : null;
    }

    /** The home of a spot in surface water (a river or the sea, open to the sky), or null. */
    public static Home waterHome(boolean drowned, int depth) {
        return drowned && depth >= 2 ? Home.WATER : null;
    }

    /** Whether a home with {@code nearby} groups round a player gets a new one now ({@code roll} uniform in [0, 1)). */
    public static boolean wantGroup(int nearby, int kept, int most, double roll) {
        if (nearby >= most) return false;
        return roll < (nearby < kept ? FILL_CHANCE : TOP_UP_CHANCE);
    }

    /** Whether a lone enderman comes out for a player with {@code nearby} endermen round it. */
    public static boolean wantEnderman(int nearby, double roll, double chance) {
        return nearby < ENDERMEN_MOST && roll < chance;
    }

    /** Which kind of group a home gets ({@code roll} uniform in [0, 1)). */
    public static Kind kindFor(Home home, double roll) {
        return switch (home) {
            case WOODS -> Kind.UNDEAD;
            case DARK_OAK -> roll < WITCH_SHARE ? Kind.WITCHES : Kind.SPIDERS;
            case GREEN_CAVES -> roll < SLIME_SHARE ? Kind.SLIMES : Kind.CREEPERS;
            case DESERT -> Kind.HUSKS;
            case SNOW -> Kind.STRAYS;
            case WATER -> Kind.DROWNED;
        };
    }

    /** How many a new group of {@code kind} has, from {@code min} to {@code max} (a witch coven: {@link #COVEN}). */
    public static int size(Kind kind, int min, int max, double roll) {
        if (kind == Kind.WITCHES) return COVEN;
        int lo = Math.max(2, Math.min(min, max)), hi = Math.max(lo, max);
        return lo + Math.min(hi - lo, (int) (Math.max(0.0, roll) * (hi - lo + 1)));
    }

    /** Whether what is left of a group still counts as one round a player: a lone straggler does not. */
    public static boolean stillAGroup(int members) {
        return members >= 2;
    }

    /** The fewest of a group that must find room to come out at all: all of a coven, else three (or all of a smaller one). */
    public static int fewest(Kind kind, int size) {
        return kind == Kind.WITCHES ? size : Math.min(size, 3);
    }

    /** How many skeletons a mixed group of {@code size} has: at least one of each, the split even. */
    public static int skeletons(int size, double roll) {
        if (size <= 1) return size;
        int n = 1 + (int) (Math.max(0.0, Math.min(0.999999, roll)) * (size - 1));
        return Math.max(1, Math.min(size - 1, n));
    }

    /**
     * Picks one of several weights ({@code roll} uniform in [0, 1)): the index whose share of the total covers the roll,
     * or -1 when every weight is zero.
     */
    public static int pick(double[] weights, double roll) {
        double total = 0.0;
        for (double w : weights) total += Math.max(0.0, w);
        if (total <= 0.0) return -1;
        double at = Math.max(0.0, Math.min(0.999999, roll)) * total;
        for (int i = 0; i < weights.length; i++) {
            at -= Math.max(0.0, weights[i]);
            if (at < 0.0) return i;
        }
        return weights.length - 1;
    }

    /**
     * Whether a spot suits a new group: at least {@link #NEAR} from every player ({@code nearestPlayer}) and from the
     * world's spawn point, and within {@link #DESPAWN} of the player it is for (else vanilla would remove it at once).
     */
    public static boolean spotOk(double nearestPlayer, double fromPlayer, double fromWorldSpawn) {
        return nearestPlayer >= NEAR && fromPlayer <= DESPAWN && fromWorldSpawn >= NEAR;
    }

    /** Whether a member walks back to its leader (it is past the leash). */
    public static boolean strayed(double fromLeader) {
        return fromLeader > LEASH;
    }

    /** Whether a member walking back has caught up. */
    public static boolean caughtUp(double fromLeader) {
        return fromLeader <= CLOSE;
    }

    /**
     * Whether a blow is held back: a grouped monster's arrow, trident, potion or bite does not hurt another grouped
     * monster, of its own group or the next, so groups never fall out among themselves (a skeleton shooting at you past
     * a zombie, when two groups close on you together). A creeper's blast still hurts everything round it, and a monster
     * in no group fights as in vanilla.
     */
    public static boolean spared(boolean attackerGrouped, boolean victimGrouped, boolean explosion) {
        return attackerGrouped && victimGrouped && !explosion;
    }

    /**
     * Whether vanilla's despawning of a member is held off: a member with its group (within {@link #KEEP} of a leader
     * that may itself despawn) goes when its leader does, all together, not one by one.
     */
    public static boolean heldByLeader(boolean isLeader, double fromLeader, boolean leaderMayDespawn) {
        return !isLeader && leaderMayDespawn && fromLeader <= KEEP;
    }

    /** The share of sampled columns with leaves over the ground. */
    public static double canopy(int covered, int sampled) {
        return sampled <= 0 ? 0.0 : (double) covered / sampled;
    }

    /**
     * Whether an entry of an overworld biome's monster list is taken off vanilla's own spawning: one of vanilla's
     * monsters ({@code namespace} minecraft, in the monster category) from the biome's own list. A structure's spawns
     * (witch huts, monuments, outposts) are not the biome's own and stay, as do other mods' monsters and creatures a
     * biome lists among its monsters (the pack's jungles list ocelots there).
     */
    public static boolean takenOff(String namespace, boolean monsterCategory, boolean biomeOwn) {
        return biomeOwn && monsterCategory && "minecraft".equals(namespace);
    }
}
