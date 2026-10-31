package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Entrances;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.List;
import java.util.Optional;

/**
 * Keeps a feature to some of the entrances' looks: 0 root halls, 1 teal grottos, 2 ravines open to the sky,
 * 3 sinkholes, 4 oasis hollows (see {@link Entrances}); each chamber has one look and its tunnels share it. With
 * {@code cold}, also to entrances under snowy ground (true) or not (false). With {@code min_chill} or {@code max_chill}
 * (cold Root Vaults), also to spots at least or at most that far beyond the snowy zone round the openings
 * ({@link Entrances#chill}): plants deep inside, snow near the openings.
 */
public final class RootvaultsLookFilter extends PlacementFilter {
    public static final Codec<RootvaultsLookFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, Entrances.LOOKS - 1).listOf().fieldOf("looks").forGetter(RootvaultsLookFilter::looks),
            Codec.BOOL.optionalFieldOf("cold").forGetter(RootvaultsLookFilter::cold),
            Codec.DOUBLE.optionalFieldOf("min_chill").forGetter(RootvaultsLookFilter::minChill),
            Codec.DOUBLE.optionalFieldOf("max_chill").forGetter(RootvaultsLookFilter::maxChill)
    ).apply(i, RootvaultsLookFilter::new));

    private final List<Integer> looks;
    private final Optional<Boolean> cold;
    private final Optional<Double> minChill, maxChill;

    public RootvaultsLookFilter(List<Integer> looks, Optional<Boolean> cold, Optional<Double> minChill, Optional<Double> maxChill) {
        this.looks = looks;
        this.cold = cold;
        this.minChill = minChill;
        this.maxChill = maxChill;
    }

    public List<Integer> looks() { return looks; }

    public Optional<Boolean> cold() { return cold; }

    public Optional<Double> minChill() { return minChill; }

    public Optional<Double> maxChill() { return maxChill; }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Entrances e = DeepWorld.entrances();
        if (e == null) return false;
        int look = e.look(pos.getX(), pos.getZ());
        if (look < 0 || !looks.contains(look)) return false;
        if (cold.isEmpty() && minChill.isEmpty() && maxChill.isEmpty()) return true;
        Entrances.Site s = e.siteAt(pos.getX(), pos.getZ());
        if (s == null || cold.isPresent() && s.cold != cold.get()) return false;
        if (minChill.isEmpty() && maxChill.isEmpty()) return true;
        if (!s.cold || s.kind != Entrances.Kind.VAULTS) return false;
        double c = e.chill(s, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return (minChill.isEmpty() || c >= minChill.get()) && (maxChill.isEmpty() || c <= maxChill.get());
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.ROOTVAULTS_LOOK.get(); }
}
