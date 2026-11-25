package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * A piece of armor's runes: an effect rune in its effect socket, and a collect rune in each of its two collect sockets,
 * or none. Every rune is in a socket of its kind; a rune of the wrong kind is never kept, and nor is a collect rune
 * twice (it counts once on a piece, so the second would do nothing).
 */
public record ArmorRunes(RuneId effect, RuneId first, RuneId second) {
    public static final ArmorRunes EMPTY = new ArmorRunes(null, null, null);

    public ArmorRunes {
        if (effect != null && effect.kind != RuneKind.EFFECT) effect = null;
        if (first != null && first.kind != RuneKind.COLLECT) first = null;
        if (second != null && (second.kind != RuneKind.COLLECT || second == first)) second = null;
    }

    /** The rune in an armor socket {@code s} (a weapon's socket: none). */
    public RuneId get(Socket s) {
        return switch (s) {
            case EFFECT -> effect;
            case COLLECT_1 -> first;
            case COLLECT_2 -> second;
            default -> null;
        };
    }

    /** The same runes with {@code r} in socket {@code s} (null empties it); a weapon's socket changes nothing. */
    public ArmorRunes with(Socket s, RuneId r) {
        return switch (s) {
            case EFFECT -> new ArmorRunes(r, first, second);
            case COLLECT_1 -> new ArmorRunes(effect, r, second == r ? null : second);
            case COLLECT_2 -> new ArmorRunes(effect, first == r ? null : first, r);
            default -> this;
        };
    }

    /** The same runes with {@code r} in a socket of its kind: a collect rune in the first empty collect socket. */
    public ArmorRunes with(RuneId r) {
        if (r == null) return this;
        if (r.kind == RuneKind.EFFECT) return with(Socket.EFFECT, r);
        if (r.kind != RuneKind.COLLECT || r == first || r == second) return this;
        return with(first == null || second != null ? Socket.COLLECT_1 : Socket.COLLECT_2, r);
    }

    /** Only the runes that fit this piece (an effect rune of another piece's is left out). */
    public ArmorRunes fitting(Piece p) {
        return new ArmorRunes(effect != null && effect.fits(p) ? effect : null, first, second);
    }

    public boolean isEmpty() { return effect == null && first == null && second == null; }

    /** Whether either collect socket holds {@code c}. */
    public boolean collects(RuneId c) { return c != null && (first == c || second == c); }

    /** The runes in it, the effect rune first. */
    public List<RuneId> runes() {
        List<RuneId> out = new ArrayList<>(3);
        if (effect != null) out.add(effect);
        if (first != null) out.add(first);
        if (second != null) out.add(second);
        return out;
    }

    /**
     * A piece's runes from ids in any order ("leap mend vigor"), each going to a socket of its kind; {@code _} or
     * {@code -} is nothing. Two effect runes, three collect runes or one collect rune twice are refused.
     */
    public static ArmorRunes parse(String text) {
        ArmorRunes a = EMPTY;
        for (String w : text.trim().split("[\\s,|\\[\\]]+")) {
            if (w.isEmpty() || w.equals("_") || w.equals("-")) continue;
            RuneId r = RuneId.byId(w);
            if (r == null) throw new IllegalArgumentException("unknown rune " + w);
            if (!r.kind.armor()) throw new IllegalArgumentException(r.id + " is a weapon rune");
            if (r.kind == RuneKind.EFFECT && a.effect != null) throw new IllegalArgumentException("two effect runes: " + a.effect.id + " and " + r.id);
            if (r.kind == RuneKind.COLLECT && (a.collects(r) || (a.first != null && a.second != null))) {
                throw new IllegalArgumentException(a.collects(r) ? r.id + " twice" : "three collect runes");
            }
            a = a.with(r);
        }
        return a;
    }

    /** "leap mend vigor", "_" for an empty socket. */
    @Override
    public String toString() {
        return (effect == null ? "_" : effect.id) + " " + (first == null ? "_" : first.id) + " " + (second == null ? "_" : second.id);
    }
}
