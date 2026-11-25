package com.explorationremix.combat.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sword's guard (Taran's picks, 2026-11-25): two thirds off a blow from the front; a parry just as it goes up. */
class GuardRulesTest {
    @Test
    void aBlowJustAsTheGuardGoesUpIsParriedAndOneLaterIsBlocked() {
        GuardRules.State s = new GuardRules.State();
        GuardRules.raise(s, 1000);
        for (long t = 1000; t < 1000 + GuardRules.PARRY_TICKS; t++) assertTrue(GuardRules.parries(s, t), "parried at " + t);
        assertFalse(GuardRules.parries(s, 1000 + GuardRules.PARRY_TICKS), "the window is 0.35 s");
        assertEquals(7, GuardRules.PARRY_TICKS);
    }

    @Test
    void aGuardRaisedAgainTooSoonOnlyBlocks() {
        GuardRules.State s = new GuardRules.State();
        GuardRules.raise(s, 1000);
        GuardRules.raise(s, 1000 + GuardRules.REARM_TICKS - 1);
        assertFalse(GuardRules.parries(s, 1000 + GuardRules.REARM_TICKS), "flicked up and down: no parry");
        // and the window it lost doesn't come back by raising it once more straight away
        GuardRules.raise(s, 1000 + GuardRules.REARM_TICKS + 2);
        assertFalse(GuardRules.parries(s, 1000 + GuardRules.REARM_TICKS + 3));
        // left down long enough, it parries again
        GuardRules.raise(s, 1000 + 3L * GuardRules.REARM_TICKS);
        assertTrue(GuardRules.parries(s, 1000 + 3L * GuardRules.REARM_TICKS));
    }

    @Test
    void theFirstRaiseEverParries() {
        GuardRules.State s = new GuardRules.State();
        GuardRules.raise(s, 5);
        assertTrue(GuardRules.parries(s, 5));
    }

    @Test
    void aBlockedBlowDoesAThird() {
        assertEquals(2.0F, GuardRules.blocked(6.0F), 1.0E-6F);
        assertEquals(0.5F, GuardRules.BLOCK_PUSH, 0.0F);
    }

    @Test
    void onlyBlowsFromInFrontAreTakenOnTheBlade() {
        // looking along +z
        assertTrue(GuardRules.inFront(0, 0, 1, 0, 0, 3));
        assertTrue(GuardRules.inFront(0, 0, 1, 1, 0.2, 1), "45 degrees off");
        assertTrue(GuardRules.inFront(0, 0, 1, 3, 0, 1), "about 72 degrees off");
        assertFalse(GuardRules.inFront(0, 0, 1, 1, 0, 0), "from the side");
        assertFalse(GuardRules.inFront(0, 0, 1, 0, 0, -1), "from behind");
        assertFalse(GuardRules.inFront(0, 0, 1, 0, 4, 0.5), "from straight above, looking ahead");
        assertTrue(GuardRules.inFront(0, 0.9, 0.44, 0, 4, 0.5), "from above, looking up at it");
    }
}
