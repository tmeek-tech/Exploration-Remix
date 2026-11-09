package com.explorationremix.world.quake;

import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Whether a block players placed in the Basalt Galleries stands through a quake, and how it comes down when it does not
 * (0.9.0, Taran's card: "anything over 2 blocks above rock" falls). Pure rules over plain data (no Minecraft types), so
 * they are tested offline; {@link BlockKinds} reads a block's kind from the world and the change log, and the quakes'
 * topple pass, ceiling debris and the eruptions' craters apply the rules.
 *
 * <p><b>Kinds.</b> The change log ({@code world.lava.ChangeLog}) knows, for every block changed since the last terrain
 * reset, the block that was there first. A position is one of five kinds, decided in this order:
 * <ol>
 *     <li>{@link #CLIMB}: held in the log with an open first block (air, the glow, fire or a fluid), and now a climbing
 *     block (#minecraft:climbable, whatever its collision: ladders, scaffolding, vines and the pack's ropes and ivy; or
 *     powder snow, climbable in leather boots);</li>
 *     <li>{@link #FLUID}: held with a first block that was air, the glow or fire (not a fluid), at or over the chunk's
 *     sea, and now a fluid source: lava or water a player poured where the ground was dry (a water ladder);</li>
 *     <li>{@link #OPEN}: air, the glow, fire, any fluid, or a block with no collision (torches, rails, signs, buttons,
 *     a vine nobody placed); ladders and scaffolding have collision, so they are not open;</li>
 *     <li>{@link #PLACED}: held with an open first block (a player's block, a block put into the glow, into a fire or into
 *     a fluid, ceiling debris that landed);</li>
 *     <li>{@link #ROCK}: anything else: the terrain's own block (not held, generated slabs among them), or a held one whose
 *     first block was not open (a mined block filled again counts as rock). Below the layer and above it (the
 *     Wasteland's side, which no log covers) is rock too, so a pillar that climbs into a hole keeps its part over the
 *     layer, which reads as rock.</li>
 * </ol>
 *
 * <p><b>Support.</b> Only what is under a block counts: a placed, climbing or fluid block stands when natural rock is at
 * most {@code supportDepth} blocks under it with only placed blocks between (or, for a climbing block, placed or climbing
 * ones), so steps and low walls stay, and the third block of a pillar and everything over it fall. A climbing block holds
 * up only another climbing block (a ladder or vine column keeps two) and is open to anything else; a poured source holds
 * up nothing.
 */
public final class Support {
    private Support() {}

    // ================================================================================================ kinds

    /** Open space to whatever is over it: air, the glow, fire, a fluid, a block with no collision. */
    public static final int OPEN = 0;
    /** A block put where the terrain was open: a player's block, landed debris. */
    public static final int PLACED = 1;
    /** A climbing block put where the terrain was open. */
    public static final int CLIMB = 2;
    /** A fluid source poured where the terrain was dry. */
    public static final int FLUID = 3;
    /** The terrain's own block, or a block put where the terrain was solid. */
    public static final int ROCK = 4;

    /** The kinds' names, for messages and tests. */
    public static String name(int kind) {
        return switch (kind) {
            case OPEN -> "open";
            case PLACED -> "placed";
            case CLIMB -> "climb";
            case FLUID -> "fluid";
            case ROCK -> "rock";
            default -> "kind " + kind;
        };
    }

    // ------------------------------------------------------------------------------------------- what a block is like

    /** Trait: air, the Galleries' glow, or fire ({@code GlowBlock.open}, {@code #minecraft:fire}). */
    public static final int T_AIRLIKE = 1;
    /** Trait: a fluid's block ({@code LiquidBlock}: lava or water, flowing or a source). */
    public static final int T_LIQUID = 2;
    /** Trait: with {@link #T_LIQUID}, its fluid is a source. */
    public static final int T_SOURCE = 4;
    /** Trait: a climbing block ({@code #minecraft:climbable}, or powder snow). */
    public static final int T_CLIMB = 8;
    /** Trait: no collision shape for the empty collision context (torches, rails, signs, unheld vines). */
    public static final int T_NO_COLLISION = 16;

    /** True for a first block that was open: air, the glow, fire or a fluid. */
    public static boolean openFirst(int first) { return (first & (T_AIRLIKE | T_LIQUID)) != 0; }

    /**
     * The kind of a position whose block now has traits {@code cur}, which the change log holds or not ({@code held})
     * with a first block of traits {@code first} (ignored when not held), under the chunk's sea top or not
     * ({@code underSea}: under the lake's rows and the chunk's share of the risen sea).
     */
    public static int kind(int cur, boolean held, int first, boolean underSea) {
        boolean placedHere = held && openFirst(first);
        if (placedHere && (cur & T_CLIMB) != 0) return CLIMB;
        if (held && (first & T_AIRLIKE) != 0 && (first & T_LIQUID) == 0 && !underSea
                && (cur & (T_LIQUID | T_SOURCE)) == (T_LIQUID | T_SOURCE)) return FLUID;
        if ((cur & (T_AIRLIKE | T_LIQUID | T_NO_COLLISION)) != 0) return OPEN;
        return placedHere ? PLACED : ROCK;
    }

    /**
     * What a block of kind {@code k} is to the block of kind {@code above} over it: a climbing block holds up another
     * climbing block and is open to anything else (nothing rests on a vine or a ladder), a poured source is open to
     * everything, and the other kinds hold up as themselves.
     */
    public static int supportFor(int above, int k) {
        if (k == FLUID) return OPEN;
        if (k == CLIMB) return above == CLIMB ? CLIMB : OPEN;
        return k;
    }

    /** True for the kinds a quake can bring down: placed blocks, climbing blocks and poured sources. */
    public static boolean topples(int kind) { return kind == PLACED || kind == CLIMB || kind == FLUID; }

    // ================================================================================================ the rule

    /**
     * True when a block of kind {@code p} falls by the card's rule: going down from it, there must be rock within
     * {@code depth} blocks ({@code quakes.supportDepth}, 2) with only placed blocks between (or, for a climbing block,
     * placed or climbing ones). {@code below} gives the kind of the position j blocks under it, for j from 1 (asked only
     * as far as needed). Rock, open space and the terrain's own blocks never fall.
     */
    public static boolean falls(int p, IntUnaryOperator below, int depth) {
        if (!topples(p)) return false;
        for (int j = 1; j <= depth; j++) {
            int k = supportFor(p, below.applyAsInt(j));
            if (k == ROCK) return false;
            if (k == PLACED || p == CLIMB && k == CLIMB) continue;
            return true;
        }
        return true;
    }

    // ================================================================================================ ways down

    /** A block that falls comes down by vanilla's falling block (or is taken out where it cannot fall: {@link #fallWay}). */
    public static final int FALL = 0;
    /**
     * It goes back into the lava (or air) it took at once, through the terrain reset's own put-back of its entry
     * ({@code GalleryChanges.giveBack}): no entity, no drop, no fluid tick.
     */
    public static final int GIVE_BACK = 1;
    /** A poured source is drained the way a bucket takes it ({@code setBlock(AIR, 3)}), so the flow it fed recedes. */
    public static final int DRAIN = 2;

    /**
     * How a block of kind {@code p} that falls is taken away. A poured source is drained. Under the chunk's sea top (the
     * lake's rows at every phase, a pool, the risen sea: {@code underSea}), when its first block was a fluid (a block put
     * into a pool over the sea: {@code firstLiquid}), or when the reset is on and the entry is from before its
     * generation ({@code old}: the reset is about to take it anyway), it is given back. Otherwise it falls. The reset being
     * off changes nothing else: every placed block topples whatever its generation.
     */
    public static int way(int p, boolean underSea, boolean firstLiquid, boolean terrainDamage, boolean old) {
        // a poured source is never under the sea or in a fluid's place (FLUID needs both), so only an old one could be
        // given back instead; a quiet give-back would leave the flow it fed standing (still climbable), so it drains too
        if (p == FLUID) return DRAIN;
        if (underSea || firstLiquid || terrainDamage && old) return GIVE_BACK;
        return FALL;
    }

    /** It falls straight down, as a falling block from where it stands. */
    public static final int DOWN = 0;
    /** It tumbles off its stump to a side, as a falling block from the open block beside it. */
    public static final int SIDE = 1;
    /** It is taken out where it stands, with vanilla's drops (or none for natural stone with the reset on: {@link #drops}). */
    public static final int OUT = 2;

    /**
     * True when a block can come down as vanilla's falling block: no block entity ({@code fall()} would spill a
     * container's contents and move an empty block), not one half of a two-part block (a door, a bed, a tall plant), and
     * one that can rest on its bottom face (not a top slab, a fence, a pane, a ladder, scaffolding, a vine).
     */
    public static boolean canFallAsEntity(boolean blockEntity, boolean twoPart, boolean sturdyBottom) {
        return !blockEntity && !twoPart && sturdyBottom;
    }

    /**
     * How a block that falls by {@link #FALL} comes down: straight down when what is under it is open (or a poured
     * source) and nothing blocks the column under it ({@code blockedUnder}: a player in it, or lava in it, which the
     * block would sink through to lie on the floor under the lava); off its stump to a side when it rests on a placed or
     * climbing block (a falling block that starts on a block lands back in its own cell) and a side is free; taken out
     * otherwise, and always when it cannot fall as an entity.
     */
    public static int fallWay(boolean canFall, int below1, boolean blockedUnder, boolean sideFree) {
        if (!canFall) return OUT;
        if (below1 == OPEN || below1 == FLUID) return blockedUnder ? OUT : DOWN;
        return sideFree ? SIDE : OUT;
    }

    /**
     * True when a block taken out drops its item (and a falling one its item if it cannot land: its {@code dropItem}): not
     * for one of the Galleries' natural stone kinds ({@code #explorationremix:galleries_rock}) while the reset is on,
     * which regrows it at its source (no farm, and landed ceiling debris never becomes an item); everything as vanilla
     * otherwise.
     */
    public static boolean drops(boolean terrainDamage, boolean galleriesRock) { return !terrainDamage || !galleriesRock; }

    /**
     * True when a block with a block entity has to wait instead of being taken out: lava stands in the column under it
     * (down to the first block that is not open), so whatever it holds would fall into the lava.
     */
    public static boolean waitsForLava(boolean blockEntity, boolean lavaUnder) { return blockEntity && lavaUnder; }

    /**
     * The flags the write at the toppled position uses ({@code Block.UPDATE_*}): 2 | 16 (clients only, no neighbour or
     * shape updates) when a fluid touches it, so the lava or water beside a plug gets no fluid tick and stays where it
     * is; 3 otherwise, so torches and plants on it pop by vanilla's rules. The write is always air, never the block's own
     * fluid (a waterlogged block leaves no water source in the air).
     */
    public static int writeFlags(boolean fluidNeighbour) { return fluidNeighbour ? 2 | 16 : 3; }

    // ------------------------------------------------------------------------------------------- the side to tumble to

    /** The four horizontal sides, as x and z steps: east, south, west, north. */
    public static final int[] SIDE_X = {1, 0, -1, 0}, SIDE_Z = {0, 1, 0, -1};

    /**
     * The side a block of a column tries first: the column's own start (drawn once a column) turned on by the block's
     * place in the column, so the blocks of one pillar go different ways.
     */
    public static int sideStart(int columnStart, int index) { return Math.floorMod(columnStart + index, 4); }

    /**
     * True when the block beside a toppling one can take it: in a loaded chunk where entities tick, air or the glow, with
     * no fluid on its four sides or over it (the toppling block itself not counted: it is about to be air), no lava in
     * the column under it down to where the block would land (it would sink through and lie on the floor under the lava,
     * there when the sea drains), not in a column a player's box is within half a block of, and not a column another
     * block of this pass was dropped into. For a waterlogged block, its own leak (flowing water, no source) counts as
     * neither a fluid nor a block in the way ({@link #ownLeak}).
     */
    public static boolean sideAllowed(boolean ticking, boolean open, boolean fluidNear, boolean lavaUnder, boolean playerColumn, boolean taken) {
        return ticking && open && !fluidNear && !lavaUnder && !playerColumn && !taken;
    }

    /**
     * True for a fluid a waterlogged block's fall may meet beside it: flowing water, no source. It is the block's own
     * leak (its water runs out over its sides), which recedes once the block goes (the write wakes it,
     * {@link #wakesWater}); without this a waterlogged block could never fall to a side, its own water being on every one.
     */
    public static boolean ownLeak(boolean wet, boolean water, boolean source) { return wet && water && !source; }

    /**
     * True when a quiet write at a toppled block must still wake the water beside it: the block carried water (it was
     * waterlogged) and no lava touches it. Its leak, flowing down the build's sides, would otherwise stand with no source
     * (still climbable) until something next to it updates. Only the flowing water is woken, never a source or lava.
     */
    public static boolean wakesWater(boolean carriedWater, boolean lavaNear) { return carriedWater && !lavaNear; }

    /**
     * The side a block tumbles to: the four sides tried from {@code start} round, the first that is allowed and has free
     * space under it ({@code FallingBlock.isFree}), else the first allowed; -1 when none is.
     */
    public static int side(int start, IntPredicate allowed, IntPredicate freeUnder) {
        int fallback = -1;
        for (int k = 0; k < 4; k++) {
            int d = (start + k) & 3;
            if (!allowed.test(d)) continue;
            if (freeUnder.test(d)) return d;
            if (fallback < 0) fallback = d;
        }
        return fallback;
    }

    // ------------------------------------------------------------------------------------------- players and columns

    /**
     * True when a player's box (minX, minY, minZ to maxX, maxZ) stands on the block at (x, y, z): their feet within a
     * block over its top ({@code minY} in [y + 1, y + 2)) and their box over its cell grown by 0.3 on each side. Such a
     * column is not toppled by the pass but lowered under them (spectators stand on nothing: callers leave them out).
     */
    public static boolean standsOn(int x, int y, int z, double minX, double minY, double minZ, double maxX, double maxZ) {
        return minY >= y + 1 && minY < y + 2 && overCell(x, z, minX, minZ, maxX, maxZ);
    }

    /** True when a player's box is over the cell (x, z) grown by 0.3 on each side, as {@link #standsOn} grows it. */
    public static boolean overCell(int x, int z, double minX, double minZ, double maxX, double maxZ) {
        return maxX > x - 0.3 && minX < x + 1.3 && maxZ > z - 0.3 && minZ < z + 1.3;
    }

    /** How far over the top of a column a player's feet may be and they still ride it: a jump from it reaches 1.25. */
    public static final double JUMP = 1.5;

    /**
     * True when a player over a column, with their feet at {@code minY}, rides it: the column's top block (the highest
     * of the placed, climbing and poured blocks stacked there) is at row {@code topY}, and they stand on it, or are in
     * the air ({@code airborne}: not on the ground, as their client tells the server) at most {@link #JUMP} over its top
     * with nothing solid between ({@code openOver}: the cell over it is open). A player towering is in the air more than
     * a block over the top for part of every jump, before they place the next block under them: such a column is still
     * under them. A pass never takes a block from a column someone rides (it would leave the top hanging under them, to
     * give way later and drop them its whole height): it is lowered under them instead ({@code Lowering}).
     */
    public static boolean rides(int topY, double minY, boolean openOver, boolean airborne) {
        if (minY < topY + 1) return false;
        if (minY < topY + 2) return true;
        return openOver && airborne && minY < topY + 1 + JUMP;
    }

    /**
     * True when a player over a column, with their feet at {@code minY} ({@code airborne}: in the air), rides it at or
     * over the block at row {@code y}: going up from that block through the placed, climbing and poured blocks stacked
     * on it ({@code kind} gives the kind at a row of the column) to the column's top, they {@link #rides ride} that top.
     * A gap or rock between the block and their feet means they stand on something else.
     */
    public static boolean ridesFrom(int y, double minY, boolean airborne, IntUnaryOperator kind) {
        if (minY < y + 1) return false;
        int top = y;
        while (top + 1 < minY && topples(kind.applyAsInt(top + 1))) top++;
        return rides(top, minY, kind.applyAsInt(top + 1) == OPEN, airborne);
    }

    /**
     * True when a player's box comes within half a block of the column (x, z) anywhere from row {@code fromY} up to and
     * including row {@code toY}: nothing falls straight down or tumbles into such a column.
     */
    public static boolean inColumn(int x, int z, int fromY, int toY, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return maxX > x - 0.5 && minX < x + 1.5 && maxZ > z - 0.5 && minZ < z + 1.5 && maxY > fromY && minY < toY + 1;
    }

    // ------------------------------------------------------------------------------------------- top-down order

    /**
     * A key that sorts positions by x, then z, then y from the top down ({@link #sortTopDown}), so the upper blocks of a
     * column are taken before the ones under them. x and z within 2^25 of 0, y within -2048 to 2047.
     */
    public static long topDownKey(int x, int y, int z) {
        return ((long) (x + (1 << 25)) & 0x3FFFFFFL) << 38 | ((long) (z + (1 << 25)) & 0x3FFFFFFL) << 12 | (2047 - y) & 0xFFFL;
    }

    public static int keyX(long key) { return (int) (key >>> 38) - (1 << 25); }
    public static int keyZ(long key) { return (int) (key >>> 12 & 0x3FFFFFFL) - (1 << 25); }
    public static int keyY(long key) { return 2047 - (int) (key & 0xFFFL); }

    /** Sorts keys made by {@link #topDownKey} into their order (they are compared unsigned). */
    public static void sortTopDown(long[] keys, int n) {
        for (int i = 0; i < n; i++) keys[i] ^= Long.MIN_VALUE;
        Arrays.sort(keys, 0, n);
        for (int i = 0; i < n; i++) keys[i] ^= Long.MIN_VALUE;
    }

    // ================================================================================================ lowering

    /** Most steps one lowering job takes under a player. */
    public static final int MAX_STEPS = 96;
    /** The shortest pause between two steps that lets a player land before the next one (a 1-block drop takes about 5 ticks). */
    public static final int MIN_LOWER_TICKS = 6;

    /** The column under a player gives way at once: a bridge or an overhang (open space under the block they stand on). */
    public static final int GIVES_WAY = 0;
    /** It sinks a block every {@code lowerTicks} with them on it: a tower. */
    public static final int SINKS = 1;
    /** Every falling block of it goes at once after the creak ({@code lowerTicks} 0, the deadly alternative). */
    public static final int AT_ONCE = 2;

    /**
     * {@code quakes.lowerTicks} as the jobs read it: 0 stays 0 (the build drops at once), 1 to 5 count as
     * {@link #MIN_LOWER_TICKS} (shorter steps would let fall damage add up).
     */
    public static int lowerTicks(int configured) { return configured <= 0 ? 0 : Math.max(MIN_LOWER_TICKS, configured); }

    /** The row of the block right under a player's feet (their box's minY). */
    public static int feetBlockY(double minY) { return (int) Math.floor(minY - 0.01); }

    /**
     * How a falling column under a player comes down, from the kind of the block under the one they stand on
     * ({@code underT}): a tower (placed or climbing blocks under it) sinks a block at a time, or all at once with
     * {@code lowerTicks} 0; anything else (a bridge, an overhang) gives way at once, taken out rather than dropped (a
     * player falls twice as fast as a falling block, so the block would land in their feet cell).
     */
    public static int lowering(int underT, int lowerTicks) {
        if (underT == PLACED || underT == CLIMB) return lowerTicks == 0 ? AT_ONCE : SINKS;
        return GIVES_WAY;
    }

    /** How long the column under a player creaks before a tower starts to sink under them, in ticks (spec 4.3.5). */
    public static final int SINK_CREAK = 20;
    /**
     * How long a bridge or an overhang creaks before it gives way, in ticks: about 3 s, time to run a few blocks back to
     * solid ground (Taran, 2026-11-08, "Longer warning, then drop"). The rest of the quake's blocks wait for it.
     */
    public static final int GIVE_WAY_CREAK = 60;

    /** How long the column under a player creaks before it comes down, by how it comes down ({@link #comesDown}). */
    public static int creakTicks(int mode) { return mode == GIVES_WAY ? GIVE_WAY_CREAK : SINK_CREAK; }

    /**
     * How the column under a player comes down in the end ({@link #lowering}), from the kinds under the {@code n} blocks
     * of its stack from the top down ({@code under.applyAsInt(k)}: the kind under the k-th block down): a tower sinks
     * onto whatever its stack stands on, so a bridge two blocks thick, or a tower standing on a bridge, gives way in the
     * end and creaks as long as a bridge does. A tower over rock sinks, as {@link #lowering} of its top says.
     */
    public static int comesDown(int n, IntUnaryOperator under, int lowerTicks) {
        int mode = SINKS;
        for (int k = 0; k < n && mode == SINKS; k++) mode = lowering(under.applyAsInt(k), lowerTicks);
        return mode;
    }

    /**
     * True while a lowering job goes on after {@code steps} steps: the block under the player still falls by the rule
     * (the tower is not yet down to {@code supportDepth} over rock), an actor still stands on the column, and it has not
     * run {@link #MAX_STEPS} steps.
     */
    public static boolean lowersOn(boolean tFalls, boolean actorOn, int steps) { return tFalls && actorOn && steps < MAX_STEPS; }
}
