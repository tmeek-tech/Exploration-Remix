package com.explorationremix.mixin.foes;

import com.explorationremix.combat.foes.Foes;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An enderman winding up or striking (0.23.8) doesn't flit away mid blow: being stared at, rained on or shot waits for it. */
@Mixin(EnderMan.class)
public abstract class EnderManHoldMixin {
    @Inject(method = "teleport(DDD)Z", at = @At("HEAD"), cancellable = true)
    private void er$notMidBlow(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (Foes.inMove((EnderMan) (Object) this)) cir.setReturnValue(false);
    }
}
