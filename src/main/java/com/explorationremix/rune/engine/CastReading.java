package com.explorationremix.rune.engine;

import java.util.List;

/**
 * A weapon's cast, as read: its parts (one; on a bow two, the arrow and the rune it carries), what it costs and how
 * long the weapon waits after it.
 *
 * @param index   always 0 (a weapon has one cast)
 * @param parts   its parts, part 0 first
 * @param cost    energy, paid in full before it fires: every copy of the action and its modifier
 * @param planned actions it makes, counting copies, after the limit
 * @param wanted  actions it would make without the limit
 * @param delay   ticks before the weapon can cast again (the recovery may be longer)
 * @param red     it costs more than the weapon can ever hold: it never fires
 */
public record CastReading(int index, List<Part> parts, int cost, int planned, int wanted, int delay, boolean red) {
    /** Whether the limit cut some copies. */
    public boolean cut() { return planned < wanted; }

    public Part part(int i) { return parts.get(i); }

    /** The active rune's act (on a bow, the one the arrow carries), or null for a bow's shaped arrow alone. */
    public Act active() {
        for (Part p : parts) for (Act a : p.acts()) if (a.rune != RuneId.ARROW) return a;
        return null;
    }
}
