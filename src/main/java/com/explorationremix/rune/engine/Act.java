package com.explorationremix.rune.engine;

/**
 * One action in a cast's part, with everything that changes it: the weapon's modifier (if it changes this action), its
 * element (the weapon's element passive), how many copies Fan makes of it, and whether its second phase is awake.
 */
public final class Act {
    /** The modifiers that change an action's numbers, in the order {@link #mods} counts them (Fan makes copies instead). */
    public static final RuneId[] MODS = {RuneId.SWIFT, RuneId.HEAVY, RuneId.WIDE, RuneId.SEEK, RuneId.ARC, RuneId.BOUNCE, RuneId.PIERCE, RuneId.SIPHON};

    public final RuneId rune;
    /**
     * Which rune this is, for "one rune strikes a creature once per cast": 0 the active, -1 a bow's own arrow, and
     * the second phases' own strikes their own numbers (an aftershock strikes what the burst struck).
     */
    public final int slot;
    /** The part of the cast it belongs to (0 the first). */
    public final int part;
    private final int[] mods;
    public final Element element;
    /** 1, or 3 when Fan copies it. */
    public final int copies;
    /** Its second phase is awake (the player's XP level is the rune's level or more). */
    public final boolean empowered;
    /** The energy one copy costs: the action and the modifier on it. */
    public final int unitCost;
    /**
     * What each arrow past the first costs when Fan copies a bow's or crossbow's own arrow: the loosed arrow is free (it
     * is the shot), the extra ones are made by the runes.
     */
    public static final int ARROW_COPY = 6;

    public Act(RuneId rune, int slot, int part, int[] mods, Element element, int copies, boolean empowered) {
        this.rune = rune;
        this.slot = slot;
        this.part = part;
        this.mods = mods.clone();
        this.element = element;
        this.copies = copies;
        this.empowered = empowered;
        int c = rune.cost;
        for (int i = 0; i < MODS.length; i++) c += this.mods[i] * MODS[i].cost;
        this.unitCost = c;
    }

    /** The same action, struck under another number (a second phase's own hits, which may strike what it struck). */
    public Act withSlot(int s) { return new Act(rune, s, part, mods, element, 1, empowered); }

    /** The same action in its first phase, one copy (what a second phase makes of it: a splinter, a fork). */
    public Act plain(int s) { return new Act(rune, s, part, mods, element, 1, false); }

    /** What copy {@code copy} (0 the middle one) costs: {@link #unitCost}, and for an extra arrow {@link #ARROW_COPY} more. */
    public int copyCost(int copy) { return unitCost + (rune == RuneId.ARROW && copy > 0 ? ARROW_COPY : 0); }

    public static int modIndex(RuneId m) {
        for (int i = 0; i < MODS.length; i++) if (MODS[i] == m) return i;
        return -1;
    }

    /** How many of modifier {@code m} count on this action (0 or 1). */
    public int count(RuneId m) {
        int i = modIndex(m);
        return i < 0 ? 0 : mods[i];
    }

    public boolean has(RuneId m) { return count(m) > 0; }

    public int[] modCounts() { return mods.clone(); }

    /** The one modifier on it, or null. */
    public RuneId modifier() {
        for (int i = 0; i < MODS.length; i++) if (mods[i] > 0) return MODS[i];
        return copies > 1 ? RuneId.FAN : null;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        if (copies > 1) b.append(copies).append('x');
        for (int i = 0; i < MODS.length; i++) for (int k = 0; k < mods[i]; k++) b.append(MODS[i].id).append('+');
        if (element != Element.NONE) b.append(element.word).append('+');
        b.append(rune.id);
        if (empowered) b.append(" II");
        return b.toString();
    }
}
