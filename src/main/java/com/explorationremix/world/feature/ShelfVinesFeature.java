package com.explorationremix.world.feature;

import com.explorationremix.block.CavePlantBlock;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.ERTags;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Shelf vines (section 10): the Deep Blue Rift is 0.2.0's caverns, cliffs and steps included, and a drop of two blocks
 * or more is one way without something to climb. So beside a lip (a standing place on rock with open air from over its
 * head down to two blocks under its feet) a vine hangs in the open column, from the rock over it past the lip down to
 * the floor or the water below, when the drop is 2 to 48 and that rock is at most 40 over the lip: blue glow vines.
 * Only the columns on the vine lines get one (every 10 blocks, {@link Network#shelfVineColumn}), so a lip has a vine at
 * least every 10 blocks along it. The deep dark's lips had them too (dusk vines, then blue glow vines in 0.14.3) until
 * 0.14.4, when Taran asked for no vines in the deep dark: its lips get none, so its drops are one way. The rift's lips
 * keep theirs as they were, the ones that hang down into the deep dark or from its rock included (Taran: the rift is the
 * way into the deep dark, and stays as it is). The voxel harness's {@code markShelfVines} models the same rule. Since
 * 0.14.3 no vine hangs from, beside or onto a block only a structure places ({@link ERTags#BUILT}: an ancient city's
 * bricks, wool and wood), so the cities stand clear of them; the harness holds no structures, so its rule needs no such
 * test.
 *
 * <p>0.21.7 (the Blue Reaches polish; Taran's card: "Climbable lichen streaks"): a lip in one of the noise rifts (not
 * the Deep Blue Rift, whose vines stay as they were) gets a streak of thick blue lichen instead, which climbs like the
 * vine: down the face of the rock under the lip from the lip's own floor to the floor below, where that rock runs
 * unbroken (else the vine as before, where one may hang). It needs no rock over the lip, so the open terraces under a
 * noise rift's far vault get theirs too. A fringe of thin lichen beside it on the same face, now and then, so it reads
 * as lichen grown down the wall. This runs last, after the lichen, crust, crystals, sculk veins and floor plants: the
 * streak takes the place of those in its column ({@link #soft}), where the harness, which has none of them, puts one.
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
                // open, for a streak: the lichen, crust, crystals, sculk veins and small plants the earlier steps left
                // take no room from it (it takes their place; wanting air, it lost about three in ten to them)
                if (!soft(level, p, qx, y + 1, qz) || !soft(level, p, qx, y, qz) || !soft(level, p, qx, y - 1, qz) || !soft(level, p, qx, y - 2, qz)) continue;
                // the lips beside the column: any (a streak's, if the drop is into a noise rift: down the rift's own wall,
                // from a deep dark cavern's floor too where the rift runs under it as a slot), and the rift's (a vine's)
                int lips = 0, riftLips = 0;
                for (int d = 0; d < 4; d++) {
                    int x = qx - DX[d], z = qz - DZ[d];
                    if (!stand(level, p, x, y, z)) continue;
                    lips |= 1 << d;
                    if (t.biomeAt(x, y, z) == DeepBiome.DEEP_BLUE_RIFT) riftLips |= 1 << d;
                }
                if (lips == 0) continue;
                // in a noise rift, a lichen streak down the rock under the lip where it runs unbroken
                int sy = y - 2;
                while (sy > lo - 2 && soft(level, p, qx, sy - 1, qz)) sy--;
                if (y - sy <= Network.STREAK_MAX_DROP && floor(level, p, qx, sy, qz) && t.network.streakAt(qx, sy, qz)) {
                    int face = -1;
                    for (int d = 0; d < 4 && face < 0; d++) if ((lips & 1 << d) != 0 && backed(level, p, qx, qz, d, sy, y)) face = d;
                    if (face >= 0 && !touchesBuilt(level, p, qx, sy, y, qz)) {
                        streak(level, p, cp, t.seed, qx, qz, face, sy, y);
                        any = true;
                        last = sy;
                        continue;
                    }
                }
                // else the rift's vine, as before: through open air only
                if (riftLips == 0 || !air(level, p, qx, y + 1, qz) || !air(level, p, qx, y, qz) || !air(level, p, qx, y - 1, qz) || !air(level, p, qx, y - 2, qz)) continue;
                int fy = y - 2;
                while (fy > lo - 2 && air(level, p, qx, fy - 1, qz)) fy--;
                if (!floor(level, p, qx, fy, qz) || y - fy > Network.SHELF_MAX_DROP) continue;
                int hy = y + 2;
                while (hy <= y + 1 + Network.SHELF_MAX_UP && air(level, p, qx, hy, qz)) hy++;
                if (hy > y + 1 + Network.SHELF_MAX_UP || !level.getBlockState(p.set(qx, hy, qz)).isFaceSturdy(level, p, Direction.DOWN)) continue;
                if (touchesBuilt(level, p, qx, fy, hy, qz)) continue;
                any |= ClimbVinesFeature.hang(level, qx, hy - 1, fy, qz, ERBlocks.BLUE_GLOW_VINES.get(), ERBlocks.BLUE_GLOW_VINES_PLANT.get());
                last = fy;
            }
        }
        return any;
    }

    /**
     * Whether the rock behind a streak in column (qx, qz), on the side of its lip in direction d, runs unbroken from the
     * floor's height ({@code fy}) up to the lip's floor ({@code y - 1}), each block's face to the column sturdy.
     */
    private static boolean backed(WorldGenLevel level, BlockPos.MutableBlockPos p, int qx, int qz, int d, int fy, int y) {
        Direction face = Direction.fromDelta(DX[d], 0, DZ[d]);
        for (int yy = fy; yy < y; yy++)
            if (!level.getBlockState(p.set(qx - DX[d], yy, qz - DZ[d])).isFaceSturdy(level, p, face)) return false;
        return true;
    }

    /**
     * The streak in column (qx, qz) from {@code fy} up to {@code y - 1}, on the rock in direction d's lip side, and its
     * fringe: thin lichen on the same face in the columns either side of it in the chunk, where they are open over
     * sturdy rock, about one block in three.
     */
    private static void streak(WorldGenLevel level, BlockPos.MutableBlockPos p, ChunkPos cp, long seed, int qx, int qz, int d, int fy, int y) {
        Direction toRock = Direction.fromDelta(-DX[d], 0, -DZ[d]);
        BlockState thick = ERBlocks.THICK_BLUE_LICHEN.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(toRock), true);
        BlockState thin = ERBlocks.BLUE_LICHEN.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(toRock), true);
        for (int yy = fy; yy < y; yy++) level.setBlock(p.set(qx, yy, qz), thick, 2);
        int sx = DZ[d], sz = DX[d];   // along the wall
        for (int side = -1; side <= 1; side += 2) {
            int x = qx + side * sx, z = qz + side * sz;
            if ((x >> 4) != cp.x || (z >> 4) != cp.z) continue;
            for (int yy = fy; yy < y; yy++) {
                if (Hashing.unit(Hashing.mix(seed ^ 0x51CEL, x, yy, z)) >= 0.35) continue;
                if (!air(level, p, x, yy, z)) continue;
                if (!level.getBlockState(p.set(x - DX[d], yy, z - DZ[d])).isFaceSturdy(level, p, toRock.getOpposite())) continue;
                level.setBlock(p.set(x, yy, z), thin, 2);
            }
        }
    }

    /**
     * True when a vine in column (x, z) hanging from the block at {@code top} down to {@code fy} (the floor under it at
     * fy - 1) would touch a built block: the one it hangs from, the floor, or one beside it on the way down.
     */
    private static boolean touchesBuilt(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int fy, int top, int z) {
        if (level.getBlockState(p.set(x, top, z)).is(ERTags.BUILT) || level.getBlockState(p.set(x, fy - 1, z)).is(ERTags.BUILT)) return true;
        for (int y = fy; y < top; y++)
            for (int d = 0; d < 4; d++)
                if (level.getBlockState(p.set(x + DX[d], y, z + DZ[d])).is(ERTags.BUILT)) return true;
        return false;
    }

    private static boolean air(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        return level.getBlockState(p.set(x, y, z)).isAir();
    }

    /**
     * Air, or what a streak may take the place of: lichen, crust or sculk veins on the rock (multiface blocks), a crystal
     * or bud, or a one-block cave plant, out of the water. Not a tall plant, a cattail or a vine, whose other blocks it
     * would leave.
     */
    private static boolean soft(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        BlockState s = level.getBlockState(p.set(x, y, z));
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return false;
        Block b = s.getBlock();
        return b instanceof MultifaceBlock || b instanceof AmethystClusterBlock || b instanceof CavePlantBlock;
    }

    /** Whether something hung or grown down column (x, z) to {@code fy} has a floor under it: water, or a sturdy top. */
    private static boolean floor(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int fy, int z) {
        BlockState under = level.getBlockState(p.set(x, fy - 1, z));
        return under.getFluidState().is(FluidTags.WATER) || under.isFaceSturdy(level, p, Direction.UP);
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
