package com.explorationremix.mixin.client;

import com.explorationremix.rune.armor.ArmorEffects;
import com.explorationremix.rune.armor.Sensed;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sense's glow is the armor's light blue, so a creature hunting you stands apart from one a Glow arrow marked (white).
 * Only the client's own copies of creatures: the server's never ask in a way anyone sees.
 */
@Mixin(Entity.class)
public abstract class SenseColourMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void explorationremix$sensedColour(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() && Sensed.has(self.getId())) cir.setReturnValue(ArmorEffects.ARMOR_BLUE);
    }
}
