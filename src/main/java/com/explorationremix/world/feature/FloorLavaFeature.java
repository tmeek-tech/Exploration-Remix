package com.explorationremix.world.feature;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.GalleriesField;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.FluidState;

/**
 * The Basalt Galleries' floor lava (fix round 2 after the 0.8.0 pack runs: "brightly lit", and Taran's picture of the
 * Galleries, a lava river winding along a terraced basalt floor): the lava rivers' channels carried on over the dry
 * floors above the table, and rills along the halls' low lines, a block deep and flush with the floor, broken into
 * chains of pools ({@link GalleriesField#floorLavaRow}, a rule of the field alone). Runs once per chunk on its own
 * columns, after the open way pass and before the glow, and sets a lava source in the floor's top block where the
 * rule puts one and the blocks still hold it: the block itself and the one under it solid, and its four sides solid or
 * a lava source (another block of this lava, a pool's, the table's). So no block of it can flow, whatever ticks it, and
 * a neighbour's later decorations, which add rock or lava sources and never open a floor, cannot loose it. The rule
 * keeps it off the ways down, their heads and the holes' throats, off the tube country, off decks, spires and banded
 * rows, and a level walk round every block of it; it is the same everywhere, so the chunks' order does not change it.
 *
 * <p>Every block of it lies open to the air above it (fix round 3 after the 0.8.0 pack runs, where 2200 of its 7491
 * blocks lay hidden under a block: 2192 under the bottom slabs the pack's Terrain Slabs lays on the floor's steps before
 * this, and 8 under full blocks, one of which, basalt, was left resting on the lava alone): a column whose floor block has
 * air or a glow over it takes the lava; one with such a slab over it ({@link LavaPocketsFeature#generated}, dry, with air
 * or a glow over it) takes it too, and the slab goes, so the stream stays whole, shows and gives its light, and nothing
 * stands on it; any other block over it keeps the column's rock. So nothing ever rests on this lava.
 */
public final class FloorLavaFeature extends Feature<NoneFeatureConfiguration> {
    public FloorLavaFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed() || t.galleries == null) return false;
        GalleriesField g = t.galleries;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), side = new BlockPos.MutableBlockPos(), over = new BlockPos.MutableBlockPos();
        BlockState lava = Blocks.LAVA.defaultBlockState();
        boolean any = false;
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++) {
                int x = minX + dx, z = minZ + dz;
                int y = g.floorLavaRow(x, z);
                if (y == GalleriesField.NO_LAVA) continue;
                pos.set(x, y, z);
                if (!solid(level.getBlockState(pos)) || !solid(level.getBlockState(side.setWithOffset(pos, Direction.DOWN)))) continue;
                boolean held = true;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    if (!holds(level.getBlockState(side.setWithOffset(pos, d)))) { held = false; break; }
                }
                if (!held) continue;
                // open above: air or a glow, or a generated slab with air or a glow over it, which goes
                BlockState up = level.getBlockState(over.setWithOffset(pos, Direction.UP));
                if (!GlowBlock.open(up)) {
                    if (!coverSlab(up) || !GlowBlock.open(level.getBlockState(side.setWithOffset(over, Direction.UP)))) continue;
                    level.setBlock(over, Blocks.AIR.defaultBlockState(), 2);
                }
                level.setBlock(pos, lava, 2);
                any = true;
            }
        return any;
    }

    /**
     * A block that holds lava in and is no fluid: not air or a glow, and a block a fluid does not flow into (vanilla's
     * FlowingFluid flows into what does not block motion).
     */
    static boolean solid(BlockState s) {
        return !GlowBlock.open(s) && s.getFluidState().isEmpty() && s.blocksMotion();
    }

    /** A bottom slab another mod's slab pass laid on the floor, dry: one this lava clears. */
    static boolean coverSlab(BlockState s) {
        return LavaPocketsFeature.generated(s) && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM && s.getFluidState().isEmpty();
    }

    /** A side that keeps a lava source still: a solid block, or a lava source block. */
    static boolean holds(BlockState s) {
        FluidState f = s.getFluidState();
        if (!f.isEmpty()) return s.is(Blocks.LAVA) && f.isSource();
        return solid(s);
    }
}
