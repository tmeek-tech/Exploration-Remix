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
 * Sticks blocks onto the faces of rock around the origin: crystals on any face, shelf fungi on walls. A block with a
 * {@code facing} property points away from the face it grows on, and is waterlogged when it grows in water.
 */
public final class SurfaceAttachFeature extends Feature<SurfaceAttachFeature.Config> {
    public record Config(BlockStateProvider state, String faces, int tries, int radius, int height, HolderSet<Block> on,
                         boolean inWater) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("state").forGetter(Config::state),
                Codec.STRING.optionalFieldOf("faces", "any").forGetter(Config::faces),
                Codec.intRange(1, 512).fieldOf("tries").forGetter(Config::tries),
                Codec.intRange(0, 15).optionalFieldOf("radius", 6).forGetter(Config::radius),
                Codec.intRange(0, 32).optionalFieldOf("height", 6).forGetter(Config::height),
                RegistryCodecs.homogeneousList(Registries.BLOCK).fieldOf("on").forGetter(Config::on),
                Codec.BOOL.optionalFieldOf("in_water", false).forGetter(Config::inWater)
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
        for (int i = 0; i < c.tries(); i++) {
            pos.setWithOffset(origin, random.nextInt(2 * c.radius() + 1) - c.radius(), random.nextInt(2 * c.height() + 1) - c.height(),
                    random.nextInt(2 * c.radius() + 1) - c.radius());
            BlockState here = level.getBlockState(pos);
            boolean water = here.is(net.minecraft.world.level.block.Blocks.WATER) && here.getFluidState().isSource();
            if (!here.isAir() && !(water && c.inWater())) continue;
            supports.clear();
            for (Direction d : Direction.values()) {
                if (!allowed(c.faces(), d)) continue;
                BlockPos sp = pos.relative(d);
                BlockState s = level.getBlockState(sp);
                if (s.is(c.on()) && s.isFaceSturdy(level, sp, d.getOpposite())) supports.add(d);
            }
            if (supports.isEmpty()) continue;
            Direction support = supports.get(random.nextInt(supports.size()));
            BlockState place = c.state().getState(random, pos);
            if (place.hasProperty(BlockStateProperties.FACING)) place = place.setValue(BlockStateProperties.FACING, support.getOpposite());
            else if (place.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                if (support.getAxis().isVertical()) continue;
                place = place.setValue(BlockStateProperties.HORIZONTAL_FACING, support.getOpposite());
            }
            // Waterlogged when it grows in water; seagrass and the like are water themselves and grow only there.
            if (place.hasProperty(BlockStateProperties.WATERLOGGED)) place = place.setValue(BlockStateProperties.WATERLOGGED, water);
            else if (water != place.getFluidState().is(FluidTags.WATER)) continue;
            if (!place.canSurvive(level, pos)) continue;
            level.setBlock(pos, place, 2);
            if (water) level.scheduleTick(pos, Fluids.WATER, 0);
            any = true;
        }
        return any;
    }

    /** "any", "walls", "floor" (resting on the block below), "ceiling" (hanging from the block above), or "floor_walls". */
    private static boolean allowed(String faces, Direction d) {
        return switch (faces) {
            case "walls" -> d.getAxis().isHorizontal();
            case "floor" -> d == Direction.DOWN;
            case "ceiling" -> d == Direction.UP;
            case "floor_walls" -> d != Direction.UP;
            default -> true;
        };
    }
}
