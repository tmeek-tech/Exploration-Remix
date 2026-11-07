package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.explorationremix.rune.engine.RuneKind.ACTION;
import static com.explorationremix.rune.engine.RuneKind.ELEMENT;
import static com.explorationremix.rune.engine.RuneKind.FAN;
import static com.explorationremix.rune.engine.RuneKind.MODIFIER;
import static com.explorationremix.rune.engine.RuneKind.RELAY;

/**
 * Every rune, with the numbers the rules read: energy cost, cast delay and recovery (ticks), and for actions their
 * shape and base damage. Costs are per action actually cast; a modifier's or element's cost is paid again by every
 * action instance it changes. Delay counts once per rune in a cast, recovery once per rune in the row.
 *
 * <p>{@link #ARROW} is not a rune: it stands for a bow's or crossbow's own arrow, the first carrier of every cast
 * those gear make. It has no item and costs nothing.
 *
 * <p>{@code layer} is where a rune is first found: 0 the Upper Caves (simple actions and modifiers), 1 the Blue
 * Reaches (relays, Fan and the rest of the actions), 2 the Deep Gardens (elements and Siphon).
 */
public enum RuneId {
    // ---- actions
    SPARK("spark", "Spark", ACTION, 8, 3, 3, 0, Shape.BOLT, true, 4.0F),
    SHARD("shard", "Shard", ACTION, 5, 2, 2, 0, Shape.BOLT, true, 3.0F),
    ORB("orb", "Orb", ACTION, 14, 8, 6, 0, Shape.BOLT, true, 7.0F),
    GLOW("glow", "Glow", ACTION, 4, 3, 2, 0, Shape.BOLT, true, 0.0F),
    BURST("burst", "Burst", ACTION, 14, 6, 6, 0, Shape.INSTANT, false, 6.0F),
    CLEAVE("cleave", "Cleave", ACTION, 10, 6, 4, 0, Shape.INSTANT, true, 6.0F),
    FIELD("field", "Field", ACTION, 16, 8, 8, 1, Shape.FIELD, false, 1.0F),
    BARRIER("barrier", "Barrier", ACTION, 12, 6, 8, 0, Shape.INSTANT, false, 0.0F),
    DASH("dash", "Dash", ACTION, 10, 8, 8, 0, Shape.SELF, false, 0.0F),
    REEL("reel", "Reel", ACTION, 8, 5, 6, 1, Shape.INSTANT, false, 0.0F),
    // ---- modifiers (cap: how many of one kind can count on an action)
    SWIFT("swift", "Swift", MODIFIER, 2, -2, 0, 0, 3),
    HEAVY("heavy", "Heavy", MODIFIER, 4, 4, 2, 0, 3),
    WIDE("wide", "Wide", MODIFIER, 4, 2, 2, 0, 3),
    SEEK("seek", "Seek", MODIFIER, 3, 1, 1, 1, 2),
    ARC("arc", "Arc", MODIFIER, 1, 0, 0, 0, 2),
    BOUNCE("bounce", "Bounce", MODIFIER, 2, 0, 1, 0, 3),
    PIERCE("pierce", "Pierce", MODIFIER, 3, 1, 1, 1, 3),
    SIPHON("siphon", "Siphon", MODIFIER, 4, 2, 2, 2, 1),
    // ---- elements: one per action, the nearest to its left wins
    EMBER("ember", "Ember", ELEMENT, 3, 1, 1, 2, Element.EMBER),
    FROST("frost", "Frost", ELEMENT, 3, 1, 1, 2, Element.FROST),
    STORM("storm", "Storm", ELEMENT, 3, 1, 1, 2, Element.STORM),
    TIDE("tide", "Tide", ELEMENT, 3, 1, 1, 2, Element.TIDE),
    // ---- relays and Fan: free; only what they make costs
    ON_HIT("on_hit", "On hit", RELAY, 0, 0, 0, 1, 1),
    AFTER("after", "After a moment", RELAY, 0, 0, 0, 1, 1),
    FAN("fan", "Fan", RuneKind.FAN, 0, 2, 2, 1, 2),
    // ---- not a rune: a bow's own arrow
    ARROW("arrow", "Arrow", ACTION, 0, 0, 0, -1, Shape.BOLT, true, 0.0F);

    /** Path of the id ({@code explorationremix:rune_<id>} for the item). */
    public final String id;
    public final String title;
    public final RuneKind kind;
    public final int cost, delay, recovery;
    /** 0 Upper Caves, 1 Blue Reaches, 2 Deep Gardens; -1 not a rune. */
    public final int layer;
    /** Actions only. */
    public final Shape shape;
    /** Actions only: Fan can copy it (it has a direction). */
    public final boolean directional;
    /** Actions only: base damage before the gear's power and modifiers (Field: per pulse). */
    public final float damage;
    /** Modifiers and Fan: how many of one kind count on an action. */
    public final int cap;
    /** Elements only. */
    public final Element element;

    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, Shape shape, boolean directional, float damage) {
        this(id, title, kind, cost, delay, recovery, layer, shape, directional, damage, 0, Element.NONE);
    }

    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, int cap) {
        this(id, title, kind, cost, delay, recovery, layer, null, false, 0.0F, cap, Element.NONE);
    }

    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, Element element) {
        this(id, title, kind, cost, delay, recovery, layer, null, false, 0.0F, 1, element);
    }

    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, Shape shape, boolean directional,
           float damage, int cap, Element element) {
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
        this.cap = cap;
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

    /** The rune with this id path, or null (never {@link #ARROW}). */
    public static RuneId byId(String id) {
        if (id == null || id.isEmpty()) return null;
        String s = id.toLowerCase(Locale.ROOT);
        for (RuneId r : RUNES) if (r.id.equals(s)) return r;
        return null;
    }

    public boolean isAction() { return kind == ACTION; }

    /** Hurts what it hits: elements, Heavy and Siphon can change it. */
    public boolean damaging() {
        return this == SPARK || this == SHARD || this == ORB || this == BURST || this == CLEAVE || this == FIELD || this == ARROW;
    }

    /** A bolt that flies through the air: Seek, Arc, Bounce and Pierce can change it. */
    public boolean flies() { return this == SPARK || this == SHARD || this == ORB || this == GLOW; }

    /**
     * Whether this modifier, element or Fan changes the action {@code a}. A rune that changes none of the actions to its
     * right in its part does nothing and shows grey.
     */
    public boolean changes(RuneId a) {
        if (!a.isAction()) return false;
        return switch (this) {
            case SWIFT -> a.flies() || a == ARROW || a == DASH || a == REEL;
            case HEAVY -> a.damaging();
            case WIDE -> a == BURST || a == FIELD || a == CLEAVE || a == BARRIER || a == SPARK || a == SHARD || a == ORB;
            case SEEK -> a.flies() || a == ARROW;
            case ARC, BOUNCE -> a.flies();
            case PIERCE -> a == SPARK || a == SHARD || a == ORB || a == ARROW;
            case SIPHON -> a.damaging();
            case EMBER, FROST, STORM, TIDE -> a.damaging();
            case FAN -> a.directional;
            default -> false;
        };
    }

    /** "a"/"an" plus the lower-case name, for readings. */
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
