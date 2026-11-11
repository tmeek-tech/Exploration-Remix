package com.explorationremix.combat.swing;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraftforge.common.ToolActions;

import javax.annotation.Nullable;

/** Which held items swing the timed way, and how: swords (other mods' too) and axes; anything else stays vanilla. */
public final class Weapons {
    private Weapons() {}

    /** Axes swing the timed way too (slower, a chain of two). */
    public static boolean axes = true;

    @Nullable
    public static WeaponProfile profile(ItemStack stack) {
        if (stack.isEmpty()) return null;
        if (stack.getItem() instanceof SwordItem || stack.canPerformAction(ToolActions.SWORD_SWEEP)) return WeaponProfile.SWORD;
        if (axes && stack.getItem() instanceof AxeItem) return WeaponProfile.AXE;
        return null;
    }
}
