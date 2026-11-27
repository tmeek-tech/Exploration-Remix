package com.explorationremix.combat.recovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Health coming back after a while unhurt (0.23.6): only a run of blows close together brings a player down. */
class RecoveryRulesTest {
    @Test
    void itComesBackAfterFiveSecondsUnhurt() {
        int delay = RecoveryRules.delayTicks(RecoveryRules.DELAY_SECONDS);
        assertEquals(100, delay);
        assertFalse(RecoveryRules.due(1099, 1000, delay));
        assertTrue(RecoveryRules.due(1100, 1000, delay));
        assertEquals(0, RecoveryRules.delayTicks(-3.0), "a setting below nothing is nothing");
    }

    @Test
    void twoHeartsASecond() {
        // a heart is two points of health: four points a second, a fifth of a point a tick
        assertEquals(0.2F, RecoveryRules.perTick(RecoveryRules.HEARTS_PER_SECOND), 1.0E-6F);
        assertEquals(5.0, RecoveryRules.fullSeconds(0.0F, 20.0F, RecoveryRules.HEARTS_PER_SECOND), 1.0E-4, "a whole bar in 5 s");
        assertEquals(0.0, RecoveryRules.fullSeconds(20.0F, 20.0F, RecoveryRules.HEARTS_PER_SECOND), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, RecoveryRules.fullSeconds(4.0F, 20.0F, 0.0));
    }

    @Test
    void itCostsAThirdOfWhatFoodsHealingDoes() {
        // vanilla's food healing: 6 exhaustion a point of health; 4 exhaustion is a point of food
        assertEquals(2.0F, RecoveryRules.exhaustion(1.0F, RecoveryRules.FOOD_COST), 1.0E-5F);
        float wholeBar = RecoveryRules.exhaustion(20.0F, RecoveryRules.FOOD_COST);
        assertEquals(10.0F, wholeBar / 4.0F, 1.0E-4F, "a whole bar of health back for five drumsticks");
        assertEquals(0.0F, RecoveryRules.exhaustion(-2.0F, RecoveryRules.FOOD_COST), 0.0F);
        assertTrue(RecoveryRules.MIN_FOOD > 6, "more than three drumsticks, as sprinting asks");
    }

    @Test
    void blowsCloseTogetherWinAndSpacedOutOnesDont() {
        // a player of 20 health hit for 4 every few seconds: blows 4 s apart wear it down, 7 s apart never do
        assertTrue(survives(20.0F, 4.0F, 7.0), "spaced out: it heals between them");
        assertFalse(survives(20.0F, 4.0F, 4.0), "close together: it never gets the chance");
    }

    /** Whether a player is still standing after 60 s of blows of {@code damage} every {@code apart} seconds. */
    private static boolean survives(float health, float damage, double apart) {
        int delay = RecoveryRules.delayTicks(RecoveryRules.DELAY_SECONDS);
        float per = RecoveryRules.perTick(RecoveryRules.HEARTS_PER_SECOND);
        int every = (int) Math.round(apart * 20.0);
        float h = health;
        long lastHurt = Long.MIN_VALUE / 2;
        for (long t = 0; t < 1200; t++) {
            if (t % every == 0) {
                h -= damage;
                lastHurt = t;
                if (h <= 0.0F) return false;
            } else if (h < health && RecoveryRules.due(t, lastHurt, delay)) {
                h = Math.min(health, h + per);
            }
        }
        return true;
    }
}
