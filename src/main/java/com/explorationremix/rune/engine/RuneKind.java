package com.explorationremix.rune.engine;

/**
 * Which socket a rune goes in. Every weapon has three: one active rune (what it casts), one modifier (how the active
 * is shaped: faster, heavier, three at once...) and one passive (what the weapon does all the time: an element on every
 * swing and arrow, a light that goes with you, a free cast every third hit...).
 */
public enum RuneKind {
    ACTION("active"), MODIFIER("modifier"), PASSIVE("passive");

    /** The socket's word ("the active socket"). */
    public final String word;

    RuneKind(String word) { this.word = word; }
}
