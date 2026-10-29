package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayDeque;
import java.util.Optional;

/**
 * Terrace pools: the flat floor around the origin becomes a shallow pool of real water (or lava), held in by a rim
 * along its edge, raised one block round water and flush with the floor round lava, so that anyone who walks into a
 * lava pool can step out. On a terraced floor every step gets its own pool, so they read as a staircase of pools.
 * Plants for the pool edges ({@code edge}) grow in the water along the rim. Plants for the bed ({@code inner}) grow on
 * a share ({@code inner_chance}) of the other water cells.
 */
public final class TerracePoolsFeature extends Feature<TerracePoolsFeature.Config> {
    public record Config(BlockStateProvider rim, BlockStateProvider bed, int radius, Optional<BlockStateProvider> edge,
                         float edgeChance, float overflowChance, boolean lava, Optional<BlockStateProvider> inner,
                         float innerChance) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("rim").forGetter(Config::rim),
                BlockStateProvider.CODEC.fieldOf("bed").forGetter(Config::bed),
                Codec.intRange(2, 15).optionalFieldOf("radius", 9).forGetter(Config::radius),
                BlockStateProvider.CODEC.optionalFieldOf("edge").forGetter(Config::edge),
                Codec.floatRange(0, 1).optionalFieldOf("edge_chance", 0.3F).forGetter(Config::edgeChance),
                Codec.floatRange(0, 1).optionalFieldOf("overflow_chance", 0F).forGetter(Config::overflowChance),
                Codec.BOOL.optionalFieldOf("lava", false).forGetter(Config::lava),
                BlockStateProvider.CODEC.optionalFieldOf("inner").forGetter(Config::inner),
                Codec.floatRange(0, 1).optionalFieldOf("inner_chance", 0F).forGetter(Config::innerChance)
        ).apply(i, Config::new));

        BlockState fluid() { return lava ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState(); }
    }

    public TerracePoolsFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos o = ctx.origin();
        int fy = o.getY() - 1; // the floor's top block
        if (!isFloor(level, o.getX(), fy, o.getZ())) return false;
        // Pools never flood a way or put a rim across it. Beside a way a water pool shrinks until its rim keeps a
        // block clear of the way's corridor (in the network every room has ways through it); a lava pool keeps away
        // altogether.
        int r = c.radius();
        int smallest = c.lava() ? r : Math.min(r, 3);
        while (r >= smallest && Walkway.near(o.getX(), o.getZ(), fy, fy + 1, r + 1)) r--;
        if (r < smallest) return false;
        int size = 2 * r + 1;
        byte[] cell = new byte[size * size]; // 0 unknown/outside, 1 flat floor in the region
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{0, 0});
        cell[r * size + r] = 1;
        int count = 0;
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            count++;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = p[0] + d.getStepX(), nz = p[1] + d.getStepZ();
                if (Math.abs(nx) > r || Math.abs(nz) > r || nx * nx + nz * nz > r * r) continue;
                int idx = (nz + r) * size + nx + r;
                if (cell[idx] != 0) continue;
                if (!isFloor(level, o.getX() + nx, fy, o.getZ() + nz)) { cell[idx] = 2; continue; }
                cell[idx] = 1;
                queue.add(new int[]{nx, nz});
            }
        }
        if (count < 10) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int interior = 0;
        boolean[] water = new boolean[size * size];
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            int idx = (dz + r) * size + dx + r;
            if (cell[idx] != 1) continue;
            boolean edge = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = dx + d.getStepX(), nz = dz + d.getStepZ();
                if (Math.abs(nx) > r || Math.abs(nz) > r || cell[(nz + r) * size + nx + r] != 1) { edge = true; break; }
            }
            if (!edge) { water[idx] = true; interior++; }
        }
        if (interior < 4) return false;
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            int idx = (dz + r) * size + dx + r;
            if (cell[idx] != 1) continue;
            int x = o.getX() + dx, z = o.getZ() + dz;
            if (water[idx]) {
                // The pool's bed is its own (glowing beds light the pool), except under unbreakable rock.
                pos.set(x, fy - 1, z);
                BlockState under = level.getBlockState(pos);
                if (!under.hasBlockEntity() && under.getDestroySpeed(level, pos) >= 0) level.setBlock(pos, c.bed().getState(random, pos), 2);
                pos.set(x, fy, z);
                level.setBlock(pos, c.fluid(), 2);
            } else {
                pos.set(x, fy, z);
                level.setBlock(pos, c.rim().getState(random, pos), 2);
                // A lava pool lies flush with the floor, as the lava basin's last tread does: over a raised rim,
                // anyone who fell in would stand two blocks under its top with no way out. Water pools keep the
                // raised rim.
                pos.set(x, fy + 1, z);
                if (!c.lava() && level.getBlockState(pos).isAir()) level.setBlock(pos, c.rim().getState(random, pos), 2);
            }
        }
        // Where the rim stands over a drop, the pool may spill over it, down to the next step.
        int spills = 0;
        for (int dz = -r; dz <= r && spills < 2; dz++) for (int dx = -r; dx <= r && spills < 2; dx++) {
            int idx = (dz + r) * size + dx + r;
            if (cell[idx] != 1 || water[idx] || random.nextFloat() >= c.overflowChance()) continue;
            boolean byPool = false, overDrop = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = dx + d.getStepX(), nz = dz + d.getStepZ();
                boolean inside = Math.abs(nx) <= r && Math.abs(nz) <= r;
                if (inside && water[(nz + r) * size + nx + r]) byPool = true;
                if (!inside || cell[(nz + r) * size + nx + r] != 1) {
                    pos.set(o.getX() + nx, fy, o.getZ() + nz);
                    if (level.getBlockState(pos).isAir()) overDrop = true;
                }
            }
            if (!byPool || !overDrop) continue;
            pos.set(o.getX() + dx, fy, o.getZ() + dz);
            level.setBlock(pos, c.fluid(), 2);
            level.scheduleTick(pos, c.lava() ? Fluids.LAVA : Fluids.WATER, 0);
            pos.set(o.getX() + dx, fy + 1, o.getZ() + dz);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            spills++;
        }
        // Plants in the water along the rim.
        if (c.edge().isPresent() && !c.lava()) {
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                int idx = (dz + r) * size + dx + r;
                if (!water[idx] || random.nextFloat() >= c.edgeChance()) continue;
                boolean byRim = false;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int nx = dx + d.getStepX(), nz = dz + d.getStepZ();
                    if (Math.abs(nx) <= r && Math.abs(nz) <= r && !water[(nz + r) * size + nx + r]) byRim = true;
                }
                if (!byRim) continue;
                pos.set(o.getX() + dx, fy, o.getZ() + dz);
                BlockState p = c.edge().get().getState(random, pos);
                if (p.hasProperty(BlockStateProperties.WATERLOGGED)) p = p.setValue(BlockStateProperties.WATERLOGGED, true);
                if (p.canSurvive(level, pos)) level.setBlock(pos, p, 2);
            }
        }
        // Plants on the bed inside the pool, where no edge plant stands (pool pearls glowing through the water).
        if (c.inner().isPresent() && !c.lava()) {
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                int idx = (dz + r) * size + dx + r;
                if (!water[idx] || random.nextFloat() >= c.innerChance()) continue;
                pos.set(o.getX() + dx, fy, o.getZ() + dz);
                if (!level.getBlockState(pos).is(Blocks.WATER)) continue;
                BlockState p = c.inner().get().getState(random, pos);
                if (p.hasProperty(BlockStateProperties.WATERLOGGED)) p = p.setValue(BlockStateProperties.WATERLOGGED, true);
                if (p.canSurvive(level, pos)) level.setBlock(pos, p, 2);
            }
        }
        return true;
    }

    /** Solid floor with two blocks of air above it. */
    private static boolean isFloor(WorldGenLevel level, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        return s.isSolid() && !s.hasBlockEntity() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.above(2)).isAir();
    }
}
