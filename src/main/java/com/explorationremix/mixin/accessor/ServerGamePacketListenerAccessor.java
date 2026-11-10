package com.explorationremix.mixin.accessor;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How many moves a player's game has sent: one each of its ticks while they move, however the packets bunch up. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {
    @Accessor("receivedMovePacketCount") int er$movePackets();
}
