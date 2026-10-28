package com.explorationremix.mixin.accessor;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The surface rule context keeps the block position and the column scan state package-private. */
@Mixin(SurfaceRules.Context.class)
public interface SurfaceContextAccessor {
    @Accessor("blockX") int er$blockX();
    @Accessor("blockY") int er$blockY();
    @Accessor("blockZ") int er$blockZ();
    /** Solid blocks from the top of this run of rock down to this block, counting this one (1 = exposed above). */
    @Accessor("stoneDepthAbove") int er$stoneDepthAbove();
    /** Solid blocks from this block down to the bottom of the run, counting this one (1 = exposed below). */
    @Accessor("stoneDepthBelow") int er$stoneDepthBelow();
    /** The Y just above the fluid resting on this run of rock, or Integer.MIN_VALUE when air rests on it. */
    @Accessor("waterHeight") int er$waterHeight();
    @Accessor("chunk") ChunkAccess er$chunk();
}
