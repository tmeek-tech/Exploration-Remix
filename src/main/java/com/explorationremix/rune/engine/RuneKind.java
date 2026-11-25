package com.explorationremix.rune.engine;

/**
 * Which socket a rune goes in. Every weapon has three: one active rune (what it casts), one modifier (how the active
 * is shaped: faster, heavier, three at once...) and one passive (what the weapon does all the time: an element on every
 * swing and arrow, a free cast every third hit...). Every piece of armor has three too: one effect rune, which belongs
 * to that piece (a helmet's Lantern, a pair of boots' Leap), and two collect sockets, for collect runes that wake once
 * three pieces carry the same one, with a bonus for all four. A tool (a pickaxe, an axe, a shovel, a hoe) has one, for
 * a tool rune: Haste, Bounty or Gentle.
 */
public enum RuneKind {
    ACTION("active"), MODIFIER("modifier"), PASSIVE("passive"), EFFECT("effect"), COLLECT("collect"), TOOL("tool");

    /** The socket's word ("the active socket"). */
    public final String word;

    RuneKind(String word) { this.word = word; }

    /** Whether runes of this kind go in armor (effect and collect). */
    public boolean armor() { return this == EFFECT || this == COLLECT; }

    /** Whether runes of this kind go in weapons (active, modifier and passive). */
    public boolean weapon() { return this == ACTION || this == MODIFIER || this == PASSIVE; }
}
