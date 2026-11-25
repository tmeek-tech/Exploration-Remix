package com.explorationremix.rune.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

import java.util.function.Supplier;

/**
 * Every loot table's roll, made plain (Plain): a chest's, a catch's, a barter's, a creature's drops. An enchanted book
 * becomes a rune and enchanted gear comes without its enchantments. A broken block's drops are left alone: a block
 * that keeps a player's own gear and drops it as loot gives it back as it was.
 * (data/explorationremix/loot_modifiers/plain_loot.json, listed in data/forge/loot_modifiers/global_loot_modifiers.json.)
 */
public final class PlainLoot extends LootModifier {
    public static final Supplier<Codec<PlainLoot>> CODEC =
            Suppliers.memoize(() -> RecordCodecBuilder.create(inst -> codecStart(inst).apply(inst, PlainLoot::new)));

    public PlainLoot(LootItemCondition[] conditions) { super(conditions); }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
        if (ctx.hasParam(LootContextParams.BLOCK_STATE)) return loot;
        for (int i = 0; i < loot.size(); i++) loot.set(i, Plain.of(loot.get(i), ctx.getRandom()));
        return loot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() { return CODEC.get(); }
}
