package com.explorationremix.client;

import com.explorationremix.run.RunXp;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/** A quiet line in the corner while on a run: where you are, how long you have been down, what XP you hold. */
public final class RunHud implements IGuiOverlay {
    public static final RunHud INSTANCE = new RunHud();
    private RunHud() {}

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partial, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.options.renderDebug || !ClientRunState.inRun()) return;
        int y = mc.player.getBlockY();
        MutableComponent where;
        if (DeepWorld.installed() && y < DeepWorld.packFloor()) {
            Layer l = DeepWorld.layerAt(y);
            where = l == null ? Component.translatable("hud.explorationremix.gate") : Component.translatable(l.displayKey());
        } else where = Component.translatable("layer.explorationremix.upper_caves");
        long ticks = Math.max(0, mc.level.getGameTime() - ClientRunState.startedAt);
        long seconds = ticks / 20;
        String time = String.format("%d:%02d", seconds / 60, seconds % 60);
        int xp = RunXp.points(mc.player);
        Component line = where.withStyle(ChatFormatting.GOLD)
                .append(Component.literal("  " + time).withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("hud.explorationremix.xp", xp).withStyle(ChatFormatting.AQUA));
        g.drawString(mc.font, line, 4, 4, 0xFFFFFF, true);
    }
}
