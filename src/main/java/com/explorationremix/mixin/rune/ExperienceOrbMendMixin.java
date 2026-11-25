package com.explorationremix.mixin.rune;

import com.explorationremix.rune.armor.ArmorEffects;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mend, a collect set: XP a player picks up mends their worn armor (a full set, what they hold too) before the game's
 * own Mending sees what is left, and the player gets the rest as ever. Server side (orbs are picked up there).
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMendMixin {
    @Shadow
    protected abstract int repairPlayerItems(Player player, int amount);

    @Inject(method = "repairPlayerItems", at = @At("HEAD"), cancellable = true)
    private void explorationremix$mend(Player player, int amount, CallbackInfoReturnable<Integer> cir) {
        int left = ArmorEffects.mend(player, amount);
        if (left == amount) return;
        // what is left goes on to the game's own mending (this runs again, and finds nothing more to mend)
        cir.setReturnValue(left > 0 ? repairPlayerItems(player, left) : 0);
    }
}
