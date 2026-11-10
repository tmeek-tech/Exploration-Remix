package com.explorationremix.rune.engine;

/**
 * What an action is once cast, which decides how it carries the part after a relay:
 * <ul>
 *   <li>BOLT: a flying thing (Spark, Shard, Orb, Glow, and a bow's arrow); releases its payload where it hits, or
 *   after a moment wherever it has got to (and goes off).</li>
 *   <li>FIELD: a lingering patch; releases its payload where it first touches a creature, or after a moment at its
 *   middle.</li>
 *   <li>SELF: you, while you dash; releases when you hit a creature or a wall, or after a moment where you are.</li>
 *   <li>INSTANT: happens on the spot (Burst, Cleave, Barrier, Reel) and releases its payload at once, there.</li>
 * </ul>
 */
public enum Shape {
    BOLT, FIELD, SELF, INSTANT;

    /** Whether it carries a payload until a moment comes, rather than handing it on at once. */
    public boolean carries() { return this != INSTANT; }
}
