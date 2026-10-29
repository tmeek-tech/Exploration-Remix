package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * A waterfall or lava fall: a short row of fluid sources set into a cave wall, high enough above the floor for a long
 * drop. The falling column is laid down at once, so a lava fall is there when you arrive instead of creeping down the
 * wall for minutes; the game takes over its flow when the chunk loads. A lava fall always ends in lava, in a pool or in
 * a one-block sump under its foot, so its lava never spreads over the floor.
 */
public final class FluidFallFeature extends Feature<FluidFallFeature.Config> {
    public record Config(BlockState fluid, HolderSet<Block> rock, int minDrop, int search, IntProvider width) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("fluid").forGetter(Config::fluid),
                RegistryCodecs.homogeneousList(Registries.BLOCK).fieldOf("rock").forGetter(Config::rock),
                Codec.intRange(0, 256).optionalFieldOf("min_drop", 8).forGetter(Config::minDrop),
                Codec.intRange(1, 15).optionalFieldOf("search", 10).forGetter(Config::search),
                IntProvider.codec(1, 4).optionalFieldOf("width", net.minecraft.util.valueproviders.ConstantInt.of(1)).forGetter(Config::width)
        ).apply(i, Config::new));
    }

    public FluidFallFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        if (!(c.fluid().getFluidState().getType() instanceof FlowingFluid fluid)) return false;
        if (!level.getBlockState(origin).isAir()) return false;
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        // Walk to the wall.
        BlockPos.MutableBlockPos p = origin.mutable();
        BlockPos wall = null;
        for (int k = 1; k <= c.search(); k++) {
            p.move(dir);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            if (s.is(c.rock())) wall = p.immutable();
            break;
        }
        if (wall == null) return false;
        Direction side = dir.getClockWise();
        int width = c.width().sample(random);
        int placed = 0;
        for (int w = 0; w < width; w++) {
            BlockPos src = wall.relative(side, w);
            if (placeOne(level, c, fluid, src, dir)) placed++;
            else if (w == 0) return false;
        }
        return placed > 0;
    }

    private static boolean placeOne(WorldGenLevel level, Config c, FlowingFluid fluid, BlockPos src, Direction dir) {
        BlockPos front = src.relative(dir.getOpposite());
        if (!level.getBlockState(src).is(c.rock()) || !level.getBlockState(front).isAir()) return false;
        // Sealed on every side but the open face, so the fluid only pours out of the wall.
        if (!sealed(level, c, src.above()) || !sealed(level, c, src.below()) || !sealed(level, c, src.relative(dir))) return false;
        for (Direction d : new Direction[]{dir.getClockWise(), dir.getCounterClockWise()}) {
            BlockState s = level.getBlockState(src.relative(d));
            if (!(s.is(c.rock()) || s.getFluidState().getType().isSame(fluid))) return false;
        }
        // The drop below the open face.
        BlockPos.MutableBlockPos p = front.mutable();
        int drop = 0;
        while (drop < 300 && level.getBlockState(p.move(Direction.DOWN)).isAir()) drop++;
        if (drop < c.minDrop()) return false;
        // A fall never pours onto the way down.
        if (Walkway.near(front.getX(), front.getZ(), front.getY() - drop, front.getY(), 2)) return false;
        BlockState below = level.getBlockState(p);
        boolean intoPool = below.getFluidState().getType().isSame(fluid);
        if (!intoPool && !below.getFluidState().isEmpty()) return false; // never lava into water or the other way round
        // Lava would run on from the foot of its fall once the chunk loads, three blocks every way and down every step,
        // onto the walks that cross the room. So a lava fall that does not end in lava ends in a sump: the floor block
        // under its foot becomes a lava source, closed in by rock below and on all four sides (or by the sump of the
        // fall beside it). Falling lava over a lava source spreads no further, as over a pool.
        BlockPos sump = null;
        if (!intoPool && fluid.isSame(Fluids.LAVA)) {
            sump = p.immutable();
            if (!below.is(c.rock()) || !sealed(level, c, sump.below())) return false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = sump.relative(d);
                FluidState fs = level.getFluidState(n);
                if (!sealed(level, c, n) && !(fs.isSource() && fs.getType().isSame(fluid))) return false;
            }
        }
        level.setBlock(src, fluid.getSource(false).createLegacyBlock(), 2);
        level.setBlock(front, fluid.getFlowing(7, false).createLegacyBlock(), 2);
        BlockState falling = fluid.getFlowing(8, true).createLegacyBlock();
        BlockPos.MutableBlockPos q = front.mutable();
        for (int k = 0; k < drop; k++) level.setBlock(q.move(Direction.DOWN), falling, 2);
        if (sump != null) level.setBlock(sump, fluid.getSource(false).createLegacyBlock(), 2);
        Fluid f = fluid.getSource();
        level.scheduleTick(src, f, 0);
        level.scheduleTick(front, f, 0);
        level.scheduleTick(q.immutable(), f, 0);
        return true;
    }

    private static boolean sealed(WorldGenLevel level, Config c, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.is(c.rock()) || (s.isSolidRender(level, pos) && s.getFluidState().isEmpty());
    }
}
