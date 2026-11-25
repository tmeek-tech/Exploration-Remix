package com.explorationremix.client.rune.table;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.armor.WornRunes;
import com.explorationremix.rune.engine.ArmorRunes;
import com.explorationremix.rune.engine.ArmorSet;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Piece;
import com.explorationremix.rune.engine.Reader;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.RuneKind;
import com.explorationremix.rune.engine.Socket;
import com.explorationremix.rune.menu.RuneTableMenu;
import com.explorationremix.rune.net.RuneNet;
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
import java.util.Locale;

/**
 * The enchanting table's rune view, its clicks and its words: the view itself is in the world (RuneTableView). A column
 * at the left holds everything that takes runes (Taran, 2026-11-25: runes for bows and armor, and in place of the
 * table's enchanting): the weapon or tool in the main hand, then the helmet, chestplate, leggings and boots worn; click
 * one and its sockets hang over the book. Click a socket to raise the runes you carry for it, click one of those to set
 * it; right-click a socket (or pick the struck one among the risen) to take its rune out. Each socket is named under
 * it, the active one with its second phase's mark once it wakes. At the right, the sets: each collect rune worn and how
 * many pieces carry it, awake at three. A panel framed in silver at the foot of the screen says what the mouse is over:
 * what it is, and for a rune not yet set what the gear would do with it (Taran, 2026-11-23: "have tooltips appear at
 * the bottom of the screen"); over nothing, it says what the gear does.
 *
 * <p>The frames are pixel art, one texel to a GUI pixel (textures/gui/rune_table.png, from tools/rune_table_art.py):
 * silver filigree set with gems, a crown of gems over the panel, a plate on its edge for its title, flourishes either
 * side of the gear's name, and an icon for each kind of rune.
 */
public class RuneTableScreen extends AbstractContainerScreen<RuneTableMenu> {
    // ---- the words' colours (no warm colours, no cyan)
    private static final int TEXT = 0xFFDCE6F5, DIM = 0xFF8C9BB3, FAINT = 0xFF5F6E87, BRIGHT = 0xFFFFFFFF, AWAKE = 0xFF000000 | RuneTableView.PHASE;
    /** A collect set's colours in the tally: its lit pips, and its name once awake and as a full set. */
    private static final int SET_LIT = 0xFF000000 | RuneTableView.COLLECT, SET_DARK = 0xFF333B50, SET_AWAKE = 0xFFE8E0FF;
    /** The widest the panel gets (GUI pixels). */
    private static final int PANEL_MAX = 440;

    // ---- the pixel art: where each piece lies on the sheet (texels), and the corners of the pieces drawn stretched
    private static final ResourceLocation ART = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/gui/rune_table.png");
    private static final int FRAME_SIZE = 32, FRAME_CORNER = 12;
    private static final int BUTTON_U = 32, BUTTON_LIT_U = 48, BUTTON_SIZE = 16, BUTTON_CORNER = 5;
    private static final int CROWN_V = 40, CROWN_W = 41, CROWN_H = 11;
    private static final int FLOURISH_RIGHT_U = 48, FLOURISH_V = 56, FLOURISH_W = 40, FLOURISH_H = 9;
    private static final int ICON_V = 72, ICON = 9;
    /** The icons: the kinds' (RuneTableView.iconIndex); then the second phase's mark, and none. */
    private static final int PHASE_ICON = 3, NO_ICON = -1;
    /** The title's plate on the panel's top edge: its height, and how far in from the panel's left end it sits. */
    private static final int PLATE_H = 16, PLATE_IN = 16;
    /** Ticks the panel goes on saying what the mouse was over once it leaves (passing between things doesn't flicker), and the ticks new words take to come in. */
    private static final int LINGER = 6;
    private static final float WORDS_IN = 3.0F;
    /** The column of gear at the left: a box's size, the gap between boxes, how far in they sit and where they start. */
    private static final int BOX = 22, BOX_GAP = 3, BOX_X = 8, BOX_TOP = 34;
    /** The sets' tally at the right: how far in from the edge it sits, its width, a row's height, and where it starts. */
    private static final int TALLY_RIGHT = 8, TALLY_W = 72, TALLY_ROW = 11, TALLY_TOP = 34;
    /** The empty slots' outlines: the hand, then helmet to boots (the game's own). */
    private static final ResourceLocation[] EMPTY = {
            new ResourceLocation("textures/item/empty_slot_sword.png"),
            new ResourceLocation("textures/item/empty_armor_slot_helmet.png"),
            new ResourceLocation("textures/item/empty_armor_slot_chestplate.png"),
            new ResourceLocation("textures/item/empty_armor_slot_leggings.png"),
            new ResourceLocation("textures/item/empty_armor_slot_boots.png")};

    /** The runes the gear shown had when last looked at, by socket place, to flash a socket the server changed. */
    private final RuneId[] seen = new RuneId[3];
    private int seenFocus = -1;
    private ItemStack seenGear = ItemStack.EMPTY;
    /** The box the mouse is over in the column (-1: none), and the set in the tally (null: none). */
    private int hoveredGear = -1;
    @Nullable
    private RuneId hoveredSet;
    /** How far the words have faded in (0 to 1). */
    private float fade = 1.0F;
    /** What the panel says: what the mouse is over, or was a moment ago (null: what the gear does); till when; and since when it has said it. */
    @Nullable
    private RuneTableView.Target said;
    private int saidUntil;
    private String saidKey = "";
    private float saidSince = -100.0F;
    /** Dev (the shot driver): what the mouse is held over ("active", a rune's id, "out", "gear 2"), or null to follow the mouse. */
    private static String devHover;

    public RuneTableScreen(RuneTableMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = 0;
        imageHeight = 0;
    }

    @Override
    protected void init() {
        super.init();
        RuneTableView.open(menu.table(), firstGear());
        seenFocus = -1;
    }

    /** The gear the table opens on: what is in hand, if it takes runes; else the first piece worn that does. */
    private int firstGear() {
        if (!menu.hand().isEmpty()) return RuneTableMenu.HAND;
        for (int g = 1; g < RuneTableMenu.GEAR; g++) if (!menu.gear(g).isEmpty()) return g;
        return RuneTableMenu.HAND;
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
        ArmorSet set = WornRunes.of(minecraft.player);
        hoverSides(mouseX, mouseY, set);
        hover(mouseX, mouseY);
        // the words come in with the runes, once the view is most of the way over the table
        fade = Mth.clamp((RuneTableView.shown(partialTick) - 0.55F) / 0.45F, 0.0F, 1.0F);
        if (fade < 0.05F || !RuneTableView.drawn()) return;
        int level = minecraft.player.experienceLevel;
        float time = RuneTableView.ticks() + partialTick;
        Reading now = weaponReading(level);
        header(g);
        column(g);
        tally(g, set);
        for (RuneTableView.Target t : RuneTableView.targets) if (t.kind == RuneTableView.Target.Kind.SOCKET) socketLabel(g, t, now, set);
        risenHint(g);
        panel(g, say(said(time), now, set, level), time);
    }

    /** The weapon in hand's runes read now, when it is the gear shown; else null. */
    @Nullable
    private Reading weaponReading(int level) {
        if (RuneTableView.picked != RuneTableMenu.HAND) return null;
        HostSpec spec = RuneGear.spec(menu.weapon());
        return spec == null ? null : Reader.read(menu.loadout(), spec, level, RuneConfig.limits());
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

    /** An icon, 9 x 9: a kind's (RuneTableView.iconIndex) or the second phase's. */
    private void icon(GuiGraphics g, int which, int x, int y, float alpha) {
        art(g, alpha, () -> g.blit(ART, x, y, 10 * which, ICON_V, ICON, ICON));
    }

    /** The gear shown's name between two silver flourishes, and what to do under it (or the lock while in combat). */
    private void header(GuiGraphics g) {
        int f = RuneTableView.picked;
        ItemStack gear = menu.gear(f);
        Piece piece = RuneTableMenu.piece(f);
        Component name = !gear.isEmpty() ? gear.getHoverName()
                : piece != null ? Component.literal(piece.title) : Component.translatable("screen.explorationremix.rune_table.hand");
        int cx = width / 2, nw = font.width(name);
        int left = cx - nw / 2 - 6 - FLOURISH_W, right = cx + (nw + 1) / 2 + 6;
        // the flourishes only where they fit, clear of the column and the tally
        if (left >= BOX_X + BOX + 8 && right + FLOURISH_W <= width - TALLY_RIGHT - TALLY_W - 4) {
            art(g, 1.0F, () -> {
                g.blit(ART, left, 7, 0, FLOURISH_V, FLOURISH_W, FLOURISH_H);
                g.blit(ART, right, 7, FLOURISH_RIGHT_U, FLOURISH_V, FLOURISH_W, FLOURISH_H);
            });
        }
        g.drawCenteredString(font, name, cx, 8, a(TEXT));
        Component how;
        if (menu.locked()) how = Component.translatable("screen.explorationremix.rune_table.locked", (menu.lockTicks() + 19) / 20);
        else if (gear.isEmpty()) how = nothingThere(f);
        else how = Component.translatable(RuneTableView.chosen != null ? "screen.explorationremix.rune_table.how_chosen" : "screen.explorationremix.rune_table.how");
        g.drawCenteredString(font, how, cx, 19, a(menu.locked() ? 0xFFB7C8F0 : DIM));
    }

    /** What to do when gear {@code f} is not there: hold a weapon or tool, or wear the piece. */
    private static Component nothingThere(int f) {
        Piece piece = RuneTableMenu.piece(f);
        return piece == null ? Component.translatable("screen.explorationremix.rune_table.nothing_held")
                : Component.translatable("screen.explorationremix.rune_table.nothing_worn", piece.withArticle());
    }

    /** Where box {@code g} of the column sits (its top). */
    private static int boxY(int g) { return BOX_TOP + g * (BOX + BOX_GAP); }

    /**
     * The column of gear at the left: a gem-cornered box for the hand and for each piece of armor, the one shown lit, the
     * gear itself in it (or the slot's outline, faint), and beside it a dot for each socket, lit in its colour while it
     * holds a rune.
     */
    private void column(GuiGraphics g) {
        for (int k = 0; k < RuneTableMenu.GEAR; k++) {
            int x = BOX_X, y = boxY(k);
            boolean lit = k == RuneTableView.picked || k == hoveredGear;
            int u = lit ? BUTTON_LIT_U : BUTTON_U;
            art(g, k == RuneTableView.picked ? 1.0F : 0.8F, () -> g.blitNineSliced(ART, x, y, BOX, BOX, BUTTON_CORNER, BUTTON_SIZE, BUTTON_SIZE, u, 0));
            ItemStack gear = menu.gear(k);
            if (gear.isEmpty()) {
                int which = k;
                art(g, 0.35F, () -> g.blit(EMPTY[which], x + 3, y + 3, 0, 0, 16, 16, 16, 16));
                continue;
            }
            if (fade > 0.6F) {
                g.renderItem(gear, x + 3, y + 3);
                g.renderItemDecorations(font, gear, x + 3, y + 3);
            }
            Socket[] sockets = menu.sockets(k);
            for (int i = 0; i < sockets.length; i++) {
                boolean in = menu.rune(k, sockets[i]) != null;
                int dy = y + BOX / 2 - 1 + (i - (sockets.length - 1) / 2) * 5 - (sockets.length % 2 == 0 ? 2 : 0);
                int c = in ? 0xFF000000 | RuneTableView.colour(sockets[i]) : 0xFF2A3246;
                g.fill(x + BOX + 2, dy, x + BOX + 4, dy + 2, a(c));
            }
        }
    }

    /** The sets at the right: each collect rune worn, how many pieces carry it (a pip for each piece), lit once awake. */
    private void tally(GuiGraphics g, ArmorSet set) {
        List<RuneId> sets = set.collects();
        if (sets.isEmpty()) return;
        int x1 = width - TALLY_RIGHT, x0 = x1 - TALLY_W;
        icon(g, RuneTableView.iconIndex(Socket.COLLECT_1), x0, TALLY_TOP - 1, 1.0F);
        g.drawString(font, words("sets"), x0 + ICON + 3, TALLY_TOP, a(DIM), false);
        for (int i = 0; i < sets.size(); i++) {
            RuneId c = sets.get(i);
            int y = TALLY_TOP + (i + 1) * TALLY_ROW + 2, count = set.count(c);
            boolean hover = c == hoveredSet;
            int colour = set.full(c) ? BRIGHT : set.awake(c) ? SET_AWAKE : hover ? TEXT : DIM;
            g.drawString(font, c.title, x0, y, a(colour), false);
            for (int p = 0; p < ArmorSet.FULL; p++) {
                int px = x1 - (ArmorSet.FULL - p) * 5;
                g.fill(px, y + 2, px + 3, y + 5, a(p < count ? SET_LIT : SET_DARK));
            }
        }
    }

    /**
     * Under each socket, two lines: its kind's icon and name, in its colour, and the rune in it ("II" once the active's
     * second phase wakes; a collect rune bright once its set is awake).
     */
    private void socketLabel(GuiGraphics g, RuneTableView.Target t, @Nullable Reading now, ArmorSet set) {
        if (!t.onScreen) return;
        int x = Math.round(t.sx), y = Math.round(t.sy + t.sr + 3);
        Component kind = Component.translatable("rune.explorationremix.kind." + t.socket.kind.word);
        int kx = x - (ICON + 2 + font.width(kind)) / 2;
        icon(g, RuneTableView.iconIndex(t.socket), kx, y - 1, 1.0F);
        g.drawString(font, kind, kx + ICON + 2, y, a(0xFF000000 | RuneTableView.colour(t.socket)), true);
        Component name;
        int colour;
        if (t.rune == null) {
            name = Component.translatable("screen.explorationremix.rune_table.empty");
            colour = FAINT;
        } else if (t.socket == Socket.ACTIVE && now != null && now.empowered()) {
            name = Component.literal(t.rune.title + " II");
            colour = AWAKE;
        } else if (t.socket.kind == RuneKind.COLLECT) {
            name = Component.literal(t.rune.title + " " + set.count(t.rune) + "/" + ArmorSet.FULL);
            colour = set.full(t.rune) ? AWAKE : set.awake(t.rune) ? TEXT : DIM;
        } else {
            name = Component.literal(t.rune.title);
            colour = now != null && t.socket.kind.weapon() && now.note(t.socket).grey() ? DIM : TEXT;
        }
        g.drawCenteredString(font, name, x, y + 10, a(colour));
    }

    /** With a socket chosen and nothing to raise for it: says so, over the gear. */
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
     * is now, in case a rune just went in); then nothing, and the panel says what the gear does.
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
        String key = hoveredGear >= 0 ? "gear:" + hoveredGear : hoveredSet != null ? "set:" + hoveredSet
                : said == null ? "focus:" + RuneTableView.picked : said.kind + ":" + said.socket + ":" + said.rune;
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
    private void panel(GuiGraphics g, Say say, float time) {
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

    // ------------------------------------------------------------------------------------------------ what the panel says

    /** What the panel says now: of the box or set the mouse is over, else of {@code t} on the gear shown. */
    private Say say(@Nullable RuneTableView.Target t, @Nullable Reading now, ArmorSet set, int level) {
        if (hoveredGear >= 0) return sayGear(hoveredGear, set, level);
        if (hoveredSet != null) return saySet(hoveredSet, set);
        int f = RuneTableView.picked;
        ItemStack gear = menu.gear(f);
        if (gear.isEmpty()) return new Say(words("what_it_does"), TEXT, NO_ICON, List.of(new Line(nothingThere(f).getString(), DIM, NO_ICON)));
        if (f != RuneTableMenu.HAND) return sayArmor(t, RuneTableMenu.piece(f), set);
        if (menu.tool()) return sayTool(t, menu.rune(f, Socket.TOOL));
        HostSpec spec = RuneGear.spec(gear);
        if (now == null || spec == null) return new Say(words("what_it_does"), TEXT, NO_ICON, List.of());
        return sayWeapon(t, now, spec, level);
    }

    /**
     * Of the weapon. Over nothing, what it does: the cast, the passive and the second phase, each by its icon. A socket:
     * its rune and what it does, the active's second phase, and the clicks; or, empty, what goes in it. A risen rune:
     * what it is, and the lines of what the weapon does that it would change; the struck one, what taking it out would
     * change.
     */
    private Say sayWeapon(@Nullable RuneTableView.Target t, Reading now, HostSpec spec, int level) {
        List<Line> out = new ArrayList<>();
        if (t == null) {
            if (menu.loadout().isEmpty()) {
                out.add(new Line(words("none_set"), TEXT, NO_ICON));
                out.add(new Line(words("three"), DIM, NO_ICON));
            } else {
                out.add(new Line(Explain.castLine(now), TEXT, RuneTableView.iconIndex(Socket.ACTIVE)));
                String modifier = Explain.note(now, Socket.MODIFIER);
                if (!modifier.isEmpty()) out.add(new Line(modifier + ".", DIM, RuneTableView.iconIndex(Socket.MODIFIER)));
                String passive = Explain.passiveLine(now);
                if (!passive.isEmpty()) out.add(new Line(passive, DIM, RuneTableView.iconIndex(Socket.PASSIVE)));
                String phase = Explain.phaseLine(now);
                if (!phase.isEmpty()) out.add(new Line(phase, now.empowered() ? AWAKE : DIM, PHASE_ICON));
            }
            return new Say(words("what_it_does"), TEXT, NO_ICON, out);
        }
        int kind = 0xFF000000 | RuneTableView.colour(t.socket), icon = RuneTableView.iconIndex(t.socket);
        return switch (t.kind) {
            case SOCKET -> {
                if (t.rune == null) yield emptySocket(t, kind, icon);
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
                yield new Say(t.rune.title + (t.count > 1 ? "  ×" + t.count : ""), kind, icon, out);
            }
            case TAKE_OUT -> {
                out.add(new Line(words("back_to_you"), DIM, NO_ICON));
                Reading without = Reader.read(menu.loadout().with(t.socket, null), spec, level, RuneConfig.limits());
                differences(out, now, without, words("without_it"));
                yield new Say(Component.translatable("screen.explorationremix.rune_table.take_out", t.rune.title).getString(), kind, icon, out);
            }
        };
    }

    /**
     * Of a piece of armor. Over nothing, what its runes do: the effect rune, and how far each collect rune's set has
     * come. A socket: its rune and what it does (a collect rune, how many pieces carry it); a risen rune, what it is and,
     * for a collect rune, how far its set would come; the struck one, how far its set would fall back.
     */
    private Say sayArmor(@Nullable RuneTableView.Target t, Piece piece, ArmorSet set) {
        List<Line> out = new ArrayList<>();
        ArmorRunes a = set.piece(piece) == null ? ArmorRunes.EMPTY : set.piece(piece);
        int effect = RuneTableView.iconIndex(Socket.EFFECT), collect = RuneTableView.iconIndex(Socket.COLLECT_1);
        if (t == null) {
            if (a.isEmpty()) {
                out.add(new Line(words("none_set_armor"), TEXT, NO_ICON));
                out.add(new Line(words("armor_three"), DIM, NO_ICON));
            } else {
                if (a.effect() != null) out.add(new Line(a.effect().title + ": " + desc(a.effect()), TEXT, effect));
                else out.add(new Line(words("no_effect"), DIM, effect));
                for (RuneId c : new RuneId[]{a.first(), a.second()}) {
                    if (c != null) out.add(new Line(Explain.collectLine(c, set.count(c)), set.awake(c) ? TEXT : DIM, collect));
                }
            }
            return new Say(words("what_it_does"), TEXT, NO_ICON, out);
        }
        int kind = 0xFF000000 | RuneTableView.colour(t.socket), icon = RuneTableView.iconIndex(t.socket);
        return switch (t.kind) {
            case SOCKET -> {
                if (t.rune == null) yield emptySocket(t, kind, icon);
                out.add(new Line(desc(t.rune), TEXT, NO_ICON));
                if (t.rune.isCollect()) out.add(new Line(Explain.collectLine(t.rune, set.count(t.rune)), set.awake(t.rune) ? TEXT : DIM, collect));
                out.add(new Line(words("click_change"), FAINT, NO_ICON));
                yield new Say(t.rune.title, kind, icon, out);
            }
            case RUNE -> {
                out.add(new Line(desc(t.rune), TEXT, NO_ICON));
                if (t.rune.isCollect()) {
                    ArmorSet with = withRunes(set, piece, a.with(t.socket, t.rune));
                    out.add(new Line(Explain.collectLine(t.rune, with.count(t.rune)), DIM, collect, words("with_it")));
                    RuneId was = a.get(t.socket);
                    if (was != null && was != t.rune) out.add(new Line(Explain.collectLine(was, with.count(was)), DIM, collect));
                }
                yield new Say(t.rune.title + (t.count > 1 ? "  ×" + t.count : ""), kind, icon, out);
            }
            case TAKE_OUT -> {
                out.add(new Line(words("back_to_you"), DIM, NO_ICON));
                if (t.rune.isCollect()) {
                    ArmorSet without = withRunes(set, piece, a.with(t.socket, null));
                    out.add(new Line(Explain.collectLine(t.rune, without.count(t.rune)), DIM, collect, words("without_it")));
                }
                yield new Say(Component.translatable("screen.explorationremix.rune_table.take_out", t.rune.title).getString(), kind, icon, out);
            }
        };
    }

    /** The set as it would be with {@code a} on {@code piece} instead. */
    private static ArmorSet withRunes(ArmorSet set, Piece piece, ArmorRunes a) {
        ArmorRunes[] pieces = new ArmorRunes[Piece.values().length];
        for (Piece p : Piece.values()) pieces[p.ordinal()] = set.piece(p);
        pieces[piece.ordinal()] = a;
        return new ArmorSet(pieces);
    }

    /** Of a tool: its one rune, or what goes in its socket. */
    private Say sayTool(@Nullable RuneTableView.Target t, @Nullable RuneId in) {
        List<Line> out = new ArrayList<>();
        int icon = RuneTableView.iconIndex(Socket.TOOL), kind = 0xFF000000 | RuneTableView.colour(Socket.TOOL);
        if (t == null) {
            if (in == null) {
                out.add(new Line(words("none_set_tool"), TEXT, NO_ICON));
                out.add(new Line(words("about.tool"), DIM, NO_ICON));
            } else {
                out.add(new Line(in.title + ": " + desc(in), TEXT, icon));
            }
            return new Say(words("what_it_does"), TEXT, NO_ICON, out);
        }
        return switch (t.kind) {
            case SOCKET -> {
                if (t.rune == null) yield emptySocket(t, kind, icon);
                out.add(new Line(desc(t.rune), TEXT, NO_ICON));
                out.add(new Line(words("click_change"), FAINT, NO_ICON));
                yield new Say(t.rune.title, kind, icon, out);
            }
            case RUNE -> {
                out.add(new Line(desc(t.rune), TEXT, NO_ICON));
                yield new Say(t.rune.title + (t.count > 1 ? "  ×" + t.count : ""), kind, icon, out);
            }
            case TAKE_OUT -> {
                out.add(new Line(words("back_to_you"), DIM, NO_ICON));
                yield new Say(Component.translatable("screen.explorationremix.rune_table.take_out", t.rune.title).getString(), kind, icon, out);
            }
        };
    }

    /** An empty socket: what goes in it, and whether the player carries any. */
    private Say emptySocket(RuneTableView.Target t, int kind, int icon) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(words("about." + t.socket.kind.word), TEXT, NO_ICON));
        boolean carries = !menu.carried(RuneTableView.picked, t.socket).isEmpty();
        out.add(new Line(Component.translatable(carries ? "screen.explorationremix.rune_table.click_choose"
                : "screen.explorationremix.rune_table.none_carried", t.socket.kind.word).getString(), DIM, NO_ICON));
        return new Say(Component.translatable("screen.explorationremix.rune_table.socket_empty",
                Component.translatable("rune.explorationremix.kind." + t.socket.kind.word)).getString(), kind, icon, out);
    }

    /** Of a box in the column: what that gear's runes are, and the click that shows them. */
    private Say sayGear(int k, ArmorSet set, int level) {
        List<Line> out = new ArrayList<>();
        ItemStack gear = menu.gear(k);
        if (gear.isEmpty()) {
            out.add(new Line(nothingThere(k).getString(), DIM, NO_ICON));
            Piece p = RuneTableMenu.piece(k);
            return new Say(p == null ? words("hand") : p.title, TEXT, NO_ICON, out);
        }
        List<String> names = new ArrayList<>();
        for (Socket s : menu.sockets(k)) {
            RuneId r = menu.rune(k, s);
            if (r != null) names.add(r.title);
        }
        out.add(new Line(names.isEmpty() ? words("no_runes") : String.join(", ", names), names.isEmpty() ? DIM : TEXT, NO_ICON));
        if (k != RuneTableView.picked) out.add(new Line(words("click_gear"), FAINT, NO_ICON));
        return new Say(gear.getHoverName().getString(), TEXT, NO_ICON, out);
    }

    /** Of a set in the tally: how far it has come, and what it does awake and full. */
    private Say saySet(RuneId c, ArmorSet set) {
        List<Line> out = new ArrayList<>();
        int collect = RuneTableView.iconIndex(Socket.COLLECT_1);
        out.add(new Line(Explain.collectLine(c, set.count(c)), set.awake(c) ? TEXT : DIM, NO_ICON));
        List<String> on = new ArrayList<>();
        for (Piece p : Piece.values()) {
            ArmorRunes a = set.piece(p);
            if (a != null && a.collects(c)) on.add(p.word);
        }
        if (!on.isEmpty()) out.add(new Line(Component.translatable("screen.explorationremix.rune_table.set_on", String.join(", ", on)).getString(), FAINT, NO_ICON));
        return new Say(Explain.tally(c, set.count(c)), set.awake(c) ? SET_AWAKE : DIM, collect, out);
    }

    /** The lines of what the weapon does that {@code after} would change from {@code before}, each by its icon, the first led by {@code lead}. */
    private static void differences(List<Line> out, Reading before, Reading after, String lead) {
        String[] was = {Explain.castLine(before), Explain.passiveLine(before), Explain.phaseLine(before)};
        String[] will = {Explain.castLine(after), Explain.passiveLine(after), Explain.phaseLine(after)};
        int[] icons = {RuneTableView.iconIndex(Socket.ACTIVE), RuneTableView.iconIndex(Socket.PASSIVE), PHASE_ICON};
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

    /** Which box of the column, or which set of the tally, the mouse is over. */
    private void hoverSides(int mouseX, int mouseY, ArmorSet set) {
        int was = hoveredGear;
        hoveredGear = -1;
        hoveredSet = null;
        if (devHover != null) {
            if (devHover.startsWith("gear ")) hoveredGear = gearNamed(devHover.substring(5));
            return;
        }
        if (mouseX >= BOX_X && mouseX < BOX_X + BOX) {
            for (int k = 0; k < RuneTableMenu.GEAR; k++) {
                if (mouseY >= boxY(k) && mouseY < boxY(k) + BOX) hoveredGear = k;
            }
        }
        if (hoveredGear >= 0 && hoveredGear != was) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_CLUSTER_STEP, 1.5F, 0.2F));
        List<RuneId> sets = set.collects();
        int x1 = width - TALLY_RIGHT, x0 = x1 - TALLY_W;
        if (mouseX >= x0 && mouseX < x1) {
            for (int i = 0; i < sets.size(); i++) {
                int y = TALLY_TOP + (i + 1) * TALLY_ROW + 2;
                if (mouseY >= y - 2 && mouseY < y + TALLY_ROW - 2) hoveredSet = sets.get(i);
            }
        }
    }

    /** What the mouse is over in the world, of what was drawn last: the nearest within its reach (risen runes before sockets). */
    private void hover(int mouseX, int mouseY) {
        RuneTableView.Target best = null;
        double bestD = Double.MAX_VALUE;
        if (hoveredGear < 0 && hoveredSet == null) {
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
        }
        if (best != null && !best.same(RuneTableView.hovered) && best.kind != RuneTableView.Target.Kind.SOCKET) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_CLUSTER_STEP, 1.7F, 0.25F));
        }
        RuneTableView.hovered = best;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hoveredGear >= 0) {
            if (button == 0) pick(hoveredGear);
            return true;
        }
        if (hoveredSet != null) return true;
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
                    if (t.rune != null && !locked) edit(t.socket, null);
                } else if (RuneTableView.chosen == t.socket) {
                    RuneTableView.chosen = null;
                    chime(0.8F);
                } else {
                    RuneTableView.chosen = t.socket;
                    RuneTableView.chosenAt = RuneTableView.ticks();
                    chime(1.2F + 0.15F * t.socket.pos);
                }
            }
            case RUNE -> {
                if (!locked) edit(t.socket, t.rune);
            }
            case TAKE_OUT -> {
                if (!locked) edit(t.socket, null);
            }
        }
        return true;
    }

    /** Shows gear {@code k}'s sockets over the book. */
    private void pick(int k) {
        if (k == RuneTableView.picked) return;
        RuneTableView.pick(k);
        chime(1.0F + 0.12F * k);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) { return true; }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) { return true; }

    /** Asks the server to set {@code r} (null: take out what is there) in socket {@code s} of the gear shown. */
    private void edit(Socket s, @Nullable RuneId r) {
        RuneNet.CHANNEL.sendToServer(new RuneNet.TableEdit(menu.containerId, RuneTableView.picked, s.ordinal(), r == null ? -1 : r.ordinal()));
    }

    private void chime(float pitch) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, pitch, 0.5F));
    }

    /** The server's change arrived: the socket flashes, and the risen runes sink back once one is set. */
    private void changes() {
        int f = RuneTableView.picked;
        ItemStack gear = menu.gear(f);
        Socket[] sockets = menu.sockets(f);
        RuneId[] now = new RuneId[seen.length];
        for (Socket s : sockets) now[s.pos] = menu.rune(f, s);
        if (f != seenFocus || gear.getItem() != seenGear.getItem()) {
            System.arraycopy(now, 0, seen, 0, seen.length);
            seenFocus = f;
            seenGear = gear;
            return;
        }
        for (Socket s : sockets) {
            if (now[s.pos] != seen[s.pos]) {
                RuneTableView.burst(s);
                if (RuneTableView.chosen == s) RuneTableView.chosen = null;
            }
        }
        System.arraycopy(now, 0, seen, 0, seen.length);
    }

    // ------------------------------------------------------------------------------------------------ dev

    /** Whether the target goes by this name: a socket's ("active", "collect_2"), a risen rune's id, or "out" for taking one out. */
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

    /** A box of the column by its number (0 the hand to 4 the boots) or name ("hand", "helmet", "boots"); -1 if none. */
    private static int gearNamed(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("hand")) return RuneTableMenu.HAND;
        for (Piece p : Piece.values()) if (n.equals(p.name().toLowerCase(Locale.ROOT)) || n.equals(p.word)) return RuneTableMenu.gearOf(p);
        try {
            int k = Integer.parseInt(n);
            return k >= 0 && k < RuneTableMenu.GEAR ? k : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The shot driver's steps at an open table: "gear hand|helmet|..|boots" shows that gear's sockets, "choose socket"
     * raises the runes carried for it, "hover name|gear name|none" holds the mouse over a socket, a risen rune, "out" or a
     * box of the column, "set rune[@socket]" and "out socket" press as a click would. False if the table is not open or
     * the step is unknown.
     */
    public static boolean dev(String what, String arg) {
        if (!(net.minecraft.client.Minecraft.getInstance().screen instanceof RuneTableScreen screen)) return false;
        switch (what) {
            case "gear" -> {
                int k = gearNamed(arg);
                if (k < 0) return false;
                screen.pick(k);
            }
            case "choose" -> {
                Socket k = socketNamed(arg);
                if (k == null) return false;
                RuneTableView.chosen = k;
                RuneTableView.chosenAt = RuneTableView.ticks();
            }
            case "hover" -> devHover = arg.equals("none") || arg.isEmpty() ? null : arg;
            case "set" -> {
                String[] parts = arg.split("@", 2);
                RuneId r = RuneId.byId(parts[0]);
                if (r == null) return false;
                Socket k = parts.length > 1 ? socketNamed(parts[1]) : Socket.of(r.kind);
                if (k == null) return false;
                screen.edit(k, r);
            }
            case "out" -> {
                Socket k = socketNamed(arg);
                if (k == null) return false;
                screen.edit(k, null);
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
