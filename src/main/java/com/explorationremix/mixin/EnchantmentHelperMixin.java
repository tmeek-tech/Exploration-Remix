package com.explorationremix.mixin;

import com.explorationremix.enchant.Enchantments;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * The backstop of the enchanting removal: every gameplay read of an item's enchantments goes through these three
 * methods (Forge routes {@code getItemEnchantmentLevel} and {@code getAllEnchantments} here too), so an enchantment
 * that slips in from a command or another mod simply does nothing.
 */
@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentHelperMixin {
    @Inject(method = "getTagEnchantmentLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private static void er$tagLevel(Enchantment ench, ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (Enchantments.nullify() && !Enchantments.allowed(ench)) cir.setReturnValue(0);
    }

    @Inject(method = "getItemEnchantmentLevel", at = @At("RETURN"), cancellable = true)
    private static void er$itemLevel(Enchantment ench, ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() > 0 && Enchantments.nullify() && !Enchantments.allowed(ench)) cir.setReturnValue(0);
    }

    @Inject(method = "deserializeEnchantments", at = @At("RETURN"))
    private static void er$filter(ListTag tags, CallbackInfoReturnable<Map<Enchantment, Integer>> cir) {
        if (!Enchantments.nullify()) return;
        Map<Enchantment, Integer> map = cir.getReturnValue();
        if (map != null && !map.isEmpty()) map.keySet().removeIf(e -> !Enchantments.allowed(e));
    }
}
