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
 * The skylights planned for the entrances' chambers (see {@link Entrances#skylights}) whose column lies in this chunk:
 * one position for each, inside the chamber under it, for a feature that cuts the opening (with a scan up to the
 * ceiling first). Nothing where there are no entrances.
 */
public final class RootvaultsOpeningsPlacement extends PlacementModifier {
    public static final RootvaultsOpeningsPlacement INSTANCE = new RootvaultsOpeningsPlacement();
    public static final Codec<RootvaultsOpeningsPlacement> CODEC = Codec.unit(() -> INSTANCE);

    private RootvaultsOpeningsPlacement() {}

    @Override
    public Stream<BlockPos> getPositions(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Entrances e = DeepWorld.entrances();
        if (e == null) return Stream.empty();
        return e.skylights(pos.getX() >> 4, pos.getZ() >> 4).stream().map(s -> new BlockPos(s[0], s[1], s[2]));
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.ROOTVAULTS_OPENINGS.get(); }
}
