package com.explorationremix.combat.swing;

/**
 * How one kind of weapon swings (all times in ticks, 20 a second). Taran's picks (2026-11-11): a light swing winds up
 * for 0.25 s; holding the attack button past {@link #holdAt} turns it into a strong attack that winds up for 0.5 s in all,
 * then lunges about 1.5 blocks; three light swings in a row, or one strong attack, start a 1.2 s cooldown, and only a
 * strong attack can be made during the cooldown three light swings started. Every swing that lands is at full strength
 * (vanilla's charge bar is gone for these weapons). Taran 2026-11-25: a heavy attack (strong, or a sword's jump attack,
 * held in mid-air) is followed by a longer cooldown, 2 s, in which nothing can start.
 *
 * @param lightWindup   from the press to the light swing landing
 * @param holdAt        held from the press to this tick of a wind-up: it becomes a strong attack
 * @param strongWindup  from the press to the strong attack landing
 * @param lightRecover  after a light swing lands, before the next wind-up can start (presses meanwhile wait)
 * @param strongRecover after a strong attack's lunge
 * @param chain         light swings in a row before the cooldown
 * @param chainGap      idle this long after a swing's recovery and the chain starts again from its first swing
 * @param cooldown      from the landing of the chain's last light swing
 * @param heavyCooldown from the landing of a strong attack or a jump attack
 * @param lunge         the strong attack's lunge: the speed (blocks a tick) the player is set to on each of its ticks
 * @param lightCone     how far off the line of sight (degrees) a light swing still finds its target
 * @param strongCone    the same for the strong attack's thrust
 * @param strongReach   extra reach of the thrust (blocks)
 * @param jumpMin       a jump attack lands as you touch down, but never sooner than this after its press (0: the weapon
 *                      makes none; held in mid-air it is a strong attack)
 * @param jumpRecover   after a jump attack lands, the blade held low where it struck
 * @param jumpReach     extra reach of the jump attack's blow (blocks)
 * @param jumpCone      how far off the line of sight it still finds its target
 */
public record WeaponProfile(String name, int lightWindup, int holdAt, int strongWindup, int lightRecover, int strongRecover,
                            int chain, int chainGap, int cooldown, int heavyCooldown, double[] lunge, double lightCone,
                            double strongCone, double strongReach, int jumpMin, int jumpRecover, double jumpReach,
                            double jumpCone) {
    /** Swords: Taran's numbers. The lunge's three ticks and the slide after them come to about 1.5 blocks on a floor. */
    public static final WeaponProfile SWORD = new WeaponProfile("sword", 5, 4, 10, 4, 8, 3, 10, 24, 40,
            new double[]{0.42, 0.38, 0.3}, 35.0, 24.0, 0.75, 8, 10, 0.5, 30.0);

    /** Axes: slower, heavier swings and a chain of two; no jump attack (Taran asked for one on swords). */
    public static final WeaponProfile AXE = new WeaponProfile("axe", 8, 4, 14, 6, 10, 2, 12, 28, 44,
            new double[]{0.42, 0.38, 0.3}, 30.0, 22.0, 0.6, 0, 10, 0.0, 30.0);

    /** From the press to the landing: for a jump attack, the soonest it can land (it waits to touch down). */
    public int windup(SwingKind k) {
        return switch (k) {
            case STRONG -> strongWindup;
            case JUMP -> jumpMin;
            default -> lightWindup;
        };
    }

    public int recover(SwingKind k) {
        return switch (k) {
            case STRONG -> strongRecover;
            case JUMP -> jumpRecover;
            default -> lightRecover;
        };
    }

    /** The cooldown after a swing of kind {@code k} that ends a chain or is heavy. */
    public int cooldown(SwingKind k) { return k.heavy() ? heavyCooldown : cooldown; }

    /** Whether this weapon makes jump attacks (swords). */
    public boolean jumps() { return jumpMin > 0; }

    public int lungeTicks() { return lunge.length; }
}
