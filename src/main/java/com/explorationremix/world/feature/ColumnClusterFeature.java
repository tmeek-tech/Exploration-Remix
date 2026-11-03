package com.explorationremix.world.feature;

import com.explorationremix.block.GlowBlock;
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
 * some hanging from it (a quarter by default; hang_chance 1 hangs every column from the ceiling), like the column
 * walls of the Basalt Galleries. With a {@code clearance}, a hanging column stops that many open blocks over whatever is
 * under it ({@link ColumnFit#hangingLength}), so in a low tunnel it is a short stub and never closes the way.
 */
public final class ColumnClusterFeature extends Feature<ColumnClusterFeature.Config> {
    public record Config(BlockStateProvider block, int radius, int count, IntProvider height, float hangChance, int clearance)
            implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("block").forGetter(Config::block),
                Codec.intRange(1, 14).fieldOf("radius").forGetter(Config::radius),
                Codec.intRange(1, 200).fieldOf("count").forGetter(Config::count),
                IntProvider.codec(1, 64).fieldOf("height").forGetter(Config::height),
                Codec.floatRange(0, 1).optionalFieldOf("hang_chance", 0.25F).forGetter(Config::hangChance),
                Codec.intRange(0, 8).optionalFieldOf("clearance", 0).forGetter(Config::clearance)
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
            boolean hang = random.nextFloat() < c.hangChance();
            pos.set(o.getX() + dx, o.getY(), o.getZ() + dz);
            // Find the floor (or the ceiling for a hanging column) within a few blocks.
            int step = hang ? 1 : -1, tries = 0;
            if (!GlowBlock.open(level.getBlockState(pos))) continue;
            while (tries < 12 && GlowBlock.open(level.getBlockState(pos))) { pos.move(0, step, 0); tries++; }
            if (tries >= 12 || GlowBlock.open(level.getBlockState(pos))) continue;
            BlockState anchor = level.getBlockState(pos);
            if (!anchor.isSolid()) continue;
            if (hang && c.clearance() > 0) {
                // the open blocks straight under the ceiling block, as far as the column and its clearance could reach
                int open = 0;
                BlockPos.MutableBlockPos q = pos.mutable();
                while (open < h + c.clearance() && GlowBlock.open(level.getBlockState(q.move(0, -1, 0)))) open++;
                h = ColumnFit.hangingLength(h, open, c.clearance());
                if (h < 1) continue;
            }
            if (Walkway.near(pos.getX(), pos.getZ(), pos.getY(), pos.getY() - step * h, 1)) continue;
            for (int k = 0; k < h; k++) {
                pos.move(0, -step, 0);
                if (!GlowBlock.open(level.getBlockState(pos))) break;
                level.setBlock(pos, c.block().getState(random, pos), 2);
                any = true;
            }
        }
        return any;
    }
}
