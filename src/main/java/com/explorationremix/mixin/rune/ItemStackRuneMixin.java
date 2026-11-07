package com.explorationremix.mixin.rune;

import com.explorationremix.rune.RuneGear;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * A runed tool that breaks gives its runes back to the player who broke it (into the inventory, or at their feet), so
 * nothing is lost by wearing gear out. Hooked just before the broken stack is used up, the one place every way of
 * breaking a tool passes through.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackRuneMixin {
    @Inject(method = "hurtAndBreak", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private <T extends LivingEntity> void explorationremix$giveRunesBack(int amount, T entity, Consumer<T> onBroken, CallbackInfo ci) {
        if (entity instanceof Player p && RuneGear.hasRunes((ItemStack) (Object) this)) RuneGear.dropRunes((ItemStack) (Object) this, p);
    }
}
