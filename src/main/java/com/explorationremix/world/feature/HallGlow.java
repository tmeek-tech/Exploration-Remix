package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Perlin;
import net.minecraft.util.Mth;

/** How bright the Galleries' lava glow is ({@link HallGlowFeature}): pure, so it is tested without Minecraft. */
final class HallGlow {
    private HallGlow() {}

    /** The level of every glow in the flood band, the one the lava's drain puts back. */
    static final int BAND_LEVEL = 15;

    /**
     * The glow's level at {@code over} blocks above the flood band: {@code low} fading to {@code high} at {@code fade}
     * blocks up, moved by the slow patch noise up to {@code patch} either way; always 1 to 15.
     */
    static int levelAt(HallGlowFeature.Config c, int over, Perlin noise, int x, int y, int z) {
        double f = Mth.clamp(over / (double) c.fade(), 0, 1);
        double lv = c.low() + (c.high() - c.low()) * f;
        if (noise != null) lv += c.patch() * Mth.clamp(noise.noise3(x / 28.0, y / 20.0, z / 28.0) * 1.6, -1, 1);
        return Mth.clamp((int) Math.round(lv), 1, 15);
    }
}
