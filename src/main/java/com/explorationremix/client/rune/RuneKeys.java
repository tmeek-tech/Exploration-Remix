package com.explorationremix.client.rune;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

/**
 * The runes' key, in the mod's own group of the Controls screen. Cast runes (R by default; Taran, 2026-11-11 14:45:
 * casting on a key of its own, not right click): casts the runed sword in hand, and again each time it is ready while
 * held. Runes are set at an enchanting table now (0.20.3), so the rune screen's key (K) is gone.
 */
public final class RuneKeys {
    public static final String CATEGORY = "key.categories.explorationremix";

    public static final KeyMapping CAST = new KeyMapping("key.explorationremix.cast_runes", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.KEY_R, CATEGORY);

    private RuneKeys() {}
}
