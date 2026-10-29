package com.explorationremix.world.feature;

import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Rootvaults;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Right under the trees: for each tree trunk standing over a Rootvaults grotto in this chunk (a big tree's 2x2 trunk
 * counts once), with the given chance, the first open block under the rock beneath it (or a plant hanging there), at
 * most {@code max_depth} blocks under the ground. Meant for the top layer step, when the chunk's own trees have grown;
 * roots placed there hang straight under the tree they belong to.
 */
public final class UnderTreesPlacement extends PlacementModifier {
    public static final Codec<UnderTreesPlacement> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.floatRange(0.0F, 1.0F).fieldOf("chance").forGetter(UnderTreesPlacement::chance),
            Codec.intRange(4, 64).optionalFieldOf("max_depth", 44).forGetter(UnderTreesPlacement::maxDepth)
    ).apply(i, UnderTreesPlacement::new));

    private final float chance;
    private final int maxDepth;

    public UnderTreesPlacement(float chance, int maxDepth) {
        this.chance = chance;
        this.maxDepth = maxDepth;
    }

    public float chance() { return chance; }

    public int maxDepth() { return maxDepth; }

    @Override
    public Stream<BlockPos> getPositions(PlacementContext ctx, RandomSource random, BlockPos pos) {
        Rootvaults r = DeepWorld.roots();
        if (r == null) return Stream.empty();
        WorldGenLevel level = ctx.getLevel();
        int x0 = pos.getX() & ~15, z0 = pos.getZ() & ~15;
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = x0 + dx, z = z0 + dz;
            if (r.grottoAt(x, z) == null) continue;
            BlockState log = CeilingRootsFeature.trunk(level, x, z);
            if (log == null) continue;
            // One root per big tree: its trunk's corner towards negative x and z.
            if (log.equals(CeilingRootsFeature.trunk(level, x - 1, z)) || log.equals(CeilingRootsFeature.trunk(level, x, z - 1))) continue;
            if (random.nextFloat() >= chance) continue;
            int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
            for (int y = ground - 1; y >= ground - maxDepth; y--) {
                BlockState s = level.getBlockState(p.set(x, y, z));
                if (!s.getFluidState().isEmpty()) break;
                // The first block that is not rock: the open space under the ceiling, or a plant hanging from it there
                // (the vegetation came first; the root takes its place).
                if (s.isAir() || s.canBeReplaced()) {
                    out.add(p.immutable());
                    break;
                }
            }
        }
        return out.stream();
    }

    @Override
    public PlacementModifierType<?> type() { return ERWorldgen.UNDER_TREES.get(); }
}
