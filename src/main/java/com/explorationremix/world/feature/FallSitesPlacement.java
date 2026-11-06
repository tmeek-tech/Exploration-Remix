package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * One try at each of the chunk's lava fall sites ({@link FallSites}) whose share lies in [share_min, share_max): its
 * column at the height the try came in with, for open_span to move to the column's vault or a run of its air. Two
 * features that split one set of sites by share (the vault falls and the cliff falls) never stand closer than its
 * spacing, nor do their falls come nearer each other than its keep.
 */
public final class FallSitesPlacement extends PlacementModifier {
    public static final Codec<FallSitesPlacement> CODEC = RecordCodecBuilder.create(i -> i.group(
            FallSites.Spec.CODEC.fieldOf("sites").forGetter(FallSitesPlacement::sites),
            Codec.doubleRange(0, 1).optionalFieldOf("share_min", 0.0).forGetter(FallSitesPlacement::shareMin),
            Codec.doubleRange(0, 1).optionalFieldOf("share_max", 1.0).forGetter(FallSitesPlacement::shareMax)
    ).apply(i, FallSitesPlacement::new));

    private final FallSites.Spec sites;
    private final double shareMin, shareMax;

    public FallSitesPlacement(FallSites.Spec sites, double shareMin, double shareMax) {
        this.sites = sites;
        this.shareMin = shareMin;
        this.shareMax = shareMax;
    }

    public FallSites.Spec sites() { return sites; }
    public double shareMin() { return shareMin; }
    public double shareMax() { return shareMax; }

    @Override
    public Stream<BlockPos> getPositions(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return Stream.empty();
        List<int[]> out = new ArrayList<>();
        FallSites.inChunk(t, sites, Math.floorDiv(pos.getX(), 16) * 16, Math.floorDiv(pos.getZ(), 16) * 16, shareMin, shareMax, out);
        return out.stream().map(c -> new BlockPos(c[0], pos.getY(), c[1]));
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.FALL_SITES.get(); }
}
