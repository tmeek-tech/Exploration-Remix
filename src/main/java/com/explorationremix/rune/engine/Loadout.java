package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * A weapon's runes: one in each of its three sockets, or none. Every rune is in the socket of its kind (an action in
 * the active socket, and so on); a rune of the wrong kind is never kept.
 */
public record Loadout(RuneId active, RuneId modifier, RuneId passive) {
    public static final Loadout EMPTY = new Loadout(null, null, null);

    public Loadout {
        if (active != null && active.kind != RuneKind.ACTION) active = null;
        if (modifier != null && modifier.kind != RuneKind.MODIFIER) modifier = null;
        if (passive != null && passive.kind != RuneKind.PASSIVE) passive = null;
    }

    /** The rune in a weapon's socket {@code s} (an armor socket: none). */
    public RuneId get(Socket s) {
        return switch (s) {
            case ACTIVE -> active;
            case MODIFIER -> modifier;
            case PASSIVE -> passive;
            default -> null;
        };
    }

    /**
     * The same runes with {@code r} in socket {@code s} (null empties it). A rune of another kind leaves it empty; an
     * armor socket changes nothing.
     */
    public Loadout with(Socket s, RuneId r) {
        return switch (s) {
            case ACTIVE -> new Loadout(r, modifier, passive);
            case MODIFIER -> new Loadout(active, r, passive);
            case PASSIVE -> new Loadout(active, modifier, r);
            default -> this;
        };
    }

    /** Only the runes that fit this gear ({@link Fits} bits): a sword's runes on a bow are not a bow's. */
    public Loadout fitting(int gear) {
        return new Loadout(active != null && active.fits(gear) ? active : null, modifier != null && modifier.fits(gear) ? modifier : null,
                passive != null && passive.fits(gear) ? passive : null);
    }

    /** The same runes with {@code r} in the socket of its kind. */
    public Loadout with(RuneId r) { return r == null ? this : with(Socket.of(r.kind), r); }

    public boolean isEmpty() { return active == null && modifier == null && passive == null; }

    /** The runes in it, active first. */
    public List<RuneId> runes() {
        List<RuneId> out = new ArrayList<>(3);
        if (active != null) out.add(active);
        if (modifier != null) out.add(modifier);
        if (passive != null) out.add(passive);
        return out;
    }

    /** Whether any socket holds this rune. */
    public boolean holds(RuneId r) { return r != null && (active == r || modifier == r || passive == r); }

    /**
     * A loadout from runes by id in any order ("spark swift ember"), each going to the socket of its kind; {@code _} or
     * {@code -} is nothing. Two runes of one kind are refused.
     */
    public static Loadout parse(String text) {
        Loadout l = EMPTY;
        for (String w : text.trim().split("[\\s,|\\[\\]]+")) {
            if (w.isEmpty() || w.equals("_") || w.equals("-")) continue;
            RuneId r = RuneId.byId(w);
            if (r == null) throw new IllegalArgumentException("unknown rune " + w);
            if (!r.kind.weapon()) throw new IllegalArgumentException(r.id + " is " + (r.kind.armor() ? "an armor" : "a tool") + " rune");
            Socket s = Socket.of(r.kind);
            if (l.get(s) != null) throw new IllegalArgumentException("two " + r.kind.word + " runes: " + l.get(s).id + " and " + r.id);
            l = l.with(s, r);
        }
        return l;
    }

    /** "spark swift ember", "_" for an empty socket. */
    @Override
    public String toString() {
        return (active == null ? "_" : active.id) + " " + (modifier == null ? "_" : modifier.id) + " " + (passive == null ? "_" : passive.id);
    }
}
