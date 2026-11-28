package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Hashing;
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
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * A patch of ground cover laid over the floors round its origin, like vanilla's vegetation_patch, but shaped and split by
 * noise ({@link SplitPatch}): an ellipse of its own size whose outline a fine field pushes in and out, its top blocks
 * taking {@code cover} or, where a second field over the world's x and z lies in its lowest {@code bare_share},
 * {@code bare}. Plants ({@code vegetation}) grow only on the cover. Each column finds its floor as vanilla's patch does:
 * down through open air from the origin's height, then up out of anything solid, within {@code vertical_range}, so a
 * patch drapes over steps and leaves water, plants and overhangs alone.
 *
 * <p>A patch takes only its two half widths from the chunk's random, drawn as vanilla's patch drew its square's sides;
 * everything else it picks (which blocks grow plants, and any random cover) comes from a random of its own, seeded by the
 * world and its middle. So the tries after it in a chunk land in the same places whatever its covers, split or plants
 * are, and changing those never moves a patch.
 *
 * <p>The Ivory Root Caverns' moss (0.24.6, Taran, 2026-11-28: "replace about half of the moss in Ivory Root Caverns with
 * limestone"; cards: moss islands on limestone, anywhere in the halls, Create's limestone, carpets only on the moss): the
 * patches are placed as vanilla's were, as many and as big (their outlines were squares with ragged edges), and half of
 * each is limestone in rounded pieces a few blocks across.
 */
public final class SplitPatchFeature extends Feature<SplitPatchFeature.Config> {
    public record Config(HolderSet<Block> replaceable, BlockStateProvider cover, BlockStateProvider bare, BlockStateProvider vegetation,
                         float vegetationChance, IntProvider radius, int verticalRange, float bareShare, float splitScale)
            implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                RegistryCodecs.homogeneousList(Registries.BLOCK).fieldOf("replaceable").forGetter(Config::replaceable),
                BlockStateProvider.CODEC.fieldOf("cover").forGetter(Config::cover),
                BlockStateProvider.CODEC.fieldOf("bare").forGetter(Config::bare),
                BlockStateProvider.CODEC.fieldOf("vegetation").forGetter(Config::vegetation),
                Codec.floatRange(0, 1).optionalFieldOf("vegetation_chance", 0F).forGetter(Config::vegetationChance),
                IntProvider.codec(1, 12).fieldOf("radius").forGetter(Config::radius),
                Codec.intRange(1, 16).optionalFieldOf("vertical_range", 4).forGetter(Config::verticalRange),
                Codec.floatRange(0, 1).optionalFieldOf("bare_share", 0.5F).forGetter(Config::bareShare),
                Codec.floatRange(0.5F, 64).optionalFieldOf("split_scale", 9F).forGetter(Config::splitScale)
        ).apply(i, Config::new));
    }

    public SplitPatchFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        SplitPatch shape = SplitPatch.of(level.getSeed(), c.bareShare(), c.splitScale());
        // half widths as vanilla's patch drew them (its square's half side, plus the half block of its edge row): the only
        // draws from the chunk's random
        double ax = c.radius().sample(ctx.random()) + 0.5, az = c.radius().sample(ctx.random()) + 0.5;
        RandomSource random = RandomSource.create(Hashing.mix(level.getSeed(), origin.getX(), origin.getY(), origin.getZ()));
        int reach = SplitPatch.reach(Math.max(ax, az));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        int laid = 0;
        for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
            int x = origin.getX() + dx, z = origin.getZ() + dz;
            if (!shape.inside(x, z, dx, dz, ax, az)) continue;
            // the floor under the column: down through the air, then up out of the rock
            pos.set(x, origin.getY(), z);
            for (int k = 0; k < c.verticalRange() && level.isStateAtPosition(pos, BlockState::isAir); k++) pos.move(Direction.DOWN);
            for (int k = 0; k < c.verticalRange() && level.isStateAtPosition(pos, s -> !s.isAir()); k++) pos.move(Direction.UP);
            if (!level.isEmptyBlock(pos)) continue;
            below.setWithOffset(pos, Direction.DOWN);
            BlockState ground = level.getBlockState(below);
            if (!ground.is(c.replaceable()) || !ground.isFaceSturdy(level, below, Direction.UP)) continue;
            boolean bare = shape.bare(x, z);
            BlockState put = (bare ? c.bare() : c.cover()).getState(random, below);
            if (!ground.is(put.getBlock())) level.setBlock(below, put, 2);
            laid++;
            if (!bare && c.vegetationChance() > 0 && random.nextFloat() < c.vegetationChance()) {
                BlockState plant = c.vegetation().getState(random, pos);
                if (plant.canSurvive(level, pos)) level.setBlock(pos, plant, 2);
            }
        }
        return laid > 0;
    }
}
