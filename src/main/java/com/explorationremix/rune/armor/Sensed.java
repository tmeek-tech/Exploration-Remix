package com.explorationremix.rune.armor;

import java.util.Arrays;

/**
 * The creatures this game's player senses hunting them (Sense, on the helmet), as the server last said
 * ({@link com.explorationremix.rune.net.RuneNet.SenseSync}): they are drawn glowing, in the armor's light blue, through
 * walls. The server says again every second while any are sensed; if it goes quiet (the world left, the helmet gone
 * and the word lost) they stop glowing after a few seconds. Kept here, with no client classes, so the message can be
 * read on either side; only the client game's threads touch it.
 */
public final class Sensed {
    /** How long a word from the server holds (ms): it says again at least every second while any are sensed. */
    static final long HOLDS = 2500;

    private static volatile int[] ids = new int[0];
    private static volatile long at;

    private Sensed() {}

    /** The server's word: these entity ids, sorted. */
    public static void set(int[] sorted) {
        ids = sorted.clone();
        at = System.currentTimeMillis();
    }

    /** Whether the creature with this id is sensed now. */
    public static boolean has(int id) {
        int[] now = ids;
        if (now.length == 0 || System.currentTimeMillis() - at > HOLDS) return false;
        return Arrays.binarySearch(now, id) >= 0;
    }

    public static void clear() {
        ids = new int[0];
        at = 0;
    }
}
