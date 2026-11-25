package com.explorationremix.combat.swing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(SwingRules.land(s, W, SwingKind.LIGHT, 5, strongAt + W.heavyCooldown() + W.lightWindup()));
    }

    /** Taran 2026-11-25: 2 s after a heavy attack (a strong one or a jump attack), the chain's own cooldown still 1.2 s. */
    @Test
    void aHeavyAttacksCooldownIsTwoSeconds() {
        assertEquals(40, W.heavyCooldown());
        assertEquals(24, W.cooldown());
        SwingRules.State s = new SwingRules.State();
        assertTrue(SwingRules.land(s, W, SwingKind.JUMP, 1, 100));
        assertTrue(SwingRules.heavyCooling(s, W, 130));
        assertFalse(SwingRules.land(s, W, SwingKind.LIGHT, 2, 100 + W.cooldown() + W.lightWindup()), "the chain's cooldown is not enough");
        assertFalse(SwingRules.land(s, W, SwingKind.JUMP, 2, 120), "nor another jump attack");
        assertTrue(SwingRules.land(s, W, SwingKind.LIGHT, 2, 100 + W.heavyCooldown() + W.lightWindup()));
        assertFalse(SwingRules.heavyCooling(s, W, 100 + W.heavyCooldown() + W.lightWindup()));
    }

    @Test
    void onlySwordsMakeJumpAttacksAndTheyComeOutOfTheChainsCooldownToo() {
        SwingRules.State axe = new SwingRules.State();
        assertFalse(SwingRules.land(axe, WeaponProfile.AXE, SwingKind.JUMP, 1, 100), "an axe has no jump attack");
        SwingRules.State s = new SwingRules.State();
        long t = 100;
        for (int i = 1; i <= 3; i++) {
            assertTrue(SwingRules.land(s, W, SwingKind.LIGHT, i, t));
            if (i < 3) t += W.lightRecover() + W.lightWindup();
        }
        // after the chop, a jump attack (held in mid-air) needs only its own wind-up, as a strong attack does
        assertFalse(SwingRules.land(s, W, SwingKind.JUMP, 4, t + 2));
        assertTrue(SwingRules.land(s, W, SwingKind.JUMP, 4, t + W.lightRecover() + W.jumpMin()));
    }
}
