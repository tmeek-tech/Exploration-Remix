package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomes;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Turns the lava lakes at the bottom of the pack's caves into still water inside the Rootvaults. The Rootvaults take
 * the deep dark's old places, which sit right where the pack fills its lowest caves with lava, and their pictures
 * show clear pools. Runs once per chunk, only on blocks inside the Rootvaults, and only within the chunk.
 */
public final class LavaToWaterFeature extends Feature<NoneFeatureConfiguration> {
    private static final ResourceKey<Biome> ROOTVAULTS = ResourceKey.create(Registries.BIOME, DeepBiomes.ROOTVAULTS);
    /** How far above the pack's floor its lava lakes reach (vanilla's lava level is 10 above a floor of -64). */
    private static final int LAVA_REACH = 24;

    public LavaToWaterFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int floor = DeepWorld.packFloor();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                for (int y = floor; y < floor + LAVA_REACH; y++) {
                    if (!level.getBlockState(p.set(x, y, z)).is(Blocks.LAVA)) continue;
                    if (!level.getBiome(p).is(ROOTVAULTS)) continue;
                    level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
                    any = true;
                }
            }
        }
        return any;
    }
}
