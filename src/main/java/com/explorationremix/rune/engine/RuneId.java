package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.explorationremix.rune.engine.RuneKind.ACTION;
import static com.explorationremix.rune.engine.RuneKind.MODIFIER;
import static com.explorationremix.rune.engine.RuneKind.PASSIVE;

/**
 * Every rune, with the numbers the rules read: energy cost, cast delay and recovery (ticks), and for active runes their
 * shape, base damage and the XP level their second phase wakes at. A weapon holds one rune of each kind (RuneKind): its
 * active rune is what it casts, its modifier changes that cast, and its passive is what the weapon does all the time.
 * A modifier's cost is paid by every copy of the cast it changes; passives cost nothing.
 *
 * <p>{@link #ARROW} is not a rune: it stands for a bow's or crossbow's own arrow, the first carrier of every cast
 * those weapons make. It has no item and costs nothing.
 *
 * <p>{@code layer} is where a rune is first found: 0 the Upper Caves, 1 the Blue Reaches, 2 the Deep Gardens. The id
 * of Echo is "after" (it was "After a moment" when runes sat in rows), so runes already found keep working.
 */
public enum RuneId {
    // ---- active runes: shape, directional (Fan can copy it), damage, and the XP level of the second phase
    SPARK("spark", "Spark", ACTION, 8, 3, 3, 0, Shape.BOLT, true, 4.0F, 12,
            "Fork: where it strikes a foe, it leaps on to two more nearby."),
    SHARD("shard", "Shard", ACTION, 5, 2, 2, 0, Shape.BOLT, true, 3.0F, 6,
            "Splinter: three smaller shards fly on from whatever it hits."),
    ORB("orb", "Orb", ACTION, 14, 8, 6, 0, Shape.BOLT, true, 7.0F, 24,
            "Gravity: it drags foes near its path in toward it, and bursts where it stops."),
    GLOW("glow", "Glow", ACTION, 4, 3, 2, 0, Shape.BOLT, true, 0.0F, 4,
            "Scatter: lights spray onto the rock all round where it stops, and foes near the one it marks glow too."),
    BURST("burst", "Burst", ACTION, 14, 6, 6, 0, Shape.INSTANT, false, 6.0F, 20,
            "Aftershock: a wider blast follows a moment later."),
    CLEAVE("cleave", "Cleave", ACTION, 10, 6, 4, 0, Shape.INSTANT, true, 6.0F, 16,
            "Wave: the slash flies on for ten blocks through everything in its way."),
    FIELD("field", "Field", ACTION, 16, 8, 8, 1, Shape.FIELD, false, 1.0F, 14,
            "Pull: it draws creatures in to its middle, and lasts twice as long."),
    BARRIER("barrier", "Barrier", ACTION, 12, 6, 8, 0, Shape.INSTANT, false, 0.0F, 10,
            "Bulwark: a bigger wall that shoves aside whatever stands where it rises, for 16 seconds."),
    DASH("dash", "Dash", ACTION, 10, 8, 8, 0, Shape.SELF, false, 0.0F, 18,
            "Charge: you slam through what you dash into, and can dash twice before you land."),
    REEL("reel", "Reel", ACTION, 8, 5, 6, 1, Shape.INSTANT, false, 0.0F, 8,
            "Long line: it reaches half as far again, and pulls twice before you land."),
    // ---- modifiers: they change the active rune (on a bow, Swift, Seek, Pierce and Fan shape the arrow itself)
    SWIFT("swift", "Swift", MODIFIER, 2, -2, 0, 0),
    HEAVY("heavy", "Heavy", MODIFIER, 4, 4, 2, 0),
    WIDE("wide", "Wide", MODIFIER, 4, 2, 2, 0),
    SEEK("seek", "Seek", MODIFIER, 3, 1, 1, 1),
    ARC("arc", "Arc", MODIFIER, 1, 0, 0, 0),
    BOUNCE("bounce", "Bounce", MODIFIER, 2, 0, 1, 0),
    PIERCE("pierce", "Pierce", MODIFIER, 3, 1, 1, 1),
    SIPHON("siphon", "Siphon", MODIFIER, 4, 2, 2, 2),
    FAN("fan", "Fan", MODIFIER, 0, 2, 2, 1),
    // ---- passives: free; what the weapon does all the time
    EMBER("ember", "Ember", PASSIVE, 0, 0, 0, 2, Element.EMBER),
    FROST("frost", "Frost", PASSIVE, 0, 0, 0, 2, Element.FROST),
    STORM("storm", "Storm", PASSIVE, 0, 0, 0, 2, Element.STORM),
    TIDE("tide", "Tide", PASSIVE, 0, 0, 0, 2, Element.TIDE),
    ON_HIT("on_hit", "On hit", PASSIVE, 0, 0, 0, 1, Element.NONE),
    AFTER("after", "Echo", PASSIVE, 0, 0, 0, 1, Element.NONE),
    LANTERN("lantern", "Lantern", PASSIVE, 0, 0, 0, 0, Element.NONE),
    THIRST("thirst", "Thirst", PASSIVE, 0, 0, 0, 1, Element.NONE),
    // ---- not a rune: a bow's own arrow
    ARROW("arrow", "Arrow", ACTION, 0, 0, 0, -1, Shape.BOLT, true, 0.0F, 0, "");

    /** Path of the id ({@code explorationremix:rune_<id>} for the item). */
    public final String id;
    public final String title;
    public final RuneKind kind;
    public final int cost, delay, recovery;
    /** 0 Upper Caves, 1 Blue Reaches, 2 Deep Gardens; -1 not a rune. */
    public final int layer;
    /** Active runes only. */
    public final Shape shape;
    /** Active runes only: Fan can copy it (it has a direction). */
    public final boolean directional;
    /** Active runes only: base damage before the weapon's power and the modifier (Field: per pulse). */
    public final float damage;
    /** Active runes only: the XP level at which the second phase wakes (it works while the player's level is this or more). */
    public final int phaseLevel;
    /** Active runes only: what the second phase does, in a line. */
    public final String phaseTwo;
    /** The four element passives only. */
    public final Element element;

    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, Shape shape, boolean directional, float damage,
           int phaseLevel, String phaseTwo) {
        this(id, title, kind, cost, delay, recovery, layer, shape, directional, damage, phaseLevel, phaseTwo, Element.NONE);
    }

    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer) {
        this(id, title, kind, cost, delay, recovery, layer, null, false, 0.0F, 0, "", Element.NONE);
    }

    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, Element element) {
        this(id, title, kind, cost, delay, recovery, layer, null, false, 0.0F, 0, "", element);
    }

    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, Shape shape, boolean directional,
           float damage, int phaseLevel, String phaseTwo, Element element) {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.cost = cost;
        this.delay = delay;
        this.recovery = recovery;
        this.layer = layer;
        this.shape = shape;
        this.directional = directional;
        this.damage = damage;
        this.phaseLevel = phaseLevel;
        this.phaseTwo = phaseTwo;
        this.element = element;
    }

    private static final List<RuneId> RUNES;

    static {
        List<RuneId> l = new ArrayList<>();
        for (RuneId r : values()) if (r != ARROW) l.add(r);
        RUNES = Collections.unmodifiableList(l);
    }

    /** Every real rune (not {@link #ARROW}), in the order of the rune list. */
    public static List<RuneId> runes() { return RUNES; }

    /** The runes of one kind, in the rune list's order. */
    public static List<RuneId> ofKind(RuneKind k) {
        List<RuneId> out = new ArrayList<>();
        for (RuneId r : RUNES) if (r.kind == k) out.add(r);
        return out;
    }

    /** The rune with this id path, or null (never {@link #ARROW}); "echo" is Echo too. */
    public static RuneId byId(String id) {
        if (id == null || id.isEmpty()) return null;
        String s = id.toLowerCase(Locale.ROOT);
        if (s.equals("echo")) return AFTER;
        for (RuneId r : RUNES) if (r.id.equals(s)) return r;
        return null;
    }

    /** The rune at this place in {@link #values()} (as the table's buttons name them), or null; never {@link #ARROW}. */
    public static RuneId byOrdinal(int i) {
        RuneId[] all = values();
        return i >= 0 && i < all.length && all[i] != ARROW ? all[i] : null;
    }

    public boolean isAction() { return kind == ACTION; }

    public boolean isModifier() { return kind == MODIFIER; }

    public boolean isPassive() { return kind == PASSIVE; }

    /** One of the four element passives. */
    public boolean isElement() { return element != Element.NONE; }

    /** Hurts what it hits: elements, Heavy and Siphon can change it. */
    public boolean damaging() {
        return this == SPARK || this == SHARD || this == ORB || this == BURST || this == CLEAVE || this == FIELD || this == ARROW;
    }

    /** An active rune Echo can repeat: one that strikes, or lights (never a wall, a throw or a pull). */
    public boolean echoes() {
        return this == SPARK || this == SHARD || this == ORB || this == GLOW || this == BURST || this == CLEAVE || this == FIELD;
    }

    /** A bolt that flies through the air: Seek, Arc, Bounce and Pierce can change it. */
    public boolean flies() { return this == SPARK || this == SHARD || this == ORB || this == GLOW; }

    /** On a bow or crossbow, this modifier shapes the arrow itself rather than the rune the arrow carries. */
    public boolean shapesArrow() { return this == SWIFT || this == SEEK || this == PIERCE || this == FAN; }

    /** Whether this modifier changes the active rune {@code a}. A modifier that changes nothing shows grey. */
    public boolean changes(RuneId a) {
        if (a == null || !a.isAction()) return false;
        return switch (this) {
            case SWIFT -> a.flies() || a == ARROW || a == DASH || a == REEL;
            case HEAVY -> a.damaging();
            case WIDE -> a == BURST || a == FIELD || a == CLEAVE || a == BARRIER || a == SPARK || a == SHARD || a == ORB;
            case SEEK -> a.flies() || a == ARROW;
            case ARC, BOUNCE -> a.flies();
            case PIERCE -> a == SPARK || a == SHARD || a == ORB || a == ARROW;
            case SIPHON -> a.damaging();
            case FAN -> a.directional;
            default -> false;
        };
    }

    /** The lower-case name, for readings ("a fast spark"). */
    public String noun() {
        return switch (this) {
            case GLOW -> "glow mote";
            case BARRIER -> "barrier wall";
            default -> title.toLowerCase(Locale.ROOT);
        };
    }

    public String plural() {
        String n = noun();
        if (n.endsWith("sh") || n.endsWith("ch") || n.endsWith("s")) return n + "es";
        return n + "s";
    }
}
