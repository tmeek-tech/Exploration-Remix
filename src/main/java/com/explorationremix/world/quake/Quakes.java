package com.explorationremix.world.quake;

import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.lava.LavaCycle;
import com.explorationremix.world.lava.LavaFlood;
import com.explorationremix.world.lava.LavaNet;
import com.explorationremix.world.lava.NearChunks;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Basalt Galleries' quakes on the server (0.9.0): big quakes every few minutes, light tremors between them, and the
 * ground answering a climb, all on {@link QuakeSchedule}'s clock and the gates below; each one is told to the players
 * in or near the Galleries ({@link LavaNet.Quake}), whose clients play its shake, sound and dust. Not saved: the
 * schedule is drawn again when the server starts, and a quake running then is simply over.
 *
 * <p><b>Gates</b> (spec 3.1), every tick, after the flood and the reset ({@code LavaFlood.tick}), with the lava cycle on
 * or off:
 * <ol>
 *     <li>With {@code quakes.enabled} off, any running quake ends (its end is sent to the clients) and everything is
 *     forgotten; the schedule is drawn again when it comes back on.</li>
 *     <li>Nothing starts on its own while the lava clock is held ({@link LavaFlood#held}, which DevShots' setup does) or
 *     while a quake is held by command; big quakes keep out of the lava's warning and the first 30 seconds of its rise,
 *     and tremors then run silent (the warning is the layer's only clock). Commands still start quakes.</li>
 *     <li>The players in or near the Galleries ({@link NearChunks#players}) are the <b>watchers</b>, who are told of
 *     every quake; those of them not in spectator mode are the <b>actors</b>, who drive whatever changes the world. A
 *     quake due with no actor is skipped. {@code /remix quake spectators act} (not saved) makes spectators actors too,
 *     for the picture rig's camera, which is one; the eruptions read the same switch.</li>
 * </ol>
 *
 * <p><b>Messages.</b> A big quake is told to every watcher when it starts, felt in full. A tremor's and a climb answer's
 * message goes out once its find of the blocks to topple is done, with each player's {@code felt} flag (in full only
 * near something that topples); a climb answer only to watchers within {@link #CLIMB_RANGE} blocks of the block placed.
 * Players who come near while a quake runs are told it then, so their clients play the rest of it; a hold and a release
 * are told again to everyone told before; a quake that is stopped early sends its end to everyone in the overworld.
 */
public final class Quakes {
    private Quakes() {}

    /** How far from the block placed a climb answer is told and felt, in blocks. */
    static final int CLIMB_RANGE = 48;

    // ------------------------------------------------------------------------------------------- state of this server
    @Nullable private static QuakeSchedule schedule;
    /** The quakes under way: at most one big quake or tremor, and the climb answers. */
    private static final List<Event> RUNNING = new ArrayList<>();
    private static int nextId = 1;
    /** Whether spectators are actors ({@code /remix quake spectators}); not saved. */
    private static boolean spectatorsAct;
    /** The last quake that ended, for the status. */
    @Nullable private static Event last;
    /** The cycle as of the last tick, for the status. */
    @Nullable private static LavaCycle.State lastState;
    private static boolean lastCycleOn;
    @Nullable private static LavaCycle.Phase lastPhase;
    private static int climbsThisCycle;

    /** One quake under way. */
    static final class Event {
        final int kind, id, duration, seed;
        final float strength;
        final boolean silent;
        /** For a climb answer, the block placed (the find and the message are round it). */
        @Nullable final BlockPos origin;
        /** The game time it started at; a release moves it on by the time it was held. */
        long start;
        /** Ticks into it while held by command, or -1. */
        int held = -1;
        /** Its message has gone out (a tremor's and a climb answer's once its find is done). */
        boolean announced;
        /** The players told of it, with their felt flag; dropped when they leave, so they are told again when they come back. */
        final Map<UUID, Boolean> told = new HashMap<>();
        /** What it did, for the status. */
        int toppled, fell, givenBack, takenOut, lowered, debris, shattered;

        Event(int kind, int id, long start, int duration, float strength, int seed, boolean silent, @Nullable BlockPos origin) {
            this.kind = kind;
            this.id = id;
            this.start = start;
            this.duration = duration;
            this.strength = strength;
            this.seed = seed;
            this.silent = silent;
            this.origin = origin;
        }

        /** Ticks into it now. */
        int into(long now) { return held >= 0 ? held : (int) Math.min(Integer.MAX_VALUE, now - start); }

        LavaNet.Quake message(boolean felt) {
            return new LavaNet.Quake((byte) kind, id, start, duration, strength, seed, held, felt);
        }

        /** True for a watcher this quake is told to: every one, or for a climb answer those within {@link #CLIMB_RANGE}. */
        boolean reaches(ServerPlayer p) {
            return origin == null || p.distanceToSqr(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5) <= CLIMB_RANGE * CLIMB_RANGE;
        }
    }

    // ================================================================================================ the tick

    /** Every overworld tick, after the flood and the reset ({@code LavaFlood.tick}). */
    public static void tick(ServerLevel level, LavaFlood.Band band, LavaCycle.State state, long now) {
        ERConfig cfg = ERConfig.INSTANCE;
        // gate 1: turned off, every quake ends and everything is forgotten
        if (!cfg.quakesEnabled.get()) {
            if (schedule != null || !RUNNING.isEmpty()) stop();
            return;
        }
        boolean cycleOn = cfg.lavaCycleEnabled.get();
        lastState = state;
        lastCycleOn = cycleOn;
        if (state.phase() != lastPhase) {
            if (state.phase() == LavaCycle.Phase.CALM && lastPhase != null) climbsThisCycle = 0;
            lastPhase = state.phase();
        }
        List<ServerPlayer> watchers = NearChunks.players(level, band);
        List<ServerPlayer> actors = actors(watchers);
        for (Iterator<Event> it = RUNNING.iterator(); it.hasNext(); ) {
            Event e = it.next();
            if (!e.announced) {
                // a tremor's or a climb answer's message waits for its find; nothing finds placed blocks to topple yet,
                // so it goes out on the tick after the start with every felt flag false (the faint one in three)
                announce(e, watchers, false);
            } else {
                tellLate(e, watchers);
            }
            if (e.held < 0 && now - e.start >= e.duration) {
                finish(e);
                it.remove();
            }
        }
        // gate 2: nothing starts on its own while the lava clock or a quake is held; the quiet window of the warning
        QuakeSchedule.Settings s = settings(cfg);
        int quiet = QuakeSchedule.quietLeft(cycleOn, state.phase() == LavaCycle.Phase.WARNING, state.phase() == LavaCycle.Phase.RISE,
                state.into(), state.left());
        QuakeSchedule.Start start = schedule(level).tick(now, s, quiet, LavaFlood.held() || anyHeld(), !actors.isEmpty());
        if (start != null) begin(start.kind(), now, start.duration(), start.strength(), start.seed(), start.silent(), null, watchers);
    }

    /** The config's numbers for the schedule. */
    private static QuakeSchedule.Settings settings(ERConfig cfg) {
        return new QuakeSchedule.Settings(cfg.quakeTremors.get(), cfg.quakeTremorMinSeconds.get(), cfg.quakeTremorMaxSeconds.get(),
                cfg.quakeMinMinutes.get(), cfg.quakeMaxMinutes.get(), cfg.quakeSeconds.get());
    }

    private static QuakeSchedule schedule(ServerLevel level) {
        if (schedule == null) schedule = new QuakeSchedule(level.getRandom().nextLong());
        return schedule;
    }

    /**
     * The actors among the watchers (gate 3): those not in spectator mode, or every one of them with
     * {@code /remix quake spectators act}. They drive what changes the world: topples, climb answers, ceiling debris,
     * and for the eruptions the Calderas' clocks and the bombs' aims. Always a list of its own, never the one given.
     */
    public static List<ServerPlayer> actors(List<ServerPlayer> watchers) {
        if (spectatorsAct) return new ArrayList<>(watchers);
        List<ServerPlayer> out = new ArrayList<>(watchers.size());
        for (ServerPlayer p : watchers) if (!p.isSpectator()) out.add(p);
        return out;
    }

    /** Whether spectators are actors ({@code /remix quake spectators act}); for quakes and eruptions both. */
    public static boolean spectatorsAct() { return spectatorsAct; }

    private static boolean anyHeld() {
        for (Event e : RUNNING) if (e.held >= 0) return true;
        return false;
    }

    /**
     * Starts a quake of {@code kind} at game time {@code now}: a big quake (or a tremor) takes the place of one running,
     * whose end is sent; a big quake is told to the watchers at once, a tremor and a climb answer on the next tick, once
     * their find is done. Returns it.
     */
    private static Event begin(int kind, long now, int duration, float strength, int seed, boolean silent, @Nullable BlockPos origin,
                               List<ServerPlayer> watchers) {
        if (kind != QuakeSchedule.CLIMB) {
            for (Iterator<Event> it = RUNNING.iterator(); it.hasNext(); ) {
                Event e = it.next();
                if (e.kind == QuakeSchedule.CLIMB) continue;
                endEarly(e);
                it.remove();
            }
        } else {
            climbsThisCycle++;
        }
        Event e = new Event(kind, nextId++, now, duration, strength, seed, silent, origin);
        RUNNING.add(e);
        if (kind == QuakeSchedule.BIG) announce(e, watchers, true);
        return e;
    }

    /** Sends the quake's message to every watcher it reaches, with {@code felt} (silent quakes send nothing). */
    private static void announce(Event e, List<ServerPlayer> watchers, boolean felt) {
        e.announced = true;
        if (e.silent) return;
        for (ServerPlayer p : watchers) {
            if (!e.reaches(p)) continue;
            e.told.put(p.getUUID(), felt);
            send(p, e.message(felt));
        }
    }

    /**
     * Tells the quake to watchers who came near since it started (a big quake felt in full, the others faintly), and
     * forgets those no longer near, so they are told again if they come back (a client takes a quake it already has by
     * its id).
     */
    private static void tellLate(Event e, List<ServerPlayer> watchers) {
        if (e.silent) return;
        if (!e.told.isEmpty()) {
            List<UUID> near = new ArrayList<>(watchers.size());
            for (ServerPlayer p : watchers) if (e.reaches(p)) near.add(p.getUUID());
            e.told.keySet().retainAll(near);
        }
        for (ServerPlayer p : watchers) {
            if (e.told.containsKey(p.getUUID()) || !e.reaches(p)) continue;
            boolean felt = e.kind == QuakeSchedule.BIG;
            e.told.put(p.getUUID(), felt);
            send(p, e.message(felt));
        }
    }

    /** Tells everyone told of the quake where it is now (after a hold or a release). */
    private static void retell(ServerLevel level, Event e) {
        for (Map.Entry<UUID, Boolean> t : e.told.entrySet()) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(t.getKey());
            if (p != null && p.level() == level) send(p, e.message(t.getValue()));
        }
    }

    /** A quake ends before its time: every client in the overworld drops it. */
    private static void endEarly(Event e) {
        if (!e.silent) LavaNet.CHANNEL.send(PacketDistributor.DIMENSION.with(() -> Level.OVERWORLD), LavaNet.Quake.end(e.id));
        finish(e);
    }

    /** A quake is over: kept for the status. */
    private static void finish(Event e) { last = e; }

    private static void send(ServerPlayer p, LavaNet.Quake q) { LavaNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), q); }

    /** Gate 1: every quake ends now (its end sent to the clients) and the schedule is forgotten. */
    private static void stop() {
        for (Event e : RUNNING) endEarly(e);
        RUNNING.clear();
        schedule = null;
        lastPhase = null;
    }

    /** When the server stops: forgets everything (the next world may differ). */
    public static void reset() {
        schedule = null;
        RUNNING.clear();
        nextId = 1;
        spectatorsAct = false;
        last = null;
        lastState = null;
        lastCycleOn = false;
        lastPhase = null;
        climbsThisCycle = 0;
    }

    // ================================================================================================ the command

    /**
     * {@code /remix quake}: when the next tremor and big quake come and what the last one did; {@code now [tremor|big|
     * climb] [seconds] [strength]} starts one at once (a climb answer at the source's block), even while the lava warns
     * or its clock is held; {@code hold [ticks]} holds the newest running quake that many ticks in (now if left out), for
     * pictures, and {@code release} lets it go on; {@code spectators act|watch} makes spectators actors or only watchers
     * (not saved; watchers at start), for quakes and eruptions both.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("quake").executes(Quakes::status)
                .then(Commands.literal("now").executes(c -> now(c, QuakeSchedule.BIG, -1, -1))
                        .then(nowOf("tremor", QuakeSchedule.TREMOR))
                        .then(nowOf("big", QuakeSchedule.BIG))
                        .then(nowOf("climb", QuakeSchedule.CLIMB)))
                .then(Commands.literal("hold").executes(c -> hold(c, -1))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0)).executes(c -> hold(c, IntegerArgumentType.getInteger(c, "ticks")))))
                .then(Commands.literal("release").executes(Quakes::release))
                .then(Commands.literal("spectators")
                        .then(Commands.literal("act").executes(c -> spectators(c, true)))
                        .then(Commands.literal("watch").executes(c -> spectators(c, false))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> nowOf(String name, int kind) {
        return Commands.literal(name).executes(c -> now(c, kind, -1, -1))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                        .executes(c -> now(c, kind, IntegerArgumentType.getInteger(c, "seconds"), -1))
                        .then(Commands.argument("strength", FloatArgumentType.floatArg(0, 1))
                                .executes(c -> now(c, kind, IntegerArgumentType.getInteger(c, "seconds"), FloatArgumentType.getFloat(c, "strength")))));
    }

    /** The overworld with its Galleries, or null after telling the source why there are no quakes. */
    @Nullable
    private static ServerLevel galleries(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().getLevel(Level.OVERWORLD);
        if (level == null || !DeepWorld.installed() || LavaFlood.band() == null) {
            c.getSource().sendFailure(Component.literal("No quakes in this world: it has no Basalt Galleries (see the server log)."));
            return null;
        }
        if (!ERConfig.INSTANCE.quakesEnabled.get()) {
            c.getSource().sendFailure(Component.literal("No quakes: they are turned off in explorationremix-world.toml (quakes.enabled)."));
            return null;
        }
        return level;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        ERConfig cfg = ERConfig.INSTANCE;
        long now = level.getGameTime();
        String held = anyHeld() ? ", held" : LavaFlood.held() ? ", the lava clock is held so none start" : "";
        QuakeSchedule q = schedule;
        String tremor = !cfg.quakeTremors.get() ? "no tremors (quakes.tremors is off)"
                : "the next tremor in " + (q == null || !q.begun() ? "a moment" : time(q.nextTremor() - now));
        String big = q == null || !q.begun() ? "a moment" : time(q.nextBig() - now);
        LavaCycle.State st = lastState;
        int quiet = st == null ? 0 : QuakeSchedule.quietLeft(lastCycleOn, st.phase() == LavaCycle.Phase.WARNING, st.phase() == LavaCycle.Phase.RISE,
                st.into(), st.left());
        say(c, String.format(Locale.ROOT, "Quakes%s: %s, the next big quake in %s%s", held, tremor, big,
                quiet > 0 ? "; quiet while the lava warns (" + time(quiet) + " left)" : ""));
        Event l = last;
        if (l != null) {
            say(c, String.format(Locale.ROOT, "  last: %s %s ago, %d s at strength %.2f: %d placed blocks toppled (%d fell, %d given back to the lava, "
                            + "%d taken out, %d lowered under players), %d fell from the ceilings (%d shattered on someone)",
                    name(l.kind), time(now - l.start), Math.round(l.duration / 20.0), l.strength, l.toppled, l.fell, l.givenBack, l.takenOut, l.lowered,
                    l.debris, l.shattered));
        }
        int pending = 0;
        for (Event r : RUNNING) if (r.kind == QuakeSchedule.CLIMB) pending++;
        say(c, String.format(Locale.ROOT, "  climb answers: %d pending, %d this cycle", pending, climbsThisCycle));
        return 1;
    }

    private static int now(CommandContext<CommandSourceStack> c, int kind, int seconds, float strength) {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        LavaFlood.Band band = LavaFlood.band();
        List<ServerPlayer> watchers = band == null ? List.of() : NearChunks.players(level, band);
        if (watchers.isEmpty()) {
            c.getSource().sendFailure(Component.literal("No player is in or near the Basalt Galleries to feel a quake."));
            return 0;
        }
        ERConfig cfg = ERConfig.INSTANCE;
        QuakeSchedule q = schedule(level);
        QuakeSchedule.Settings s = settings(cfg);
        long now = level.getGameTime();
        int duration = seconds > 0 ? seconds * 20 : kind == QuakeSchedule.BIG ? q.drawBigDuration(s) : 30 + Math.round(q.drawFloat() * 20);
        float str = strength >= 0 ? strength : switch (kind) {
            case QuakeSchedule.BIG -> q.drawBigStrength();
            case QuakeSchedule.TREMOR -> 0.15F + q.drawFloat() * 0.2F;
            default -> 0.25F + q.drawFloat() * 0.15F;
        };
        BlockPos origin = kind == QuakeSchedule.CLIMB ? BlockPos.containing(c.getSource().getPosition()) : null;
        Event e = begin(kind, now, duration, str, q.drawSeed(), false, origin, watchers);
        if (kind == QuakeSchedule.BIG) {
            q.bigStarted(now, duration, s);
            say(c, String.format(Locale.ROOT, "A big quake starts: %d s at strength %.2f (%d players feel it).", Math.round(duration / 20.0), str, e.told.size()));
        } else if (kind == QuakeSchedule.TREMOR) {
            say(c, String.format(Locale.ROOT, "A tremor starts: %d placed blocks will topple.", e.toppled));
        } else {
            say(c, String.format(Locale.ROOT, "The ground answers a climb at %d %d %d: %d placed blocks will topple.",
                    origin.getX(), origin.getY(), origin.getZ(), e.toppled));
        }
        return 1;
    }

    private static int hold(CommandContext<CommandSourceStack> c, int ticks) {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        Event e = newest();
        if (e == null) {
            c.getSource().sendFailure(Component.literal("There is no quake to hold."));
            return 0;
        }
        long now = level.getGameTime();
        e.held = ticks < 0 ? Math.min(e.into(now), Math.max(0, e.duration - 1)) : Math.min(ticks, Math.max(0, e.duration - 1));
        retell(level, e);
        say(c, String.format(Locale.ROOT, "The quake is held %d ticks in.", e.held));
        return 1;
    }

    private static int release(CommandContext<CommandSourceStack> c) {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        Event e = null;
        for (Event r : RUNNING) if (r.held >= 0) e = r;
        if (e == null) {
            c.getSource().sendFailure(Component.literal("There is no held quake."));
            return 0;
        }
        e.start = level.getGameTime() - e.held;
        e.held = -1;
        // a big quake held ends later than the schedule drew it: tell the schedule its new start, or the tremor (and the
        // next big quake) that fell due while it was held would start at once and cut the rest of it short
        if (e.kind == QuakeSchedule.BIG) schedule(level).bigStarted(e.start, e.duration, settings(ERConfig.INSTANCE));
        retell(level, e);
        say(c, "The quake goes on.");
        return 1;
    }

    private static int spectators(CommandContext<CommandSourceStack> c, boolean act) {
        spectatorsAct = act;
        say(c, "Spectators now " + (act ? "set off" : "only watch") + " quakes and eruptions.");
        return 1;
    }

    /** The quake started last of those running, or null. */
    @Nullable
    private static Event newest() { return RUNNING.isEmpty() ? null : RUNNING.get(RUNNING.size() - 1); }

    private static String name(int kind) {
        return switch (kind) {
            case QuakeSchedule.BIG -> "big quake";
            case QuakeSchedule.TREMOR -> "tremor";
            default -> "climb answer";
        };
    }

    /** Ticks as minutes and seconds (0:00 when due). */
    private static String time(long ticks) {
        long s = Math.max(0, ticks) / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
