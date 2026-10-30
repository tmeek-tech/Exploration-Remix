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
 * {@code cold}, also to entrances under snowy ground (true) or not (false).
 */
public final class RootvaultsLookFilter extends PlacementFilter {
    public static final Codec<RootvaultsLookFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, Entrances.LOOKS - 1).listOf().fieldOf("looks").forGetter(RootvaultsLookFilter::looks),
            Codec.BOOL.optionalFieldOf("cold").forGetter(RootvaultsLookFilter::cold)
    ).apply(i, RootvaultsLookFilter::new));

    private final List<Integer> looks;
    private final Optional<Boolean> cold;

    public RootvaultsLookFilter(List<Integer> looks, Optional<Boolean> cold) {
        this.looks = looks;
        this.cold = cold;
    }

    public List<Integer> looks() { return looks; }

    public Optional<Boolean> cold() { return cold; }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Entrances e = DeepWorld.entrances();
        if (e == null) return false;
        int look = e.look(pos.getX(), pos.getZ());
        if (look < 0 || !looks.contains(look)) return false;
        if (cold.isEmpty()) return true;
        Entrances.Site s = e.siteAt(pos.getX(), pos.getZ());
        return s != null && s.cold == cold.get();
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.ROOTVAULTS_LOOK.get(); }
}
