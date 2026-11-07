package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Puts a reading in plain words, in the row's own order: "Three fast sparks in a fan; where each hits, a wide burst."
 * The rune screen, the gear's tooltip and the commands all use these words, so they never disagree.
 */
public final class Explain {
    private Explain() {}

    /** The whole row, one line per cast. */
    public static List<String> lines(Reading r) {
        List<String> out = new ArrayList<>();
        int n = r.casts().size();
        for (CastReading c : r.casts()) {
            String head = n > 1 ? "Cast " + (c.index() + 1) + " of " + n + ": " : "";
            out.add(head + cast(c) + " " + costs(r, c));
        }
        if (n == 0) out.add(r.row().isEmpty() ? "No runes." : "Nothing here casts.");
        return out;
    }

    /** What one cast does: "A spark; where it hits, a burst." */
    public static String cast(CastReading c) {
        StringBuilder b = new StringBuilder();
        long carriers = 1;
        for (int k = 0; k < c.parts().size(); k++) {
            Part part = c.part(k);
            if (k > 0) {
                Part prev = c.part(k - 1);
                b.append("; ").append(relay(part.relay(), prev, carriers > 1)).append(", ");
            }
            b.append(part(part));
            carriers *= Math.max(1, part.instances());
        }
        b.append('.');
        return capital(b.toString());
    }

    /** "26 energy, 0.4 s", or why it can't fire. */
    public static String costs(Reading r, CastReading c) {
        if (c.red()) return "(costs " + c.cost() + ", this " + r.spec().host().word + " holds " + Math.round(r.spec().maxEnergy()) + ")";
        StringBuilder b = new StringBuilder("(").append(c.cost()).append(" energy, ").append(seconds(c.delay()));
        if (c.cut()) b.append("; ").append(c.planned()).append(" of ").append(c.wanted()).append(" cast");
        return b.append(')').toString();
    }

    /** Why a slot does nothing, or "" if it is live or empty. */
    public static String note(Reading r, int slot) {
        Note n = r.note(slot);
        RuneId rune = r.row().get(slot);
        String name = rune == null ? "" : rune.title;
        return switch (n.reason()) {
            case NONE -> "";
            case NOTHING_RIGHT -> name + " has nothing to its right";
            case CANT_CHANGE -> name + " doesn't change " + article(n.arg()) + " " + n.arg();
            case ARROW_CANT -> name + " doesn't change an arrow";
            case AT_CAP -> "only " + n.arg() + " " + name + " count on one action";
            case OVERRULED -> n.arg() + " is nearer: an action takes one element";
            case NOTHING_BEFORE -> name + " has nothing before it to ride on";
            case NOTHING_AFTER -> name + " has nothing after it";
            case TOO_DEEP -> "too deep: " + number(Integer.parseInt(n.arg())) + " parts at most";
            case ASLEEP -> wakes(Integer.parseInt(n.arg()));
        };
    }

    /** "wakes in the Blue Reaches". */
    public static String wakes(int depth) {
        return switch (depth) {
            case 0 -> "awake at home";
            case 1 -> "wakes in the Blue Reaches";
            case 2 -> "wakes in the Deep Gardens";
            default -> "never wakes on this gear";
        };
    }

    public static String depthName(int depth) {
        return switch (depth) {
            case 1 -> "the Blue Reaches";
            case 2 -> "the Deep Gardens";
            default -> "home";
        };
    }

    public static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1f s", ticks / 20.0);
    }

    // ------------------------------------------------------------------------------------------------ words

    static String part(Part p) {
        List<String> words = new ArrayList<>();
        for (Act a : p.acts()) words.add(act(a));
        return join(words);
    }

    static String act(Act a) {
        List<String> adj = new ArrayList<>();
        for (RuneId m : Act.MODS) {
            int k = a.count(m);
            if (k > 0) adj.add(adjective(m, k));
        }
        if (a.element != Element.NONE) adj.add(a.element.word);
        String noun = a.copies > 1 ? (a.rune == RuneId.ARROW ? "arrows" : a.rune.plural()) : a.rune.noun();
        String body = adj.isEmpty() ? noun : String.join(" ", adj) + " " + noun;
        if (a.copies > 1) return number(a.copies) + " " + body + " in a fan";
        return article(body) + " " + body;
    }

    static String relay(RuneId relay, Part prev, boolean many) {
        boolean instant = true, self = true, field = true;
        for (Act a : prev.acts()) {
            instant &= a.rune.shape == Shape.INSTANT;
            self &= a.rune.shape == Shape.SELF;
            field &= a.rune.shape == Shape.FIELD;
        }
        many |= prev.instances() > 1;
        if (instant) return many ? "at once, from each" : "at once, from there";
        if (relay == RuneId.AFTER) {
            if (self) return "a moment later, from where you are";
            if (field) return many ? "a moment later, from the middle of each" : "a moment later, from its middle";
            return many ? "a moment later, from each" : "a moment later, wherever it has got to";
        }
        if (self) return "when you hit something";
        if (field) return many ? "when each first touches a creature" : "when it first touches a creature";
        return many ? "where each hits" : "where it hits";
    }

    static String adjective(RuneId m, int k) {
        return switch (m) {
            case SWIFT -> k == 1 ? "fast" : k == 2 ? "faster" : "fastest";
            case HEAVY -> k == 1 ? "heavy" : k == 2 ? "heavier" : "heaviest";
            case WIDE -> k == 1 ? "wide" : k == 2 ? "wider" : "widest";
            case SEEK -> k == 1 ? "seeking" : "keen-seeking";
            case ARC -> k == 1 ? "lobbed" : "high-lobbed";
            case BOUNCE -> k == 1 ? "bouncing" : k == 2 ? "twice-bouncing" : "thrice-bouncing";
            case PIERCE -> k == 1 ? "piercing" : k == 2 ? "twice-piercing" : "thrice-piercing";
            case SIPHON -> "siphoning";
            default -> m.title.toLowerCase(Locale.ROOT);
        };
    }

    static String join(List<String> words) {
        if (words.isEmpty()) return "nothing";
        if (words.size() == 1) return words.get(0);
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    static String article(String word) {
        if (word.isEmpty()) return "a";
        char c = Character.toLowerCase(word.charAt(0));
        return "aeiou".indexOf(c) >= 0 ? "an" : "a";
    }

    static String number(int n) {
        return switch (n) {
            case 2 -> "two";
            case 3 -> "three";
            case 4 -> "four";
            case 5 -> "five";
            default -> Integer.toString(n);
        };
    }

    static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
