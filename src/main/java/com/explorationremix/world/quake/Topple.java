package com.explorationremix.world.quake;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERTags;
import com.explorationremix.world.lava.ChangeLog;
import com.explorationremix.world.lava.GalleryChanges;
import com.explorationremix.world.lava.LavaFlood;
import com.explorationremix.world.lava.LavaNet;
import com.explorationremix.world.lava.NearChunks;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The quakes' topple pass (0.9.0, spec 4.3): finds the blocks players placed in the Basalt Galleries that stand on no
 * natural rock within {@code quakes.supportDepth} ({@link Support#falls}) and takes them down, each by its way
 * ({@link Support#way}). Every quake has a pass ({@link Pass}): a big quake's, a tremor's round every actor, a climb
 * answer's round the block placed; {@code quakes.toppleAtOnce} has a queue of placements instead ({@link #queue}).
 *
 * <p><b>The find.</b> The chunks round the actors (within the simulation distance less one, where falling blocks tick)
 * or the 5 by 5 chunks round a climb answer's block, each once, skipping a chunk whose middle is not entity-ticking; in
 * each, the change log's entries whose first block was open (air, the glow, fire, a fluid), read with the block there
 * now and the two under it. At most {@link #ENTRIES_PER_TICK} entries a tick (the chunk in hand is finished; the rest
 * wait for the next tick), at most {@code quakes.toppleMax} blocks a quake. A chunk's finds are taken top-down, so the
 * upper blocks of a column go before the ones under them. A block a player stands on (and the falling blocks of that
 * column under it) is not toppled but lowered under them ({@link Lowering}), after a creak.
 *
 * <p><b>The ways.</b> Under the lava (the lake's rows, a pool, the risen sea), in a pool's place, and with the reset on
 * for an entry the reset is about to take, a block is given back at once by the reset's own code
 * ({@link GalleryChanges#giveBack}): the lava comes back with no entity, no drop and no fluid tick. A poured source is
 * drained the way a bucket takes it, so its flow recedes. Anything else comes down as vanilla's falling block: straight
 * down when it has open space under it and no player is in the column under it, off its stump to a free side when it
 * rests on a placed block (a falling block that starts on a block lands back in its own cell), and taken out where it
 * stands, with vanilla's drops, when it cannot fall as an entity (a block entity, a two-part block, a block that cannot
 * rest on its bottom) or no way down is free. The write where it stood is always air (never a waterlogged block's water),
 * quiet (no neighbour updates) when a fluid touches it, so a plug is never pulled with a fluid tick. With the reset on,
 * a natural stone kind ({@code #explorationremix:galleries_rock}) never becomes an item: the reset regrows it at its
 * source. Every block found is read again when its turn comes, so what a player changed in between is judged again.
 */
final class Topple {
    private Topple() {}

    /** Most change-log entries the finds look at in one tick (the chunk in hand is finished). */
    static final int ENTRIES_PER_TICK = 8192;
    /** Most placed blocks taken down in one tick, lowering steps included. */
    static final int TAKEN_PER_TICK = 64;
    /** A tremor or climb answer is felt in full by a player within this many blocks of a block it found. */
    static final int FELT_RANGE = 32;
    /** A climb answer's find covers the chunks this many chunks round the block placed (5 by 5). */
    static final int CLIMB_CHUNKS = 2;
    /** Most placements waiting in the {@code toppleAtOnce} queue, and most of them checked in a tick. */
    static final int QUEUE_MAX = 1024, QUEUE_PER_TICK = 64;
    /** How far up from a queued placement the {@code toppleAtOnce} check goes. */
    static final int QUEUE_UP = 16;
    /** How far down the landing of a falling block and the lava under a container are looked for. */
    private static final int DOWN_REACH = 192;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Direction[] SIDES = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};

    /** What {@link #take} did with a block. */
    static final int LEFT = 0, FELL = 1, GIVEN = 2, OUT = 3;

    // ------------------------------------------------------------------------------------------- state of this server
    /** The {@code toppleAtOnce} queue: placements and the blocks over what was taken away, to check soon. */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    /** The last find that ended, for the status: chunks with changes, actors round, entries, ms and most ms in a tick. */
    static int lastChunks, lastPlayers, lastEntries;
    static double lastMs, lastMaxMs;

    /** When the server stops, or toppling is turned off. */
    static void clear() { QUEUE.clear(); }

    // ================================================================================================ one tick's work

    /** What one tick of toppling reads once and shares: the settings, the players and the budgets. */
    static final class Work {
        final ServerLevel level;
        final long now;
        final int depth, toppleMax, lowerTicks, resetGen;
        final boolean terrainDamage, atOnce;
        /** The players near the Galleries who are not spectators: who stand on builds, and whom nothing falls onto or into. */
        final List<ServerPlayer> standing = new ArrayList<>();
        /** Every player near the Galleries: who are told of creaks. */
        final List<ServerPlayer> watchers;
        /** Entries looked at and blocks taken this tick, against {@link #ENTRIES_PER_TICK} and {@link #TAKEN_PER_TICK}. */
        int entries, taken;
        /** Columns a give-back has hissed in this tick: one hiss a column. */
        final LongOpenHashSet hissed = new LongOpenHashSet();
        /** Columns falling blocks of the queue's checks went into this tick. */
        final LongOpenHashSet spawned = new LongOpenHashSet();

        Work(ServerLevel level, long now, List<ServerPlayer> watchers) {
            ERConfig cfg = ERConfig.INSTANCE;
            this.level = level;
            this.now = now;
            this.watchers = watchers;
            depth = cfg.quakeSupportDepth.get();
            toppleMax = cfg.quakeToppleMax.get();
            lowerTicks = Support.lowerTicks(cfg.quakeLowerTicks.get());
            terrainDamage = cfg.resetEnabled.get() && cfg.lavaCycleEnabled.get();
            atOnce = cfg.quakeToppleAtOnce.get();
            resetGen = (int) Math.min(Integer.MAX_VALUE, LavaFlood.resetGen(level));
            for (ServerPlayer p : watchers) if (!p.isSpectator()) standing.add(p);
        }

        boolean budget() { return taken < TAKEN_PER_TICK; }
    }

    // ================================================================================================ one quake's pass

    /** A column found under a player: lowered under them after a creak rather than toppled by the pass. */
    record UnderPlayer(UUID player, int x, int z, int topY, LongArrayList positions) {}

    /** One quake's topple pass: its find, the blocks found waiting for their turn, and the columns under players. */
    static final class Pass {
        /** For a climb answer, the block placed: the find covers the 5 by 5 chunks round it. */
        @Nullable final BlockPos origin;
        /** Draws each column's first side from this, so the blocks of a column go different ways. */
        final int seed;
        final LongOpenHashSet done = new LongOpenHashSet();
        boolean findDone;
        /** The blocks found, in their order (packed positions), with the block state each was found as. */
        final LongArrayList list = new LongArrayList();
        final IntArrayList states = new IntArrayList();
        int next;
        final List<UnderPlayer> under = new ArrayList<>();
        /** Every block found, list and lowered columns both: whom the quake is felt by in full. */
        final LongArrayList found = new LongArrayList();
        /** Columns falling blocks of this pass were spawned into: no other block of it tumbles into one. */
        final LongOpenHashSet spawned = new LongOpenHashSet();
        private long lastColumn = Long.MIN_VALUE;
        private int index;
        /** For the status: chunks with changes, entries looked at, actors round, time spent. */
        int chunks, entries, players;
        long nanos, maxNanos;

        Pass(@Nullable BlockPos origin, int seed) {
            this.origin = origin;
            this.seed = seed;
        }

        /** How many placed blocks the find has found so far. */
        int found() { return found.size(); }

        private boolean full(Work w) { return found.size() >= w.toppleMax; }

        /**
         * Goes on with the find within this tick's budget, round {@code actors} (a climb answer's round its block).
         * Sets {@link #findDone} once every chunk has been looked at or the quake's cap is reached.
         */
        void find(Work w, List<ServerPlayer> actors) {
            if (findDone) return;
            long t0 = System.nanoTime();
            boolean[] stopped = {false};
            if (full(w)) {
                findDone = true;
            } else if (origin != null) {
                int cx0 = origin.getX() >> 4, cz0 = origin.getZ() >> 4;
                players = 1;
                outer:
                for (int dx = -CLIMB_CHUNKS; dx <= CLIMB_CHUNKS; dx++) {
                    for (int dz = -CLIMB_CHUNKS; dz <= CLIMB_CHUNKS; dz++) {
                        if (full(w)) break outer;
                        if (w.entries >= ENTRIES_PER_TICK) {
                            stopped[0] = true;
                            break outer;
                        }
                        if (!done.add(ChunkPos.asLong(cx0 + dx, cz0 + dz))) continue;
                        LevelChunk c = w.level.getChunkSource().getChunkNow(cx0 + dx, cz0 + dz);
                        if (c != null) visit(w, c);
                    }
                }
                findDone = !stopped[0];
            } else {
                int sim = w.level.getServer().getPlayerList().getSimulationDistance();
                int rad = Math.max(0, Math.min(NearChunks.radius(w.level), sim - 1));
                players = actors.size();
                NearChunks.walk(w.level, actors, rad, w.now, c -> {
                    if (full(w)) return false;
                    if (!done.contains(c.getPos().toLong())) {
                        if (w.entries >= ENTRIES_PER_TICK) {
                            stopped[0] = true;
                            return false;
                        }
                        done.add(c.getPos().toLong());
                        visit(w, c);
                    }
                    return true;
                });
                findDone = !stopped[0];
            }
            long dt = System.nanoTime() - t0;
            nanos += dt;
            maxNanos = Math.max(maxNanos, dt);
            if (findDone) {
                lastChunks = chunks;
                lastPlayers = players;
                lastEntries = entries;
                lastMs = nanos / 1e6;
                lastMaxMs = maxNanos / 1e6;
            }
        }

        /** Finds the falling blocks of one chunk, sorts them top-down and files them: the list, or a column under a player. */
        private void visit(Work w, LevelChunk chunk) {
            ChunkPos cp = chunk.getPos();
            if (!w.level.isPositionEntityTicking(new BlockPos(cp.getMiddleBlockX(), 0, cp.getMiddleBlockZ()))) return;
            ChangeLog log = GalleryChanges.logOf(chunk);
            if (log == null || log.isEmpty()) return;
            chunks++;
            int bottom = GalleryChanges.bottom(), seaTop = GalleryChanges.seaTop(chunk);
            int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
            LongArrayList keys = new LongArrayList();
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(), below = new BlockPos.MutableBlockPos();
            int depth = w.depth;
            log.forEach((key, first, gen) -> {
                w.entries++;
                entries++;
                int ft = BlockKinds.firstTraits(Block.stateById(first));
                if (!Support.openFirst(ft)) return;
                int x = x0 + ChangeLog.x(key), y = bottom + ChangeLog.dy(key), z = z0 + ChangeLog.z(key);
                at.set(x, y, z);
                int p = Support.kind(BlockKinds.traits(chunk.getBlockState(at), chunk, at), true, ft, y < seaTop);
                if (!Support.topples(p)) return;
                if (!Support.falls(p, j -> BlockKinds.kindAt(chunk, log, bottom, seaTop, below.set(x, y - j, z)), depth)) return;
                keys.add(Support.topDownKey(x, y, z));
            });
            if (keys.isEmpty()) return;
            long[] k = keys.toLongArray();
            Support.sortTopDown(k, k.length);
            for (long key : k) {
                if (full(w)) return;
                int x = Support.keyX(key), y = Support.keyY(key), z = Support.keyZ(key);
                // a column a lowering job already takes down is the job's
                if (Lowering.runningAt(x, z)) continue;
                long pos = BlockPos.asLong(x, y, z);
                UnderPlayer col = underAt(x, z);
                if (col != null && y < col.topY()) {
                    col.positions().add(pos);
                    found.add(pos);
                    continue;
                }
                ServerPlayer on = standsOn(w, x, y, z);
                if (on != null) {
                    LongArrayList ps = new LongArrayList();
                    ps.add(pos);
                    under.add(new UnderPlayer(on.getUUID(), x, z, y, ps));
                    found.add(pos);
                    continue;
                }
                list.add(pos);
                states.add(Block.getId(chunk.getBlockState(at.set(x, y, z))));
                found.add(pos);
            }
        }

        @Nullable
        private UnderPlayer underAt(int x, int z) {
            for (UnderPlayer u : under) if (u.x() == x && u.z() == z) return u;
            return null;
        }

        /** True when the find is done and every block found has had its turn. */
        boolean finished() { return findDone && next >= list.size(); }

        /** Takes the blocks found, in their order, within this tick's budget; counts them on {@code e}. */
        void falls(Work w, Quakes.Event e) {
            while (next < list.size() && w.budget()) {
                BlockPos pos = BlockPos.of(list.getLong(next));
                int state = states.getInt(next);
                next++;
                long col = column(pos.getX(), pos.getZ());
                if (col != lastColumn) {
                    lastColumn = col;
                    index = 0;
                } else {
                    index++;
                }
                // a player who has since stepped onto it keeps it: the next pass lowers it under them
                if (standsOn(w, pos.getX(), pos.getY(), pos.getZ()) != null) continue;
                int r = take(w, pos, state, false, Support.sideStart(columnStart(seed, pos.getX(), pos.getZ()), index), spawned);
                Topple.count(e, r, false);
                if (r != LEFT) w.taken++;
            }
        }

        /** A lowering job's leftover blocks come back into the list, to be taken as found ones are. */
        void requeue(LongArrayList positions, ServerLevel level) {
            for (int i = 0; i < positions.size(); i++) {
                long p = positions.getLong(i);
                BlockState s = stateAt(level, BlockPos.of(p));
                if (s == null) continue;
                list.add(p);
                states.add(Block.getId(s));
            }
        }

        /**
         * The find is done: every column found under a player gets a lowering job, starting {@code delay} ticks on, and,
         * with a delay, a creak now (the job waits for it: nothing is taken in the meantime).
         */
        void startJobs(Work w, @Nullable Quakes.Event e, int delay) {
            for (UnderPlayer u : under) {
                if (Lowering.runningAt(u.x(), u.z()) || !Lowering.room()) continue;
                Lowering.add(new Lowering.Job(u.player(), u.x(), u.z(), e, w.now + delay, u.positions()));
                if (delay > 0) creak(w, u, w.now + delay);
            }
        }
    }

    /** The side the blocks of the column at (x, z) start from, drawn once a column from the quake's seed. */
    static int columnStart(int seed, int x, int z) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) (h & 3);
    }

    /** The two horizontal coordinates of a column, packed. */
    static long column(int x, int z) { return ChunkPos.asLong(x, z); }

    /** Counts what happened to one block on the quake ({@code lowered} for a lowering job's step). */
    static void count(@Nullable Quakes.Event e, int r, boolean lowered) {
        if (e == null || r == LEFT) return;
        if (lowered) {
            e.lowered++;
        } else {
            switch (r) {
                case FELL -> e.fell++;
                case GIVEN -> e.givenBack++;
                default -> e.takenOut++;
            }
        }
        e.toppled++;
    }

    /**
     * Sends a creak for the column under a player to the watchers within {@link LavaNet.Debris#CREAK_RANGE}: the block
     * under their feet is about to go, at tick {@code goes} (spec 4.3.5).
     */
    private static void creak(Work w, UnderPlayer u, long goes) {
        BlockPos at = new BlockPos(u.x(), u.topY(), u.z());
        BlockState s = stateAt(w.level, at);
        if (s == null) return;
        int low = u.topY();
        for (int i = 0; i < u.positions().size(); i++) low = Math.min(low, BlockPos.getY(u.positions().getLong(i)));
        LavaNet.Debris d = new LavaNet.Debris(LavaNet.Debris.CREAK, at.asLong(), Block.getId(s), goes, low, Integer.MIN_VALUE, -1);
        double r2 = (double) LavaNet.Debris.CREAK_RANGE * LavaNet.Debris.CREAK_RANGE;
        for (ServerPlayer p : w.watchers) {
            if (p.distanceToSqr(u.x() + 0.5, u.topY() + 0.5, u.z() + 0.5) > r2) continue;
            LavaNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), d);
        }
        Build.creaked(w.level, u.x(), u.z());
    }

    // ================================================================================================ one block down

    /**
     * Takes the placed block at {@code pos} down by its way (spec 4.3.3, 4.3.4), if it still falls by the rule and, when
     * {@code expect} is not -1, is still the block state found. {@code noFall} takes it out rather than letting it fall
     * (a bridge giving way under a player: a falling block would land in their feet cell). {@code sideStart} is the side
     * it tries first; {@code spawned} the columns this pass's falling blocks went into. Returns {@link #LEFT} when it
     * stays (for now: the next look tries again), or {@link #FELL}, {@link #GIVEN} or {@link #OUT}.
     */
    static int take(Work w, BlockPos pos, int expect, boolean noFall, int sideStart, LongOpenHashSet spawned) {
        ServerLevel level = w.level;
        if (!GalleryChanges.inLayer(pos.getY())) return LEFT;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return LEFT;
        ChangeLog log = GalleryChanges.logOf(chunk);
        int bottom = GalleryChanges.bottom(), dy = pos.getY() - bottom;
        if (log == null || !ChangeLog.holds(dy)) return LEFT;
        int key = ChangeLog.key(pos.getX() & 15, dy, pos.getZ() & 15);
        int first = log.state(key);
        if (first == ChangeLog.NONE) return LEFT;
        BlockState cur = chunk.getBlockState(pos);
        if (expect >= 0 && Block.getId(cur) != expect) return LEFT;
        int seaTop = GalleryChanges.seaTop(chunk);
        boolean underSea = pos.getY() < seaTop;
        int ft = BlockKinds.firstTraits(Block.stateById(first));
        int p = Support.kind(BlockKinds.traits(cur, chunk, pos), true, ft, underSea);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        if (!Support.falls(p, j -> BlockKinds.kindAt(chunk, log, bottom, seaTop, m.setWithOffset(pos, 0, -j, 0)), w.depth)) return LEFT;
        int way = Support.way(p, underSea, (ft & Support.T_LIQUID) != 0, w.terrainDamage, log.gen(key) < w.resetGen);
        if (way == Support.GIVE_BACK) {
            // one hiss a column a tick: a tower given back at once under the lava hisses once, smoking all the way up
            boolean hiss = !w.hissed.contains(column(pos.getX(), pos.getZ()));
            if (!GalleryChanges.giveBack(level, chunk, pos, hiss)) return LEFT;
            if (hiss) w.hissed.add(column(pos.getX(), pos.getZ()));
            tookAway(w, pos);
            return GIVEN;
        }
        if (way == Support.DRAIN) {
            // the way a bucket takes it: the flow it fed recedes by vanilla's rules
            level.setBlock(pos, AIR, Block.UPDATE_ALL);
            tookAway(w, pos);
            return OUT;
        }
        boolean blockEntity = cur.hasBlockEntity();
        boolean canFall = !noFall && Support.canFallAsEntity(blockEntity, twoPart(cur), cur.isFaceSturdy(chunk, pos, Direction.DOWN));
        int below = BlockKinds.kindAt(chunk, log, bottom, seaTop, m.setWithOffset(pos, Direction.DOWN));
        int fw;
        int side = -1;
        if (!canFall) {
            fw = Support.OUT;
        } else if (below == Support.OPEN || below == Support.FLUID) {
            boolean playerUnder = playerInColumn(w, pos.getX(), pos.getZ(), restRow(level, pos.getX(), pos.getY() - 1, pos.getZ()), pos.getY());
            fw = Support.fallWay(true, below, playerUnder, false);
        } else {
            side = Support.side(sideStart, d -> sideAllowed(w, pos, d, spawned), d -> freeUnder(level, pos, d));
            fw = Support.fallWay(true, below, false, side >= 0);
        }
        boolean drops = Support.drops(w.terrainDamage, cur.is(ERTags.GALLERIES_ROCK));
        if (fw == Support.DOWN) {
            if (!level.isPositionEntityTicking(pos)) return LEFT;
            write(level, chunk, pos);
            // fall() writes the dry block's fluid (air) where it stands, which is already air: nothing round it wakes
            FallingBlockEntity e = FallingBlockEntity.fall(level, pos, dry(cur));
            e.dropItem = drops;
            spawned.add(column(pos.getX(), pos.getZ()));
            tookAway(w, pos);
            return FELL;
        }
        if (fw == Support.SIDE) {
            BlockPos n = pos.relative(SIDES[side]);
            write(level, chunk, pos);
            FallingBlockEntity e = FallingBlockEntity.fall(level, n, dry(cur));
            double v = 0.08 + level.random.nextDouble() * 0.08;
            e.setDeltaMovement(Support.SIDE_X[side] * v + (level.random.nextDouble() - 0.5) * 0.06, 0.04,
                    Support.SIDE_Z[side] * v + (level.random.nextDouble() - 0.5) * 0.06);
            // a new entity's first update sends no motion: this sends it on that same update, so clients see it tumble
            e.hurtMarked = true;
            e.dropItem = drops;
            spawned.add(column(n.getX(), n.getZ()));
            tookAway(w, pos);
            return FELL;
        }
        // taken out where it stands, with vanilla's drops; a container waits while lava is under it
        BlockEntity be = blockEntity ? chunk.getBlockEntity(pos) : null;
        if (Support.waitsForLava(be != null, be != null && lavaUnder(level, pos))) return LEFT;
        if (drops) Block.dropResources(cur, level, pos, be);
        write(level, chunk, pos);
        level.levelEvent(2001, pos, Block.getId(cur));
        tookAway(w, pos);
        return OUT;
    }

    /**
     * Writes air where a toppled block stood: quietly (sent to clients, no neighbour or shape updates) when a fluid
     * touches it, so the lava or water beside a plug gets no fluid tick; else with neighbour updates, so torches and
     * plants on it pop by vanilla's rules. Recorded by the change log like any change.
     */
    private static void write(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        level.setBlock(pos, AIR, Support.writeFlags(fluidNear(level, pos, true)));
    }

    /** After a block was taken away: with {@code toppleAtOnce} the block over it is checked next (spec 4.3.8). */
    private static void tookAway(Work w, BlockPos pos) {
        if (w.atOnce) queue(pos.above());
    }

    /** True when the side {@code d} of {@code pos} can take a toppling block ({@link Support#sideAllowed}). */
    private static boolean sideAllowed(Work w, BlockPos pos, int d, LongOpenHashSet spawned) {
        BlockPos n = pos.relative(SIDES[d]);
        LevelChunk c = w.level.getChunkSource().getChunkNow(n.getX() >> 4, n.getZ() >> 4);
        boolean ticking = c != null && w.level.isPositionEntityTicking(n) && GalleryChanges.inLayer(n.getY());
        if (!ticking) return false;
        boolean open = GlowBlock.open(c.getBlockState(n));
        boolean fluid = fluidNear(w.level, n, false);
        boolean player = playerInColumn(w, n.getX(), n.getZ(), restRow(w.level, n.getX(), n.getY() - 1, n.getZ()), n.getY());
        return Support.sideAllowed(true, open, fluid, player, spawned.contains(column(n.getX(), n.getZ())));
    }

    /** True when the block under the side {@code d} of {@code pos} is free for a falling block ({@code FallingBlock.isFree}). */
    private static boolean freeUnder(ServerLevel level, BlockPos pos, int d) {
        BlockState s = stateAt(level, pos.relative(SIDES[d]).below());
        return s != null && FallingBlock.isFree(s);
    }

    /**
     * True when a fluid stands next to {@code pos}: on its four sides or over it, and with {@code under} under it too.
     * A block not loaded counts as a fluid (the careful answer).
     */
    static boolean fluidNear(ServerLevel level, BlockPos pos, boolean under) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN && !under) continue;
            BlockState s = stateAt(level, m.setWithOffset(pos, d));
            if (s == null || !s.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /**
     * The row a falling block let go over (x, y, z) comes to rest in: down through cells with no collision (air, the
     * glow, fire, fluids, torches, vines) to the first with some; it rests in that cell when its shape ends below the
     * cell's top (a bottom slab), else in the one over it. A cell not loaded stops the search there.
     */
    static int restRow(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, y, z);
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c == null) return y + 1;
        int floor = Math.max(level.getMinBuildHeight(), y - DOWN_REACH);
        for (int yy = y; yy >= floor; yy--) {
            m.setY(yy);
            BlockState s = c.getBlockState(m);
            VoxelShape shape = s.getCollisionShape(c, m);
            if (shape.isEmpty()) continue;
            return shape.max(Direction.Axis.Y) < 1 ? yy : yy + 1;
        }
        return floor;
    }

    /** True when a player who is not a spectator comes within half a block of the column (x, z) between rows {@code fromY} and {@code toY}. */
    static boolean playerInColumn(Work w, int x, int z, int fromY, int toY) {
        for (ServerPlayer p : w.standing) {
            AABB b = p.getBoundingBox();
            if (Support.inColumn(x, z, fromY, toY, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) return true;
        }
        return false;
    }

    /** The player (not a spectator) who stands on the block at (x, y, z), or null ({@link Support#standsOn}). */
    @Nullable
    static ServerPlayer standsOn(Work w, int x, int y, int z) {
        for (ServerPlayer p : w.standing) {
            AABB b = p.getBoundingBox();
            if (Support.standsOn(x, y, z, b.minX, b.minY, b.minZ, b.maxX, b.maxZ)) return p;
        }
        return null;
    }

    /** True when lava stands in the column under {@code pos}, down to the first block that is not open. */
    private static boolean lavaUnder(ServerLevel level, BlockPos pos) {
        LevelChunk c = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (c == null) return true;
        BlockPos.MutableBlockPos m = pos.mutable();
        int floor = Math.max(level.getMinBuildHeight(), pos.getY() - DOWN_REACH);
        for (int y = pos.getY() - 1; y >= floor; y--) {
            m.setY(y);
            BlockState s = c.getBlockState(m);
            if (s.getFluidState().is(FluidTags.LAVA)) return true;
            int t = BlockKinds.traits(s, c, m);
            if ((t & (Support.T_AIRLIKE | Support.T_LIQUID | Support.T_NO_COLLISION)) == 0) return false;
        }
        return false;
    }

    /** The block state at {@code pos}, or null when its chunk is not loaded (never loads one). */
    @Nullable
    static BlockState stateAt(ServerLevel level, BlockPos pos) {
        LevelChunk c = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return c == null ? null : c.getBlockState(pos);
    }

    /** The kind of {@code pos} ({@link BlockKinds#kindAt}), or rock when its chunk is not loaded. */
    static int kindAt(ServerLevel level, BlockPos pos) {
        LevelChunk c = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (c == null) return Support.ROCK;
        return BlockKinds.kindAt(c, GalleryChanges.logOf(c), GalleryChanges.bottom(), GalleryChanges.seaTop(c), pos);
    }

    /** True when the block at {@code pos} falls by the rule now ({@link Support#falls}). */
    static boolean fallsNow(ServerLevel level, BlockPos pos, int depth) {
        LevelChunk c = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (c == null || !GalleryChanges.inLayer(pos.getY())) return false;
        ChangeLog log = GalleryChanges.logOf(c);
        int bottom = GalleryChanges.bottom(), seaTop = GalleryChanges.seaTop(c);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int p = BlockKinds.kindAt(c, log, bottom, seaTop, pos);
        return Support.falls(p, j -> BlockKinds.kindAt(c, log, bottom, seaTop, m.setWithOffset(pos, 0, -j, 0)), depth);
    }

    /** The block without its water: a falling block never carries a fluid. */
    private static BlockState dry(BlockState s) {
        return s.hasProperty(BlockStateProperties.WATERLOGGED) ? s.setValue(BlockStateProperties.WATERLOGGED, false) : s;
    }

    /** True for one half of a two-part block: a door, a tall plant, a bed. */
    private static boolean twoPart(BlockState s) {
        return s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || s.hasProperty(BlockStateProperties.BED_PART);
    }

    // ================================================================================================ at once, like sand

    /** Queues a position for the {@code toppleAtOnce} check (spec 4.3.8); a full queue leaves it for the next pass. */
    static void queue(BlockPos pos) {
        if (QUEUE.size() < QUEUE_MAX) QUEUE.add(pos.asLong());
    }

    /** How many positions wait in the {@code toppleAtOnce} queue. */
    static int queued() { return QUEUE.size(); }

    /**
     * The {@code toppleAtOnce} check of up to {@link #QUEUE_PER_TICK} queued positions: from each up while the blocks are
     * placed, climbing or poured (at most {@link #QUEUE_UP}), those that fall by the rule are taken at once by their ways,
     * top-down, with no creak; a column under a player gets a lowering job whose first step is the next tick.
     */
    static void atOnce(Work w) {
        int checked = 0;
        while (!QUEUE.isEmpty() && checked < QUEUE_PER_TICK && w.budget()) {
            long q = QUEUE.removeFirstLong();
            checked++;
            BlockPos base = BlockPos.of(q);
            if (!GalleryChanges.inLayer(base.getY())) continue;
            if (stateAt(w.level, base) == null || !w.level.isPositionEntityTicking(base)) continue;
            LongArrayList falling = new LongArrayList();
            BlockPos.MutableBlockPos m = base.mutable();
            for (int k = 0; k < QUEUE_UP && GalleryChanges.inLayer(m.getY()); k++, m.move(Direction.UP)) {
                if (!Support.topples(kindAt(w.level, m))) break;
                if (fallsNow(w.level, m, w.depth)) falling.add(m.asLong());
            }
            // top-down: the upper blocks go before the ones under them
            int start = columnStart((int) w.now, base.getX(), base.getZ());
            for (int i = falling.size() - 1, index = 0; i >= 0; i--, index++) {
                BlockPos p = BlockPos.of(falling.getLong(i));
                ServerPlayer on = standsOn(w, p.getX(), p.getY(), p.getZ());
                if (on != null) {
                    if (!Lowering.runningAt(p.getX(), p.getZ()) && Lowering.room()) {
                        LongArrayList ps = new LongArrayList();
                        for (int j = i; j >= 0; j--) ps.add(falling.getLong(j));
                        Lowering.add(new Lowering.Job(on.getUUID(), p.getX(), p.getZ(), null, w.now + 1, ps));
                    }
                    break;
                }
                if (!w.budget()) {
                    // out of budget this tick: the rest of the column is checked again next tick
                    queue(base);
                    break;
                }
                if (take(w, p, -1, false, Support.sideStart(start, index), w.spawned) != LEFT) w.taken++;
            }
        }
    }
}
