package com.explorationremix.rune.tool;

import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool runes (0.22.6; Taran, 2026-11-25: "Tools get one socket: Haste digs faster, Bounty finds more ore, Gentle picks
 * blocks up whole."). Each stands in for an enchantment the game already knows, heard through Forge's enchantment
 * lookups (ToolRunesMixin), so everything that asks a tool what it carries hears the rune: a block's breaking speed on
 * both sides, every loot table's Fortune and Silk Touch, an ore's XP. Haste is Efficiency III, Bounty Fortune II,
 * Gentle Silk Touch. Enchantments a tool already had still count, the higher level winning; neither the tooltip nor
 * the glint shows a rune as an enchantment, and an anvil or a grindstone never sees one.
 */
public final class ToolRunes {
    /** The enchantment levels the runes stand in for. */
    public static final int HASTE = 3, BOUNTY = 2, GENTLE = 1;

    private ToolRunes() {}

    /** The enchantment a tool rune stands in for, or null for any other rune. */
    @Nullable
    public static Enchantment enchantment(@Nullable RuneId r) {
        if (r == null) return null;
        return switch (r) {
            case HASTE -> Enchantments.BLOCK_EFFICIENCY;
            case BOUNTY -> Enchantments.BLOCK_FORTUNE;
            case GENTLE -> Enchantments.SILK_TOUCH;
            default -> null;
        };
    }

    /** The level of the enchantment a tool rune stands in for (0 for any other rune). */
    public static int level(@Nullable RuneId r) {
        if (r == null) return 0;
        return switch (r) {
            case HASTE -> HASTE;
            case BOUNTY -> BOUNTY;
            case GENTLE -> GENTLE;
            default -> 0;
        };
    }

    /** The level of {@code e} on this tool with its rune counted: {@code own} (its enchantments') or the rune's, the higher. */
    public static int level(ItemStack stack, Enchantment e, int own) {
        RuneId r = RuneGear.tool(stack);
        return r != null && enchantment(r) == e ? Math.max(own, level(r)) : own;
    }

    /** Every enchantment on this tool with its rune counted ({@code own}: its enchantments'). */
    public static Map<Enchantment, Integer> all(ItemStack stack, Map<Enchantment, Integer> own) {
        RuneId r = RuneGear.tool(stack);
        Enchantment e = enchantment(r);
        if (e == null) return own;
        Map<Enchantment, Integer> out = new LinkedHashMap<>(own);
        out.merge(e, level(r), Math::max);
        return out;
    }
}
