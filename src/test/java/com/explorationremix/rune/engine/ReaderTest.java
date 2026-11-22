package com.explorationremix.rune.engine;

import com.explorationremix.rune.menu.RuneTableMenu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The socket rules, one by one: one active rune, one modifier, one passive, and the active rune's second phase. */
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
        assertEquals(Loadout.parse("spark _ echo"), new Loadout(RuneId.SPARK, null, RuneId.AFTER), "echo is Echo's other name");
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
    void theTablesButtonsFitInAByte() {
        // the table's buttons name a socket and a rune in one number, which the game sends as a byte
        assertTrue(RuneId.values().length < RuneTableMenu.STRIDE, "every rune's number fits under one socket's stride");
        assertTrue(Socket.values().length * RuneTableMenu.STRIDE <= RuneTableMenu.ENCHANT);
        assertTrue(RuneTableMenu.ENCHANT <= Byte.MAX_VALUE);
        for (Socket s : Socket.values()) {
            for (RuneId r : RuneId.ofKind(s.kind)) {
                int id = RuneTableMenu.setButton(s, r);
                assertEquals(s, Socket.byIndex(id / RuneTableMenu.STRIDE));
                assertEquals(r, RuneId.byOrdinal(id % RuneTableMenu.STRIDE - 1));
            }
            assertEquals(0, RuneTableMenu.clearButton(s) % RuneTableMenu.STRIDE);
        }
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
    void onHitAndEchoNeedAnActiveRune() {
        assertEquals(Note.Reason.NO_ACTIVE, read("on_hit", SWORD).note(Socket.PASSIVE).reason());
        assertEquals("On hit needs an active rune", Explain.note(read("on_hit", SWORD), Socket.PASSIVE));
        assertEquals(Note.Reason.NO_ACTIVE, read("echo", SWORD).note(Socket.PASSIVE).reason());
        assertTrue(read("spark on_hit", SWORD).passiveIs(RuneId.ON_HIT));
        assertEquals("Every third full swing that hits a foe casts the spark for free.", Explain.passiveLine(read("spark on_hit", SWORD)));
    }

    @Test
    void echoRepeatsOnlyWhatStrikesOrLights() {
        for (RuneId a : RuneId.ofKind(RuneKind.ACTION)) {
            Reading r = Reader.read(new Loadout(a, null, RuneId.AFTER), SWORD, 0);
            boolean repeats = a != RuneId.BARRIER && a != RuneId.DASH && a != RuneId.REEL;
            assertEquals(repeats, r.passiveIs(RuneId.AFTER), a.id);
            if (!repeats) assertEquals(Note.Reason.CANT_ECHO, r.note(Socket.PASSIVE).reason(), a.id);
        }
        assertEquals("Echo can't repeat a dash", Explain.note(read("dash echo", SWORD), Socket.PASSIVE));
    }

    @Test
    void lanternAndThirstAlwaysWork() {
        assertTrue(read("lantern", SWORD).passiveIs(RuneId.LANTERN));
        assertTrue(read("thirst", BOW).passiveIs(RuneId.THIRST));
        assertNull(read("lantern", SWORD).cast(), "a passive alone casts nothing");
    }

    // ------------------------------------------------------------------------------------------------ bows

    @Test
    void aBowsCastRidesItsArrow() {
        CastReading c = read("spark", BOW).cast();
        assertEquals(2, c.parts().size());
        assertEquals("[arrow]", acts(c.part(0)));
        assertEquals(RuneId.ON_HIT, c.part(1).relay());
        assertEquals("[spark]", acts(c.part(1)));
        assertEquals(8, c.cost(), "the arrow itself is free");
        assertEquals("An arrow; where it hits, a spark.", Explain.cast(c));
        assertEquals(RuneId.SPARK, c.active().rune);
    }

    @Test
    void someModifiersShapeABowsArrow() {
        CastReading seek = read("spark seek", BOW).cast();
        assertEquals("[seek+arrow]", acts(seek.part(0)));
        assertEquals("[spark]", acts(seek.part(1)));
        CastReading heavy = read("spark heavy", BOW).cast();
        assertEquals("[arrow]", acts(heavy.part(0)));
        assertEquals("[heavy+spark]", acts(heavy.part(1)), "Heavy changes the rune the arrow carries");
        CastReading fan = read("spark fan", BOW).cast();
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
        assertTrue(read("spark", BOW, at).cast().part(1).acts().get(0).empowered, "on a bow, the rune the arrow carries");
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
