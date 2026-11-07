package com.explorationremix.rune.engine;

/**
 * The few fixed limits the reading obeys (server settings in the game).
 *
 * @param maxParts   parts in one cast (relays deep + 1); deeper parts are grey, "too deep"
 * @param maxActions actions in one cast, counting copies; whole extra copies are cut and not paid for
 * @param idleTicks  after this long without casting, the gear goes back to its first cast
 */
public record Limits(int maxParts, int maxActions, int idleTicks) {
    public static final Limits DEFAULT = new Limits(4, 16, 60);
}
