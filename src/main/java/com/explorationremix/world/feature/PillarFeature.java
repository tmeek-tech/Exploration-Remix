package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

import java.util.HashSet;
import java.util.Set;

/**
 * A column from the ceiling at the origin down to the floor, wide where it meets the rock and pinched in the middle,
 * like the great joined columns of a dripstone cave (the Frost Fern Grotto's are packed and blue ice). Only where the
 * floor lies between {@code min_room} and {@code max_room} open blocks under the ceiling and is dry rock, so a column
 * always joins both and never stands in a lake, and (with {@code spacing}) only where no other column stands within
 * that many blocks, so a big room that catches many tries gets columns spread through it rather than a wall of them.
 */
public final class PillarFeature extends Feature<PillarFeature.Config> {
    public record Config(BlockStateProvider body, FloatProvider radius, int minRoom, int maxRoom, int spacing) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("body").forGetter(Config::body),
                FloatProvider.codec(1.0F, 8.0F).fieldOf("radius").forGetter(Config::radius),
                Codec.intRange(3, 64).optionalFieldOf("min_room", 6).forGetter(Config::minRoom),
                Codec.intRange(3, 64).optionalFieldOf("max_room", 28).forGetter(Config::maxRoom),
                // at most 14: the look round a column stays inside the chunks a feature may read
                Codec.intRange(0, 14).optionalFieldOf("spacing", 0).forGetter(Config::spacing)
        ).apply(i, Config::new));
    }

    public PillarFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos ceiling = ctx.origin();
        if (level.getBlockState(ceiling).isAir() || !level.getBlockState(ceiling.below()).isAir()) return false;
        int room = 0;
        while (room <= c.maxRoom() && level.getBlockState(ceiling.below(room + 1)).isAir()) room++;
        if (room < c.minRoom() || room > c.maxRoom()) return false;
        BlockPos floor = ceiling.below(room + 1);
        BlockState floorState = level.getBlockState(floor);
        if (!floorState.isSolid() || !floorState.getFluidState().isEmpty()) return false;
        if (c.spacing() > 0 && columnNear(level, ceiling, room, c)) return false;
        float wide = c.radius().sample(random);
        // pinched to about a third in the middle, never thinner than a block
        float waist = Math.max(0.9F, wide * 0.36F);
        double leanX = (random.nextDouble() - 0.5) * 0.12, leanZ = (random.nextDouble() - 0.5) * 0.12;
        if (Walkway.near(ceiling.getX(), ceiling.getZ(), floor.getY(), ceiling.getY(), wide + 1)) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int ri = (int) Math.ceil(wide) + 2;
        // from two blocks into the ceiling to two into the floor, so it grows out of the rock at both ends
        for (int k = -2; k <= room + 3; k++) {
            double t = Mth.clamp((k - 0.5) / room, 0, 1);
            double rad = waist + (wide - waist) * Math.pow(Math.abs(2 * t - 1), 1.6);
            if (k < 1 || k > room) rad += 0.8;
            int y = ceiling.getY() - k;
            double cx = ceiling.getX() + 0.5 + leanX * Math.min(k, room), cz = ceiling.getZ() + 0.5 + leanZ * Math.min(k, room);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double px = ceiling.getX() + dx + 0.5 - cx, pz = ceiling.getZ() + dz + 0.5 - cz;
                double wob = 0.3 * Mth.sin((float) (k * 0.8 + dx * 1.9 + dz * 2.7));
                if (px * px + pz * pz > (rad + wob) * (rad + wob)) continue;
                pos.set(ceiling.getX() + dx, y, ceiling.getZ() + dz);
                if (!level.getBlockState(pos).isAir()) continue;
                level.setBlock(pos, c.body().getState(random, pos), 2);
            }
        }
        return true;
    }

    /**
     * Whether another column (a block the body gives) stands within {@code spacing} of this one's axis, looked for at
     * every third height of its room.
     */
    private static boolean columnNear(WorldGenLevel level, BlockPos ceiling, int room, Config c) {
        Set<Block> kinds = kinds(c.body(), ceiling);
        int r = c.spacing();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int k = 1; k <= room; k += 3) {
            int y = ceiling.getY() - k;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                if (kinds.contains(level.getBlockState(pos.set(ceiling.getX() + dx, y, ceiling.getZ() + dz)).getBlock())) return true;
            }
        }
        return false;
    }

    /** The blocks the body gives, from a fixed sample of it (a mix of packed and blue ice gives both). */
    private static Set<Block> kinds(BlockStateProvider body, BlockPos pos) {
        RandomSource r = RandomSource.create(0);
        Set<Block> s = new HashSet<>();
        for (int i = 0; i < 32; i++) s.add(body.getState(r, pos).getBlock());
        return s;
    }
}
