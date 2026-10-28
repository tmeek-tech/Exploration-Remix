package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.enchant.StripEnchantmentsModifier;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ERLootModifiers {
    private ERLootModifiers() {}

    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> GLM =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, ExplorationRemix.MOD_ID);

    public static final RegistryObject<Codec<StripEnchantmentsModifier>> STRIP_ENCHANTMENTS =
            GLM.register("strip_enchantments", () -> StripEnchantmentsModifier.CODEC);
}
