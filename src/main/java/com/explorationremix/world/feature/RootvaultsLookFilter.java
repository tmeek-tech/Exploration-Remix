package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Rootvaults;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.List;

/**
 * Keeps a feature to some of the Rootvaults' three looks: 0 root halls, 1 teal grottos, 2 ravines open to the sky.
 * Each grotto has one look (see {@link Rootvaults}); its tunnels share it.
 */
public final class RootvaultsLookFilter extends PlacementFilter {
    public static final Codec<RootvaultsLookFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, 2).listOf().fieldOf("looks").forGetter(RootvaultsLookFilter::looks)
    ).apply(i, RootvaultsLookFilter::new));

    private final List<Integer> looks;

    public RootvaultsLookFilter(List<Integer> looks) { this.looks = looks; }

    public List<Integer> looks() { return looks; }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Rootvaults r = DeepWorld.roots();
        if (r == null) return false;
        int look = r.look(pos.getX(), pos.getZ());
        return look >= 0 && looks.contains(look);
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.ROOTVAULTS_LOOK.get(); }
}
