package com.explorationremix.rune.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reading rules, one by one, with the rows the design doc uses. */
class ReaderTest {
    static final HostSpec SWORD = HostSpec.of(Host.SWORD);
    /** A netherite sword: six slots awake at depth 2, the longest row any gear has. */
    static final HostSpec BLADE = HostSpec.of(Host.SWORD).withBonus(1);
    static final HostSpec BOW = HostSpec.of(Host.BOW);
    /** A sword with room for long test rows (9 slots awake at depth 2). */
    static final HostSpec LONG = HostSpec.of(Host.SWORD).withBonus(4).withMax(200);

    static Reading read(String row, HostSpec spec) { return Reader.read(Row.parse(row), spec, 2); }

    static String acts(Part p) { return p.acts().toString(); }

    // ------------------------------------------------------------------------------------------------ parts

    @Test
    void speakingOrder() {
        Reading r = read("spark on_hit burst", BLADE);
        assertEquals(1, r.casts().size());
        CastReading c = r.cast(0);
        assertEquals(2, c.parts().size());
        assertEquals("[spark]", acts(c.part(0)));
        assertEquals("[burst]", acts(c.part(1)));
        assertEquals(RuneId.ON_HIT, c.part(1).relay());
        assertEquals(8 + 14, c.cost());
        assertEquals("A spark; where it hits, a burst.", Explain.cast(c));
    }

    @Test
    void aPartFiresTogether() {
        CastReading c = read("spark orb", BLADE).cast(0);
        assertEquals(1, c.parts().size());
        assertEquals("[spark, orb]", acts(c.part(0)));
        assertEquals("A spark and an orb.", Explain.cast(c));
    }

    @Test
    void modifiersReachToTheirRight() {
        assertEquals("[swift+spark, swift+orb]", acts(read("swift spark orb", BLADE).cast(0).part(0)));
        assertEquals("[spark, swift+orb]", acts(read("spark swift orb", BLADE).cast(0).part(0)));
    }

    @Test
    void modifiersStopAtTheNextRelay() {
        CastReading c = read("heavy spark on_hit burst", BLADE).cast(0);
        assertEquals("[heavy+spark]", acts(c.part(0)));
        assertEquals("[burst]", acts(c.part(1)));
    }

    @Test
    void sameModifiersAddUpToACap() {
        Reading r = Reader.read(Row.parse("swift swift swift swift spark"), LONG, 2);
        assertEquals("[swift+swift+swift+spark]", acts(r.cast(0).part(0)));
        assertEquals(Note.State.GREY, r.note(0).state());
        assertEquals(Note.Reason.AT_CAP, r.note(0).reason());
        assertEquals("only 3 Swift count on one action", Explain.note(r, 0));
        for (int i = 1; i <= 4; i++) assertTrue(r.note(i).live(), "slot " + i);
    }

    @Test
    void theNearestElementWins() {
        Reading r = read("frost ember spark", BLADE);
        assertEquals("[ember+spark]", acts(r.cast(0).part(0)));
        assertEquals(Note.Reason.OVERRULED, r.note(0).reason());
        assertEquals("Ember is nearer: an action takes one element", Explain.note(r, 0));
        // each element can still win the actions it is nearest
        assertEquals("[frost+spark, ember+orb]", acts(read("frost spark ember orb", BLADE).cast(0).part(0)));
    }

    @Test
    void aRelayHandsTheRestToEveryCarrierBeforeIt() {
        Reading r = read("spark orb on_hit burst", BLADE);
        CastReading c = r.cast(0);
        Plan p = Plan.expand(c, Limits.DEFAULT);
        assertEquals(2, p.roots.size());
        for (Plan.Node n : p.roots) {
            assertEquals(1, n.children.size());
            assertEquals(RuneId.BURST, n.children.get(0).act.rune);
            assertEquals(RuneId.ON_HIT, n.relay);
        }
        assertEquals(8 + 14 + 2 * 14, c.cost());
        assertEquals("A spark and an orb; where each hits, a burst.", Explain.cast(c));
    }

    @Test
    void instantsReleaseAtOnce() {
        CastReading c = read("wide burst on_hit spark", BLADE).cast(0);
        assertEquals("[wide+burst]", acts(c.part(0)));
        assertEquals("[spark]", acts(c.part(1)));
        assertEquals("A wide burst; at once, from there, a spark.", Explain.cast(c));
    }

    @Test
    void movingOneRuneChangesTheJob() {
        // the doc's pair: a ranged blast, and a blast around you that then fires a spark
        CastReading ranged = read("spark on_hit wide burst", BLADE).cast(0);
        CastReading close = read("wide burst on_hit spark", BLADE).cast(0);
        assertEquals("[spark]", acts(ranged.part(0)));
        assertEquals("[wide+burst]", acts(close.part(0)));
        assertEquals(ranged.cost(), close.cost());
    }

    @Test
    void fanCopiesWhatFollowsInItsPart() {
        CastReading c = read("fan spark on_hit burst", BLADE).cast(0);
        assertEquals("[3xspark]", acts(c.part(0)));
        Plan p = Plan.expand(c, Limits.DEFAULT);
        assertEquals(3, p.roots.size());
        assertEquals(6, p.planned);
        assertEquals(3 * 8 + 3 * 14, c.cost());
        assertEquals("Three sparks in a fan; where each hits, a burst.", Explain.cast(c));
        // the copies spread out from the middle one
        assertEquals(0.0F, p.roots.get(0).yaw);
        assertEquals(-12.0F, p.roots.get(1).yaw);
        assertEquals(12.0F, p.roots.get(2).yaw);
    }

    @Test
    void fanOnlyCopiesThingsWithADirection() {
        Reading r = read("fan burst", BLADE);
        assertEquals("[burst]", acts(r.cast(0).part(0)));
        assertEquals(Note.Reason.CANT_CHANGE, r.note(0).reason());
        assertEquals("Fan doesn't change a Burst", Explain.note(r, 0));
        // but it copies the directional actions it reaches
        assertEquals("[3xspark, burst]", acts(read("fan spark burst", BLADE).cast(0).part(0)));
    }

    @Test
    void twoFansMakeFiveCopies() {
        assertEquals("[5xspark]", acts(read("fan fan spark", BLADE).cast(0).part(0)));
    }

    @Test
    void castBreaksSplitARow() {
        Reading r = read("tide fan shard | storm spark", BLADE);
        assertEquals(2, r.casts().size());
        assertEquals("[3xtide+shard]", acts(r.cast(0).part(0)));
        assertEquals("[storm+spark]", acts(r.cast(1).part(0)));
        assertEquals(0, r.note(0).cast());
        assertEquals(1, r.note(3).cast());
        assertEquals(List.of(
                "Cast 1 of 2: Three tide shards in a fan. (24 energy, 0.5 s)",
                "Cast 2 of 2: A storm spark. (11 energy, 0.5 s)"), Explain.lines(r));
    }

    @Test
    void greyRunesSayWhy() {
        Reading r = read("spark swift", BLADE);
        assertEquals(Note.Reason.NOTHING_RIGHT, r.note(1).reason());
        assertEquals("Swift has nothing to its right", Explain.note(r, 1));
        Reading b = read("bounce burst", BLADE);
        assertEquals("Bounce doesn't change a Burst", Explain.note(b, 0));
        assertEquals(14, b.cast(0).cost(), "a grey rune costs nothing");
    }

    @Test
    void aRelayWithNothingToRideOnDoesNothing() {
        Reading r = read("on_hit spark", BLADE);
        assertEquals(Note.Reason.NOTHING_BEFORE, r.note(0).reason());
        assertEquals("On hit has nothing before it to ride on", Explain.note(r, 0));
        assertEquals(1, r.cast(0).parts().size());
        assertEquals("[spark]", acts(r.cast(0).part(0)));
        Reading after = read("spark on_hit", BLADE);
        assertEquals("On hit has nothing after it", Explain.note(after, 1));
    }

    @Test
    void aGreyRelayJoinsThePartsOnEitherSide() {
        // the first relay carries nothing (Wide is no action): it does nothing, and Wide joins the spark's part
        Reading r = Reader.read(Row.parse("spark on_hit wide after burst"), LONG, 2);
        CastReading c = r.cast(0);
        assertEquals(Note.Reason.NOTHING_AFTER, r.note(1).reason());
        assertEquals(Note.Reason.NOTHING_RIGHT, r.note(2).reason());
        assertEquals(2, c.parts().size());
        assertEquals(RuneId.AFTER, c.part(1).relay());
        assertEquals("[burst]", acts(c.part(1)));
    }

    @Test
    void partsPastTheLimitAreTooDeep() {
        Reading r = Reader.read(Row.parse("spark on_hit spark on_hit spark on_hit spark on_hit spark"), LONG.withBonus(4), 2);
        CastReading c = r.cast(0);
        assertEquals(4, c.parts().size());
        assertEquals(Note.Reason.TOO_DEEP, r.note(7).reason());
        assertEquals(Note.Reason.TOO_DEEP, r.note(8).reason());
        assertEquals("too deep: four parts at most", Explain.note(r, 8));
        assertEquals(4 * 8, c.cost());
    }

    @Test
    void copiesPastTheLimitAreCutWholeAndNotPaid() {
        // five sparks, three shards on each, a burst on each shard: 5 + 15 + 15 = 35 wanted, 16 cast
        Reading r = Reader.read(Row.parse("fan fan spark on_hit fan shard on_hit burst"), LONG, 2);
        CastReading c = r.cast(0);
        assertEquals(35, c.wanted());
        assertEquals(16, c.planned());
        assertTrue(c.cut());
        // 5 sparks, then 11 of the 15 shards; no burst fits
        assertEquals(5 * 8 + 11 * 5, c.cost());
        Plan p = Plan.expand(c, Limits.DEFAULT);
        int n = 0;
        for (Plan.Node root : p.roots) n += root.size();
        assertEquals(16, n);
        assertTrue(Explain.costs(r, c).contains("16 of 35 cast"));
    }

    @Test
    void sleepingSlotsAreSkippedAndKept() {
        Reading home = Reader.read(Row.parse("spark burst on_hit wide orb"), SWORD, 0);
        assertEquals(3, home.awake());
        assertEquals(Note.State.ASLEEP, home.note(3).state());
        assertEquals("wakes in the Blue Reaches", Explain.note(home, 3));
        assertEquals("wakes in the Deep Gardens", Explain.note(home, 4));
        // [spark][burst][on hit] at home: the relay has nothing after it
        assertEquals("[spark, burst]", acts(home.cast(0).part(0)));
        Reading deep = Reader.read(Row.parse("spark burst on_hit wide orb"), SWORD, 2);
        assertEquals(5, deep.awake());
        assertEquals(2, deep.cast(0).parts().size());
        // a bow keeps the shorter row
        assertEquals(2, Reader.read(Row.empty(4), BOW, 0).awake());
        assertEquals(4, Reader.read(Row.empty(4), BOW, 2).awake());
    }

    @Test
    void diamondGearWakesOneMore() {
        HostSpec diamond = SWORD.withBonus(1);
        assertEquals(4, Reader.read(Row.empty(6), diamond, 0).awake());
        assertEquals(6, Reader.read(Row.empty(6), diamond, 2).awake());
    }

    @Test
    void aCastDearerThanTheGearHoldsIsRed() {
        HostSpec small = SWORD.withMax(20);
        Reading r = Reader.read(Row.parse("spark | orb on_hit burst"), small.withBonus(2), 2);
        assertFalse(r.cast(0).red());
        assertTrue(r.cast(1).red());
        assertEquals("(costs 28, this sword holds 20)", Explain.costs(r, r.cast(1)));
    }

    // ------------------------------------------------------------------------------------------------ bow

    @Test
    void theArrowIsTheFirstCarrier() {
        CastReading c = read("seek pierce", BOW).cast(0);
        assertEquals(1, c.parts().size());
        assertEquals("[seek+pierce+arrow]", acts(c.part(0)));
        assertEquals(6, c.cost());
        assertEquals("A seeking piercing arrow.", Explain.cast(c));
    }

    @Test
    void whatFollowsTheArrowGoesOffWhereItLands() {
        CastReading c = read("ember spark burst", BOW).cast(0);
        assertEquals("[ember+arrow]", acts(c.part(0)));
        assertEquals(RuneId.ON_HIT, c.part(1).relay());
        assertEquals(-1, c.part(1).relaySlot());
        assertEquals("[spark, burst]", acts(c.part(1)));
        assertEquals("An ember arrow; where it hits, a spark and a burst.", Explain.cast(c));
    }

    @Test
    void aRelayRightAfterTheFrontModifiersHooksTheArrow() {
        CastReading air = read("after wide burst", BOW).cast(0);
        assertEquals(RuneId.AFTER, air.part(1).relay());
        assertEquals(0, air.part(1).relaySlot());
        assertEquals("An arrow; a moment later, wherever it has got to, a wide burst.", Explain.cast(air));
        // the doc's crossbow: a homing bolt that goes off over the group a moment after leaving
        CastReading volley = read("seek after storm burst", HostSpec.of(Host.CROSSBOW)).cast(0);
        assertEquals("[seek+arrow]", acts(volley.part(0)));
        assertEquals("[storm+burst]", acts(volley.part(1)));
    }

    @Test
    void fanAtTheFrontLoosesThreeArrows() {
        CastReading c = read("fan on_hit burst", BOW).cast(0);
        assertEquals("[3xarrow]", acts(c.part(0)));
        assertEquals(3 * 14, c.cost());
    }

    @Test
    void modifiersThatCantShapeAnArrowAreGrey() {
        Reading r = read("arc", BOW);
        assertTrue(r.casts().isEmpty(), "a bare arrow is a plain shot, not a cast");
        assertEquals("Arc doesn't change an arrow", Explain.note(r, 0));
    }

    // ------------------------------------------------------------------------------------------------ the doc's builds

    @Test
    void theDocsExampleBuildsReadAsWritten() {
        assertEquals("An arrow; where it hits, three shards in a fan.", Explain.cast(read("on_hit fan shard", BOW).cast(0)));
        assertEquals("A dash; when you hit something, a siphoning cleave.", Explain.cast(read("dash on_hit siphon cleave", SWORD).cast(0)));
        assertEquals("A storm burst.", Explain.cast(read("storm burst", SWORD).cast(0)));
        assertEquals("A lobbed orb; a moment later, wherever it has got to, a wide frost field.",
                Explain.cast(read("arc orb after wide frost field", BLADE).cast(0)));
        assertEquals("A fast spark; where it hits, a reel.", Explain.cast(read("swift spark on_hit reel", SWORD).cast(0)));
        assertEquals("An orb; where it hits, three shards in a fan; where each hits, a burst.",
                Explain.cast(read("orb on_hit fan shard on_hit burst", BLADE).cast(0)));
        assertEquals("Three bouncing glow motes in a fan.", Explain.cast(read("bounce fan glow", BLADE).cast(0)));
        assertEquals("A burst.", Explain.cast(read("burst", SWORD).cast(0)));
        // every one of them fits its gear at the depth the doc gives
        assertEquals(6, Reader.read(Row.empty(6), BLADE, 2).awake());
    }

    @Test
    void nothingToCast() {
        Reading r = read("", BLADE);
        assertTrue(r.casts().isEmpty());
        assertEquals(List.of("No runes."), Explain.lines(r));
        assertNull(r.cast(0));
        assertEquals(List.of("Nothing here casts."), Explain.lines(read("swift wide", BLADE)));
    }
}
