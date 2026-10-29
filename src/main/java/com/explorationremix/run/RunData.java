package com.explorationremix.run;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-wide store of every player's run state, saved with the overworld. */
public final class RunData extends SavedData {
    private static final String NAME = "explorationremix_runs";
    private final Map<UUID, RunState> states = new HashMap<>();
    private long nextRunId = 1;

    public static RunData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(RunData::load, RunData::new, NAME);
    }

    public RunState state(UUID player) {
        return states.computeIfAbsent(player, k -> { setDirty(); return new RunState(); });
    }

    public long newRunId() { setDirty(); return nextRunId++; }

    public Map<UUID, RunState> all() { return states; }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag players = new CompoundTag();
        states.forEach((id, st) -> players.put(id.toString(), st.save()));
        tag.put("Players", players);
        tag.putLong("NextRunId", nextRunId);
        return tag;
    }

    public static RunData load(CompoundTag tag) {
        RunData d = new RunData();
        CompoundTag players = tag.getCompound("Players");
        for (String key : players.getAllKeys()) {
            try { d.states.put(UUID.fromString(key), RunState.load(players.getCompound(key))); } catch (IllegalArgumentException ignored) {}
        }
        d.nextRunId = Math.max(1, tag.getLong("NextRunId"));
        return d;
    }
}
