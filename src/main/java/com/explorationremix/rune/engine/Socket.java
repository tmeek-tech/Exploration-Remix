package com.explorationremix.rune.engine;

/**
 * A rune socket, in the order the table shows them: a weapon's three (active, modifier, passive), a piece of armor's
 * three (effect, and two collect), then a tool's one. {@code pos} is its place left to right among its gear's sockets.
 */
public enum Socket {
    ACTIVE(RuneKind.ACTION, "Active", 0), MODIFIER(RuneKind.MODIFIER, "Modifier", 1), PASSIVE(RuneKind.PASSIVE, "Passive", 2),
    EFFECT(RuneKind.EFFECT, "Effect", 0), COLLECT_1(RuneKind.COLLECT, "Collect", 1), COLLECT_2(RuneKind.COLLECT, "Collect", 2),
    TOOL(RuneKind.TOOL, "Tool", 0);

    /** A weapon's sockets, a piece of armor's and a tool's, left to right. */
    public static final Socket[] WEAPON = {ACTIVE, MODIFIER, PASSIVE}, ARMOR = {EFFECT, COLLECT_1, COLLECT_2}, TOOLS = {TOOL};

    /** The kind of rune the socket takes. */
    public final RuneKind kind;
    public final String title;
    /** Its place among its gear's three, left to right. */
    public final int pos;

    Socket(RuneKind kind, String title, int pos) {
        this.kind = kind;
        this.title = title;
        this.pos = pos;
    }

    public boolean armor() { return kind.armor(); }

    /** The socket a rune of kind {@code k} goes in (a collect rune: the first collect socket). */
    public static Socket of(RuneKind k) {
        return switch (k) {
            case ACTION -> ACTIVE;
            case MODIFIER -> MODIFIER;
            case PASSIVE -> PASSIVE;
            case EFFECT -> EFFECT;
            case COLLECT -> COLLECT_1;
            case TOOL -> TOOL;
        };
    }

    public static Socket byIndex(int i) {
        Socket[] all = values();
        return i >= 0 && i < all.length ? all[i] : null;
    }
}
