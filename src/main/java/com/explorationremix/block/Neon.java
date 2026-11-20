package com.explorationremix.block;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.material.MapColor;

/**
 * The Deep Gardens' four neon colours (Deep Gardens round 2, Taran's look rules for the vent field): magenta, violet,
 * lime and light blue, lime only near the ways down. Every glowing block of the vent field comes in all four, as its
 * blockstate property {@link NeonBlock#NEON}. Declared in the order of the vent field's own colour codes
 * ({@code GardensVents}: MAGENTA 0, VIOLET 1, LIME 2, LIGHTBLUE 3), so {@link #ofCode} maps a code to its colour.
 */
public enum Neon implements StringRepresentable {
    MAGENTA("magenta", MapColor.COLOR_MAGENTA, 0xFF46CD),
    VIOLET("violet", MapColor.COLOR_PURPLE, 0xA860FF),
    LIME("lime", MapColor.COLOR_LIGHT_GREEN, 0xB0FF46),
    LIGHT_BLUE("light_blue", MapColor.COLOR_LIGHT_BLUE, 0x7DAFFF);

    private static final Neon[] BY_CODE = values();

    private final String name;
    /** The colour a block of this neon shows on a map. */
    public final MapColor mapColor;
    /** The neon itself (tools/textures.py's NEON_RGB), for what the game tints: the spores off the plants (0.20.2). */
    public final int rgb;

    Neon(String name, MapColor mapColor, int rgb) {
        this.name = name;
        this.mapColor = mapColor;
        this.rgb = rgb;
    }

    /** The colour of one of the vent field's colour codes (0 magenta, 1 violet, 2 lime, 3 light blue). */
    public static Neon ofCode(int code) { return BY_CODE[code]; }

    @Override
    public String getSerializedName() { return name; }

    @Override
    public String toString() { return name; }
}
