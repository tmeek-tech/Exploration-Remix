package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.biome.RemoveCreateStriationsModifier;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ERBiomeModifiers {
    public static final DeferredRegister<Codec<? extends BiomeModifier>> SERIALIZERS = DeferredRegister.create(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, ExplorationRemix.MOD_ID);

    private ERBiomeModifiers() {}

    public static final RegistryObject<Codec<RemoveCreateStriationsModifier>> REMOVE_CREATE_STRIATIONS =
            SERIALIZERS.register("remove_create_striations", () -> RemoveCreateStriationsModifier.CODEC);
}
