package com.explorationremix.world.density;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * The deep world's density after Minecraft's interpolation, corrected block by block where thin pieces matter (the
 * path's floor, bridges, arches, landings and the walking line). See {@link Terrain#fine}.
 */
public final class DeepFineDensity implements DensityFunction {
    public static final KeyDispatchDataCodec<DeepFineDensity> CODEC = KeyDispatchDataCodec.of(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").xmap(DeepFineDensity::new, f -> f.coarse));

    private final DensityFunction coarse;

    public DeepFineDensity(DensityFunction coarse) { this.coarse = coarse; }

    @Override
    public double compute(FunctionContext ctx) {
        double c = coarse.compute(ctx);
        Terrain t = DeepWorld.terrain();
        return t == null ? c : t.fine(ctx.blockX(), ctx.blockY(), ctx.blockZ(), c);
    }

    @Override
    public void fillArray(double[] arr, ContextProvider provider) {
        coarse.fillArray(arr, provider);
        Terrain t = DeepWorld.terrain();
        if (t == null) return;
        for (int i = 0; i < arr.length; i++) {
            FunctionContext ctx = provider.forIndex(i);
            arr[i] = t.fine(ctx.blockX(), ctx.blockY(), ctx.blockZ(), arr[i]);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) { return visitor.apply(new DeepFineDensity(coarse.mapAll(visitor))); }

    @Override
    public double minValue() { return -1.0; }

    @Override
    public double maxValue() { return 1.0; }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
