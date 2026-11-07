package com.explorationremix.rune.effect;

/** What a flying rune does on the server each tick: where it goes, what it hits, what it lets go. */
public interface BoltLogic {
    void tick(RuneBolt bolt);

    /** The bolt is gone from the world (hit, timed out, its chunk unloaded). */
    default void removed(RuneBolt bolt) {}
}
