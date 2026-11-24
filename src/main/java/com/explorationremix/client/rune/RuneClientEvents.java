package com.explorationremix.client.rune;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.cast.LocalPull;
import com.explorationremix.rune.cast.Pull;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Note;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.Socket;
import com.explorationremix.rune.item.RuneItem;
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
 * The runes' client game events, on the Forge bus: the cast key tells the server when it goes down and up, Reel's pull
 * is steered, and weapon tooltips show the sockets and what they do.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RuneClientEvents {
    /** The widest a tooltip line about what the runes do gets (GUI pixels) before it wraps. */
    private static final int TOOLTIP_WIDTH = 200;
    /** Whether this game has told the server the cast key is down (and so owes it word when it comes up). */
    private static boolean castDown;

    private RuneClientEvents() {}

    // ------------------------------------------------------------------------------------------------ the keys

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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
     * Weapons only, right under the name: the three sockets' runes in their colours (one that does nothing dimmed, a
     * cast dearer than the weapon holds in red), what they do in the reading's own words (the second phase's line with
     * the player's own XP level), and the weapon's energy. A weapon with no runes says where they are set.
     */
    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        HostSpec spec = RuneGear.spec(stack);
        // no player: the game is filling its search index, not showing a tooltip
        if (spec == null || event.getEntity() == null) return;
        Minecraft mc = Minecraft.getInstance();
        List<Component> add = new ArrayList<>();
        if (RuneGear.hasRunes(stack)) {
            Reading r = RuneGear.reading(stack, event.getEntity().experienceLevel);
            if (r != null) {
                add.add(sockets(r));
                for (String line : Explain.lines(r)) wrap(add, mc.font, line);
            }
            Level level = mc.level;
            if (level != null) {
                long now = level.getGameTime();
                float energy = CastMachine.energyAt(RuneGear.state(stack, spec, now), spec, now);
                add.add(Component.literal("Energy " + RuneLook.whole(energy) + " / " + Math.round(spec.maxEnergy()))
                        .withStyle(s -> s.withColor(0x8EC2FF)));
            }
        } else {
            add.add(Component.translatable("rune.explorationremix.no_runes").withStyle(ChatFormatting.DARK_GRAY));
        }
        List<Component> tip = event.getToolTip();
        tip.addAll(Math.min(1, tip.size()), add);
    }

    /** "Spark · Swift · Ember", each in its socket's colour; an empty socket's place left out. */
    private static Component sockets(Reading r) {
        MutableComponent out = Component.empty();
        boolean any = false;
        CastReading c = r.cast();
        for (Socket s : Socket.values()) {
            RuneId rune = r.loadout().get(s);
            if (rune == null) continue;
            if (any) out.append(Component.literal(" \u00b7 ").withStyle(ChatFormatting.DARK_GRAY));
            Note n = r.note(s);
            int colour = !n.live() ? 0x5F6E87 : s == Socket.ACTIVE && c != null && c.red() ? 0xFF5555 : RuneItem.kindColour(s.kind);
            String name = rune.title + (s == Socket.ACTIVE && r.empowered() ? " II" : "");
            out.append(Component.literal(name).withStyle(st -> st.withColor(colour)));
            any = true;
        }
        return out;
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
