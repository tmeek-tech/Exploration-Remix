package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.plan.Gate;
import com.explorationremix.world.plan.LayerPlans;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Guarantees every gate tube is open. The density interpolation works in 4x8x4 cells, which can pinch a tube that
 * twists through a 16-block band; this pass re-carves the tube's core exactly, block by block, inside each chunk.
 */
public final class GateTubeFeature extends Feature<NoneFeatureConfiguration> {
    public GateTubeFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        if (!DeepWorld.installed()) return false;
        LayerPlans plans = DeepWorld.plans();
        if (plans == null) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ(), maxX = minX + 15, maxZ = minZ + 15;
        boolean any = false;
        int cellX = plans.cellOf(minX + 8), cellZ = plans.cellOf(minZ + 8);
        for (int band = 1; band <= DeepWorld.bandCount(); band++) {
            for (int dcx = -1; dcx <= 1; dcx++) for (int dcz = -1; dcz <= 1; dcz++) {
                Gate g = plans.gate(band, cellX + dcx, cellZ + dcz);
                double r = g.radius() + 1.0;
                double bx0 = Math.min(g.upperX(), g.lowerX()) - 40, bx1 = Math.max(g.upperX(), g.lowerX()) + 40;
                double bz0 = Math.min(g.upperZ(), g.lowerZ()) - 40, bz1 = Math.max(g.upperZ(), g.lowerZ()) + 40;
                if (maxX < bx0 || minX > bx1 || maxZ < bz0 || minZ > bz1) continue;
                any |= carve(level, g, r, minX, minZ, maxX, maxZ);
            }
        }
        return any;
    }

    private static boolean carve(WorldGenLevel level, Gate g, double r, int minX, int minZ, int maxX, int maxZ) {
        double len = Math.abs(g.upperY() - g.lowerY()) + Math.hypot(g.upperX() - g.lowerX(), g.upperZ() - g.lowerZ());
        int steps = (int) Math.ceil(len * 1.5);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.CAVE_AIR.defaultBlockState();
        boolean any = false;
        int ri = (int) Math.ceil(r);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double cx = g.px(t), cy = g.py(t), cz = g.pz(t);
            if (cx + r < minX || cx - r > maxX || cz + r < minZ || cz - r > maxZ) continue;
            int x0 = Math.max(minX, (int) Math.floor(cx - r)), x1 = Math.min(maxX, (int) Math.ceil(cx + r));
            int z0 = Math.max(minZ, (int) Math.floor(cz - r)), z1 = Math.min(maxZ, (int) Math.ceil(cz + r));
            int y0 = Math.max(level.getMinBuildHeight() + 1, (int) Math.floor(cy - r)), y1 = Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(cy + r));
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) {
                double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
                if (dx * dx + dy * dy + dz * dz > r * r) continue;
                pos.set(x, y, z);
                BlockState st = level.getBlockState(pos);
                if (st.isAir() || !st.getFluidState().isEmpty() || st.is(Blocks.BEDROCK)) continue;
                level.setBlock(pos, air, 2);
                any = true;
            }
        }
        return any;
    }
}
