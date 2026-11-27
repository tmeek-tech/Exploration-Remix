package com.explorationremix.combat.guard;

/**
 * A sword's guard, with no game in it (Taran 2026-11-25). Held up, it turns blows from in front: a blow taken on the blade
 * does a third of its damage and pushes half as hard (a shield still stops all of it); one that lands within
 * {@link #PARRY_TICKS} of the guard going up is parried: none of it lands, the creature is thrown back a step and reels,
 * and an arrow flies back where it came from. Raised again too soon after the last raise, the guard only blocks, so a
 * guard flicked up and down can't parry everything.
 */
public final class GuardRules {
    /** A parry's window from the guard going up (Taran's pick: 0.35 s). */
    public static final int PARRY_TICKS = 7;
    /** A raise this soon after the one before opens no window: it only blocks (ticks). */
    public static final int REARM_TICKS = 16;
    /** What a blocked blow still does (Taran's pick: two thirds off). */
    public static final float BLOCK_KEEPS = 1.0F / 3.0F;
    /** And how hard it still pushes. */
    public static final float BLOCK_PUSH = 0.5F;
    /** Blows from within this angle of where the player looks are taken on the blade (degrees). */
    public static final double FRONT_DEGREES = 75.0;
    /** Only blows from a creature this near (blocks, to the nearest of it) are taken on the blade: no sweep from afar. */
    public static final double REACH = 4.0;
    /** A parried creature is thrown back this hard (a step), and reels as a strong attack's interrupt makes it (1.5 s). */
    public static final double PARRY_PUSH = 0.6;
    /**
     * A thrown bottle breaking this near a player (blocks from their body) in their parry window is caught on the blade
     * (0.23.6, a witch's): most land at the feet rather than on the player, and would splash them all the same.
     */
    public static final double BOTTLE_CATCH = 2.0;

    /** One player's guard. Never saved. */
    public static final class State {
        long raisedAt = Long.MIN_VALUE / 4;
        long parryUntil = Long.MIN_VALUE / 4;

        public long raisedAt() { return raisedAt; }
    }

    private GuardRules() {}

    /** The guard went up at {@code now}: its parry window opens, unless the last raise was too recent. */
    public static void raise(State s, long now) {
        s.parryUntil = now - s.raisedAt >= REARM_TICKS ? now + PARRY_TICKS - 1 : Long.MIN_VALUE / 4;
        s.raisedAt = now;
    }

    /** Whether a blow landing at {@code now} on the raised guard is parried. */
    public static boolean parries(State s, long now) { return now >= s.raisedAt && now <= s.parryUntil; }

    /** Whether the way {@code (dx, dy, dz)} from the eye (any length) is in front of a look {@code (lx, ly, lz)}. */
    public static boolean inFront(double lx, double ly, double lz, double dx, double dy, double dz) {
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz), m = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (n < 1.0E-6) return true;
        if (m < 1.0E-6) return false;
        return (lx * dx + ly * dy + lz * dz) / (n * m) >= Math.cos(Math.toRadians(FRONT_DEGREES));
    }

    /** What a blocked blow of {@code amount} still does. */
    public static float blocked(float amount) { return amount * BLOCK_KEEPS; }
}
