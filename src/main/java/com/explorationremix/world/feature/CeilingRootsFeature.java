package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * Tree roots pushing through a cave ceiling: a gnarled root of logs that comes out of the rock and winds down,
 * sometimes all the way to the floor, with rooted dirt and hanging roots around the spot where it breaks through.
 */
public final class CeilingRootsFeature extends Feature<CeilingRootsFeature.Config> {
    public record Config(BlockState log, BlockState dirt, BlockState hanging, IntProvider length) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("log").forGetter(Config::log),
                BlockState.CODEC.fieldOf("dirt").forGetter(Config::dirt),
                BlockState.CODEC.fieldOf("hanging").forGetter(Config::hanging),
                IntProvider.codec(1, 40).fieldOf("length").forGetter(Config::length)
        ).apply(i, Config::new));
    }

    public CeilingRootsFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos o = ctx.origin();
        if (!level.getBlockState(o).isAir() || !solid(level, o.above())) return false;
        // Rooted dirt and hanging roots where it breaks through.
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx * dx + dz * dz > 5) continue;
            p.set(o.getX() + dx, o.getY() + 1, o.getZ() + dz);
            if (!solid(level, p)) continue;
            BlockState s = level.getBlockState(p);
            if (!s.hasBlockEntity() && random.nextFloat() < 0.7F) level.setBlock(p, c.dirt(), 2);
            p.move(Direction.DOWN);
            if (level.getBlockState(p).isAir() && (dx != 0 || dz != 0) && random.nextFloat() < 0.55F && c.hanging().canSurvive(level, p))
                level.setBlock(p, c.hanging(), 2);
        }
        // The root itself, starting two blocks up inside the rock.
        for (int k = 2; k >= 1; k--) {
            p.set(o.getX(), o.getY() + k, o.getZ());
            if (solid(level, p) && !level.getBlockState(p).hasBlockEntity()) level.setBlock(p, axis(c.log(), Direction.Axis.Y), 2);
        }
        int len = c.length().sample(random);
        p.set(o);
        boolean thick = random.nextFloat() < 0.35F;
        Direction thickDir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        for (int i = 0; i < len; i++) {
            // A root stops short of the way down rather than hanging across it.
            if (Walkway.near(p.getX(), p.getZ(), p.getY(), p.getY(), 1.5)) break;
            BlockState here = level.getBlockState(p);
            if (!here.isAir() && !here.canBeReplaced()) {
                // Reached the floor: the root goes into it.
                if (solid(level, p) && !here.hasBlockEntity()) level.setBlock(p, axis(c.log(), Direction.Axis.Y), 2);
                break;
            }
            level.setBlock(p, axis(c.log(), Direction.Axis.Y), 2);
            if (thick && i < len / 2) {
                BlockPos side = p.relative(thickDir);
                if (level.getBlockState(side).isAir()) level.setBlock(side, axis(c.log(), Direction.Axis.Y), 2);
            }
            // A kink to one side now and then, laid along its direction.
            if (i > 0 && random.nextFloat() < 0.22F) {
                Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(random);
                BlockPos next = p.relative(d);
                if (level.getBlockState(next).isAir() && Math.abs(next.getX() - o.getX()) <= 4 && Math.abs(next.getZ() - o.getZ()) <= 4) {
                    level.setBlock(next, axis(c.log(), d.getAxis()), 2);
                    p.set(next);
                }
            }
            p.move(Direction.DOWN);
        }
        return true;
    }

    private static boolean solid(WorldGenLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isSolidRender(level, pos) && s.getFluidState().isEmpty();
    }

    private static BlockState axis(BlockState s, Direction.Axis a) {
        return s.hasProperty(BlockStateProperties.AXIS) ? s.setValue(BlockStateProperties.AXIS, a) : s;
    }
}
