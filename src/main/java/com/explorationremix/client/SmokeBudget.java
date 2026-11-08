package com.explorationremix.client;

import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * How many puffs of volcanic smoke are alive, so the plumes stay within the client's cost: each puff is counted until
 * the game time its life ends (the particle engine never says when it drops one, so a puff it dropped early still
 * counts until then). When the count is full the plumes go short whole, not gappy, the far ones first ({@link #admit}).
 * One client thread only. Public, as the eruptions' client thins its far particles by {@link #share}.
 */
public final class SmokeBudget {
    /** At most this many puffs at once, at the "All" particle setting (they share the translucent sheet, 16384). */
    static final int MAX = 1800;
    /** The share of the count the far plumes may fill, so the near ones always find room. */
    static final double FAR_SHARE = 0.9;
    /** How long a plume rests once the count turns it away, ticks. */
    static final int REST = 600;

    private static final PriorityQueue<Long> ENDS = new PriorityQueue<>();
    /** The plumes resting, by cell, until when. */
    private static final Map<Long, Long> RESTING = new HashMap<>();

    private SmokeBudget() {}

    static void add(long end) { ENDS.add(end); }

    /** Puffs alive at game time {@code now}. */
    static int alive(long now) {
        while (!ENDS.isEmpty() && ENDS.peek() <= now) ENDS.poll();
        return ENDS.size();
    }

    /**
     * Whether a puff of the plume of cell {@code plume} may start at {@code now}: not while the plume rests; a near
     * plume's while the count is under {@code budget}, a far one's while it is under {@link #FAR_SHARE} of it. A plume
     * turned away rests {@link #REST} ticks, as a vent that pauses: its last puffs rise away whole, so it goes short from
     * the lava up instead of gappy all along, and the room it leaves keeps the others whole. The far plumes are turned
     * away first.
     */
    static boolean admit(long plume, boolean far, long now, int budget) {
        Long until = RESTING.get(plume);
        if (until != null && now < until) return false;
        if (alive(now) < (far ? FAR_SHARE * budget : budget)) {
            if (until != null) RESTING.remove(plume);
            return true;
        }
        if (RESTING.size() > 512) RESTING.values().removeIf(t -> t <= now);
        RESTING.put(plume, now + REST);
        return false;
    }

    /**
     * The share of the forced particles to spawn (the far-seen smoke, the grit that warns of falling rock): forced
     * particles skip the vanilla Particles option, so they follow it by hand, 1 at "All", 0.4 at "Decreased" and 0.1 at
     * "Minimal".
     */
    public static float share() {
        return switch (Minecraft.getInstance().options.particles().get()) {
            case ALL -> 1F;
            case DECREASED -> 0.4F;
            case MINIMAL -> 0.1F;
        };
    }

    /** A new level: its particles are gone and its clock is another. */
    static void clear() {
        ENDS.clear();
        RESTING.clear();
    }
}
