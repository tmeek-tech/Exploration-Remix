package com.explorationremix.mixin.rune;

import com.explorationremix.rune.tool.ToolRunes;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.common.extensions.IForgeItem;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Map;

/**
 * A tool's rune heard as the enchantment it stands in for (ToolRunes): pickaxes, axes, shovels and hoes answer Forge's
 * two enchantment lookups (which every enchantment's work goes through: breaking speed, Fortune and Silk Touch in loot
 * tables, an ore's XP) with their rune counted. Both default to the stack's own enchantments, as here; a tool of
 * another mod's that answers them itself keeps its own answer.
 */
@Mixin(DiggerItem.class)
public abstract class ToolRunesMixin implements IForgeItem {
    @Override
    public int getEnchantmentLevel(ItemStack stack, Enchantment enchantment) {
        return ToolRunes.level(stack, enchantment, EnchantmentHelper.getTagEnchantmentLevel(enchantment, stack));
    }

    @Override
    public Map<Enchantment, Integer> getAllEnchantments(ItemStack stack) {
        return ToolRunes.all(stack, EnchantmentHelper.deserializeEnchantments(stack.getEnchantmentTags()));
    }
}
