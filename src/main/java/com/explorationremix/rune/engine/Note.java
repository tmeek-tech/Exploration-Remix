package com.explorationremix.rune.engine;

/**
 * What the reading says about one slot.
 *
 * @param state  empty, live (it does something), grey (it does nothing and costs nothing) or asleep (not awake at this
 *               depth; kept, skipped)
 * @param reason why a slot is grey or asleep
 * @param arg    the reason's words: another rune's or action's name, a cap, the depth a slot wakes at
 * @param cast   the cast it belongs to (from 0), or -1
 * @param part   the part of that cast, or -1
 */
public record Note(State state, Reason reason, String arg, int cast, int part) {
    public enum State { EMPTY, LIVE, GREY, ASLEEP }

    public enum Reason {
        NONE,
        /** A modifier, element or Fan with no action to its right in its part. */
        NOTHING_RIGHT,
        /** It changes none of the actions to its right (arg: the first one's name). */
        CANT_CHANGE,
        /** It can't change the bow's arrow, and nothing else is to its right. */
        ARROW_CANT,
        /** More of one modifier than can count on an action (arg: the cap). */
        AT_CAP,
        /** Another element sits nearer every action it could change (arg: that element). */
        OVERRULED,
        /** A relay with no action before it to ride on. */
        NOTHING_BEFORE,
        /** A relay with no action after it. */
        NOTHING_AFTER,
        /** Past the deepest part a cast can have (arg: the most parts). */
        TOO_DEEP,
        /** Asleep at this depth (arg: the depth it wakes at, or -1 never). */
        ASLEEP
    }

    static final Note EMPTY = new Note(State.EMPTY, Reason.NONE, "", -1, -1);

    static Note live(int cast, int part) { return new Note(State.LIVE, Reason.NONE, "", cast, part); }

    static Note grey(Reason r, String arg) { return new Note(State.GREY, r, arg, -1, -1); }

    static Note asleep(int wakesAt) { return new Note(State.ASLEEP, Reason.ASLEEP, Integer.toString(wakesAt), -1, -1); }

    public boolean live() { return state == State.LIVE; }
}
