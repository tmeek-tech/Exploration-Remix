package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Shape;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.HeightProviderType;

/**
 * A height a set number of blocks above a deep layer's water table, so a structure follows the configured layer
 * heights: the ancient cities stand on the floor of the deep dark's lower caverns wherever the Blue Reaches end up.
 * Outside the deep world (switched off, or another dimension's generator) it gives {@code fallback} instead.
 */
public final class LayerHeight extends HeightProvider {
    public static final Codec<LayerHeight> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("layer").forGetter(h -> h.layer),
            Codec.INT.fieldOf("above_fluid").forGetter(h -> h.aboveFluid),
            Codec.INT.fieldOf("fallback").forGetter(h -> h.fallback)
    ).apply(i, LayerHeight::new));

    private final String layer;
    private final int aboveFluid;
    private final int fallback;

    public LayerHeight(String layer, int aboveFluid, int fallback) {
        this.layer = layer;
        this.aboveFluid = aboveFluid;
        this.fallback = fallback;
    }

    @Override
    public int sample(RandomSource random, WorldGenerationContext ctx) {
        Shape s = DeepWorld.shape();
        if (s == null || !DeepWorld.installed() || ctx.getMinGenY() != s.floorY) return fallback;
        int li = s.layerIndex(layer);
        return li < 0 ? fallback : s.waterY(li) + aboveFluid;
    }

    @Override
    public HeightProviderType<?> getType() { return ERWorldgen.LAYER_HEIGHT.get(); }

    @Override
    public String toString() { return "layer " + layer + " table +" + aboveFluid; }
}
