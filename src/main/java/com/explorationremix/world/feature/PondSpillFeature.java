package com.explorationremix.world.feature;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * The spilling ponds' waterfalls (0.21.7, Taran's card "About half"): about half the ponds on the Blue Reaches' rift
 * shelves spill, their water running out along a trough through the rim to the front of the pond's platform
 * ({@link Network#pondsIn}). The deep world's water never flows while the world is made, so this lays the flow at
 * once, as {@link FluidFallFeature} lays its falls: from each open block beside a pond's water at the water's level
 * (the trough's mouth, and the odd block beside it), flowing water, and under it a falling column down to the rock or
 * the water below; then a block on, down the platform's steep side and the ledges under it, flowing water and a fall
 * again, until the flow reaches water, runs out on a floor or would leave the chunk. Their ticks are scheduled, so the
 * game takes the flow over once the chunk is near a player (the foot of the last fall spreading over its floor) and the
 * fall is there already when the chunk is seen from further off.
 *
 * <p>Each flow is laid by the chunk its first block stands in, which reads the pond's water beside it in this chunk or
 * the next, and every block it sets lies in that chunk: a block set in a neighbour not decorated yet would be there
 * before that neighbour's own decorations (see {@link FluidFallFeature} on the other mod's slab pass).
 */
public final class PondSpillFeature extends Feature<NoneFeatureConfiguration> {
    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};
    /** The most steps a laid flow takes (each a block on and the fall under it), and the longest fall it lays. */
    static final int STEPS = 10, MAX_DROP = 160;

    public PondSpillFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int floorY = t.shape.bottom(Terrain.BLUE) + 1;
        boolean any = false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (Network.PondInfo pond : t.network.pondsIn(x0 - 1, z0 - 1, x0 + 16, z0 + 16)) {
            int y = pond.top();
            double reach = Math.max(pond.r() + 3, pond.spills() ? pond.wet() + 2 : 0);
            int ax = Math.max(x0, (int) Math.floor(pond.x() - reach)), bx = Math.min(x0 + 15, (int) Math.ceil(pond.x() + reach));
            int az = Math.max(z0, (int) Math.floor(pond.z() - reach)), bz = Math.min(z0 + 15, (int) Math.ceil(pond.z() + reach));
            for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) {
                if (!room(level.getBlockState(p.set(x, y, z)))) continue;
                // fed by the pond's water beside it: the flow heads away from it, the way the spill runs where it can
                int dir = -1;
                double best = -9;
                for (int d = 0; d < 4; d++) {
                    FluidState fs = level.getFluidState(p.set(x - DX[d], y, z - DZ[d]));
                    if (!fs.isSource() || !fs.is(FluidTags.WATER)) continue;
                    double along = DX[d] * pond.sx() + DZ[d] * pond.sz();
                    if (along > best) { best = along; dir = d; }
                }
                if (dir < 0) continue;
                any |= flow(level, cp, p, x, y, z, dir, pond.sx(), pond.sz(), floorY);
            }
        }
        return any;
    }

    /**
     * The flow from the open block (x, y, z), fed from the side opposite {@code dir}: see the class comment. Each step
     * sets flowing water in its block (as full as the game's flow would have it: seven under a fall's foot or by the
     * pond, one less for each block it runs on over a floor) and a falling column under it while there is room; the
     * next step is a block on from the column's foot, the way that falls soonest, keeping on along the spill (sx, sz)
     * and never back.
     */
    private static boolean flow(WorldGenLevel level, ChunkPos cp, BlockPos.MutableBlockPos p, int x, int y, int z, int dir, double sx, double sz,
                                int floorY) {
        BlockState falling = Fluids.WATER.getFlowing(8, true).createLegacyBlock();
        int amount = 7;
        boolean any = false;
        for (int step = 0; step < STEPS && amount > 0; step++) {
            level.setBlock(p.set(x, y, z), Fluids.WATER.getFlowing(amount, false).createLegacyBlock(), 2);
            level.scheduleTick(p.immutable(), Fluids.WATER, 0);
            any = true;
            int drop = 0;
            while (drop < MAX_DROP && y - drop - 1 > floorY && room(level.getBlockState(p.set(x, y - drop - 1, z)))) drop++;
            for (int k = 1; k <= drop; k++) level.setBlock(p.set(x, y - k, z), falling, 2);
            if (drop > 0) level.scheduleTick(p.set(x, y - drop, z).immutable(), Fluids.WATER, 0);
            // into water (a pool, the stream), or onto rock
            if (!level.getFluidState(p.set(x, y - drop - 1, z)).isEmpty()) break;
            y -= drop;
            amount = drop > 0 ? 7 : amount - 1;
            // on: the block that falls at once, else the one most along the spill; never back the way it came
            int next = -1;
            double best = -9;
            for (int d = 0; d < 4; d++) {
                if (d == (dir ^ 1)) continue;
                int nx = x + DX[d], nz = z + DZ[d];
                if ((nx >> 4) != cp.x || (nz >> 4) != cp.z) continue;
                if (!room(level.getBlockState(p.set(nx, y, nz)))) continue;
                double score = DX[d] * sx + DZ[d] * sz + (room(level.getBlockState(p.set(nx, y - 1, nz))) ? 2 : 0);
                if (score > best) { best = score; next = d; }
            }
            if (next < 0 || best < -0.3) break;
            dir = next;
            x += DX[dir];
            z += DZ[dir];
        }
        return any;
    }

    /**
     * Room for the flow: an open block (air or the glow), or lichen or crust on the rock, which the water washes off (any
     * block that gives way to what is set in it, with no fluid in it).
     */
    static boolean room(BlockState s) {
        return GlowBlock.open(s) || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }
}
