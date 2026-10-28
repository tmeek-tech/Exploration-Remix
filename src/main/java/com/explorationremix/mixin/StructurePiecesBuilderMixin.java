package com.explorationremix.mixin;

import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Keeps structures that spread "down to the world's floor" (mineshafts) above the first band. */
@Mixin(StructurePiecesBuilder.class)
public abstract class StructurePiecesBuilderMixin {
    @ModifyVariable(method = "moveBelowSeaLevel", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int er$clampMinY(int minY) {
        return DeepWorld.installed() && minY < DeepWorld.packFloor() ? Math.max(minY, ERConfig.INSTANCE.structureFloorY.get()) : minY;
    }

    @ModifyVariable(method = "moveInsideHeights", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int er$clampMinY2(int minY) {
        return DeepWorld.installed() && minY < DeepWorld.packFloor() ? Math.max(minY, ERConfig.INSTANCE.structureFloorY.get()) : minY;
    }
}
