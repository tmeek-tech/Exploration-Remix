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
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/**
 * Places only where every biome within {@code radius} blocks across, from {@code below} blocks under the spot to
 * {@code above} blocks over it, is in a biome tag, looked up as the game looks it up for a block (with its blur between
 * biome cells). Immersive Weathering's icicles melt and fall by day in any biome outside {@code #immersive_weathering:icy},
 * so an icicle cluster that reached from a Frost Fern Grotto into the lush cave beside it, or up into the surface biome's
 * cells over a frozen Root Vault, would drop its icicles there: the clusters keep their whole reach inside the icy
 * biomes. Each column of a cluster looks for its ceiling and its floor up to 13 blocks from the spot, so that is the
 * reach to check, both ways. Samples a grid at most 3 blocks apart, finer than the 4-block biome cells.
 */
public final class BiomeAroundFilter extends PlacementFilter {
    public static final Codec<BiomeAroundFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
            TagKey.hashedCodec(Registries.BIOME).fieldOf("biomes").forGetter(BiomeAroundFilter::biomes),
            Codec.intRange(0, 12).fieldOf("radius").forGetter(BiomeAroundFilter::radius),
            Codec.intRange(0, 16).optionalFieldOf("above", 0).forGetter(BiomeAroundFilter::above),
            Codec.intRange(0, 16).optionalFieldOf("below", 0).forGetter(BiomeAroundFilter::below)
    ).apply(i, BiomeAroundFilter::new));

    private static final int STEP = 3;

    private final TagKey<Biome> biomes;
    private final int radius, above, below;

    public BiomeAroundFilter(TagKey<Biome> biomes, int radius, int above, int below) {
        this.biomes = biomes;
        this.radius = radius;
        this.above = above;
        this.below = below;
    }

    public TagKey<Biome> biomes() { return biomes; }

    public int radius() { return radius; }

    public int above() { return above; }

    public int below() { return below; }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        WorldGenLevel level = ctx.getLevel();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int[] across = offsets(-radius, radius);
        for (int dy : offsets(-below, above)) {
            for (int dx : across) {
                for (int dz : across) {
                    if (!level.getBiome(p.setWithOffset(pos, dx, dy, dz)).is(biomes)) return false;
                }
            }
        }
        return true;
    }

    /** From {@code lo} to {@code hi}, both included, at most STEP apart (just {@code lo} when they are equal). */
    static int[] offsets(int lo, int hi) {
        int n = hi <= lo ? 1 : (hi - lo + STEP - 1) / STEP + 1;
        int[] o = new int[n];
        for (int k = 0; k < n; k++) o[k] = n == 1 ? lo : lo + (int) Math.round((double) (hi - lo) * k / (n - 1));
        return o;
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.BIOME_AROUND.get(); }
}
