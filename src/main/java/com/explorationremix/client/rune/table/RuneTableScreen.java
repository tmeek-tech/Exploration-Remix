package com.explorationremix.client.rune.table;

import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Loadout;
import com.explorationremix.rune.engine.Reader;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.Socket;
import com.explorationremix.rune.menu.RuneTableMenu;
import com.explorationremix.rune.RuneConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The enchanting table's rune view, its clicks and its words: the view itself is in the world (RuneTableView). Click a
 * socket to raise the runes you carry for it, click one of those to set it; right-click a socket (or pick the struck
 * one among the risen) to take its rune out. Each socket is named under it, the active one with its second phase's
 * level; the mouse over anything says what it is, and over a rune not yet set, what the weapon would cast with it. A
 * panel at the foot says what the weapon does now. "Enchant" at the top swaps to the table's own enchanting.
 */
public class RuneTableScreen extends AbstractContainerScreen<RuneTableMenu> {
    // ---- the words' colours (no warm colours, no cyan)
    private static final int TEXT = 0xFFDCE6F5, DIM = 0xFF8C9BB3, FAINT = 0xFF5F6E87, EDGE = 0x998EC2FF, EDGE_SOFT = 0x404F6FA8;
    /** The panel's and the tips' dark blue: the tips nearly solid, as they sit over everything. */
    private static final int PANEL = 0xE00A1020, TIP = 0xF40A1020;
    /** The widest the panel gets (GUI pixels), and the widest a tip gets. */
    private static final int PANEL_MAX = 420, TIP_WIDTH = 220;

    private Loadout seen = Loadout.EMPTY;
    private boolean enchantHovered;
    private int bx0, by0, bx1, by1;
    /** How far the words have faded in (0 to 1), and the top of the panel drawn this frame (the tips keep clear of it). */
    private float fade = 1.0F;
    private int panelTop;
    /** Dev (the shot driver): what the mouse is held over ("active", a rune's id, "out"), or null to follow the mouse. */
    private static String devHover;

    public RuneTableScreen(RuneTableMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = 0;
        imageHeight = 0;
    }

    @Override
    protected void init() {
        super.init();
        RuneTableView.open(menu.table());
        seen = menu.loadout();
    }

    @Override
    public void removed() {
        super.removed();
        RuneTableView.close();
        devHover = null;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {}

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {}

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        changes();
        hover(mouseX, mouseY);
        if (devHover != null && RuneTableView.hovered != null) {
            // the shot driver holds the mouse over a target: the tip sits where the mouse would
            mouseX = Math.round(RuneTableView.hovered.sx);
            mouseY = Math.round(RuneTableView.hovered.sy);
        }
        // the words come in with the runes, once the view is most of the way over the table
        fade = Mth.clamp((RuneTableView.shown(partialTick) - 0.55F) / 0.45F, 0.0F, 1.0F);
        if (fade < 0.05F || !RuneTableView.drawn()) return;
        ItemStack weapon = menu.weapon();
        HostSpec spec = RuneGear.spec(weapon);
        if (spec == null) return;
        int level = minecraft.player.experienceLevel;
        Reading now = Reader.read(menu.loadout(), spec, level, RuneConfig.limits());
        header(g, weapon, mouseX, mouseY);
        for (RuneTableView.Target t : RuneTableView.targets) if (t.kind == RuneTableView.Target.Kind.SOCKET) socketLabel(g, t, now);
        risenHint(g);
        panel(g, now);
        RuneTableView.Target h = RuneTableView.hovered;
        if (h != null) tip(g, h, now, spec, level, mouseX, mouseY);
    }

    /** A colour with its alpha faded as far as the words are. */
    private int a(int argb) { return Math.max(4, Math.round((argb >>> 24) * fade)) << 24 | (argb & 0xFFFFFF); }

    /** The weapon's name, what to do, the lock while in combat, and the way to the table's own enchanting. */
    private void header(GuiGraphics g, ItemStack weapon, int mouseX, int mouseY) {
        g.drawCenteredString(font, weapon.getHoverName(), width / 2, 10, a(TEXT));
        Component how = menu.locked()
                ? Component.translatable("screen.explorationremix.rune_table.locked", (menu.lockTicks() + 19) / 20)
                : Component.translatable(RuneTableView.chosen != null ? "screen.explorationremix.rune_table.how_chosen" : "screen.explorationremix.rune_table.how");
        g.drawCenteredString(font, how, width / 2, 22, a(menu.locked() ? 0xFFB7C8F0 : DIM));
        Component enchant = Component.translatable("screen.explorationremix.rune_table.enchant");
        int w = font.width(enchant) + 16;
        bx1 = width - 10;
        bx0 = bx1 - w;
        by0 = 8;
        by1 = by0 + 16;
        enchantHovered = mouseX >= bx0 && mouseX < bx1 && mouseY >= by0 && mouseY < by1;
        g.fill(bx0, by0, bx1, by1, a(enchantHovered ? 0xE0182844 : PANEL));
        g.renderOutline(bx0, by0, w, by1 - by0, a(enchantHovered ? 0xFFB8D8FF : EDGE));
        g.drawString(font, enchant, bx0 + 8, by0 + 4, a(enchantHovered ? 0xFFFFFFFF : TEXT), false);
    }

    /** Under each socket, two lines: what kind it is, in its colour, and the rune in it ("II" once the active's second phase wakes). */
    private void socketLabel(GuiGraphics g, RuneTableView.Target t, Reading now) {
        if (!t.onScreen) return;
        int x = Math.round(t.sx), y = Math.round(t.sy + t.sr + 3);
        g.drawCenteredString(font, Component.translatable("rune.explorationremix.kind." + t.socket.kind.word), x, y, a(0xFF000000 | RuneTableView.colour(t.socket)));
        Component name;
        int colour;
        if (t.rune == null) {
            name = Component.translatable("screen.explorationremix.rune_table.empty");
            colour = FAINT;
        } else if (t.socket == Socket.ACTIVE && now.empowered()) {
            name = Component.literal(t.rune.title + " II");
            colour = 0xFF000000 | RuneTableView.PHASE;
        } else {
            name = Component.literal(t.rune.title);
            colour = now.note(t.socket).grey() ? DIM : TEXT;
        }
        g.drawCenteredString(font, name, x, y + 10, a(colour));
    }

    /** With a socket chosen and nothing to raise for it: says so, over the weapon. */
    private void risenHint(GuiGraphics g) {
        Socket s = RuneTableView.chosen;
        if (s == null) return;
        for (RuneTableView.Target t : RuneTableView.targets) if (t.kind != RuneTableView.Target.Kind.SOCKET) return;
        float[] p = RuneTableView.hintAt();
        if (p == null) return;
        g.drawCenteredString(font, Component.translatable("screen.explorationremix.rune_table.none_carried", s.kind.word), Math.round(p[0]), Math.round(p[1]), a(DIM));
    }

    /**
     * At the foot: what the weapon does now, in the reading's own words, the first line bright; as wide as the words
     * need, up to {@link #PANEL_MAX}. The view is told the room it takes, so the sockets' names stay clear of it.
     */
    private void panel(GuiGraphics g, Reading now) {
        int max = Math.min(width - 32, PANEL_MAX);
        List<String> words = menu.loadout().isEmpty()
                ? List.of(Component.translatable("screen.explorationremix.rune_table.none_set").getString()) : Explain.lines(now);
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            for (FormattedCharSequence line : font.split(FormattedText.of(words.get(i)), max - 16)) {
                lines.add(line);
                colours.add(i == 0 ? TEXT : DIM);
            }
        }
        int w = 0;
        for (FormattedCharSequence line : lines) w = Math.max(w, font.width(line));
        w = Math.min(max, w + 16);
        int h = lines.size() * 10 + 8;
        int x0 = (width - w) / 2, y1 = height - 6, y0 = y1 - h;
        box(g, x0, y0, w, h, PANEL);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x0 + 8, y0 + 5 + i * 10, a(colours.get(i)), false);
        panelTop = y0;
        RuneTableView.footNeed(h + 12);
    }

    /** What the mouse is over: what it is, and for a rune not yet set what the weapon would do with it. */
    private void tip(GuiGraphics g, RuneTableView.Target t, Reading now, HostSpec spec, int level, int mouseX, int mouseY) {
        List<Line> out = new ArrayList<>();
        int kind = 0xFF000000 | RuneTableView.colour(t.socket);
        switch (t.kind) {
            case SOCKET -> {
                if (t.rune == null) {
                    out.add(new Line(Component.translatable("screen.explorationremix.rune_table.socket_empty",
                            Component.translatable("rune.explorationremix.kind." + t.socket.kind.word)).getString(), kind));
                    boolean carries = !menu.carried(t.socket).isEmpty();
                    out.add(new Line(Component.translatable(carries ? "screen.explorationremix.rune_table.click_choose"
                            : "screen.explorationremix.rune_table.none_carried", t.socket.kind.word).getString(), DIM));
                } else {
                    out.add(new Line(t.rune.title, kind));
                    out.add(new Line(desc(t.rune), TEXT));
                    String note = Explain.note(now, t.socket);
                    if (!note.isEmpty()) out.add(new Line(note + ".", DIM));
                    if (t.rune.isAction()) out.add(new Line(Explain.phaseLine(now), now.empowered() ? 0xFF000000 | RuneTableView.PHASE : DIM));
                    out.add(new Line(Component.translatable("screen.explorationremix.rune_table.click_change").getString(), FAINT));
                }
            }
            case RUNE -> {
                out.add(new Line(t.rune.title + (t.count > 1 ? "  ×" + t.count : ""), kind));
                out.add(new Line(desc(t.rune), TEXT));
                Reading with = Reader.read(menu.loadout().with(t.socket, t.rune), spec, level, RuneConfig.limits());
                String note = Explain.note(with, t.socket);
                if (!note.isEmpty()) out.add(new Line(note + ".", DIM));
                out.add(new Line(Component.translatable("screen.explorationremix.rune_table.with_it").getString(), FAINT));
                for (String line : Explain.lines(with)) out.add(new Line(line, DIM));
            }
            case TAKE_OUT -> {
                out.add(new Line(Component.translatable("screen.explorationremix.rune_table.take_out", t.rune.title).getString(), kind));
                out.add(new Line(Component.translatable("screen.explorationremix.rune_table.back_to_you").getString(), DIM));
            }
        }
        List<FormattedCharSequence> seqs = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        for (Line l : out) {
            for (FormattedCharSequence s : font.split(FormattedText.of(l.text), TIP_WIDTH - 12)) {
                seqs.add(s);
                colours.add(l.colour);
            }
        }
        int w = 0;
        for (FormattedCharSequence s : seqs) w = Math.max(w, font.width(s));
        w += 14;
        int h = seqs.size() * 10 + 8;
        // a socket's over it, clear of its neighbours; a risen rune's beside the mouse; neither on the panel at the foot
        int x = mouseX + 14, y = mouseY - 6;
        if (t.kind == RuneTableView.Target.Kind.SOCKET && t.sy - t.sr - 6 - h >= 4) {
            x = Math.round(t.sx) - w / 2;
            y = Math.round(t.sy - t.sr) - 6 - h;
        } else if (x + w > width - 4) {
            x = mouseX - 14 - w;
        }
        x = Mth.clamp(x, 4, Math.max(4, width - 4 - w));
        if (y + h > panelTop - 4) y = panelTop - 4 - h;
        y = Math.max(4, y);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        box(g, x, y, w, h, TIP);
        for (int i = 0; i < seqs.size(); i++) g.drawString(font, seqs.get(i), x + 7, y + 5 + i * 10, a(colours.get(i)), false);
        g.pose().popPose();
    }

    private record Line(String text, int colour) {}

    private static String desc(RuneId r) { return Component.translatable("rune.explorationremix." + r.id + ".desc").getString(); }

    /** A dark blue panel with a fine light-blue edge. */
    private void box(GuiGraphics g, int x, int y, int w, int h, int fill) {
        g.fill(x, y, x + w, y + h, a(fill));
        g.renderOutline(x, y, w, h, a(EDGE));
        g.renderOutline(x + 1, y + 1, w - 2, h - 2, a(EDGE_SOFT));
    }

    // ------------------------------------------------------------------------------------------------ the mouse

    /** What the mouse is over, of what was drawn last: the nearest within its reach (risen runes before sockets). */
    private void hover(int mouseX, int mouseY) {
        RuneTableView.Target best = null;
        double bestD = Double.MAX_VALUE;
        for (RuneTableView.Target t : RuneTableView.targets) {
            if (!t.onScreen) continue;
            if (devHover != null) {
                if (named(t, devHover)) best = t;
                continue;
            }
            double d = Math.hypot(mouseX - t.sx, mouseY - t.sy);
            if (d > t.sr) continue;
            if (d < bestD) {
                best = t;
                bestD = d;
            }
        }
        if (best != null && !best.same(RuneTableView.hovered) && best.kind != RuneTableView.Target.Kind.SOCKET) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_CLUSTER_STEP, 1.7F, 0.25F));
        }
        RuneTableView.hovered = best;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && enchantHovered) {
            press(RuneTableMenu.ENCHANT);
            return true;
        }
        RuneTableView.Target t = RuneTableView.hovered;
        if (t == null) {
            // a click on nothing lets the risen runes sink back
            if (RuneTableView.chosen != null) chime(0.8F);
            RuneTableView.chosen = null;
            return true;
        }
        boolean locked = menu.locked();
        switch (t.kind) {
            case SOCKET -> {
                if (button == 1) {
                    if (t.rune != null && !locked) press(RuneTableMenu.clearButton(t.socket));
                } else if (RuneTableView.chosen == t.socket) {
                    RuneTableView.chosen = null;
                    chime(0.8F);
                } else {
                    RuneTableView.chosen = t.socket;
                    RuneTableView.chosenAt = RuneTableView.ticks();
                    chime(1.2F + 0.15F * t.socket.ordinal());
                }
            }
            case RUNE -> {
                if (!locked) press(RuneTableMenu.setButton(t.socket, t.rune));
            }
            case TAKE_OUT -> {
                if (!locked) press(RuneTableMenu.clearButton(t.socket));
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) { return true; }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) { return true; }

    private void press(int id) {
        if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private void chime(float pitch) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, pitch, 0.5F));
    }

    /** The server's change arrived: the socket flashes, and the risen runes sink back once one is set. */
    private void changes() {
        Loadout l = menu.loadout();
        if (l.equals(seen)) return;
        for (Socket s : Socket.values()) {
            if (l.get(s) != seen.get(s)) {
                RuneTableView.burst(s);
                if (RuneTableView.chosen == s) RuneTableView.chosen = null;
            }
        }
        seen = l;
    }

    // ------------------------------------------------------------------------------------------------ dev

    /** Whether the target goes by this name: a socket's ("active"), a risen rune's id, or "out" for taking one out. */
    private static boolean named(RuneTableView.Target t, String name) {
        return switch (t.kind) {
            case SOCKET -> t.socket.name().equalsIgnoreCase(name);
            case RUNE -> t.rune == RuneId.byId(name);
            case TAKE_OUT -> name.equals("out");
        };
    }

    private static Socket socketNamed(String name) {
        for (Socket s : Socket.values()) if (s.name().equalsIgnoreCase(name)) return s;
        return null;
    }

    /**
     * The shot driver's steps at an open table: "choose socket" raises the runes carried for it, "hover name|none"
     * holds the mouse over a socket, a risen rune or "out", "set rune" and "out socket" press as a click would, and
     * "enchant" swaps to the table's own enchanting. False if the table is not open or the step is unknown.
     */
    public static boolean dev(String what, String arg) {
        if (!(net.minecraft.client.Minecraft.getInstance().screen instanceof RuneTableScreen screen)) return false;
        switch (what) {
            case "choose" -> {
                Socket k = socketNamed(arg);
                if (k == null) return false;
                RuneTableView.chosen = k;
                RuneTableView.chosenAt = RuneTableView.ticks();
            }
            case "hover" -> devHover = arg.equals("none") || arg.isEmpty() ? null : arg;
            case "set" -> {
                RuneId r = RuneId.byId(arg);
                if (r == null) return false;
                screen.press(RuneTableMenu.setButton(Socket.of(r.kind), r));
            }
            case "out" -> {
                Socket k = socketNamed(arg);
                if (k == null) return false;
                screen.press(RuneTableMenu.clearButton(k));
            }
            case "enchant" -> screen.press(RuneTableMenu.ENCHANT);
            default -> {
                return false;
            }
        }
        return true;
    }
}
