package com.explorationremix.combat.swing;

/** A light swing (tap the attack button) or a strong attack (hold it). */
public enum SwingKind {
    LIGHT, STRONG;

    public static SwingKind of(int ordinal) { return ordinal == 1 ? STRONG : LIGHT; }
}
