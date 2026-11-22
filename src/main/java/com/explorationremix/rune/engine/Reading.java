package com.explorationremix.rune.engine;

import java.util.List;

/**
 * A weapon's runes read for one player: its cast (none, or one), a note for every socket, the recovery after the cast,
 * and whether the active rune's second phase is awake at the player's XP level. Pure data; {@link Explain} puts it in
 * words.
 *
 * @param level     the XP level it was read at
 * @param casts     the cast, if the weapon has one (an active rune, or on a bow a modifier that shapes its arrows)
 * @param recovery  ticks the weapon waits after a cast (the longer of this and the cast's delay)
 * @param empowered the active rune's second phase is awake
 */
public record Reading(Loadout loadout, HostSpec spec, int level, List<CastReading> casts, Note[] notes, int recovery, boolean empowered) {
    public Note note(Socket s) { return notes[s.ordinal()]; }

    /** The cast, or null. */
    public CastReading cast() { return casts.isEmpty() ? null : casts.get(0); }

    public CastReading cast(int i) { return i >= 0 && i < casts.size() ? casts.get(i) : null; }

    /** Whether it can ever cast (it has a cast, and the cast is not dearer than the weapon holds). */
    public boolean castable() {
        CastReading c = cast();
        return c != null && !c.red();
    }

    public RuneId active() { return loadout.active(); }

    public RuneId modifier() { return loadout.modifier(); }

    public RuneId passive() { return loadout.passive(); }

    /** The weapon's element (its element passive), or NONE. */
    public Element element() { return loadout.passive() == null ? Element.NONE : loadout.passive().element; }

    /** Whether this passive is set and does its work. */
    public boolean passiveIs(RuneId p) { return loadout.passive() == p && note(Socket.PASSIVE).live(); }

    /** Levels still to gain before the second phase wakes (0 when it is awake, or there is no active rune). */
    public int levelsToGo() {
        RuneId a = loadout.active();
        return a == null ? 0 : Math.max(0, a.phaseLevel - level);
    }
}
