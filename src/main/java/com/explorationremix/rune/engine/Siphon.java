package com.explorationremix.rune.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * One cast's Siphon refunds. Hits by actions that carry Siphon on creatures that are fighting the caster pay energy
 * back, as shares of what the cast cost: each creature once, more for a weak spot, an element reaction or a kill. One
 * cast pays back at most {@link #CAP} of its cost, so careful play keeps gear casting and sloppy play drains it.
 */
public final class Siphon {
    public static final float PER_CREATURE = 0.15F, WEAK_SPOT = 0.40F, REACTION = 0.30F, KILL = 0.50F, CAP = 1.20F;

    private final float cost;
    private float paid;
    private final Set<Integer> creatures = new HashSet<>(), weak = new HashSet<>(), reacted = new HashSet<>(), killed = new HashSet<>();

    public Siphon(float cost) { this.cost = Math.max(0.0F, cost); }

    /** A first hit on a creature. Returns the energy to pay back now. */
    public float hit(int creature) { return creatures.add(creature) ? pay(PER_CREATURE) : 0.0F; }

    /** A hit on a creature's weak spot (a rearing Mossback's throat), once per creature. */
    public float weakSpot(int creature) { return weak.add(creature) ? pay(WEAK_SPOT) : 0.0F; }

    /** An element reaction set off on a creature, once per creature. */
    public float reaction(int creature) { return reacted.add(creature) ? pay(REACTION) : 0.0F; }

    /** A kill, once per creature. */
    public float kill(int creature) { return killed.add(creature) ? pay(KILL) : 0.0F; }

    public float paid() { return paid; }

    public float cost() { return cost; }

    private float pay(float share) {
        float room = CAP * cost - paid;
        float amount = Math.max(0.0F, Math.min(room, share * cost));
        paid += amount;
        return amount;
    }
}
