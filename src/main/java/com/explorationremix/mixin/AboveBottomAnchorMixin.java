package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "above_bottom" anchors keep meaning the pack's old floor, so every feature and carver from vanilla, WWOO,
 * Tectonic or Create stays where its author put it instead of sliding 768 blocks down into the deep layers.
 */
@Mixin(VerticalAnchor.AboveBottom.class)
public abstract class AboveBottomAnchorMixin {
    @Inject(method = "resolveY", at = @At("HEAD"), cancellable = true)
    private void er$resolveAgainstPackFloor(WorldGenerationContext ctx, CallbackInfoReturnable<Integer> cir) {
        if (DeepWorld.installed() && ctx.getMinGenY() == DeepWorld.floorY()) {
            cir.setReturnValue(DeepWorld.packFloor() + ((VerticalAnchor.AboveBottom) (Object) this).offset());
        }
    }
}
