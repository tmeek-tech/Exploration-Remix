package com.explorationremix.mixin;

import com.explorationremix.block.GlowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Basalt Galleries' glow is open space inside a nether portal's frame: the portal lights round it and takes its
 * place. Vanilla counts only air, fire and the portal itself as empty, and the glow is not air to the game, so a frame
 * built round one of the invisible glow blocks (one floor spot in 64 there holds one) would never light.
 */
@Mixin(PortalShape.class)
public abstract class PortalShapeMixin {
    @Inject(method = "isEmpty(Lnet/minecraft/world/level/block/state/BlockState;)Z", at = @At("HEAD"), cancellable = true)
    private static void er$glowIsEmpty(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (state.getBlock() instanceof GlowBlock) cir.setReturnValue(true);
    }
}
