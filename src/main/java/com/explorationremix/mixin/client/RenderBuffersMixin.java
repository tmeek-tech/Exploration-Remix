package com.explorationremix.mixin.client;

import com.explorationremix.client.rune.fx.GlowRender;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedMap;

/**
 * The blade's light (seen from outside, without a shader pack) and the swings' sweep join the buffers the game keeps for
 * the end of a frame's world, after the held items' own (and their enchantment glint): drawn any sooner, the blade drawn
 * after them would cover them.
 */
@Mixin(RenderBuffers.class)
public abstract class RenderBuffersMixin {
    @Shadow @Final private SortedMap<RenderType, BufferBuilder> fixedBuffers;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void explorationremix$bladeLast(CallbackInfo ci) {
        fixedBuffers.put(GlowRender.BLADE, new BufferBuilder(GlowRender.BLADE.bufferSize()));
        fixedBuffers.put(GlowRender.SWEEP, new BufferBuilder(GlowRender.SWEEP.bufferSize()));
    }
}
