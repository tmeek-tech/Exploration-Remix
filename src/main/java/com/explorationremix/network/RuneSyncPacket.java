package com.explorationremix.network;

import com.explorationremix.rune.Rune;
import com.explorationremix.rune.RuneKind;
import com.explorationremix.rune.RuneRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Sends every rune definition to a client on login and datapack reload, so tooltips and the editor can show them. */
public record RuneSyncPacket(List<Rune> runes) {
    public static RuneSyncPacket all() { return new RuneSyncPacket(new ArrayList<>(RuneRegistry.all())); }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(runes.size());
        for (Rune r : runes) {
            buf.writeResourceLocation(r.id()); buf.writeEnum(r.kind()); buf.writeInt(r.cost()); buf.writeInt(r.tier()); buf.writeInt(r.weight());
            buf.writeInt(r.attunements().size());
            for (String a : r.attunements()) buf.writeUtf(a);
            buf.writeInt(r.params().size());
            for (Map.Entry<String, Double> e : r.params().entrySet()) { buf.writeUtf(e.getKey()); buf.writeDouble(e.getValue()); }
            buf.writeUtf(r.color());
        }
    }

    public static RuneSyncPacket read(FriendlyByteBuf buf) {
        int n = buf.readInt();
        List<Rune> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ResourceLocation id = buf.readResourceLocation(); RuneKind kind = buf.readEnum(RuneKind.class); int cost = buf.readInt(); int tier = buf.readInt(); int weight = buf.readInt();
            int na = buf.readInt(); List<String> att = new ArrayList<>();
            for (int j = 0; j < na; j++) att.add(buf.readUtf());
            int np = buf.readInt(); Map<String, Double> params = new HashMap<>();
            for (int j = 0; j < np; j++) params.put(buf.readUtf(), buf.readDouble());
            String color = buf.readUtf();
            list.add(new Rune(id, kind, cost, tier, weight, att, params, color));
        }
        return new RuneSyncPacket(list);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        Collection<Rune> copy = runes;
        RuneRegistry.replaceAll(copy);
        ctx.get().setPacketHandled(true);
    }
}
