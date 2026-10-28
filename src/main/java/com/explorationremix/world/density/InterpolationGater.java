package com.explorationremix.world.density;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.HashMap;
import java.util.Map;

/**
 * Visitor that wraps the inside of every {@code interpolated} marker in a {@link YGate}. Interpolators are filled for
 * the whole chunk height, so without this the pack's terrain noise would run three times as often for nothing.
 */
public final class InterpolationGater implements DensityFunction.Visitor {
    private final int belowY;
    private final Map<DensityFunction, DensityFunction> seen = new HashMap<>();

    public InterpolationGater(int belowY) { this.belowY = belowY; }

    @Override
    public DensityFunction apply(DensityFunction fn) {
        return seen.computeIfAbsent(fn, f -> {
            if (f instanceof DensityFunctions.Marker marker && marker.type() == DensityFunctions.Marker.Type.Interpolated) {
                return DensityFunctions.interpolated(new YGate(marker.wrapped(), belowY, 1.0));
            }
            return f;
        });
    }
}
