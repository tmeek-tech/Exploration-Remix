package com.explorationremix.world.quake;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.world.lava.ChangeLog;
import com.explorationremix.world.lava.GalleryChanges;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;

/**
 * Reads {@link Support}'s kinds from the world (0.9.0): a block state's traits, and a position's kind from its chunk,
 * the chunk's change log and the chunk's sea top. Kept apart from {@link Support} so the rules stay pure and tested
 * offline; this is their one reader of real blocks, shared by the topple pass, the ceiling debris and the craters.
 */
public final class BlockKinds {
    private BlockKinds() {}

    /**
     * The traits of {@code s} standing at {@code pos} ({@link Support#T_AIRLIKE} and the rest). The collision shape is
     * the empty context's (no entity), so a ladder and scaffolding have one and a vine, a torch or a sign has none.
     */
    public static int traits(BlockState s, BlockGetter level, BlockPos pos) {
        int t = firstTraits(s);
        if (s.is(BlockTags.CLIMBABLE) || s.is(Blocks.POWDER_SNOW)) t |= Support.T_CLIMB;
        if (s.getCollisionShape(level, pos).isEmpty()) t |= Support.T_NO_COLLISION;
        return t;
    }

    /** The traits that matter in a first block the change log holds: open (air, the glow, fire) or a fluid, and a source. */
    public static int firstTraits(BlockState s) {
        int t = 0;
        if (GlowBlock.open(s) || s.is(BlockTags.FIRE)) t |= Support.T_AIRLIKE;
        if (s.getBlock() instanceof LiquidBlock) {
            t |= Support.T_LIQUID;
            if (s.getFluidState().isSource()) t |= Support.T_SOURCE;
        }
        return t;
    }

    /**
     * The kind of the position {@code pos} of {@code chunk} ({@link Support#kind}): {@code log} is the chunk's change
     * log ({@code GalleryChanges.logOf}, read this tick, or null for none), {@code bottom} the Galleries' bottom its keys
     * count from, and {@code seaTop} the chunk's sea top ({@code GalleryChanges.seaTop}). Outside the Galleries' rows
     * (bedrock under them, the Wasteland's unlogged blocks over them) a position is rock.
     */
    public static int kindAt(LevelChunk chunk, @Nullable ChangeLog log, int bottom, int seaTop, BlockPos pos) {
        int y = pos.getY();
        if (!GalleryChanges.inLayer(y)) return Support.ROCK;
        boolean held = false;
        int first = 0;
        int dy = y - bottom;
        if (log != null && ChangeLog.holds(dy)) {
            int s = log.state(ChangeLog.key(pos.getX() & 15, dy, pos.getZ() & 15));
            if (s != ChangeLog.NONE) {
                held = true;
                first = firstTraits(Block.stateById(s));
            }
        }
        return Support.kind(traits(chunk.getBlockState(pos), chunk, pos), held, first, y < seaTop);
    }
}
