package com.explorationremix.mixin.foes;

import com.explorationremix.combat.foes.FoeSlimes;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Touching a slime that fights the new way (0.23.8) doesn't hurt: its slam is its blow. Magma cubes are as they were. */
@Mixin(Slime.class)
public abstract class SlimeTouchMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void er$harmlessTouch(Player player, CallbackInfo ci) {
        if (FoeSlimes.harmlessTouch((Slime) (Object) this)) ci.cancel();
    }
}
