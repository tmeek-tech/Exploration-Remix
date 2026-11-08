package com.explorationremix.mixin;

import com.explorationremix.combat.Stagger;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A creature that reels (its poise broken) stands as the dying do: its mind skips its step (no goals, no pathing, no
 * steering) and it puts no effort into moving, while knockback, gravity and its body's own tick go on, so it can still be
 * knocked about, and slammed. The game asks only in its living tick.
 */
@Mixin(LivingEntity.class)
public abstract class LivingImmobileMixin {
    @Inject(method = "isImmobile", at = @At("RETURN"), cancellable = true)
    private void er$reeling(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && Stagger.reeling((LivingEntity) (Object) this)) cir.setReturnValue(true);
    }
}
