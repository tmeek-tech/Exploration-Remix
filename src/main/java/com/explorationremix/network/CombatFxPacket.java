package com.explorationremix.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server to client: hit-stop frames and a camera kick, strength 0..1. */
public record CombatFxPacket(int hitStopTicks, float kick, float dirX, float dirZ) {
    public void write(FriendlyByteBuf buf) { buf.writeInt(hitStopTicks); buf.writeFloat(kick); buf.writeFloat(dirX); buf.writeFloat(dirZ); }
    public static CombatFxPacket read(FriendlyByteBuf buf) { return new CombatFxPacket(buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readFloat()); }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.ClientHooks.combatFx(this));
        ctx.get().setPacketHandled(true);
    }
}
