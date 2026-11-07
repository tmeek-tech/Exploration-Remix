package com.explorationremix.rune.effect;

/** What a Field does on the server each tick: who it touches, what it lets go. */
public interface FieldLogic {
    void tick(RuneField field);

    /** The field is gone from the world. */
    default void removed(RuneField field) {}
}
