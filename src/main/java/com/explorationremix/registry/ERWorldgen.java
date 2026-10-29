package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.feature.BrokenMachineFeature;
import com.explorationremix.world.feature.CeilingRootsFeature;
import com.explorationremix.world.feature.ClimbVinesFeature;
import com.explorationremix.world.feature.ColumnClusterFeature;
import com.explorationremix.world.feature.DaylightShaftFeature;
import com.explorationremix.world.feature.FallbackStateProvider;
import com.explorationremix.world.feature.FluidFallFeature;
import com.explorationremix.world.feature.GiantFungusFeature;
import com.explorationremix.world.feature.GiantSpikeFeature;
import com.explorationremix.world.feature.LayerHeight;
import com.explorationremix.world.feature.LayerRangePlacement;
import com.explorationremix.world.feature.MixStateProvider;
import com.explorationremix.world.feature.MushroomShelfFeature;
import com.explorationremix.world.feature.OpenWayFeature;
import com.explorationremix.world.feature.RootvaultsLookFilter;
import com.explorationremix.world.feature.RootvaultsOpeningsPlacement;
import com.explorationremix.world.feature.ShelfVinesFeature;
import com.explorationremix.world.feature.SurfaceAttachFeature;
import com.explorationremix.world.feature.TerracePoolsFeature;
import com.explorationremix.world.feature.UnderTreesPlacement;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProviderType;
import net.minecraft.world.level.levelgen.heightproviders.HeightProviderType;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The mod's world generation types. The features themselves are configured in the datapack (tools/gen_data.py). */
public final class ERWorldgen {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, ExplorationRemix.MOD_ID);
    public static final DeferredRegister<PlacementModifierType<?>> PLACEMENT_TYPES = DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, ExplorationRemix.MOD_ID);
    public static final DeferredRegister<BlockStateProviderType<?>> STATE_PROVIDERS = DeferredRegister.create(Registries.BLOCK_STATE_PROVIDER_TYPE, ExplorationRemix.MOD_ID);
    public static final DeferredRegister<HeightProviderType<?>> HEIGHT_PROVIDERS = DeferredRegister.create(Registries.HEIGHT_PROVIDER_TYPE, ExplorationRemix.MOD_ID);

    private ERWorldgen() {}

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> OPEN_WAY = FEATURES.register("open_way", () -> new OpenWayFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> CLIMB_VINES = FEATURES.register("climb_vines", () -> new ClimbVinesFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> SHELF_VINES = FEATURES.register("shelf_vines", () -> new ShelfVinesFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<Feature<SurfaceAttachFeature.Config>> SURFACE_ATTACH = FEATURES.register("surface_attach", () -> new SurfaceAttachFeature(SurfaceAttachFeature.Config.CODEC));
    public static final RegistryObject<Feature<GiantSpikeFeature.Config>> GIANT_SPIKE = FEATURES.register("giant_spike", () -> new GiantSpikeFeature(GiantSpikeFeature.Config.CODEC));
    public static final RegistryObject<Feature<TerracePoolsFeature.Config>> TERRACE_POOLS = FEATURES.register("terrace_pools", () -> new TerracePoolsFeature(TerracePoolsFeature.Config.CODEC));
    public static final RegistryObject<Feature<ColumnClusterFeature.Config>> COLUMN_CLUSTER = FEATURES.register("column_cluster", () -> new ColumnClusterFeature(ColumnClusterFeature.Config.CODEC));
    public static final RegistryObject<Feature<FluidFallFeature.Config>> FLUID_FALL = FEATURES.register("fluid_fall", () -> new FluidFallFeature(FluidFallFeature.Config.CODEC));
    public static final RegistryObject<Feature<DaylightShaftFeature.Config>> DAYLIGHT_SHAFT = FEATURES.register("daylight_shaft", () -> new DaylightShaftFeature(DaylightShaftFeature.Config.CODEC));
    public static final RegistryObject<Feature<CeilingRootsFeature.Config>> CEILING_ROOTS = FEATURES.register("ceiling_roots", () -> new CeilingRootsFeature(CeilingRootsFeature.Config.CODEC));
    public static final RegistryObject<Feature<GiantFungusFeature.Config>> GIANT_FUNGUS = FEATURES.register("giant_fungus", () -> new GiantFungusFeature(GiantFungusFeature.Config.CODEC));
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> BROKEN_MACHINE = FEATURES.register("broken_machine", () -> new BrokenMachineFeature(NoneFeatureConfiguration.CODEC));
    // vegetation rework, part B: brown mushroom shelves stacked up the Ember Gardens' walls
    public static final RegistryObject<Feature<MushroomShelfFeature.Config>> MUSHROOM_SHELF = FEATURES.register("mushroom_shelf", () -> new MushroomShelfFeature(MushroomShelfFeature.Config.CODEC));

    public static final RegistryObject<BlockStateProviderType<FallbackStateProvider>> FALLBACK_PROVIDER = STATE_PROVIDERS.register("fallback", () -> new BlockStateProviderType<>(FallbackStateProvider.CODEC));
    public static final RegistryObject<BlockStateProviderType<MixStateProvider>> MIX_PROVIDER = STATE_PROVIDERS.register("mix", () -> new BlockStateProviderType<>(MixStateProvider.CODEC));
    public static final RegistryObject<PlacementModifierType<LayerRangePlacement>> LAYER_RANGE = PLACEMENT_TYPES.register("layer_range", () -> () -> LayerRangePlacement.CODEC);
    public static final RegistryObject<PlacementModifierType<RootvaultsLookFilter>> ROOTVAULTS_LOOK = PLACEMENT_TYPES.register("rootvaults_look", () -> () -> RootvaultsLookFilter.CODEC);
    public static final RegistryObject<PlacementModifierType<RootvaultsOpeningsPlacement>> ROOTVAULTS_OPENINGS = PLACEMENT_TYPES.register("rootvaults_openings", () -> () -> RootvaultsOpeningsPlacement.CODEC);
    public static final RegistryObject<PlacementModifierType<UnderTreesPlacement>> UNDER_TREES = PLACEMENT_TYPES.register("under_trees", () -> () -> UnderTreesPlacement.CODEC);
    public static final RegistryObject<HeightProviderType<LayerHeight>> LAYER_HEIGHT = HEIGHT_PROVIDERS.register("layer_height", () -> () -> LayerHeight.CODEC);
}
