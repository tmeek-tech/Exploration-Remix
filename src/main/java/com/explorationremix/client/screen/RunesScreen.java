package com.explorationremix.client.screen;

import com.explorationremix.client.ClientRunState;
import com.explorationremix.client.RuneTooltips;
import com.explorationremix.rune.RuneTool;
import com.explorationremix.world.DeepWorld;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The runes tab: a read-only view of every rune-holding tool in the inventory and its sentences. */
public class RunesScreen extends Screen {
    private record Entry(ItemStack stack, List<Component> lines) {}

    private final List<Entry> entries = new ArrayList<>();
    private int scroll;

    public RunesScreen() { super(Component.translatable("screen.explorationremix.runes")); }

    @Override
    protected void init() {
        entries.clear();
        if (minecraft == null || minecraft.player == null) return;
        Inventory inv = minecraft.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !RuneTool.canHoldRunes(s)) continue;
            List<Component> lines = new ArrayList<>();
            RuneTooltips.append(s, lines);
            if (lines.isEmpty()) lines.add(Component.translatable("rune.explorationremix.tooltip.empty", RuneTool.slots(s)).withStyle(ChatFormatting.DARK_GRAY));
            entries.add(new Entry(s, lines));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int w = Math.min(width - 40, 360);
        int x0 = (width - w) / 2;
        int y = 18;
        g.drawCenteredString(font, title, width / 2, y, 0xFFFFFF);
        y += 14;
        Component state = ClientRunState.runId == 0L
                ? Component.translatable("screen.explorationremix.runes.no_run").withStyle(ChatFormatting.GRAY)
                : Component.translatable("screen.explorationremix.runes.run", ClientRunState.layerIndex < 0
                        ? Component.translatable("layer.explorationremix.upper_caves")
                        : Component.translatable("layer.explorationremix." + layerId(ClientRunState.layerIndex))).withStyle(ChatFormatting.GOLD);
        g.drawCenteredString(font, state, width / 2, y, 0xFFFFFF);
        y += 16;
        g.drawCenteredString(font, Component.translatable("screen.explorationremix.runes.hint").withStyle(ChatFormatting.DARK_GRAY), width / 2, y, 0xFFFFFF);
        y += 14 - scroll;
        if (entries.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("screen.explorationremix.runes.empty").withStyle(ChatFormatting.GRAY), width / 2, y + 10, 0xFFFFFF);
        }
        for (Entry e : entries) {
            if (y > 20 && y < height - 10) {
                g.renderItem(e.stack(), x0, y);
                g.drawString(font, e.stack().getHoverName(), x0 + 20, y + 4, 0xFFFFFF);
            }
            y += 18;
            for (Component line : e.lines()) {
                if (y > 20 && y < height - 10) g.drawString(font, line, x0 + 20, y, 0xFFFFFF);
                y += 10;
            }
            y += 6;
        }
        super.render(g, mouseX, mouseY, partial);
    }

    private static String layerId(int index) {
        if (!DeepWorld.layers().isEmpty() && index < DeepWorld.layers().size()) return DeepWorld.layers().get(index).id();
        return switch (index) { case 0 -> "lantern_deeps"; case 1 -> "blue_reaches"; case 2 -> "ember_gardens"; default -> "basalt_galleries"; };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, scroll - (int) (delta * 20));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
