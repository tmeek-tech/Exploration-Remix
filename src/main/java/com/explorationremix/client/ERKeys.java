package com.explorationremix.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Client key bindings. */
public final class ERKeys {
    private ERKeys() {}

    /** Opens the read-only runes screen: every tool's sentences at a glance. Editing happens at rune blocks only. */
    public static final KeyMapping RUNES = new KeyMapping("key.explorationremix.runes", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.explorationremix");
}
