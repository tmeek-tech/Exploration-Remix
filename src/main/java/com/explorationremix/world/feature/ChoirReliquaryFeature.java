package com.explorationremix.world.feature;

import com.explorationremix.block.ReliquaryBlock;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sets the one choir key an ancient city holds into a reliquary at its heart (0.21.9): a casket on the floor of the
 * city's middle, as near its planned heart as the floor allows. Three cities' keys call the Hollow Choir
 * ({@code creature.choir.ChoirSummon}), so this is the only thing between a player and the boss.
 *
 * <p>Every city's heart is a planned CITY chamber ({@code Network.cityHearts}), and the city's own start piece stands
 * within 24 blocks of it, so the one chunk that holds the heart is inside the city's middle. That chunk alone looks
 * for the spot, over its own 256 columns and nothing else: no chunk ever has to ask about another, and no two chunks
 * can both set a casket. A city that vanilla did not actually build (its biome refused it, say) leaves no casket at
 * all, so a key never turns up on bare cavern floor.
 *
 * <p>The spot is the city floor nearest the heart with room to stand over it, taking the lowest floor each column
 * offers, which in vanilla's centre piece is the sunken room with the reinforced frame: the deepest and most obvious
 * room in the city.
 */
public final class ChoirReliquaryFeature extends Feature<NoneFeatureConfiguration> {
    /** How far above and below the heart's planned floor the city's own floors are looked for. */
    private static final int BAND = 34;
    /** Head room wanted over the casket. */
    private static final int ROOM = 2;
    private static final long SALT_FACE = 7301;

    /** For /remix choir: cities whose chunk was built, and caskets actually set. */
    public static final AtomicLong HEARTS = new AtomicLong(), SET = new AtomicLong();

    public ChoirReliquaryFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        boolean any = false;
        for (double[] heart : t.network.cityHearts(t.plans.cellOf(minX + 8), t.plans.cellOf(minZ + 8))) {
            if (Math.floorDiv((int) Math.floor(heart[0]), 16) != cp.x) continue;
            if (Math.floorDiv((int) Math.floor(heart[1]), 16) != cp.z) continue;
            HEARTS.incrementAndGet();
            any |= set(level, t.seed, heart, minX, minZ);
        }
        return any;
    }

    private static boolean set(WorldGenLevel level, long seed, double[] heart, int minX, int minZ) {
        // every spot the chunk offers: a city floor with room over it, nearest the heart first, lowest of those
        List<int[]> spots = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int lo = (int) Math.floor(heart[2]) - BAND, hi = (int) Math.floor(heart[2]) + BAND;
        for (int x = minX; x <= minX + 15; x++)
            for (int z = minZ; z <= minZ + 15; z++)
                for (int y = lo; y <= hi; y++) {
                    p.set(x, y, z);
                    if (!cityFloor(level.getBlockState(p))) continue;
                    if (!roomOver(level, p, x, y, z)) continue;
                    spots.add(new int[]{x, y, z});
                    break; // the lowest floor of this column is the one that counts
                }
        if (spots.isEmpty()) return false;
        spots.sort(Comparator.<int[]>comparingDouble(s -> Math.hypot(s[0] + 0.5 - heart[0], s[2] + 0.5 - heart[1]))
                .thenComparingInt(s -> s[1]));
        int[] best = spots.get(0);
        long h = Hashing.mix(seed, SALT_FACE, best[0], best[2]);
        Direction face = Direction.from2DDataValue((int) (Hashing.unit(h) * 4) & 3);
        level.setBlock(p.set(best[0], best[1] + 1, best[2]),
                ERBlocks.RELIQUARY.get().defaultBlockState().setValue(ReliquaryBlock.FACING, face), 2);
        SET.incrementAndGet();
        return true;
    }

    /** The worked stone an ancient city is floored with: nothing else counts, so no key lands on plain cavern rock. */
    private static boolean cityFloor(BlockState st) {
        return st.is(Blocks.DEEPSLATE_TILES) || st.is(Blocks.CRACKED_DEEPSLATE_TILES)
                || st.is(Blocks.DEEPSLATE_BRICKS) || st.is(Blocks.CRACKED_DEEPSLATE_BRICKS)
                || st.is(Blocks.POLISHED_DEEPSLATE) || st.is(Blocks.CHISELED_DEEPSLATE)
                || st.is(Blocks.DARK_OAK_PLANKS);
    }

    private static boolean roomOver(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        for (int d = 1; d <= ROOM; d++) {
            p.set(x, y + d, z);
            BlockState over = level.getBlockState(p);
            if (!over.isAir() && !over.is(Blocks.SCULK_VEIN) && !over.is(Blocks.LIGHT)) return false;
        }
        return true;
    }
}
