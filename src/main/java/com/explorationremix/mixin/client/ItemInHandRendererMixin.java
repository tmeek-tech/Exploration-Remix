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

/** After a held item is drawn, in first or third person: a rune sword's light over its blade (see RuneGlow). */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderItem", at = @At("TAIL"))
    private void explorationremix$bladeLight(LivingEntity holder, ItemStack stack, ItemDisplayContext ctx, boolean leftHand, PoseStack pose,
                                             MultiBufferSource buffers, int light, CallbackInfo ci) {
        RuneGlow.draw(holder, stack, ctx, leftHand, pose, buffers);
    }
}
