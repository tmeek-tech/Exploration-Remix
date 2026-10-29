package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * Shelves of brown mushroom stacked up a wall (the Ember Gardens picture). Each tier is a half disc one block thick and
 * flat on top, so plants grow on it and it can be stood on; the tiers are spacing + 1 blocks apart and shift a little
 * along the wall. It finds its wall by itself, only fills air, keeps off the way down, and never reaches across a
 * passage: in front of every shelf there are at least two blocks of open air.
 */
public final class MushroomShelfFeature extends Feature<MushroomShelfFeature.Config> {
    public record Config(BlockStateProvider block, IntProvider tiers, IntProvider spacing, IntProvider depth, IntProvider halfWidth)
            implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("block").forGetter(Config::block),
                IntProvider.codec(1, 6).fieldOf("tiers").forGetter(Config::tiers),
                IntProvider.codec(1, 6).fieldOf("spacing").forGetter(Config::spacing),
                IntProvider.codec(1, 6).fieldOf("depth").forGetter(Config::depth),
                IntProvider.codec(1, 5).fieldOf("half_width").forGetter(Config::halfWidth)
        ).apply(i, Config::new));
    }

    /** How far from the origin a wall is looked for, along each of the four sides. */
    private static final int SEARCH = 6;
    /** Open air left in front of a shelf, so it never closes a passage. */
    private static final int CLEAR = 2;

    public MushroomShelfFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        if (!level.getBlockState(origin).isAir()) return false;
        // The nearest wall along the four sides.
        Direction toWall = null;
        int dist = SEARCH + 1;
        for (Direction d : Direction.Plane.HORIZONTAL.shuffledCopy(random)) {
            for (int k = 1; k < dist; k++) {
                BlockPos p = origin.relative(d, k);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                if (s.isFaceSturdy(level, p, d.getOpposite())) {
                    toWall = d;
                    dist = k;
                }
                break;
            }
        }
        if (toWall == null) return false;
        Direction out = toWall.getOpposite(), along = toWall.getClockWise();
        BlockPos base = origin.relative(toWall, dist - 1);
        int tiers = c.tiers().sample(random);
        if (Walkway.near(base.getX(), base.getZ(), base.getY() - 1, base.getY() + tiers * 7, 8)) return false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int y = base.getY(), shift = 0, placed = 0;
        for (int t = 0; t < tiers; t++) {
            int depth = c.depth().sample(random), half = c.halfWidth().sample(random);
            for (int a = -half; a <= half; a++) {
                // This column's own wall: step back towards it while there is air, at most three blocks.
                p.set(base.getX(), y, base.getZ()).move(along, a + shift);
                for (int back = 0; back < 3 && level.getBlockState(p.relative(toWall)).isAir(); back++) p.move(toWall);
                BlockPos anchor = p.relative(toWall);
                if (!level.getBlockState(p).isAir() || !level.getBlockState(anchor).isFaceSturdy(level, anchor, out)) continue;
                double w = a / (half + 0.5);
                int reach = (int) Math.round(depth * Math.sqrt(1 - w * w) + random.nextFloat() * 0.6F - 0.3F);
                if (reach <= 0 || !open(level, p, out, reach + CLEAR)) continue;
                for (int o = 0; o < reach; o++) {
                    BlockPos q = p.relative(out, o);
                    level.setBlock(q, c.block().getState(random, q), 2);
                    placed++;
                }
            }
            y += c.spacing().sample(random) + 1;
            shift += random.nextInt(3) - 1;
        }
        return placed > 0;
    }

    /** Whether the n blocks from p outwards are all air. */
    private static boolean open(WorldGenLevel level, BlockPos p, Direction out, int n) {
        for (int o = 0; o < n; o++) if (!level.getBlockState(p.relative(out, o)).isAir()) return false;
        return true;
    }
}
