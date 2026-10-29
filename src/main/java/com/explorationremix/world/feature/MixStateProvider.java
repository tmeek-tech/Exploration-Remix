package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.SimpleWeightedRandomList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProviderType;

/**
 * A weighted mix of other state providers, so a mix can include a {@link FallbackStateProvider} (a Create stone with
 * its vanilla stand-in), which vanilla's weighted provider cannot.
 */
public final class MixStateProvider extends BlockStateProvider {
    public static final Codec<MixStateProvider> CODEC = RecordCodecBuilder.create(i -> i.group(
            SimpleWeightedRandomList.wrappedCodec(BlockStateProvider.CODEC).fieldOf("entries").forGetter(p -> p.entries)
    ).apply(i, MixStateProvider::new));

    private final SimpleWeightedRandomList<BlockStateProvider> entries;

    public MixStateProvider(SimpleWeightedRandomList<BlockStateProvider> entries) { this.entries = entries; }

    @Override
    protected BlockStateProviderType<?> type() { return ERWorldgen.MIX_PROVIDER.get(); }

    @Override
    public BlockState getState(RandomSource random, BlockPos pos) {
        return entries.getRandomValue(random).map(p -> p.getState(random, pos)).orElse(Blocks.STONE.defaultBlockState());
    }
}
