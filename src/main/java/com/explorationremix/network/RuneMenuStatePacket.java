package com.explorationremix.network;

import com.explorationremix.rune.menu.RuneBlockMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server to client: the rune block editor's state (offers, cost, run XP) after the server changed it. */
public record RuneMenuStatePacket(List<ResourceLocation> offers, int wakeCost, int xp, boolean wokenThisRun, boolean inRun, int tier) {
    public void write(FriendlyByteBuf buf) {
        buf.writeInt(offers.size());
        for (ResourceLocation r : offers) buf.writeResourceLocation(r);
        buf.writeInt(wakeCost); buf.writeInt(xp); buf.writeBoolean(wokenThisRun); buf.writeBoolean(inRun); buf.writeInt(tier);
    }

    public static RuneMenuStatePacket read(FriendlyByteBuf buf) {
        int n = buf.readInt();
        List<ResourceLocation> offers = new ArrayList<>();
        for (int i = 0; i < n; i++) offers.add(buf.readResourceLocation());
        return new RuneMenuStatePacket(offers, buf.readInt(), buf.readInt(), buf.readBoolean(), buf.readBoolean(), buf.readInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof RuneBlockMenu menu) menu.applyState(this);
        });
        ctx.get().setPacketHandled(true);
    }
}
