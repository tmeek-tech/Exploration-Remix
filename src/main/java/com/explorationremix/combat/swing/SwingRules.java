package com.explorationremix.combat.swing;

/**
 * The server's check on a player's swings, with no game in it: a player's own game times their swings
 * ({@link SwingMachine}) and says when each lands; the server only makes sure no swing lands sooner after the last one
 * than the rules allow, with a few ticks to spare for a connection that bunches its messages. It errs towards the player:
 * a chain counted on their game is never longer than the one counted here.
 */
public final class SwingRules {
    /** Ticks a landing may come early (messages bunched by a slow connection). */
    public static final int SLACK = 4;

    /** What the server knows of one player's swings. Never saved. */
    public static final class State {
        long lastLand = Long.MIN_VALUE / 4;
        SwingKind lastKind = SwingKind.LIGHT;
        int chain;
        boolean cooldown, cooldownFromStrong;
        int seq = Integer.MIN_VALUE;

        public long lastLand() { return lastLand; }

        public SwingKind lastKind() { return lastKind; }

        public int lastSeq() { return seq; }
    }

    private SwingRules() {}

    /** The earliest a swing of kind {@code k} may land after the last one (game time). */
    public static long earliest(State s, WeaponProfile w, SwingKind k) {
        long last = s.lastLand;
        if (s.cooldown) {
            // a light swing waits out the cooldown; a strong attack waits out only a strong attack's
            if (s.cooldownFromStrong || k == SwingKind.LIGHT) return last + w.cooldown() + 1 - SLACK;
            return last + w.lightRecover() + w.strongWindup() - SLACK;
        }
        return last + w.lightRecover() + w.windup(k) - SLACK;
    }

    /**
     * A swing of kind {@code k} lands now ({@code seq} is its number on the player's game): true if it may, and then
     * it is counted. A swing already counted (the same number) is refused.
     */
    public static boolean land(State s, WeaponProfile w, SwingKind k, int seq, long now) {
        if (seq == s.seq) return false;
        long gap = now - s.lastLand;
        // idle long enough that the player's chain has started again, and any cooldown is over
        if (s.lastKind == SwingKind.LIGHT && !s.cooldown && gap > (long) w.lightRecover() + w.chainGap() + w.lightWindup() - SLACK) s.chain = 0;
        if (s.cooldown && gap > w.cooldown()) {
            s.cooldown = false;
            s.chain = 0;
        }
        if (now < earliest(s, w, k)) return false;
        s.lastLand = now;
        s.lastKind = k;
        s.seq = seq;
        if (k == SwingKind.STRONG) {
            s.chain = 0;
            s.cooldown = true;
            s.cooldownFromStrong = true;
        } else {
            s.cooldown = false;
            if (++s.chain >= w.chain()) {
                s.chain = 0;
                s.cooldown = true;
                s.cooldownFromStrong = false;
            }
        }
        return true;
    }
}
