package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.stream.Stream;

/**
 * Picks a random Y inside a named layer (by its config id), so datapack features follow the configured heights
 * instead of hard-coded numbers. {@code inset} keeps the position away from the band edges.
 */
public final class LayerRangePlacement extends PlacementModifier {
    public static final Codec<LayerRangePlacement> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("layer").forGetter(LayerRangePlacement::layer),
            Codec.INT.optionalFieldOf("inset", 2).forGetter(LayerRangePlacement::inset)
    ).apply(i, LayerRangePlacement::new));

    private final String layer;
    private final int inset;

    public LayerRangePlacement(String layer, int inset) {
        this.layer = layer;
        this.inset = inset;
    }

    public String layer() { return layer; }
    public int inset() { return inset; }

    @Override
    public Stream<BlockPos> getPositions(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Layer l = null;
        for (Layer candidate : DeepWorld.layers()) if (candidate.id().equals(layer)) l = candidate;
        if (l == null) return Stream.empty();
        int min = l.bottom() + inset, max = l.top() - inset;
        if (max < min) return Stream.empty();
        return Stream.of(new BlockPos(pos.getX(), min + random.nextInt(max - min + 1), pos.getZ()));
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.LAYER_RANGE.get(); }
}
