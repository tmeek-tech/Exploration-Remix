package com.explorationremix.rune.engine;

import java.util.Arrays;

/**
 * A gear's rune row: its slots left to right (null where empty) and its cast breaks. Bit {@code i} of {@code breaks}
 * is a break between slot {@code i} and slot {@code i + 1}. Breaks are free: they are not runes and take no slot.
 */
public final class Row {
    private final RuneId[] slots;
    private final int breaks;

    public Row(RuneId[] slots, int breaks) {
        this.slots = slots.clone();
        int mask = slots.length <= 1 ? 0 : (1 << (slots.length - 1)) - 1;
        this.breaks = breaks & mask;
    }

    /** A row of the given runes (nulls for empty slots) and no breaks. */
    public static Row of(RuneId... slots) { return new Row(slots, 0); }

    /** An empty row of {@code n} slots. */
    public static Row empty(int n) { return new Row(new RuneId[n], 0); }

    public int size() { return slots.length; }

    public RuneId get(int i) { return i >= 0 && i < slots.length ? slots[i] : null; }

    public int breaks() { return breaks; }

    public boolean breakAfter(int i) { return i >= 0 && i < slots.length - 1 && (breaks & (1 << i)) != 0; }

    public RuneId[] slots() { return slots.clone(); }

    public boolean isEmpty() {
        for (RuneId r : slots) if (r != null) return false;
        return true;
    }

    public int runeCount() {
        int n = 0;
        for (RuneId r : slots) if (r != null) n++;
        return n;
    }

    public Row with(int i, RuneId r) {
        RuneId[] s = slots.clone();
        s[i] = r;
        return new Row(s, breaks);
    }

    public Row withBreak(int i, boolean on) {
        if (i < 0 || i >= slots.length - 1) return this;
        return new Row(slots, on ? breaks | (1 << i) : breaks & ~(1 << i));
    }

    /** The same runes and breaks in a row of {@code n} slots (runes past the end are dropped: callers move them out first). */
    public Row resized(int n) { return new Row(Arrays.copyOf(slots, n), breaks); }

    /** A short text form for tests and logs: {@code [spark][on_hit][burst] | [orb]}. */
    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            b.append('[').append(slots[i] == null ? "" : slots[i].id).append(']');
            if (breakAfter(i)) b.append(" | ");
        }
        return b.toString();
    }

    /**
     * Parses the text form: runes by id in brackets or separated by spaces, {@code _} or {@code -} for an empty slot,
     * {@code |} for a break. "spark on_hit burst | orb".
     */
    public static Row parse(String text) {
        String t = text.replace("][", "] [").replace("[", " ").replace("]", " ").replace("|", " | ");
        String[] words = t.trim().split("\\s+");
        RuneId[] out = new RuneId[words.length];
        int n = 0, mask = 0;
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (w.equals("|")) {
                if (n > 0) mask |= 1 << (n - 1);
                continue;
            }
            if (w.equals("_") || w.equals("-")) { out[n++] = null; continue; }
            RuneId r = RuneId.byId(w);
            if (r == null) throw new IllegalArgumentException("unknown rune " + w);
            out[n++] = r;
        }
        return new Row(Arrays.copyOf(out, n), mask);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Row r && r.breaks == breaks && Arrays.equals(r.slots, slots);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(slots) * 31 + breaks; }
}
