package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * A patch of small pointed stacks round the origin: blocks of a pointed kind (vanilla's pointed dripstone or anything
 * built like it, such as Immersive Weathering's icicles) standing on the floor ({@code up}) or hanging from the ceiling,
 * each one to {@code height} blocks long and shaped base to tip like vanilla's. Only on sturdy, dry rock, and only into
 * open air, so nothing stands in water or pokes through a wall.
 */
public final class PointedPatchFeature extends Feature<PointedPatchFeature.Config> {
    public record Config(BlockStateProvider state, int tries, int radius, IntProvider height, boolean up) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.CODEC.fieldOf("state").forGetter(Config::state),
                Codec.intRange(1, 256).fieldOf("tries").forGetter(Config::tries),
                Codec.intRange(0, 15).optionalFieldOf("radius", 5).forGetter(Config::radius),
                IntProvider.codec(1, 8).fieldOf("height").forGetter(Config::height),
                Codec.BOOL.optionalFieldOf("up", true).forGetter(Config::up)
        ).apply(i, Config::new));
    }

    /** How far up and down from the origin each try looks for its floor (or ceiling). */
    private static final int REACH = 6;

    public PointedPatchFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        Direction grow = c.up() ? Direction.UP : Direction.DOWN;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int i = 0; i < c.tries(); i++) {
            int dx = random.nextInt(2 * c.radius() + 1) - c.radius(), dz = random.nextInt(2 * c.radius() + 1) - c.radius();
            if (dx * dx + dz * dz > c.radius() * c.radius() + 1) continue;
            // the open block on the floor (or under the ceiling) nearest the origin's height in this column
            pos.set(origin.getX() + dx, origin.getY() + (c.up() ? REACH : -REACH), origin.getZ() + dz);
            boolean found = false;
            for (int k = 0; k <= 2 * REACH; k++) {
                BlockPos support = pos.relative(grow.getOpposite());
                if (level.getBlockState(pos).isAir()) {
                    BlockState s = level.getBlockState(support);
                    if (s.isFaceSturdy(level, support, grow) && s.getFluidState().isEmpty()) { found = true; break; }
                }
                pos.move(grow.getOpposite());
            }
            if (!found) continue;
            // how long this one can be: open air all the way, and one more open block past its tip
            int want = c.height().sample(random), len = 0;
            while (len < want && level.getBlockState(pos.relative(grow, len)).isAir() && level.getBlockState(pos.relative(grow, len + 1)).isAir()) len++;
            if (len == 0) continue;
            BlockState base = c.state().getState(random, pos);
            if (!base.hasProperty(PointedDripstoneBlock.TIP_DIRECTION) || !base.hasProperty(PointedDripstoneBlock.THICKNESS)) return false;
            base = base.setValue(PointedDripstoneBlock.TIP_DIRECTION, grow);
            if (base.hasProperty(BlockStateProperties.WATERLOGGED)) base = base.setValue(BlockStateProperties.WATERLOGGED, false);
            for (int k = 0; k < len; k++) {
                level.setBlock(pos.relative(grow, k), base.setValue(PointedDripstoneBlock.THICKNESS, thickness(k, len)), 2);
            }
            any = true;
        }
        return any;
    }

    /** Vanilla's shape for block {@code k} (0 at the rock) of a stack {@code len} long: base, middle, frustum, tip. */
    static DripstoneThickness thickness(int k, int len) {
        int fromTip = len - 1 - k;
        if (fromTip == 0) return DripstoneThickness.TIP;
        if (fromTip == 1) return DripstoneThickness.FRUSTUM;
        return k == 0 ? DripstoneThickness.BASE : DripstoneThickness.MIDDLE;
    }
}
