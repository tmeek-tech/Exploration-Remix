package com.explorationremix.world.feature;

import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Shelf vines (section 10): the Deep Blue Rift and the deep dark are 0.2.0's caverns, cliffs and steps included, and a
 * drop of two blocks or more is one way without something to climb. So beside a lip (a standing place on rock with
 * open air from over its head down to two blocks under its feet) a vine hangs in the open column, from the rock over it
 * past the lip down to the floor or the water below, when the drop is 2 to 48 and that rock is at most 40 over the lip:
 * blue glow vines in the rift, dusk vines in the deep dark. Only the columns on the vine lines get one (every 10
 * blocks, {@link Network#shelfVineColumn}), so a lip has a vine at least every 10 blocks along it. The voxel harness's
 * {@code markShelfVines} models the same rule.
 */
public final class ShelfVinesFeature extends Feature<NoneFeatureConfiguration> {
    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    public ShelfVinesFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        // the harness's box: the layer's blocks from its bottom up
        int lo = t.shape.bottom(Terrain.BLUE) + 2, hi = t.shape.bottom(Terrain.BLUE) + t.shape.height(Terrain.BLUE) - 3;
        boolean any = false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int qx = minX; qx < minX + 16; qx++) for (int qz = minZ; qz < minZ + 16; qz++) {
            if (!t.network.shelfVineColumn(qx, qz)) continue;
            int last = Integer.MAX_VALUE;
            for (int y = hi; y >= lo; y--) {
                if (y >= last) continue;
                if (!air(level, p, qx, y + 1, qz) || !air(level, p, qx, y, qz) || !air(level, p, qx, y - 1, qz) || !air(level, p, qx, y - 2, qz)) continue;
                DeepBiome biome = null;
                for (int d = 0; d < 4 && biome == null; d++) {
                    int x = qx - DX[d], z = qz - DZ[d];
                    if (!stand(level, p, x, y, z)) continue;
                    DeepBiome b = t.biomeAt(x, y, z);
                    if (b == DeepBiome.DEEP_BLUE_RIFT || b == DeepBiome.DEEP_DARK) biome = b;
                }
                if (biome == null) continue;
                int fy = y - 2;
                while (fy > lo - 2 && air(level, p, qx, fy - 1, qz)) fy--;
                BlockState under = level.getBlockState(p.set(qx, fy - 1, qz));
                if (y - fy > Network.SHELF_MAX_DROP || !(under.getFluidState().is(FluidTags.WATER) || under.isFaceSturdy(level, p, Direction.UP))) continue;
                int hy = y + 2;
                while (hy <= y + 1 + Network.SHELF_MAX_UP && air(level, p, qx, hy, qz)) hy++;
                if (hy > y + 1 + Network.SHELF_MAX_UP || !level.getBlockState(p.set(qx, hy, qz)).isFaceSturdy(level, p, Direction.DOWN)) continue;
                Block head = biome == DeepBiome.DEEP_DARK ? ERBlocks.DUSK_VINES.get() : ERBlocks.BLUE_GLOW_VINES.get();
                Block body = biome == DeepBiome.DEEP_DARK ? ERBlocks.DUSK_VINES_PLANT.get() : ERBlocks.BLUE_GLOW_VINES_PLANT.get();
                any |= ClimbVinesFeature.hang(level, qx, hy - 1, fy, qz, head, body);
                last = fy;
            }
        }
        return any;
    }

    private static boolean air(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        return level.getBlockState(p.set(x, y, z)).isAir();
    }

    /** A standing place on rock: feet and head open (air or plants, or water), a block below with a sturdy top. */
    private static boolean stand(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        for (int k = 0; k < 2; k++) {
            BlockState s = level.getBlockState(p.set(x, y + k, z));
            if (!s.isAir() && !s.getCollisionShape(level, p).isEmpty()) return false;
        }
        return level.getBlockState(p.set(x, y - 1, z)).isFaceSturdy(level, p, Direction.UP);
    }
}
