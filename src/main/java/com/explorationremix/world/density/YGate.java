package com.explorationremix.world.density;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Returns a constant below a given Y and the wrapped function above it. Wrapped around every interpolated node of the
 * pack's terrain so that the expensive terrain noise is never evaluated inside the deep layers, where the result is
 * discarded anyway.
 */
public record YGate(DensityFunction inner, int belowY, double value) implements DensityFunction {
    public static final MapCodec<YGate> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("inner").forGetter(YGate::inner),
            com.mojang.serialization.Codec.INT.fieldOf("below_y").forGetter(YGate::belowY),
            com.mojang.serialization.Codec.DOUBLE.fieldOf("value").forGetter(YGate::value)
    ).apply(i, YGate::new));
    public static final KeyDispatchDataCodec<YGate> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    @Override
    public double compute(FunctionContext ctx) {
        return ctx.blockY() < belowY ? value : inner.compute(ctx);
    }

    @Override
    public void fillArray(double[] arr, ContextProvider provider) {
        provider.fillAllDirectly(arr, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new YGate(inner.mapAll(visitor), belowY, value));
    }

    @Override
    public double minValue() { return Math.min(value, inner.minValue()); }

    @Override
    public double maxValue() { return Math.max(value, inner.maxValue()); }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
