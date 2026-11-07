package com.explorationremix.mixin.client;

import com.explorationremix.client.DeepClouds;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lifts the clouds far over the camera while it is under the pack floor in the overworld (DeepClouds), so a shader pack's
 * clouds are not drawn past the edge of the drawn world in the deep caves. Client only, and optional: if the getter is not
 * there the clouds simply stay where the dimension puts them.
 */
@Mixin(DimensionSpecialEffects.class)
public abstract class DimensionSpecialEffectsMixin {
    @Inject(method = "getCloudHeight", at = @At("RETURN"), cancellable = true, require = 0)
    private void er$noCloudsUnderground(CallbackInfoReturnable<Float> cir) {
        float vanilla = cir.getReturnValueF();
        if (Float.isNaN(vanilla)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null) return;
        ClientLevel level = mc.level;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (level == null || camera == null || !camera.isInitialized()) return;
        float height = DeepClouds.cloudHeight(vanilla, level.dimension() == Level.OVERWORLD, camera.getPosition().y);
        if (height != vanilla) cir.setReturnValue(height);
    }
}
