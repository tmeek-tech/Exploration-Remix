package com.explorationremix.rune.engine;

import java.util.List;

/**
 * One cast of a row, as read: its parts, what it costs and how long the gear waits after it.
 *
 * @param index     its place in the row's cycle (0 the first)
 * @param firstSlot the first slot of the row it covers
 * @param lastSlot  the last
 * @param parts     its parts, part 0 first
 * @param cost      energy, paid in full before it fires: every planned copy of every action and its modifiers
 * @param planned   actions it makes, counting copies, after the limit
 * @param wanted    actions it would make without the limit
 * @param delay     ticks before the gear's next cast
 * @param red       it costs more than the gear can ever hold: it is skipped
 */
public record CastReading(int index, int firstSlot, int lastSlot, List<Part> parts, int cost, int planned, int wanted, int delay, boolean red) {
    /** Whether the limit cut some copies. */
    public boolean cut() { return planned < wanted; }

    public Part part(int i) { return parts.get(i); }
}
