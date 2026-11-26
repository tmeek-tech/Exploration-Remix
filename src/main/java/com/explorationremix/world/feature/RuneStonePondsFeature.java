package com.explorationremix.world.feature;

import com.explorationremix.rune.stone.RuneStoneHost;
import com.explorationremix.rune.stone.RuneStoneKind;
import com.explorationremix.rune.stone.RuneStones;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Blue Reaches' pond stones (0.23.1; Taran: "the Reaches runes in ponds and ancient cities"; card pick "One in
 * three, in the bed"): one pond in three on the rifts' shelves hides a rune stone in its bed, under the water near its
 * middle, in the deepslate the bed is cut in (a bed of gravel there and the next spot out is tried). Which ponds, by
 * each pond's own middle, so every chunk agrees; the chunk holding a pond's middle sets its stone, read from the blocks.
 * The ancient cities' stones are set with the cities ({@link RuneStoneStructures}).
 */
public final class RuneStonePondsFeature extends Feature<NoneFeatureConfiguration> {
    /** The share of ponds that hide a stone. */
    public static final double SHARE = 1.0 / 3.0;
    /** How far from a pond's middle its stone may lie (blocks, and never nearer its shore than SHORE). */
    static final double NEAR = 3.5, SHORE = 1.5;
    private static final long SALT = 0x504F4E44L;

    /** For /remix runestones: ponds met that hide a stone, and stones set in them. */
    public static final AtomicLong PONDS = new AtomicLong(), SET = new AtomicLong();

    public RuneStonePondsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        boolean any = false;
        for (Network.PondInfo pond : t.network.pondsIn(x0, z0, x0 + 15, z0 + 15)) {
            if ((Mth.floor(pond.x()) >> 4) != cp.x || (Mth.floor(pond.z()) >> 4) != cp.z) continue;
            if (!hides(t.seed, pond)) continue;
            PONDS.incrementAndGet();
            BlockPos at = spot(level, t.seed, pond);
            if (at == null) continue;
            RuneStoneHost host = RuneStoneHost.of(level.getBlockState(at));
            if (host == null) continue;
            level.setBlock(at, RuneStoneKind.BLUE_REACHES.state(host), 2);
            SET.incrementAndGet();
            RuneStones.placed(RuneStoneKind.BLUE_REACHES);
            any = true;
        }
        return any;
    }

    /** Whether a pond hides a stone: one in three, by its middle. */
    public static boolean hides(long seed, Network.PondInfo pond) {
        return Hashing.unit(Hashing.mix(seed, SALT, Mth.floor(pond.x() * 4), Mth.floor(pond.z() * 4))) < SHARE;
    }

    /**
     * The bed block under the pond's water that takes its stone: of the columns within NEAR of its middle (and SHORE in
     * from its shore), nearest the middle first with a little of the pond's own jitter, the first whose bed (the first
     * block under its water) is a stone the Blue Reaches' rune stones hide in.
     */
    @Nullable
    static BlockPos spot(WorldGenLevel level, long seed, Network.PondInfo pond) {
        double reach = Math.min(NEAR, pond.r() - SHORE);
        if (reach < 0.5) reach = 0.5;
        List<double[]> cols = new ArrayList<>();
        int r = (int) Math.ceil(reach);
        int cx = Mth.floor(pond.x()), cz = Mth.floor(pond.z());
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) {
                double d = Math.hypot(x + 0.5 - pond.x(), z + 0.5 - pond.z());
                if (d > reach) continue;
                cols.add(new double[]{d + 0.8 * Hashing.unit(Hashing.mix(seed, SALT ^ 1, x, z)), x, z});
            }
        cols.sort(Comparator.comparingDouble(c -> c[0]));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (double[] c : cols) {
            int x = (int) c[1], z = (int) c[2];
            for (int y = pond.top(); y >= pond.top() - pond.depth() - 3; y--) {
                BlockState s = level.getBlockState(p.set(x, y, z));
                if (s.getFluidState().is(FluidTags.WATER)) continue;
                // the bed: under the water, and a stone it may hide in
                boolean wet = level.getFluidState(p.set(x, y + 1, z)).is(FluidTags.WATER);
                RuneStoneHost host = RuneStoneHost.of(s);
                if (wet && host != null && RuneStoneKind.BLUE_REACHES.takes(host)) return new BlockPos(x, y, z);
                break;
            }
        }
        return null;
    }
}
