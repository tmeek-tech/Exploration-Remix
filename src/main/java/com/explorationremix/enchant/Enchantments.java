package com.explorationremix.enchant;

import com.explorationremix.config.ERConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Vanilla enchantments are gone from this world; runes replace them. Whatever still carries one (a command, another
 * mod, an old chest) is nullified at read time, and these helpers strip them from items we hand out.
 */
public final class Enchantments {
    private Enchantments() {}

    public static boolean nullify() { return ERConfig.INSTANCE.nullifyEnchantments.get(); }

    /** True for the few enchantment ids the config lets keep working. */
    public static boolean allowed(ResourceLocation id) {
        List<? extends String> list = ERConfig.INSTANCE.allowedEnchantments.get();
        return id != null && !list.isEmpty() && list.contains(id.toString());
    }

    public static boolean allowed(Enchantment e) {
        return e != null && allowed(ForgeRegistries.ENCHANTMENTS.getKey(e));
    }

    /** Removes every nullified enchantment from a stack (its own and, for books, the stored ones). */
    public static boolean strip(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return false;
        boolean changed = filter(stack, ItemStack.TAG_ENCH);
        changed |= filter(stack, EnchantedBookItem.TAG_STORED_ENCHANTMENTS);
        return changed;
    }

    private static boolean filter(ItemStack stack, String key) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(key, Tag.TAG_LIST)) return false;
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        ListTag kept = new ListTag();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            if (allowed(EnchantmentHelper.getEnchantmentId(c))) kept.add(c);
        }
        if (kept.size() == list.size()) return false;
        if (kept.isEmpty()) stack.removeTagKey(key); else tag.put(key, kept);
        return true;
    }

    /** True when the stack carries any enchantment that this world nullifies. */
    public static boolean carriesDead(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return false;
        ListTag list = stack.is(Items.ENCHANTED_BOOK) ? EnchantedBookItem.getEnchantments(stack) : stack.getEnchantmentTags();
        for (int i = 0; i < list.size(); i++) {
            if (!allowed(EnchantmentHelper.getEnchantmentId(list.getCompound(i)))) return true;
        }
        return false;
    }
}
