package com.explorationremix.client.screen;

import com.explorationremix.rune.Rune;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.run.RunSummary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** The run summary: how deep, how long, what was found, what ended it. Shown when a run ends. */
public class RunSummaryScreen extends Screen {
    private final RunSummary summary;
    @Nullable private final Screen parent;
    private final List<Component> lines = new ArrayList<>();

    public RunSummaryScreen(RunSummary summary, @Nullable Screen parent) {
        super(Component.translatable("screen.explorationremix.run_summary"));
        this.summary = summary;
        this.parent = parent;
    }

    @Override
    protected void init() {
        lines.clear();
        long seconds = summary.ticks() / 20;
        lines.add(Component.translatable("screen.explorationremix.run_summary.end." + summary.endReason()).withStyle(ChatFormatting.GOLD));
        lines.add(Component.translatable("screen.explorationremix.run_summary.time", seconds / 60, seconds % 60));
        lines.add(Component.translatable("screen.explorationremix.run_summary.deepest",
                summary.deepestLayer() < 0 ? Component.translatable("layer.explorationremix.upper_caves") : layerName(summary.deepestLayer())));
        lines.add(Component.translatable("screen.explorationremix.run_summary.gates", summary.gatesPassed()));
        lines.add(Component.translatable("screen.explorationremix.run_summary.kills", summary.kills()));
        lines.add(Component.translatable("screen.explorationremix.run_summary.xp", summary.xpEarned(), summary.xpSpent()));
        lines.add(Component.translatable("screen.explorationremix.run_summary.woken", summary.runeBlocksWoken()));
        lines.add(Component.translatable("screen.explorationremix.run_summary.damage", summary.damageTaken()));
        if (!summary.runesTaken().isEmpty()) {
            lines.add(Component.translatable("screen.explorationremix.run_summary.runes").withStyle(ChatFormatting.AQUA));
            for (String id : summary.runesTaken()) {
                Rune r = RuneRegistry.get(ResourceLocation.tryParse(id));
                lines.add(Component.literal("  ").append(r == null ? Component.literal(id) : Component.translatable(r.translationKey()).withStyle(r.formatting())));
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 60, Math.min(height - 30, 60 + lines.size() * 12 + 10), 120, 20).build());
    }

    private Component layerName(int index) {
        List<com.explorationremix.world.Layer> layers = com.explorationremix.world.DeepWorld.layers();
        if (index >= 0 && index < layers.size()) return Component.translatable("layer.explorationremix." + layers.get(index).id());
        return Component.literal("#" + index);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        int y = 40;
        for (Component c : lines) { g.drawCenteredString(font, c, width / 2, y, 0xE0E0E0); y += 12; }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
