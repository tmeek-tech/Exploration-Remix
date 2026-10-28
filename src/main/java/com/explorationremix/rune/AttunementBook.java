package com.explorationremix.rune;

import com.explorationremix.item.AttunementBookItem;
import net.minecraft.world.item.ItemStack;

/** Attunement families and how to read them off a book. Attunement is permanent on the tool (Taran, 2026-10-28). */
public final class AttunementBook {
    private AttunementBook() {}

    public static final String MOVEMENT = "movement";
    public static final String COMBAT = "combat";
    public static final String ELEMENT = "element";
    public static final String UTILITY = "utility";
    public static final String FUN = "fun";
    public static final String[] ALL = {MOVEMENT, COMBAT, ELEMENT, UTILITY, FUN};

    /** The attunement a book gives, or "" for anything that is not an attunement book. */
    public static String attunementOf(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof AttunementBookItem)) return "";
        return AttunementBookItem.attunement(stack);
    }
}
