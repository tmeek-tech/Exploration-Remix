package com.explorationremix.mixin.client;

import com.explorationremix.client.rune.ArmorClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Far Sight: while its player peers far off, the mouse turns the view as slowly as through a spyglass. */
@Mixin(MouseHandler.class)
public abstract class PeerTurnMixin {
    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isScoping()Z"))
    private boolean explorationremix$peering(LocalPlayer player) {
        return player.isScoping() || ArmorClient.peering();
    }
}
