package com.explorationremix.client.rune;

import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.engine.HostSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.IItemDecorator;

/**
 * Runed gear in any slot (inventory, hotbar, chests) shows its energy as a thin cyan line along the top of the slot,
 * as wide as vanilla's durability bar and well clear of it at the bottom. Gear without runes shows nothing.
 */
final class RuneSlotEnergy implements IItemDecorator {
    static final RuneSlotEnergy INSTANCE = new RuneSlotEnergy();

    private static final int BACK = 0xFF0B1A20, LINE = 0xFF3FE0FF;
    /** The durability bar's width: the line spans x + 2 to x + 14. */
    private static final int WIDTH = 13;

    private RuneSlotEnergy() {}

    @Override
    public boolean render(GuiGraphics g, Font font, ItemStack stack, int x, int y) {
        if (!RuneGear.hasRunes(stack)) return false;
        HostSpec spec = RuneGear.spec(stack);
        Minecraft mc = Minecraft.getInstance();
        if (spec == null || mc.level == null) return false;
        long now = mc.level.getGameTime();
        float energy = RuneLook.energy(RuneGear.state(stack, spec, now), spec, now, mc.getFrameTime());
        float frac = spec.maxEnergy() <= 0 ? 0.0F : Mth.clamp(energy / spec.maxEnergy(), 0.0F, 1.0F);
        int w = Math.round(WIDTH * frac);
        // the overlay type, as vanilla's own bar: drawn over the item whatever its depth
        g.fill(RenderType.guiOverlay(), x + 2, y + 1, x + 2 + WIDTH, y + 2, BACK);
        if (w > 0) g.fill(RenderType.guiOverlay(), x + 2, y + 1, x + 2 + w, y + 2, LINE);
        return false;
    }
}
