package com.explorationremix.combat;

/**
 * The combat layer's numbers, with no game in them (so they can be tested). Every creature has hidden poise: hits from
 * players wear it down, and when it runs out the creature staggers for a moment, open to harder hits. Speed carried
 * into a melee hit (a sprint, a leap, a Dash) makes a momentum hit; a creature knocked hard into a wall is slammed.
 */
public final class Poise {
    private Poise() {}

    /** Ticks a creature reels once its poise breaks. */
    public static final int STAGGER_TICKS = 40;
    /** The cave hunters and big creatures only reel this long, and take no extra damage for it. */
    public static final int HUNTER_REEL_TICKS = 12;
    /** Ticks after a stagger in which its poise can't be worn (no creature can be kept reeling). */
    public static final int IMMUNE_TICKS = 60;
    /** Ticks without a hit before poise starts coming back, and how fast it does (a share of the most, per tick). */
    public static final int RECOVER_DELAY = 60;
    public static final float RECOVER_PER_TICK = 0.01F;
    /** Damage players deal a reeling creature is multiplied by this. */
    public static final float STAGGER_BONUS = 1.35F;

    /** A creature's most poise: a zombie's (20 health) is 16, so three full sword hits break it, two with an axe. */
    public static float max(float maxHealth) { return 4.0F + 0.6F * Math.max(0.0F, maxHealth); }

    /** Poise as it stands {@code now}: what was left at {@code since}, come back since the last hit's delay ran out. */
    public static float at(float poise, float max, long since, long lastHit, long now) {
        long from = Math.max(since, lastHit + RECOVER_DELAY);
        if (now <= from) return Math.min(poise, max);
        return Math.min(max, poise + max * RECOVER_PER_TICK * (now - from));
    }

    /** What a weapon does to poise, per point of damage: an axe most, a sword a little less, anything else less still. */
    public enum Weapon {
        AXE(1.6F), SWORD(1.0F), OTHER(0.8F);

        public final float factor;

        Weapon(float factor) { this.factor = factor; }
    }

    /**
     * The poise a melee hit wears: its damage times the weapon's factor; a swing made before the weapon was ready
     * (strength under 0.9) wears little, a critical hit more, and momentum much more.
     */
    public static float melee(float damage, Weapon w, float strength, boolean crit, float momentum) {
        float p = Math.max(0.0F, damage) * w.factor;
        if (strength < 0.9F) p *= 0.3F;
        if (crit) p *= 1.5F;
        return p * (1.0F + 1.5F * clamp01(momentum));
    }

    /** An arrow's poise: a little less than its damage. */
    public static float arrow(float damage) { return Math.max(0.0F, damage) * 0.7F; }

    /** A slam wears twice its damage: walls are the surest way to break a creature's guard. */
    public static float slam(float damage) { return Math.max(0.0F, damage) * 2.0F; }

    // ------------------------------------------------------------------------------------------------ momentum

    /** Closing speeds (blocks a tick) below this carry nothing; at {@link #MOMENTUM_FULL} past it, all there is. */
    public static final double MOMENTUM_FROM = 0.2, MOMENTUM_FULL = 0.6;

    /**
     * How much momentum a hit carries, 0 to 1, from how fast the attacker closed on the creature (blocks a tick, along
     * the line between them): a walk carries nothing, a sprint a little, a sprinting leap or a dive more, a Dash all.
     */
    public static float momentum(double closing) { return clamp01((float) ((closing - MOMENTUM_FROM) / MOMENTUM_FULL)); }

    /** A momentum hit's damage is multiplied by this. */
    public static float momentumDamage(float momentum) { return 1.0F + 0.5F * clamp01(momentum); }

    /** And it throws the creature this much harder (blocks a tick, on top of the game's own knockback). */
    public static float momentumPush(float momentum) { return momentum <= 0.0F ? 0.0F : 0.25F + 0.55F * clamp01(momentum); }

    // ------------------------------------------------------------------------------------------------ slams

    /** Speeds into a wall (blocks a tick) below this are only a bump. A plain hit never gets there; a sprinting one can. */
    public static final double SLAM_FROM = 0.45;
    public static final float SLAM_MAX = 6.0F;

    /** A slam's damage for the speed it hit the wall at: 1 at the least, up to {@link #SLAM_MAX}. */
    public static float slamDamage(double speed) {
        if (speed < SLAM_FROM) return 0.0F;
        return Math.min(SLAM_MAX, 1.0F + (float) (speed - SLAM_FROM) * 6.0F);
    }

    /** How hard a slam was, 0 to 1, for its look and sound. */
    public static float slamShare(double speed) { return clamp01((float) ((speed - SLAM_FROM) / 0.8)); }

    static float clamp01(float f) { return f < 0.0F ? 0.0F : Math.min(1.0F, f); }
}
