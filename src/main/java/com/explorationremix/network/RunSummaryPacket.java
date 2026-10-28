package com.explorationremix.network;

import com.explorationremix.run.RunSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RunSummaryPacket(RunSummary summary) {
    public void write(FriendlyByteBuf buf) { summary.write(buf); }
    public static RunSummaryPacket read(FriendlyByteBuf buf) { return new RunSummaryPacket(RunSummary.read(buf)); }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> com.explorationremix.client.ClientHooks.showRunSummary(summary));
        ctx.get().setPacketHandled(true);
    }
}
