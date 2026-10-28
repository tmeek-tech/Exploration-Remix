package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Rootvaults;
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
 * keeps it above the layer's water or lava table. The layer {@code upper} is the pack's own caves: where a Rootvaults
 * grotto lies under the column, mostly the heights of that grotto (10 to 40 blocks under the ground); otherwise from
 * the old floor up to 128 blocks above it, where the Silver Fern Rift and the Ivory Root Cavern are.
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
        if (layer.equals("upper")) {
            Rootvaults r = DeepWorld.roots();
            int[] band = r != null ? r.band(pos.getX(), pos.getZ()) : null;
            if (band != null && random.nextFloat() < 0.75F)
                return Stream.of(new BlockPos(pos.getX(), band[0] + random.nextInt(band[1] - band[0] + 1), pos.getZ()));
            int lo = s.packFloor + 6 + inset;
            return Stream.of(new BlockPos(pos.getX(), lo + random.nextInt(128 - inset), pos.getZ()));
        }
        int li = s.layerIndex(layer);
        if (li < 0) return Stream.empty();
        int min = (aboveWater ? s.waterY(li) : s.bottom(li)) + inset, max = s.top(li) - inset;
        if (max < min) return Stream.empty();
        return Stream.of(new BlockPos(pos.getX(), min + random.nextInt(max - min + 1), pos.getZ()));
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.LAYER_RANGE.get(); }
}
