package com.explorationremix.mixin.client;

import com.explorationremix.client.combat.SwingClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The attack button with a sword or an axe in hand runs the timed swings (SwingClient) instead of vanilla's attack,
 * except on a block the weapon is meant for with nothing to swing at: that is mined as ever.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void explorationremix$timedSwing(CallbackInfoReturnable<Boolean> cir) {
        if (SwingClient.press()) cir.setReturnValue(true);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void explorationremix$noMiningWhileSwinging(boolean held, CallbackInfo ci) {
        if (SwingClient.holdOnBlock(held)) ci.cancel();
    }
}
