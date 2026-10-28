package com.explorationremix.world.density;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.plan.LayerPlans;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Carves the first gate's upper landing and its ramp out of the pack's own terrain, just above the first band.
 * Combined with the pack's density through {@code min}, so it only ever removes stone.
 */
public final class GateOneCarve implements DensityFunction {
    public static final KeyDispatchDataCodec<GateOneCarve> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(GateOneCarve::new));

    @Override
    public double compute(FunctionContext ctx) {
        int y = ctx.blockY();
        int floor = DeepWorld.packFloor();
        if (y < floor - 8 || y > floor + 64) return 1.0;
        LayerPlans plans = DeepWorld.plans();
        if (plans == null) return 1.0;
        double d = plans.gateOneCarveDistance(ctx.blockX(), y, ctx.blockZ());
        double v = d / 3.0;
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
