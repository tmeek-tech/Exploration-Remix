package com.explorationremix.world.density;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Removes {@code interpolated} and {@code cache_all_in_cell} markers from a function that is going to live inside
 * another interpolated function (vanilla does not support nesting them). Other caches are left alone.
 */
public final class MarkerStripper implements DensityFunction.Visitor {
    public static final MarkerStripper INSTANCE = new MarkerStripper();

    @Override
    public DensityFunction apply(DensityFunction fn) {
        if (fn instanceof DensityFunctions.Marker marker) {
            DensityFunctions.Marker.Type t = marker.type();
            if (t == DensityFunctions.Marker.Type.Interpolated || t == DensityFunctions.Marker.Type.CacheAllInCell) {
                return marker.wrapped();
            }
        }
        if (fn instanceof DensityFunctions.HolderHolder holder) {
            return holder.function().value().mapAll(this);
        }
        return fn;
    }
}
