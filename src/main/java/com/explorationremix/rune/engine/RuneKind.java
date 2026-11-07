package com.explorationremix.rune.engine;

/**
 * What a rune is in a row. Actions make something happen; modifiers and elements change the actions to their right;
 * relays hand the rest of the row to what came before them; Fan copies what follows it in its part.
 */
public enum RuneKind {
    ACTION, MODIFIER, ELEMENT, RELAY, FAN
}
