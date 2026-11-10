package com.explorationremix.mixin.client;

import com.explorationremix.rune.RuneGear;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * No re-equip bob when only a piece of gear's casting state changed: every cast, hit and Siphon mote rewrites the
 * gear's energy, and without this the held sword or bow would dip out of view and back each time. Any other change
 * (another item, a new row, a scroll to another slot) still bobs as usual.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class RuneHandRendererMixin {
    @Unique
    private int explorationremix$lastSlot = -1;

    @Redirect(method = "tick", at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraftforge/client/ForgeHooksClient;shouldCauseReequipAnimation(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;I)Z"))
    private boolean explorationremix$noBobForCasting(ItemStack from, ItemStack to, int slot) {
        boolean bob = ForgeHooksClient.shouldCauseReequipAnimation(from, to, slot);
        boolean sameSlot = true;
        if (slot != -1) {
            sameSlot = slot == explorationremix$lastSlot;
            explorationremix$lastSlot = slot;
        }
        return bob && !(sameSlot && RuneGear.isGear(to) && RuneGear.sameButCastState(from, to));
    }
}
