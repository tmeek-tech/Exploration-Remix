package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.DeepBiomes;
import com.explorationremix.world.gen.SurfaceOracle;
import com.explorationremix.world.terrain.IceColumns;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The Frost Fern Grotto's ice columns (0.20.7, Taran: "Make ice columns procedurally generated instead of structures"),
 * grown from {@link IceColumns}' fields in place of 0.14.0's columns placed one by one (the pillar feature) and the
 * grotto's giant icicles: joined columns, broken spikes over stumps, and the fused masses where they crowd, in groves
 * that come thicker the deeper the grotto lies. It runs once per chunk with the grotto in it, first of the grotto's
 * features, and grows whole the formations whose cells centre in the chunk, up to 13 blocks into the chunks round it
 * (a feature may change the chunks next to its own), so one chunk alone decides each formation.
 */
public final class IceColumnsFeature extends Feature<NoneFeatureConfiguration> {
    /** Rooms are read from this far over the caves' old floor up to this far under the lowest ground round the column. */
    private static final int FLOOR_INSET = 1, GROUND_INSET = 3;
    private static final ResourceLocation ICICLE = new ResourceLocation("immersive_weathering", "icicle");

    private static volatile IceColumns columns;

    public IceColumnsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    /** The world's fields, made once per seed. */
    private static IceColumns columns(long seed) {
        IceColumns c = columns;
        if (c == null || c.seed() != seed) columns = c = new IceColumns(seed);
        return c;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        Shape shape = DeepWorld.shape();
        if (t == null || shape == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        SurfaceOracle oracle = DeepWorld.oracle();
        Block icicleBlock = ForgeRegistries.BLOCKS.getValue(ICICLE);
        BlockState icicle = icicleBlock == null || icicleBlock == Blocks.AIR ? null : icicleBlock.defaultBlockState();
        if (icicle != null && (!icicle.hasProperty(PointedDripstoneBlock.TIP_DIRECTION) || !icicle.hasProperty(PointedDripstoneBlock.THICKNESS)))
            icicle = null;
        else if (icicle != null && icicle.hasProperty(BlockStateProperties.WATERLOGGED))
            icicle = icicle.setValue(BlockStateProperties.WATERLOGGED, false);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState packed = Blocks.PACKED_ICE.defaultBlockState(), blue = Blocks.BLUE_ICE.defaultBlockState();

        IceColumns.World world = new IceColumns.World() {
            @Override
            public boolean open(int x, int y, int z) {
                return IceColumnsFeature.open(level.getBlockState(pos.set(x, y, z)), icicleBlock);
            }

            @Override
            public boolean standable(int x, int y, int z) {
                BlockState s = level.getBlockState(pos.set(x, y, z));
                // not a frozen pool's ice either, which was water until a later feature froze it
                return !IceColumnsFeature.open(s, icicleBlock) && !s.is(Blocks.ICE) && s.getFluidState().isEmpty()
                        && s.isFaceSturdy(level, pos, Direction.UP);
            }

            @Override
            public boolean grotto(int x, int y, int z) {
                return level.getBiome(pos.set(x, y, z)).is(DeepBiomes.FROST_FERN_GROTTO);
            }

            @Override
            public double iciness(int x, int y, int z) {
                return t.palette.grottoIce(x, y, z, ground(x, z));
            }

            @Override
            public boolean way(int x, int z, int y0, int y1, double margin) {
                return Walkway.near(x, z, y0, y1, margin);
            }

            @Override
            public int low(int x, int z) { return shape.packFloor + FLOOR_INSET; }

            @Override
            public int high(int x, int z) { return ground(x, z) - GROUND_INSET; }

            /**
             * The ground over a column as the world was shaped, the same whatever has grown on it since (trees raise the
             * heightmaps): so the grotto reads as icy at a formation's middle from every chunk.
             */
            private int ground(int x, int z) {
                return oracle != null ? oracle.low(x, z) : shape.packFloor + 128;
            }
        };
        BlockState icicleState = icicle;
        boolean[] any = {false};
        IceColumns.Sink sink = new IceColumns.Sink() {
            @Override
            public void ice(int x, int y, int z, boolean isBlue) {
                level.setBlock(pos.set(x, y, z), isBlue ? blue : packed, 2);
                any[0] = true;
            }

            @Override
            public void icicle(int x, int y, int z, boolean down, int index, int length) {
                // Immersive Weathering's icicles melt by day outside its icy biomes: only in the grotto, and only into air
                if (icicleState == null || !level.getBlockState(pos.set(x, y, z)).isAir()) return;
                if (!level.getBiome(pos).is(DeepBiomes.FROST_FERN_GROTTO)) return;
                level.setBlock(pos.set(x, y, z), icicleState.setValue(PointedDripstoneBlock.TIP_DIRECTION, down ? Direction.DOWN : Direction.UP)
                        .setValue(PointedDripstoneBlock.THICKNESS, PointedPatchFeature.thickness(index, length)), 2);
            }
        };
        columns(level.getSeed()).chunk(cp.getMinBlockX(), cp.getMinBlockZ(), world, sink);
        return any[0];
    }

    /**
     * Open to the ice: air, ice (packed or blue: a formation already grown reads as the room it grew in), an icicle, a
     * half slab, and whatever a body walks through or a block replaces (snow layers, plants, moss carpets, frost); not
     * water or the ice of a frozen pool, nor rock.
     */
    static boolean open(BlockState s, Block icicle) {
        if (s.isAir() || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE) || icicle != null && s.is(icicle)) return true;
        // a half slab: Terrain Slabs lays them in the air of a floor's or a ceiling's steps after this has run in its chunk
        if (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) return true;
        if (!s.getFluidState().isEmpty() || s.is(Blocks.ICE)) return false;
        return s.canBeReplaced() || !s.blocksMotion();
    }
}
