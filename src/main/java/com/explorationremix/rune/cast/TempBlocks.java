package com.explorationremix.rune.cast;

import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.world.TempBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The blocks runes make for a while (Barrier glass, Frost's ice, Glow's light), kept to a few per player: a new one
 * past the limit ends that player's oldest first. Runes only ever fill air (and still water, for ice), so they never
 * break or replace anything built or grown. Each block undoes itself on a timer saved with its chunk (TempBlock).
 */
public final class TempBlocks {
    private record Placed(ResourceKey<Level> dim, BlockPos pos) {}

    private static final Map<UUID, Map<TempBlock.Kind, Deque<Placed>>> OWNED = new HashMap<>();

    private TempBlocks() {}

    private static int cap(TempBlock.Kind k) {
        return switch (k) {
            case BARRIER -> RuneConfig.get(RuneConfig.INSTANCE.maxBarrierBlocks);
            case ICE -> RuneConfig.get(RuneConfig.INSTANCE.maxIceBlocks);
            case LIGHT -> RuneConfig.get(RuneConfig.INSTANCE.maxGlowBlocks);
        };
    }

    private static Block block(TempBlock.Kind k) {
        return switch (k) {
            case BARRIER -> RuneRegistry.BARRIER.get();
            case ICE -> RuneRegistry.ICE.get();
            case LIGHT -> RuneRegistry.LIGHT.get();
        };
    }

    /** Whether a rune may make this kind of block here: air for glass and light, the top of still water for ice. */
    public static boolean fits(ServerLevel level, BlockPos pos, TempBlock.Kind k) {
        if (!level.isLoaded(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockState s = level.getBlockState(pos);
        if (k == TempBlock.Kind.ICE) {
            FluidState f = s.getFluidState();
            return s.is(Blocks.WATER) && f.isSource() && f.getType() == Fluids.WATER && level.getBlockState(pos.above()).isAir();
        }
        return s.isAir();
    }

    /** Makes one block for {@code owner}, ending their oldest of that kind past the limit. False if it doesn't fit. */
    public static boolean place(ServerLevel level, BlockPos pos, TempBlock.Kind k, UUID owner) {
        int cap = cap(k);
        if (cap <= 0 || !fits(level, pos, k)) return false;
        Deque<Placed> q = OWNED.computeIfAbsent(owner, u -> new HashMap<>()).computeIfAbsent(k, kk -> new ArrayDeque<>());
        while (q.size() >= cap) {
            Placed old = q.pollFirst();
            ServerLevel l = level.getServer().getLevel(old.dim);
            if (l != null && l.isLoaded(old.pos) && l.getBlockState(old.pos).getBlock() instanceof TempBlock tb && tb.kind == k) tb.fade(l, old.pos);
        }
        if (!level.setBlock(pos, block(k).defaultBlockState(), Block.UPDATE_ALL)) return false;
        q.addLast(new Placed(level.dimension(), pos.immutable()));
        return true;
    }

    /** A player left: their blocks stay until their timers end, but no longer count against anyone. */
    public static void forget(UUID owner) { OWNED.remove(owner); }

    public static void clear() { OWNED.clear(); }
}
