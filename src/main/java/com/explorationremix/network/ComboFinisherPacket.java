package com.explorationremix.network;

import com.explorationremix.combat.CombatTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: Better Combat started the last swing of a combo. */
public record ComboFinisherPacket() {
    public void write(FriendlyByteBuf buf) {}
    public static ComboFinisherPacket read(FriendlyByteBuf buf) { return new ComboFinisherPacket(); }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        if (p != null) CombatTracker.markFinisherWindow(p);
        ctx.get().setPacketHandled(true);
    }
}
