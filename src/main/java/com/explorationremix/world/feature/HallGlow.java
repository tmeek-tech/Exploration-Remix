package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Perlin;
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
