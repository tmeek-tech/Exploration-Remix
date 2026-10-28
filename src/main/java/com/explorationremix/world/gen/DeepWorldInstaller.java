package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.mixin.accessor.DimensionTypeAccessor;
import com.explorationremix.mixin.accessor.NoiseBasedChunkGeneratorAccessor;
import com.explorationremix.mixin.accessor.NoiseGeneratorSettingsAccessor;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.explorationremix.world.density.DeepDensityFunction;
import com.explorationremix.world.density.GateOneCarve;
import com.explorationremix.world.density.InterpolationGater;
import com.explorationremix.world.density.MarkerStripper;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Deepens the overworld when a server starts. Runs after every datapack (Tectonic, WWOO, Lithostitched) has loaded,
 * reads whatever floor the pack ended up with, and extends the dimension, the noise settings, the density router and
 * the surface rules downward from there.
 */
public final class DeepWorldInstaller {
    private static final Set<Object> PATCHED = Collections.newSetFromMap(new WeakHashMap<>());

    private DeepWorldInstaller() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        DeepWorld.clearMarks();
        DeepWorld.setInstalled(false);
        if (!DeepWorld.enabled()) {
            ExplorationRemix.LOGGER.info("Deep world disabled by config");
            return;
        }
        try {
            install(server);
        } catch (RuntimeException e) {
            ExplorationRemix.LOGGER.error("Failed to install the deep world; the overworld is unchanged", e);
            DeepWorld.setInstalled(false);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DeepWorld.setInstalled(false);
        DeepWorld.clearMarks();
    }

    private static void install(MinecraftServer server) {
        RegistryAccess access = server.registryAccess();
        Registry<LevelStem> stems = access.registryOrThrow(Registries.LEVEL_STEM);
        LevelStem stem = stems.get(LevelStem.OVERWORLD);
        if (stem == null) {
            ExplorationRemix.LOGGER.error("No overworld level stem found");
            return;
        }
        if (!(stem.generator() instanceof NoiseBasedChunkGenerator generator)) {
            ExplorationRemix.LOGGER.error("The overworld generator is {}, not a noise generator; the deep world needs noise terrain", stem.generator().getClass().getName());
            return;
        }
        Holder<NoiseGeneratorSettings> settingsHolder = ((NoiseBasedChunkGeneratorAccessor) generator).er$getSettings();
        NoiseGeneratorSettings settings = settingsHolder.value();
        DimensionType dimension = stem.type().value();
        if (PATCHED.contains(settings) || PATCHED.contains(dimension)) {
            ExplorationRemix.LOGGER.warn("Overworld settings were already deepened in this JVM; skipping a second install");
            DeepWorld.setInstalled(true);
            DeepWorld.markDeepGenerator(generator);
            DeepWorld.markDeepBiomeSource(generator.getBiomeSource());
            return;
        }
        NoiseSettings noise = settings.noiseSettings();
        int currentMin = dimension.minY();
        if (noise.minY() != currentMin) {
            ExplorationRemix.LOGGER.warn("Overworld dimension minY {} differs from noise minY {}; using the higher one as the pack floor", currentMin, noise.minY());
            currentMin = Math.max(currentMin, noise.minY());
        }
        if (!settings.aquifersEnabled()) {
            ExplorationRemix.LOGGER.warn("Aquifers are disabled in the overworld noise settings; deep layers will use vanilla's fluid picker");
        }

        DeepWorld.rebuild(currentMin);
        long seed = server.getWorldData().worldGenOptions().seed();
        DeepWorld.setSeed(seed);
        DeepSurfaceRules.setSeed(seed);
        int floorY = DeepWorld.floorY();
        int extra = currentMin - floorY;

        // Per-layer cave noise from the datapack.
        Registry<DensityFunction> functions = access.registryOrThrow(Registries.DENSITY_FUNCTION);
        List<DensityFunction> caves = new ArrayList<>();
        for (Layer layer : DeepWorld.layers()) {
            DensityFunction f = functions.get(ResourceKey.create(Registries.DENSITY_FUNCTION, layer.caveFunction()));
            if (f == null) {
                ExplorationRemix.LOGGER.error("Cave density function {} for layer {} is missing; the layer will be solid apart from its planned caves", layer.caveFunction(), layer.id());
                f = DensityFunctions.constant(1.0);
            }
            caves.add(f.mapAll(MarkerStripper.INSTANCE));
        }

        // Density: the pack's terrain above its old floor (with its interpolators gated), the deep world below.
        NoiseRouter router = settings.noiseRouter();
        int packFloor = DeepWorld.packFloor();
        DensityFunction original = router.finalDensity().mapAll(new InterpolationGater(packFloor - 8));
        DensityFunction upper = DensityFunctions.min(original, DensityFunctions.interpolated(new GateOneCarve()));
        DensityFunction deep = DensityFunctions.interpolated(new DeepDensityFunction(caves));
        DensityFunction selector = DensityFunctions.yClampedGradient(packFloor - 1, packFloor, 0.0, 1.0);
        DensityFunction composed = DensityFunctions.rangeChoice(selector, 0.5, 2.0, upper, deep);
        NoiseRouter deepened = new NoiseRouter(router.barrierNoise(), router.fluidLevelFloodednessNoise(), router.fluidLevelSpreadNoise(),
                router.lavaNoise(), router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.depth(),
                router.ridges(), router.initialDensityWithoutJaggedness(), composed, router.veinToggle(), router.veinRidged(), router.veinGap());

        NoiseGeneratorSettingsAccessor sa = (NoiseGeneratorSettingsAccessor) (Object) settings;
        sa.er$setNoiseRouter(deepened);
        sa.er$setNoiseSettings(NoiseSettings.create(floorY, noise.height() + extra, noise.noiseSizeHorizontal(), noise.noiseSizeVertical()));
        sa.er$setSurfaceRule(DeepSurfaceRules.build(settings.surfaceRule()));

        DimensionTypeAccessor da = (DimensionTypeAccessor) (Object) dimension;
        da.er$setMinY(floorY);
        da.er$setHeight(dimension.height() + extra);
        da.er$setLogicalHeight(dimension.logicalHeight() + extra);

        PATCHED.add(settings);
        PATCHED.add(dimension);
        DeepWorld.markDeepGenerator(generator);
        DeepWorld.markDeepBiomeSource(generator.getBiomeSource());
        DeepBiomes.resolve(access.registryOrThrow(Registries.BIOME));
        DeepWorld.setInstalled(true);
        ExplorationRemix.LOGGER.info("Deep world installed: overworld now spans y {} to {} ({} blocks)", floorY, floorY + dimension.height() - 1, dimension.height());
    }
}
