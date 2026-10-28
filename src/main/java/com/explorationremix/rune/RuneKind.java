package com.explorationremix.rune;

import net.minecraft.util.StringRepresentable;

public enum RuneKind implements StringRepresentable {
    TRIGGER("trigger"), MODIFIER("modifier"), EFFECT("effect"), PASSIVE("passive");

    private final String name;
    RuneKind(String name) { this.name = name; }

    @Override
    public String getSerializedName() { return name; }

    public static RuneKind parse(String s) {
        for (RuneKind k : values()) if (k.name.equals(s)) return k;
        throw new IllegalArgumentException("Unknown rune kind " + s);
    }
}
