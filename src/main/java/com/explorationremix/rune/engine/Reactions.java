package com.explorationremix.rune.engine;

/**
 * Element reactions: what an element does to a creature that is wet or frozen.
 * <ul>
 *   <li>Shock: Storm on something wet: a stun, and the storm leaps further between wet creatures.</li>
 *   <li>Freeze: Frost on something wet: rooted in place.</li>
 *   <li>Shatter: a heavy hit on something frozen: extra damage, and the ice breaks.</li>
 *   <li>Steam: Ember on something wet: a puff of steam that dries it.</li>
 *   <li>Thaw: Ember on something frozen: extra damage, and the ice melts.</li>
 * </ul>
 * A creature that has just been stunned or frozen shrugs both off for {@link #IMMUNE_TICKS}; hunters are held for half
 * as long as small creatures.
 */
public final class Reactions {
    public static final int WET_TICKS = 120, STUN_TICKS = 20, ROOT_TICKS = 40, IMMUNE_TICKS = 120;
    public static final float SHATTER_MULT = 1.6F, THAW_MULT = 1.4F;

    public enum Reaction {
        NONE(""), SHOCK("Shock"), FREEZE("Freeze"), SHATTER("Shatter"), STEAM("Steam"), THAW("Thaw");

        public final String title;

        Reaction(String title) { this.title = title; }

        /** Holds the creature in place (stun or root). */
        public boolean holds() { return this == SHOCK || this == FREEZE; }
    }

    private Reactions() {}

    /**
     * The reaction a hit sets off.
     *
     * @param heavy  the hit is heavy (Heavy on it, an Orb, or a full-strength melee swing)
     * @param immune the creature was just stunned or frozen: it can't be held again yet
     */
    public static Reaction react(Element e, boolean heavy, boolean wet, boolean frozen, boolean immune) {
        if (frozen) {
            if (e == Element.EMBER) return Reaction.THAW;
            if (heavy) return Reaction.SHATTER;
        }
        if (wet) {
            if (e == Element.STORM) return immune ? Reaction.NONE : Reaction.SHOCK;
            if (e == Element.FROST) return immune ? Reaction.NONE : Reaction.FREEZE;
            if (e == Element.EMBER) return Reaction.STEAM;
        }
        return Reaction.NONE;
    }

    /** How long a reaction holds a creature: hunters half as long. */
    public static int holdTicks(Reaction r, boolean hunter) {
        int t = r == Reaction.SHOCK ? STUN_TICKS : r == Reaction.FREEZE ? ROOT_TICKS : 0;
        return hunter ? t / 2 : t;
    }
}
