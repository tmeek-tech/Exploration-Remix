package com.explorationremix.rune.engine;

/** A weapon's three rune sockets, in the order the table shows them. */
public enum Socket {
    ACTIVE(RuneKind.ACTION, "Active"), MODIFIER(RuneKind.MODIFIER, "Modifier"), PASSIVE(RuneKind.PASSIVE, "Passive");

    /** The kind of rune the socket takes. */
    public final RuneKind kind;
    public final String title;

    Socket(RuneKind kind, String title) {
        this.kind = kind;
        this.title = title;
    }

    public static Socket of(RuneKind k) {
        return switch (k) {
            case ACTION -> ACTIVE;
            case MODIFIER -> MODIFIER;
            case PASSIVE -> PASSIVE;
        };
    }

    public static Socket byIndex(int i) {
        Socket[] all = values();
        return i >= 0 && i < all.length ? all[i] : null;
    }
}
