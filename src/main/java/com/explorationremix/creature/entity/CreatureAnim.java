package com.explorationremix.creature.entity;

/** What the shared creature model needs to know to animate a creature. */
public interface CreatureAnim {
    /** 0 = normal. Creatures define their own states (hanging, burrowed, charging, roosting). */
    default int animState() { return 0; }
    default boolean isHanging() { return false; }
    default boolean isHidden() { return false; }
    default boolean isFlying() { return false; }
    default boolean isCharging() { return false; }
}
