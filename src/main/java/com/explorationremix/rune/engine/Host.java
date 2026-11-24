package com.explorationremix.rune.engine;

/**
 * The kinds of weapon that carry runes, which only ever cast when the player makes them (the cast key, a loosed
 * arrow). The rules never change between them; what changes is when the weapon casts, where from, and how it fills up.
 * There is no wand (the sword does that job) and shields never cast. Armor will carry runes of its own, a different
 * set from these.
 */
public enum Host {
    //            title       baseDelay recovery
    SWORD("sword", 5, 10, false),
    BOW("bow", 0, 8, true),
    CROSSBOW("crossbow", 0, 8, true);

    public final String word;
    /** Ticks added to the cast's delay. */
    public final int baseDelay;
    /** Ticks added to the recovery. */
    public final int baseRecovery;
    /** Its own arrow is the first carrier of every cast. */
    public final boolean arrowCarrier;

    Host(String word, int baseDelay, int baseRecovery, boolean arrowCarrier) {
        this.word = word;
        this.baseDelay = baseDelay;
        this.baseRecovery = baseRecovery;
        this.arrowCarrier = arrowCarrier;
    }
}
