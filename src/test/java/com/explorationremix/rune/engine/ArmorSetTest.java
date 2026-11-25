package com.explorationremix.rune.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Armor's runes (0.22.6): one effect socket and two collect sockets a piece; effect runes are each piece's own; a collect
 * rune wakes on three pieces and is a full set on four.
 */
class ArmorSetTest {
    /** Helmet, chestplate, leggings, boots; null where nothing is worn. */
    static ArmorSet set(String helmet, String chest, String legs, String boots) {
        return new ArmorSet(new ArmorRunes[]{runes(helmet), runes(chest), runes(legs), runes(boots)});
    }

    static ArmorRunes runes(String s) { return s == null ? null : ArmorRunes.parse(s); }

    @Test
    void eachPieceHasEffectRunesOfItsOwn() {
        for (Piece p : Piece.values()) {
            List<RuneId> own = RuneId.ofKind(RuneKind.EFFECT, Fits.of(p));
            assertEquals(3, own.size(), p.word + " has three effect runes");
            for (RuneId e : own) {
                for (Piece q : Piece.values()) assertEquals(p == q, e.fits(q), e.id + " is " + p.word + "'s alone");
            }
        }
        assertEquals(12, RuneId.ofKind(RuneKind.EFFECT).size());
        for (RuneId c : RuneId.ofKind(RuneKind.COLLECT)) {
            for (Piece p : Piece.values()) assertTrue(c.fits(p), c.id + " goes on any piece");
        }
    }

    @Test
    void anEffectRuneWorksOnlyOnItsOwnPiece() {
        ArmorSet s = set("lantern", "glide", "climb", "leap");
        for (RuneId e : List.of(RuneId.LANTERN, RuneId.GLIDE, RuneId.CLIMB, RuneId.LEAP)) assertTrue(s.has(e), e.id);
        assertFalse(s.has(RuneId.FEATHER));
        // a helmet that somehow holds a boots' rune (set by hand, or a piece from before) does nothing with it
        ArmorSet wrong = new ArmorSet(new ArmorRunes[]{new ArmorRunes(RuneId.LEAP, null, null), null, null, null});
        assertFalse(wrong.has(RuneId.LEAP));
        assertNull(wrong.effect(Piece.HELMET));
        assertFalse(s.has(RuneId.MEND), "a collect rune is never an effect at work");
    }

    @Test
    void aCollectRuneWakesOnThreePiecesAndIsAFullSetOnFour() {
        ArmorSet two = set("mend", "mend", null, "vigor");
        assertEquals(2, two.count(RuneId.MEND));
        assertFalse(two.awake(RuneId.MEND));
        assertEquals(Note.Reason.TOO_FEW, two.note(Piece.HELMET, Socket.COLLECT_1).reason());
        assertEquals("Mend is on two pieces; it wakes on three", Explain.note(two.note(Piece.HELMET, Socket.COLLECT_1), "Mend"));
        ArmorSet three = set("mend", "mend", "mend", "vigor");
        assertTrue(three.awake(RuneId.MEND));
        assertFalse(three.full(RuneId.MEND));
        assertEquals(1, three.tier(RuneId.MEND));
        assertTrue(three.note(Piece.HELMET, Socket.COLLECT_1).live());
        ArmorSet four = set("mend", "mend", "mend", "mend vigor");
        assertTrue(four.full(RuneId.MEND));
        assertEquals(2, four.tier(RuneId.MEND));
        assertEquals(0, four.tier(RuneId.VIGOR));
        assertEquals(List.of(RuneId.MEND, RuneId.VIGOR), four.collects());
        assertEquals("Mend 4/4", Explain.tally(RuneId.MEND, 4));
        assertTrue(Explain.collectLine(RuneId.MEND, 4).startsWith("Mend, full set: "));
        assertTrue(Explain.collectLine(RuneId.MEND, 1).startsWith("Mend is on one piece yet; on three it wakes: "));
    }

    @Test
    void aCollectRuneCountsOncePerPiece() {
        assertThrows(IllegalArgumentException.class, () -> ArmorRunes.parse("mend mend"));
        assertNull(new ArmorRunes(null, RuneId.MEND, RuneId.MEND).second(), "the second would do nothing");
        assertEquals(1, set("mend vigor", null, null, null).count(RuneId.MEND));
        assertEquals(new ArmorRunes(null, RuneId.VIGOR, null), new ArmorRunes(null, RuneId.MEND, RuneId.VIGOR).with(Socket.COLLECT_1, RuneId.VIGOR),
                "setting a rune the other socket holds moves it");
        assertThrows(IllegalArgumentException.class, () -> ArmorRunes.parse("mend vigor fleet"), "two collect sockets");
    }

    @Test
    void weaponAndToolRunesNeverGoInArmor() {
        assertThrows(IllegalArgumentException.class, () -> ArmorRunes.parse("spark"));
        assertTrue(new ArmorRunes(RuneId.SPARK, RuneId.SWIFT, RuneId.HASTE).isEmpty());
        assertEquals(ArmorRunes.EMPTY, ArmorRunes.EMPTY.with(Socket.ACTIVE, RuneId.SPARK));
    }

    @Test
    void aPieceNotWornCountsForNothing() {
        ArmorSet s = set(null, "mend", "mend", "mend");
        assertFalse(s.worn(Piece.HELMET));
        assertNull(s.piece(Piece.HELMET));
        assertEquals(Note.State.EMPTY, s.note(Piece.HELMET, Socket.EFFECT).state());
        assertTrue(s.awake(RuneId.MEND));
        assertTrue(ArmorSet.NONE.isEmpty());
        assertTrue(set("_", null, null, null).isEmpty());
    }
}
