package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Perlin;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.util.Mth;

/** How bright the Galleries' lava glow is ({@link HallGlowFeature}): pure, so it is tested without Minecraft. */
final class HallGlow {
    private HallGlow() {}

    /** The level of every glow in the flood band, the one the lava's drain puts back. */
    static final int BAND_LEVEL = 15;

    /**
     * The hash that places the glow's spot in the cube (cx, cy, cz): its offsets along x, y and z are read from bits 0,
     * 16 and 32. Mixed twice, as {@code Hashing.mix} mixes its last argument with one multiply only, which lined the
     * spots of cubes next to each other along z up in rows.
     */
    static long spotHash(long seed, int cx, int cy, int cz) {
        return Hashing.mix(Hashing.mix(seed ^ 0x61C8864680B583EBL, cx, cy, cz), 0, 0, 0);
    }

    /**
     * Where a spot's rock lies, which sets how bright it is (0.5.1, "lit from the lava and the floor up"): rock under it
     * within reach (a floor, a ledge, a terrace), else beside it (a wall, a column), else only over it (a vault, the
     * underside of a hanging cluster).
     */
    static final int FLOOR = 0, WALL = 1, VAULT = 2;

    /** The side of a spot that has rock under it, beside it or over it within reach, floor first; -1 for none. */
    static int side(boolean under, boolean beside, boolean over) {
        return under ? FLOOR : beside ? WALL : over ? VAULT : -1;
    }

    /** {@link #levelAt(HallGlowFeature.Config, int, int, Perlin, int, int, int)} for a spot over a floor. */
    static int levelAt(HallGlowFeature.Config c, int over, Perlin noise, int x, int y, int z) {
        return levelAt(c, over, FLOOR, noise, x, y, z);
    }

    /** The Galleries' biomes that glow with the halls' light (the Obsidian Tubes take their own). */
    static boolean galleries(DeepBiome b) {
        return b == DeepBiome.BASALT_GALLERIES || b == DeepBiome.CALDERA || b == DeepBiome.CAUSEWAY || b == DeepBiome.SCORIA_CANYONS;
    }

    /**
     * The glow's level at a spot of air (x, y, z) of the Galleries whose rock lies on {@code side}, or 0 for none:
     * {@link #BAND_LEVEL} in the flood band (under {@code y0}, the band's top), {@code tubes} in a tube's air, the halls'
     * {@link #levelAt} over the band. Air outside the tube field's tubes takes the hall's light whatever its biome (the
     * Galleries keep the Obsidian Tubes' biome to the cavern's rock, so hall air at a tube's mouth is the hall's anyway).
     */
    static int spotLevel(Terrain t, HallGlowFeature.Config c, int y0, int side, Perlin noise, int x, int y, int z) {
        DeepBiome b = t.biomeAt(x, y, z);
        if (b == DeepBiome.OBSIDIAN_TUBES && t.looks.tube(x, y, z) >= 0) b = DeepBiome.BASALT_GALLERIES;
        if (y < y0) return galleries(b) ? BAND_LEVEL : 0;
        if (b == DeepBiome.OBSIDIAN_TUBES) return c.tubes();
        return galleries(b) ? levelAt(c, y - y0, side, noise, x, y, z) : 0;
    }

    /**
     * What a try does at its spot ({@code HallGlowFeature.lightChunk}), from what the spot holds: {@code held} is 0 for
     * air, the level of a glow already there, -1 for any other block; {@code lv} is the level the spot would take now
     * ({@link #spotLevel}). Air takes lv. A glow over the flood band (y at or over {@code y0}) goes up to lv when lv is
     * higher and never down: a neighbour decorated before this chunk lit its tries before this chunk's columns stood in
     * it, and a column added in reach can only move a spot's side from vault to wall or floor, so the most a spot is given
     * over its passes is the level it gets when the columns stood first, whatever the order. The band's glow is the
     * flood's and is always {@link #BAND_LEVEL}, so it is left. Returns the level to set, or 0 to leave the spot.
     */
    static int tryLevel(int held, int lv, int y, int y0) {
        if (held < 0 || lv <= 0) return 0;
        if (held == 0) return lv;
        return y >= y0 && lv > held ? lv : 0;
    }

    /**
     * The glow's level at {@code over} blocks above the flood band: {@code low} fading to {@code high} at {@code fade}
     * blocks up, {@code wall} less for a spot by a wall and {@code vault} less for one under a vault, moved by the slow
     * patch noise up to {@code patch} either way; always 1 to 15.
     */
    static int levelAt(HallGlowFeature.Config c, int over, int side, Perlin noise, int x, int y, int z) {
        double f = Mth.clamp(over / (double) c.fade(), 0, 1);
        double lv = c.low() + (c.high() - c.low()) * f;
        if (side == WALL) lv -= c.wall();
        else if (side == VAULT) lv -= c.vault();
        if (noise != null) lv += c.patch() * Mth.clamp(noise.noise3(x / 28.0, y / 20.0, z / 28.0) * 1.6, -1, 1);
        return Mth.clamp((int) Math.round(lv), 1, 15);
    }
}
