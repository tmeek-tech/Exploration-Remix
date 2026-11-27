package com.explorationremix.combat.foes;

/**
 * The moves vanilla's hostile mobs make since 0.23.8 (Taran 2026-11-27), as both sides name them: the server says which
 * one a mob starts and which phase it is in ({@link Phase}), and each client poses the mob's body for it.
 */
public enum FoeMove {
    NONE,
    /** A zombie's two-armed slam: both arms drawn back over its head, then a lurch forward into the blow. */
    SLAM,
    /** A skeleton without a bow: one arm drawn back, then a swipe. */
    SWIPE,
    /** A skeleton's tell before it lets an arrow go: the string pulled the last bit back. */
    SHOT,
    /** A skeleton kicks off a player who reached it. */
    KICK,
    /** A spider's crouch with its front legs raised, then the leap. */
    POUNCE,
    /** A spider's rear and bite at close range. */
    BITE,
    /** A spider on a ledge or wall above lets go onto its quarry. */
    DROP,
    /** An enderman's two-armed blow from overhead. */
    CLAW,
    /** A witch's bottle raised, then thrown. */
    THROW,
    /** A slime squashing down before its slam. */
    SQUASH,
    /** A vindicator's axe raised overhead, then the chop. */
    CHOP,
    /** A pillager's tell before it lets its bolt go. */
    BOLT,
    /** A creeper lit: it crouches and shudders as it swells (the client reads the swell itself). */
    SWELL;

    private static final FoeMove[] ALL = values();

    public static FoeMove of(int i) { return i >= 0 && i < ALL.length ? ALL[i] : NONE; }

    /** Where in a move a mob is. */
    public enum Phase {
        /** Not in a move. */
        IDLE,
        /** Winding up: the tell, the time to answer it. */
        WINDUP,
        /** The blow (or leap, or throw) itself. */
        STRIKE,
        /** Coming back from it: open, or stumbling after a miss. */
        RECOVER;

        private static final Phase[] ALL = values();

        public static Phase of(int i) { return i >= 0 && i < ALL.length ? ALL[i] : IDLE; }
    }
}
