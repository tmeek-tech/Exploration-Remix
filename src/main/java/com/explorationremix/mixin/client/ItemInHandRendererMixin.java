package com.explorationremix.mixin.client;

import com.explorationremix.client.combat.RuneGlow;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A held item drawn in first or third person: a rune sword whose ready light is playing has the faces it is drawn with
 * gathered as it is drawn, and its light laid on them after (see RuneGlow).
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderItem", at = @At("HEAD"))
    private void explorationremix$bladeFaces(LivingEntity holder, ItemStack stack, ItemDisplayContext ctx, boolean leftHand, PoseStack pose,
                                             MultiBufferSource buffers, int light, CallbackInfo ci) {
        RuneGlow.beginItem(holder, stack, ctx, leftHand, pose);
    }

    @Inject(method = "renderItem", at = @At("TAIL"))
    private void explorationremix$bladeLight(LivingEntity holder, ItemStack stack, ItemDisplayContext ctx, boolean leftHand, PoseStack pose,
                                             MultiBufferSource buffers, int light, CallbackInfo ci) {
        RuneGlow.endItem(buffers);
    }
}
