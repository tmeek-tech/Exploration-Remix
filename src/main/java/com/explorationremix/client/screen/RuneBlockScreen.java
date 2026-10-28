package com.explorationremix.client.screen;

import com.explorationremix.network.ERNetwork;
import com.explorationremix.network.RuneEditPacket;
import com.explorationremix.rune.AttunementBook;
import com.explorationremix.rune.Rune;
import com.explorationremix.rune.RuneGrammar;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.RuneTool;
import com.explorationremix.rune.menu.RuneBlockMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The rune block editor. Top: the tool's runes in sentence order (click to select, arrows to move, X to remove).
 * Middle: the three offers after waking, or the wake button. Bottom: the inventory; click an enchanted book to attune.
 */
public class RuneBlockScreen extends AbstractContainerScreen<RuneBlockMenu> {
    private static final int RUNE_W = 18;
    private int selected = -1;
    private Button wakeButton, leftButton, rightButton, removeButton;
    private final List<Button> offerButtons = new ArrayList<>();

    public RuneBlockScreen(RuneBlockMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = 176;
        imageHeight = 222;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos, y = topPos;
        leftButton = addRenderableWidget(Button.builder(Component.literal("<"), b -> move(-1)).bounds(x + 8, y + 50, 16, 14).build());
        rightButton = addRenderableWidget(Button.builder(Component.literal(">"), b -> move(1)).bounds(x + 26, y + 50, 16, 14).build());
        removeButton = addRenderableWidget(Button.builder(Component.literal("x"), b -> {
            if (selected >= 0) { send(RuneEditPacket.Action.REMOVE, selected, 0); selected = -1; }
        }).bounds(x + 44, y + 50, 16, 14).build());
        wakeButton = addRenderableWidget(Button.builder(Component.translatable("rune.explorationremix.screen.wake", menu.wakeCost), b -> send(RuneEditPacket.Action.WAKE, 0, 0))
                .bounds(x + 8, y + 90, 160, 16).build());
        offerButtons.clear();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            offerButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> send(RuneEditPacket.Action.PICK_OFFER, idx, 0))
                    .bounds(x + 8 + i * 54, y + 108, 52, 16).build()));
        }
        refreshWidgets();
    }

    private void move(int dir) {
        if (selected < 0) return;
        int to = selected + dir;
        if (to < 0 || to >= RuneTool.runes(tool()).size()) return;
        send(RuneEditPacket.Action.MOVE, selected, to);
        selected = to;
    }

    private ItemStack tool() { return menu.tool(minecraft.player); }

    private void send(RuneEditPacket.Action action, int a, int b) { ERNetwork.CHANNEL.sendToServer(new RuneEditPacket(action, a, b)); }

    private void refreshWidgets() {
        boolean canWake = menu.inRun && !menu.wokenThisRun;
        wakeButton.visible = canWake;
        wakeButton.active = canWake && menu.xp >= menu.wakeCost;
        wakeButton.setMessage(Component.translatable("rune.explorationremix.screen.wake", menu.wakeCost));
        for (int i = 0; i < 3; i++) {
            Button b = offerButtons.get(i);
            boolean has = i < menu.offers.size();
            b.visible = has;
            if (has) {
                Rune r = RuneRegistry.get(menu.offers.get(i));
                b.setMessage(r == null ? Component.literal("?") : Component.translatable(r.translationKey()).withStyle(r.formatting()));
            }
        }
        boolean hasSel = selected >= 0 && selected < RuneTool.runes(tool()).size();
        leftButton.active = hasSel; rightButton.active = hasSel; removeButton.active = hasSel;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refreshWidgets();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, 0xF0101018);
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xF0202030);
        // tool rune row
        ItemStack tool = tool();
        List<ResourceLocation> runes = RuneTool.runes(tool);
        int slots = RuneTool.slots(tool);
        long stamp = RuneTool.runStamp(tool);
        boolean dead = !runes.isEmpty() && (!menu.inRun || stamp != com.explorationremix.client.ClientRunState.runId);
        for (int i = 0; i < Math.min(slots, 9); i++) {
            int rx = x + 8 + i * RUNE_W, ry = y + 28;
            int col = i == selected ? 0xFFE0C060 : 0xFF404058;
            g.fill(rx, ry, rx + RUNE_W - 2, ry + 18, col);
            g.fill(rx + 1, ry + 1, rx + RUNE_W - 3, ry + 17, dead ? 0xFF2A2020 : 0xFF181820);
            if (i < runes.size()) {
                Rune r = RuneRegistry.get(runes.get(i));
                String glyph = r == null ? "?" : glyphOf(r);
                int color = r == null ? 0xFF8888 : (r.formatting().getColor() == null ? 0xFFFFFF : r.formatting().getColor());
                if (dead) color = 0x806060;
                g.drawCenteredString(font, glyph, rx + RUNE_W / 2 - 1, ry + 5, color);
            }
        }
        // inventory slot backgrounds
        for (Slot s : menu.slots) {
            int sx = x + s.x - 1, sy = y + s.y - 1;
            g.fill(sx, sy, sx + 18, sy + 18, s.getContainerSlot() == menu.toolSlot ? 0xFF806020 : 0xFF383848);
            g.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF8B8B8B);
        }
    }

    private static String glyphOf(Rune r) {
        String p = r.id().getPath();
        return p.length() <= 2 ? p.toUpperCase() : p.substring(0, 2).toUpperCase();
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 8, 6, 0xE0E0E0, false);
        g.drawString(font, Component.translatable("rune.explorationremix.screen.tier", menu.tier).withStyle(ChatFormatting.GRAY), 100, 6, 0xA0A0A0, false);
        String att = RuneTool.attunement(tool());
        g.drawString(font, Component.translatable("rune.explorationremix.screen.xp", menu.xp).withStyle(ChatFormatting.GREEN), 8, 17, 0x80FF80, false);
        if (!att.isEmpty()) g.drawString(font, Component.translatable("attunement.explorationremix." + att).withStyle(ChatFormatting.AQUA), 100, 17, 0x80E0FF, false);
        // sentence preview
        List<Rune> live = new ArrayList<>();
        for (ResourceLocation id : RuneTool.runes(tool())) { Rune r = RuneRegistry.get(id); if (r != null) live.add(r); }
        RuneGrammar.Parsed parsed = RuneGrammar.parse(live);
        int y = 68;
        for (RuneGrammar.Sentence s : parsed.sentences()) {
            if (y > 86) break;
            StringBuilder sb = new StringBuilder(short_(s.trigger())).append(" → ");
            for (RuneGrammar.Clause c : s.clauses()) {
                for (Rune m : c.modifiers()) sb.append(short_(m)).append('+');
                sb.append(short_(c.effect())).append(' ');
            }
            sb.append('(').append(s.cooldownTicks() / 20.0).append("s)");
            g.drawString(font, sb.toString(), 8, y, 0xC0C0D0, false);
            y += 9;
        }
        if (menu.offers.isEmpty() && !wakeButton.visible && menu.inRun) g.drawString(font, Component.translatable("rune.explorationremix.screen.woken").withStyle(ChatFormatting.DARK_GRAY), 8, 94, 0x707070, false);
        if (!menu.inRun) g.drawString(font, Component.translatable("rune.explorationremix.screen.no_run").withStyle(ChatFormatting.DARK_GRAY), 8, 94, 0x707070, false);
        if (!menu.offers.isEmpty()) g.drawString(font, Component.translatable("rune.explorationremix.screen.choose"), 8, 98, 0xE0E0E0, false);
    }

    private static String short_(Rune r) {
        String p = r.id().getPath();
        return p.length() > 6 ? p.substring(0, 6) : p;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        // rune row tooltips
        List<ResourceLocation> runes = RuneTool.runes(tool());
        for (int i = 0; i < runes.size() && i < 9; i++) {
            int rx = leftPos + 8 + i * RUNE_W, ry = topPos + 28;
            if (mouseX >= rx && mouseX < rx + RUNE_W - 2 && mouseY >= ry && mouseY < ry + 18) {
                Rune r = RuneRegistry.get(runes.get(i));
                if (r != null) g.renderComponentTooltip(font, tooltipOf(r), mouseX, mouseY);
            }
        }
        for (int i = 0; i < offerButtons.size() && i < menu.offers.size(); i++) {
            Button b = offerButtons.get(i);
            if (b.visible && b.isHoveredOrFocused()) {
                Rune r = RuneRegistry.get(menu.offers.get(i));
                if (r != null) g.renderComponentTooltip(font, tooltipOf(r), mouseX, mouseY);
            }
        }
    }

    public static List<Component> tooltipOf(Rune r) {
        List<Component> list = new ArrayList<>();
        list.add(Component.translatable(r.translationKey()).withStyle(r.formatting()));
        list.add(Component.translatable("rune.explorationremix.kind." + r.kind().getSerializedName()).withStyle(ChatFormatting.GRAY));
        list.add(Component.translatable(r.descriptionKey()).withStyle(ChatFormatting.WHITE));
        list.add(Component.translatable("rune.explorationremix.cost", r.cost() / 20.0).withStyle(ChatFormatting.DARK_GRAY));
        return list;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<ResourceLocation> runes = RuneTool.runes(tool());
        for (int i = 0; i < runes.size() && i < 9; i++) {
            int rx = leftPos + 8 + i * RUNE_W, ry = topPos + 28;
            if (mouseX >= rx && mouseX < rx + RUNE_W - 2 && mouseY >= ry && mouseY < ry + 18) {
                selected = selected == i ? -1 : i;
                refreshWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot != null && !AttunementBook.attunementOf(slot.getItem()).isEmpty()) {
            send(RuneEditPacket.Action.ATTUNE, slot.getContainerSlot(), 0);
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, type);
    }
}
