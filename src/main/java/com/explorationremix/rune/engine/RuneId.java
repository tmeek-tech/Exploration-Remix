package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.explorationremix.rune.engine.RuneKind.ACTION;
import static com.explorationremix.rune.engine.RuneKind.COLLECT;
import static com.explorationremix.rune.engine.RuneKind.EFFECT;
import static com.explorationremix.rune.engine.RuneKind.MODIFIER;
import static com.explorationremix.rune.engine.RuneKind.PASSIVE;
import static com.explorationremix.rune.engine.RuneKind.TOOL;

/**
 * Every rune, with the numbers the rules read: energy cost, cast delay and recovery (ticks), which gear it fits
 * ({@link Fits}), and for active runes their shape, base damage and the XP level their second phase wakes at. A weapon
 * holds one rune of each weapon kind (RuneKind): its active rune is what it casts, its modifier changes that cast, and
 * its passive is what the weapon does all the time. A modifier's cost is paid by every copy of the cast it changes;
 * passives cost nothing. Swords and bows have actives of their own (a bow's go off where its arrow lands); modifiers
 * and most passives fit both.
 *
 * <p>Armor and tool runes cost nothing, and work the same at every XP level (Taran, 2026-11-25: armor grows by its
 * collect sets, not by levels). An effect rune fits one piece of armor; a collect rune fits any piece and wakes once
 * three pieces carry it ({@link #three}), with a bonus when all four do ({@link #full}). A tool rune fits a pickaxe, an
 * axe, a shovel or a hoe, in its one socket.
 *
 * <p>{@link #ARROW} is not a rune: it stands for a bow's or crossbow's own arrow, the first carrier of every cast
 * those weapons make. It has no item and costs nothing.
 *
 * <p>{@code layer} is where a rune is first found: 0 the Upper Caves, 1 the Blue Reaches, 2 the Deep Gardens. Some ids
 * are older than their names, so runes already found keep working: Overflow is "after" (it was "After a moment" when
 * runes sat in rows, then Echo) and Return is "arc" (until 0.22.6 it only lobbed bolts; then it became a boomerang).
 */
public enum RuneId {
    // ---- sword actives: shape, directional (Fan can copy it), damage, and the XP level of the second phase
    SPARK("spark", "Spark", ACTION, 8, 3, 3, 0, Fits.SWORD, Shape.BOLT, true, 4.0F, 12,
            "Fork: where it strikes a foe, it leaps on to two more nearby."),
    SHARD("shard", "Shard", ACTION, 8, 2, 4, 0, Fits.SWORD, Shape.BOLT, true, 1.8F, 6,
            "Hail: seven needles fly, and a foe four of them strike is knocked reeling."),
    ORB("orb", "Orb", ACTION, 14, 8, 6, 0, Fits.SWORD, Shape.BOLT, true, 7.0F, 24,
            "Gravity: it drags foes near its path in toward it, and bursts where it stops."),
    BURST("burst", "Burst", ACTION, 14, 6, 6, 0, Fits.SWORD, Shape.INSTANT, false, 6.0F, 20,
            "Shockwave: a ring of force rolls out along the ground from the blast and throws foes off their feet."),
    CLEAVE("cleave", "Cleave", ACTION, 10, 6, 4, 0, Fits.SWORD, Shape.INSTANT, true, 6.0F, 16,
            "Wave: the slash flies on for ten blocks through everything in its way."),
    FIELD("field", "Field", ACTION, 16, 8, 8, 1, Fits.SWORD, Shape.FIELD, false, 1.0F, 14,
            "Mire: the ground in it holds creatures to a crawl, and it lasts twice as long."),
    BARRIER("barrier", "Barrier", ACTION, 12, 6, 8, 0, Fits.SWORD, Shape.INSTANT, false, 0.0F, 10,
            "Bulwark: a bigger wall that shoves aside whatever stands where it rises, for 16 seconds."),
    DASH("dash", "Dash", ACTION, 10, 8, 8, 0, Fits.SWORD, Shape.SELF, false, 0.0F, 18,
            "Charge: you slam through what you dash into, and can dash twice before you land."),
    REEL("reel", "Reel", ACTION, 8, 5, 6, 1, Fits.SWORD, Shape.INSTANT, false, 0.0F, 8,
            "Long line: it reaches half as far again, and pulls twice before you land."),
    // ---- bow actives: each goes off where its arrow lands
    GLOW("glow", "Glow", ACTION, 4, 0, 2, 0, Fits.BOW, Shape.INSTANT, false, 0.0F, 4,
            "Scatter: lights spray onto the rock all round where it lands, and foes near the one it marks glow too."),
    ROPE("rope", "Rope", ACTION, 6, 0, 4, 0, Fits.BOW, Shape.INSTANT, false, 0.0F, 7,
            "Long rope: it hangs for twice as long, and you climb it twice as fast."),
    SNARE("snare", "Snare", ACTION, 10, 0, 6, 1, Fits.BOW, Shape.INSTANT, false, 0.0F, 11,
            "Bind: it holds twice as long, and what it holds takes a third more damage."),
    VOLLEY("volley", "Volley", ACTION, 12, 0, 8, 1, Fits.BOW, Shape.INSTANT, false, 2.5F, 15,
            "Second rain: a wider rain follows a moment after the first."),
    MINE("mine", "Mine", ACTION, 12, 0, 8, 2, Fits.BOW, Shape.INSTANT, false, 7.0F, 22,
            "Cluster: as it goes off it throws three small mines round it, which go off a moment later."),
    // ---- modifiers: they change the active rune (on a bow, Swift, Seek, Pierce and Fan shape the arrow itself)
    SWIFT("swift", "Swift", MODIFIER, 2, -2, 0, 0),
    HEAVY("heavy", "Heavy", MODIFIER, 4, 4, 2, 0),
    WIDE("wide", "Wide", MODIFIER, 4, 2, 2, 0),
    SEEK("seek", "Seek", MODIFIER, 3, 1, 1, 1),
    ARC("arc", "Return", MODIFIER, 2, 0, 1, 0),
    BOUNCE("bounce", "Bounce", MODIFIER, 2, 0, 1, 0),
    PIERCE("pierce", "Pierce", MODIFIER, 3, 1, 1, 1),
    SIPHON("siphon", "Siphon", MODIFIER, 4, 2, 2, 2),
    FAN("fan", "Fan", MODIFIER, 0, 2, 2, 1),
    // ---- passives: free; what the weapon does all the time
    EMBER("ember", "Ember", PASSIVE, 0, 0, 0, 2, Fits.WEAPONS, Element.EMBER),
    FROST("frost", "Frost", PASSIVE, 0, 0, 0, 2, Fits.WEAPONS, Element.FROST),
    STORM("storm", "Storm", PASSIVE, 0, 0, 0, 2, Fits.WEAPONS, Element.STORM),
    TIDE("tide", "Tide", PASSIVE, 0, 0, 0, 2, Fits.WEAPONS, Element.TIDE),
    ON_HIT("on_hit", "On hit", PASSIVE, 0, 0, 0, 1, Fits.WEAPONS, Element.NONE),
    AFTER("after", "Overflow", PASSIVE, 0, 0, 0, 1, Fits.WEAPONS, Element.NONE),
    RECALL("recall", "Recall", PASSIVE, 0, 0, 0, 0, Fits.BOW, Element.NONE),
    QUICKDRAW("quickdraw", "Quickdraw", PASSIVE, 0, 0, 0, 1, Fits.BOW, Element.NONE),
    // ---- armor effect runes: one piece each
    LANTERN("lantern", "Lantern", EFFECT, 0, 0, 0, 0, Fits.HELMET),
    SENSE("sense", "Sense", EFFECT, 0, 0, 0, 1, Fits.HELMET),
    FAR_SIGHT("far_sight", "Far Sight", EFFECT, 0, 0, 0, 0, Fits.HELMET),
    GLIDE("glide", "Glide", EFFECT, 0, 0, 0, 1, Fits.CHEST),
    SECOND_WIND("second_wind", "Second Wind", EFFECT, 0, 0, 0, 2, Fits.CHEST),
    STEADFAST("steadfast", "Steadfast", EFFECT, 0, 0, 0, 0, Fits.CHEST),
    STRIDE("stride", "Stride", EFFECT, 0, 0, 0, 0, Fits.LEGS),
    CLIMB("climb", "Climb", EFFECT, 0, 0, 0, 1, Fits.LEGS),
    SWIM("swim", "Swim", EFFECT, 0, 0, 0, 1, Fits.LEGS),
    LEAP("leap", "Leap", EFFECT, 0, 0, 0, 1, Fits.BOOTS),
    FEATHER("feather", "Feather", EFFECT, 0, 0, 0, 0, Fits.BOOTS),
    FROST_WALK("frost_walk", "Frost Walk", EFFECT, 0, 0, 0, 2, Fits.BOOTS),
    // ---- collect runes: any piece; three pieces wake one, all four make a full set
    MEND("mend", "Mend", COLLECT, 0, 0, 0, 0,
            "XP you pick up mends your worn armor.", "It mends what you hold too, and twice as fast."),
    VIGOR("vigor", "Vigor", COLLECT, 0, 0, 0, 0,
            "Two more hearts.", "Four more hearts."),
    FLEET("fleet", "Fleet", COLLECT, 0, 0, 0, 1,
            "You move 10% faster.", "You move 20% faster."),
    THIRST("thirst", "Thirst", COLLECT, 0, 0, 0, 1,
            "Hits fill your weapons with energy 40% faster.", "Hits fill your weapons with energy 80% faster."),
    WARD("ward", "Ward", COLLECT, 0, 0, 0, 2,
            "Creatures' hits hurt you 12% less.", "Creatures' hits hurt you 25% less."),
    WISDOM("wisdom", "Wisdom", COLLECT, 0, 0, 0, 2,
            "Half as much XP again from what you kill and mine.", "Twice the XP from what you kill and mine."),
    // ---- tool runes: a pickaxe's, an axe's, a shovel's or a hoe's one socket
    HASTE("haste", "Haste", TOOL, 0, 0, 0, 0, Fits.TOOL),
    BOUNTY("bounty", "Bounty", TOOL, 0, 0, 0, 1, Fits.TOOL),
    GENTLE("gentle", "Gentle", TOOL, 0, 0, 0, 2, Fits.TOOL),
    // ---- not a rune: a bow's own arrow
    ARROW("arrow", "Arrow", ACTION, 0, 0, 0, -1, Fits.BOW, Shape.BOLT, true, 0.0F, 0, "");

    /** Path of the id ({@code explorationremix:rune_<id>} for the item). */
    public final String id;
    public final String title;
    public final RuneKind kind;
    public final int cost, delay, recovery;
    /** 0 Upper Caves, 1 Blue Reaches, 2 Deep Gardens; -1 not a rune. */
    public final int layer;
    /** The gear it can be set into ({@link Fits} bits). */
    public final int fits;
    /** Active runes only. */
    public final Shape shape;
    /** Active runes only: Fan can copy it (it has a direction). */
    public final boolean directional;
    /** Active runes only: base damage before the weapon's power and the modifier (Field: per pulse; Volley: per arrow). */
    public final float damage;
    /** Active runes only: the XP level at which the second phase wakes (it works while the player's level is this or more). */
    public final int phaseLevel;
    /** Active runes only: what the second phase does, in a line. */
    public final String phaseTwo;
    /** The four element passives only. */
    public final Element element;
    /** Collect runes only: what three pieces carrying it give, and what all four give. */
    public final String three, full;

    // an active rune (and the bow's own arrow)
    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, int fits, Shape shape, boolean directional,
           float damage, int phaseLevel, String phaseTwo) {
        this(id, title, kind, cost, delay, recovery, layer, fits, shape, directional, damage, phaseLevel, phaseTwo, Element.NONE, "", "");
    }

    // a modifier: every weapon takes one
    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer) {
        this(id, title, kind, cost, delay, recovery, layer, Fits.WEAPONS, null, false, 0.0F, 0, "", Element.NONE, "", "");
    }

    // a passive
    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, int fits, Element element) {
        this(id, title, kind, cost, delay, recovery, layer, fits, null, false, 0.0F, 0, "", element, "", "");
    }

    // an effect rune (one piece of armor) or a tool rune: it does what it does, in the gear it fits
    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, int fits) {
        this(id, title, kind, cost, delay, recovery, layer, fits, null, false, 0.0F, 0, "", Element.NONE, "", "");
    }

    // a collect rune: any piece of armor
    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, String three, String full) {
        this(id, title, kind, cost, delay, recovery, layer, Fits.ARMOR, null, false, 0.0F, 0, "", Element.NONE, three, full);
    }

    RuneId(String id, String title, RuneKind kind, int cost, int delay, int recovery, int layer, int fits, Shape shape, boolean directional,
           float damage, int phaseLevel, String phaseTwo, Element element, String three, String full) {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.cost = cost;
        this.delay = delay;
        this.recovery = recovery;
        this.layer = layer;
        this.fits = fits;
        this.shape = shape;
        this.directional = directional;
        this.damage = damage;
        this.phaseLevel = phaseLevel;
        this.phaseTwo = phaseTwo;
        this.element = element;
        this.three = three;
        this.full = full;
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

    /** The runes of one kind that fit this gear ({@link Fits} bits), in the rune list's order. */
    public static List<RuneId> ofKind(RuneKind k, int gear) {
        List<RuneId> out = new ArrayList<>();
        for (RuneId r : RUNES) if (r.kind == k && r.fits(gear)) out.add(r);
        return out;
    }

    /** The rune with this id path, or null (never {@link #ARROW}); "overflow" and "echo" are Overflow too, and "return" Return. */
    public static RuneId byId(String id) {
        if (id == null || id.isEmpty()) return null;
        String s = id.toLowerCase(Locale.ROOT);
        if (s.equals("overflow") || s.equals("echo")) return AFTER;
        if (s.equals("return")) return ARC;
        for (RuneId r : RUNES) if (r.id.equals(s)) return r;
        return null;
    }

    /** The rune at this place in {@link #values()}, or null; never {@link #ARROW}. */
    public static RuneId byOrdinal(int i) {
        RuneId[] all = values();
        return i >= 0 && i < all.length && all[i] != ARROW ? all[i] : null;
    }

    public boolean isAction() { return kind == ACTION; }

    public boolean isModifier() { return kind == MODIFIER; }

    public boolean isPassive() { return kind == PASSIVE; }

    public boolean isEffect() { return kind == EFFECT; }

    public boolean isCollect() { return kind == COLLECT; }

    public boolean isTool() { return kind == TOOL; }

    /** Whether it can be set into gear with these {@link Fits} bits. */
    public boolean fits(int gear) { return (fits & gear) != 0; }

    public boolean fits(Host h) { return fits(Fits.of(h)); }

    public boolean fits(Piece p) { return fits(Fits.of(p)); }

    /** One of the four element passives. */
    public boolean isElement() { return element != Element.NONE; }

    /** Hurts what it hits: elements, Heavy and Siphon can change it. */
    public boolean damaging() {
        return this == SPARK || this == SHARD || this == ORB || this == BURST || this == CLEAVE || this == FIELD || this == VOLLEY || this == MINE
                || this == ARROW;
    }

    /** A bolt that flies through the air: Seek, Return, Bounce and Pierce can change it. */
    public boolean flies() { return this == SPARK || this == SHARD || this == ORB; }

    /** On a bow or crossbow, this modifier shapes the arrow itself rather than the rune the arrow carries. */
    public boolean shapesArrow() { return this == SWIFT || this == SEEK || this == PIERCE || this == FAN; }

    /** Whether this modifier changes the active rune {@code a}. A modifier that changes nothing shows grey. */
    public boolean changes(RuneId a) {
        if (a == null || !a.isAction()) return false;
        return switch (this) {
            case SWIFT -> a.flies() || a == ARROW || a == DASH || a == REEL;
            case HEAVY -> a.damaging();
            case WIDE -> a == BURST || a == FIELD || a == CLEAVE || a == BARRIER || a == SPARK || a == SHARD || a == ORB || a == GLOW || a == SNARE
                    || a == VOLLEY || a == MINE;
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
            case SHARD -> "needle spray";
            case GLOW -> "flare";
            case BARRIER -> "barrier wall";
            default -> title.toLowerCase(Locale.ROOT);
        };
    }

    public String plural() {
        String n = noun();
        if (n.endsWith("sh") || n.endsWith("ch") || n.endsWith("s")) return n + "es";
        if (n.length() > 1 && n.endsWith("y") && "aeiou".indexOf(n.charAt(n.length() - 2)) < 0) return n.substring(0, n.length() - 1) + "ies";
        return n + "s";
    }
}
