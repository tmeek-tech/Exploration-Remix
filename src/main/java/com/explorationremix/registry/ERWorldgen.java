package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.feature.FallbackStateProvider;
import com.explorationremix.world.feature.LayerRangePlacement;
import com.explorationremix.world.feature.OpenWayFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProviderType;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ERWorldgen {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, ExplorationRemix.MOD_ID);
    public static final DeferredRegister<PlacementModifierType<?>> PLACEMENT_TYPES = DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, ExplorationRemix.MOD_ID);
    public static final DeferredRegister<BlockStateProviderType<?>> STATE_PROVIDERS = DeferredRegister.create(Registries.BLOCK_STATE_PROVIDER_TYPE, ExplorationRemix.MOD_ID);

    private ERWorldgen() {}

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> OPEN_WAY = FEATURES.register("open_way", () -> new OpenWayFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<BlockStateProviderType<FallbackStateProvider>> FALLBACK_PROVIDER = STATE_PROVIDERS.register("fallback", () -> new BlockStateProviderType<>(FallbackStateProvider.CODEC));
    public static final RegistryObject<PlacementModifierType<LayerRangePlacement>> LAYER_RANGE = PLACEMENT_TYPES.register("layer_range", () -> () -> LayerRangePlacement.CODEC);
}
