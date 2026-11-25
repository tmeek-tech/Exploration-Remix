package com.explorationremix.rune.engine;

/**
 * What the reading says about one socket.
 *
 * @param state  empty, live (it does something) or grey (it does nothing, and costs nothing)
 * @param reason why a socket is grey
 * @param arg    the reason's words: the active rune's name, the gear's word, or a count
 */
public record Note(State state, Reason reason, String arg) {
    public enum State { EMPTY, LIVE, GREY }

    public enum Reason {
        NONE,
        /** A modifier, On hit or Overflow with no active rune to work on. */
        NO_ACTIVE,
        /** The modifier doesn't change the active rune (arg: the active rune's name). */
        CANT_CHANGE,
        /** On a bow or crossbow with no active rune: the modifier doesn't change an arrow. */
        ARROW_CANT,
        /** Overflow on a sword whose active rune strikes nothing it could make stronger (a wall, a throw, a pull; arg: its name). */
        CANT_STRENGTHEN,
        /** A rune that doesn't fit this gear (a sword's active on a bow; arg: the gear's word). */
        WRONG_GEAR,
        /** A collect rune too few worn pieces carry to wake yet (arg: how many do). */
        TOO_FEW
    }

    static final Note EMPTY = new Note(State.EMPTY, Reason.NONE, "");
    static final Note LIVE = new Note(State.LIVE, Reason.NONE, "");

    static Note grey(Reason r, String arg) { return new Note(State.GREY, r, arg); }

    public boolean live() { return state == State.LIVE; }

    public boolean grey() { return state == State.GREY; }
}
