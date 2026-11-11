package com.explorationremix.rune.engine;

/**
 * The kinds of gear that carry a rune row: the weapons, which only ever cast when the player makes them (the cast key,
 * a loosed arrow). The rules never change between them; what changes is when the gear casts, where from, how it fills
 * up, and how many slots wake at each depth. There is no wand (the sword does that job) and shields never cast. Armor
 * will carry runes of its own, a different set from these.
 */
public enum Host {
    //            title       baseDelay recovery maxSlots  slots at home / Blue Reaches / Deep Gardens and below
    SWORD("sword", 5, 10, 5, new int[]{3, 4, 5}, false),
    BOW("bow", 0, 8, 4, new int[]{2, 3, 4}, true),
    CROSSBOW("crossbow", 0, 8, 4, new int[]{2, 3, 4}, true);

    public final String word;
    /** Ticks added to every cast's delay. */
    public final int baseDelay;
    /** Ticks added to the row's recovery. */
    public final int baseRecovery;
    /** Slots the gear has (diamond and netherite gear one more). */
    public final int maxSlots;
    private final int[] awake;
    /** Its own arrow is the first carrier of every cast. */
    public final boolean arrowCarrier;

    Host(String word, int baseDelay, int baseRecovery, int maxSlots, int[] awake, boolean arrowCarrier) {
        this.word = word;
        this.baseDelay = baseDelay;
        this.baseRecovery = baseRecovery;
        this.maxSlots = maxSlots;
        this.awake = awake;
        this.arrowCarrier = arrowCarrier;
    }

    /**
     * Slots awake at a run depth (0 home: the surface and the Upper Caves; 1 the Blue Reaches; 2 the Deep Gardens and
     * below), plus the gear's bonus slots (diamond and netherite gear one).
     */
    public int awakeSlots(int depth, int bonus) {
        int d = Math.max(0, Math.min(awake.length - 1, depth));
        return Math.min(maxSlots + bonus, awake[d] + bonus);
    }

    /** The run depth at which slot {@code index} (from 0) wakes, or -1 if it never does on this gear. */
    public int wakesAt(int index, int bonus) {
        for (int d = 0; d < awake.length; d++) if (index < awakeSlots(d, bonus)) return d;
        return -1;
    }
}
