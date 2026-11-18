package com.explorationremix.world.feature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.UpperShapes;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * The rim of a plains sinkhole (0.18.6, from Taran's first picture of them: "daylit sinkholes over a pool, limestone
 * walls with grass roots hanging in"): grass trails and roots hang from the lip of soil over the pit, and in a pit hung
 * with ivy ({@link UpperShapes#TRAIT_IVY}) Immersive Weathering's ivy spills over its walls from the top. How thickly
 * the grass hangs is the pit's own ({@link UpperShapes#TRAIT_RIM}). The top {@link #DEPTH} blocks of the shaft lie in
 * the surface's biome, out of reach of the sinkhole's own features, so this runs there, once per chunk on its own
 * columns, after the surface's plants.
 */
public final class SinkholeRimFeature extends Feature<NoneFeatureConfiguration> {
    /** How far under the ground the rim's dressing reaches. */
    static final int DEPTH = 10;
    /** The natural rock and soil plants grow on, Create's limestone among them. */
    private static final TagKey<Block> CAVE_ROCK = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(ExplorationRemix.MOD_ID, "cave_rock"));

    public SinkholeRimFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        UpperShapes sh = DeepWorld.shapes();
        if (sh == null) return false;
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        ChunkPos cp = new ChunkPos(ctx.origin());
        BlockState ivy = ivy();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), side = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                int g = sh.sinkholeRim(x, z);
                if (g == Integer.MIN_VALUE) continue;
                double rim = sh.pitTrait(x, z, UpperShapes.TRAIT_RIM), ivyTrait = sh.pitTrait(x, z, UpperShapes.TRAIT_IVY);
                if (rim < 0) continue;
                // from a third to all of the lip hung with grass, by pit
                double hang = 0.2 + 0.4 * rim;
                boolean ivied = ivy != null && ivyTrait >= 0 && ivyTrait < 0.5;
                for (int y = g + 1; y >= g - DEPTH; y--) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir() || !sh.pitOpen(x, y, z)) continue;
                    int depth = g - y;
                    BlockState over = level.getBlockState(side.setWithOffset(pos, Direction.UP));
                    if (soil(over) && over.isFaceSturdy(level, side, Direction.DOWN) && random.nextDouble() < hang * (1 - depth / (double) (DEPTH + 4))) {
                        any |= hangFrom(level, random, pos, over);
                        continue;
                    }
                    if (ivied && random.nextDouble() < 0.8 - 0.05 * depth) any |= sheet(level, pos, side, ivy);
                }
            }
        return any;
    }

    /** Ground the grass and roots hang from: soil, grass and the rock under it. */
    private static boolean soil(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(CAVE_ROCK) || s.is(Blocks.GRAVEL);
    }

    /**
     * Grass trailing down from the lip, one to five blocks long and longer nearer the ground, or now and then a tuft of
     * roots under soil; each only as long as the air under it allows.
     */
    private static boolean hangFrom(WorldGenLevel level, RandomSource random, BlockPos.MutableBlockPos pos, BlockState over) {
        if (over.is(BlockTags.DIRT) && random.nextInt(4) == 0) {
            BlockState roots = Blocks.HANGING_ROOTS.defaultBlockState();
            if (!roots.canSurvive(level, pos)) return false;
            level.setBlock(pos, roots, 2);
            return true;
        }
        int want = 1 + random.nextInt(3) + random.nextInt(3);
        int n = 0;
        BlockPos.MutableBlockPos p = pos.mutable();
        while (n < want && level.getBlockState(p).isAir()) {
            n++;
            p.move(Direction.DOWN);
        }
        if (n == 0) return false;
        Block body = ERBlocks.TRAILING_GRASS_PLANT.get(), tip = ERBlocks.TRAILING_GRASS.get();
        p.set(pos);
        for (int i = 0; i < n; i++) {
            level.setBlock(p, (i == n - 1 ? tip : body).defaultBlockState(), 2);
            p.move(Direction.DOWN);
        }
        return true;
    }

    /** Ivy over every wall face of soil or rock round an open block. */
    private static boolean sheet(WorldGenLevel level, BlockPos pos, BlockPos.MutableBlockPos side, BlockState ivy) {
        BlockState place = ivy;
        boolean any = false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState s = level.getBlockState(side.setWithOffset(pos, d));
            if (!soil(s) || !s.isFaceSturdy(level, side, d.getOpposite())) continue;
            place = place.setValue(MultifaceBlock.getFaceProperty(d), true);
            any = true;
        }
        if (!any || !place.canSurvive(level, pos)) return false;
        level.setBlock(pos, place, 2);
        return true;
    }

    /** Immersive Weathering's ivy, or null without it. */
    private static BlockState ivy() {
        ResourceLocation id = ResourceLocation.tryParse("immersive_weathering:ivy");
        Block b = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        return b instanceof MultifaceBlock ? b.defaultBlockState() : null;
    }
}
