package com.explorationremix.rune.engine;

/**
 * What the reading says about one socket.
 *
 * @param state  empty, live (it does something) or grey (it does nothing, and costs nothing)
 * @param reason why a socket is grey
 * @param arg    the reason's words: the active rune's name
 */
public record Note(State state, Reason reason, String arg) {
    public enum State { EMPTY, LIVE, GREY }

    public enum Reason {
        NONE,
        /** A modifier, On hit or Echo with no active rune to work on. */
        NO_ACTIVE,
        /** The modifier doesn't change the active rune (arg: the active rune's name). */
        CANT_CHANGE,
        /** On a bow or crossbow with no active rune: the modifier doesn't change an arrow. */
        ARROW_CANT,
        /** Echo with an active rune it can't repeat (a wall, a throw, a pull; arg: the active rune's name). */
        CANT_ECHO
    }

    static final Note EMPTY = new Note(State.EMPTY, Reason.NONE, "");
    static final Note LIVE = new Note(State.LIVE, Reason.NONE, "");

    static Note grey(Reason r, String arg) { return new Note(State.GREY, r, arg); }

    public boolean live() { return state == State.LIVE; }

    public boolean grey() { return state == State.GREY; }
}
