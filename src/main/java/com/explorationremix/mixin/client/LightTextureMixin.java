package com.explorationremix.mixin.client;

import com.explorationremix.client.CaveAtmosphere;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Tints the light of glowing blocks with the deep biome around the camera, as each lightmap pixel is written. Optional:
 * if another mod rewrites the lightmap, the light simply stays vanilla.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    @ModifyArg(method = "updateLightTexture", require = 0,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/NativeImage;setPixelRGBA(III)V"), index = 2)
    private int er$tintLight(int block, int sky, int abgr) {
        return CaveAtmosphere.tintLightmap(block, sky, abgr);
    }
}
