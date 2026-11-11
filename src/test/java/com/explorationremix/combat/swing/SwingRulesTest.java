package com.explorationremix.combat.swing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server's check on a player's swings: nothing lands sooner than the rules allow, with a few ticks to spare. */
class SwingRulesTest {
    private static final WeaponProfile W = WeaponProfile.SWORD;

    @Test
    void lightSwingsAtTheirOwnPaceAreCountedAndFasterOnesAreNot() {
        SwingRules.State s = new SwingRules.State();
        long t = 100;
        assertTrue(SwingRules.land(s, W, SwingKind.LIGHT, 1, t));
        // the next can land a recovery and a wind-up later (a few ticks early for a bunched connection)
        assertFalse(SwingRules.land(s, W, SwingKind.LIGHT, 2, t + 2), "two light swings two ticks apart");
        assertTrue(SwingRules.land(s, W, SwingKind.LIGHT, 2, t + W.lightRecover() + W.lightWindup()));
        assertFalse(SwingRules.land(s, W, SwingKind.LIGHT, 2, t + 40), "the same swing twice");
    }

    @Test
    void afterThreeLightSwingsOnlyAStrongAttackComesBeforeTheCooldownEnds() {
        SwingRules.State s = new SwingRules.State();
        long t = 100;
        for (int i = 1; i <= 3; i++) {
            assertTrue(SwingRules.land(s, W, SwingKind.LIGHT, i, t));
            if (i < 3) t += W.lightRecover() + W.lightWindup();
        }
        assertFalse(SwingRules.land(s, W, SwingKind.LIGHT, 4, t + 12), "a light swing in the cooldown");
        assertTrue(SwingRules.land(s, W, SwingKind.STRONG, 4, t + W.lightRecover() + W.strongWindup()), "a strong attack overrides it");
        long strongAt = t + W.lightRecover() + W.strongWindup();
        assertFalse(SwingRules.land(s, W, SwingKind.STRONG, 5, strongAt + 12), "nothing in a strong attack's cooldown");
        assertFalse(SwingRules.land(s, W, SwingKind.LIGHT, 5, strongAt + 12), "nothing in a strong attack's cooldown");
        assertTrue(SwingRules.land(s, W, SwingKind.LIGHT, 5, strongAt + W.cooldown() + W.lightWindup()));
    }
}
