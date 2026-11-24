package com.explorationremix.client.rune.table;

import com.explorationremix.ExplorationRemix;
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
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The enchanting table's rune view, its clicks and its words: the view itself is in the world (RuneTableView). Click a
 * socket to raise the runes you carry for it, click one of those to set it; right-click a socket (or pick the struck
 * one among the risen) to take its rune out. Each socket is named under it, the active one with its second phase's
 * mark once it wakes. A panel framed in silver at the foot of the screen says what the mouse is over: what it is, and
 * for a rune not yet set what the weapon would do with it (Taran, 2026-11-23: "have tooltips appear at the bottom of
 * the screen"); over nothing, it says what the weapon does. "Enchant" at the top swaps to the table's own enchanting.
 *
 * <p>The frames are pixel art, one texel to a GUI pixel (textures/gui/rune_table.png, from tools/rune_table_art.py):
 * silver filigree set with gems, a crown of gems over the panel, a plate on its edge for its title, flourishes either
 * side of the weapon's name, and an icon for each kind of rune.
 */
public class RuneTableScreen extends AbstractContainerScreen<RuneTableMenu> {
    // ---- the words' colours (no warm colours, no cyan)
    private static final int TEXT = 0xFFDCE6F5, DIM = 0xFF8C9BB3, FAINT = 0xFF5F6E87, BRIGHT = 0xFFFFFFFF, AWAKE = 0xFF000000 | RuneTableView.PHASE;
    /** The widest the panel gets (GUI pixels). */
    private static final int PANEL_MAX = 440;

    // ---- the pixel art: where each piece lies on the sheet (texels), and the corners of the pieces drawn stretched
    private static final ResourceLocation ART = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/gui/rune_table.png");
    private static final int FRAME_SIZE = 32, FRAME_CORNER = 12;
    private static final int BUTTON_U = 32, BUTTON_LIT_U = 48, BUTTON_SIZE = 16, BUTTON_CORNER = 5, BUTTON_H = 18;
    private static final int CROWN_V = 40, CROWN_W = 41, CROWN_H = 11;
    private static final int FLOURISH_RIGHT_U = 48, FLOURISH_V = 56, FLOURISH_W = 40, FLOURISH_H = 9;
    private static final int ICON_V = 72, ICON = 9;
    /** The icons: the three kinds' are their sockets' numbers; then the second phase's mark, and none. */
    private static final int PHASE_ICON = 3, NO_ICON = -1;
    /** The title's plate on the panel's top edge: its height, and how far in from the panel's left end it sits. */
    private static final int PLATE_H = 16, PLATE_IN = 16;
    /** Ticks the panel goes on saying what the mouse was over once it leaves (passing between things doesn't flicker), and the ticks new words take to come in. */
    private static final int LINGER = 6;
    private static final float WORDS_IN = 3.0F;

    private Loadout seen = Loadout.EMPTY;
    private boolean enchantHovered;
    private int bx0, by0, bx1, by1;
    /** How far the words have faded in (0 to 1). */
    private float fade = 1.0F;
    /** What the panel says: what the mouse is over, or was a moment ago (null: what the weapon does); till when; and since when it has said it. */
    @Nullable
    private RuneTableView.Target said;
    private int saidUntil;
    private String saidKey = "";
    private float saidSince = -100.0F;
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
        // the words come in with the runes, once the view is most of the way over the table
        fade = Mth.clamp((RuneTableView.shown(partialTick) - 0.55F) / 0.45F, 0.0F, 1.0F);
        if (fade < 0.05F || !RuneTableView.drawn()) return;
        ItemStack weapon = menu.weapon();
        HostSpec spec = RuneGear.spec(weapon);
        if (spec == null) return;
        int level = minecraft.player.experienceLevel;
        Reading now = Reader.read(menu.loadout(), spec, level, RuneConfig.limits());
        float time = RuneTableView.ticks() + partialTick;
        header(g, weapon, mouseX, mouseY);
        for (RuneTableView.Target t : RuneTableView.targets) if (t.kind == RuneTableView.Target.Kind.SOCKET) socketLabel(g, t, now);
        risenHint(g);
        panel(g, said(time), now, spec, level, time);
    }

    /** A colour with its alpha faded as far as the words are (and as far as {@code k}). */
    private int a(int argb, float k) { return Math.max(4, Math.round((argb >>> 24) * fade * k)) << 24 | (argb & 0xFFFFFF); }

    private int a(int argb) { return a(argb, 1.0F); }

    /** Pixel art from the sheet, faded with the words: drawn blended, the shader's colour put back after. */
    private void art(GuiGraphics g, float alpha, Runnable draw) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1.0F, 1.0F, 1.0F, fade * alpha);
        draw.run();
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    /** An icon, 9 x 9: a kind's (by its socket's number) or the second phase's. */
    private void icon(GuiGraphics g, int which, int x, int y, float alpha) {
        art(g, alpha, () -> g.blit(ART, x, y, 10 * which, ICON_V, ICON, ICON));
    }

    /**
     * The weapon's name between two silver flourishes, what to do under it (or the lock while in combat), and the way
     * to the table's own enchanting: a gem-cornered button at the top right.
     */
    private void header(GuiGraphics g, ItemStack weapon, int mouseX, int mouseY) {
        Component enchant = Component.translatable("screen.explorationremix.rune_table.enchant");
        int w = font.width(enchant) + 18;
        bx1 = width - 8;
        bx0 = bx1 - w;
        by0 = 6;
        by1 = by0 + BUTTON_H;
        enchantHovered = mouseX >= bx0 && mouseX < bx1 && mouseY >= by0 && mouseY < by1;
        int u = enchantHovered ? BUTTON_LIT_U : BUTTON_U;
        art(g, 1.0F, () -> g.blitNineSliced(ART, bx0, by0, bx1 - bx0, BUTTON_H, BUTTON_CORNER, BUTTON_SIZE, BUTTON_SIZE, u, 0));
        g.drawString(font, enchant, bx0 + 9, by0 + 5, a(enchantHovered ? BRIGHT : TEXT), false);

        Component name = weapon.getHoverName();
        int cx = width / 2, nw = font.width(name);
        int left = cx - nw / 2 - 6 - FLOURISH_W, right = cx + (nw + 1) / 2 + 6;
        // the flourishes only where they fit, clear of the button
        if (left >= 4 && right + FLOURISH_W <= bx0 - 4) {
            art(g, 1.0F, () -> {
                g.blit(ART, left, 7, 0, FLOURISH_V, FLOURISH_W, FLOURISH_H);
                g.blit(ART, right, 7, FLOURISH_RIGHT_U, FLOURISH_V, FLOURISH_W, FLOURISH_H);
            });
        }
        g.drawCenteredString(font, name, cx, 8, a(TEXT));
        Component how = menu.locked()
                ? Component.translatable("screen.explorationremix.rune_table.locked", (menu.lockTicks() + 19) / 20)
                : Component.translatable(RuneTableView.chosen != null ? "screen.explorationremix.rune_table.how_chosen" : "screen.explorationremix.rune_table.how");
        g.drawCenteredString(font, how, cx, 19, a(menu.locked() ? 0xFFB7C8F0 : DIM));
    }

    /**
     * Under each socket, two lines: its kind's icon and name, in its colour, and the rune in it ("II" once the active's
     * second phase wakes).
     */
    private void socketLabel(GuiGraphics g, RuneTableView.Target t, Reading now) {
        if (!t.onScreen) return;
        int x = Math.round(t.sx), y = Math.round(t.sy + t.sr + 3);
        Component kind = Component.translatable("rune.explorationremix.kind." + t.socket.kind.word);
        int kx = x - (ICON + 2 + font.width(kind)) / 2;
        icon(g, t.socket.ordinal(), kx, y - 1, 1.0F);
        g.drawString(font, kind, kx + ICON + 2, y, a(0xFF000000 | RuneTableView.colour(t.socket)), true);
        Component name;
        int colour;
        if (t.rune == null) {
            name = Component.translatable("screen.explorationremix.rune_table.empty");
            colour = FAINT;
        } else if (t.socket == Socket.ACTIVE && now.empowered()) {
            name = Component.literal(t.rune.title + " II");
            colour = AWAKE;
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
     * What the panel speaks of: what the mouse is over; for a moment after it leaves, what it was over (a socket as it
     * is now, in case a rune just went in); then nothing, and the panel says what the weapon does.
     */
    @Nullable
    private RuneTableView.Target said(float time) {
        RuneTableView.Target h = RuneTableView.hovered;
        int ticks = RuneTableView.ticks();
        if (h != null) {
            said = h;
            saidUntil = ticks + LINGER;
        } else if (said != null) {
            if (ticks > saidUntil) {
                said = null;
            } else if (said.kind == RuneTableView.Target.Kind.SOCKET) {
                for (RuneTableView.Target t : RuneTableView.targets) {
                    if (t.kind == RuneTableView.Target.Kind.SOCKET && t.socket == said.socket) said = t;
                }
            }
        }
        String key = said == null ? "" : said.kind + ":" + said.socket + ":" + said.rune;
        if (!key.equals(saidKey)) {
            saidKey = key;
            saidSince = time;
        }
        return said;
    }

    /**
     * The panel at the foot, framed in silver with a crown of gems: a plate on its top edge names what it speaks of, and
     * under it, what that is and does. It keeps the room the view leaves it ({@link RuneTableView#panelLines}), whatever
     * it says; a longer say grows it upward for as long as it lasts.
     */
    private void panel(GuiGraphics g, @Nullable RuneTableView.Target t, Reading now, HostSpec spec, int level, float time) {
        Say say = say(t, now, spec, level);
        int pw = Math.min(width - 24, PANEL_MAX), x0 = (width - pw) / 2;
        int textW = pw - 2 * RuneTableView.PANEL_PAD_X;
        List<Row> rows = new ArrayList<>();
        for (Line l : say.lines) {
            int indent = l.icon == NO_ICON ? 0 : ICON + 3;
            FormattedText text = l.lead.isEmpty() ? FormattedText.of(l.text)
                    : FormattedText.composite(FormattedText.of(l.lead + " ", Style.EMPTY.withColor(FAINT & 0xFFFFFF)), FormattedText.of(l.text));
            List<FormattedCharSequence> split = font.split(text, textW - indent);
            for (int i = 0; i < split.size(); i++) rows.add(new Row(split.get(i), l.colour, i == 0 ? l.icon : NO_ICON, indent));
        }
        int h = RuneTableView.panelHeight(Math.max(RuneTableView.panelLines(height), rows.size()));
        int y0 = height - RuneTableView.PANEL_MARGIN - h;
        int titleW = font.width(say.title), iconW = say.icon == NO_ICON ? 0 : ICON + 3;
        int px0 = x0 + PLATE_IN, py0 = y0 - RuneTableView.PANEL_RAISED, plateW = Math.min(pw - 2 * PLATE_IN, titleW + iconW + 14);
        art(g, 1.0F, () -> {
            g.blitNineSliced(ART, x0, y0, pw, h, FRAME_CORNER, FRAME_SIZE, FRAME_SIZE, 0, 0);
            g.blit(ART, x0 + (pw - CROWN_W) / 2, y0 - 1, 0, CROWN_V, CROWN_W, CROWN_H);
            g.blitNineSliced(ART, px0, py0, plateW, PLATE_H, BUTTON_CORNER, BUTTON_SIZE, BUTTON_SIZE, BUTTON_U, 0);
        });
        // the words come in quickly as the panel turns to something new
        float in = 0.25F + 0.75F * Mth.clamp((time - saidSince) / WORDS_IN, 0.0F, 1.0F);
        if (say.icon != NO_ICON) icon(g, say.icon, px0 + 7, py0 + 3, 1.0F);
        g.drawString(font, say.title, px0 + 7 + iconW, py0 + 4, a(say.colour, in), false);
        int bx = x0 + RuneTableView.PANEL_PAD_X, by = y0 + RuneTableView.PANEL_PAD_TOP;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = by + i * RuneTableView.PANEL_LINE;
            if (r.icon != NO_ICON) icon(g, r.icon, bx, y - 1, in);
            g.drawString(font, r.text, bx + r.indent, y, a(r.colour, in), false);
        }
    }

    /**
     * What the panel says of {@code t}: its title (with an icon and a colour) and its lines. Over nothing, what the
     * weapon does: the cast, the passive and the second phase, each by its icon. A socket: its rune and what it does,
     * the active's second phase, and the clicks; or, empty, what goes in it. A risen rune: what it is, and the lines of
     * what the weapon does that it would change; the struck one, what taking it out would change.
     */
    private Say say(@Nullable RuneTableView.Target t, Reading now, HostSpec spec, int level) {
        List<Line> out = new ArrayList<>();
        if (t == null) {
            if (menu.loadout().isEmpty()) {
                out.add(new Line(words("none_set"), TEXT, NO_ICON));
                out.add(new Line(words("three"), DIM, NO_ICON));
            } else {
                out.add(new Line(Explain.castLine(now), TEXT, Socket.ACTIVE.ordinal()));
                String modifier = Explain.note(now, Socket.MODIFIER);
                if (!modifier.isEmpty()) out.add(new Line(modifier + ".", DIM, Socket.MODIFIER.ordinal()));
                String passive = Explain.passiveLine(now);
                if (!passive.isEmpty()) out.add(new Line(passive, DIM, Socket.PASSIVE.ordinal()));
                String phase = Explain.phaseLine(now);
                if (!phase.isEmpty()) out.add(new Line(phase, now.empowered() ? AWAKE : DIM, PHASE_ICON));
            }
            return new Say(words("what_it_does"), TEXT, NO_ICON, out);
        }
        int kind = 0xFF000000 | RuneTableView.colour(t.socket), icon = t.socket.ordinal();
        return switch (t.kind) {
            case SOCKET -> {
                if (t.rune == null) {
                    out.add(new Line(words("about." + t.socket.kind.word), TEXT, NO_ICON));
                    boolean carries = !menu.carried(t.socket).isEmpty();
                    out.add(new Line(Component.translatable(carries ? "screen.explorationremix.rune_table.click_choose"
                            : "screen.explorationremix.rune_table.none_carried", t.socket.kind.word).getString(), DIM, NO_ICON));
                    yield new Say(Component.translatable("screen.explorationremix.rune_table.socket_empty",
                            Component.translatable("rune.explorationremix.kind." + t.socket.kind.word)).getString(), kind, icon, out);
                }
                boolean awake = t.socket == Socket.ACTIVE && now.empowered();
                out.add(new Line(desc(t.rune), TEXT, NO_ICON));
                String note = Explain.note(now, t.socket);
                if (!note.isEmpty()) out.add(new Line(note + ".", DIM, NO_ICON));
                if (t.rune.isAction()) out.add(new Line(Explain.phaseLine(now), awake ? AWAKE : DIM, PHASE_ICON));
                out.add(new Line(words("click_change"), FAINT, NO_ICON));
                yield new Say(t.rune.title + (awake ? " II" : ""), awake ? AWAKE : kind, icon, out);
            }
            case RUNE -> {
                out.add(new Line(desc(t.rune), TEXT, NO_ICON));
                Reading with = Reader.read(menu.loadout().with(t.socket, t.rune), spec, level, RuneConfig.limits());
                String note = Explain.note(with, t.socket);
                if (!note.isEmpty()) out.add(new Line(note + ".", DIM, NO_ICON));
                differences(out, now, with, words("with_it"));
                yield new Say(t.rune.title + (t.count > 1 ? "  \u00d7" + t.count : ""), kind, icon, out);
            }
            case TAKE_OUT -> {
                out.add(new Line(words("back_to_you"), DIM, NO_ICON));
                Reading without = Reader.read(menu.loadout().with(t.socket, null), spec, level, RuneConfig.limits());
                differences(out, now, without, words("without_it"));
                yield new Say(Component.translatable("screen.explorationremix.rune_table.take_out", t.rune.title).getString(), kind, icon, out);
            }
        };
    }

    /** The lines of what the weapon does that {@code after} would change from {@code before}, each by its icon, the first led by {@code lead}. */
    private static void differences(List<Line> out, Reading before, Reading after, String lead) {
        String[] was = {Explain.castLine(before), Explain.passiveLine(before), Explain.phaseLine(before)};
        String[] will = {Explain.castLine(after), Explain.passiveLine(after), Explain.phaseLine(after)};
        int[] icons = {Socket.ACTIVE.ordinal(), Socket.PASSIVE.ordinal(), PHASE_ICON};
        boolean led = false;
        for (int i = 0; i < was.length; i++) {
            if (will[i].isEmpty() || will[i].equals(was[i])) continue;
            out.add(new Line(will[i], DIM, icons[i], led ? "" : lead));
            led = true;
        }
    }

    /** What the panel says: a title, its colour and icon, and lines. */
    private record Say(String title, int colour, int icon, List<Line> lines) {}

    /** A line of the panel's words (wrapped as it needs), its colour, the icon before it, and a faint lead-in. */
    private record Line(String text, int colour, int icon, String lead) {
        Line(String text, int colour, int icon) { this(text, colour, icon, ""); }
    }

    /** A line as drawn, once wrapped. */
    private record Row(FormattedCharSequence text, int colour, int icon, int indent) {}

    private static String words(String key) { return Component.translatable("screen.explorationremix.rune_table." + key).getString(); }

    private static String desc(RuneId r) { return Component.translatable("rune.explorationremix." + r.id + ".desc").getString(); }

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
