package com.explorationremix.world.quake;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERTags;
import com.explorationremix.world.lava.ChangeLog;
import com.explorationremix.world.lava.Falling;
import com.explorationremix.world.lava.GalleryChanges;
import com.explorationremix.world.lava.LavaFlood;
import com.explorationremix.world.lava.LavaNet;
import com.explorationremix.world.lava.NearChunks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ceiling debris (0.9.0, spec 4.4): in a big quake, and only with the terrain reset putting the holes back
 * ({@code terrainDamage}), natural ceiling rock comes loose round the players and falls.
 *
 * <ul>
 *     <li><b>Picks</b> (4.4.1). For each actor whose feet are in the layer, {@code round(debrisPerQuake × strength)}
 *     picks at times spread evenly over [10, duration - 40] ticks into the quake (one who comes later gets the picks still
 *     to come). Each draws a column round the player where they are then: 5 % within a block (overhead: the warning
 *     gives time to step aside), {@code debrisNear} from 1.5 to 8 blocks, the rest spread evenly over the area from 8 to
 *     {@code debrisRadius}; up to 8 columns are tried with {@link DebrisSearch}. A quarter of the picks that find a block
 *     also take 1 to 3 of its four neighbours that pass the same tests at the same row (a piece of a column end). At
 *     most 2 picks a tick, 32 blocks for each player and {@code debrisMax} for the quake. A column holding a block of the
 *     quake that waits or falls is skipped, and so is any within 2 blocks of one that landed.</li>
 *     <li><b>Warning and drop</b> (4.4.3). A pick sends a {@link LavaNet.Debris} WARN to the players within 160 blocks
 *     and drops the block {@code debrisWarnTicks} later, if then the terrain damage is still on, the block is loaded on
 *     entity-ticking ground, still the same natural block with open space under it, no fluid next to it and somewhere to
 *     land ({@link DebrisSearch#at}); else a CANCEL goes out. It drops as a falling block that never makes an item (a
 *     block that cannot land vanishes; its hole is logged and comes back), tagged and tracked by {@link Falling}, which
 *     does its hits.</li>
 *     <li><b>Holds.</b> While its quake is held a block waits (its drop time moves on) or hangs in the air; a WARN with
 *     the blocks it has fallen goes out at the hold, and one with -1 at the release. A drop staged by
 *     {@code /remix quake drop ... above N} hangs once it is N blocks over its landing row, until
 *     {@code /remix quake release}.</li>
 * </ul>
 */
final class Debris {
    private Debris() {}

    /** The picks are spread over this window: from this many ticks into the quake to this many before its end. */
    static final int PICKS_FROM = 10, PICKS_TO_END = 40;
    /** Most picks in one tick, over all players. */
    static final int PICKS_PER_TICK = 2;
    /** Most blocks for one player in one quake. */
    static final int PER_PLAYER = 32;
    /** Columns tried for one pick. */
    static final int TRIES = 8;
    /** No block comes down within this many blocks (either way) of one of the same quake that landed. */
    static final int APART = 2;
    /** Share of picks overhead, within a block of the player. */
    static final double OVERHEAD = 0.05;
    /** The near picks' band, in blocks from the player. */
    static final double NEAR_FROM = 1.5, NEAR_TO = 8;
    /** How far from its position {@code /remix quake drop} looks, in blocks. */
    static final int COMMAND_REACH = 8;

    private static final Direction[] SIDES = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};

    /** What one quake's debris has done so far. */
    static final class Plan {
        /** For each player: picks done (or skipped), blocks taken. */
        final Map<UUID, int[]> players = new HashMap<>();
        int blocks;
        /** The columns of the blocks that landed. */
        final LongArrayList landed = new LongArrayList();
    }

    /** One block warned of: waiting to drop, or in the air. */
    static final class Piece implements Falling.Owner {
        /** The quake it came loose in, or null for one the command staged. */
        @Nullable final Quakes.Event event;
        final BlockPos pos;
        final int state;
        /** The tick it drops at; while it is held, moved on a tick each tick. */
        long dropAt;
        int landY, lavaY;
        /** For a staged drop, how far over its landing row it hangs; else -1. */
        final int above;
        @Nullable FallingBlockEntity entity;
        /** A staged drop hangs now; it has been let go by the command; its quakes were turned off. */
        boolean hanging, released, stopped;

        Piece(@Nullable Quakes.Event event, BlockPos pos, int state, long dropAt, int landY, int lavaY, int above) {
            this.event = event;
            this.pos = pos;
            this.state = state;
            this.dropAt = dropAt;
            this.landY = landY;
            this.lavaY = lavaY;
            this.above = above;
        }

        /** Whether it waits or hangs now (its quake is held, or it is a staged drop hanging). */
        boolean held() {
            if (stopped) return false;
            return event != null && event.held >= 0 || hanging && !released;
        }

        /** Blocks it has fallen so far. */
        int fallen() {
            FallingBlockEntity e = entity;
            return e == null ? 0 : Math.max(0, (int) Math.floor(pos.getY() - e.getY() + 1e-3));
        }

        LavaNet.Debris message(byte kind, int held) {
            return new LavaNet.Debris(kind, pos.asLong(), state, dropAt, landY, lavaY, held);
        }

        @Override
        public boolean holds(FallingBlockEntity e) {
            if (stopped) return false;
            if (event != null) return event.held >= 0;
            if (above < 0 || released) return false;
            if (!hanging) {
                double stop = landY + above;
                if (e.getY() + e.getDeltaMovement().y - Falling.GRAVITY > stop) return false;
                // it would pass the row this tick: it hangs there
                if (e.getY() > stop) e.setPos(e.getX(), stop, e.getZ());
                hanging = true;
                if (e.level() instanceof ServerLevel level) tell(level, message(LavaNet.Debris.WARN, fallen()));
            }
            return true;
        }

        @Override
        public void landed(FallingBlockEntity e, BlockPos at) {
            PIECES.remove(this);
            if (event != null && event.drops != null) event.drops.landed.add(column(at.getX(), at.getZ()));
        }

        @Override
        public void shattered(FallingBlockEntity e, BlockPos at) {
            PIECES.remove(this);
            if (event != null) event.shattered++;
        }

        @Override
        public void gone(FallingBlockEntity e) { PIECES.remove(this); }
    }

    /** The blocks warned of, waiting to drop or in the air. */
    private static final List<Piece> PIECES = new ArrayList<>();

    /** Whether the terrain reset puts holes back, which ceiling debris needs (read live). */
    static boolean terrainDamage() {
        ERConfig cfg = ERConfig.INSTANCE;
        return cfg.resetEnabled.get() && cfg.lavaCycleEnabled.get();
    }

    // ================================================================================================ picks

    /** One tick of a big quake that is not held: the picks that are due for its actors. */
    static void picks(ServerLevel level, Quakes.Event e, List<ServerPlayer> actors, List<ServerPlayer> watchers, long now) {
        if (e.kind != QuakeSchedule.BIG || actors.isEmpty() || !terrainDamage()) return;
        LavaFlood.Band band = LavaFlood.band();
        if (band == null) return;
        ERConfig cfg = ERConfig.INSTANCE;
        int n = (int) Math.round(cfg.quakeDebrisPerQuake.get() * (double) e.strength);
        int max = cfg.quakeDebrisMax.get();
        if (n <= 0 || max <= 0) return;
        Plan plan = e.drops;
        if (plan == null) plan = e.drops = new Plan();
        int into = e.into(now);
        int due = dueBy(into, n, e.duration);
        int budget = PICKS_PER_TICK;
        Reader reader = new Reader(level);
        for (ServerPlayer p : actors) {
            if (budget <= 0) return;
            if (!GalleryChanges.inLayer(Mth.floor(p.getY()))) continue;
            int[] a = plan.players.get(p.getUUID());
            if (a == null) {
                // the picks before the player came are not theirs
                a = new int[]{dueBy(into - 1, n, e.duration), 0};
                plan.players.put(p.getUUID(), a);
            }
            while (a[0] < due && budget > 0) {
                if (a[1] >= PER_PLAYER || plan.blocks >= max) {
                    a[0] = due;
                    break;
                }
                a[0]++;
                budget--;
                pick(level, reader, band, e, plan, a, p, watchers, now, max);
            }
        }
    }

    /** The tick into a quake of {@code duration} ticks that pick {@code i} of {@code n} comes at. */
    static int pickTime(int i, int n, int duration) {
        int span = Math.max(0, duration - PICKS_TO_END - PICKS_FROM);
        return n <= 1 ? PICKS_FROM + span / 2 : PICKS_FROM + (int) ((long) i * span / (n - 1));
    }

    /** How many of {@code n} picks are due by tick {@code into} of the quake. */
    static int dueBy(int into, int n, int duration) {
        int c = 0;
        for (int i = 0; i < n; i++) if (pickTime(i, n, duration) <= into) c++;
        return c;
    }

    /** One pick round the player: up to {@link #TRIES} columns, and perhaps the found block's neighbours. */
    private static void pick(ServerLevel level, Reader reader, LavaFlood.Band band, Quakes.Event e, Plan plan, int[] a, ServerPlayer p,
                             List<ServerPlayer> watchers, long now, int max) {
        RandomSource r = level.getRandom();
        double near = ERConfig.INSTANCE.quakeDebrisNear.get();
        double far = Math.max(NEAR_TO, ERConfig.INSTANCE.quakeDebrisRadius.get());
        double u = r.nextDouble();
        int y0 = Mth.floor(p.getY()) + 2;
        for (int k = 0; k < TRIES; k++) {
            double d = u < OVERHEAD ? r.nextDouble()
                    : u < OVERHEAD + near ? NEAR_FROM + r.nextDouble() * (NEAR_TO - NEAR_FROM)
                    : Math.sqrt(NEAR_TO * NEAR_TO + r.nextDouble() * (far * far - NEAR_TO * NEAR_TO));
            double ang = r.nextDouble() * Math.PI * 2;
            int x = Mth.floor(p.getX() + d * Math.cos(ang)), z = Mth.floor(p.getZ() + d * Math.sin(ang));
            if (busy(e, x, z) || nearLanded(plan, x, z)) continue;
            DebrisSearch.Found f = DebrisSearch.search(reader.column(x, z), y0, band.top(), band.bottom());
            if (f == null) continue;
            BlockPos c = new BlockPos(x, f.y(), z);
            if (!level.isPositionEntityTicking(c)) continue;
            warn(level, e, plan, a, c, f, watchers, now);
            if (r.nextInt(4) != 0) return;
            // a piece of a column end breaking off: 1 to 3 of its neighbours at the same row
            int more = 1 + r.nextInt(3), start = r.nextInt(4);
            for (int j = 0; j < 4 && more > 0; j++) {
                if (a[1] >= PER_PLAYER || plan.blocks >= max) return;
                Direction dir = SIDES[(start + j) & 3];
                int nx = x + dir.getStepX(), nz = z + dir.getStepZ();
                if (busy(e, nx, nz) || nearLanded(plan, nx, nz)) continue;
                DebrisSearch.Found g = DebrisSearch.search(reader.column(nx, nz), y0, band.top(), band.bottom());
                if (g == null || g.y() != f.y()) continue;
                BlockPos cn = new BlockPos(nx, g.y(), nz);
                if (!level.isPositionEntityTicking(cn)) continue;
                warn(level, e, plan, a, cn, g, watchers, now);
                more--;
            }
            return;
        }
    }

    /** A block comes loose: it is warned of now and drops {@code debrisWarnTicks} on. */
    private static void warn(ServerLevel level, Quakes.Event e, Plan plan, int[] a, BlockPos c, DebrisSearch.Found f,
                             List<ServerPlayer> watchers, long now) {
        BlockState s = Topple.stateAt(level, c);
        if (s == null) return;
        Piece pc = new Piece(e, c, Block.getId(s), now + ERConfig.INSTANCE.quakeDebrisWarnTicks.get(), f.landY(), f.lavaY(), -1);
        PIECES.add(pc);
        plan.blocks++;
        a[1]++;
        Falling.send(watchers, pc.message(LavaNet.Debris.WARN, -1), c, LavaNet.Debris.WARN_RANGE);
    }

    /** True when a block of the quake {@code e} waits or falls in the column (x, z). */
    private static boolean busy(@Nullable Quakes.Event e, int x, int z) {
        for (Piece pc : PIECES) if (pc.event == e && pc.pos.getX() == x && pc.pos.getZ() == z) return true;
        return false;
    }

    /** True when a block of the quake landed within {@link #APART} blocks of the column (x, z). */
    private static boolean nearLanded(Plan plan, int x, int z) {
        for (int i = 0; i < plan.landed.size(); i++) {
            long c = plan.landed.getLong(i);
            if (Math.abs(ChunkPos.getX(c) - x) <= APART && Math.abs(ChunkPos.getZ(c) - z) <= APART) return true;
        }
        return false;
    }

    private static long column(int x, int z) { return ChunkPos.asLong(x, z); }

    // ================================================================================================ drops

    /** Every tick the quakes run: the blocks whose time has come drop, or are called off. */
    static void tick(ServerLevel level, List<ServerPlayer> watchers, long now) {
        if (PIECES.isEmpty()) return;
        LavaFlood.Band band = LavaFlood.band();
        Reader reader = new Reader(level);
        List<Piece> due = null;
        for (Iterator<Piece> it = PIECES.iterator(); it.hasNext(); ) {
            Piece pc = it.next();
            if (pc.held()) {
                pc.dropAt++;
                continue;
            }
            if (pc.entity != null || now < pc.dropAt) continue;
            if (band == null || !dropOk(level, reader, band, pc)) {
                it.remove();
                Falling.send(watchers, pc.message(LavaNet.Debris.CANCEL, -1), pc.pos, LavaNet.Debris.WARN_RANGE);
                continue;
            }
            if (due == null) due = new ArrayList<>();
            due.add(pc);
        }
        // let go after the loop: a block that breaks at once on someone takes itself off the list
        if (due != null) for (Piece pc : due) drop(level, pc);
    }

    /** The checks at the drop (spec 4.4.3); a landing found again is kept. */
    private static boolean dropOk(ServerLevel level, Reader reader, LavaFlood.Band band, Piece pc) {
        if (!terrainDamage() || !level.isPositionEntityTicking(pc.pos)) return false;
        BlockState s = Topple.stateAt(level, pc.pos);
        if (s == null || Block.getId(s) != pc.state) return false;
        DebrisSearch.Found f = DebrisSearch.at(reader.column(pc.pos.getX(), pc.pos.getZ()), pc.pos.getY(), band.top(), band.bottom());
        if (f == null) return false;
        pc.landY = f.landY();
        pc.lavaY = f.lavaY();
        return true;
    }

    /** Lets the block go: a falling block that never makes an item, tagged and tracked. */
    private static void drop(ServerLevel level, Piece pc) {
        // fall() writes the block's fluid where it stood, which is none (it was checked): air, which the log records
        FallingBlockEntity fe = FallingBlockEntity.fall(level, pc.pos, Block.stateById(pc.state));
        fe.dropItem = false;
        fe.addTag(Falling.TAG);
        pc.entity = fe;
        if (pc.event != null) pc.event.debris++;
        Falling.track(fe, Falling.DEBRIS, pc);
    }

    // ================================================================================================ holds, the command

    /** The quake {@code e} is held: its blocks are told where they wait or hang. */
    static void held(ServerLevel level, Quakes.Event e) {
        for (Piece pc : PIECES) if (pc.event == e) tell(level, pc.message(LavaNet.Debris.WARN, pc.fallen()));
    }

    /** The quake {@code e} goes on: its blocks are told when they drop (or dropped) now. */
    static void released(ServerLevel level, Quakes.Event e) {
        for (Piece pc : PIECES) if (pc.event == e) tell(level, pc.message(LavaNet.Debris.WARN, -1));
    }

    /** {@code /remix quake release}: every staged drop is let go. Returns how many there were. */
    static int releaseStaged(ServerLevel level) {
        int n = 0;
        for (Piece pc : PIECES) {
            if (pc.event != null || pc.above < 0 || pc.released) continue;
            pc.released = true;
            n++;
            if (pc.hanging) tell(level, pc.message(LavaNet.Debris.WARN, -1));
        }
        return n;
    }

    /**
     * {@code /remix quake drop}: a staged ceiling drop for pictures, searched from {@code from} (its row + 2 up) in the
     * columns within {@link #COMMAND_REACH} blocks, nearest first; warned of and dropped as a quake's, hanging
     * {@code above} blocks over its landing row (-1: it does not hang). Returns it, or null when no column has one.
     */
    @Nullable
    static Piece stage(ServerLevel level, BlockPos from, int above, List<ServerPlayer> watchers, long now) {
        LavaFlood.Band band = LavaFlood.band();
        if (band == null) return null;
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -COMMAND_REACH; dx <= COMMAND_REACH; dx++) {
            for (int dz = -COMMAND_REACH; dz <= COMMAND_REACH; dz++) {
                if (dx * dx + dz * dz <= COMMAND_REACH * COMMAND_REACH) offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        Reader reader = new Reader(level);
        for (int[] o : offsets) {
            int x = from.getX() + o[0], z = from.getZ() + o[1];
            if (busyAny(x, z)) continue;
            DebrisSearch.Found f = DebrisSearch.search(reader.column(x, z), from.getY() + 2, band.top(), band.bottom());
            if (f == null) continue;
            BlockPos c = new BlockPos(x, f.y(), z);
            BlockState s = Topple.stateAt(level, c);
            if (s == null || !level.isPositionEntityTicking(c)) continue;
            Piece pc = new Piece(null, c, Block.getId(s), now + ERConfig.INSTANCE.quakeDebrisWarnTicks.get(), f.landY(), f.lavaY(),
                    above < 0 ? -1 : Math.min(above, f.height()));
            PIECES.add(pc);
            Falling.send(watchers, pc.message(LavaNet.Debris.WARN, -1), c, LavaNet.Debris.WARN_RANGE);
            return pc;
        }
        return null;
    }

    private static boolean busyAny(int x, int z) {
        for (Piece pc : PIECES) if (pc.pos.getX() == x && pc.pos.getZ() == z) return true;
        return false;
    }

    /** Sends a debris message to the watchers within reach of a WARN. */
    private static void tell(ServerLevel level, LavaNet.Debris d) {
        LavaFlood.Band band = LavaFlood.band();
        if (band == null) return;
        Falling.send(NearChunks.players(level, band), d, BlockPos.of(d.pos()), LavaNet.Debris.WARN_RANGE);
    }

    /** Gate 1: the blocks waiting are called off; those in the air are let go and fall on, tracked for their hits. */
    static void stop(List<ServerPlayer> watchers) {
        for (Piece pc : PIECES) {
            pc.stopped = true;
            if (pc.entity == null) Falling.send(watchers, pc.message(LavaNet.Debris.CANCEL, -1), pc.pos, LavaNet.Debris.WARN_RANGE);
        }
        PIECES.clear();
    }

    /** How many blocks are warned of, waiting to drop or in the air. */
    static int pending() { return PIECES.size(); }

    /** When the server stops: everything is forgotten. */
    static void reset() { PIECES.clear(); }

    // ================================================================================================ the reader

    /**
     * Reads the world for {@link DebrisSearch}, one column at a time: each cell's flags from its block state, its
     * collision for the empty context, its fluid and the change log. A chunk not loaded reads as {@link
     * DebrisSearch#UNKNOWN}, and a chunk with no change log holds everything (nothing there could come back).
     */
    private static final class Reader implements DebrisSearch.Cells {
        private final ServerLevel level;
        private final int bottom = GalleryChanges.bottom();
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        private int x, z;
        private long chunkKey = Long.MIN_VALUE;
        @Nullable private LevelChunk chunk;
        @Nullable private ChangeLog log;

        Reader(ServerLevel level) { this.level = level; }

        Reader column(int x, int z) {
            this.x = x;
            this.z = z;
            return this;
        }

        @Override
        public int at(int dx, int y, int dz) {
            int bx = x + dx, bz = z + dz;
            long key = ChunkPos.asLong(bx >> 4, bz >> 4);
            if (key != chunkKey) {
                chunkKey = key;
                chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
                log = chunk == null ? null : GalleryChanges.logOf(chunk);
            }
            LevelChunk c = chunk;
            if (c == null) return DebrisSearch.UNKNOWN;
            m.set(bx, y, bz);
            BlockState s = c.getBlockState(m);
            int f = 0;
            if (GlowBlock.open(s)) f |= DebrisSearch.OPEN;
            VoxelShape shape = s.getCollisionShape(c, m);
            if (shape.isEmpty()) f |= DebrisSearch.EMPTY;
            else if (shape.max(Direction.Axis.Y) < 1) f |= DebrisSearch.LOW;
            FluidState fl = s.getFluidState();
            if (!fl.isEmpty()) {
                f |= DebrisSearch.FLUID;
                if (fl.isSource()) f |= DebrisSearch.SOURCE;
                if (fl.is(FluidTags.LAVA)) f |= DebrisSearch.LAVA;
            }
            if (s.getBlock() instanceof LiquidBlock) f |= DebrisSearch.LIQUID;
            int dy = y - bottom;
            if (GalleryChanges.inLayer(y) && (log == null || !ChangeLog.holds(dy) || log.has(ChangeLog.key(bx & 15, dy, bz & 15)))) {
                f |= DebrisSearch.HELD;
            }
            if (s.is(ERTags.QUAKE_DEBRIS)) f |= DebrisSearch.DEBRIS;
            if (s.hasProperty(BlockStateProperties.WATERLOGGED)) f |= DebrisSearch.WATERLOGGABLE;
            return f;
        }
    }
}
