package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A player's worn armor read as a set (Taran, 2026-11-25: "Armor should have one effect slot and two collect slots.
 * Runes should be specific to each piece. Collect runes should only activate if you collect three of a kind, with a
 * bonus for a full set."):
 * <ul>
 *   <li>Each piece's effect rune works while that piece is worn, when it is that piece's (a helmet's rune on boots does
 *   nothing). It works the same at every XP level: armor grows by its collect sets.</li>
 *   <li>A collect rune counts once on each piece that carries it. Three pieces carrying the same one wake it
 *   ({@link #WAKES}); all four make a full set ({@link #FULL}) and its bonus.</li>
 * </ul>
 * Pure: the same runes always read the same, on the server and in the player's own game.
 */
public final class ArmorSet {
    /** Pieces carrying one collect rune for it to wake, and for its full set. */
    public static final int WAKES = 3, FULL = 4;

    public static final ArmorSet NONE = new ArmorSet(new ArmorRunes[Piece.values().length]);

    private final ArmorRunes[] pieces;
    private final int[] counts = new int[RuneId.values().length];

    /** {@code pieces} by {@link Piece} order: null where nothing that takes runes is worn. */
    public ArmorSet(ArmorRunes[] pieces) {
        this.pieces = new ArmorRunes[Piece.values().length];
        for (int i = 0; i < this.pieces.length; i++) {
            ArmorRunes a = i < pieces.length ? pieces[i] : null;
            this.pieces[i] = a == null ? null : a.fitting(Piece.values()[i]);
        }
        for (ArmorRunes a : this.pieces) {
            if (a == null) continue;
            if (a.first() != null) counts[a.first().ordinal()]++;
            if (a.second() != null) counts[a.second().ordinal()]++;
        }
    }

    /** The runes on a worn piece (EMPTY with none), or null when nothing that takes runes is worn there. */
    public ArmorRunes piece(Piece p) { return pieces[p.ordinal()]; }

    /**
     * The set with {@code a} worn as {@code p}, in place of whatever is worn there (null: nothing worn there). The
     * enchanting table counts a piece of armor held in the hand so, as if it were put on (0.23.0).
     */
    public ArmorSet with(Piece p, ArmorRunes a) {
        ArmorRunes[] next = pieces.clone();
        next[p.ordinal()] = a;
        return new ArmorSet(next);
    }

    public boolean worn(Piece p) { return pieces[p.ordinal()] != null; }

    /** How many worn pieces carry collect rune {@code c} (each piece once). */
    public int count(RuneId c) { return c == null ? 0 : counts[c.ordinal()]; }

    /** Collect rune {@code c} is awake: three pieces carry it. */
    public boolean awake(RuneId c) { return count(c) >= WAKES; }

    /** Collect rune {@code c} is a full set: every piece carries it. */
    public boolean full(RuneId c) { return count(c) >= FULL; }

    /** 0 asleep, 1 awake, 2 a full set. */
    public int tier(RuneId c) { return full(c) ? 2 : awake(c) ? 1 : 0; }

    /** Every collect rune worn, in the rune list's order. */
    public List<RuneId> collects() {
        List<RuneId> out = new ArrayList<>();
        for (RuneId r : RuneId.ofKind(RuneKind.COLLECT)) if (count(r) > 0) out.add(r);
        return out;
    }

    /** The effect rune at work on this piece, or null. */
    public RuneId effect(Piece p) {
        ArmorRunes a = pieces[p.ordinal()];
        return a == null ? null : a.effect();
    }

    /** Whether effect rune {@code e} is at work: on its own piece, worn. */
    public boolean has(RuneId e) {
        if (e == null || e.kind != RuneKind.EFFECT) return false;
        for (Piece p : Piece.values()) if (effect(p) == e) return true;
        return false;
    }

    /**
     * What a socket of a worn piece does: empty, live (an effect rune at work, a collect rune awake) or grey (a collect
     * rune too few pieces carry yet: {@link Note.Reason#TOO_FEW}).
     */
    public Note note(Piece p, Socket s) {
        ArmorRunes a = pieces[p.ordinal()];
        RuneId r = a == null ? null : a.get(s);
        if (r == null) return Note.EMPTY;
        if (r.kind == RuneKind.COLLECT && !awake(r)) return Note.grey(Note.Reason.TOO_FEW, Integer.toString(count(r)));
        return Note.LIVE;
    }

    /** Whether nothing worn carries a rune. */
    public boolean isEmpty() {
        for (ArmorRunes a : pieces) if (a != null && !a.isEmpty()) return false;
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ArmorSet s && Arrays.equals(s.pieces, pieces);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(pieces); }

    @Override
    public String toString() { return Arrays.toString(pieces); }
}
