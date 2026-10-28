package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * A cluster of basalt columns around the origin: stepped stacks rising from the floor, some reaching the ceiling, and
 * a few hanging from it, like the column walls of the Basalt Galleries.
 */
public final class ColumnClusterFeature extends Feature<ColumnClusterFeature.Config> {
    public record Config(BlockStateProvider block, int radius, int count, IntProvider height) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("block").forGetter(Config::block),
                Codec.intRange(1, 14).fieldOf("radius").forGetter(Config::radius),
                Codec.intRange(1, 200).fieldOf("count").forGetter(Config::count),
                IntProvider.codec(1, 64).fieldOf("height").forGetter(Config::height)
        ).apply(i, Config::new));
    }

    public ColumnClusterFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos o = ctx.origin();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int i = 0; i < c.count(); i++) {
            int dx = random.nextInt(2 * c.radius() + 1) - c.radius(), dz = random.nextInt(2 * c.radius() + 1) - c.radius();
            if (dx * dx + dz * dz > c.radius() * c.radius()) continue;
            // Taller towards the middle of the cluster.
            double centre = 1 - Math.sqrt(dx * dx + dz * dz) / (c.radius() + 1.0);
            int h = Math.max(1, (int) Math.round(c.height().sample(random) * (0.4 + 0.6 * centre)));
            boolean hang = random.nextFloat() < 0.25F;
            pos.set(o.getX() + dx, o.getY(), o.getZ() + dz);
            // Find the floor (or the ceiling for a hanging column) within a few blocks.
            int step = hang ? 1 : -1, tries = 0;
            if (!level.getBlockState(pos).isAir()) continue;
            while (tries < 12 && level.getBlockState(pos).isAir()) { pos.move(0, step, 0); tries++; }
            if (tries >= 12 || level.getBlockState(pos).isAir()) continue;
            BlockState anchor = level.getBlockState(pos);
            if (!anchor.isSolid()) continue;
            if (Walkway.near(pos.getX(), pos.getZ(), pos.getY(), pos.getY() - step * h, 1)) continue;
            for (int k = 0; k < h; k++) {
                pos.move(0, -step, 0);
                if (!level.getBlockState(pos).isAir()) break;
                level.setBlock(pos, c.block().getState(random, pos), 2);
                any = true;
            }
        }
        return any;
    }
}
