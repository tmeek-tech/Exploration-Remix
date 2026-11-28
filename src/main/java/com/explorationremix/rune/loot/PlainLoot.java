package com.explorationremix.rune.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

import java.util.function.Supplier;

/**
 * Every loot table's roll, made plain (Plain): a chest's, a catch's, a barter's, a creature's drops. Enchanted gear
 * comes without its enchantments, and an enchanted book becomes a rune, but in a chest, where it comes as a plain book
 * (0.24.5, Taran: "Can you take the runes out of other chests as well?"). A broken block's drops are left alone: a block
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
        boolean chest = isChest(ctx);
        for (int i = 0; i < loot.size(); i++) loot.set(i, Plain.of(loot.get(i), ctx.getRandom(), chest));
        return loot;
    }

    /**
     * Whether this roll fills a chest: a chest's, a barrel's or a chest minecart's table (type minecraft:chest), or one
     * kept under chests/ as most mods keep theirs, whatever type it names. Other mods' structures' chests count too.
     */
    static boolean isChest(LootContext ctx) {
        ResourceLocation id = ctx.getQueriedLootTableId();
        if (id == null) return false;
        return chestPath(id.getPath()) || ctx.getResolver().getLootTable(id).getParamSet() == LootContextParamSets.CHEST;
    }

    /** A chest's table's path: chests/..., or .../chests/... */
    static boolean chestPath(String path) { return path.startsWith("chests/") || path.contains("/chests/"); }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() { return CODEC.get(); }
}
