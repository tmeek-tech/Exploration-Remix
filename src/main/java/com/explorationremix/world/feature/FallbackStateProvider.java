package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProviderType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * A block state provider that takes a list of block ids and uses the first one that exists.
 * Lets datapack features name Create's stones with a vanilla fallback when Create is absent.
 */
public final class FallbackStateProvider extends BlockStateProvider {
    public static final Codec<FallbackStateProvider> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.listOf().fieldOf("blocks").forGetter(p -> p.blocks)
    ).apply(i, FallbackStateProvider::new));

    private final List<ResourceLocation> blocks;
    private BlockState resolved;

    public FallbackStateProvider(List<ResourceLocation> blocks) {
        this.blocks = blocks;
    }

    @Override
    protected BlockStateProviderType<?> type() {
        return ERWorldgen.FALLBACK_PROVIDER.get();
    }

    @Override
    public BlockState getState(RandomSource random, BlockPos pos) {
        BlockState s = resolved;
        if (s == null) {
            s = Blocks.STONE.defaultBlockState();
            for (ResourceLocation id : blocks) {
                if (ForgeRegistries.BLOCKS.containsKey(id)) {
                    s = ForgeRegistries.BLOCKS.getValue(id).defaultBlockState();
                    break;
                }
            }
            resolved = s;
        }
        return s;
    }
}
