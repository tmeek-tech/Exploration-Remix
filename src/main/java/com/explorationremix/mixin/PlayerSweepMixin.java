package com.explorationremix.mixin;

import com.explorationremix.combat.swing.Swordplay;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A timed swing never sweeps: a light swing strikes the one creature it was aimed at, and a strong attack's thrust
 * already strikes all it reaches (Swordplay). Vanilla's sweep (its sound and arc included) stays for anything else.
 */
@Mixin(Player.class)
public abstract class PlayerSweepMixin {
    @Redirect(method = "attack", at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/world/item/ItemStack;canPerformAction(Lnet/minecraftforge/common/ToolAction;)Z"))
    private boolean explorationremix$noSweep(ItemStack stack, ToolAction action) {
        if ((Object) this instanceof ServerPlayer p && Swordplay.landing(p) != null) return false;
        return stack.canPerformAction(action);
    }
}
