package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.UpperShapes;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Root hairs (0.18.6): vanilla's hanging roots under the roots of the trees overhead, which the Root Vaults' noise
 * shapes leave in the trees' wood (strands over the ceilings and walls, twisted pillars, short stubs), so they read as
 * living roots. The top of a sunken way lies in the surface's biome, out of reach of the vaults' own features, so this
 * runs once per chunk wherever the vaults are in it, over its own columns from the ground down to the roots' depth.
 */
public final class RootHairsFeature extends Feature<NoneFeatureConfiguration> {
    /** The share of a root's open undersides that grow hairs. */
    private static final float CHANCE = 0.3f;
    /** How far under the ground to look: the strands' depth and a little. */
    private static final int DEPTH = 32;

    public RootHairsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        UpperShapes sh = DeepWorld.shapes();
        if (sh == null) return false;
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        ChunkPos cp = new ChunkPos(ctx.origin());
        BlockState hair = Blocks.HANGING_ROOTS.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), above = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                int g = sh.rootGround(x, z);
                if (g == Integer.MIN_VALUE) continue;
                for (int y = g - 1; y >= g - DEPTH; y--) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) continue;
                    BlockState over = level.getBlockState(above.setWithOffset(pos, Direction.UP));
                    if (!over.is(BlockTags.LOGS) || random.nextFloat() >= CHANCE || !hair.canSurvive(level, pos)) continue;
                    level.setBlock(pos, hair, 2);
                    any = true;
                }
            }
        return any;
    }
}
