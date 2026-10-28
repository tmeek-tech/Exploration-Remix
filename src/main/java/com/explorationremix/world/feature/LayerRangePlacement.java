package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Shape;
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
 * instead of hard-coded numbers. {@code inset} keeps the position away from the band edges; {@code above_water}
 * keeps it above the layer's water or lava table.
 */
public final class LayerRangePlacement extends PlacementModifier {
    public static final Codec<LayerRangePlacement> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("layer").forGetter(LayerRangePlacement::layer),
            Codec.INT.optionalFieldOf("inset", 2).forGetter(LayerRangePlacement::inset),
            Codec.BOOL.optionalFieldOf("above_water", false).forGetter(LayerRangePlacement::aboveWater)
    ).apply(i, LayerRangePlacement::new));

    private final String layer;
    private final int inset;
    private final boolean aboveWater;

    public LayerRangePlacement(String layer, int inset, boolean aboveWater) {
        this.layer = layer;
        this.inset = inset;
        this.aboveWater = aboveWater;
    }

    public String layer() { return layer; }
    public int inset() { return inset; }
    public boolean aboveWater() { return aboveWater; }

    @Override
    public Stream<BlockPos> getPositions(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Shape s = DeepWorld.shape();
        if (s == null || !DeepWorld.installed()) return Stream.empty();
        int li = s.layerIndex(layer);
        if (li < 0) return Stream.empty();
        int min = (aboveWater ? s.waterY(li) : s.bottom(li)) + inset, max = s.top(li) - inset;
        if (max < min) return Stream.empty();
        return Stream.of(new BlockPos(pos.getX(), min + random.nextInt(max - min + 1), pos.getZ()));
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.LAYER_RANGE.get(); }
}
