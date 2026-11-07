package com.explorationremix.client.rune;

import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Note;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.Row;
import com.explorationremix.rune.menu.RuneEditorMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.explorationremix.rune.menu.RuneEditorMenu.GEAR_X;
import static com.explorationremix.rune.menu.RuneEditorMenu.GEAR_Y;
import static com.explorationremix.rune.menu.RuneEditorMenu.HOTBAR_Y;
import static com.explorationremix.rune.menu.RuneEditorMenu.INV_X;
import static com.explorationremix.rune.menu.RuneEditorMenu.INV_Y;
import static com.explorationremix.rune.menu.RuneEditorMenu.ROW_Y;
import static com.explorationremix.rune.menu.RuneEditorMenu.runeX;

/**
 * The rune screen: the gear in its slot with its name and energy, its row of rune slots read left to right into casts
 * (a coloured bar under each cast's slots), the breaks between the slots (click a gap to start a new cast there), what
 * the row does in plain words, and the player's inventory. Drawn without a texture, in the look of vanilla's
 * containers. Grey runes are washed out and marked; asleep slots are dark with a small moon and say where they wake.
 *
 * <p>Everything is read again every frame from the gear in the slot ({@link RuneGear#reading}), so the screen shows a
 * change the moment the server confirms it, and a break the moment it is clicked (the menu toggles it here too).
 */
public class RuneEditorScreen extends AbstractContainerScreen<RuneEditorMenu> {
    // ---- vanilla's container colours
    private static final int OUTLINE = 0xFF000000, LIGHT = 0xFFFFFFFF, FACE = 0xFFC6C6C6, SHADOW = 0xFF555555;
    private static final int SLOT_EDGE = 0xFF373737, SLOT_FILL = 0xFF8B8B8B, SLOT_ASLEEP = 0xFF5A5A5A;
    // ---- text: vanilla's label grey, and the few others the screen writes in
    private static final int LABEL = 0xFF404040, SOFT = 0xFF5E5E5E, ENERGY = 0xFF1B6573, HINT = 0xFF34508A;
    // ---- the breaks: a bright bar edged dark when on, a faint seam when off
    private static final int BREAK_EDGE = 0xFF3A3A3A, BREAK_ON = 0xFFFFE89A, BREAK_ON_HOVER = 0xFFFFFFFF;
    private static final int SEAM = 0x2E000000, SEAM_HOVER = 0x8C000000, GAP_HOVER = 0x40FFFFFF;
    // ---- over a rune: a grey one is washed out and marked; an asleep one is darkened under a moon
    private static final int GREY_WASH = 0xA8C6C6C6, GREY_MARK = 0xFF333333, ASLEEP_WASH = 0x9C1C1C1C, MOON = 0xFFF0E2A0;
    // ---- combat: the row dims and says how long to wait
    private static final int LOCK_DIM = 0x99303030, LOCK_BACK = 0xE0202020, LOCK_TEXT = 0xFFFFD8D8;

    /** The breaks' column reaches a little above the slots and down over the cast bars. */
    private static final int GAP_TOP = ROW_Y - 3, GAP_BOTTOM = ROW_Y + 21;
    /** The cast bars, under the rune slots' boxes. */
    private static final int BAR_TOP = ROW_Y + 19, BAR_BOTTOM = ROW_Y + 21;

    // ---- the reading, between the row and the inventory (its slots' boxes start at INV_Y - 1)
    private static final int TEXT_X = 8, TEXT_RIGHT = RuneEditorMenu.WIDTH - 8, TEXT_TOP = 68, TEXT_LAST = 128, LINE = 9;
    /** Cast lines start with their cast's colour; their text sits this far in. */
    private static final int MARK_INDENT = 7;
    /** Room kept on the right for the scroll arrows when the reading is longer than the space. */
    private static final int ARROW_ROOM = 7;

    // ---- tiny glyphs, '#' drawn
    private static final String[] MOON_GLYPH = {".###.", "##...", "##...", "##...", "##...", ".###."};
    private static final String[] GREY_GLYPH = {".###.", "#..##", "#.#.#", "##..#", ".###."};
    private static final String[] UP = {"..#..", ".###.", "#####"}, DOWN = {"#####", ".###.", "..#.."};

    /** This frame's reading of the gear in the slot (null when the slot is empty). */
    @Nullable
    private Reading reading;
    /** The reading's text, wrapped (kept until the words change), and how far it is scrolled. */
    private List<String> textFrom = List.of();
    @Nullable
    private List<TextLine> textWide, textNarrow;
    private int scroll;
    /** A click on a break was handled here: its release is not the menu's business. */
    private boolean swallowRelease;

    /** One wrapped line of the reading; {@code mark} is its cast's colour on the cast's first line, else 0. */
    private record TextLine(FormattedCharSequence text, int mark) {}

    public RuneEditorScreen(RuneEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = RuneEditorMenu.WIDTH;
        imageHeight = RuneEditorMenu.HEIGHT;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        reading = RuneGear.reading(menu.gear(), RunDepth.clientDepth());
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    // ------------------------------------------------------------------------------------------------ under the items

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        panel(g, x, y, imageWidth, imageHeight);
        slotBox(g, x + GEAR_X, y + GEAR_Y, SLOT_FILL);
        int n = menu.slotCount();
        int awake = reading == null ? n : reading.awake();
        // inactive slots (past the gear's last) are not drawn at all
        for (int i = 0; i < n; i++) slotBox(g, x + runeX(i), y + ROW_Y, i < awake ? SLOT_FILL : SLOT_ASLEEP);
        if (reading != null) {
            for (CastReading c : reading.casts()) {
                int last = Math.min(c.lastSlot(), n - 1);
                if (c.firstSlot() > last) continue;
                g.fill(x + runeX(c.firstSlot()) - 1, y + BAR_TOP, x + runeX(last) + 17, y + BAR_BOTTOM, RuneLook.castColour(c));
            }
            Row row = reading.row();
            int hover = gapAt(mouseX, mouseY);
            for (int i = 0; i + 1 < n; i++) divider(g, x, y, i, row.breakAfter(i), i == hover);
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) slotBox(g, x + INV_X + c * 18, y + INV_Y + r * 18, SLOT_FILL);
        }
        for (int c = 0; c < 9; c++) slotBox(g, x + INV_X + c * 18, y + HOTBAR_Y, SLOT_FILL);
    }

    /** Vanilla's container panel, pixel for pixel: a black outline with cut corners, light and dark bevels, grey face. */
    private static void panel(GuiGraphics g, int x, int y, int w, int h) {
        int r = x + w, b = y + h;
        // black outline
        g.fill(x + 2, y, r - 3, y + 1, OUTLINE);
        g.fill(x + 1, y + 1, x + 2, y + 2, OUTLINE);
        g.fill(r - 3, y + 1, r - 2, y + 2, OUTLINE);
        g.fill(x, y + 2, x + 1, b - 3, OUTLINE);
        g.fill(r - 2, y + 2, r - 1, y + 3, OUTLINE);
        g.fill(r - 1, y + 3, r, b - 2, OUTLINE);
        g.fill(x + 1, b - 3, x + 2, b - 2, OUTLINE);
        g.fill(x + 2, b - 2, x + 3, b - 1, OUTLINE);
        g.fill(r - 2, b - 2, r - 1, b - 1, OUTLINE);
        g.fill(x + 3, b - 1, r - 2, b, OUTLINE);
        // face, then the light bevel (top, left) and the dark one (bottom, right)
        g.fill(x + 3, y + 3, r - 3, b - 3, FACE);
        g.fill(x + 2, y + 1, r - 3, y + 2, LIGHT);
        g.fill(x + 1, y + 2, r - 3, y + 3, LIGHT);
        g.fill(x + 1, y + 3, x + 3, b - 3, LIGHT);
        g.fill(x + 3, y + 3, x + 4, y + 4, LIGHT);
        g.fill(r - 3, y + 2, r - 2, y + 3, FACE);
        g.fill(x + 2, b - 3, x + 3, b - 2, FACE);
        g.fill(r - 3, y + 3, r - 1, b - 2, SHADOW);
        g.fill(x + 3, b - 3, r - 3, b - 2, SHADOW);
        g.fill(x + 3, b - 2, r - 2, b - 1, SHADOW);
        g.fill(r - 4, b - 4, r - 3, b - 3, SHADOW);
    }

    /** Vanilla's 18 x 18 inset slot round an item at {@code sx, sy}: dark top and left, white bottom and right. */
    private static void slotBox(GuiGraphics g, int sx, int sy, int fill) {
        g.fill(sx - 1, sy - 1, sx + 17, sy + 17, fill);
        g.fill(sx - 1, sy - 1, sx + 16, sy, SLOT_EDGE);
        g.fill(sx - 1, sy, sx, sy + 16, SLOT_EDGE);
        g.fill(sx, sy + 16, sx + 17, sy + 17, LIGHT);
        g.fill(sx + 16, sy, sx + 17, sy + 16, LIGHT);
    }

    /** The break after slot {@code i}, in the 6 px gap between its box and the next one's. */
    private static void divider(GuiGraphics g, int x, int y, int i, boolean on, boolean hover) {
        int left = x + runeX(i) + 17, right = x + runeX(i + 1) - 1, mid = (left + right) / 2;
        int top = y + GAP_TOP, bottom = y + GAP_BOTTOM;
        if (hover) g.fill(left, top, right, bottom, GAP_HOVER);
        if (on) {
            g.fill(mid - 2, top, mid + 2, bottom, BREAK_EDGE);
            g.fill(mid - 1, top + 1, mid + 1, bottom - 1, hover ? BREAK_ON_HOVER : BREAK_ON);
        } else {
            g.fill(mid - 1, y + ROW_Y + 3, mid + 1, y + ROW_Y + 13, hover ? SEAM_HOVER : SEAM);
        }
    }

    // ------------------------------------------------------------------------------------------------ over the items

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, LABEL, false);
        gearInfo(g);
        if (reading != null) runeMarks(g, reading);
        readingText(g);
        int lock = menu.lockTicks();
        if (lock > 0) combat(g, lock);
    }

    /** Next to the gear slot: the gear's name and energy, or what to put there. */
    private void gearInfo(GuiGraphics g) {
        ItemStack gear = menu.gear();
        int tx = GEAR_X + 22, width = TEXT_RIGHT - tx;
        if (gear.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(Component.literal("Put a sword, bow or crossbow here"), width);
            int ty = GEAR_Y + 8 - (lines.size() * LINE - 1) / 2;
            for (FormattedCharSequence l : lines) {
                g.drawString(font, l, tx, ty, SOFT, false);
                ty += LINE;
            }
            return;
        }
        g.drawString(font, clip(gear.getHoverName().getString(), width), tx, GEAR_Y + 1, LABEL, false);
        HostSpec spec = RuneGear.spec(gear);
        if (spec != null && minecraft != null && minecraft.level != null) {
            long now = minecraft.level.getGameTime();
            float e = CastMachine.energyAt(RuneGear.state(gear, spec, now), spec, now);
            g.drawString(font, "Energy " + RuneLook.whole(e) + " / " + Math.round(spec.maxEnergy()), tx, GEAR_Y + 10, ENERGY, false);
        }
    }

    /** An asleep slot stays dark under the mouse: no highlight (its tooltip says when it wakes). */
    @Override
    public int getSlotColor(int index) {
        int i = index - RuneEditorMenu.RUNE_SLOTS;
        if (reading != null && i >= 0 && i < RuneEditorMenu.MAX_SLOTS && i >= reading.awake()) return 0;
        return super.getSlotColor(index);
    }

    /** Grey runes washed out with a small mark; asleep slots darkened with a moon (drawn over the rune items). */
    private void runeMarks(GuiGraphics g, Reading r) {
        int n = Math.min(menu.slotCount(), r.row().size());
        for (int i = 0; i < n; i++) {
            int sx = runeX(i), sy = ROW_Y;
            if (i >= r.awake()) {
                if (r.row().get(i) != null) g.fill(RenderType.guiOverlay(), sx, sy, sx + 16, sy + 16, ASLEEP_WASH);
                glyph(g, sx + 11, sy + 1, MOON_GLYPH, MOON);
            } else if (r.note(i).state() == Note.State.GREY) {
                g.fill(RenderType.guiOverlay(), sx, sy, sx + 16, sy + 16, GREY_WASH);
                glyph(g, sx + 11, sy, GREY_GLYPH, GREY_MARK);
            }
        }
    }

    /**
     * What the row does, one line per cast (wrapped, each cast's first line marked with its bar's colour), and at the
     * bottom the run depth and, while some slots sleep, how to wake them. A long reading scrolls with the mouse wheel.
     */
    private void readingText(GuiGraphics g) {
        boolean asleep = reading != null && reading.awake() < menu.slotCount();
        int depthY = asleep ? TEXT_LAST - LINE : TEXT_LAST;
        g.drawString(font, "Run depth: " + Explain.depthName(RunDepth.clientDepth()), TEXT_X, depthY, SOFT, false);
        if (asleep) g.drawString(font, "Go deeper to wake more slots", TEXT_X, TEXT_LAST, HINT, false);
        if (reading == null) return;

        // lines at TEXT_TOP + 9k, each clear of the depth line by 2 px
        int rows = Math.max(1, (depthY - 10 - TEXT_TOP) / LINE + 1);
        int indent = reading.casts().isEmpty() ? 0 : MARK_INDENT;
        int width = TEXT_RIGHT - TEXT_X - indent;
        List<TextLine> lines = lines(reading, width, false);
        if (lines.size() > rows) lines = lines(reading, width - ARROW_ROOM, true);
        int max = Math.max(0, lines.size() - rows);
        scroll = Mth.clamp(scroll, 0, max);
        for (int k = 0; k < rows && scroll + k < lines.size(); k++) {
            TextLine l = lines.get(scroll + k);
            int ly = TEXT_TOP + k * LINE;
            if (l.mark() != 0) g.fill(TEXT_X, ly + 2, TEXT_X + 4, ly + 6, l.mark());
            g.drawString(font, l.text(), TEXT_X + indent, ly, LABEL, false);
        }
        if (scroll > 0) glyph(g, TEXT_RIGHT - 5, TEXT_TOP + 1, UP, LABEL);
        if (scroll < max) glyph(g, TEXT_RIGHT - 5, TEXT_TOP + (rows - 1) * LINE + 3, DOWN, LABEL);
    }

    /** The reading's lines wrapped to {@code width}, kept until its words change (a change scrolls back to the top). */
    private List<TextLine> lines(Reading r, int width, boolean narrow) {
        List<String> words = Explain.lines(r);
        if (!words.equals(textFrom)) {
            textFrom = words;
            textWide = null;
            textNarrow = null;
            scroll = 0;
        }
        List<TextLine> kept = narrow ? textNarrow : textWide;
        if (kept != null) return kept;
        List<TextLine> out = new ArrayList<>();
        for (int k = 0; k < words.size(); k++) {
            CastReading c = r.cast(k);
            int mark = c == null ? 0 : RuneLook.castColour(c);
            List<FormattedCharSequence> split = font.split(Component.literal(words.get(k)), width);
            for (int j = 0; j < split.size(); j++) out.add(new TextLine(split.get(j), j == 0 ? mark : 0));
        }
        if (narrow) textNarrow = out;
        else textWide = out;
        return out;
    }

    /** In combat runes can't be moved: the row dims and says how long is left. */
    private void combat(GuiGraphics g, int ticks) {
        int n = menu.slotCount();
        if (n > 0) g.fill(RenderType.guiOverlay(), runeX(0) - 3, GAP_TOP, runeX(n - 1) + 19, GAP_BOTTOM, LOCK_DIM);
        String s = lockText(ticks);
        // centred over the row (a bow's is shorter than a sword's), kept inside the panel
        int mid = n > 0 ? (runeX(0) - 1 + runeX(n - 1) + 17) / 2 : imageWidth / 2;
        int w = font.width(s), tx = Mth.clamp(mid - w / 2, 6, imageWidth - 6 - w), ty = ROW_Y + 4;
        g.fill(RenderType.guiOverlay(), tx - 3, ty - 2, tx + w + 3, ty + LINE, LOCK_BACK);
        // in front of the rune items (drawn at about z 250)
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 300.0F);
        g.drawString(font, s, tx, ty, LOCK_TEXT, false);
        g.pose().popPose();
    }

    private static String lockText(int ticks) { return "In combat: wait " + Mth.ceil(ticks / 20.0F) + " s"; }

    // ------------------------------------------------------------------------------------------------ tooltips

    @Override
    protected void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (menu.getCarried().isEmpty() && reading != null) {
            int gap = gapAt(mouseX, mouseY);
            if (gap >= 0) {
                int lock = menu.lockTicks();
                String s = lock > 0 ? lockText(lock)
                        : reading.row().breakAfter(gap) ? "Break: a new cast starts here" : "Click to start a new cast here";
                g.renderTooltip(font, Component.literal(s), mouseX, mouseY);
                return;
            }
            Slot slot = hoveredSlot;
            if (slot != null && slot.index >= RuneEditorMenu.RUNE_SLOTS && slot.index < RuneEditorMenu.INV_SLOTS) {
                runeTooltip(g, reading, slot, slot.index - RuneEditorMenu.RUNE_SLOTS, mouseX, mouseY);
                return;
            }
        }
        super.renderTooltip(g, mouseX, mouseY);
    }

    /** A rune slot: the rune's own tooltip with why it is grey or asleep under its name; an empty asleep slot says when it wakes. */
    private void runeTooltip(GuiGraphics g, Reading r, Slot slot, int i, int mouseX, int mouseY) {
        boolean asleep = i >= r.awake();
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            if (asleep) {
                String wakes = Explain.wakes(r.spec().host().wakesAt(i, r.spec().bonusSlots()));
                g.renderTooltip(font, Component.literal("Asleep: " + wakes).withStyle(ChatFormatting.AQUA), mouseX, mouseY);
            }
            return;
        }
        List<Component> tip = new ArrayList<>(getTooltipFromContainerItem(stack));
        String note = i < r.row().size() ? Explain.note(r, i) : "";
        if (!note.isEmpty()) {
            Component line = asleep ? Component.literal("Asleep: " + note).withStyle(ChatFormatting.AQUA)
                    : Component.literal(capital(note)).withStyle(ChatFormatting.GOLD);
            tip.add(Math.min(1, tip.size()), line);
        }
        g.renderTooltip(font, tip, stack.getTooltipImage(), stack, mouseX, mouseY);
    }

    // ------------------------------------------------------------------------------------------------ input

    /** The break gap under the mouse (the gap after slot {@code i}, between two slots the gear has), or -1. */
    private int gapAt(double mouseX, double mouseY) {
        double lx = mouseX - leftPos, ly = mouseY - topPos;
        if (ly < GAP_TOP || ly >= GAP_BOTTOM) return -1;
        int n = menu.slotCount();
        for (int i = 0; i + 1 < n; i++) {
            if (lx >= runeX(i) + 17 && lx < runeX(i + 1) - 1) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int gap = gapAt(mouseX, mouseY);
            if (gap >= 0) {
                swallowRelease = true;
                // toggled here at once, and on the server, which has the last word (it refuses in combat)
                if (minecraft != null && minecraft.player != null && minecraft.gameMode != null && menu.clickMenuButton(minecraft.player, gap)) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, gap);
                    boolean on = menu.row().breakAfter(gap);
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, on ? 1.15F : 0.85F));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && swallowRelease) {
            swallowRelease = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double lx = mouseX - leftPos, ly = mouseY - topPos;
        if (delta != 0 && lx >= TEXT_X && lx < TEXT_RIGHT && ly >= TEXT_TOP && ly < INV_Y - 1) {
            scroll -= (int) Math.signum(delta);      // kept in range when drawn
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // the rune key closes the rune screen, as the inventory key closes the inventory
        if (RuneKeys.OPEN.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------------------------------------ small helpers

    /** A tiny glyph, one pixel per '#', drawn over anything (items included). */
    private static void glyph(GuiGraphics g, int x, int y, String[] rows, int colour) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            int c = 0;
            while (c < row.length()) {
                if (row.charAt(c) != '#') {
                    c++;
                    continue;
                }
                int from = c;
                while (c < row.length() && row.charAt(c) == '#') c++;
                g.fill(RenderType.guiOverlay(), x + from, y + r, x + c, y + r + 1, colour);
            }
        }
    }

    private String clip(String s, int width) {
        if (font.width(s) <= width) return s;
        return font.plainSubstrByWidth(s, Math.max(0, width - font.width("..."))) + "...";
    }

    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
