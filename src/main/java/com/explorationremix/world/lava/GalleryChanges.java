package com.explorationremix.world.lava;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.HallGlowFeature;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;

/**
 * Records every change to the Basalt Galleries' blocks in the chunk's {@link ChangeLog}, so the terrain reset can put
 * the terrain back (0.5.1). Called by {@code LevelChunkMixin} at the head of {@code LevelChunk#setBlockState}, which
 * every block change in a loaded chunk goes through: players breaking and placing, explosions, falling blocks leaving
 * and landing, fire, pistons, buckets, and fluids flowing. The lava flood writes straight into the chunk's sections and
 * is never seen here; the reset turns recording off while it writes ({@link #setQuietly}). The quakes and eruptions
 * (0.9.0) read the log too ({@link #logOf}): a block whose terrain block was open is a placed one (or a fluid that flowed
 * there), and a block the log does not hold is the terrain's own.
 *
 * <p>Fluids are recorded like any other block (0.8.0; before, flowing fluid spreading, receding or changing its level was
 * left out): lava a player lets flow, a lava fall a player cuts or re-routes, a fall's flow over the halls' glow, and
 * Immersive Weathering's turning flowing lava to basalt or blackstone all go back with the reset, exactly. This no longer
 * churns, as nothing of the mod's own sets the terrain's fluids flowing: the flood's drain gives a fall back as it was
 * and wakes only lava that changed, and the reset writes fluids straight into the chunk with no fluid tick.
 *
 * <p>The log sees through the flood ({@link Restore#seeThrough}): a lava source the flood put there is recorded as the
 * air, the glow or the kept block (a fall's flowing lava, fire) it took.
 */
public final class GalleryChanges {
    private GalleryChanges() {}

    /** The Galleries' lowest and highest Y (nothing recorded while lo > hi), worked out when first needed. */
    private static int lo = 1, hi = 0;
    private static boolean ready;
    /** True while the reset writes: its own changes are not recorded. */
    private static boolean quiet;

    /** Forgets the layer's bounds (when the server stops: the next world may differ). */
    static void reset() {
        ready = false;
        lo = 1;
        hi = 0;
        quiet = false;
    }

    private static void setUp() {
        ready = true;
        lo = 1;
        hi = 0;
        if (!DeepWorld.installed()) return;
        Shape s = DeepWorld.shape();
        int g = Terrain.GALLERIES;
        if (s == null || s.layerCount <= g) return;
        lo = s.bottom(g);
        hi = s.top(g);
    }

    /** The Galleries' bottom Y the logs count from, or MIN_VALUE when there are no Galleries. */
    public static int bottom() {
        if (!ready) setUp();
        return lo <= hi ? lo : Integer.MIN_VALUE;
    }

    /** True for a Y inside the Galleries. */
    public static boolean inLayer(int y) {
        if (!ready) setUp();
        return y >= lo && y <= hi;
    }

    /** Sets a block without recording the change, or any change it sets off (the reset's own writes). */
    static void setQuietly(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        boolean was = quiet;
        quiet = true;
        try {
            level.setBlock(pos, state, flags);
        } finally {
            quiet = was;
        }
    }

    /** The block at {@code pos} of {@code chunk} is about to become {@code next} (from the mixin, on any thread the game calls it on). */
    public static void changing(LevelChunk chunk, BlockPos pos, BlockState next) {
        Level level = chunk.getLevel();
        if (level.isClientSide || quiet) return;
        int y = pos.getY();
        if (!ready) setUp();
        if (y < lo || y > hi || !ChangeLog.holds(y - lo)) return;
        if (level.dimension() != Level.OVERWORLD || !(level instanceof ServerLevel sl)) return;
        // the chunk's own thread only: a change made elsewhere (a mod writing from a worker) is left out rather than racing
        if (!sl.getServer().isSameThread()) return;
        LavaChunk d = LavaChunk.of(chunk);
        if (d == null) return;
        BlockState cur = chunk.getBlockState(pos);
        if (cur == next) return;
        ChangeLog log = d.log;
        log.align(lo);
        int x = pos.getX() & 15, z = pos.getZ() & 15;
        int key = ChangeLog.key(x, y - lo, z);
        int nextId = Block.getId(next);
        if (log.has(key)) {
            // a later change that brings the terrain's own block back: nothing left to put back
            if (log.state(key) == nextId) log.remove(key);
            return;
        }
        int gen = (int) LavaFlood.resetGen(sl);   // first: it finds the flood's band on a server's first change
        BlockState first = terrainBlock(d, cur, x, y, z);
        if (first == next) return;
        log.record(key, Block.getId(first), gen);
    }

    /**
     * The terrain's own block where the chunk holds {@code cur}: what the flood took (the air, the glow, or the kept
     * block), if the lava there is the flood's.
     */
    private static BlockState terrainBlock(LavaChunk d, BlockState cur, int x, int y, int z) {
        LavaFlood.Band b = LavaFlood.band();
        if (b == null) return cur;
        int i = y - b.tableY();
        if (i < 0 || i >= b.rise()) return cur;
        d.align(b.tableY());
        int keptId = d.kept.get(i, x, z);
        int kind = Restore.seeThrough(LavaFlood.kindOf(cur), d.mask.get(i, x, z), d.glow.get(i, x, z), keptId >= 0);
        return switch (kind) {
            case Flood.AIR -> kindOf(cur) == Flood.AIR ? cur : Blocks.AIR.defaultBlockState();
            case Flood.GLOW -> kindOf(cur) == Flood.GLOW ? cur : HallGlowFeature.bandGlow();
            case Restore.KEPT -> Block.stateById(keptId);
            default -> cur;
        };
    }

    private static int kindOf(BlockState s) { return LavaFlood.kindOf(s); }

    /**
     * Breaks the block the way a player's pick would (its loot, a container's contents and a shulker box's with it, the
     * break sound and particles) without recording the change, or any change it sets off.
     */
    static void destroyQuietly(ServerLevel level, BlockPos pos) {
        boolean was = quiet;
        quiet = true;
        try {
            level.destroyBlock(pos, true);
        } finally {
            quiet = was;
        }
    }

    // ================================================================================================ reading the log

    /**
     * The terrain's own block at {@code pos} as far as the log knows: the block it first held if it changed since the
     * last reset, or null when it has not (the block there now is the terrain's).
     */
    @Nullable
    public static BlockState original(LevelChunk chunk, BlockPos pos) {
        if (!inLayer(pos.getY()) || !ChangeLog.holds(pos.getY() - lo)) return null;
        LavaChunk d = LavaChunk.of(chunk);
        if (d == null) return null;
        d.log.align(lo);
        int s = d.log.state(ChangeLog.key(pos.getX() & 15, pos.getY() - lo, pos.getZ() & 15));
        return s == ChangeLog.NONE ? null : Block.stateById(s);
    }

    /**
     * The chunk's change log, lined up with the Galleries' bottom ({@link #bottom}, which its keys' dy counts from), or
     * null when the world has no Galleries or the chunk has no lava record (other dimensions, the client). For reading
     * only (0.9.0: the quakes and eruptions tell placed blocks from the terrain's by it): nothing but this class and the
     * reset writes it. Valid for this tick only; never keep it, as Forge never invalidates a chunk's capability. Callers
     * check {@link ChangeLog#holds} before {@link ChangeLog#key}, which throws.
     */
    @Nullable
    public static ChangeLog logOf(LevelChunk chunk) {
        int b = bottom();
        if (b == Integer.MIN_VALUE) return null;
        LavaChunk d = LavaChunk.of(chunk);
        if (d == null) return null;
        d.log.align(b);
        return d.log;
    }

    /**
     * How many blocks the chunk's lava stands over the lava table now, as the flood has applied it to the chunk's blocks
     * (its record's height), or 0 without a record or a lava table. The chunk's sea fills its open blocks from the
     * table's Y up to {@link #seaTop} less one.
     */
    public static int seaLevel(LevelChunk chunk) {
        LavaFlood.Band b = LavaFlood.band();
        if (b == null) return 0;
        LavaChunk d = LavaChunk.of(chunk);
        if (d == null) return 0;
        d.align(b.tableY());
        return d.level;
    }

    /**
     * The first Y over the chunk's lava: over the lake's rows (every open block under the lava table is the table's lava)
     * and over its share of the risen sea ({@link #seaLevel}). Integer.MIN_VALUE when the world has no lava table.
     */
    public static int seaTop(LevelChunk chunk) {
        LavaFlood.Band b = LavaFlood.band();
        return b == null ? Integer.MIN_VALUE : b.tableY() + seaLevel(chunk);
    }

    /**
     * Puts one logged position back at once, exactly as the terrain reset would, and drops its entry (0.9.0: a placed
     * block a quake topples under the lake, a pool or the risen sea melts back into the lava rather than falling through
     * it). The block comes back as the log's first block ({@link TerrainReset#resetAs} applied), or under the chunk's sea
     * as the sea itself with its flood records, written quietly (not recorded; flags 2 | 16; a fluid or the sea straight
     * into the chunk's section with no fluid tick), with the reset's smoke and hiss for a player within 48 blocks
     * ({@link TerrainReset#putBack}, {@link TerrainReset#showPutBack}).
     *
     * <p>Returns false and changes nothing when the log does not hold the position, when the block there has a block
     * entity (the reset breaks those itself once the lava is down at its table, so nothing they hold falls into lava), or
     * when the flood has not brought the chunk's lava to its height yet (a later look tries again). {@code chunk} must be
     * the chunk holding {@code pos}.
     */
    public static boolean giveBack(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        LavaFlood.Band band = LavaFlood.band();
        int b = bottom(), y = pos.getY();
        if (band == null || b == Integer.MIN_VALUE || !inLayer(y) || !ChangeLog.holds(y - b)) return false;
        if (pos.getX() >> 4 != chunk.getPos().x || pos.getZ() >> 4 != chunk.getPos().z) return false;
        LavaChunk d = LavaChunk.of(chunk);
        if (d == null) return false;
        d.log.align(b);
        int key = ChangeLog.key(pos.getX() & 15, y - b, pos.getZ() & 15);
        if (!d.log.has(key)) return false;
        d.align(band.tableY());
        // the record of the sea must be the true one, or a block put back over this chunk's sea would be flooded by a
        // neighbour's sea standing higher (the reset waits for the flood the same way)
        if (d.level != LavaFlood.target()) return false;
        if (chunk.getBlockState(pos).hasBlockEntity()) return false;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        TerrainReset.putBack(level, chunk, d, band, b, key, at);
        chunk.setUnsaved(true);
        TerrainReset.showPutBack(level, NearChunks.players(level, band), at.getX(), at.getY(), at.getZ(), true);
        return true;
    }
}
