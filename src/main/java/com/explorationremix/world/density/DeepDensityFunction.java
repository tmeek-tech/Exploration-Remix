package com.explorationremix.world.density;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.explorationremix.world.plan.LayerPlans;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The terrain of everything below the pack's floor. Bands are solid except for their gate tube; layers are the
 * layer's own cave noise, sealed at the band edges and opened by the planned route, landings, refuges and ruins.
 * Positive is stone, negative is air.
 */
public final class DeepDensityFunction implements DensityFunction {
    private static final double EDGE_SEAL = 5.0;
    /** Blocks above the world floor that are always solid, so the bedrock floor is never carved (one noise cell + 1). */
    public static final int BOTTOM_SEAL = 9;
    private final List<DensityFunction> layerCaves;

    public DeepDensityFunction(List<DensityFunction> layerCaves) {
        this.layerCaves = List.copyOf(layerCaves);
    }

    public static final KeyDispatchDataCodec<DeepDensityFunction> CODEC =
            KeyDispatchDataCodec.of(MapCodec.unit(() -> new DeepDensityFunction(List.of())));

    @Override
    public double compute(FunctionContext ctx) {
        int y = ctx.blockY();
        int packFloor = DeepWorld.packFloor();
        if (y < DeepWorld.floorY() + BOTTOM_SEAL) return 1.0;
        LayerPlans plans = DeepWorld.plans();
        if (plans == null) return 1.0;
        int x = ctx.blockX(), z = ctx.blockZ();
        if (y >= packFloor) {
            // Only interpolation reads this side of the floor: keep the first gate's tube open across the seam.
            if (y < packFloor + 16) return clamp(plans.bandCarveDistance(1, x, y, z) / 2.5);
            return 1.0;
        }
        int band = DeepWorld.bandAt(y);
        if (band != 0) {
            double d = plans.bandCarveDistance(band, x, y, z);
            return clamp(d / 2.5);
        }
        Layer layer = DeepWorld.layerAt(y);
        if (layer == null) return 1.0;
        double base = layer.index() < layerCaves.size() ? layerCaves.get(layer.index()).compute(ctx) : 1.0;
        double edge = Math.min(layer.top() - y, y - layer.bottom());
        if (edge < EDGE_SEAL) {
            base = Math.max(base, (EDGE_SEAL - edge) / EDGE_SEAL);
        }
        double pd = plans.layerCarveDistance(layer, x, y, z);
        double plan = clamp(pd / 3.0);
        return Math.min(base, plan);
    }

    private static double clamp(double v) { return v < -1 ? -1 : (v > 1 ? 1 : v); }

    @Override
    public void fillArray(double[] arr, ContextProvider provider) {
        provider.fillAllDirectly(arr, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        List<DensityFunction> mapped = new ArrayList<>(layerCaves.size());
        for (DensityFunction f : layerCaves) mapped.add(f.mapAll(visitor));
        return visitor.apply(new DeepDensityFunction(mapped));
    }

    @Override
    public double minValue() { return -1.0; }

    @Override
    public double maxValue() { return 1.0; }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
