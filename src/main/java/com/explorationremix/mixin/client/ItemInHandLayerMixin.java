package com.explorationremix.mixin.client;

import com.explorationremix.client.combat.SwingPoses;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A held blade seen from outside, in a timed swing: turned in the fist towards the line of the arm (SwingPoses), so a
 * thrust drives the point forward and a cut reaches out along the sweep, where vanilla would hold it square to the arm.
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void explorationremix$swingGrip(LivingEntity holder, ItemStack stack, ItemDisplayContext ctx, HumanoidArm arm, PoseStack pose,
                                           MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (!(((RenderLayer<?, ?>) (Object) this).getParentModel() instanceof HumanoidModel<?> model)) return;
        float armX = arm == HumanoidArm.RIGHT ? model.rightArm.xRot : model.leftArm.xRot;
        float turn = SwingPoses.gripTurn(holder, arm, armX);
        if (turn != 0.0F) pose.mulPose(Axis.XP.rotation(turn));
    }
}
