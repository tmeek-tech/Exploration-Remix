package com.explorationremix.mixin;

import com.explorationremix.world.lava.GalleryChanges;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block change in a loaded chunk goes through {@code LevelChunk#setBlockState}: this tells the Basalt Galleries'
 * change log ({@link GalleryChanges}) first, so the terrain reset (0.5.1) can put the block back and the quakes can tell
 * a placed block from the rock. At the head, not the return: the block's own {@code onRemove} and {@code onPlace} may set
 * the same position again inside the call, and only here is the block it held before the outer change still there to
 * read. Costs one height check for a block outside the Galleries.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void er$recordGalleryChange(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> cir) {
        GalleryChanges.changing((LevelChunk) (Object) this, pos, state);
    }
}
