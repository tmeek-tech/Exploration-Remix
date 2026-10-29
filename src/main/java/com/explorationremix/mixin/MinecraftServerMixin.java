package com.explorationremix.mixin;

import com.explorationremix.world.gen.DeepWorldInstaller;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs the deep world at the head of loadLevel(). Forge's ServerAboutToStartEvent is too early: Lithostitched
 * applies its worldgen modifiers (including Tectonic's height limits) right before loadLevel() is called, and that
 * would undo the deepening. Nothing has created a level or a chunk map yet at this point.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "loadLevel", at = @At("HEAD"))
    private void er$installDeepWorld(CallbackInfo ci) {
        DeepWorldInstaller.onLoadLevel((MinecraftServer) (Object) this);
    }
}
