package com.explorationremix.mixin.rune;

import com.explorationremix.rune.world.RopeBlock;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Rope's second phase, Long rope: it is climbed twice as fast. The game climbs anything at one speed (0.2 a tick up);
 * on a fast rope it is twice that. Both sides, as a player's own game moves them.
 */
@Mixin(LivingEntity.class)
public abstract class RopeClimbMixin {
    @ModifyConstant(method = "handleRelativeFrictionAndCalculateMovement", constant = @Constant(doubleValue = 0.2D))
    private double explorationremix$fastRope(double up) {
        BlockState feet = ((LivingEntity) (Object) this).getFeetBlockState();
        return feet.getBlock() instanceof RopeBlock && feet.getValue(RopeBlock.FAST) ? up * 2.0 : up;
    }
}
