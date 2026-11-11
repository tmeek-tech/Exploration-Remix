package com.explorationremix.mixin.client;

import com.explorationremix.client.combat.RuneGlow;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The faces an item with its own renderer draws through ItemRenderer (Create's cardboard sword draws its model this
 * way): while such a sword's ready light is playing, RuneGlow takes them down to lay the light on.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Inject(method = "renderQuadList", at = @At("HEAD"))
    private void explorationremix$bladeFaces(PoseStack pose, VertexConsumer consumer, List<BakedQuad> quads, ItemStack stack, int light, int overlay,
                                             CallbackInfo ci) {
        RuneGlow.quads(pose, quads);
    }
}
