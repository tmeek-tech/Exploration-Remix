package com.explorationremix.world.feature;

import com.explorationremix.world.gen.SurfaceOracle;
import com.explorationremix.world.terrain.RootKind;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Tree roots pushing through a cave ceiling: a gnarled root that comes out of the rock and winds down, sometimes all
 * the way to the floor and into it, with rooted dirt and hanging roots around the spot where it breaks through and side
 * roots running along the ceiling.
 *
 * <p>With {@code match_trees} the roots are those of the trees above: the wood of the nearest trunk standing on the
 * ground over the spot (within 5 blocks), else of the trees the surface biome grows, else the configured blocks.
 * Mangroves send down mangrove roots out of muddy roots; jungle and dark oak roots are thick and branch more; spruce
 * and birch roots are thin and straighter. {@code column_chance} is the share of roots that reach the floor, and
 * {@code branches} the number of side roots.
 */
public final class CeilingRootsFeature extends Feature<CeilingRootsFeature.Config> {
    public record Config(BlockState log, BlockState dirt, BlockState hanging, IntProvider length, boolean matchTrees,
                         float columnChance, IntProvider branches) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("log").forGetter(Config::log),
                BlockState.CODEC.fieldOf("dirt").forGetter(Config::dirt),
                BlockState.CODEC.fieldOf("hanging").forGetter(Config::hanging),
                IntProvider.codec(1, 40).fieldOf("length").forGetter(Config::length),
                Codec.BOOL.optionalFieldOf("match_trees", false).forGetter(Config::matchTrees),
                Codec.floatRange(0.0F, 1.0F).optionalFieldOf("column_chance", 0.0F).forGetter(Config::columnChance),
                IntProvider.codec(0, 6).optionalFieldOf("branches", ConstantInt.ZERO).forGetter(Config::branches)
        ).apply(i, Config::new));
    }

    /**
     * What one root is made of: {@code log} for the strands, {@code dirt} where it breaks through the ceiling.
     * {@code thick} roots are two blocks wide at the top and branch more, {@code thin} ones kink less, and a
     * {@code tangle} (mangroves) sends down several thin strands around the main one.
     */
    record Wood(BlockState log, BlockState dirt, BlockState hanging, boolean thick, boolean thin, boolean tangle) {}

    /** Column offsets within 5 blocks, nearest first, for finding the nearest trunk. */
    private static final int[][] NEAR = nearOffsets(5);

    public CeilingRootsFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos o = ctx.origin();
        // The open block under the ceiling; a plant hanging there (glow lichen, hanging roots) gives way to the root.
        if (!open(level, o) || !solid(level, o.above())) return false;
        Wood w = c.matchTrees() ? woodAbove(level, o, c, random)
                : new Wood(c.log(), c.dirt(), c.hanging(), random.nextFloat() < 0.35F, false, false);
        float column = c.columnChance() * (w.thick() ? 1.5F : 1.0F);
        int branches = c.branches().sample(random) + (w.thick() && c.matchTrees() ? 1 : 0);
        grow(level, random, o, w, c.length().sample(random), column, branches);
        return true;
    }

    // ------------------------------------------------------------------------------------------- the trees above

    /** The wood of the trees over a spot (see the class comment). */
    static Wood woodAbove(WorldGenLevel level, BlockPos o, Config c, RandomSource random) {
        BlockState log = trunkNear(level, o.getX(), o.getZ());
        if (log == null) {
            int x = o.getX(), z = o.getZ();
            int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            RootKind kind = SurfaceOracle.kindOf(level.getBiome(new BlockPos(x, ground, z)));
            if (kind.trees()) log = logOf(kind);
        }
        if (log == null) return new Wood(c.log(), c.dirt(), c.hanging(), random.nextFloat() < 0.35F, false, false);
        return wood(log, c);
    }

    /** The log of the nearest trunk standing on the ground within 5 blocks of a column, or null. */
    @Nullable
    static BlockState trunkNear(WorldGenLevel level, int x, int z) {
        for (int[] d : NEAR) {
            BlockState s = trunk(level, x + d[0], z + d[1]);
            if (s != null) return s;
        }
        return null;
    }

    /**
     * The log of a tree whose trunk stands on the ground in this column, or null. The ground is the pack's terrain as
     * it was before any feature (the ocean-floor heightmap of world generation, which features no longer move), so the
     * trunk is the block just above it; a mangrove stands on its roots a few blocks higher.
     */
    @Nullable
    static BlockState trunk(WorldGenLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        if (s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MANGROVE_LOG)) return Blocks.MANGROVE_LOG.defaultBlockState();
        if (!s.is(BlockTags.LOGS)) return null;
        // A log lying on the ground (a fallen tree) is no trunk.
        if (s.hasProperty(BlockStateProperties.AXIS) && s.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) return null;
        BlockState up = level.getBlockState(p.move(Direction.UP));
        return up.is(BlockTags.LOGS) || up.is(BlockTags.LEAVES) ? s : null;
    }

    /** The log of a kind of tree. */
    static BlockState logOf(RootKind kind) {
        return switch (kind) {
            case BIRCH -> Blocks.BIRCH_LOG.defaultBlockState();
            case SPRUCE -> Blocks.SPRUCE_LOG.defaultBlockState();
            case JUNGLE -> Blocks.JUNGLE_LOG.defaultBlockState();
            case DARK_OAK -> Blocks.DARK_OAK_LOG.defaultBlockState();
            case MANGROVE -> Blocks.MANGROVE_LOG.defaultBlockState();
            case CHERRY -> Blocks.CHERRY_LOG.defaultBlockState();
            default -> Blocks.OAK_LOG.defaultBlockState();
        };
    }

    /** How the roots of a tree with this log grow. */
    static Wood wood(BlockState log, Config c) {
        if (log.is(Blocks.MANGROVE_LOG) || log.is(Blocks.MANGROVE_ROOTS))
            return new Wood(Blocks.MANGROVE_ROOTS.defaultBlockState(), Blocks.MUDDY_MANGROVE_ROOTS.defaultBlockState(), c.hanging(), false, true, true);
        boolean thick = log.is(Blocks.JUNGLE_LOG) || log.is(Blocks.DARK_OAK_LOG);
        boolean thin = log.is(Blocks.SPRUCE_LOG) || log.is(Blocks.BIRCH_LOG);
        return new Wood(axis(log, Direction.Axis.Y), c.dirt(), c.hanging(), thick, thin, false);
    }

    // ------------------------------------------------------------------------------------------- growing

    /** Grows one root from the open block under a ceiling. */
    static void grow(WorldGenLevel level, RandomSource random, BlockPos o, Wood w, int len, float columnChance, int branches) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // Rooted dirt where it breaks through, hanging roots around it.
        int pr = w.thick() ? 3 : 2, pr2 = w.thick() ? 8 : 5;
        for (int dx = -pr; dx <= pr; dx++) for (int dz = -pr; dz <= pr; dz++) {
            if (dx * dx + dz * dz > pr2) continue;
            p.set(o.getX() + dx, o.getY() + 1, o.getZ() + dz);
            if (!solid(level, p)) continue;
            if (!level.getBlockState(p).hasBlockEntity() && random.nextFloat() < 0.7F) level.setBlock(p, w.dirt(), 2);
            p.move(Direction.DOWN);
            if (level.getBlockState(p).isAir() && (dx != 0 || dz != 0) && random.nextFloat() < 0.5F && w.hanging().canSurvive(level, p))
                level.setBlock(p, w.hanging(), 2);
        }
        // The root itself comes out of the rock, two blocks up inside it.
        BlockState into = w.tangle() ? w.dirt() : axis(w.log(), Direction.Axis.Y);
        for (int k = 2; k >= 1; k--) {
            p.set(o.getX(), o.getY() + k, o.getZ());
            if (solid(level, p) && !level.getBlockState(p).hasBlockEntity()) level.setBlock(p, into, 2);
        }
        // Some reach the floor: then the strand runs all the way down and into it.
        boolean toFloor = false;
        if (random.nextFloat() < columnChance) {
            int drop = 0;
            p.set(o);
            while (drop < 30 && open(level, p)) {
                p.move(Direction.DOWN);
                drop++;
            }
            if (drop < 30 && solid(level, p)) {
                len = drop + 1;
                toFloor = true;
            }
        }
        strand(level, random, o, w, len, toFloor, w.thick());
        // Side roots along the ceiling, each hanging down at its end.
        for (int b = 0; b < branches; b++) sideRoot(level, random, o, w);
        // A mangrove sends down a tangle of thinner roots around the main one.
        if (w.tangle()) {
            int n = 2 + random.nextInt(3);
            for (int k = 0; k < n; k++) {
                int dx = random.nextInt(5) - 2, dz = random.nextInt(5) - 2;
                if (dx == 0 && dz == 0) continue;
                BlockPos s = ceilingNear(level, o.getX() + dx, o.getY(), o.getZ() + dz);
                if (s != null) strand(level, random, s, w, 2 + random.nextInt(5), false, false);
            }
        }
    }

    /**
     * A strand winding down from {@code start}. A thick one is two blocks wide over its upper half. It stops at the
     * floor (and goes into it), short of the way down, or after {@code len} blocks.
     */
    private static void strand(WorldGenLevel level, RandomSource random, BlockPos start, Wood w, int len, boolean toFloor, boolean thick) {
        BlockPos.MutableBlockPos p = start.mutable();
        Direction thickDir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        float kink = toFloor ? 0.08F : (w.thin() ? 0.15F : 0.22F);
        for (int i = 0; i < len + (toFloor ? 2 : 0); i++) {
            // A root stops short of the way down rather than hanging across it.
            if (Walkway.near(p.getX(), p.getZ(), p.getY(), p.getY(), 1.5)) break;
            BlockState here = level.getBlockState(p);
            if (!here.isAir() && !here.canBeReplaced()) {
                // Reached the floor: the root goes into it.
                if (solid(level, p) && !here.hasBlockEntity()) level.setBlock(p, axis(w.log(), Direction.Axis.Y), 2);
                break;
            }
            level.setBlock(p, axis(w.log(), Direction.Axis.Y), 2);
            if (thick && i < len / 2) {
                BlockPos side = p.relative(thickDir);
                if (level.getBlockState(side).isAir()) level.setBlock(side, axis(w.log(), Direction.Axis.Y), 2);
            }
            // A kink to one side now and then, laid along its direction.
            if (i > 0 && random.nextFloat() < kink) {
                Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(random);
                BlockPos next = p.relative(d);
                if (level.getBlockState(next).isAir() && Math.abs(next.getX() - start.getX()) <= 4 && Math.abs(next.getZ() - start.getZ()) <= 4) {
                    level.setBlock(next, axis(w.log(), d.getAxis()), 2);
                    p.set(next);
                }
            }
            p.move(Direction.DOWN);
        }
    }

    /** A side root: along the ceiling for a few blocks, following it up or down, then hanging down a little. */
    private static void sideRoot(WorldGenLevel level, RandomSource random, BlockPos o, Wood w) {
        Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        Direction drift = random.nextBoolean() ? d.getClockWise() : d.getCounterClockWise();
        int run = 2 + random.nextInt(w.thick() ? 5 : 4);
        BlockPos.MutableBlockPos p = o.mutable();
        BlockState along = axis(w.log(), d.getAxis());
        for (int i = 0; i < run; i++) {
            p.move(d);
            if (random.nextFloat() < 0.25F) p.move(drift);
            // Keep to the underside of the ceiling: up into a hollow, down where the ceiling comes lower.
            if (open(level, p) && open(level, p.above())) {
                p.move(Direction.UP);
                if (!solid(level, p.above())) return;
            } else if (!open(level, p)) {
                p.move(Direction.DOWN);
                if (!open(level, p) || !solid(level, p.above())) return;
            }
            if (Walkway.near(p.getX(), p.getZ(), p.getY(), p.getY(), 1.5)) return;
            level.setBlock(p, w.tangle() ? w.log() : along, 2);
        }
        int hang = 1 + random.nextInt(4);
        for (int i = 0; i < hang; i++) {
            p.move(Direction.DOWN);
            if (!open(level, p) || Walkway.near(p.getX(), p.getZ(), p.getY(), p.getY(), 1.5)) return;
            level.setBlock(p, axis(w.log(), Direction.Axis.Y), 2);
        }
        if (level.getBlockState(p.move(Direction.DOWN)).isAir() && w.hanging().canSurvive(level, p)) level.setBlock(p, w.hanging(), 2);
    }

    /** Air, or a plant or other dry block a root may grow through (the hanging roots it just put up, glow lichen). */
    private static boolean open(WorldGenLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty() && !s.hasBlockEntity());
    }

    /** The open block under the ceiling near (x, y, z), within 2 blocks up or down, or null. */
    @Nullable
    private static BlockPos ceilingNear(WorldGenLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -2; dy <= 2; dy++) {
            p.set(x, y + dy, z);
            if (level.getBlockState(p).isAir() && solid(level, p.above())) return p.immutable();
        }
        return null;
    }

    private static boolean solid(WorldGenLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isSolidRender(level, pos) && s.getFluidState().isEmpty();
    }

    private static BlockState axis(BlockState s, Direction.Axis a) {
        return s.hasProperty(BlockStateProperties.AXIS) ? s.setValue(BlockStateProperties.AXIS, a) : s;
    }

    private static int[][] nearOffsets(int r) {
        int[][] out = new int[(2 * r + 1) * (2 * r + 1)][];
        int n = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz <= r * r) out[n++] = new int[]{dx, dz};
        int[][] a = Arrays.copyOf(out, n);
        Arrays.sort(a, Comparator.comparingInt(d -> d[0] * d[0] + d[1] * d[1]));
        return a;
    }
}
