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
 * the cave below. Wide, stretched ones open a ravine to the sky. A shaft is only cut through dry land, never under
 * water, through a cave holding water or lava, or where a village or other structure stands.
 */
public final class DaylightShaftFeature extends Feature<DaylightShaftFeature.Config> {
    /** {@code stretch} lengthens the shaft along a random direction into a slot; {@code max_lean} is how far it may lean. */
    public record Config(FloatProvider radius, int maxDepth, float stretch, int maxLean) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                FloatProvider.codec(1.0F, 7.0F).fieldOf("radius").forGetter(Config::radius),
                Codec.intRange(8, 200).optionalFieldOf("max_depth", 90).forGetter(Config::maxDepth),
                Codec.floatRange(1.0F, 3.0F).optionalFieldOf("stretch", 1.0F).forGetter(Config::stretch),
                Codec.intRange(0, 5).optionalFieldOf("max_lean", 5).forGetter(Config::maxLean)
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
        // The widest the shaft gets (ragged edge and funnel included), stretched into a slot, plus its lean: at most 15
        // blocks out from the origin, inside the area a feature may change.
        double wide = 1.15 * r + 1.8;
        double long_ = Math.min(c.stretch(), (15.0 - c.maxLean()) / wide);
        int ri = Mth.ceil(wide * long_) + c.maxLean();
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
        double maxLean = Math.min(0.18, c.maxLean() / (double) height);
        double leanX = (random.nextDouble() * 2 - 1) * maxLean, leanZ = (random.nextDouble() * 2 - 1) * maxLean;
        double phase = random.nextDouble() * 10;
        double angle = random.nextDouble() * Math.PI, ca = Math.cos(angle), sa = Math.sin(angle);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // First make sure no fluid sits anywhere in the way.
        for (int pass = 0; pass < 2; pass++) {
            for (int dy = -1; dy <= height; dy++) {
                int y = y0 + dy;
                double t = Math.max(0, dy) / (double) Math.max(1, height);
                double cx = o.getX() + 0.5 + leanX * dy, cz = o.getZ() + 0.5 + leanZ * dy;
                // Ragged walls, and a funnel that widens a little towards the top.
                double rad = r * (0.85 + 0.3 * Math.sin(dy * 0.31 + phase)) + 1.2 * t * t;
                int rr = Mth.ceil(rad * long_ + 1 + Math.abs(leanX * dy) + Math.abs(leanZ * dy));
                for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
                    double px = o.getX() + dx + 0.5 - cx, pz = o.getZ() + dz + 0.5 - cz;
                    // Along the slot and across it: a stretched shaft is an ellipse long_ times as long as it is wide.
                    double u = (px * ca + pz * sa) / long_, v = -px * sa + pz * ca;
                    double wob = 0.6 * Math.sin(dx * 1.3 + dz * 0.7 + dy * 0.45 + phase);
                    if (u * u + v * v > (rad + wob) * (rad + wob)) continue;
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
