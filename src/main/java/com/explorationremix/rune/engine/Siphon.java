package com.explorationremix.rune.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * One cast's refunds: what its hits on creatures fighting the caster knock loose as motes, as shares of what the cast
 * cost. Every rune hit knocks a little loose; an action carrying Siphon knocks loose much more, most for a weak spot,
 * an element reaction or a kill. Each creature counts once for each of these, once with Siphon and once without. One
 * cast pays back at most {@link #PLAIN_CAP} of its cost without Siphon, {@link #CAP} in all, so runes alone never keep
 * gear casting (hits with the gear itself do that), and careful Siphon play just about can.
 */
public final class Siphon {
    /** Shares with Siphon. */
    public static final float PER_CREATURE = 0.15F, WEAK_SPOT = 0.40F, REACTION = 0.30F, KILL = 0.50F, CAP = 1.20F;
    /** Shares without it. */
    public static final float PLAIN_CREATURE = 0.10F, PLAIN_WEAK_SPOT = 0.15F, PLAIN_REACTION = 0.10F, PLAIN_KILL = 0.15F, PLAIN_CAP = 0.40F;

    private final float cost;
    private float paid, plainPaid;
    private final Set<Long> creatures = new HashSet<>(), weak = new HashSet<>(), reacted = new HashSet<>(), killed = new HashSet<>();

    public Siphon(float cost) { this.cost = Math.max(0.0F, cost); }

    /** A first hit on a creature by an action with ({@code siphon}) or without Siphon. Returns the energy to knock loose. */
    public float hit(int creature, boolean siphon) { return creatures.add(key(creature, siphon)) ? pay(siphon ? PER_CREATURE : PLAIN_CREATURE, siphon) : 0.0F; }

    /** A hit on a creature's weak spot (a rearing Mossback's throat), once per creature. */
    public float weakSpot(int creature, boolean siphon) { return weak.add(key(creature, siphon)) ? pay(siphon ? WEAK_SPOT : PLAIN_WEAK_SPOT, siphon) : 0.0F; }

    /** An element reaction set off on a creature, once per creature. */
    public float reaction(int creature, boolean siphon) { return reacted.add(key(creature, siphon)) ? pay(siphon ? REACTION : PLAIN_REACTION, siphon) : 0.0F; }

    /** A kill, once per creature. */
    public float kill(int creature, boolean siphon) { return killed.add(key(creature, siphon)) ? pay(siphon ? KILL : PLAIN_KILL, siphon) : 0.0F; }

    /** A first hit with Siphon. */
    public float hit(int creature) { return hit(creature, true); }

    public float weakSpot(int creature) { return weakSpot(creature, true); }

    public float reaction(int creature) { return reaction(creature, true); }

    public float kill(int creature) { return kill(creature, true); }

    public float paid() { return paid; }

    public float cost() { return cost; }

    private static long key(int creature, boolean siphon) { return (siphon ? 1L << 32 : 0L) | (creature & 0xFFFFFFFFL); }

    /**
     * Pays a share, within the caps: the whole cast's refunds stay under {@link #CAP}, and those without Siphon under
     * {@link #PLAIN_CAP} too.
     */
    private float pay(float share, boolean siphon) {
        float room = CAP * cost - paid;
        if (!siphon) room = Math.min(room, PLAIN_CAP * cost - plainPaid);
        float amount = Math.max(0.0F, Math.min(room, share * cost));
        paid += amount;
        if (!siphon) plainPaid += amount;
        return amount;
    }
}
