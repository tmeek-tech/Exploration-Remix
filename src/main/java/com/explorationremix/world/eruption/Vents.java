package com.explorationremix.world.eruption;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.world.lava.GalleryChanges;
import com.explorationremix.world.lava.LavaFlood;
import com.explorationremix.world.terrain.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;

/**
 * Reading a Caldera vent from the world's blocks when it erupts (0.9.0, spec 5.1): the field's rows are not the world's
 * once bombs, quakes or players have been at it, and decorations sit on the crater floors. Every read goes through
 * {@code getChunkNow}: a chunk not loaded reads as nothing, and that vent cannot erupt now.
 *
 * <p>A cell is <b>open</b> when a bomb or a scan passes through it: air, the glow, fire, any liquid, or a block with no
 * collision (a torch, a vine). A liquid is open to the vent's scans (lava standing in a crater is noted on the way down);
 * the bombs' drop stops at lava ({@link #ground}).
 */
final class Vents {
    private Vents() {}

    /** The highest row a scan starts from: under the Galleries' top (y -689), whose open rows end at -693. */
    static final int TOP_CAP = -690;
    /** The crater floor must lie this many rows over the lava table, and at most {@link #TOP_HI}. */
    static final int TOP_LO = 30, TOP_HI = 70;
    /** A scan starts this many rows over the stack's height. */
    static final int OVER = 8;
    /** The open rows over the mouth are counted up to this many. */
    static final int ROOM_CAP = 80;
    /** Row of no lava. */
    static final int NONE = Integer.MIN_VALUE;

    /**
     * A vent as the world has it now: its crater floor's top block {@code top}, the highest top block on the ring just
     * outside the crater {@code rim} ({@link #NONE} when no ring point could be read), the mouth (the first row over both),
     * the top lava row standing in the crater or {@link #NONE}, the row the fountain and the bombs start from (the mouth,
     * or over the lava), and the open rows straight up from there (at most {@link #ROOM_CAP}).
     */
    record Read(int x, int z, int top, int rim, int mouthY, int lavaTop, int startY, int room) {}

    /**
     * Reads vent {@code v} from the world: null when its chunk is not loaded, when its crater cell is rock at the scan's
     * start (a closed stack), or when its floor does not lie {@link #TOP_LO} to {@link #TOP_HI} rows over the lava table.
     */
    @Nullable
    static Read read(ServerLevel level, LavaFlood.Band band, Net.Vent v) {
        int w = band.tableY();
        int from = Math.min(w + (int) Math.ceil(v.height) + OVER, TOP_CAP);
        int x = Mth.floor(v.x), z = Mth.floor(v.z);
        int[] axis = scan(level, x, z, from, w + TOP_LO);
        if (axis == null || axis[0] > w + TOP_HI) return null;
        int rim = NONE;
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int px = Mth.floor(v.x + (v.r + 1) * Math.cos(a)), pz = Mth.floor(v.z + (v.r + 1) * Math.sin(a));
            int[] p = scan(level, px, pz, from, w + TOP_LO);
            if (p != null) rim = Math.max(rim, p[0]);
        }
        int mouth = Math.max(axis[0], rim) + 1;
        int start = axis[1] == NONE ? mouth : Math.max(mouth, axis[1] + 1);
        return new Read(x, z, axis[0], rim, mouth, axis[1], start, room(level, x, start, z));
    }

    /**
     * Scans the column (x, z) down from row {@code from}, which must be open, through open cells to the first cell that is
     * not, no lower than {@code lowest}. Returns {that row, the first lava row passed or {@link #NONE}}, or null when the
     * chunk is not loaded, the start is not open or nothing stops the scan by {@code lowest}.
     */
    @Nullable
    static int[] scan(ServerLevel level, int x, int z, int from, int lowest) {
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c == null) return null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, from, z);
        if (!open(c, m, c.getBlockState(m))) return null;
        int lava = NONE;
        for (int y = from; y >= lowest; y--) {
            m.setY(y);
            BlockState s = c.getBlockState(m);
            if (!open(c, m, s)) return new int[]{y, lava};
            if (lava == NONE && s.getFluidState().is(FluidTags.LAVA)) lava = y;
        }
        return null;
    }

    /**
     * The ground a bomb comes down on in column (x, z): the first cell under {@code from} (scanning down through open
     * cells) that is not open or holds lava; {@code orElse} when the chunk is not loaded or nothing is found within the
     * layer.
     */
    static int ground(ServerLevel level, int x, int z, int from, int orElse) {
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c == null) return orElse;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, from, z);
        int bottom = GalleryChanges.bottom();
        for (int y = from; y >= bottom; y--) {
            m.setY(y);
            BlockState s = c.getBlockState(m);
            if (!open(c, m, s) || s.getFluidState().is(FluidTags.LAVA)) return y;
        }
        return orElse;
    }

    /** Open rows straight up from row {@code y} in column (x, z), within the layer, at most {@link #ROOM_CAP}. */
    static int room(ServerLevel level, int x, int y, int z) {
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c == null) return 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, y, z);
        int n = 0;
        while (n < ROOM_CAP && GalleryChanges.inLayer(y + n)) {
            m.setY(y + n);
            if (!open(c, m, c.getBlockState(m))) break;
            n++;
        }
        return n;
    }

    /**
     * Whether a player whose feet are in cell {@code feet} stands on a refuge ({@link BombFlight#refuge}), read from the
     * world; a cell whose chunk is not loaded reads as rock.
     */
    static boolean refuge(ServerLevel level, BlockPos feet) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        return BombFlight.refuge((dx, dy, dz) -> {
            int x = feet.getX() + dx, z = feet.getZ() + dz;
            LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (c == null) return BombFlight.SOLID;
            m.set(x, feet.getY() + dy, z);
            BlockState s = c.getBlockState(m);
            if (s.getFluidState().is(FluidTags.LAVA)) return BombFlight.LAVA;
            return open(c, m, s) ? BombFlight.OPEN : BombFlight.SOLID;
        });
    }

    /** True for a cell a scan passes through: air, the glow, fire, a liquid, or a block with no collision. */
    static boolean open(LevelChunk c, BlockPos p, BlockState s) {
        return GlowBlock.open(s) || s.getBlock() instanceof LiquidBlock || s.is(BlockTags.FIRE) || s.getCollisionShape(c, p).isEmpty();
    }
}
