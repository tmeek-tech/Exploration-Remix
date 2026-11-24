package com.explorationremix.rune.engine;

import java.util.Collections;
import java.util.List;

/**
 * Reads a weapon's three sockets:
 * <ul>
 *   <li>The active rune is the cast. Its second phase is awake while the player's XP level is the rune's level or more.</li>
 *   <li>The modifier changes the active rune, when it can (Swift on a Burst does nothing, and shows grey). On a bow or
 *   crossbow the cast rides the arrow: Swift, Seek, Pierce and Fan shape the arrow itself (Fan looses three, each
 *   carrying the rune); the others change the rune the arrow carries, or the arrow when they can't change the rune.
 *   A bow with a modifier and no active rune still shapes its arrows.</li>
 *   <li>An element passive is the element of the active rune too. On hit and Echo need an active rune (Echo one that
 *   strikes or lights: not Barrier, Dash or Reel); Lantern and Thirst always work.</li>
 * </ul>
 * The reading is pure: the same runes, weapon and level always read the same.
 */
public final class Reader {
    private Reader() {}

    public static Reading read(Loadout l, HostSpec spec, int level) { return read(l, spec, level, Limits.DEFAULT); }

    public static Reading read(Loadout l, HostSpec spec, int level, Limits lim) {
        RuneId a = l.active(), m = l.modifier(), p = l.passive();
        boolean bow = spec.host().arrowCarrier;
        Note[] notes = new Note[Socket.values().length];
        boolean empowered = a != null && level >= a.phaseLevel;
        notes[Socket.ACTIVE.ordinal()] = a == null ? Note.EMPTY : Note.LIVE;

        // ---- where the modifier goes: the active rune, or a bow's arrow
        boolean onArrow = false, onActive = false;
        if (m != null) {
            if (bow && m.shapesArrow()) onArrow = true;
            else if (a != null && m.changes(a)) onActive = true;
            else if (bow && m.changes(RuneId.ARROW)) onArrow = true;
            Note n;
            if (onArrow || onActive) n = Note.LIVE;
            else if (a == null) n = bow ? Note.grey(Note.Reason.ARROW_CANT, m.title) : Note.grey(Note.Reason.NO_ACTIVE, m.title);
            else n = Note.grey(Note.Reason.CANT_CHANGE, a.title);
            notes[Socket.MODIFIER.ordinal()] = n;
        } else {
            notes[Socket.MODIFIER.ordinal()] = Note.EMPTY;
        }

        // ---- the passive
        if (p == null) notes[Socket.PASSIVE.ordinal()] = Note.EMPTY;
        else if ((p == RuneId.ON_HIT || p == RuneId.AFTER) && a == null) notes[Socket.PASSIVE.ordinal()] = Note.grey(Note.Reason.NO_ACTIVE, p.title);
        else if (p == RuneId.AFTER && !a.echoes()) notes[Socket.PASSIVE.ordinal()] = Note.grey(Note.Reason.CANT_ECHO, a.title);
        else notes[Socket.PASSIVE.ordinal()] = Note.LIVE;
        Element el = p == null ? Element.NONE : p.element;

        // ---- the cast
        int[] activeMods = new int[Act.MODS.length], arrowMods = new int[Act.MODS.length];
        int activeCopies = 1, arrowCopies = 1;
        if (m == RuneId.FAN) {
            if (onArrow) arrowCopies = 3;
            else if (onActive) activeCopies = 3;
        } else if (m != null && (onArrow || onActive)) {
            (onArrow ? arrowMods : activeMods)[Act.modIndex(m)] = 1;
        }
        List<Part> parts;
        if (bow) {
            if (a == null && !onArrow) return new Reading(l, spec, level, List.of(), notes, recovery(spec, a, m, false), false);
            Act arrow = new Act(RuneId.ARROW, -1, 0, arrowMods, el, arrowCopies, false);
            Part first = new Part(null, -1, List.of(arrow));
            parts = a == null ? List.of(first)
                    : List.of(first, new Part(RuneId.ON_HIT, -1, List.of(new Act(a, 0, 1, activeMods, el, activeCopies, empowered))));
        } else {
            if (a == null) return new Reading(l, spec, level, List.of(), notes, recovery(spec, null, m, false), false);
            parts = List.of(new Part(null, -1, List.of(new Act(a, 0, 0, activeMods, el, activeCopies, empowered))));
        }
        boolean modLive = onArrow || onActive;
        Plan.Counts counts = Plan.count(parts, lim);
        int delay = spec.host().baseDelay + (a == null ? 0 : a.delay) + (modLive ? m.delay : 0);
        delay = Math.max(bow ? 0 : 2, delay);
        boolean red = counts.cost() > spec.maxEnergy();
        CastReading cast = new CastReading(0, parts, counts.cost(), counts.planned(), counts.wanted(), delay, red);
        return new Reading(l, spec, level, List.of(cast), notes, recovery(spec, a, m, modLive), empowered);
    }

    /** The wait after a cast: the weapon's own, the active rune's and a live modifier's, from 6 to 80 ticks. */
    private static int recovery(HostSpec spec, RuneId a, RuneId m, boolean modLive) {
        int r = spec.host().baseRecovery + (a == null ? 0 : a.recovery) + (modLive && m != null ? m.recovery : 0);
        return Math.max(6, Math.min(80, r));
    }

    /** An empty reading (no runes). */
    public static Reading empty(HostSpec spec, int level) {
        return new Reading(Loadout.EMPTY, spec, level, Collections.emptyList(),
                new Note[]{Note.EMPTY, Note.EMPTY, Note.EMPTY}, recovery(spec, null, null, false), false);
    }
}
