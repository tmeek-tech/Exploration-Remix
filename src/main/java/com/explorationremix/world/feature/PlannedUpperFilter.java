package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/**
 * Keeps a feature to worlds whose Upper Caves are planned (the entrances and set pieces of 0.12): the big formations
 * placed piece by piece there (pools, waterfalls, ceiling roots, giant icicles and stalactites) grow from the noise
 * itself where the Upper Caves do ({@link com.explorationremix.world.terrain.UpperShapes}).
 */
public final class PlannedUpperFilter extends PlacementFilter {
    public static final PlannedUpperFilter INSTANCE = new PlannedUpperFilter();
    public static final Codec<PlannedUpperFilter> CODEC = Codec.unit(() -> INSTANCE);

    private PlannedUpperFilter() {}

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) { return DeepWorld.shapes() == null; }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.PLANNED_UPPER.get(); }
}
