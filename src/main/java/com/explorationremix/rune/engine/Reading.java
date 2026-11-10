package com.explorationremix.rune.engine;

import java.util.List;

/**
 * A row read for one piece of gear at one depth: its casts in order, a note for every slot, and the recovery after
 * the last cast. Pure data; {@link Explain} puts it in words.
 *
 * @param awake    slots awake at this depth
 * @param recovery ticks the gear waits after its last cast (the longer of this and that cast's delay)
 */
public record Reading(Row row, HostSpec spec, int awake, List<CastReading> casts, Note[] notes, int recovery) {
    public Note note(int slot) { return notes[slot]; }

    /** Whether any cast can ever fire (has actions and is not red). */
    public boolean castable() {
        for (CastReading c : casts) if (!c.red()) return true;
        return false;
    }

    public CastReading cast(int i) { return i >= 0 && i < casts.size() ? casts.get(i) : null; }
}
