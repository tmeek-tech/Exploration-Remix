package com.explorationremix.combat.swing;

/**
 * A light swing (tap the attack button), a strong attack (hold it), or a heavy jump attack (hold it in mid-air, Taran
 * 2026-11-25: the sword rises overhead and smashes down on the creature in front as you land). Strong and jump attacks
 * are the heavy ones: a longer cooldown follows them.
 */
public enum SwingKind {
    LIGHT, STRONG, JUMP;

    public static SwingKind of(int ordinal) { return ordinal == 2 ? JUMP : ordinal == 1 ? STRONG : LIGHT; }

    /** A strong attack or a jump attack: held, and followed by the heavy cooldown. */
    public boolean heavy() { return this != LIGHT; }
}
