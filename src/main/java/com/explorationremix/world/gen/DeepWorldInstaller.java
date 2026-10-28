package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.mixin.accessor.DimensionTypeAccessor;
import com.explorationremix.mixin.accessor.NoiseBasedChunkGeneratorAccessor;
import com.explorationremix.mixin.accessor.NoiseGeneratorSettingsAccessor;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.density.DeepDensityFunction;
import com.explorationremix.world.density.DeepFineDensity;
import com.explorationremix.world.density.GateOneCarve;
import com.explorationremix.world.density.InterpolationGater;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Deepens the overworld when a server starts. Runs from the head of {@code MinecraftServer.loadLevel()}, which is after
 * every datapack has loaded and after Lithostitched has applied its worldgen modifiers (Tectonic's set_height_limits
 * modifier runs there and would otherwise reset the height limits we just changed). It reads whatever floor the pack
 * ended up with and extends the dimension, the noise settings, the density router and the surface rules downward.
 */
public final class DeepWorldInstaller {
    private static final Set<Object> PATCHED = Collections.newSetFromMap(new WeakHashMap<>());

    private DeepWorldInstaller() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        DeepWorld.clear();
    }

    /** Called by {@code MinecraftServerMixin} at the head of {@code loadLevel()}, before any level or chunk map exists. */
    public static void onLoadLevel(MinecraftServer server) {
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

    /** After the levels exist: make sure nothing undid the deepening between loadLevel() and now. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!DeepWorld.installed()) return;
        ServerLevel overworld = event.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) return;
        int minY = overworld.getMinBuildHeight();
        int noiseMin = Integer.MIN_VALUE;
        if (overworld.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator g) {
            noiseMin = g.generatorSettings().value().noiseSettings().minY();
        }
        if (minY != DeepWorld.floorY() || noiseMin != DeepWorld.floorY()) {
            ExplorationRemix.LOGGER.error("The deep world was installed but another mod changed the overworld's height afterwards (dimension minY {}, noise minY {}, expected {}). The deep layers will not generate.", minY, noiseMin, DeepWorld.floorY());
            DeepWorld.setInstalled(false);
        } else {
            ExplorationRemix.LOGGER.info("Deep world check passed: overworld floor is y {}", minY);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DeepWorld.clear();
        DeepBiomes.clear();
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
            return;
        }
        NoiseSettings noise = settings.noiseSettings();
        int currentMin = dimension.minY();
        if (noise.minY() != currentMin) {
            ExplorationRemix.LOGGER.warn("Overworld dimension minY {} differs from noise minY {}; using the higher one as the pack floor", currentMin, noise.minY());
            currentMin = Math.max(currentMin, noise.minY());
        }
        if (!settings.aquifersEnabled()) {
            ExplorationRemix.LOGGER.error("Aquifers are disabled in the overworld noise settings; the deep layers need them for their water, lava and stone");
        }

        long seed = server.getWorldData().worldGenOptions().seed();
        Terrain terrain = DeepWorld.build(currentMin, seed);
        DeepMaterials.resolve();
        int floorY = terrain.shape.floorY;
        int packFloor = terrain.shape.packFloor;
        int extra = currentMin - floorY;
        if ((extra & 15) != 0) throw new IllegalStateException("Deep world height " + extra + " is not a multiple of 16");

        // Density: the pack's terrain above its old floor (with its interpolators gated), the deep world below.
        NoiseRouter router = settings.noiseRouter();
        DensityFunction original = router.finalDensity().mapAll(new InterpolationGater(packFloor - 8));
        DensityFunction upper = DensityFunctions.min(original, DensityFunctions.interpolated(GateOneCarve.INSTANCE));
        DensityFunction deep = new DeepFineDensity(DensityFunctions.interpolated(DeepDensityFunction.INSTANCE));
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
