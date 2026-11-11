package com.explorationremix.client.rune;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

/**
 * The runes' keys, in the mod's own group of the Controls screen. Cast runes (R by default; Taran, 2026-11-11 14:45:
 * casting on a key of its own, not right click): casts the runed sword in hand, and again each time it is ready while
 * held. Open the rune screen (K by default; it was R until casting took R): pressed in game it asks the server to open
 * the rune screen (refused in combat); pressed on the rune screen it closes it.
 */
public final class RuneKeys {
    public static final String CATEGORY = "key.categories.explorationremix";

    public static final KeyMapping CAST = new KeyMapping("key.explorationremix.cast_runes", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.KEY_R, CATEGORY);
    /** Under a new name, so a game whose options kept the rune screen on R moves it to K and leaves R to casting. */
    public static final KeyMapping OPEN = new KeyMapping("key.explorationremix.rune_screen", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.KEY_K, CATEGORY);

    private RuneKeys() {}
}
