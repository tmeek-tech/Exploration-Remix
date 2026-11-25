package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Puts a weapon's runes in plain words: "A fast frost spark (10 energy, 0.4 s)." The enchanting table, the weapon's
 * tooltip and the commands all use these words, so they never disagree.
 */
public final class Explain {
    private Explain() {}

    /** Everything the runes do, a line each: the cast, the passive, the second phase. */
    public static List<String> lines(Reading r) {
        List<String> out = new ArrayList<>();
        out.add(castLine(r));
        String p = passiveLine(r);
        if (!p.isEmpty()) out.add(p);
        String ph = phaseLine(r);
        if (!ph.isEmpty()) out.add(ph);
        return out;
    }

    /** What the weapon casts, with its cost: "A fast spark (10 energy, 0.4 s)." */
    public static String castLine(Reading r) {
        CastReading c = r.cast();
        if (c == null) {
            if (r.loadout().isEmpty()) return "No runes yet: set them at an enchanting table.";
            return r.spec().host().arrowCarrier ? "No active rune: its arrows fly plain." : "No active rune: it casts nothing.";
        }
        return cast(c) + " " + costs(r, c);
    }

    /** What one cast does: "A fast spark." / "An arrow; where it hits, a burst." */
    public static String cast(CastReading c) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < c.parts().size(); k++) {
            Part part = c.part(k);
            if (k > 0) b.append("; ").append(c.part(k - 1).instances() > 1 ? "where each hits" : "where it hits").append(", ");
            b.append(part(part));
        }
        b.append('.');
        return capital(b.toString());
    }

    /** "(26 energy, 0.4 s)", or why it can't fire. */
    public static String costs(Reading r, CastReading c) {
        if (c.red()) return "(costs " + c.cost() + ", this " + r.spec().host().word + " holds " + Math.round(r.spec().maxEnergy()) + ")";
        StringBuilder b = new StringBuilder("(").append(c.cost()).append(" energy, ").append(seconds(Math.max(c.delay(), r.recovery())));
        if (c.cut()) b.append("; ").append(c.planned()).append(" of ").append(c.wanted()).append(" cast");
        return b.append(')').toString();
    }

    /** What the passive does on this weapon, or why it does nothing; "" with no passive. */
    public static String passiveLine(Reading r) {
        if (r.note(Socket.PASSIVE).grey()) return note(r, Socket.PASSIVE) + ".";
        RuneId p = r.passive();
        if (p == null) return "";
        boolean bow = r.spec().host().arrowCarrier;
        RuneId a = r.active();
        if (p.isElement()) {
            String carrier = bow ? "Every arrow" : "Every full swing";
            return carrier + " carries " + p.element.word + (a == null ? "." : ", and so does the " + a.noun() + ".");
        }
        return switch (p) {
            case ON_HIT -> bow ? "Every third arrow that hits a foe sets the " + a.noun() + " off there, for free."
                    : "Every third full swing that hits a foe casts the " + a.noun() + " for free.";
            case AFTER -> "Energy it gains past full makes its next cast a third stronger.";
            case RECALL -> "Its arrows fly back to you once they land.";
            case QUICKDRAW -> r.spec().host() == Host.CROSSBOW ? "It loads in half the time." : "It draws in half the time.";
            default -> "";
        };
    }

    /** How much more energy hits knock loose with Thirst awake on the armor, and with its full set. */
    public static final float THIRST_GAIN = 1.4F, THIRST_FULL_GAIN = 1.8F;
    /** Overflow: how much stronger the cast after energy came in past full is. */
    public static final float OVERFLOW = 4.0F / 3.0F;

    /** The active rune's second phase: "Phase two at level 12: ..." or, awake, "Phase two: ...". "" with no active. */
    public static String phaseLine(Reading r) {
        RuneId a = r.active();
        if (a == null) return "";
        return r.empowered() ? "Phase two: " + a.phaseTwo : "Phase two at level " + a.phaseLevel + ": " + a.phaseTwo;
    }

    /** Why a socket does nothing, or "" if it is live or empty. */
    public static String note(Reading r, Socket s) {
        Note n = r.note(s);
        RuneId rune = r.loadout().get(s);
        String name = rune == null ? "" : rune.title;
        return switch (n.reason()) {
            case NONE -> "";
            case NO_ACTIVE -> name + " needs an active rune";
            case CANT_CHANGE -> name + " doesn't change " + article(n.arg()) + " " + n.arg().toLowerCase(Locale.ROOT);
            case ARROW_CANT -> name + " doesn't change an arrow";
            case CANT_STRENGTHEN -> name + " can't strengthen " + article(n.arg()) + " " + n.arg().toLowerCase(Locale.ROOT);
            case WRONG_GEAR -> name + " is not " + article(n.arg()) + " " + n.arg() + " rune";
            case TOO_FEW -> name + " is on " + pieces(n.arg()) + "; it wakes on three";
        };
    }

    private static String pieces(String count) {
        return switch (count) {
            case "1" -> "one piece";
            case "2" -> "two pieces";
            default -> count + " pieces";
        };
    }

    public static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1f s", ticks / 20.0);
    }

    // ------------------------------------------------------------------------------------------------ armor

    /**
     * How far each collect rune on a worn piece has come, a line each (an effect rune says what it does in its own
     * words, the same at every level). Empty with no collect runes.
     */
    public static List<String> collectLines(ArmorSet set, Piece p) {
        List<String> out = new ArrayList<>();
        ArmorRunes a = set.piece(p);
        if (a == null) return out;
        for (RuneId c : new RuneId[]{a.first(), a.second()}) if (c != null) out.add(collectLine(c, set.count(c)));
        return out;
    }

    /**
     * How far a collect rune's set has come with {@code count} pieces carrying it: asleep below three, awake at three, a
     * full set at four.
     */
    public static String collectLine(RuneId c, int count) {
        if (count >= ArmorSet.FULL) return c.title + ", full set: " + c.full;
        if (count >= ArmorSet.WAKES) return c.title + ", three pieces: " + c.three + " A fourth makes the full set: " + lowerFirst(c.full);
        String on = count <= 0 ? "on no piece" : "on " + pieces(Integer.toString(count));
        return c.title + " is " + on + " yet; on three it wakes: " + lowerFirst(c.three);
    }

    /** "Mend 2/4", the short tally the table shows beside the worn pieces. */
    public static String tally(RuneId c, int count) { return c.title + " " + count + "/" + ArmorSet.FULL; }

    static String lowerFirst(String s) {
        if (s.isEmpty() || (s.length() > 1 && Character.isUpperCase(s.charAt(1)))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------------------------------------ words

    static String part(Part p) {
        List<String> words = new ArrayList<>();
        for (Act a : p.acts()) words.add(act(a));
        return join(words);
    }

    static String act(Act a) {
        List<String> adj = new ArrayList<>();
        for (RuneId m : Act.MODS) if (a.count(m) > 0) adj.add(adjective(m));
        if (a.element != Element.NONE) adj.add(a.element.word);
        String noun = a.copies > 1 ? (a.rune == RuneId.ARROW ? "arrows" : a.rune.plural()) : a.rune.noun();
        String body = adj.isEmpty() ? noun : String.join(" ", adj) + " " + noun;
        if (a.copies > 1) return number(a.copies) + " " + body + " in a fan";
        return article(body) + " " + body;
    }

    static String adjective(RuneId m) {
        return switch (m) {
            case SWIFT -> "fast";
            case HEAVY -> "heavy";
            case WIDE -> "wide";
            case SEEK -> "seeking";
            case ARC -> "returning";
            case BOUNCE -> "bouncing";
            case PIERCE -> "piercing";
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
