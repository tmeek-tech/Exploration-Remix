package com.explorationremix.run;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/** One player's current (or last) run. */
public final class RunState {
    public long runId;               // 0 = no run ever
    public boolean active;
    public long startedAt;           // game time
    public long endedAt;
    public int deepestLayer = -1;    // -1 = above band 1
    public int gatesPassed;
    public int kills;
    public int xpEarned;
    public int xpSpent;
    public int runeBlocksWoken;
    public int damageTaken;
    public String endReason = "";
    public final List<String> runesTaken = new ArrayList<>();
    public long lastBandCrossed;     // bitmask of bands passed this run

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("RunId", runId); t.putBoolean("Active", active); t.putLong("StartedAt", startedAt); t.putLong("EndedAt", endedAt);
        t.putInt("Deepest", deepestLayer); t.putInt("Gates", gatesPassed); t.putInt("Kills", kills); t.putInt("XpEarned", xpEarned);
        t.putInt("XpSpent", xpSpent); t.putInt("Woken", runeBlocksWoken); t.putInt("Damage", damageTaken); t.putString("End", endReason);
        t.putLong("Bands", lastBandCrossed);
        ListTag list = new ListTag();
        for (String s : runesTaken) list.add(StringTag.valueOf(s));
        t.put("Runes", list);
        return t;
    }

    public static RunState load(CompoundTag t) {
        RunState s = new RunState();
        s.runId = t.getLong("RunId"); s.active = t.getBoolean("Active"); s.startedAt = t.getLong("StartedAt"); s.endedAt = t.getLong("EndedAt");
        s.deepestLayer = t.getInt("Deepest"); s.gatesPassed = t.getInt("Gates"); s.kills = t.getInt("Kills"); s.xpEarned = t.getInt("XpEarned");
        s.xpSpent = t.getInt("XpSpent"); s.runeBlocksWoken = t.getInt("Woken"); s.damageTaken = t.getInt("Damage"); s.endReason = t.getString("End");
        s.lastBandCrossed = t.getLong("Bands");
        ListTag list = t.getList("Runes", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) s.runesTaken.add(list.getString(i));
        return s;
    }
}
