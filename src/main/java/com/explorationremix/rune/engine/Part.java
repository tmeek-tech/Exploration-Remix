package com.explorationremix.rune.engine;

import java.util.List;

/**
 * A part of a cast: everything between two relays, which fires together. Part 0 fires when the gear casts; each later
 * part rides on every action of the part before it and is released there when its relay's moment comes.
 *
 * @param relay     the relay that releases this part (null for part 0)
 * @param relaySlot that relay's slot; -1 for part 0, and for a bow's implicit "on hit" after its arrow
 */
public record Part(RuneId relay, int relaySlot, List<Act> acts) {
    /** Copies of actions this part makes on each carrier of the part before it. */
    public int instances() {
        int n = 0;
        for (Act a : acts) n += a.copies;
        return n;
    }
}
