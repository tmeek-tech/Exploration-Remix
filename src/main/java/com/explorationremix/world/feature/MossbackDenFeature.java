package com.explorationremix.world.feature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.terrain.Hashing;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Mossback dens, about one to a plant cave: the map is cut into cells of 3 x 3 chunks, each cell names one of its
 * chunks, and that chunk looks for a spot of open floor in a lush cave or Frost Fern Grotto (room for the lizard's
 * box: two by two blocks, open above). Some cells find none, so some plant caves keep no lizard. The den lies on the
 * floor like a carpet, replacing any carpet or small plant there.
 */
public class MossbackDenFeature extends Feature<NoneFeatureConfiguration> {
    private static final ResourceKey<Biome> GROTTO = ResourceKey.create(Registries.BIOME, new ResourceLocation(ExplorationRemix.MOD_ID, "frost_fern_grotto"));
    private static final int CELL = 3, TRIES = 24;

    public MossbackDenFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkPos chunk = new ChunkPos(ctx.origin());
        int cx = Math.floorDiv(chunk.x, CELL), cz = Math.floorDiv(chunk.z, CELL);
        long h = Hashing.mix(level.getSeed() ^ 0x6D6F73736261636BL, cx, 0, cz);
        if (Math.floorMod(chunk.x, CELL) != Math.floorMod((int) h, CELL) || Math.floorMod(chunk.z, CELL) != Math.floorMod((int) (h >>> 20), CELL)) return false;
        RandomSource random = ctx.random();
        List<BlockPos> spots = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bottom = level.getMinBuildHeight() + 2;
        for (int i = 0; i < TRIES && spots.size() < 8; i++) {
            int x = chunk.getMinBlockX() + 1 + random.nextInt(14), z = chunk.getMinBlockZ() + 1 + random.nextInt(14);
            int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 18;
            for (int y = top; y > Math.max(bottom, -64); y--) {
                p.set(x, y, z);
                if (!plantCave(level.getBiome(p))) continue;
                if (fits(level, p)) { spots.add(p.immutable()); break; }
            }
        }
        if (spots.isEmpty()) return false;
        BlockPos at = spots.get(random.nextInt(spots.size()));
        level.setBlock(at, ERBlocks.MOSSBACK_DEN.get().defaultBlockState(), 2);
        return true;
    }

    private static boolean plantCave(Holder<Biome> biome) {
        return biome.is(Biomes.LUSH_CAVES) || biome.is(GROTTO);
    }

    /** Open floor for a lizard: solid ground under 2 x 2 cells, which hold at most a carpet or small plant, open above. */
    private static boolean fits(WorldGenLevel level, BlockPos at) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 2; dx++)
            for (int dz = 0; dz < 2; dz++) {
                q.set(at.getX() + dx, at.getY() - 1, at.getZ() + dz);
                if (!level.getBlockState(q).isFaceSturdy(level, q, net.minecraft.core.Direction.UP)) return false;
                q.set(at.getX() + dx, at.getY(), at.getZ() + dz);
                BlockState s = level.getBlockState(q);
                if (!(s.isAir() || s.canBeReplaced()) || !s.getFluidState().isEmpty()) return false;
                for (int dy = 1; dy <= 2; dy++) {
                    q.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    BlockState above = level.getBlockState(q);
                    if (!above.getCollisionShape(level, q).isEmpty() || !above.getFluidState().isEmpty()) return false;
                }
            }
        return true;
    }
}
