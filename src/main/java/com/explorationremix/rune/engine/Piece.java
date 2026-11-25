package com.explorationremix.rune.engine;

/**
 * The four pieces of armor that carry runes, in the order the enchanting table shows them, helmet first. Each piece has
 * one effect socket, for an effect rune of its own (a helmet's Lantern, a pair of boots' Leap), and two collect sockets,
 * for collect runes, which fit any piece and wake once three pieces carry the same one (ArmorSet).
 */
public enum Piece {
    HELMET("helmet", "Helmet"), CHEST("chestplate", "Chestplate"), LEGS("leggings", "Leggings"), BOOTS("boots", "Boots");

    /** "helmet", "boots": the piece in a sentence. */
    public final String word;
    public final String title;

    Piece(String word, String title) {
        this.word = word;
        this.title = title;
    }

    /** The piece's bit in {@link RuneId#fits}. */
    public int fit() { return Fits.of(this); }

    /** "a helmet", "boots" (a pair of boots, and leggings, read without an article). */
    public String withArticle() { return this == HELMET || this == CHEST ? "a " + word : word; }
}
