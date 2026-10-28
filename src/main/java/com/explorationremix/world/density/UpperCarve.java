package com.explorationremix.world.density;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Carves out of the pack's own terrain above its old floor: the first gate's upper landing and its ramp, and the
 * Silver Fern Rift or Ivory Root Cavern of each cell. Combined with the pack's density through {@code min}, so it only
 * ever removes stone.
 */
public final class UpperCarve implements DensityFunction {
    public static final UpperCarve INSTANCE = new UpperCarve();
    public static final KeyDispatchDataCodec<UpperCarve> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));

    private UpperCarve() {}

    @Override
    public double compute(FunctionContext ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null) return 1.0;
        double v = t.upperCarve(ctx.blockX(), ctx.blockY(), ctx.blockZ()) / 8.0;
        return v < -1 ? -1 : (v > 1 ? 1 : v);
    }

    @Override
    public void fillArray(double[] arr, ContextProvider provider) { provider.fillAllDirectly(arr, this); }

    @Override
    public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }

    @Override
    public double minValue() { return -1.0; }

    @Override
    public double maxValue() { return 1.0; }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
