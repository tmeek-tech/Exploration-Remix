package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
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
 * The look changes every couple of hundred blocks, the same way the rock does.
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
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        return looks.contains(t.palette.rootvaultsLook(pos.getX(), pos.getZ()));
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.ROOTVAULTS_LOOK.get(); }
}
