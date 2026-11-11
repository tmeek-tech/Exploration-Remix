package com.explorationremix.combat.swing;

/**
 * How one kind of weapon swings (all times in ticks, 20 a second). Taran's picks (2026-11-11): a light swing winds up
 * for 0.25 s; holding the attack button past {@link #holdAt} turns it into a strong attack that winds up for 0.5 s in all,
 * then lunges about 1.5 blocks; three light swings in a row, or one strong attack, start a 1.2 s cooldown, and only a
 * strong attack can be made during the cooldown three light swings started. Every swing that lands is at full strength
 * (vanilla's charge bar is gone for these weapons).
 *
 * @param lightWindup   from the press to the light swing landing
 * @param holdAt        held from the press to this tick of a wind-up: it becomes a strong attack
 * @param strongWindup  from the press to the strong attack landing
 * @param lightRecover  after a light swing lands, before the next wind-up can start (presses meanwhile wait)
 * @param strongRecover after a strong attack's lunge
 * @param chain         light swings in a row before the cooldown
 * @param chainGap      idle this long after a swing's recovery and the chain starts again from its first swing
 * @param cooldown      from the landing of the chain's last light swing, or of a strong attack
 * @param lunge         the strong attack's lunge: the speed (blocks a tick) the player is set to on each of its ticks
 * @param lightCone     how far off the line of sight (degrees) a light swing still finds its target
 * @param strongCone    the same for the strong attack's thrust
 * @param strongReach   extra reach of the thrust (blocks)
 */
public record WeaponProfile(String name, int lightWindup, int holdAt, int strongWindup, int lightRecover, int strongRecover,
                            int chain, int chainGap, int cooldown, double[] lunge, double lightCone, double strongCone,
                            double strongReach) {
    /** Swords: Taran's numbers. The lunge's three ticks and the slide after them come to about 1.5 blocks on a floor. */
    public static final WeaponProfile SWORD = new WeaponProfile("sword", 5, 4, 10, 4, 8, 3, 10, 24,
            new double[]{0.42, 0.38, 0.3}, 35.0, 24.0, 0.75);

    /** Axes: slower, heavier swings and a chain of two. */
    public static final WeaponProfile AXE = new WeaponProfile("axe", 8, 4, 14, 6, 10, 2, 12, 28,
            new double[]{0.42, 0.38, 0.3}, 30.0, 22.0, 0.6);

    public int windup(SwingKind k) { return k == SwingKind.STRONG ? strongWindup : lightWindup; }

    public int recover(SwingKind k) { return k == SwingKind.STRONG ? strongRecover : lightRecover; }

    public int lungeTicks() { return lunge.length; }
}
