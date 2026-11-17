package com.explorationremix.mixin;

import com.explorationremix.world.compat.TerrainSlabsCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block a feature or structure writes while the world generates goes through {@code WorldGenRegion#setBlock}:
 * this keeps Terrain Slabs' laid slabs true to the ground they lie on ({@link TerrainSlabsCompat}). Does nothing without
 * Terrain Slabs.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    /** How many chunks from its centre the region may write to (one, for features). */
    @Shadow @Final private int writeRadiusCutoff;

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void er$refuseSlab(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (TerrainSlabsCompat.on() && !TerrainSlabsCompat.allow((WorldGenRegion) (Object) this, pos, state)) cir.setReturnValue(false);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void er$slabsFollow(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (TerrainSlabsCompat.on() && cir.getReturnValueZ()) {
            TerrainSlabsCompat.written((WorldGenRegion) (Object) this, pos, state, writeRadiusCutoff);
        }
    }
}
