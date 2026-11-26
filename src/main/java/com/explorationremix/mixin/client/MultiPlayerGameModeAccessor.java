package com.explorationremix.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block this player is digging and how far along it is, which the game keeps to itself (RuneStoneClient). */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("destroyBlockPos")
    BlockPos explorationremix$digging();

    /** 0 to 1, smooth: the stage the game shows is this in tenths. */
    @Accessor("destroyProgress")
    float explorationremix$progress();
}
