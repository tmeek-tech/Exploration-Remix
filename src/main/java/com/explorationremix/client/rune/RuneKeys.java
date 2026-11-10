package com.explorationremix.client.rune;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

/**
 * The rune key: R by default, in the mod's own group of the Controls screen. Pressed in game it asks the server to
 * open the rune screen (refused in combat); pressed on the rune screen it closes it.
 */
public final class RuneKeys {
    public static final String CATEGORY = "key.categories.explorationremix";

    public static final KeyMapping OPEN = new KeyMapping("key.explorationremix.runes", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.KEY_R, CATEGORY);

    private RuneKeys() {}
}
