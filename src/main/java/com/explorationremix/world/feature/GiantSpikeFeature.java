package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

import java.util.Optional;

/**
 * A giant stalactite hanging from the ceiling at the origin, up to about 30 blocks, with a matching stalagmite rising
 * under it when there is floor in reach. The thick part is {@code body}, the last stretch of each point {@code tip},
 * and an optional {@code point} block (pointed dripstone) caps a stalactite.
 */
public final class GiantSpikeFeature extends Feature<GiantSpikeFeature.Config> {
    public record Config(BlockStateProvider body, BlockStateProvider tip, Optional<BlockState> point, IntProvider length,
                         FloatProvider radius, float floorChance) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("body").forGetter(Config::body),
                BlockStateProvider.CODEC.fieldOf("tip").forGetter(Config::tip),
                BlockState.CODEC.optionalFieldOf("point").forGetter(Config::point),
                IntProvider.codec(3, 48).fieldOf("length").forGetter(Config::length),
                FloatProvider.codec(1.0F, 8.0F).fieldOf("radius").forGetter(Config::radius),
                Codec.floatRange(0, 1).optionalFieldOf("floor_chance", 0.6F).forGetter(Config::floorChance)
        ).apply(i, Config::new));
    }

    public GiantSpikeFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos ceiling = ctx.origin();
        if (level.getBlockState(ceiling).isAir() || !level.getBlockState(ceiling.below()).isAir()) return false;
        // Room below: the spike never reaches more than two thirds of the way to the floor.
        int room = 0;
        while (room < 64 && level.getBlockState(ceiling.below(room + 1)).isAir()) room++;
        if (room < 6) return false;
        int len = Math.min(c.length().sample(random), (int) (room * 0.66));
        float r = Math.min(c.radius().sample(random), Math.max(1.2F, len / 4.5F));
        double leanX = (random.nextDouble() - 0.5) * 0.25, leanZ = (random.nextDouble() - 0.5) * 0.25;
        // Nothing hangs into the way down or stands on it.
        if (Walkway.near(ceiling.getX(), ceiling.getZ(), ceiling.getY() - len, ceiling.getY(), r + len * 0.13 + 1)) return false;
        spike(level, random, c, ceiling, -1, len, r, leanX, leanZ, true);
        // A stalagmite under it, on dry rock only (never out of a lake).
        BlockPos floor = ceiling.below(room + 1);
        BlockState floorState = level.getBlockState(floor);
        if (random.nextFloat() < c.floorChance() && room < 60 && floorState.isSolid() && floorState.getFluidState().isEmpty()) {
            int up = Math.min(room - len - 2, (int) (len * (0.5 + random.nextDouble() * 0.6)));
            if (up >= 4 && !Walkway.near(floor.getX(), floor.getZ(), floor.getY(), floor.getY() + up, r + up * 0.13 + 1))
                spike(level, random, c, floor, 1, up, r * 0.9F, -leanX, -leanZ, false);
        }
        return true;
    }

    private static void spike(WorldGenLevel level, RandomSource random, Config c, BlockPos base, int dir, int len, float r,
                              double leanX, double leanZ, boolean hanging) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int ri = (int) Math.ceil(r) + 1;
        // Root it a few blocks into the rock so no gap shows where it joins.
        for (int dy = -3; dy <= len; dy++) {
            double t = Math.max(0, dy) / (double) len;
            double rad = r * Math.pow(1 - t, 1.3) + (dy < 0 ? 1.0 : 0.0);
            if (rad < 0.45) {
                if (dy >= len - 1 && hanging && c.point().isPresent()) {
                    pos.set(base.getX() + (int) Math.round(leanX * dy), base.getY() + dir * dy, base.getZ() + (int) Math.round(leanZ * dy));
                    if (level.getBlockState(pos).isAir() && !level.getBlockState(pos.relative(net.minecraft.core.Direction.UP)).isAir())
                        level.setBlock(pos, c.point().get(), 2);
                }
                break;
            }
            double cx = base.getX() + 0.5 + leanX * Math.max(0, dy), cz = base.getZ() + 0.5 + leanZ * Math.max(0, dy);
            int y = base.getY() + dir * dy;
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double px = base.getX() + dx + 0.5 - cx, pz = base.getZ() + dz + 0.5 - cz;
                double wob = 0.35 * Mth.sin((float) (dy * 0.9 + dx * 1.7 + dz * 2.3));
                if (px * px + pz * pz > (rad + wob) * (rad + wob)) continue;
                pos.set(base.getX() + dx, y, base.getZ() + dz);
                BlockState here = level.getBlockState(pos);
                if (dy < 0 ? !(here.isAir() || here.is(Blocks.WATER)) : !here.isAir()) continue;
                BlockState s = t > 0.7 ? c.tip().getState(random, pos) : c.body().getState(random, pos);
                level.setBlock(pos, s, 2);
            }
        }
    }
}
