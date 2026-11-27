package com.explorationremix.combat.recovery;

/**
 * The numbers of a player's health coming back after a while unhurt (0.23.6, Taran 2026-11-27: "regeneration after a
 * while not being hit so they would need to be hit a few times in a row"), with no game in them so they can be tested.
 * The picks (cards of 27 Nov, the recommended ones built while open): back after 5 seconds without harm, two hearts a
 * second, for a third of what food's healing costs, while more than three drumsticks are left; food's own slow healing
 * goes.
 */
public final class RecoveryRules {
    private RecoveryRules() {}

    public static final double DELAY_SECONDS = 5.0;
    public static final double HEARTS_PER_SECOND = 2.0;
    /** What healing costs in hunger, as a share of what vanilla's food healing costs for the same health. */
    public static final double FOOD_COST = 1.0 / 3.0;
    /** The food it needs (points; a drumstick is two): more than three drumsticks, as sprinting needs. */
    public static final int MIN_FOOD = 7;
    /** Vanilla's food healing: this much exhaustion for each point of health (both its slow and its quick healing). */
    public static final float FOOD_HEAL_EXHAUSTION = 6.0F;

    /** Ticks without harm before health comes back. */
    public static int delayTicks(double seconds) { return (int) Math.round(Math.max(0.0, seconds) * 20.0); }

    /** Health points a tick from hearts a second (a heart is two points). */
    public static float perTick(double heartsPerSecond) { return (float) (Math.max(0.0, heartsPerSecond) * 2.0 / 20.0); }

    /** Whether it has been long enough since the last harm (server ticks). */
    public static boolean due(long now, long lastHurt, int delay) { return now - lastHurt >= delay; }

    /** The exhaustion healing {@code healed} points costs. */
    public static float exhaustion(float healed, double cost) { return (float) (Math.max(0.0F, healed) * FOOD_HEAL_EXHAUSTION * Math.max(0.0, cost)); }

    /** Seconds a full recovery takes from {@code health} to {@code max} (for the notes and the tests). */
    public static double fullSeconds(float health, float max, double heartsPerSecond) {
        float per = perTick(heartsPerSecond);
        return per <= 0.0F ? Double.POSITIVE_INFINITY : Math.max(0.0F, max - health) / per / 20.0;
    }
}
