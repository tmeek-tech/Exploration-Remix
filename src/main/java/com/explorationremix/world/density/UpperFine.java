package com.explorationremix.world.density;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * The upper carve after Minecraft's interpolation (every 4 blocks across and 8 up), recomputed exactly wherever it is
 * within about five blocks of an opening, so the grottos' ledges, terraces and stream channels and the rift's steps
 * keep their edges. Everywhere else the interpolated value stands, which costs one comparison per block.
 */
public final class UpperFine implements DensityFunction {
    public static final KeyDispatchDataCodec<UpperFine> CODEC = KeyDispatchDataCodec.of(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").xmap(UpperFine::new, f -> f.coarse));
    /** On the density scale (8 blocks per unit): about five blocks. */
    private static final double NEAR = 0.6;

    private final DensityFunction coarse;

    public UpperFine(DensityFunction coarse) { this.coarse = coarse; }

    private static double refine(int x, int y, int z, double c) {
        return c > NEAR || c < -NEAR ? c : UpperCarve.exact(x, y, z, c);
    }

    @Override
    public double compute(FunctionContext ctx) {
        return refine(ctx.blockX(), ctx.blockY(), ctx.blockZ(), coarse.compute(ctx));
    }

    @Override
    public void fillArray(double[] arr, ContextProvider provider) {
        coarse.fillArray(arr, provider);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] > NEAR || arr[i] < -NEAR) continue;
            FunctionContext ctx = provider.forIndex(i);
            arr[i] = refine(ctx.blockX(), ctx.blockY(), ctx.blockZ(), arr[i]);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) { return visitor.apply(new UpperFine(coarse.mapAll(visitor))); }

    @Override
    public double minValue() { return -1.0; }

    @Override
    public double maxValue() { return 1.0; }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
