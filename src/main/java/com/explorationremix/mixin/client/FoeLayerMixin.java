package com.explorationremix.mixin.client;

import com.explorationremix.client.foes.FoePoses;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A layer that poses its own copy of the model (a stray's clothes, a drowned's outer skin) is posed as its mob is (0.23.8). */
@Mixin(RenderLayer.class)
public abstract class FoeLayerMixin {
    @Inject(method = "coloredCutoutModelCopyLayerRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V", shift = At.Shift.AFTER))
    private static void er$foeParts(EntityModel<?> parent, EntityModel<?> model, ResourceLocation texture, PoseStack poseStack,
                                    MultiBufferSource buffers, int light, LivingEntity entity, float limbSwing, float limbSwingAmount,
                                    float ageInTicks, float netHeadYaw, float headPitch, float partialTicks, float red, float green,
                                    float blue, CallbackInfo ci) {
        FoePoses.parts(entity, model, partialTicks);
    }
}
