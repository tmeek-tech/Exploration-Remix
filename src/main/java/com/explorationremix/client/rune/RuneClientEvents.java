package com.explorationremix.client.rune;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.cast.LocalPull;
import com.explorationremix.rune.cast.Pull;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Note;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.Row;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.net.RuneNet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The runes' client game events, on the Forge bus: the cast key tells the server when it goes down and up, the rune
 * screen key asks the server for the rune screen, Reel's pull is steered, and gear tooltips show the row and what it
 * does.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RuneClientEvents {
    /** Ticks before the key asks again, so one impatient double press on a slow server does not open the screen twice. */
    private static final int KEY_COOLDOWN = 10;
    /** The widest a tooltip line about what the row does gets (GUI pixels) before it wraps. */
    private static final int TOOLTIP_WIDTH = 200;
    private static int keyCooldown;
    /** Whether this game has told the server the cast key is down (and so owes it word when it comes up). */
    private static boolean castDown;

    private RuneClientEvents() {}

    // ------------------------------------------------------------------------------------------------ the keys

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (keyCooldown > 0) keyCooldown--;
        Minecraft mc = Minecraft.getInstance();
        boolean inGame = mc.screen == null && mc.player != null && mc.getConnection() != null;
        // the cast key: the server casts when it goes down and again each time the sword is ready until it comes up (a
        // tap pressed and let go within one tick still casts once); a screen opening lets go of it
        boolean cast = false;
        while (RuneKeys.CAST.consumeClick()) cast = true;
        if (cast && inGame && !castDown) {
            castDown = true;
            RuneNet.CHANNEL.sendToServer(new RuneNet.CastKey(true));
        }
        if (castDown && (!RuneKeys.CAST.isDown() || !inGame)) {
            castDown = false;
            if (mc.getConnection() != null) RuneNet.CHANNEL.sendToServer(new RuneNet.CastKey(false));
        }
        boolean pressed = false;
        while (RuneKeys.OPEN.consumeClick()) pressed = true;
        if (pressed && keyCooldown == 0 && inGame) {
            // the server opens it, or says why not (in combat)
            RuneNet.CHANNEL.sendToServer(new RuneNet.OpenEditor());
            keyCooldown = KEY_COOLDOWN;
        }
    }

    // ------------------------------------------------------------------------------------------------ Reel's pull

    /** The pull this game is steering, and what it noted when it took it up: the level, and whether sneak was held. */
    private static int steering = -1;
    @Nullable
    private static Level steerLevel;
    private static boolean steerSneak;

    /**
     * Reel's pull on this game's player, steered at the start of each of their ticks, before they move: from where they
     * really are, so a slow link or a stuttering game can't make it shoot past or let go early ({@link Pull.Steer}).
     * A fresh sneak lets go, as does anything that would end a pull on the server; done, it tells the server.
     */
    @SubscribeEvent
    public static void steer(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || event.side != LogicalSide.CLIENT) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || event.player != p) return;
        Pull.Steer s = LocalPull.current();
        if (s == null) {
            steering = -1;
            return;
        }
        boolean sneak = p.isShiftKeyDown();
        if (s.id != steering) {
            steering = s.id;
            steerLevel = p.level();
            steerSneak = sneak;
        }
        boolean pressed = sneak && !steerSneak;
        steerSneak = sneak;
        if (pressed || p.level() != steerLevel || !p.isAlive() || p.isSpectator() || p.isPassenger() || p.isFallFlying() || p.isSleeping()) {
            pullDone();
            return;
        }
        double[] v = s.tick(p.getX(), p.getY(), p.getZ());
        if (v != null) p.setDeltaMovement(v[0], v[1], v[2]);
        if (s.over()) pullDone();
    }

    private static void pullDone() {
        int id = LocalPull.finish();
        steering = -1;
        steerLevel = null;
        if (id >= 0 && Minecraft.getInstance().getConnection() != null) RuneNet.CHANNEL.sendToServer(new RuneNet.PullDone(id));
    }

    /** Leaving a world drops any pull this game was steering. */
    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        castDown = false;
        LocalPull.finish();
        steering = -1;
        steerLevel = null;
    }

    // ------------------------------------------------------------------------------------------------ tooltips

    /**
     * Gear only, right under its name: the runes in row order ("Spark, On hit, Burst | Orb", grey and asleep ones
     * dimmed, a dear cast's in red), what the row does in the reading's own words, and its energy. Gear with no runes
     * says how many slots it has awake and which key opens the rune screen.
     */
    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        HostSpec spec = RuneGear.spec(stack);
        // no player: the game is filling its search index, not showing a tooltip
        if (spec == null || event.getEntity() == null) return;
        Minecraft mc = Minecraft.getInstance();
        int depth = RunDepth.clientDepth();
        List<Component> add = new ArrayList<>();
        if (RuneGear.hasRunes(stack)) {
            Reading r = RuneGear.reading(stack, depth);
            if (r != null) {
                Component runes = runeList(r);
                if (!runes.getSiblings().isEmpty()) add.add(runes);
                for (String line : Explain.lines(r)) wrap(add, mc.font, line);
            }
            Level level = mc.level;
            if (level != null) {
                long now = level.getGameTime();
                float energy = CastMachine.energyAt(RuneGear.state(stack, spec, now), spec, now);
                add.add(Component.literal("Energy " + RuneLook.whole(energy) + " / " + Math.round(spec.maxEnergy()))
                        .withStyle(ChatFormatting.DARK_AQUA));
            }
        } else {
            int n = spec.awakeSlots(depth);
            MutableComponent line = Component.literal(n + (n == 1 ? " rune slot" : " rune slots"));
            if (RuneKeys.OPEN.isUnbound()) line.append(" (set the rune screen key in Controls)");
            else line.append(" (press ").append(RuneKeys.OPEN.getTranslatedKeyMessage()).append(" to open the rune screen)");
            add.add(line.withStyle(ChatFormatting.DARK_GRAY));
        }
        List<Component> tip = event.getToolTip();
        tip.addAll(Math.min(1, tip.size()), add);
    }

    /** The row's runes left to right, ", " between them and " | " where a break starts a new cast. */
    private static Component runeList(Reading r) {
        MutableComponent out = Component.empty();
        Row row = r.row();
        boolean any = false, cut = false;
        for (int i = 0; i < row.size(); i++) {
            RuneId rune = row.get(i);
            if (rune != null) {
                if (any) out.append(Component.literal(cut ? " | " : ", ").withStyle(cut ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY));
                out.append(Component.literal(rune.title).withStyle(runeStyle(r, i)));
                any = true;
                cut = false;
            }
            // a break after an empty slot still parts the runes either side of it
            if (row.breakAfter(i)) cut = true;
        }
        return out;
    }

    private static ChatFormatting runeStyle(Reading r, int slot) {
        Note n = r.note(slot);
        if (!n.live()) return ChatFormatting.DARK_GRAY;
        CastReading c = r.cast(n.cast());
        return c != null && c.red() ? ChatFormatting.RED : ChatFormatting.WHITE;
    }

    /** One line of the reading, grey, wrapped to {@link #TOOLTIP_WIDTH}; the lines it wraps onto are indented. */
    private static void wrap(List<Component> out, Font font, String line) {
        List<FormattedText> parts = font.getSplitter().splitLines(line, TOOLTIP_WIDTH, Style.EMPTY);
        for (int k = 0; k < parts.size(); k++) {
            String s = parts.get(k).getString();
            out.add(Component.literal(k == 0 ? s : "  " + s).withStyle(ChatFormatting.GRAY));
        }
    }
}
