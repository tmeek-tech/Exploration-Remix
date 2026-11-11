package com.explorationremix.mixin.client;

import com.explorationremix.client.combat.RuneGlow;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where a rune sword's ready light is drawn over the finished picture (see RuneGlow): once the world is drawn (under a
 * shader pack, its last pass included, and before the depth is cleared for the vanilla hand), and once the hand is.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void explorationremix$levelBegins(float partialTick, long nanos, PoseStack pose, CallbackInfo ci) {
        RuneGlow.beginLevel();
    }

    // not required: if another mod moves this call, the light is still drawn at the end, only not hidden by the arm
    @Inject(method = "renderLevel", require = 0, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"))
    private void explorationremix$worldDrawn(float partialTick, long nanos, PoseStack pose, CallbackInfo ci) {
        RuneGlow.afterWorld();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void explorationremix$levelEnds(float partialTick, long nanos, PoseStack pose, CallbackInfo ci) {
        RuneGlow.endLevel();
    }
}
