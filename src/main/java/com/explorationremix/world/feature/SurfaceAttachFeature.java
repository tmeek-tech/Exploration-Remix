package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * Sticks blocks onto the faces of rock around the origin: crystals on any face, shelf fungi on walls, hanging ferns on
 * walls with room below them, sheets of lichen and ivy. A block with a {@code facing} property points away from the
 * face it grows on; a sheet (a MultifaceBlock) covers every allowed face it touches. Either is waterlogged when it grows
 * in water. Vanilla's multiface_growth only looks at the blocks next to its origin; this scatters its tries over a
 * radius by height box. With {@code patch} it instead covers every face in an ellipsoid of that box (a size of its own
 * each time, thinning out at the rim), so frost and the like lie in solid patches rather than splatters.
 */
public final class SurfaceAttachFeature extends Feature<SurfaceAttachFeature.Config> {
    public record Config(BlockStateProvider state, String faces, int tries, int radius, int height, HolderSet<Block> on,
                         boolean inWater, boolean patch) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("state").forGetter(Config::state),
                Codec.STRING.optionalFieldOf("faces", "any").forGetter(Config::faces),
                Codec.intRange(1, 512).fieldOf("tries").forGetter(Config::tries),
                Codec.intRange(0, 15).optionalFieldOf("radius", 6).forGetter(Config::radius),
                Codec.intRange(0, 32).optionalFieldOf("height", 6).forGetter(Config::height),
                RegistryCodecs.homogeneousList(Registries.BLOCK).fieldOf("on").forGetter(Config::on),
                Codec.BOOL.optionalFieldOf("in_water", false).forGetter(Config::inWater),
                Codec.BOOL.optionalFieldOf("patch", false).forGetter(Config::patch)
        ).apply(i, Config::new));
    }

    public SurfaceAttachFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<Direction> supports = new ArrayList<>(6);
        boolean any = false;
        if (c.patch()) {
            // One patch: an ellipsoid of its own size, solid in the middle and ragged at the rim.
            double size = 0.55 + 0.45 * random.nextDouble();
            double rx = Math.max(1.5, c.radius() * size), ry = Math.max(1.5, c.height() * size);
            for (int dx = -c.radius(); dx <= c.radius(); dx++) for (int dy = -c.height(); dy <= c.height(); dy++)
                for (int dz = -c.radius(); dz <= c.radius(); dz++) {
                    double q = (dx * dx + dz * dz) / (rx * rx) + dy * dy / (ry * ry);
                    if (q > 1 || q > 0.45 && random.nextFloat() < (q - 0.45) / 0.55 * 0.85) continue;
                    pos.setWithOffset(origin, dx, dy, dz);
                    any |= attach(level, random, c, pos, supports);
                }
            return any;
        }
        for (int i = 0; i < c.tries(); i++) {
            pos.setWithOffset(origin, random.nextInt(2 * c.radius() + 1) - c.radius(), random.nextInt(2 * c.height() + 1) - c.height(),
                    random.nextInt(2 * c.radius() + 1) - c.radius());
            any |= attach(level, random, c, pos, supports);
        }
        return any;
    }

    /** Puts one block at {@code pos} if it is open and rock of the kind wanted lies beside it on an allowed face. */
    private static boolean attach(WorldGenLevel level, RandomSource random, Config c, BlockPos.MutableBlockPos pos, List<Direction> supports) {
        BlockState here = level.getBlockState(pos);
        boolean water = here.is(net.minecraft.world.level.block.Blocks.WATER) && here.getFluidState().isSource();
        if (!here.isAir() && !(water && c.inWater())) return false;
        // a hanging plant's fronds reach below its block: only where they hang free
        if (c.faces().equals("walls_hanging") && !level.getBlockState(pos.below()).isAir()) return false;
        supports.clear();
        for (Direction d : Direction.values()) {
            if (!allowed(c.faces(), d)) continue;
            BlockPos sp = pos.relative(d);
            BlockState s = level.getBlockState(sp);
            if (s.is(c.on()) && s.isFaceSturdy(level, sp, d.getOpposite())) supports.add(d);
        }
        if (supports.isEmpty()) return false;
        Direction support = supports.get(random.nextInt(supports.size()));
        BlockState place = c.state().getState(random, pos);
        if (place.getBlock() instanceof MultifaceBlock) {
            // a sheet covers every face it found support on
            for (Direction d : supports) place = place.setValue(MultifaceBlock.getFaceProperty(d), true);
        } else if (place.hasProperty(BlockStateProperties.FACING)) place = place.setValue(BlockStateProperties.FACING, support.getOpposite());
        else if (place.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            if (support.getAxis().isVertical()) return false;
            place = place.setValue(BlockStateProperties.HORIZONTAL_FACING, support.getOpposite());
        }
        // Waterlogged when it grows in water; seagrass and the like are water themselves and grow only there.
        if (place.hasProperty(BlockStateProperties.WATERLOGGED)) place = place.setValue(BlockStateProperties.WATERLOGGED, water);
        else if (water != place.getFluidState().is(FluidTags.WATER)) return false;
        if (!place.canSurvive(level, pos)) return false;
        level.setBlock(pos, place, 2);
        if (water) level.scheduleTick(pos, Fluids.WATER, 0);
        return true;
    }

    /**
     * "any", "walls", "walls_hanging" (walls, with air below the spot), "floor" (resting on the block below), "ceiling"
     * (hanging from the block above), "floor_walls", or "walls_ceiling" (climbable sheets, which would slow anyone
     * walking over a floor of them).
     */
    private static boolean allowed(String faces, Direction d) {
        return switch (faces) {
            case "walls", "walls_hanging" -> d.getAxis().isHorizontal();
            case "floor" -> d == Direction.DOWN;
            case "ceiling" -> d == Direction.UP;
            case "floor_walls" -> d != Direction.UP;
            case "walls_ceiling" -> d != Direction.DOWN;
            default -> true;
        };
    }
}
