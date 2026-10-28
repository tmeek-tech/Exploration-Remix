package com.explorationremix.enchant;

import com.explorationremix.config.ERConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

/**
 * Global loot modifier over every loot table: chests, fishing, mob drops, piglin bartering, archaeology. Enchanted
 * books become plain books, bottles o' enchanting vanish, and enchanted gear is handed out clean.
 */
public final class StripEnchantmentsModifier extends LootModifier {
    public static final Codec<StripEnchantmentsModifier> CODEC =
            RecordCodecBuilder.create(inst -> codecStart(inst).apply(inst, StripEnchantmentsModifier::new));

    public StripEnchantmentsModifier(LootItemCondition[] conditions) { super(conditions); }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
        if (!ERConfig.INSTANCE.stripLootEnchantments.get()) return loot;
        for (int i = loot.size() - 1; i >= 0; i--) {
            ItemStack s = loot.get(i);
            if (s.is(Items.EXPERIENCE_BOTTLE)) { loot.remove(i); continue; }
            if (s.is(Items.ENCHANTED_BOOK)) { loot.set(i, new ItemStack(Items.BOOK, s.getCount())); continue; }
            Enchantments.strip(s);
        }
        return loot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() { return CODEC; }
}
