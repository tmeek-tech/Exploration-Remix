package com.explorationremix.rune.cast;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.Rune;
import com.explorationremix.rune.RuneGrammar;
import com.explorationremix.rune.RuneTool;
import com.explorationremix.run.RunManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fires triggers. A trigger runs every sentence it starts on the tool, each on its own cooldown (the sum of its runes'
 * costs). Effects run inside a casting guard, so nothing an effect does can fire another trigger: no loops.
 */
public final class RuneCaster {
    private static final ThreadLocal<Integer> CASTING = ThreadLocal.withInitial(() -> 0);
    /** toolId#sentence -> game time the sentence is ready again */
    private static final Map<String, Long> COOLDOWNS = new HashMap<>();
    private static final Deque<Delayed> DELAYED = new ArrayDeque<>();

    private record Delayed(long due, Runnable run) {}

    private RuneCaster() {}

    public static boolean isCasting() { return CASTING.get() > 0; }

    /** Fires a trigger for both hands' tools. */
    public static void fire(ServerPlayer p, String triggerId, TriggerContext ctx) {
        if (isCasting() || p.isSpectator()) return;
        long run = RunManager.currentRun(p);
        if (run == 0L) return;
        fireTool(p, p.getItemInHand(InteractionHand.MAIN_HAND), run, triggerId, ctx);
        fireTool(p, p.getItemInHand(InteractionHand.OFF_HAND), run, triggerId, ctx);
    }

    /** Fires a trigger for one tool only (the weapon that hit, for example). */
    public static void fireTool(ServerPlayer p, ItemStack tool, String triggerId, TriggerContext ctx) {
        if (isCasting() || p.isSpectator()) return;
        long run = RunManager.currentRun(p);
        if (run == 0L) return;
        fireTool(p, tool, run, triggerId, ctx);
    }

    private static void fireTool(ServerPlayer p, ItemStack tool, long run, String triggerId, TriggerContext ctx) {
        if (!RuneTool.canHoldRunes(tool)) return;
        List<Rune> live = RuneTool.liveRunes(tool, run);
        if (live.isEmpty()) return;
        RuneGrammar.Parsed parsed = RuneGrammar.parse(live);
        if (parsed.sentences().isEmpty()) return;
        UUID toolId = RuneTool.toolId(tool);
        long now = p.level().getGameTime();
        for (RuneGrammar.Sentence s : parsed.sentences()) {
            if (!s.trigger().id().getPath().equals(triggerId) || s.clauses().isEmpty()) continue;
            String key = toolId + "#" + s.index();
            long ready = COOLDOWNS.getOrDefault(key, 0L);
            if (now < ready) continue;
            COOLDOWNS.put(key, now + Math.max(1, s.cooldownTicks()));
            cast(p, tool, s, ctx);
        }
    }

    private static void cast(ServerPlayer p, ItemStack tool, RuneGrammar.Sentence s, TriggerContext ctx) {
        for (RuneGrammar.Clause c : s.clauses()) {
            EffectContext ec = new EffectContext(p, tool, s, c, ctx);
            if (ec.delayTicks > 0) {
                long due = p.level().getGameTime() + ec.delayTicks;
                DELAYED.add(new Delayed(due, () -> { if (p.isAlive() && !p.hasDisconnected()) runGuarded(ec); }));
            } else runGuarded(ec);
        }
    }

    private static void runGuarded(EffectContext ec) {
        CASTING.set(CASTING.get() + 1);
        try {
            for (int i = 0; i < ec.repeats; i++) RuneEffects.apply(ec);
        } catch (RuntimeException e) {
            ExplorationRemix.LOGGER.error("Rune {} failed", ec.effect.id(), e);
        } finally {
            CASTING.set(CASTING.get() - 1);
        }
    }

    /** Ticks remaining before a sentence on this tool fires again, for tooltips and the HUD. */
    public static int cooldownLeft(ItemStack tool, int sentence, long now) {
        if (tool.getTag() == null) return 0;
        Long ready = COOLDOWNS.get(RuneTool.toolId(tool) + "#" + sentence);
        return ready == null ? 0 : (int) Math.max(0, ready - now);
    }

    public static void clearCooldowns() { COOLDOWNS.clear(); DELAYED.clear(); }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || DELAYED.isEmpty()) return;
        long now = e.getServer().overworld().getGameTime();
        List<Delayed> due = new ArrayList<>();
        DELAYED.removeIf(d -> { if (d.due <= now) { due.add(d); return true; } return false; });
        for (Delayed d : due) d.run.run();
        if (e.getServer().getTickCount() % 1200 == 0 && COOLDOWNS.size() > 4096) COOLDOWNS.values().removeIf(t -> t < now);
    }
}
