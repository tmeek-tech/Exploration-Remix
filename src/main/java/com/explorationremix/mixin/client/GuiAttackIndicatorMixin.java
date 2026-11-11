package com.explorationremix.mixin.client;

import com.explorationremix.combat.swing.Weapons;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * No vanilla attack indicator under the crosshair for a sword or an axe: their swings are timed (every one lands at
 * full strength), and the swings' own marks sit there instead (SwingHud).
 */
@Mixin(Gui.class)
public abstract class GuiAttackIndicatorMixin {
    @Redirect(method = "renderCrosshair", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getCurrentItemAttackStrengthDelay()F"))
    private float explorationremix$noIndicatorForTimedSwings(LocalPlayer p) {
        return Weapons.profile(p.getMainHandItem()) != null ? 0.0F : p.getCurrentItemAttackStrengthDelay();
    }
}
