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

/**
 * A giant lantern fungus on a ledge: a pale stem, a flat cap whose underside glows, and glowing roots trailing from
 * the cap's rim. Only grows where the whole fungus fits in open air, and never over the way down.
 */
public final class GiantFungusFeature extends Feature<GiantFungusFeature.Config> {
    public record Config(BlockState stem, BlockState cap, BlockState glow, BlockState roots, IntProvider height, IntProvider radius,
                         IntProvider rootLength) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("stem").forGetter(Config::stem),
                BlockState.CODEC.fieldOf("cap").forGetter(Config::cap),
                BlockState.CODEC.fieldOf("glow").forGetter(Config::glow),
                BlockState.CODEC.fieldOf("roots").forGetter(Config::roots),
                IntProvider.codec(1, 12).fieldOf("height").forGetter(Config::height),
                IntProvider.codec(1, 5).fieldOf("radius").forGetter(Config::radius),
                IntProvider.codec(0, 8).fieldOf("root_length").forGetter(Config::rootLength)
        ).apply(i, Config::new));
    }

    public GiantFungusFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos o = ctx.origin();
        if (!level.getBlockState(o).isAir() || !level.getBlockState(o.below()).isFaceSturdy(level, o.below(), net.minecraft.core.Direction.UP)) return false;
        int h = c.height().sample(random), r = c.radius().sample(random);
        if (Walkway.near(o.getX(), o.getZ(), o.getY(), o.getY() + h + 2, r + 1)) return false;
        // Everything the fungus fills has to be open: the stem, and the two layers of the cap.
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = 0; y <= h + 1; y++) if (!level.getBlockState(p.setWithOffset(o, 0, y, 0)).isAir()) return false;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r + 1) continue;
            for (int y = h - 1; y <= h + 1; y++) if (!level.getBlockState(p.setWithOffset(o, dx, y, dz)).isAir()) return false;
        }
        for (int y = 0; y < h; y++) level.setBlock(p.setWithOffset(o, 0, y, 0), c.stem(), 2);
        // The cap: a glowing underside ringed by the cap's rim, and a slightly smaller top above it.
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int d2 = dx * dx + dz * dz;
            if (d2 > r * r + 1) continue;
            boolean rim = d2 > (r - 1) * (r - 1) + 1;
            level.setBlock(p.setWithOffset(o, dx, h, dz), rim || (dx == 0 && dz == 0) ? c.cap() : c.glow(), 2);
            if (d2 <= (r - 1) * (r - 1) + 1 || (r <= 2 && !rim)) level.setBlock(p.setWithOffset(o, dx, h + 1, dz), c.cap(), 2);
        }
        // Glowing roots trailing from under the rim.
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int d2 = dx * dx + dz * dz;
            if (d2 > r * r + 1 || d2 <= (r - 1) * (r - 1) + 1 || random.nextFloat() > 0.5F) continue;
            int len = c.rootLength().sample(random);
            for (int k = 1; k <= len; k++) {
                p.setWithOffset(o, dx, h - k, dz);
                if (!level.getBlockState(p).isAir() || !c.roots().canSurvive(level, p)) break;
                level.setBlock(p, c.roots(), 2);
            }
        }
        return true;
    }
}
