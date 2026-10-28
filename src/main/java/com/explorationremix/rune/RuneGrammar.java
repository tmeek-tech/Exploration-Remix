package com.explorationremix.rune;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a tool's runes left to right, Noita style. A trigger starts a sentence; modifiers in front of an effect
 * change that effect; effects fire when the sentence's trigger does; passives apply always. A sentence's cooldown is
 * the sum of its runes' costs, in ticks.
 */
public final class RuneGrammar {
    private RuneGrammar() {}

    public record Clause(List<Rune> modifiers, Rune effect) {}

    public record Sentence(int index, Rune trigger, List<Clause> clauses, int cooldownTicks) {
        public boolean has(String modifierId) {
            for (Clause c : clauses) for (Rune m : c.modifiers()) if (m.id().getPath().equals(modifierId)) return true;
            return false;
        }
    }

    public record Parsed(List<Sentence> sentences, List<Rune> passives, List<Rune> orphans) {
        public boolean isEmpty() { return sentences.isEmpty() && passives.isEmpty(); }
    }

    public static Parsed parse(List<Rune> runes) {
        List<Sentence> sentences = new ArrayList<>();
        List<Rune> passives = new ArrayList<>();
        List<Rune> orphans = new ArrayList<>();
        Rune trigger = null;
        List<Clause> clauses = new ArrayList<>();
        List<Rune> pendingMods = new ArrayList<>();
        int cost = 0;
        for (Rune r : runes) {
            switch (r.kind()) {
                case PASSIVE -> passives.add(r);
                case TRIGGER -> {
                    if (trigger != null) sentences.add(new Sentence(sentences.size(), trigger, List.copyOf(clauses), cost));
                    trigger = r;
                    clauses = new ArrayList<>();
                    pendingMods = new ArrayList<>();
                    cost = r.cost();
                }
                case MODIFIER -> {
                    if (trigger == null) orphans.add(r);
                    else { pendingMods.add(r); cost += r.cost(); }
                }
                case EFFECT -> {
                    if (trigger == null) orphans.add(r);
                    else {
                        clauses.add(new Clause(List.copyOf(pendingMods), r));
                        pendingMods = new ArrayList<>();
                        cost += r.cost();
                    }
                }
            }
        }
        if (trigger != null) sentences.add(new Sentence(sentences.size(), trigger, List.copyOf(clauses), cost));
        // Modifiers left dangling at the end of a sentence do nothing but still cost cooldown; report them as orphans.
        orphans.addAll(pendingMods);
        return new Parsed(List.copyOf(sentences), List.copyOf(passives), List.copyOf(orphans));
    }
}
