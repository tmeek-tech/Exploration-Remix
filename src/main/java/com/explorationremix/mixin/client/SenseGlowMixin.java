package com.explorationremix.mixin.client;

import com.explorationremix.rune.armor.Sensed;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sense, on the helmet: the creatures hunting its player are drawn glowing for them, through walls. */
@Mixin(Minecraft.class)
public abstract class SenseGlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void explorationremix$sensed(Entity e, CallbackInfoReturnable<Boolean> cir) {
        if (Sensed.has(e.getId())) cir.setReturnValue(true);
    }
}
