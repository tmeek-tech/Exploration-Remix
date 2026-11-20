package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/**
 * Places only where no block of the biomes given is in sight within {@code reach} blocks: rays go out from the spot in
 * 34 directions (every way to the 26 neighbours and 8 more slanting gently down) through open blocks (air, plants,
 * vines: nothing with a collision shape), and any ray that reaches a block of those biomes before rock stops it fails
 * the spot.
 * <p>
 * 0.20.1: a frozen Root Vault opens into the Frost Fern Grotto under it in places, its ceiling cells over the grotto's
 * hall, and the vault's frostberries hung there over the grotto (the first test pictures). Taran: the grotto "might look
 * better without the blue glowberry vines", and they stay in the vaults: so they grow only where the grotto is out of
 * sight, about two in three of them where vault and grotto mingle.
 */
public final class OutOfSightFilter extends PlacementFilter {
    public static final Codec<OutOfSightFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
            TagKey.hashedCodec(Registries.BIOME).fieldOf("biomes").forGetter(OutOfSightFilter::biomes),
            // at most 24: the rays stay inside the chunks a feature may read round its own
            Codec.intRange(1, 24).fieldOf("reach").forGetter(OutOfSightFilter::reach)
    ).apply(i, OutOfSightFilter::new));

    /** The ray directions, unit length. */
    private static final double[][] DIRS = directions();

    private final TagKey<Biome> biomes;
    private final int reach;

    public OutOfSightFilter(TagKey<Biome> biomes, int reach) {
        this.biomes = biomes;
        this.reach = reach;
    }

    public TagKey<Biome> biomes() { return biomes; }

    public int reach() { return reach; }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        WorldGenLevel level = ctx.getLevel();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double ox = pos.getX() + 0.5, oy = pos.getY() + 0.5, oz = pos.getZ() + 0.5;
        for (double[] d : DIRS) {
            int lx = pos.getX(), ly = pos.getY(), lz = pos.getZ();
            for (int s = 1; s <= reach; s++) {
                p.set((int) Math.floor(ox + d[0] * s), (int) Math.floor(oy + d[1] * s), (int) Math.floor(oz + d[2] * s));
                if (p.getX() == lx && p.getY() == ly && p.getZ() == lz) continue;
                lx = p.getX(); ly = p.getY(); lz = p.getZ();
                if (level.isOutsideBuildHeight(p)) break;
                BlockState state = level.getBlockState(p);
                if (!state.isAir() && !state.getCollisionShape(level, p).isEmpty()) break;
                if (level.getBiome(p).is(biomes)) return false;
            }
        }
        return true;
    }

    private static double[][] directions() {
        double[][] out = new double[34][];
        int k = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0) out[k++] = unit(dx, dy, dz);
        for (int a = 0; a < 8; a++) out[k++] = unit(Math.cos(a * Math.PI / 4), -0.5, Math.sin(a * Math.PI / 4));
        return out;
    }

    private static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.OUT_OF_SIGHT.get(); }
}
