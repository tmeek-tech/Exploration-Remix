package com.explorationremix.world.lava;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.HallGlowFeature;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;

/**
 * Records every change to the Basalt Galleries' blocks in the chunk's {@link ChangeLog}, so the terrain reset can put
 * the terrain back (0.5.1). Called by {@code LevelChunkMixin} at the head of {@code LevelChunk#setBlockState}, which
 * every block change in a loaded chunk goes through: players breaking and placing, explosions, falling blocks leaving
 * and landing, fire, pistons, buckets. The lava flood writes straight into the chunk's sections and is never seen here;
 * the reset turns recording off while it writes ({@link #setQuietly}). The quakes read the log too: a block whose
 * terrain block was open is a placed one.
 *
 * <p>Left out, so the log never churns with fluids settling: flowing (not source) fluid spreading into open space or
 * into other fluid, and flowing fluid going back to open space or changing its level. A lava fall's flow over a block
 * of the halls' glow is therefore not put back (as in 0.5.0). A source placed from a bucket is recorded.
 *
 * <p>The log sees through the flood ({@link Restore#seeThrough}): a lava source the flood put there is recorded as the
 * air, or the glow, it took.
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
        if (y < lo || y > hi) return;
        if (level.dimension() != Level.OVERWORLD || !(level instanceof ServerLevel sl)) return;
        // the chunk's own thread only: a change made elsewhere (a mod writing from a worker) is left out rather than racing
        if (!sl.getServer().isSameThread()) return;
        LavaChunk d = LavaChunk.of(chunk);
        if (d == null) return;
        BlockState cur = chunk.getBlockState(pos);
        if (cur == next || settling(cur, next)) return;
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

    /** True for a change of fluid settling, which the log leaves out (see the class comment). */
    static boolean settling(BlockState cur, BlockState next) {
        if (flowing(next) && (GlowBlock.open(cur) || cur.getBlock() instanceof LiquidBlock)) return true;
        return flowing(cur) && (GlowBlock.open(next) || flowing(next));
    }

    private static boolean flowing(BlockState s) { return s.getBlock() instanceof LiquidBlock && !s.getFluidState().isSource(); }

    /** The terrain's own block where the chunk holds {@code cur}: the air or glow the flood took, if the lava there is the flood's. */
    private static BlockState terrainBlock(LavaChunk d, BlockState cur, int x, int y, int z) {
        LavaFlood.Band b = LavaFlood.band();
        if (b == null) return cur;
        int i = y - b.tableY();
        if (i < 0 || i >= b.rise()) return cur;
        d.align(b.tableY());
        int kind = Restore.seeThrough(LavaFlood.kindOf(cur), d.mask.get(i, x, z), d.glow.get(i, x, z));
        return switch (kind) {
            case Flood.AIR -> kindOf(cur) == Flood.AIR ? cur : Blocks.AIR.defaultBlockState();
            case Flood.GLOW -> kindOf(cur) == Flood.GLOW ? cur : HallGlowFeature.bandGlow();
            default -> cur;
        };
    }

    private static int kindOf(BlockState s) { return LavaFlood.kindOf(s); }

    // ================================================================================================ reading the log

    /**
     * The terrain's own block at {@code pos} as far as the log knows: the block it first held if it changed since the
     * last reset, or null when it has not (the block there now is the terrain's).
     */
    @Nullable
    public static BlockState original(LevelChunk chunk, BlockPos pos) {
        if (!inLayer(pos.getY())) return null;
        LavaChunk d = LavaChunk.of(chunk);
        if (d == null) return null;
        d.log.align(lo);
        int s = d.log.state(ChangeLog.key(pos.getX() & 15, pos.getY() - lo, pos.getZ() & 15));
        return s == ChangeLog.NONE ? null : Block.stateById(s);
    }
}
