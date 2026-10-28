package com.explorationremix.mixin;

import com.explorationremix.config.ERConfig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Anvils repair with materials and rename for free: XP is a run currency, not a repair bill. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Shadow @Final private DataSlot cost;

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void er$freePickup(Player player, boolean hasStack, CallbackInfoReturnable<Boolean> cir) {
        if (ERConfig.INSTANCE.freeAnvils.get()) cir.setReturnValue(hasStack);
    }

    /** The "Too Expensive!" limit (the third 40 in createResult) goes away with free anvils. */
    @ModifyConstant(method = "createResult", constant = @Constant(intValue = 40, ordinal = 2))
    private int er$noTooExpensive(int limit) {
        return ERConfig.INSTANCE.freeAnvils.get() ? Integer.MAX_VALUE : limit;
    }

    @Inject(method = "createResult", at = @At("RETURN"))
    private void er$freeCost(CallbackInfo ci) {
        if (ERConfig.INSTANCE.freeAnvils.get()) this.cost.set(0);
    }
}
