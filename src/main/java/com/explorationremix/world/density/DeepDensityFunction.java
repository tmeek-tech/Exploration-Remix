package com.explorationremix.world.density;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Everything below the pack's floor, from {@link Terrain}. Positive is rock. */
public final class DeepDensityFunction implements DensityFunction {
    public static final DeepDensityFunction INSTANCE = new DeepDensityFunction();
    public static final KeyDispatchDataCodec<DeepDensityFunction> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));

    private DeepDensityFunction() {}

    @Override
    public double compute(FunctionContext ctx) {
        Terrain t = DeepWorld.terrain();
        return t == null ? 1.0 : t.density(ctx.blockX(), ctx.blockY(), ctx.blockZ());
    }

    @Override
    public void fillArray(double[] arr, ContextProvider provider) {
        Terrain t = DeepWorld.terrain();
        for (int i = 0; i < arr.length; i++) {
            FunctionContext ctx = provider.forIndex(i);
            arr[i] = t == null ? 1.0 : t.density(ctx.blockX(), ctx.blockY(), ctx.blockZ());
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }

    @Override
    public double minValue() { return -1.0; }

    @Override
    public double maxValue() { return 1.0; }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
