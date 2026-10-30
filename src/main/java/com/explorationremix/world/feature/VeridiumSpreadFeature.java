package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomes;
import com.explorationremix.world.gen.DeepMaterials;
import com.explorationremix.world.terrain.Rock;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Where a Root Vault's descent opens into a lush cave (or the Frost Fern Grotto), the vault's veridium runs on into
 * it: the bare stone of the cave's walls, floors and ceilings within 9 to 15 blocks of the descent's end turns to
 * veridium, thinning out at the edge, so the plant cave reads as the vault's lower end. Moss, clay and plants stay. It
 * is placed at the descents' ends ({@link JunctionsPlacement}), after the plants, and does nothing where the end is not
 * in a plant cave.
 */
public final class VeridiumSpreadFeature extends Feature<NoneFeatureConfiguration> {
    public VeridiumSpreadFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        if (!DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        BlockPos o = ctx.origin();
        RandomSource random = ctx.random();
        Holder<Biome> frost = DeepBiomes.frostGrotto();
        // The end may lie at the plant cave's edge: look at it and a few blocks round it.
        boolean plants = false;
        for (int[] d : new int[][]{{0, 0, 0}, {4, 0, 0}, {-4, 0, 0}, {0, 0, 4}, {0, 0, -4}, {0, -4, 0}, {0, 4, 0}}) {
            if (plantCave(level.getBiome(o.offset(d[0], d[1], d[2])), frost)) { plants = true; break; }
        }
        if (!plants) return false;
        BlockState veridium = DeepMaterials.state(Rock.VERIDIUM);
        double r = 9 + random.nextDouble() * 6;
        int reach = (int) Math.ceil(r);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(), side = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int dx = -reach; dx <= reach; dx++) for (int dy = -reach; dy <= reach; dy++) for (int dz = -reach; dz <= reach; dz++) {
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > r) continue;
            // Solid inside, ragged over its last 4 blocks.
            if (d > r - 4 && random.nextDouble() < (d - (r - 4)) / 4) continue;
            at.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
            BlockState st = level.getBlockState(at);
            if (!st.is(BlockTags.BASE_STONE_OVERWORLD)) continue;
            boolean bare = false;
            for (Direction f : Direction.values()) {
                BlockState n = level.getBlockState(side.setWithOffset(at, f));
                if (n.isAir() || !n.getFluidState().isEmpty() || !n.blocksMotion()) { bare = true; break; }
            }
            if (!bare || !plantCave(level.getBiome(at), frost)) continue;
            level.setBlock(at, veridium, 2);
            any = true;
        }
        return any;
    }

    private static boolean plantCave(Holder<Biome> b, Holder<Biome> frost) {
        return b.is(Biomes.LUSH_CAVES) || (frost != null && b == frost);
    }
}
