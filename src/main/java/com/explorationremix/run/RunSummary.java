package com.explorationremix.run;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** What the run summary screen shows. */
public record RunSummary(long runId, String endReason, long ticks, int deepestLayer, int gatesPassed, int kills, int xpEarned, int xpSpent,
                         int runeBlocksWoken, int damageTaken, List<String> runesTaken) {

    public static RunSummary of(RunState s, long now) {
        return new RunSummary(s.runId, s.endReason, Math.max(0, (s.endedAt == 0 ? now : s.endedAt) - s.startedAt), s.deepestLayer, s.gatesPassed, s.kills,
                s.xpEarned, s.xpSpent, s.runeBlocksWoken, s.damageTaken, List.copyOf(s.runesTaken));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(runId); buf.writeUtf(endReason); buf.writeLong(ticks); buf.writeInt(deepestLayer); buf.writeInt(gatesPassed); buf.writeInt(kills);
        buf.writeInt(xpEarned); buf.writeInt(xpSpent); buf.writeInt(runeBlocksWoken); buf.writeInt(damageTaken);
        buf.writeInt(runesTaken.size());
        for (String s : runesTaken) buf.writeUtf(s);
    }

    public static RunSummary read(FriendlyByteBuf buf) {
        long id = buf.readLong(); String reason = buf.readUtf(); long ticks = buf.readLong(); int deepest = buf.readInt(); int gates = buf.readInt(); int kills = buf.readInt();
        int earned = buf.readInt(); int spent = buf.readInt(); int woken = buf.readInt(); int dmg = buf.readInt();
        int n = buf.readInt();
        List<String> runes = new ArrayList<>();
        for (int i = 0; i < n; i++) runes.add(buf.readUtf());
        return new RunSummary(id, reason, ticks, deepest, gates, kills, earned, spent, woken, dmg, runes);
    }
}
