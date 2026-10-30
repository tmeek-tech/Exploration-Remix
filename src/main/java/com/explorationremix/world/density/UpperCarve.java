package com.explorationremix.world.density;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Entrances;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Carves out of the pack's own terrain above its old floor: the first gate's upper landing and its ramp, the Silver
 * Fern Rift or Ivory Root Cavern of each cell, and the entrances' chambers and tunnels near the surface. Combined with
 * the pack's density through {@code min}, so it only ever removes stone. Interpolated like the pack's density;
 * {@link UpperFine} puts the thin parts back block by block.
 */
public final class UpperCarve implements DensityFunction {
    public static final UpperCarve INSTANCE = new UpperCarve();
    public static final KeyDispatchDataCodec<UpperCarve> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));

    private UpperCarve() {}

    /** The carve at a block on Minecraft's density scale (-1 open to 1 solid), or {@code fallback} before install. */
    static double exact(int x, int y, int z, double fallback) {
        Terrain t = DeepWorld.terrain();
        if (t == null) return fallback;
        double d = t.upperCarve(x, y, z);
        Entrances e = DeepWorld.entrances();
        if (e != null) d = Math.min(d, e.carve(x, y, z));
        double v = d / 8.0;
        return v < -1 ? -1 : (v > 1 ? 1 : v);
    }

    @Override
    public double compute(FunctionContext ctx) { return exact(ctx.blockX(), ctx.blockY(), ctx.blockZ(), 1.0); }

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
