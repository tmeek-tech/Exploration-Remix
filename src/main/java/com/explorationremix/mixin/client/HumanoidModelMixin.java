package com.explorationremix.mixin.client;

import com.explorationremix.client.combat.SwingPoses;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player's timed swing poses their model, after vanilla has (see SwingPoses). */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void explorationremix$swing(LivingEntity entity, float limbSwing, float limbSwingAmount, float age, float headYaw, float headPitch,
                                        CallbackInfo ci) {
        SwingPoses.apply((HumanoidModel<?>) (Object) this, entity);
    }
}
