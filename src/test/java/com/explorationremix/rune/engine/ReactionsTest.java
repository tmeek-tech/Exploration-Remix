package com.explorationremix.rune.engine;

import org.junit.jupiter.api.Test;

import static com.explorationremix.rune.engine.Reactions.Reaction.FREEZE;
import static com.explorationremix.rune.engine.Reactions.Reaction.NONE;
import static com.explorationremix.rune.engine.Reactions.Reaction.SHATTER;
import static com.explorationremix.rune.engine.Reactions.Reaction.SHOCK;
import static com.explorationremix.rune.engine.Reactions.Reaction.STEAM;
import static com.explorationremix.rune.engine.Reactions.Reaction.THAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every element on every state a creature can be in: which reaction goes off. */
class ReactionsTest {
    static Reactions.Reaction r(Element e, boolean heavy, boolean wet, boolean frozen, boolean immune) {
        return Reactions.react(e, heavy, wet, frozen, immune);
    }

    @Test
    void onADryCreatureNothingReacts() {
        for (Element e : Element.values()) {
            assertEquals(NONE, r(e, false, false, false, false), e.name());
            assertEquals(NONE, r(e, true, false, false, false), "a heavy hit on a dry creature: " + e);
        }
    }

    @Test
    void onAWetCreature() {
        assertEquals(SHOCK, r(Element.STORM, false, true, false, false));
        assertEquals(FREEZE, r(Element.FROST, false, true, false, false));
        assertEquals(STEAM, r(Element.EMBER, false, true, false, false));
        assertEquals(NONE, r(Element.TIDE, false, true, false, false), "more water does nothing");
        assertEquals(NONE, r(Element.NONE, true, true, false, false), "a plain heavy hit does nothing");
    }

    @Test
    void justHeldItShrugsOffBeingHeldAgainButNotTheRest() {
        assertEquals(NONE, r(Element.STORM, false, true, false, true));
        assertEquals(NONE, r(Element.FROST, false, true, false, true));
        assertEquals(STEAM, r(Element.EMBER, false, true, false, true));
        assertEquals(SHATTER, r(Element.NONE, true, false, true, true), "a frozen creature still shatters");
        assertEquals(THAW, r(Element.EMBER, false, false, true, true));
    }

    @Test
    void onAFrozenCreature() {
        assertEquals(THAW, r(Element.EMBER, false, false, true, false));
        assertEquals(THAW, r(Element.EMBER, true, true, true, false), "ember thaws before anything else");
        for (Element e : new Element[]{Element.NONE, Element.FROST, Element.STORM, Element.TIDE}) {
            assertEquals(SHATTER, r(e, true, false, true, false), "heavy " + e);
            assertEquals(NONE, r(e, false, false, true, false), "light " + e);
        }
        assertEquals(SHOCK, r(Element.STORM, false, true, true, false), "frozen and wet, a light storm still shocks");
    }

    @Test
    void onlyShockAndFreezeHoldAndHuntersAreHeldHalfAsLong() {
        for (Reactions.Reaction x : Reactions.Reaction.values()) assertEquals(x == SHOCK || x == FREEZE, x.holds(), x.name());
        assertEquals(Reactions.STUN_TICKS, Reactions.holdTicks(SHOCK, false));
        assertEquals(Reactions.STUN_TICKS / 2, Reactions.holdTicks(SHOCK, true));
        assertEquals(Reactions.ROOT_TICKS, Reactions.holdTicks(FREEZE, false));
        assertEquals(Reactions.ROOT_TICKS / 2, Reactions.holdTicks(FREEZE, true));
        assertEquals(0, Reactions.holdTicks(SHATTER, false));
        assertTrue(Reactions.IMMUNE_TICKS > Reactions.ROOT_TICKS, "it can't be chain-held");
        assertFalse(Reactions.SHATTER_MULT <= 1.0F || Reactions.THAW_MULT <= 1.0F);
    }
}
