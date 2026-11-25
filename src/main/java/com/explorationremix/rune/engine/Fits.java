package com.explorationremix.rune.engine;

/**
 * Which gear a rune can be set into, as bits ({@link RuneId#fits}): swords, bows (a crossbow takes a bow's runes), each
 * piece of armor, and tools (pickaxes, axes, shovels and hoes, alike). Sword actives fit swords only and bow actives
 * bows only; modifiers and most passives fit both; an effect rune fits its one piece of armor; collect runes fit every
 * piece; tool runes fit every tool.
 */
public final class Fits {
    public static final int SWORD = 1, BOW = 2, HELMET = 4, CHEST = 8, LEGS = 16, BOOTS = 32, TOOL = 64;
    public static final int WEAPONS = SWORD | BOW, ARMOR = HELMET | CHEST | LEGS | BOOTS;

    private Fits() {}

    /** A weapon's bit: a crossbow takes what a bow takes. */
    public static int of(Host h) { return h == Host.SWORD ? SWORD : BOW; }

    public static int of(Piece p) { return HELMET << p.ordinal(); }

    /**
     * What a rune that fits {@code fits} goes on, in words, for "Spark is a sword rune" and the like: "sword", "bow",
     * "helmet", "armor", "weapon", "tool".
     */
    public static String word(int fits) {
        if (fits == TOOL) return "tool";
        if (fits == SWORD) return "sword";
        if (fits == BOW) return "bow";
        if (fits == WEAPONS) return "weapon";
        if (fits == ARMOR) return "armor";
        for (Piece p : Piece.values()) if (fits == of(p)) return p.word;
        return "gear";
    }
}
