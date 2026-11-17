package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Palette;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/**
 * Keeps a Fern Rift feature to one of the rift's bands by depth, {@code green} up top, {@code silver} through the
 * middle or {@code blue} by the floor ({@link Palette#riftBand}, 0.14.5): a try is kept as often as its spot lies in
 * the band, so where two bands meet their plants thin out into each other over a few blocks. Put it after the scans
 * that find the try's floor or ceiling, so it judges the spot the plant grows from.
 */
public final class RiftBandPlacement extends PlacementFilter {
    public static final Codec<RiftBandPlacement> CODEC = Codec.STRING.comapFlatMap(RiftBandPlacement::parse, RiftBandPlacement::band)
            .fieldOf("band").codec();

    private final String band;
    private final int index;

    private RiftBandPlacement(String band, int index) {
        this.band = band;
        this.index = index;
    }

    private static DataResult<RiftBandPlacement> parse(String band) {
        return switch (band) {
            case "green" -> DataResult.success(new RiftBandPlacement(band, Palette.RIFT_GREEN));
            case "silver" -> DataResult.success(new RiftBandPlacement(band, Palette.RIFT_SILVER));
            case "blue" -> DataResult.success(new RiftBandPlacement(band, Palette.RIFT_BLUE));
            default -> DataResult.error(() -> "rift_band: no band " + band + " (green, silver or blue)");
        };
    }

    public String band() { return band; }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Terrain t = DeepWorld.terrain();
        if (t == null) return false;
        double w = t.palette.riftBand(index, pos.getX(), pos.getY(), pos.getZ());
        return w >= 1 || w > 0 && random.nextDouble() < w;
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.RIFT_BAND.get(); }
}
