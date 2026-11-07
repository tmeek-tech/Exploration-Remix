package com.explorationremix.rune.engine;

/**
 * One action in a cast's part, with everything that changes it: the modifiers to its left in its part (nearest first,
 * up to each kind's cap), its element (the nearest), and how many copies Fan makes of it.
 */
public final class Act {
    /** The modifiers, in the order {@link #mods} counts them. */
    public static final RuneId[] MODS = {RuneId.SWIFT, RuneId.HEAVY, RuneId.WIDE, RuneId.SEEK, RuneId.ARC, RuneId.BOUNCE, RuneId.PIERCE, RuneId.SIPHON};

    public final RuneId rune;
    /** Its slot in the row; -1 for a bow's own arrow. */
    public final int slot;
    /** The part of the cast it belongs to (0 the first). */
    public final int part;
    private final int[] mods;
    public final Element element;
    /** 1, or 3 and 5 when one or two Fans copy it. */
    public final int copies;
    /** The energy one copy costs: the action, each modifier on it, and its element. */
    public final int unitCost;

    Act(RuneId rune, int slot, int part, int[] mods, Element element, int copies) {
        this.rune = rune;
        this.slot = slot;
        this.part = part;
        this.mods = mods.clone();
        this.element = element;
        this.copies = copies;
        int c = rune.cost;
        for (int i = 0; i < MODS.length; i++) c += mods[i] * MODS[i].cost;
        if (element != Element.NONE) c += RuneId.EMBER.cost;
        this.unitCost = c;
    }

    public static int modIndex(RuneId m) {
        for (int i = 0; i < MODS.length; i++) if (MODS[i] == m) return i;
        return -1;
    }

    /** How many of modifier {@code m} count on this action (0 to its cap). */
    public int count(RuneId m) {
        int i = modIndex(m);
        return i < 0 ? 0 : mods[i];
    }

    public boolean has(RuneId m) { return count(m) > 0; }

    public int[] modCounts() { return mods.clone(); }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        if (copies > 1) b.append(copies).append('x');
        for (int i = 0; i < MODS.length; i++) for (int k = 0; k < mods[i]; k++) b.append(MODS[i].id).append('+');
        if (element != Element.NONE) b.append(element.word).append('+');
        return b.append(rune.id).toString();
    }
}
