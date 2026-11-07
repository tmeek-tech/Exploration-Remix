package com.explorationremix.rune.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paying and timing: all-or-nothing casts, delays, recovery, idle resets, regeneration, red casts, Siphon. */
class CastMachineTest {
    static final Limits LIM = Limits.DEFAULT;
    static final HostSpec WAND = HostSpec.of(Host.WAND);

    static Reading read(String row, HostSpec spec) { return Reader.read(Row.parse(row), spec, 2); }

    @Test
    void aCastIsPaidInFullOrSputters() {
        Reading r = read("orb on_hit burst", HostSpec.of(Host.SWORD));
        CastState s = new CastState(20, 0, -1, 0, 0, Long.MIN_VALUE / 4);
        CastMachine.Outcome o = CastMachine.attempt(s, r, 100, false, LIM);
        assertEquals(CastMachine.Kind.SPUTTER, o.kind());
        assertEquals(28, o.cost());
        assertEquals(20.0F, o.have());
        assertEquals(20.0F, s.energy, "nothing spent");
        assertEquals(0, s.cursor);
        CastMachine.add(s, r.spec(), 100, 10);
        o = CastMachine.attempt(s, r, 100, false, LIM);
        assertEquals(CastMachine.Kind.CAST, o.kind());
        assertEquals(2.0F, s.energy, 1.0E-4);
    }

    @Test
    void eachCastWaitsItsDelayAndTheLastWaitsTheRecovery() {
        Reading r = read("spark | orb", WAND);
        CastState s = CastState.fresh(WAND, 0);
        CastMachine.Outcome o = CastMachine.attempt(s, r, 10, false, LIM);
        assertEquals(0, o.cast());
        int d0 = r.cast(0).delay();
        assertEquals(10 + d0, s.readyAt);
        assertEquals(1, s.cursor);
        // too soon
        assertEquals(CastMachine.Kind.NOT_READY, CastMachine.attempt(s, r, 10 + d0 - 1, false, LIM).kind());
        o = CastMachine.attempt(s, r, 10 + d0, false, LIM);
        assertEquals(1, o.cast());
        // after the last cast: the longer of its delay and the recovery, then the first cast again
        long t = 10 + d0;
        assertEquals(t + Math.max(r.cast(1).delay(), r.recovery()), s.readyAt);
        assertEquals(0, s.cursor);
    }

    @Test
    void idleGearGoesBackToItsFirstCast() {
        Reading r = read("spark | orb | burst", WAND);
        CastState s = CastState.fresh(WAND, 0);
        CastMachine.attempt(s, r, 0, false, LIM);
        assertEquals(1, s.cursor);
        assertEquals(1, CastMachine.next(s, r, LIM.idleTicks() - 1, LIM));
        assertEquals(0, CastMachine.next(s, r, LIM.idleTicks(), LIM));
        assertEquals(0, CastMachine.attempt(s, r, LIM.idleTicks(), false, LIM).cast());
    }

    @Test
    void redCastsAreSkipped() {
        HostSpec small = WAND.withMax(20);
        Reading r = read("spark | orb on_hit burst | shard", small);
        assertTrue(r.cast(1).red());
        CastState s = CastState.fresh(small, 0);
        assertEquals(0, CastMachine.attempt(s, r, 0, false, LIM).cast());
        assertEquals(2, s.cursor, "the red cast is skipped");
        assertEquals(2, CastMachine.attempt(s, r, s.readyAt, false, LIM).cast());
        Reading allRed = read("orb on_hit burst", small);
        assertEquals(CastMachine.Kind.ALL_RED, CastMachine.attempt(CastState.fresh(small, 0), allRed, 0, false, LIM).kind());
    }

    @Test
    void aWandRegainsEnergyOnlyWhileHeld() {
        CastState s = new CastState(0, 0, -1, 0, 0, 0);
        assertEquals(0.0F, CastMachine.energyAt(s, WAND, 100));
        CastMachine.setHeld(s, WAND, 100, true);
        assertEquals(0.8F * 50, CastMachine.energyAt(s, WAND, 150), 1.0E-3);
        CastMachine.setHeld(s, WAND, 150, false);
        assertEquals(40.0F, CastMachine.energyAt(s, WAND, 1000), 1.0E-3, "put away: no more");
        CastMachine.setHeld(s, WAND, 1000, true);
        assertEquals(WAND.maxEnergy(), CastMachine.energyAt(s, WAND, 100_000), "never past the most it holds");
    }

    @Test
    void recoveryRunsWhileTheGearIsPutAway() {
        Reading r = read("orb", WAND);
        CastState s = CastState.fresh(WAND, 0);
        CastMachine.attempt(s, r, 0, false, LIM);
        long ready = s.readyAt;
        CastMachine.setHeld(s, WAND, 5, false);
        assertEquals(ready, s.readyAt, "the clock is the world's, not the hand's");
        assertEquals(CastMachine.Kind.CAST, CastMachine.attempt(s, r, ready, false, LIM).kind());
    }

    @Test
    void aFreeCastCostsNothing() {
        Reading r = read("storm burst", HostSpec.of(Host.SHIELD));
        CastState s = new CastState(0, 0, -1, 0, 0, 0);
        CastMachine.Outcome o = CastMachine.attempt(s, r, 10, true, LIM);
        assertEquals(CastMachine.Kind.CAST, o.kind());
        assertEquals(0, o.cost());
        assertEquals(0.0F, s.energy);
    }

    @Test
    void anEditedRowStartsAgainFromItsFirstCast() {
        Reading three = read("spark | orb | burst", WAND);
        CastState s = CastState.fresh(WAND, 0);
        CastMachine.attempt(s, three, 0, false, LIM);
        CastMachine.attempt(s, three, s.readyAt, false, LIM);
        assertEquals(2, s.cursor);
        Reading one = read("spark", WAND);
        assertEquals(0, CastMachine.next(s, one, s.readyAt, LIM), "the cursor never points past the row");
    }

    @Test
    void siphonPaysBackAtMostALittleOverTheCost() {
        Siphon sp = new Siphon(20);
        assertEquals(3.0F, sp.hit(1), 1.0E-4);
        assertEquals(0.0F, sp.hit(1), "each creature once");
        assertEquals(3.0F, sp.hit(2), 1.0E-4);
        assertEquals(8.0F, sp.weakSpot(1), 1.0E-4);
        assertEquals(6.0F, sp.reaction(2), 1.0E-4);
        // 20 paid back so far; the kill would add 10, but the cap is 24
        assertEquals(4.0F, sp.kill(1), 1.0E-4);
        assertEquals(0.0F, sp.kill(2));
        assertEquals(24.0F, sp.paid(), 1.0E-4);
    }

    @Test
    void addingEnergyStopsAtTheMost() {
        HostSpec sword = HostSpec.of(Host.SWORD);
        CastState s = new CastState(60, 0, -1, 0, 0, 0);
        assertEquals(10.0F, CastMachine.add(s, sword, 5, 25), 1.0E-4);
        assertEquals(70.0F, s.energy);
    }

    @Test
    void reactions() {
        assertEquals(Reactions.Reaction.SHOCK, Reactions.react(Element.STORM, false, true, false, false));
        assertEquals(Reactions.Reaction.NONE, Reactions.react(Element.STORM, false, true, false, true), "just held: immune");
        assertEquals(Reactions.Reaction.FREEZE, Reactions.react(Element.FROST, false, true, false, false));
        assertEquals(Reactions.Reaction.SHATTER, Reactions.react(Element.NONE, true, false, true, false));
        assertEquals(Reactions.Reaction.THAW, Reactions.react(Element.EMBER, false, false, true, false));
        assertEquals(Reactions.Reaction.STEAM, Reactions.react(Element.EMBER, false, true, false, false));
        assertEquals(Reactions.Reaction.NONE, Reactions.react(Element.FROST, false, false, false, false));
        assertEquals(10, Reactions.holdTicks(Reactions.Reaction.SHOCK, true));
        assertEquals(40, Reactions.holdTicks(Reactions.Reaction.FREEZE, false));
    }
}
