package com.explorationremix.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the player's current run id (0 when not in a run), the deepest layer reached, when the run
 * started, and the pack floor so a client on a dedicated server can lay out the layers for fog and the HUD.
 */
public record RunStatePacket(long runId, int layerIndex, long startedAt, int packFloor, boolean installed) {
    public void write(FriendlyByteBuf buf) { buf.writeLong(runId); buf.writeInt(layerIndex); buf.writeLong(startedAt); buf.writeInt(packFloor); buf.writeBoolean(installed); }
    public static RunStatePacket read(FriendlyByteBuf buf) { return new RunStatePacket(buf.readLong(), buf.readInt(), buf.readLong(), buf.readInt(), buf.readBoolean()); }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.ClientHooks.runState(this));
        ctx.get().setPacketHandled(true);
    }
}
