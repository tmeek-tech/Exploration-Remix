package com.explorationremix.world;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.world.plan.LayerPlans;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The static geometry of the deeper world: where the pack's floor was, where every band and layer sits,
 * and the per-seed layer plans. Rebuilt from config whenever a server starts.
 */
public final class DeepWorld {
    private static volatile List<Layer> layers = List.of();
    private static volatile int packFloor = -64;      // the old minimum Y, now the top of band 1
    private static volatile int floorY = -64;         // the new minimum Y
    private static volatile int bandThickness = 16;
    private static volatile boolean installed = false;
    private static volatile long seed = 0L;
    private static volatile LayerPlans plans = null;
    private static final Set<BiomeSource> DEEP_SOURCES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Object> DEEP_GENERATORS = Collections.newSetFromMap(new WeakHashMap<>());

    private DeepWorld() {}

    public static boolean enabled() { return ERConfig.INSTANCE.worldEnabled.get(); }
    public static boolean installed() { return installed; }
    public static void setInstalled(boolean v) { installed = v; }
    public static int packFloor() { return packFloor; }
    public static int floorY() { return floorY; }
    public static int bandThickness() { return bandThickness; }
    public static List<Layer> layers() { return layers; }
    public static long seed() { return seed; }
    public static LayerPlans plans() { return plans; }

    /** Band 1 occupies [band1Bottom, packFloor - 1]. */
    public static int band1Top() { return packFloor - 1; }
    public static int band1Bottom() { return packFloor - bandThickness; }

    /** Recompute all layer heights from config, given the pack's current minimum Y. */
    public static synchronized void rebuild(int currentMinY) {
        ERConfig c = ERConfig.INSTANCE;
        packFloor = currentMinY;
        bandThickness = c.bandThickness.get();
        List<Layer> list = new ArrayList<>();
        int top = packFloor - bandThickness - 1;
        for (int i = 0; i < 4; i++) {
            ERConfig.LayerConfig lc = c.layers[i];
            int height = Math.max(32, (lc.height.get() / 16) * 16);
            int bottom = top - height + 1;
            List<ResourceLocation> hunting = new ArrayList<>();
            for (String s : lc.huntingBiomes.get()) hunting.add(biomeId(s));
            list.add(new Layer(i, lc.id, top, bottom, Layer.FluidKind.parse(lc.fluid.get()), lc.lavaPockets.get(),
                    Layer.Style.parse(lc.style.get()), biomeId(lc.refugeBiome.get()), biomeId(lc.routeBiome.get()),
                    List.copyOf(hunting), new ResourceLocation(lc.baseStone.get()), new ResourceLocation(lc.caveFunction.get()),
                    lc.cavernScale.get(), lc.fluidLevel.get()));
            top = bottom - bandThickness - 1;
        }
        layers = List.copyOf(list);
        floorY = list.get(list.size() - 1).bottom();
        ExplorationRemix.LOGGER.info("Deep world: pack floor {}, new floor {}, {} layers", packFloor, floorY, layers.size());
        for (Layer l : layers) ExplorationRemix.LOGGER.info("  {}: y {}..{} ({} blocks, {} / {})", l.id(), l.top(), l.bottom(), l.height(), l.fluid(), l.style());
    }

    private static ResourceLocation biomeId(String s) {
        return s.contains(":") ? new ResourceLocation(s) : new ResourceLocation(ExplorationRemix.MOD_ID, s);
    }

    public static void setSeed(long s) {
        if (plans == null || seed != s) {
            seed = s;
            plans = new LayerPlans(s);
        }
    }

    /** The layer containing y, or null if y is in a band or above the deep world. */
    @Nullable
    public static Layer layerAt(int y) {
        for (Layer l : layers) if (l.contains(y)) return l;
        return null;
    }

    /** 1-based band index containing y, or 0. Band 1 is just under the pack's floor. */
    public static int bandAt(int y) {
        if (y >= packFloor) return 0;
        if (y >= band1Bottom()) return 1;
        for (Layer l : layers) {
            if (y > l.top()) return l.index() + 1;      // band above this layer: should have been caught earlier
            if (y >= l.bottom()) return 0;
            if (y >= l.bottom() - bandThickness) return l.index() + 2;
        }
        return 0;
    }

    /** Top (inclusive) block of a 1-based band. */
    public static int bandTopY(int band) {
        if (band <= 1) return packFloor - 1;
        return layers.get(band - 2).bottom() - 1;
    }

    /** Bottom (inclusive) block of a 1-based band. */
    public static int bandBottomY(int band) {
        return bandTopY(band) - bandThickness + 1;
    }

    public static int bandCount() { return layers.size(); }

    public static boolean isBand(int y) { return y < packFloor && bandAt(y) != 0; }
    public static boolean isBelowBand1(int y) { return y < band1Bottom(); }
    /** True for every y below the pack's old floor (bands included). */
    public static boolean isDeep(int y) { return y < packFloor; }

    /** Index of the deepest layer a player at y has reached: -1 above band 1, 0..3 inside the deep world. */
    public static int depthIndex(int y) {
        if (y >= band1Bottom()) return -1;
        int idx = -1;
        for (Layer l : layers) if (y <= l.top()) idx = l.index();
        return idx;
    }

    public static void markDeepGenerator(Object generator) { synchronized (DEEP_GENERATORS) { DEEP_GENERATORS.add(generator); } }
    public static boolean isDeepGenerator(Object generator) { synchronized (DEEP_GENERATORS) { return DEEP_GENERATORS.contains(generator); } }
    public static void clearMarks() { synchronized (DEEP_GENERATORS) { DEEP_GENERATORS.clear(); } synchronized (DEEP_SOURCES) { DEEP_SOURCES.clear(); } }

    public static void markDeepBiomeSource(BiomeSource source) { synchronized (DEEP_SOURCES) { DEEP_SOURCES.add(source); } }
    public static boolean isDeepBiomeSource(BiomeSource source) { synchronized (DEEP_SOURCES) { return DEEP_SOURCES.contains(source); } }
}
