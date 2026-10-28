package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * A daylight shaft: a ragged, slightly leaning hole from a cave's ceiling up to the open sky, so daylight falls into
 * the cave below. Wide ones open a ravine to the sky. A shaft is only cut through dry land, never under water, through
 * a cave holding water or lava, or where a village or other structure stands.
 */
public final class DaylightShaftFeature extends Feature<DaylightShaftFeature.Config> {
    public record Config(FloatProvider radius, int maxDepth) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                FloatProvider.codec(1.0F, 7.0F).fieldOf("radius").forGetter(Config::radius),
                Codec.intRange(8, 200).optionalFieldOf("max_depth", 90).forGetter(Config::maxDepth)
        ).apply(i, Config::new));
    }

    public DaylightShaftFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos o = ctx.origin();
        if (!level.getBlockState(o).isAir()) return false;
        // The ceiling above the origin.
        int y0 = o.getY();
        while (y0 < o.getY() + 40 && level.getBlockState(new BlockPos(o.getX(), y0 + 1, o.getZ())).isAir()) y0++;
        y0++;
        float r = c.radius().sample(random);
        // Leaning at most 5 blocks over its whole height, so it stays inside the area a feature may change.
        int ri = Mth.ceil(r + 2.5F) + 5;
        // The surface over every column the shaft could touch: dry, and not too far up.
        int top = Integer.MIN_VALUE;
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            if (dx * dx + dz * dz > ri * ri) continue;
            int x = o.getX() + dx, z = o.getZ() + dz;
            int surf = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            if (surf != floor) return false; // water or lava on top
            if (surf - y0 > c.maxDepth() || surf <= y0) return false;
            top = Math.max(top, surf);
        }
        // No structure in the way.
        ChunkPos a = new ChunkPos(new BlockPos(o.getX() - ri, 0, o.getZ() - ri)), b = new ChunkPos(new BlockPos(o.getX() + ri, 0, o.getZ() + ri));
        for (int cx = a.x; cx <= b.x; cx++) for (int cz = a.z; cz <= b.z; cz++) {
            ChunkAccess chunk = level.getChunk(cx, cz);
            if (!chunk.getAllReferences().isEmpty() || !chunk.getAllStarts().isEmpty()) return false;
        }
        int height = top + 1 - y0;
        double maxLean = Math.min(0.18, 5.0 / height);
        double leanX = (random.nextDouble() * 2 - 1) * maxLean, leanZ = (random.nextDouble() * 2 - 1) * maxLean;
        double phase = random.nextDouble() * 10;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // First make sure no fluid sits anywhere in the way.
        for (int pass = 0; pass < 2; pass++) {
            for (int dy = -1; dy <= height; dy++) {
                int y = y0 + dy;
                double t = Math.max(0, dy) / (double) Math.max(1, height);
                double cx = o.getX() + 0.5 + leanX * dy, cz = o.getZ() + 0.5 + leanZ * dy;
                // Ragged walls, and a funnel that widens a little towards the top.
                double rad = r * (0.85 + 0.3 * Math.sin(dy * 0.31 + phase)) + 1.2 * t * t;
                int rr = Mth.ceil(rad + 1);
                for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
                    double px = o.getX() + dx + 0.5 - cx, pz = o.getZ() + dz + 0.5 - cz;
                    double wob = 0.6 * Math.sin(dx * 1.3 + dz * 0.7 + dy * 0.45 + phase);
                    if (px * px + pz * pz > (rad + wob) * (rad + wob)) continue;
                    p.set(o.getX() + dx, y, o.getZ() + dz);
                    BlockState s = level.getBlockState(p);
                    if (pass == 0) {
                        if (!s.getFluidState().isEmpty() || s.hasBlockEntity() || s.is(Blocks.BEDROCK)) return false;
                    } else if (!s.isAir()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        return true;
    }
}
