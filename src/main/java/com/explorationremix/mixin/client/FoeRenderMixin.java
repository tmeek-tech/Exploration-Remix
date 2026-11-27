package com.explorationremix.mixin.client;

import com.explorationremix.client.foes.FoePoses;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's hostile mobs posed for their moves (0.23.8, client.foes): their parts once vanilla has set them up for the
 * draw (the layers that copy them, armour and held items, follow), and their whole body once it has been turned to
 * face its way.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class FoeRenderMixin {
    @Shadow protected EntityModel<?> model;

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER))
    private void er$foeParts(LivingEntity entity, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light,
                             CallbackInfo ci) {
        FoePoses.parts(entity, model, partialTicks);
    }

    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void er$foeBody(LivingEntity entity, PoseStack poseStack, float bob, float bodyYaw, float partialTicks, CallbackInfo ci) {
        FoePoses.body(entity, poseStack, partialTicks);
    }
}
