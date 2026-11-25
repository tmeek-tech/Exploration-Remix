package com.explorationremix.mixin.rune;

import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Quickdraw on a crossbow: it loads in half the time (after Quick Charge's own cut). Both sides, as both load it. */
@Mixin(CrossbowItem.class)
public abstract class CrossbowQuickdrawMixin {
    @Inject(method = "getChargeDuration", at = @At("RETURN"), cancellable = true)
    private static void explorationremix$quickdraw(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (RuneGear.passive(stack) == RuneId.QUICKDRAW) cir.setReturnValue(Math.max(1, cir.getReturnValueI() / 2));
    }
}
