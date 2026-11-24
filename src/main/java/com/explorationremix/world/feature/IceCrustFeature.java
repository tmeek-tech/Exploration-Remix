package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepMaterials;
import com.explorationremix.world.terrain.Palette;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * Ice crusting the rock round a spot of the deep Frost Fern Grotto (0.20.1, Taran: "the deeper caves more icy"): in an
 * ellipsoid of its own size each time (solid in the middle, ragged at the rim), every block of rock of the kinds given
 * that lies bare to the air beside it or under it becomes packed ice with specks of blue ice
 * ({@link Palette#grottoIceBlock}; 0.20.1's glowing veins left with the glow ice in 0.20.7). The tops of floors and ledges
 * are left to the grotto's floors ({@link Palette#frostGrotto}), where its frost moss patches grow, so the crust ices
 * walls and the undersides of ledges and ceilings: patches of ice over the rock.
 */
public final class IceCrustFeature extends Feature<IceCrustFeature.Config> {
    public record Config(int radius, int height, HolderSet<Block> on) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                // at most 8 from the spot: the crust stays inside the chunks a feature may change
                Codec.intRange(1, 8).fieldOf("radius").forGetter(Config::radius),
                Codec.intRange(1, 12).fieldOf("height").forGetter(Config::height),
                RegistryCodecs.homogeneousList(Registries.BLOCK).fieldOf("on").forGetter(Config::on)
        ).apply(i, Config::new));
    }

    public IceCrustFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        Terrain t = DeepWorld.terrain();
        if (t == null) return false;
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), side = new BlockPos.MutableBlockPos();
        double size = 0.6 + 0.4 * random.nextDouble();
        double rx = Math.max(1.5, c.radius() * size), ry = Math.max(1.5, c.height() * size);
        boolean any = false;
        for (int dx = -c.radius(); dx <= c.radius(); dx++) for (int dz = -c.radius(); dz <= c.radius(); dz++) {
            int x = origin.getX() + dx, z = origin.getZ() + dz;
            for (int dy = -c.height(); dy <= c.height(); dy++) {
                double q = (dx * dx + dz * dz) / (rx * rx) + dy * dy / (ry * ry);
                if (q > 1 || q > 0.5 && random.nextFloat() < (q - 0.5) / 0.5 * 0.8) continue;
                pos.set(x, origin.getY() + dy, z);
                if (!level.getBlockState(pos).is(c.on())) continue;
                // the top of a floor or a ledge stays the floor's
                if (level.getBlockState(side.setWithOffset(pos, Direction.UP)).isAir()) continue;
                if (!bare(level, pos, side)) continue;
                level.setBlock(pos, DeepMaterials.state(t.palette.grottoIceBlock(x, pos.getY(), z)), 2);
                any = true;
            }
        }
        return any;
    }

    /** Whether air lies beside the block or under it. */
    private static boolean bare(WorldGenLevel level, BlockPos pos, BlockPos.MutableBlockPos side) {
        for (Direction d : Direction.values())
            if (d != Direction.UP && level.getBlockState(side.setWithOffset(pos, d)).isAir()) return true;
        return false;
    }
}
