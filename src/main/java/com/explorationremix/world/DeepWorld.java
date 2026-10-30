package com.explorationremix.world;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.world.gen.SurfaceOracle;
import com.explorationremix.world.terrain.Entrances;
import com.explorationremix.world.terrain.PlantCaves;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;
import net.minecraft.world.level.biome.BiomeSource;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The game-side handle on the deep world: the terrain for the running server's seed, and which generator and biome
 * source were deepened. Rebuilt every time a server starts.
 */
public final class DeepWorld {
    private static volatile Terrain terrain;
    private static volatile Entrances entrances;
    private static volatile PlantCaves plants;
    private static volatile SurfaceOracle oracle;
    private static volatile boolean installed = false;
    private static final Set<BiomeSource> DEEP_SOURCES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Object> DEEP_GENERATORS = Collections.newSetFromMap(new WeakHashMap<>());

    private DeepWorld() {}

    public static boolean enabled() { return ERConfig.INSTANCE.worldEnabled.get(); }
    public static boolean installed() { return installed && terrain != null; }
    public static void setInstalled(boolean v) { installed = v; }

    /** The terrain of the running world, or null before the deep world is installed. */
    @Nullable
    public static Terrain terrain() { return terrain; }
    @Nullable
    public static Shape shape() { Terrain t = terrain; return t == null ? null : t.shape; }

    /**
     * The entrances to the Upper Caves of the running world (the Root Vaults, sinkholes and oasis hollows), or null when
     * they are switched off or the deep world is not installed.
     */
    @Nullable
    public static Entrances entrances() { return installed ? entrances : null; }

    /** Where the Upper Caves grow plants, or null when the cave biomes are left to the pack or before install. */
    @Nullable
    public static PlantCaves plants() { return installed ? plants : null; }

    /** What is over each column of the running world (the pack's ground and surface biome), or null before install. */
    @Nullable
    public static SurfaceOracle oracle() { return oracle; }

    public static void setUpper(@Nullable Entrances e, @Nullable PlantCaves p, @Nullable SurfaceOracle o) {
        entrances = e;
        plants = p;
        oracle = o;
    }

    public static int packFloor() { Terrain t = terrain; return t == null ? -64 : t.shape.packFloor; }
    public static int floorY() { Terrain t = terrain; return t == null ? -64 : t.shape.floorY; }

    /** Build the terrain for a world: the pack's floor as found at install time, the world seed and the config. */
    public static synchronized Terrain build(int packFloor, long seed) {
        TerrainSettings settings = ERConfig.INSTANCE.toSettings();
        Terrain t = new Terrain(settings, packFloor, seed);
        terrain = t;
        Shape s = t.shape;
        ExplorationRemix.LOGGER.info("Deep world: pack floor {}, new floor {}, {} layers", s.packFloor, s.floorY, s.layerCount);
        for (int i = 0; i < s.layerCount; i++) {
            ExplorationRemix.LOGGER.info("  {}: y {} to {} ({} blocks), {} table at y {}", s.id(i), s.top(i), s.bottom(i), s.height(i),
                    s.lava(i) ? "lava" : "water", s.waterY(i));
        }
        return t;
    }

    public static boolean isDeep(int y) { Terrain t = terrain; return t != null && y < t.shape.packFloor; }

    public static void markDeepGenerator(Object generator) { synchronized (DEEP_GENERATORS) { DEEP_GENERATORS.add(generator); } }
    public static boolean isDeepGenerator(Object generator) { synchronized (DEEP_GENERATORS) { return DEEP_GENERATORS.contains(generator); } }
    public static void markDeepBiomeSource(BiomeSource source) { synchronized (DEEP_SOURCES) { DEEP_SOURCES.add(source); } }
    public static boolean isDeepBiomeSource(BiomeSource source) { synchronized (DEEP_SOURCES) { return DEEP_SOURCES.contains(source); } }

    public static void clear() {
        synchronized (DEEP_GENERATORS) { DEEP_GENERATORS.clear(); }
        synchronized (DEEP_SOURCES) { DEEP_SOURCES.clear(); }
        installed = false;
        entrances = null;
        plants = null;
        oracle = null;
    }
}
