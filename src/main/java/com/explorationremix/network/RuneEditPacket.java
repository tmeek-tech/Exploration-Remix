package com.explorationremix.network;

import com.explorationremix.rune.menu.RuneBlockMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: an action in the rune block editor. */
public record RuneEditPacket(Action action, int a, int b) {
    public enum Action { WAKE, PICK_OFFER, MOVE, REMOVE, ATTUNE }

    public void write(FriendlyByteBuf buf) { buf.writeEnum(action); buf.writeInt(a); buf.writeInt(b); }
    public static RuneEditPacket read(FriendlyByteBuf buf) { return new RuneEditPacket(buf.readEnum(Action.class), buf.readInt(), buf.readInt()); }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        if (p != null && p.containerMenu instanceof RuneBlockMenu menu) menu.handle(p, this);
        ctx.get().setPacketHandled(true);
    }
}
