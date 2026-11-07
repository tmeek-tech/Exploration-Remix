package com.explorationremix.rune.engine;

/** The four elements an action can carry (one each), and NONE. */
public enum Element {
    NONE("", 0xFFFFFF),
    EMBER("ember", 0xFF7A2E),
    FROST("frost", 0x9FE4FF),
    STORM("storm", 0xC9A8FF),
    TIDE("tide", 0x3F8CFF);

    /** The adjective the reading uses ("a frost burst"). */
    public final String word;
    /** The element's colour (RGB) for bolts, particles and the rune screen. */
    public final int colour;

    Element(String word, int colour) {
        this.word = word;
        this.colour = colour;
    }
}
