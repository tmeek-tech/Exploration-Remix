package com.explorationremix.rune;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The grammar is the contract between the editor, the tooltip and the caster, so it gets pinned down here. */
class RuneGrammarTest {
    private static Rune rune(String id, RuneKind kind, int cost) {
        return new Rune(new ResourceLocation("explorationremix", id), kind, cost, 0, 10, List.of(), Map.of(), "");
    }

    private static final Rune ON_HIT = rune("on_hit", RuneKind.TRIGGER, 20);
    private static final Rune ON_JUMP = rune("on_jump", RuneKind.TRIGGER, 30);
    private static final Rune TWICE = rune("twice", RuneKind.MODIFIER, 40);
    private static final Rune WIDE = rune("wide", RuneKind.MODIFIER, 30);
    private static final Rune SPARK = rune("spark", RuneKind.EFFECT, 30);
    private static final Rune FROST = rune("frost", RuneKind.EFFECT, 30);
    private static final Rune LEAP = rune("leap", RuneKind.EFFECT, 25);
    private static final Rune KEEN = rune("keen_edge", RuneKind.PASSIVE, 0);

    @Test
    void modifiersBindToTheNextEffectOnly() {
        RuneGrammar.Parsed p = RuneGrammar.parse(List.of(ON_HIT, TWICE, SPARK, FROST));
        assertEquals(1, p.sentences().size());
        RuneGrammar.Sentence s = p.sentences().get(0);
        assertEquals(2, s.clauses().size());
        assertEquals(List.of(TWICE), s.clauses().get(0).modifiers());
        assertEquals(SPARK, s.clauses().get(0).effect());
        assertTrue(s.clauses().get(1).modifiers().isEmpty());
        assertEquals(FROST, s.clauses().get(1).effect());
        assertTrue(s.has("twice"));
        assertFalse(s.has("wide"));
    }

    @Test
    void eachTriggerStartsANewSentenceAndCooldownIsTheSumOfCosts() {
        RuneGrammar.Parsed p = RuneGrammar.parse(List.of(ON_HIT, SPARK, ON_JUMP, WIDE, LEAP));
        assertEquals(2, p.sentences().size());
        assertEquals(20 + 30, p.sentences().get(0).cooldownTicks());
        assertEquals(30 + 30 + 25, p.sentences().get(1).cooldownTicks());
        assertEquals(0, p.sentences().get(0).index());
        assertEquals(1, p.sentences().get(1).index());
    }

    @Test
    void runesBeforeAnyTriggerAndDanglingModifiersAreOrphans() {
        RuneGrammar.Parsed p = RuneGrammar.parse(List.of(SPARK, WIDE, ON_HIT, FROST, TWICE));
        assertEquals(1, p.sentences().size());
        assertEquals(List.of(SPARK, WIDE, TWICE), p.orphans());
        // the dangling modifier still costs cooldown, as the tooltip says
        assertEquals(20 + 30 + 40, p.sentences().get(0).cooldownTicks());
    }

    @Test
    void passivesApplyRegardlessOfPosition() {
        RuneGrammar.Parsed p = RuneGrammar.parse(List.of(KEEN, ON_HIT, KEEN, SPARK));
        assertEquals(List.of(KEEN, KEEN), p.passives());
        assertEquals(1, p.sentences().size());
        assertFalse(p.isEmpty());
        assertTrue(RuneGrammar.parse(List.of()).isEmpty());
    }

    @Test
    void aSentenceNeverContainsItsOwnTriggerSoNothingCanLoop() {
        // A trigger can only start a sentence; it can never be an effect inside one, which is what keeps
        // "on hit -> on hit" impossible at the grammar level (the caster guards the runtime side).
        RuneGrammar.Parsed p = RuneGrammar.parse(List.of(ON_HIT, ON_HIT, SPARK));
        assertEquals(2, p.sentences().size());
        assertTrue(p.sentences().get(0).clauses().isEmpty());
        for (RuneGrammar.Sentence s : p.sentences()) for (RuneGrammar.Clause c : s.clauses()) {
            assertNotEquals(RuneKind.TRIGGER, c.effect().kind());
            for (Rune m : c.modifiers()) assertEquals(RuneKind.MODIFIER, m.kind());
        }
    }
}
