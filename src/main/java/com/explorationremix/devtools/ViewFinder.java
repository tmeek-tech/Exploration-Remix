package com.explorationremix.devtools;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * Finds a spot to stand with a wide view into a biome, for screenshots (/remix view): it tries standing places around
 * a point and casts rays across a field of view in sixteen directions and three pitches, scoring each view by how far
 * it reaches and how much of it stays in the biome.
 */
final class ViewFinder {
    record View(double x, double y, double z, float yaw, float pitch, double reach, double score) {}

    private static final float[] PITCHES = {-14F, 3F, 20F};
    private static final int DIRS = 16, REACH = 72;

    private ViewFinder() {}

    @Nullable
    static View find(ServerLevel level, BlockPos centre, Predicate<BlockPos> inBiome, int radius, int vRadius) {
        int step = Math.max(2, radius / 10);
        // Load everything the rays can reach first, so no ray stops at an unloaded chunk.
        int cr = (radius + REACH) / 16 + 1;
        for (int cx = -cr; cx <= cr; cx++) for (int cz = -cr; cz <= cr; cz++) {
            level.getChunk((centre.getX() >> 4) + cx, (centre.getZ() >> 4) + cz, ChunkStatus.FULL, true);
        }
        BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        View best = null;
        double[][] d = new double[DIRS][PITCHES.length], frac = new double[DIRS][PITCHES.length];
        for (int dx = -radius; dx <= radius; dx += step) for (int dz = -radius; dz <= radius; dz += step) {
            for (int dy = -vRadius; dy <= vRadius; dy += 2) {
                feet.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                if (!standable(level, feet) || !inBiome.test(feet)) continue;
                double ex = feet.getX() + 0.5, ey = feet.getY() + 1.62, ez = feet.getZ() + 0.5;
                for (int k = 0; k < DIRS; k++) for (int p = 0; p < PITCHES.length; p++) {
                    double[] r = ray(level, ex, ey, ez, k * 360F / DIRS, PITCHES[p], inBiome);
                    d[k][p] = r[0];
                    frac[k][p] = r[1];
                }
                for (int k = 0; k < DIRS; k++) for (int p = 0; p < PITCHES.length; p++) {
                    // A view is as good as the field around its centre: the neighbouring directions and pitches count too.
                    double s = 0, w = 0, reach = 0;
                    for (int j = -1; j <= 1; j++) for (int q = 0; q < PITCHES.length; q++) {
                        int kk = Math.floorMod(k + j, DIRS);
                        double weight = (j == 0 ? 2 : 1) * (q == p ? 2 : 0.5);
                        s += weight * d[kk][q] * (0.3 + 0.7 * frac[kk][q]);
                        w += weight;
                        if (j == 0 && q == p) reach = d[kk][q];
                    }
                    s /= w;
                    if (p == 1) s *= 1.08; // a level view reads best
                    if (best == null || s > best.score()) best = new View(ex, feet.getY(), ez, k * 360F / DIRS, PITCHES[p], reach, s);
                }
            }
        }
        return best;
    }

    /** Feet and head in air (or plants), something solid underneath. */
    static boolean standable(ServerLevel level, BlockPos feet) {
        BlockState f = level.getBlockState(feet), h = level.getBlockState(feet.above()), u = level.getBlockState(feet.below());
        return open(f) && f.getFluidState().isEmpty() && open(h) && h.getFluidState().isEmpty()
                && u.isFaceSturdy(level, feet.below(), Direction.UP);
    }

    private static boolean open(BlockState s) { return s.isAir() || (!s.blocksMotion() && !s.canOcclude()); }

    /** {distance until the view is blocked, fraction of the samples along it inside the biome}. */
    private static double[] ray(ServerLevel level, double x, double y, double z, float yaw, float pitch, Predicate<BlockPos> inBiome) {
        float yr = yaw * Mth.DEG_TO_RAD, pr = pitch * Mth.DEG_TO_RAD;
        double dx = -Mth.sin(yr) * Mth.cos(pr), dy = -Mth.sin(pr), dz = Mth.cos(yr) * Mth.cos(pr);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int in = 0, samples = 0;
        double dist = REACH;
        for (int i = 1; i <= REACH; i++) {
            p.set(Mth.floor(x + dx * i), Mth.floor(y + dy * i), Mth.floor(z + dz * i));
            if (p.getY() < level.getMinBuildHeight() || p.getY() >= level.getMaxBuildHeight()) { dist = i; break; }
            ChunkAccess c = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            if (c == null) { dist = i; break; }
            BlockState s = c.getBlockState(p);
            if (!s.isAir() && s.getFluidState().isEmpty() && (s.canOcclude() || s.blocksMotion())) { dist = i; break; }
            if (i % 4 == 0) {
                samples++;
                if (inBiome.test(p)) in++;
            }
        }
        return new double[]{dist, samples == 0 ? 0 : in / (double) samples};
    }
}
