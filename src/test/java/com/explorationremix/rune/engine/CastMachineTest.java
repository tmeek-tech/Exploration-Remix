package com.explorationremix.rune.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paying and timing: all-or-nothing casts, delays, recovery, fading to the floor, red casts, refunds. */
class CastMachineTest {
    static final Limits LIM = Limits.DEFAULT;
    static final HostSpec BLADE = HostSpec.of(Host.SWORD);

    static Reading read(String runes, HostSpec spec) { return Reader.read(Loadout.parse(runes), spec, 0); }

    /** Full gear that has just been filled at tick 0 (it holds until the fade's delay is up). */
    static CastState full(HostSpec spec) { return new CastState(spec.maxEnergy(), 0, 0, 0, 0, Long.MIN_VALUE / 4); }

    @Test
    void aCastIsPaidInFullOrSputters() {
        Reading r = read("orb heavy", HostSpec.of(Host.SWORD));
        CastState s = new CastState(10, 100, 100, 0, 0, Long.MIN_VALUE / 4);
        CastMachine.Outcome o = CastMachine.attempt(s, r, 100, false, LIM);
        assertEquals(CastMachine.Kind.SPUTTER, o.kind());
        assertEquals(18, o.cost());
        assertEquals(10.0F, o.have());
        assertEquals(10.0F, s.energy, "nothing spent");
        CastMachine.add(s, r.spec(), 100, 10);
        o = CastMachine.attempt(s, r, 100, false, LIM);
        assertEquals(CastMachine.Kind.CAST, o.kind());
        assertEquals(2.0F, s.energy, 1.0E-4);
    }

    @Test
    void aCastWaitsTheLongerOfItsDelayAndTheRecovery() {
        for (String runes : new String[]{"spark", "orb heavy", "burst", "spark swift"}) {
            Reading r = read(runes, BLADE);
            CastState s = full(BLADE);
            CastMachine.Outcome o = CastMachine.attempt(s, r, 10, false, LIM);
            assertEquals(0, o.cast(), runes);
            long wait = Math.max(r.cast().delay(), r.recovery());
            assertEquals(10 + wait, s.readyAt, runes);
            assertEquals(0, s.cursor, runes);
            assertEquals(CastMachine.Kind.NOT_READY, CastMachine.attempt(s, r, 10 + wait - 1, false, LIM).kind(), runes + ": too soon");
            assertEquals(CastMachine.Kind.CAST, CastMachine.attempt(s, r, 10 + wait, false, LIM).kind(), runes);
        }
    }

    @Test
    void aRedCastNeverFires() {
        HostSpec small = BLADE.withMax(10);
        Reading r = read("orb", small);
        assertTrue(r.cast().red());
        assertEquals(-1, CastMachine.next(CastState.fresh(small, 0), r, 0, LIM));
        assertEquals(CastMachine.Kind.ALL_RED, CastMachine.attempt(CastState.fresh(small, 0), r, 0, false, LIM).kind());
        assertEquals(CastMachine.Kind.NOTHING, CastMachine.attempt(CastState.fresh(small, 0), read("ember", small), 0, false, LIM).kind());
    }

    @Test
    void freshGearStartsAtItsFloor() {
        HostSpec sword = HostSpec.of(Host.SWORD);
        assertEquals(21.0F, sword.floor(), "three tenths of 70");
        assertEquals(sword.floor(), CastState.fresh(sword, 0).energy);
        assertEquals(sword.floor(), CastMachine.energyAt(CastState.fresh(sword, 0), sword, 1_000_000), "and stays there");
        assertEquals(20.0F, sword.withMax(20).floor(), "never above the most it holds");
    }

    @Test
    void energyHoldsThroughAFightThenFadesToTheFloor() {
        HostSpec sword = HostSpec.of(Host.SWORD);
        CastState s = new CastState(21, 0, -1, 0, 0, Long.MIN_VALUE / 4);
        CastMachine.add(s, sword, 100, 40);
        assertEquals(61.0F, s.energy, 1.0E-4);
        int delay = sword.fade().delayTicks();
        assertEquals(61.0F, CastMachine.energyAt(s, sword, 100 + delay - 1), 1.0E-4, "held while the fight is on");
        assertEquals(61.0F, CastMachine.energyAt(s, sword, 100 + delay), 1.0E-4);
        assertEquals(61.0F - sword.fade().downPerTick() * 20, CastMachine.energyAt(s, sword, 100 + delay + 20), 1.0E-3, "then it fades");
        assertEquals(21.0F, CastMachine.energyAt(s, sword, 100_000), "down to the floor, no further");
        // another hit while it fades: it holds again from what was left
        CastMachine.add(s, sword, 100 + delay + 20, 5);
        assertEquals(59.0F, CastMachine.energyAt(s, sword, 100 + 2 * delay + 20), 1.0E-3);
    }

    @Test
    void aCastKeepsTheFightOn() {
        HostSpec sword = HostSpec.of(Host.SWORD);
        Reading r = read("spark", sword);
        CastState s = new CastState(60, 0, 0, 0, 0, Long.MIN_VALUE / 4);
        CastMachine.attempt(s, r, 70, false, LIM);
        assertEquals(52.0F, CastMachine.energyAt(s, sword, 150), 1.0E-4, "the delay counts from the cast");
        assertEquals(52.0F - 0.35F * 10, CastMachine.energyAt(s, sword, 160), 1.0E-3);
        assertEquals(150, CastMachine.quietAt(s, sword));
    }

    @Test
    void spentGearComesBackUpToTheFloor() {
        HostSpec sword = HostSpec.of(Host.SWORD);
        Reading r = read("orb", sword);
        CastState s = CastState.fresh(sword, 0);
        assertEquals(CastMachine.Kind.CAST, CastMachine.attempt(s, r, 0, false, LIM).kind(), "a fight opens with a cast");
        assertEquals(7.0F, CastMachine.energyAt(s, sword, 80), 1.0E-4);
        assertEquals(7.0F + 0.15F * 40, CastMachine.energyAt(s, sword, 120), 1.0E-3);
        assertEquals(21.0F, CastMachine.energyAt(s, sword, 100_000));
    }

    @Test
    void theGearWaitsOnTheWorldsClock() {
        Reading r = read("orb", BLADE);
        CastState s = full(BLADE);
        CastMachine.attempt(s, r, 0, false, LIM);
        long ready = s.readyAt;
        assertEquals(CastMachine.Kind.NOT_READY, CastMachine.attempt(s, r, ready - 1, false, LIM).kind());
        assertEquals(CastMachine.Kind.CAST, CastMachine.attempt(s, r, ready, false, LIM).kind());
    }

    @Test
    void aFreeCastCostsNothing() {
        Reading r = read("burst storm", HostSpec.of(Host.SWORD));
        CastState s = new CastState(0, 0, -1, 0, 0, 0);
        CastMachine.Outcome o = CastMachine.attempt(s, r, 10, true, LIM);
        assertEquals(CastMachine.Kind.CAST, o.kind());
        assertEquals(0, o.cost());
        assertEquals(0.0F, s.energy);
    }

    @Test
    void aCursorLeftFromARowPointsAtTheOneCast() {
        // a weapon whose row had three casts was saved with its cursor on the third
        CastState s = new CastState(70, 0, -1, 2, 0, Long.MIN_VALUE / 4);
        Reading one = read("spark", BLADE);
        assertEquals(0, CastMachine.next(s, one, 0, LIM), "the cursor never points past the one cast");
        assertEquals(CastMachine.Kind.CAST, CastMachine.attempt(s, one, 0, false, LIM).kind());
        assertEquals(0, s.cursor);
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
    void plainRuneHitsKnockALittleLoose() {
        Siphon sp = new Siphon(20);
        assertEquals(2.0F, sp.hit(1, false), 1.0E-4);
        assertEquals(0.0F, sp.hit(1, false), "each creature once");
        assertEquals(3.0F, sp.weakSpot(1, false), 1.0E-4);
        assertEquals(2.0F, sp.reaction(1, false), 1.0E-4);
        // 7 so far; without Siphon a cast gets back at most 8
        assertEquals(1.0F, sp.kill(1, false), 1.0E-4);
        assertEquals(0.0F, sp.hit(2, false), "past the plain cap");
        assertEquals(3.0F, sp.hit(1, true), 1.0E-4, "Siphon counts the creature again, with its own share");
        assertEquals(11.0F, sp.paid(), 1.0E-4);
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
