package com.explorationremix.world.lava;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The lava cycle's clock ({@link LavaClock}) in the overworld's saved data,
 * {@code data/explorationremix_lava_cycle.dat}: when the current cycle began, its phase lengths, whether the clock is
 * held and where, how many cycles have ended, and a cycle length set by command. Marked dirty when it is created and
 * when one of those changes (a new cycle or a command), never every tick: the phase follows from the game time, saved
 * with the level.
 */
public final class LavaCycleData extends SavedData {
    private static final String NAME = "explorationremix_lava_cycle";
    private static final int VERSION = 1;

    final LavaClock clock;

    private LavaCycleData(LavaClock clock) { this.clock = clock; }

    /** The overworld's clock, started calm at the current game time the first time it is asked for, and saved from then on. */
    static LavaCycleData get(ServerLevel overworld, int[] firstLengths) {
        return overworld.getDataStorage().computeIfAbsent(t -> load(t, firstLengths), () -> {
            LavaCycleData d = new LavaCycleData(new LavaClock(overworld.getGameTime(), firstLengths));
            d.setDirty();   // the new cycle's start is state: left unsaved, the next server start would begin the cycle over
            return d;
        }, NAME);
    }

    private static LavaCycleData load(CompoundTag t, int[] fallback) {
        return new LavaCycleData(new LavaClock(t.getLong("cycleStart"), t.getIntArray("lengths"), t.getBoolean("frozen"),
                t.getInt("frozenAt"), t.getLong("cycles"), t.getInt("lengthOverride"), fallback));
    }

    @Override
    public CompoundTag save(CompoundTag t) {
        t.putInt("v", VERSION);
        t.putLong("cycleStart", clock.cycleStart);
        t.putIntArray("lengths", clock.lengths);
        t.putBoolean("frozen", clock.frozen);
        t.putInt("frozenAt", clock.frozenAt);
        t.putLong("cycles", clock.cycles);
        t.putInt("lengthOverride", clock.lengthOverride);
        return t;
    }
}
