package com.explorationremix.creature;

import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.Layer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a gate nest the first time a run reaches its landing: egg clusters on the chamber floor with the layer's
 * growth around them. After a regrow it puts back any cluster that is missing.
 */
public final class NestBuilder {
    private NestBuilder() {}

    private static Block growth(Layer layer) {
        return switch (layer.index()) {
            case 0 -> ERBlocks.AMBER_RESIN.get();
            case 1 -> ERBlocks.ASURINE_CRYSTAL.get();
            case 2 -> ERBlocks.HEMATITE_CRYSTAL.get();
            default -> Blocks.MAGMA_BLOCK;
        };
    }

    private static boolean floorAt(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /** Lays out the clusters. Only chunks that are loaded take part; an empty layout is retried on the next visit. */
    public static void build(ServerLevel level, String key, NestRegrowth.Entry entry, BlockPos center, Layer layer, NestRegrowth data) {
        RandomSource random = RandomSource.create(center.asLong() ^ level.getSeed());
        int want = ERConfig.INSTANCE.nestEggClusters.get();
        List<BlockPos> layout = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int tries = 0; tries < want * 10 && layout.size() < want; tries++) {
            double angle = random.nextDouble() * Math.PI * 2, r = 4 + random.nextDouble() * 9;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * r), z = center.getZ() + (int) Math.round(Math.sin(angle) * r);
            pos.set(x, center.getY() + 8, z);
            if (!level.hasChunkAt(pos)) continue;
            boolean found = false;
            for (int dy = 0; dy < 26; dy++) {
                pos.setY(center.getY() + 8 - dy);
                if (floorAt(level, pos)) { found = true; break; }
            }
            if (!found) continue;
            BlockPos at = pos.immutable();
            boolean tooClose = false;
            for (BlockPos other : layout) if (other.distSqr(at) < 6) { tooClose = true; break; }
            if (tooClose) continue;
            layout.add(at);
        }
        if (layout.isEmpty()) return;
        for (BlockPos at : layout) placeCluster(level, at, layer, random);
        data.register(key, center, layout);
    }

    private static void placeCluster(ServerLevel level, BlockPos at, Layer layer, RandomSource random) {
        level.setBlock(at, ERBlocks.EGG_CLUSTER.get().defaultBlockState(), 3);
        BlockState g = growth(layer).defaultBlockState();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = at.relative(d);
            if (random.nextFloat() < 0.5F && level.getBlockState(n).isAir() && !level.getBlockState(n.below()).isAir()) level.setBlock(n, g, 3);
        }
    }

    /** Puts back every cluster the bookkeeping still expects but the world has lost (after a regrow, or a creative break). */
    public static void ensure(ServerLevel level, NestRegrowth.Entry entry, Layer layer) {
        RandomSource random = level.random;
        for (BlockPos at : entry.clusters) {
            if (!level.hasChunkAt(at)) continue;
            BlockState s = level.getBlockState(at);
            if (s.is(ERBlocks.EGG_CLUSTER.get())) continue;
            if (s.isAir() || s.canBeReplaced()) placeCluster(level, at, layer, random);
        }
    }

    public static void regrow(ServerLevel level, String key, NestRegrowth.Entry entry) {
        if (level == null) return;
        NestFeatureHooks.regrow(level, key, entry);
    }
}
