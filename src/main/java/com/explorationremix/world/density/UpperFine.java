package com.explorationremix.world.density;

import com.explorationremix.world.terrain.UpperShapes;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * The upper carve after Minecraft's interpolation (every 4 blocks across and 8 up), recomputed exactly wherever it is
 * within about five blocks of an opening, so the grottos' ledges, terraces and stream channels and the rift's steps
 * keep their edges. Everywhere else the interpolated value stands, which costs one comparison per block. Where the
 * shapes merge with the pack's caves ({@link UpperShapes#SEAM}) it is recomputed wherever the interpolation is not at
 * its limit (within about eight blocks): the rock that falls away can lie far enough from the shape's own opening, on
 * the pack's side, for the blend between the corners to pass five blocks.
 */
public final class UpperFine implements DensityFunction {
    public static final KeyDispatchDataCodec<UpperFine> CODEC = KeyDispatchDataCodec.of(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").xmap(UpperFine::new, f -> f.coarse));
    /** On the density scale (8 blocks per unit): about five blocks. */
    private static final double NEAR = 0.6;

    /** Recomputed between -NEAR and this (1 when merging: anything short of the limit). */
    private final double far;
    private final DensityFunction coarse;

    public UpperFine(DensityFunction coarse) { this(coarse, false); }

    public UpperFine(DensityFunction coarse, boolean merging) { this(coarse, merging ? 0.999 : NEAR); }

    private UpperFine(DensityFunction coarse, double far) {
        this.coarse = coarse;
        this.far = far;
    }

    private double refine(int x, int y, int z, double c) {
        return c > far || c < -NEAR ? c : UpperCarve.exact(x, y, z, c);
    }

    @Override
    public double compute(FunctionContext ctx) {
        return refine(ctx.blockX(), ctx.blockY(), ctx.blockZ(), coarse.compute(ctx));
    }

    @Override
    public void fillArray(double[] arr, ContextProvider provider) {
        coarse.fillArray(arr, provider);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] > far || arr[i] < -NEAR) continue;
            FunctionContext ctx = provider.forIndex(i);
            arr[i] = refine(ctx.blockX(), ctx.blockY(), ctx.blockZ(), arr[i]);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) { return visitor.apply(new UpperFine(coarse.mapAll(visitor), far)); }

    @Override
    public double minValue() { return -1.0; }

    @Override
    public double maxValue() { return 1.0; }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
