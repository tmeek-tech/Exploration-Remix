package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Palette;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/**
 * Thins a Frost Fern Grotto feature by how icy the grotto is at its spot ({@link Palette#grottoIce}: 0 near the top, 1
 * deep down; 0.20.1, Taran: "the deeper caves more icy"). A try is kept as often as {@code top} says near the top and
 * {@code deep} deep down, in between in proportion: so the icicles, ice columns, frozen pools and ice crusts grow and
 * the frost moss patches thin out as one goes down. Put it after the scans that find the try's floor or ceiling, so it
 * judges the spot the feature grows from.
 */
public final class GrottoIceFilter extends PlacementFilter {
    public static final Codec<GrottoIceFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.floatRange(0, 1).fieldOf("top").forGetter(GrottoIceFilter::top),
            Codec.floatRange(0, 1).fieldOf("deep").forGetter(GrottoIceFilter::deep)
    ).apply(i, GrottoIceFilter::new));

    private final float top, deep;

    public GrottoIceFilter(float top, float deep) {
        this.top = top;
        this.deep = deep;
    }

    public float top() { return top; }

    public float deep() { return deep; }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Terrain t = DeepWorld.terrain();
        if (t == null) return false;
        int ground = ctx.getHeight(Heightmap.Types.WORLD_SURFACE_WG, pos.getX(), pos.getZ());
        double keep = top + (deep - top) * t.palette.grottoIce(pos.getX(), pos.getY(), pos.getZ(), ground);
        return keep >= 1 || keep > 0 && random.nextDouble() < keep;
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.GROTTO_ICE.get(); }
}
