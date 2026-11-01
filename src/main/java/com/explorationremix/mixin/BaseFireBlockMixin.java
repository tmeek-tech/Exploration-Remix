package com.explorationremix.mixin;

import com.explorationremix.block.GlowBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fire may be lit where the Basalt Galleries' invisible glow stands, as in air: flint and steel or a fire charge used on
 * a block, and a dispenser holding flint and steel, all ask this first and then set the fire there, which takes the
 * glow's place. Vanilla allows only air, and the glow is not air to the game, so about one floor spot in 64 there would
 * refuse the flint and steel with nothing to show why. A fire charge shot from a dispenser or by a blaze
 * ({@code SmallFireball.onHitBlock} asks {@code isEmptyBlock}) and lava setting things alight still stop at a glow, as
 * at vanilla's light block; a portal still lights from fire set anywhere else in its frame ({@code PortalShapeMixin}).
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockMixin {
    @Shadow
    private static boolean isPortal(Level level, BlockPos pos, Direction direction) { throw new AssertionError(); }

    @Inject(method = "canBePlacedAt", at = @At("HEAD"), cancellable = true)
    private static void er$fireTakesGlow(Level level, BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (level.getBlockState(pos).getBlock() instanceof GlowBlock)
            cir.setReturnValue(BaseFireBlock.getState(level, pos).canSurvive(level, pos) || isPortal(level, pos, direction));
    }
}
