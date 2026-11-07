package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * The lava's own pockets given back in the Wasteland and the Basalt Galleries (fix round 3 after the 0.8.0 pack runs).
 * The pack's Terrain Slabs lays its slab pass at underground_structures over every chunk, before these layers' falls
 * and their floor's lava: a bottom slab goes into any spot a body can pass (lava is one) on a block it has a slab for
 * (basalt, blackstone), with no lava block on its four sides and a step beside it, and it waterlogs every slab whose spot
 * holds a fluid, lava too. So each 1 x 1 dip of the Wasteland's lava table, under one to three rows of its lava or in its
 * top row, held a slab full of water under or beside the lava (117 in the pack run's region, in every world), and a fall's
 * sump poured into a chunk before that chunk's own pass became one too (9; the falls now keep to their own chunk,
 * {@link FluidFallFeature}). Water beside lava turns to stone and runs out over the floor when a block beside it changes.
 *
 * <p>Once per chunk, after the falls and before the floor's lava, every slab such a pass made ({@link #generated}) that
 * holds water in these two layers' rows of the chunk, and of the {@link #RING} columns round it that a neighbour's pass
 * reaches into (Terrain Slabs' corner slabs and its longer runs), is set right, unless it stands in the terrain's own
 * water (the Obsidian Tubes' still pools) or under water. The layers hold no other water, so such a slab fills a spot that
 * held lava: the spot becomes a lava source again where the lava is held there (rock or lava under it and on its four
 * sides: {@link FloorLavaFeature#holds}), as the terrain or the fall made it, and otherwise the slab stays, dry. Sections
 * with no waterlogged block in their palette are passed over, so the pass costs next to nothing. The Blue Reaches and the
 * Deep Gardens are not touched: their rows lie over these.
 */
public final class LavaPocketsFeature extends Feature<NoneFeatureConfiguration> {
    /** How far into the neighbours round the chunk the pass looks: as far as a neighbour's slab pass reaches into it. */
    static final int RING = 2;

    public LavaPocketsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos own = FluidFallFeature.decorating(level, ctx.origin());
        int lo = Math.max(level.getMinBuildHeight(), t.shape.bottom(Terrain.GALLERIES)), hi = t.shape.top(Terrain.WASTELAND);
        int x0 = own.getMinBlockX() - RING, x1 = own.getMaxBlockX() + RING, z0 = own.getMinBlockZ() - RING, z1 = own.getMaxBlockZ() + RING;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int cx = own.x - 1; cx <= own.x + 1; cx++)
            for (int cz = own.z - 1; cz <= own.z + 1; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                ChunkAccess ch = level.getChunk(cx, cz);
                int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15), az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                for (int sy = lo >> 4; sy <= hi >> 4; sy++) {
                    int si = ch.getSectionIndexFromSectionY(sy);
                    if (si < 0 || si >= ch.getSectionsCount()) continue;
                    LevelChunkSection sec = ch.getSection(si);
                    if (sec.hasOnlyAir() || !sec.maybeHas(LavaPocketsFeature::wet)) continue;
                    for (int y = Math.max(lo, sy << 4); y <= Math.min(hi, (sy << 4) + 15); y++)
                        for (int x = ax; x <= bx; x++)
                            for (int z = az; z <= bz; z++) {
                                BlockState s = sec.getBlockState(x & 15, y & 15, z & 15);
                                if (!wet(s) || !generated(s)) continue;
                                pos.set(x, y, z);
                                if (t.fluidAt(x, y, z) == Terrain.WATER || level.getFluidState(pos.above()).is(FluidTags.WATER)) continue;
                                level.setBlock(pos, held(level, pos) ? Blocks.LAVA.defaultBlockState() : s.setValue(BlockStateProperties.WATERLOGGED, false), 2);
                                any = true;
                            }
                }
            }
        return any;
    }

    /** A block with water in it by its waterlogged property. */
    static boolean wet(BlockState s) {
        return s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED);
    }

    /**
     * Whether s is a slab another mod's slab pass laid in the terrain: a slab whose boolean property {@code generated} is
     * set (Terrain Slabs' slabs; read by name, so this mod needs no other).
     */
    static boolean generated(BlockState s) {
        if (!(s.getBlock() instanceof SlabBlock)) return false;
        for (Property<?> p : s.getProperties())
            if (p instanceof BooleanProperty b && p.getName().equals("generated")) return s.getValue(b);
        return false;
    }

    /** A lava source at pos would be still: rock or a lava source under it and on its four sides. */
    private static boolean held(WorldGenLevel level, BlockPos pos) {
        if (!FloorLavaFeature.holds(level.getBlockState(pos.below()))) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (!FloorLavaFeature.holds(level.getBlockState(pos.relative(d)))) return false;
        return true;
    }
}
