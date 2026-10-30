package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Entrances;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.stream.Stream;

/**
 * Where the entrances' descents end in the pack's caves (see {@link Entrances#junctions}), for those ends in this chunk:
 * one position each, for features that dress the cave a descent opens into.
 */
public final class JunctionsPlacement extends PlacementModifier {
    public static final JunctionsPlacement INSTANCE = new JunctionsPlacement();
    public static final Codec<JunctionsPlacement> CODEC = Codec.unit(() -> INSTANCE);

    private JunctionsPlacement() {}

    @Override
    public Stream<BlockPos> getPositions(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Entrances e = DeepWorld.entrances();
        if (e == null) return Stream.empty();
        return e.junctions(pos.getX() >> 4, pos.getZ() >> 4).stream().map(j -> new BlockPos(j[0], j[1], j[2]));
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.JUNCTIONS.get(); }
}
