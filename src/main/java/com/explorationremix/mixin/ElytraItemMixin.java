package com.explorationremix.mixin;

import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Elytra close below the pack's floor, so no one glides down a gate or across a layer. */
@Mixin(ElytraItem.class)
public abstract class ElytraItemMixin {
    @Inject(method = "canElytraFly", at = @At("HEAD"), cancellable = true, remap = false)
    private void er$noDeepFlight(ItemStack stack, LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (ERConfig.INSTANCE.elytraBelowBand.get() || !DeepWorld.installed()) return;
        if (entity.level().dimension() != Level.OVERWORLD || entity.getBlockY() >= DeepWorld.packFloor()) return;
        if (entity instanceof ServerPlayer p && p.isFallFlying()) {
            p.displayClientMessage(Component.translatable("run.explorationremix.no_elytra").withStyle(ChatFormatting.GRAY), true);
        }
        cir.setReturnValue(false);
    }
}
