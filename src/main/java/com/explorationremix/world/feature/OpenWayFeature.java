package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Net;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.List;

/**
 * Guarantees the ways are open, after every other feature has run. The terrain's density is interpolated in 4x8x4
 * cells, which can pinch a passage that twists through a 16-block band, and other mods' features can drop blocks
 * into it. This pass re-opens, block by block inside each chunk: every gate passage's core, the first gate's ramp up
 * into the pack's caves, a walking corridor along every way of the network (section 12.3), and the open core of every
 * climb. Only blocks you would bump into are removed, so plants, vines and lichen on the path stay; climbable blocks
 * (the climbs' vines) and the rims round the climbs' mouths are never touched.
 */
public final class OpenWayFeature extends Feature<NoneFeatureConfiguration> {
    public OpenWayFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ(), maxX = minX + 15, maxZ = minZ + 15;
        boolean any = false;
        Plans plans = t.plans;
        int cellX = plans.cellOf(minX + 8), cellZ = plans.cellOf(minZ + 8);
        Shape s = t.shape;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int band = 1; band <= s.layerCount; band++) {
            for (int dcx = -1; dcx <= 1; dcx++) for (int dcz = -1; dcz <= 1; dcz++) {
                Plans.Gate g = plans.gate(band, cellX + dcx, cellZ + dcz);
                if (!g.overlaps(minX, minZ, maxX, maxZ, 24)) continue;
                any |= carvePassage(level, g, minX, minZ, maxX, maxZ, pos);
                if (band == 1) any |= carveLink(level, plans.ramp(g), 1.4, minX, minZ, maxX, maxZ, pos);
            }
        }
        for (int li = 0; li < s.layerCount; li++) {
            List<Net.Shaft> shafts = t.shaftsIn(minX, minZ, maxX, maxZ, li);
            // The walking corridor along every way: the feet and two blocks over them.
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Network.WayLines w = t.carvedLinesAt(li, x, z, 1.6);
                    for (int k = 0; k < w.n; k++) {
                        int feet = (int) Math.ceil(w.floor[k] - 1e-9);
                        for (int y = feet; y <= feet + 2; y++) {
                            if (!rim(shafts, x, y, z)) any |= clear(level, pos.set(x, y, z));
                        }
                    }
                }
            }
            // Each climb's open core, and the step off the top of its vines (Network.mustOpen).
            for (Net.Shaft sh : shafts) {
                int top = Network.vineTop(sh);
                for (int x = Math.max(minX, (int) Math.floor(sh.x - sh.r)); x <= Math.min(maxX, (int) Math.ceil(sh.x + sh.r)); x++) {
                    for (int z = Math.max(minZ, (int) Math.floor(sh.z - sh.r)); z <= Math.min(maxZ, (int) Math.ceil(sh.z + sh.r)); z++) {
                        if (x == sh.stepX && z == sh.stepZ) {
                            for (int y = top; y <= top + 1; y++) any |= clear(level, pos.set(x, y, z));
                            continue;
                        }
                        if (Math.hypot(x - sh.x, z - sh.z) >= sh.r - 0.6) continue;
                        for (int y = (int) Math.floor(sh.y0 + 0.5) + 1; y < sh.y1; y++) any |= clear(level, pos.set(x, y, z));
                    }
                }
            }
        }
        return any;
    }

    /** True for a block of the 1-block rim round a climb's mouth (Network.mustStay): left alone. */
    private static boolean rim(List<Net.Shaft> shafts, int x, int y, int z) {
        for (Net.Shaft sh : shafts) {
            if (y != Network.vineTop(sh)) continue;
            double sd = Math.hypot(x - sh.x, z - sh.z);
            if (sd <= sh.r + 0.5 || sd >= sh.r + 1.5) continue;
            if (Math.abs(Network.angleDiff(Math.atan2(z - sh.z, x - sh.x), sh.vineA)) >= Network.RIM_GAP) return true;
        }
        return false;
    }

    private static boolean carvePassage(WorldGenLevel level, Plans.Gate g, int minX, int minZ, int maxX, int maxZ, BlockPos.MutableBlockPos pos) {
        boolean any = false;
        double len = Math.abs(g.y0 - g.y1) * 1.6 + 40;
        int steps = (int) Math.ceil(len * 2);
        for (int i = 0; i <= steps; i++) {
            double tt = i / (double) steps;
            double cx = g.px(tt), cy = g.py(tt), cz = g.pz(tt);
            double r = Math.max(2.2, g.radiusAt(tt) - 1.0);
            if (cx + r < minX || cx - r > maxX || cz + r < minZ || cz - r > maxZ) continue;
            any |= carveBall(level, cx, cy, cz, r, minX, minZ, maxX, maxZ, pos);
        }
        return any;
    }

    private static boolean carveLink(WorldGenLevel level, Route.Link l, double shrink, int minX, int minZ, int maxX, int maxZ, BlockPos.MutableBlockPos pos) {
        boolean any = false;
        double len = Math.sqrt((l.x2() - l.x1()) * (l.x2() - l.x1()) + (l.y2() - l.y1()) * (l.y2() - l.y1()) + (l.z2() - l.z1()) * (l.z2() - l.z1()));
        int steps = (int) Math.ceil(len * 2);
        double r = Math.max(2.0, l.r() - shrink);
        for (int i = 0; i <= steps; i++) {
            double tt = i / (double) Math.max(1, steps);
            double cx = l.x1() + (l.x2() - l.x1()) * tt, cy = l.y1() + (l.y2() - l.y1()) * tt, cz = l.z1() + (l.z2() - l.z1()) * tt;
            if (cx + r < minX || cx - r > maxX || cz + r < minZ || cz - r > maxZ) continue;
            any |= carveBall(level, cx, cy, cz, r, minX, minZ, maxX, maxZ, pos);
        }
        return any;
    }

    private static boolean carveBall(WorldGenLevel level, double cx, double cy, double cz, double r, int minX, int minZ, int maxX, int maxZ,
                                     BlockPos.MutableBlockPos pos) {
        boolean any = false;
        int x0 = Math.max(minX, (int) Math.floor(cx - r)), x1 = Math.min(maxX, (int) Math.ceil(cx + r));
        int z0 = Math.max(minZ, (int) Math.floor(cz - r)), z1 = Math.min(maxZ, (int) Math.ceil(cz + r));
        int y0 = Math.max(level.getMinBuildHeight() + 1, (int) Math.floor(cy - r)), y1 = Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(cy + r));
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) {
            double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
            if (dx * dx + dy * dy + dz * dz > r * r) continue;
            any |= clear(level, pos.set(x, y, z));
        }
        return any;
    }

    private static boolean clear(WorldGenLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir() || !st.getFluidState().isEmpty() || st.is(Blocks.BEDROCK) || st.is(BlockTags.CLIMBABLE)) return false;
        if (st.getCollisionShape(level, pos).isEmpty()) return false;
        level.setBlock(pos, Blocks.CAVE_AIR.defaultBlockState(), 2);
        return true;
    }
}
