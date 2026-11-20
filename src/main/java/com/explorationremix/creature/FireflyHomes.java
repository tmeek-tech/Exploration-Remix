package com.explorationremix.creature;

import com.explorationremix.creature.BlueSpawnRules.Place;
import com.explorationremix.world.terrain.Hashing;

/**
 * Where the Blue Reaches' firefly swarms live from the start (0.19.2, Taran's ask: "make the blue fireflies spawn when
 * starting up a world"): the rules of the world-gen feature that sets them down as each chunk is made
 * ({@code world.feature.FirefliesFeature}), as plain numbers so they can be tested without a world. Like the Skeins
 * ({@link SkeinHomes}) they are there before any player comes and stay for good: each swarm keeps the cave it was set
 * down in, resting on its rock while nobody is near ({@link FireflySwarm#REST_RANGE}), and the spawner brings a new one
 * out, out of sight, only where the caves round a player have lost theirs ({@link BlueSpawnRules#wantSwarm}).
 *
 * <p>Twice as many as the old spawner kept round a player (Taran's pick, 19 Nov 2026): six to ten within
 * {@link BlueSpawnRules#SWARM_RADIUS} blocks of a player in the Deep Blue Rift or the Asurine Geodes
 * ({@link BlueSpawnRules#swarmsKept}, {@link BlueSpawnRules#swarmsMax}); none in the Deep Dark, which is left dark
 * (0.19.8, Taran's ask; it had two to four round a player before). They are drawn to water and
 * glowbells (Taran's pick): open cave air with water or a glowbell near it ({@link #DRAW_REACH}) is {@link #DRAW}
 * times as likely to be a swarm's home as open air over bare rock, so most of the rift's swarms hang over its streams,
 * pools and glowbells and the bare halls get fewer, but not none.
 *
 * <p>Each chunk tries {@link #TRIES} random spots in the Blue Reaches under it (about one in fifty is open cave air with
 * room round it). A try counts where a swarm has room ({@link FireflySwarm#openRound}: open air for a block round) in a
 * place swarms live, and becomes a home by a roll against {@link #chance}: so the swarms follow how much open cave
 * there is, none in solid rock and several in a big hall. A home's swarm is set down resting on the best patch of rock
 * within {@link #PERCH_REACH} blocks of it, or the try is given up (so a tall hall's walls are homes all the way up,
 * and the middle of its air is not). Two homes in one chunk keep {@link #APART} blocks apart, their patches
 * {@link #PERCH_APART}, and a chunk holds at most {@link #MAX_PER_CHUNK}.
 *
 * <p>The numbers were set in the test pack (seed 8675309, the 625 chunks round spawn, 19 Nov 2026): 0.8 swarms a chunk;
 * at random spots a player could stand on, within 40 blocks, 7 on average in the rift (half the spots 4 or more, a tenth
 * 17 or more: its narrow ways have few, its big halls by the streams many), 9 in the geodes; 53% of the rift's swarms
 * by water or glowbells. (The Deep Dark then had 3.4. Its tries make no home since 0.19.8; every try still draws what
 * it drew, so the rift's and the geodes' swarms stay where they were, but for the few a Deep Dark home had kept away.)
 */
public final class FireflyHomes {
    private FireflyHomes() {}

    /** Random spots tried in each chunk, through the Blue Reaches' whole height. */
    public static final int TRIES = 2880;
    /** Tries keep this far inside the chunk, so the rock they read round them stays in reach of the chunk's generation. */
    public static final int EDGE = 3;
    /** The most swarms one chunk is home to, and how far apart two of them keep. */
    public static final int MAX_PER_CHUNK = 8;
    public static final double APART = 7.0;
    /** A home's swarm rests on a patch of rock within this many blocks of it. */
    public static final int PERCH_REACH = 4;
    /**
     * Two swarms of one chunk rest at least this far apart, patch middle to patch middle, so they never sit on one
     * another (a swarm of 45 covers rock about 3.5 blocks round its middle, {@code firefly.Perches#reach}).
     */
    public static final double PERCH_APART = 6.0;
    /** Water or a glowbell within this many blocks sideways, and this far down or up, draws the fireflies. */
    public static final int DRAW_REACH = 8, DRAW_DOWN = 10, DRAW_UP = 5;
    /**
     * How much likelier a spot by water or glowbells is to be a home than one over bare rock: set so that about half
     * the rift's swarms are by them, though the air by its water is only a tenth of its open air or less (the spots by
     * water fill up first: two homes keep {@link #APART}).
     */
    public static final double DRAW = 22.0;
    /**
     * The chance that a try over bare rock is a home, by place, each set in the test pack so a player there has about
     * the counts of {@link BlueSpawnRules#swarmsKept} to {@link BlueSpawnRules#swarmsMax} round them: the geodes' wide
     * dry halls give many tries room, so theirs is lower than the rift's. The Deep Dark has none.
     */
    public static final double RIFT_BARE = 0.045, GEODES_BARE = 0.012;

    private static final long SALT = 0x46697265666C7921L;      // "Firefly!"

    /**
     * The chance that a try at a spot in {@code p} is a swarm's home; {@code drawn}, with water or glowbells by it. None
     * where swarms do not live ({@link BlueSpawnRules#swarmsLive}: the Deep Dark, and outside the Blue Reaches).
     */
    public static double chance(Place p, boolean drawn) {
        double bare = switch (p) {
            case RIFT -> RIFT_BARE;
            case GEODES -> GEODES_BARE;
            case DEEP_DARK, OTHER -> 0.0;
        };
        return Math.min(1.0, drawn ? bare * DRAW : bare);
    }

    /** The highest {@link #chance} of any place: a roll at or over it is no home anywhere. */
    public static double maxChance() {
        double m = 0.0;
        for (Place p : Place.values()) m = Math.max(m, chance(p, true));
        return m;
    }

    /** The seed of the chunk's own tries, so a world made again from its seed puts each swarm back where it was. */
    public static long chunkSeed(long seed, int chunkX, int chunkZ) {
        return Hashing.mix(seed ^ SALT, chunkX, chunkZ, 2);
    }

    /** Whether a home at (x, y, z) keeps {@link #APART} from one already set down at (ox, oy, oz). */
    public static boolean apart(double x, double y, double z, double ox, double oy, double oz) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return dx * dx + dy * dy + dz * dz >= APART * APART;
    }
}
