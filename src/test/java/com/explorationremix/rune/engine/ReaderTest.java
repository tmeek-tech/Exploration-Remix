package com.explorationremix.rune.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The socket rules, one by one: one active rune, one modifier, one passive, and the active rune's second phase. Since
 * 0.22.6 swords and bows have actives of their own (modifiers and elements are shared), and Echo is Overflow.
 */
class ReaderTest {
    static final HostSpec SWORD = HostSpec.of(Host.SWORD);
    static final HostSpec BOW = HostSpec.of(Host.BOW);

    static Reading read(String runes, HostSpec spec) { return read(runes, spec, 0); }

    static Reading read(String runes, HostSpec spec, int level) { return Reader.read(Loadout.parse(runes), spec, level); }

    static String acts(Part p) { return p.acts().toString(); }

    // ------------------------------------------------------------------------------------------------ the loadout

    @Test
    void runesGoToTheSocketOfTheirKindInAnyOrder() {
        Loadout l = Loadout.parse("ember swift spark");
        assertEquals(RuneId.SPARK, l.active());
        assertEquals(RuneId.SWIFT, l.modifier());
        assertEquals(RuneId.EMBER, l.passive());
        assertEquals("spark swift ember", l.toString());
        assertEquals(Loadout.parse("spark _ echo"), new Loadout(RuneId.SPARK, null, RuneId.AFTER), "echo is Overflow's old name");
        assertEquals(Loadout.parse("spark _ overflow"), new Loadout(RuneId.SPARK, null, RuneId.AFTER));
        assertEquals(Loadout.parse("return"), Loadout.parse("arc"), "Return keeps Arc's id");
        assertThrows(IllegalArgumentException.class, () -> Loadout.parse("spark orb"), "two active runes");
        assertThrows(IllegalArgumentException.class, () -> Loadout.parse("moonbeam"));
    }

    @Test
    void aRuneOfTheWrongKindIsNeverKept() {
        Loadout l = new Loadout(RuneId.SWIFT, RuneId.SPARK, RuneId.ORB);
        assertTrue(l.isEmpty());
        assertNull(Loadout.EMPTY.with(Socket.ACTIVE, RuneId.EMBER).active());
        assertEquals(RuneId.ORB, Loadout.EMPTY.with(RuneId.ORB).with(RuneId.HEAVY).active());
    }

    @Test
    void armorAndToolRunesNeverGoInAWeapon() {
        assertThrows(IllegalArgumentException.class, () -> Loadout.parse("spark lantern"), "an armor rune");
        assertThrows(IllegalArgumentException.class, () -> Loadout.parse("haste"), "a tool rune");
        assertTrue(new Loadout(RuneId.LANTERN, RuneId.MEND, RuneId.HASTE).isEmpty());
        assertEquals(Loadout.EMPTY, Loadout.EMPTY.with(Socket.TOOL, RuneId.HASTE), "a weapon has no tool socket");
    }

    @Test
    void theTableNamesSocketsAndRunesByTheirPlace() {
        // a table edit names the socket and the rune by their places in the lists (RuneNet.TableEdit), -1 for none
        for (Socket s : Socket.values()) assertEquals(s, Socket.byIndex(s.ordinal()));
        for (RuneId r : RuneId.runes()) assertEquals(r, RuneId.byOrdinal(r.ordinal()));
        assertNull(RuneId.byOrdinal(-1));
        assertNull(RuneId.byOrdinal(RuneId.ARROW.ordinal()), "a bow's own arrow is never set");
        assertNull(Socket.byIndex(Socket.values().length));
    }

    // ------------------------------------------------------------------------------------------------ the cast

    @Test
    void noRunesCastNothing() {
        Reading r = read("", SWORD);
        assertNull(r.cast());
        assertFalse(r.castable());
        assertEquals("No runes yet: set them at an enchanting table.", Explain.castLine(r));
    }

    @Test
    void theActiveRuneIsTheCast() {
        Reading r = read("spark", SWORD);
        CastReading c = r.cast();
        assertEquals(1, r.casts().size());
        assertEquals(1, c.parts().size());
        assertEquals("[spark]", acts(c.part(0)));
        assertEquals(8, c.cost());
        assertEquals(Host.SWORD.baseDelay + RuneId.SPARK.delay, c.delay());
        assertEquals(Host.SWORD.baseRecovery + RuneId.SPARK.recovery, r.recovery());
        assertEquals("A spark.", Explain.cast(c));
        assertTrue(r.note(Socket.ACTIVE).live());
    }

    @Test
    void theModifierChangesTheActiveRune() {
        Reading r = read("spark swift", SWORD);
        CastReading c = r.cast();
        assertEquals("[swift+spark]", acts(c.part(0)));
        assertEquals(8 + 2, c.cost());
        assertEquals(Host.SWORD.baseDelay + RuneId.SPARK.delay + RuneId.SWIFT.delay, c.delay());
        assertEquals("A fast spark.", Explain.cast(c));
        assertTrue(r.note(Socket.MODIFIER).live());
        assertEquals("A heavy orb.", Explain.cast(read("orb heavy", SWORD).cast()));
    }

    @Test
    void aModifierThatChangesNothingIsGreyAndFree() {
        Reading r = read("burst swift", SWORD);
        assertTrue(r.note(Socket.MODIFIER).grey());
        assertEquals(Note.Reason.CANT_CHANGE, r.note(Socket.MODIFIER).reason());
        assertEquals("Swift doesn't change a burst", Explain.note(r, Socket.MODIFIER));
        assertEquals(14, r.cast().cost(), "Swift costs nothing here");
        assertEquals(Host.SWORD.baseDelay + RuneId.BURST.delay, r.cast().delay());
        assertEquals("[burst]", acts(r.cast().part(0)));
    }

    @Test
    void aModifierAloneOnASwordDoesNothing() {
        Reading r = read("heavy", SWORD);
        assertNull(r.cast());
        assertEquals(Note.Reason.NO_ACTIVE, r.note(Socket.MODIFIER).reason());
        assertEquals("Heavy needs an active rune", Explain.note(r, Socket.MODIFIER));
        assertEquals("No active rune: it casts nothing.", Explain.castLine(r));
    }

    @Test
    void fanCopiesADirectionalRuneThreeTimes() {
        Reading r = read("spark fan", SWORD);
        CastReading c = r.cast();
        assertEquals("[3xspark]", acts(c.part(0)));
        assertEquals(3 * 8, c.cost(), "every copy pays");
        assertEquals("Three sparks in a fan.", Explain.cast(c));
        Reading burst = read("burst fan", SWORD);
        assertTrue(burst.note(Socket.MODIFIER).grey(), "a burst has no direction to fan");
        assertEquals("[burst]", acts(burst.cast().part(0)));
    }

    @Test
    void aCastDearerThanTheWeaponHoldsIsRed() {
        HostSpec small = SWORD.withMax(10);
        Reading r = read("orb", small);
        assertTrue(r.cast().red());
        assertFalse(r.castable());
        assertEquals("(costs 14, this sword holds 10)", Explain.costs(r, r.cast()));
    }

    // ------------------------------------------------------------------------------------------------ the passive

    @Test
    void anElementPassiveIsTheActiveRunesElementToo() {
        Reading r = read("spark frost", SWORD);
        assertEquals(Element.FROST, r.element());
        assertEquals("[frost+spark]", acts(r.cast().part(0)));
        assertEquals("A frost spark.", Explain.cast(r.cast()));
        assertEquals("Every full swing carries frost, and so does the spark.", Explain.passiveLine(r));
        assertEquals("Every arrow carries frost.", Explain.passiveLine(read("frost", BOW)));
        assertEquals(8, r.cast().cost(), "passives are free");
    }

    @Test
    void onHitAndOverflowNeedAnActiveRune() {
        assertEquals(Note.Reason.NO_ACTIVE, read("on_hit", SWORD).note(Socket.PASSIVE).reason());
        assertEquals("On hit needs an active rune", Explain.note(read("on_hit", SWORD), Socket.PASSIVE));
        assertEquals(Note.Reason.NO_ACTIVE, read("overflow", SWORD).note(Socket.PASSIVE).reason());
        assertEquals(Note.Reason.NO_ACTIVE, read("overflow", BOW).note(Socket.PASSIVE).reason());
        assertTrue(read("spark on_hit", SWORD).passiveIs(RuneId.ON_HIT));
        assertEquals("Every third full swing that hits a foe casts the spark for free.", Explain.passiveLine(read("spark on_hit", SWORD)));
    }

    @Test
    void overflowStrengthensOnlyWhatStrikes() {
        for (RuneId a : RuneId.ofKind(RuneKind.ACTION, Fits.SWORD)) {
            Reading r = Reader.read(new Loadout(a, null, RuneId.AFTER), SWORD, 0);
            boolean strikes = a != RuneId.BARRIER && a != RuneId.DASH && a != RuneId.REEL;
            assertEquals(strikes, r.passiveIs(RuneId.AFTER), a.id);
            if (!strikes) assertEquals(Note.Reason.CANT_STRENGTHEN, r.note(Socket.PASSIVE).reason(), a.id);
        }
        assertEquals("Overflow can't strengthen a dash", Explain.note(read("dash overflow", SWORD), Socket.PASSIVE));
        assertEquals("Energy it gains past full makes its next cast a third stronger.", Explain.passiveLine(read("spark overflow", SWORD)));
        // a bow's arrow always strikes, whatever rides it
        for (RuneId a : RuneId.ofKind(RuneKind.ACTION, Fits.BOW)) {
            assertTrue(Reader.read(new Loadout(a, null, RuneId.AFTER), BOW, 0).passiveIs(RuneId.AFTER), a.id);
        }
    }

    @Test
    void aBowsPassivesWorkWithoutAnActiveRune() {
        assertTrue(read("recall", BOW).passiveIs(RuneId.RECALL));
        assertTrue(read("quickdraw", BOW).passiveIs(RuneId.QUICKDRAW));
        assertNull(read("recall", BOW).cast(), "a passive alone looses plain arrows");
        assertEquals("Its arrows fly back to you once they land.", Explain.passiveLine(read("recall", BOW)));
        assertEquals("It loads in half the time.", Explain.passiveLine(read("quickdraw", HostSpec.of(Host.CROSSBOW))));
        assertEquals(Note.Reason.WRONG_GEAR, read("recall", SWORD).note(Socket.PASSIVE).reason(), "Recall is a bow's");
    }

    // ------------------------------------------------------------------------------------------------ bows

    @Test
    void aBowsCastRidesItsArrow() {
        CastReading c = read("volley", BOW).cast();
        assertEquals(2, c.parts().size());
        assertEquals("[arrow]", acts(c.part(0)));
        assertEquals(RuneId.ON_HIT, c.part(1).relay());
        assertEquals("[volley]", acts(c.part(1)));
        assertEquals(RuneId.VOLLEY.cost, c.cost(), "the arrow itself is free");
        assertEquals("An arrow; where it hits, a volley.", Explain.cast(c));
        assertEquals(RuneId.VOLLEY, c.active().rune);
    }

    @Test
    void swordsAndBowsHaveActivesOfTheirOwn() {
        for (RuneId a : RuneId.ofKind(RuneKind.ACTION)) {
            assertTrue(a.fits(Fits.SWORD) != a.fits(Fits.BOW), a.id + " is a sword's or a bow's, not both");
            HostSpec other = a.fits(Fits.SWORD) ? BOW : SWORD;
            Reading r = Reader.read(new Loadout(a, null, null), other, 0);
            assertEquals(Note.Reason.WRONG_GEAR, r.note(Socket.ACTIVE).reason(), a.id);
            assertNull(r.cast(), a.id + " does nothing on the other weapon");
        }
        assertEquals("Spark is not a bow rune", Explain.note(read("spark", BOW), Socket.ACTIVE));
        // modifiers and the elements are shared
        assertTrue(read("volley heavy ember", BOW).note(Socket.MODIFIER).live());
        assertEquals(Element.EMBER, read("volley heavy ember", BOW).element());
    }

    @Test
    void someModifiersShapeABowsArrow() {
        CastReading seek = read("volley seek", BOW).cast();
        assertEquals("[seek+arrow]", acts(seek.part(0)));
        assertEquals("[volley]", acts(seek.part(1)));
        CastReading heavy = read("volley heavy", BOW).cast();
        assertEquals("[arrow]", acts(heavy.part(0)));
        assertEquals("[heavy+volley]", acts(heavy.part(1)), "Heavy changes the rune the arrow carries");
        CastReading fan = read("volley fan", BOW).cast();
        assertEquals("[3xarrow]", acts(fan.part(0)), "Fan looses three arrows, each carrying the rune");
        assertEquals(3, fan.part(0).instances());
    }

    @Test
    void aBowWithAModifierAloneStillShapesItsArrows() {
        Reading r = read("seek", BOW);
        assertEquals("[seek+arrow]", acts(r.cast().part(0)));
        assertNull(r.cast().active());
        assertTrue(r.note(Socket.MODIFIER).live());
        Reading wide = read("wide", BOW);
        assertNull(wide.cast());
        assertEquals("Wide doesn't change an arrow", Explain.note(wide, Socket.MODIFIER));
    }

    // ------------------------------------------------------------------------------------------------ the second phase

    @Test
    void theSecondPhaseWakesAtTheRunesLevel() {
        int at = RuneId.SPARK.phaseLevel;
        Reading below = read("spark", SWORD, at - 1);
        assertFalse(below.empowered());
        assertEquals(1, below.levelsToGo());
        assertEquals("[spark]", acts(below.cast().part(0)));
        assertEquals("Phase two at level " + at + ": " + RuneId.SPARK.phaseTwo, Explain.phaseLine(below));
        Reading awake = read("spark", SWORD, at);
        assertTrue(awake.empowered());
        assertEquals(0, awake.levelsToGo());
        assertEquals("[spark II]", acts(awake.cast().part(0)));
        assertEquals("Phase two: " + RuneId.SPARK.phaseTwo, Explain.phaseLine(awake));
        assertEquals(below.cast().cost(), awake.cast().cost(), "the second phase costs no more");
        int volley = RuneId.VOLLEY.phaseLevel;
        assertTrue(read("volley", BOW, volley).cast().part(1).acts().get(0).empowered, "on a bow, the rune the arrow carries");
        assertFalse(read("volley", BOW, volley - 1).cast().part(1).acts().get(0).empowered);
    }

    @Test
    void everyActiveRuneHasItsOwnPhaseLevel() {
        java.util.Set<Integer> levels = new java.util.HashSet<>();
        for (RuneId a : RuneId.ofKind(RuneKind.ACTION)) {
            assertTrue(a.phaseLevel > 0 && a.phaseLevel <= 30, a.id + " wakes at a level players reach");
            assertFalse(a.phaseTwo.isBlank(), a.id + " says what its second phase does");
            levels.add(a.phaseLevel);
        }
        assertEquals(RuneId.ofKind(RuneKind.ACTION).size(), levels.size(), "no two at the same level");
    }
}
