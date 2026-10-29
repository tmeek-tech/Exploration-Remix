package com.explorationremix.mixin.compat;

import com.explorationremix.client.ShaderGlow;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands each shader pack's block id map to ShaderGlow before Oculus stores it, so the mod's glowing blocks glow under
 * Complementary. Optional: applied only when Oculus is installed (CompatMixinPlugin), and skipped if Oculus changes.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings", remap = false)
public abstract class OculusBlockIdsMixin {
    @Inject(method = "setBlockStateIds", at = @At("HEAD"), remap = false, require = 0)
    private void er$glowingBlocks(Object2IntMap<BlockState> ids, CallbackInfo ci) {
        if (ids != null) {
            ShaderGlow.addTo(ids);
        }
    }
}
