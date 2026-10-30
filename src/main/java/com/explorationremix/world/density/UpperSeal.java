package com.explorationremix.world.density;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Entrances;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Rock the pack's own caves may not open: a bowl around the water of each entrance's chamber (see
 * {@link Entrances#seals}), so its pools and streams never drain into a cave beside or below them. Combined with the
 * pack's density through {@code max}, before {@link UpperCarve}'s {@code min} opens the chamber itself again. Not
 * interpolated: it is exact per block, and {@code max} only asks it where the pack's density is below 1.
 */
public final class UpperSeal implements DensityFunction {
    public static final UpperSeal INSTANCE = new UpperSeal();
    public static final KeyDispatchDataCodec<UpperSeal> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));
    /** Leaves the pack's density as it is under {@code max}. */
    private static final double NONE = -1.0e6;

    private UpperSeal() {}

    @Override
    public double compute(FunctionContext ctx) {
        Entrances e = DeepWorld.entrances();
        return e != null && e.seals(ctx.blockX(), ctx.blockY(), ctx.blockZ()) ? 1.0 : NONE;
    }

    @Override
    public void fillArray(double[] arr, ContextProvider provider) { provider.fillAllDirectly(arr, this); }

    @Override
    public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }

    @Override
    public double minValue() { return NONE; }

    @Override
    public double maxValue() { return 1.0; }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
