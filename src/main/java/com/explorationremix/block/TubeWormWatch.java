package com.explorationremix.block;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.ERBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The tube worms' eyes (0.20.2, {@link TubeWormsBlock}): every {@link #EVERY} ticks each player (not a spectator) is
 * looked round, the blocks within {@link TubeWormsBlock#REACH} of them, and every cluster out that near pulls in, with
 * one soft snap for the lot. A few hundred block lookups a player each time, and only in loaded ground (round a
 * player it always is).
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class TubeWormWatch {
    private TubeWormWatch() {}

    /** How often each player is looked round (ticks). */
    static final int EVERY = 4;

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level)) return;
        long time = level.getGameTime();
        for (ServerPlayer p : level.players())
            if (!p.isSpectator() && p.isAlive() && Math.floorMod(time + p.getId(), EVERY) == 0) lookRound(level, p);
    }

    static void lookRound(ServerLevel level, ServerPlayer p) {
        Block worms = ERBlocks.TUBE_WORMS.get();
        double reach = p.isShiftKeyDown() ? TubeWormsBlock.REACH_SNEAK : TubeWormsBlock.REACH;
        int r = (int) Math.ceil(reach);
        BlockPos at = p.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos first = null;
        for (int dy = -r; dy <= 2; dy++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    BlockState s = level.getBlockState(m);
                    if (!s.is(worms) || !s.getValue(TubeWormsBlock.OUT)) continue;
                    if (p.distanceToSqr(m.getX() + 0.5, m.getY() + 0.4, m.getZ() + 0.5) > reach * reach) continue;
                    BlockPos pos = m.immutable();
                    TubeWormsBlock.pullIn(level, pos, s);
                    if (first == null) first = pos;
                }
        if (first != null)
            level.playSound(null, first, SoundEvents.BIG_DRIPLEAF_TILT_DOWN, SoundSource.BLOCKS, 0.35F, 1.6F + level.random.nextFloat() * 0.3F);
    }
}
