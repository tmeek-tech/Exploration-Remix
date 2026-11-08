package com.explorationremix.world.quake;

import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.lava.ChangeLog;
import com.explorationremix.world.lava.Falling;
import com.explorationremix.world.lava.GalleryChanges;
import com.explorationremix.world.lava.LavaCycle;
import com.explorationremix.world.lava.LavaFlood;
import com.explorationremix.world.lava.LavaNet;
import com.explorationremix.world.lava.NearChunks;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

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
 *     while a quake or an eruption is held by command; big quakes keep out of the lava's warning and the first 30 seconds of its rise,
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
 * are told again to everyone told before (one who leaves while it is held is told its end); a quake that is stopped
 * early sends its end to everyone in the overworld.
 *
 * <p><b>Toppling</b> ({@link Topple}, with {@code quakes.topple}): every quake runs a topple pass. A big quake's find
 * starts at once and its blocks fall from 20 ticks in until it ends (what is left falls at the next); a tremor's and a
 * climb answer's message waits for the find, and its blocks fall 10 ticks after it. Every column found under a player
 * creaks when the find is done and is lowered under them 20 ticks later ({@link Lowering}). The one placement listener
 * ({@link #placed}) schedules the climb answers ({@link ClimbAnswers}, {@code quakes.climbTremors}) and feeds
 * {@code quakes.toppleAtOnce}'s check, with the break listener ({@link #broken}).
 *
 * <p><b>Ceiling debris</b> ({@link Debris}, only with the terrain reset putting the holes back): in a big quake natural
 * ceiling rock comes loose round each actor in the layer, is warned of and drops a second later as a falling block,
 * which {@link Falling} tracks for its hits; a held quake's blocks wait or hang in the air.
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
    /** The climb answers waiting to come ({@code quakes.climbTremors}). */
    @Nullable private static ClimbAnswers climbs;
    /** Positions placed since the last tick, with who placed them, as the placement listener saw them (each once). */
    private static final Map<Long, UUID> PLACED = new LinkedHashMap<>();

    /** One quake under way. */
    static final class Event {
        final int kind, id, duration, seed;
        final float strength;
        final boolean silent;
        /** For a climb answer, the block placed (the find and the message are round it). */
        @Nullable final BlockPos origin;
        /** For a climb answer, who placed the block (its cooldown runs from the find), or null. */
        @Nullable final UUID placer;
        /** Its topple pass, or null with toppling off. */
        @Nullable Topple.Pass pass;
        /** For a big quake, what its ceiling debris has done ({@link Debris}), or null before its first pick. */
        @Nullable Debris.Plan drops;
        /** The tick its found blocks start to fall from (MAX_VALUE until its find is done). */
        long fallsAt = Long.MAX_VALUE;
        /** The game time it started at; a release moves it on by the time it was held. */
        long start;
        /** Ticks into it while held by command, or -1. */
        int held = -1;
        /** Its message has gone out (a tremor's and a climb answer's once its find is done). */
        boolean announced;
        /**
         * For a tremor or climb answer started by command whose find did not end in the command's tick (it stops for the
         * tick after {@code Topple.FIND_NANOS}): the command's answer, given the count once the find is done.
         */
        @Nullable IntConsumer onFound;
        /** The players told of it, with their felt flag; dropped when they leave, so they are told again when they come back. */
        final Map<UUID, Boolean> told = new HashMap<>();
        /** What it did, for the status. */
        int toppled, fell, givenBack, takenOut, lowered, debris, shattered;

        Event(int kind, int id, long start, int duration, float strength, int seed, boolean silent, @Nullable BlockPos origin,
              @Nullable UUID placer) {
            this.kind = kind;
            this.id = id;
            this.start = start;
            this.duration = duration;
            this.strength = strength;
            this.seed = seed;
            this.silent = silent;
            this.origin = origin;
            this.placer = placer;
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

        /**
         * Whether the quake is felt in full by {@code p}: a big quake always; a tremor or a climb answer only within
         * {@link Topple#FELT_RANGE} blocks of a block its find found (elsewhere the client plays it faintly now and then).
         */
        boolean feltBy(ServerPlayer p) {
            if (kind == QuakeSchedule.BIG) return true;
            if (pass == null) return false;
            double r2 = (double) Topple.FELT_RANGE * Topple.FELT_RANGE;
            for (int i = 0; i < pass.found.size(); i++) {
                long q = pass.found.getLong(i);
                if (p.distanceToSqr(BlockPos.getX(q) + 0.5, BlockPos.getY(q) + 0.5, BlockPos.getZ(q) + 0.5) <= r2) return true;
            }
            return false;
        }
    }

    // ================================================================================================ the tick

    /** Every overworld tick, after the flood and the reset ({@code LavaFlood.tick}). */
    public static void tick(ServerLevel level, LavaFlood.Band band, LavaCycle.State state, long now) {
        ERConfig cfg = ERConfig.INSTANCE;
        // gate 1: turned off, every quake ends and everything is forgotten
        if (!cfg.quakesEnabled.get()) {
            if (schedule != null || !RUNNING.isEmpty() || Lowering.running() > 0 || climbs != null || Debris.pending() > 0) stop(level);
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
        int quiet = QuakeSchedule.quietLeft(cycleOn, state.phase() == LavaCycle.Phase.WARNING, state.phase() == LavaCycle.Phase.RISE,
                state.into(), state.left());
        Build.tick(level, now);
        // toppling has its own switch: off, its jobs, queues and answers are forgotten and the quakes only shake
        Topple.Work w = null;
        if (cfg.quakeTopple.get()) {
            w = new Topple.Work(level, now, watchers);
            placements(w);
            if (!anyHeld()) {
                for (ClimbAnswers.Answer a : climbs(level).due(now)) {
                    // gate 4: with no actor left near, an answer that comes due is dropped; so is one still pending when
                    // quakes.climbTremors was turned off (the config is read live)
                    if (actors.isEmpty() || !cfg.quakeClimbTremors.get()) continue;
                    BlockPos at = BlockPos.of(a.pos());
                    begin(level, QuakeSchedule.CLIMB, now, a.duration(), a.strength(), a.seed(), quiet > 0, at, a.placer(), watchers, actors, w);
                }
            }
            // the builds under players first: a player's safety does not wait for the budget
            Lowering.tick(w);
        } else {
            stopToppling();
        }
        for (Iterator<Event> it = RUNNING.iterator(); it.hasNext(); ) {
            Event e = it.next();
            if (e.held < 0) {
                work(e, w, watchers, actors);
                Debris.picks(level, e, actors, watchers, now);
            }
            if (e.announced) tellLate(level, e, watchers);
            if (e.held < 0 && now - e.start >= e.duration) {
                finish(e);
                it.remove();
            }
        }
        // the ceiling blocks warned of drop when their time comes (a held quake's wait)
        Debris.tick(level, now);
        if (w != null) Topple.atOnce(w);
        // gate 2: nothing starts on its own while the lava clock, a quake or an eruption is held; the quiet window of the
        // warning
        QuakeSchedule.Settings s = settings(cfg);
        boolean held = LavaFlood.held() || anyHeld() || com.explorationremix.world.eruption.Eruptions.held();
        QuakeSchedule.Start start = schedule(level).tick(now, s, quiet, held, !actors.isEmpty());
        if (start != null) begin(level, start.kind(), now, start.duration(), start.strength(), start.seed(), start.silent(), null, null, watchers, actors, w);
    }

    /**
     * One tick of a running quake that is not held: its find goes on; once the find is done its message goes out (a
     * tremor's and a climb answer's, with each player's felt flag) with the creaks of the columns under players; then its
     * blocks fall in turn. With toppling off ({@code w} null) a tremor's message goes out at once, felt by no one in full.
     */
    private static void work(Event e, @Nullable Topple.Work w, List<ServerPlayer> watchers, List<ServerPlayer> actors) {
        if (w == null || e.pass == null) {
            answer(e);
            e.pass = null;
            if (!e.announced) announce(e, watchers);
            return;
        }
        if (!e.pass.findDone) {
            e.pass.find(w, actors);
            if (e.pass.findDone) found(e, w, watchers);
        }
        if (e.announced && w.now >= e.fallsAt) e.pass.falls(w, e);
    }

    /**
     * A quake's find is done: a tremor's or a climb answer's message goes out now and its blocks fall 10 ticks on (a big
     * quake was told at its start, and its blocks fall from 20 ticks in); the columns under players creak now and come
     * down 20 ticks on. A climb answer's placer's cooldown runs from here.
     */
    private static void found(Event e, Topple.Work w, List<ServerPlayer> watchers) {
        answer(e);
        if (e.kind == QuakeSchedule.BIG) {
            e.fallsAt = Math.min(e.fallsAt, e.start + BIG_FALLS);
        } else {
            announce(e, watchers);
            e.fallsAt = w.now + FALLS_AFTER;
        }
        e.pass.startJobs(w, e, LOWER_AFTER);
        if (e.placer != null) climbs(w.level).found(e.placer, w.now);
    }

    /** A tremor's and a climb answer's blocks fall this many ticks after its message, a big quake's this many into it. */
    static final int FALLS_AFTER = 10, BIG_FALLS = 20;
    /** The columns under players come down this many ticks after their creak. */
    static final int LOWER_AFTER = 20;

    /**
     * The placements since the last tick (each position once): with {@code quakes.climbTremors} one that falls by the rule
     * schedules a climb answer for its placer, and with {@code quakes.toppleAtOnce} each is checked soon.
     */
    private static void placements(Topple.Work w) {
        if (PLACED.isEmpty()) return;
        ERConfig cfg = ERConfig.INSTANCE;
        boolean answer = cfg.quakeClimbTremors.get();
        ClimbAnswers c = climbs(w.level);
        boolean big = bigRunning();
        for (Map.Entry<Long, UUID> pl : PLACED.entrySet()) {
            BlockPos pos = BlockPos.of(pl.getKey());
            if (w.atOnce) Topple.queue(pos);
            if (answer) c.placed(pl.getValue(), pl.getKey(), w.now, Topple.fallsNow(w.level, pos, w.depth), big, Lowering.runningAt(pos.getX(), pos.getZ()));
        }
        PLACED.clear();
    }

    /** Toppling is off: its jobs, queue, answers and passes are forgotten (the quakes only shake). */
    private static void stopToppling() {
        Lowering.clear();
        Topple.clear();
        PLACED.clear();
        if (climbs != null) climbs.clear();
    }

    private static boolean bigRunning() {
        for (Event e : RUNNING) if (e.kind == QuakeSchedule.BIG) return true;
        return false;
    }

    /** True while {@code e} runs (a lowering job gives its leftovers back to it only then). */
    static boolean running(Event e) { return RUNNING.contains(e); }

    private static ClimbAnswers climbs(ServerLevel level) {
        if (climbs == null) climbs = new ClimbAnswers(level.getRandom().nextLong());
        return climbs;
    }

    // ================================================================================================ the listeners

    /**
     * A block was placed by an entity (a player, a deployer's fake player, anything that places items; a bed's or a
     * door's parts come as one event): noted for the next tick, after which the block is in the change log. Blocks set
     * by commands, pistons, landings or blasts post nothing; the next quake's pass finds those.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void placed(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        ERConfig cfg = ERConfig.INSTANCE;
        if (!cfg.quakesEnabled.get() || !cfg.quakeTopple.get() || !cfg.quakeClimbTremors.get() && !cfg.quakeToppleAtOnce.get()) return;
        Entity by = event.getEntity();
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot snap : multi.getReplacedBlockSnapshots()) note(snap.getPos(), by);
        } else {
            note(event.getPos(), by);
        }
    }

    private static void note(BlockPos pos, @Nullable Entity by) {
        if (!GalleryChanges.inLayer(pos.getY()) || PLACED.size() >= Topple.QUEUE_MAX) return;
        PLACED.put(pos.asLong(), by != null ? by.getUUID() : new UUID(0, pos.asLong()));
    }

    /** With {@code quakes.toppleAtOnce}, a block a player breaks takes the support of the block over it: that is checked next tick. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void broken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        tookAway(event.getPos());
    }

    /**
     * The block at {@code pos} in the overworld was taken away (a player broke it, or a lava bomb's crater took it): with
     * {@code quakes.toppleAtOnce} the block over it is checked next tick (spec 4.3.8).
     */
    public static void tookAway(BlockPos pos) {
        ERConfig cfg = ERConfig.INSTANCE;
        if (!cfg.quakesEnabled.get() || !cfg.quakeTopple.get() || !cfg.quakeToppleAtOnce.get()) return;
        BlockPos above = pos.above();
        if (GalleryChanges.inLayer(above.getY())) Topple.queue(above);
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

    /** True while a quake is held by command (no quake or eruption starts on its own then). */
    public static boolean held() { return anyHeld(); }

    /**
     * Starts a quake of {@code kind} at game time {@code now}: a big quake (or a tremor) takes the place of one running,
     * whose end is sent; a big quake is told to the watchers at once, a tremor and a climb answer once their find is
     * done. With toppling on ({@code w} not null) its pass starts now. Returns it.
     */
    private static Event begin(ServerLevel level, int kind, long now, int duration, float strength, int seed, boolean silent, @Nullable BlockPos origin,
                               @Nullable UUID placer, List<ServerPlayer> watchers, List<ServerPlayer> actors, @Nullable Topple.Work w) {
        if (kind != QuakeSchedule.CLIMB) {
            for (Iterator<Event> it = RUNNING.iterator(); it.hasNext(); ) {
                Event e = it.next();
                if (e.kind == QuakeSchedule.CLIMB) continue;
                endEarly(level, e);
                it.remove();
            }
        } else {
            climbsThisCycle++;
        }
        Event e = new Event(kind, nextId++, now, duration, strength, seed, silent, origin, placer);
        RUNNING.add(e);
        if (w != null) e.pass = new Topple.Pass(origin, seed);
        if (kind == QuakeSchedule.BIG) {
            announce(e, watchers);
            e.fallsAt = now + BIG_FALLS;
        }
        if (w != null) work(e, w, watchers, actors);
        return e;
    }

    /** Sends the quake's message to every watcher it reaches, with that player's felt flag (silent quakes send nothing). */
    private static void announce(Event e, List<ServerPlayer> watchers) {
        e.announced = true;
        if (e.silent) return;
        for (ServerPlayer p : watchers) {
            if (!e.reaches(p)) continue;
            boolean felt = e.feltBy(p);
            e.told.put(p.getUUID(), felt);
            send(p, e.message(felt));
        }
    }

    /**
     * Tells the quake to watchers who came near since it started (a big quake felt in full, the others by where its
     * blocks fall), and forgets those no longer near, so they are told again if they come back (a client takes a quake
     * it already has by its id). One who leaves while the quake is held is told its end: the release is told only to
     * those still near, and a client left holding a quake would keep its whole view still (the lava's warning too)
     * after the quake was let go and ran out.
     */
    private static void tellLate(ServerLevel level, Event e, List<ServerPlayer> watchers) {
        if (e.silent) return;
        if (!e.told.isEmpty()) {
            List<UUID> near = new ArrayList<>(watchers.size());
            for (ServerPlayer p : watchers) if (e.reaches(p)) near.add(p.getUUID());
            if (e.held >= 0) {
                for (UUID id : e.told.keySet()) {
                    if (near.contains(id)) continue;
                    ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                    if (p != null) send(p, LavaNet.Quake.end(e.id));
                }
            }
            e.told.keySet().retainAll(near);
        }
        for (ServerPlayer p : watchers) {
            if (e.told.containsKey(p.getUUID()) || !e.reaches(p)) continue;
            boolean felt = e.feltBy(p);
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

    /**
     * A quake ends before its time: every client in the overworld drops it. A held one is let go first: its lowering jobs
     * and its ceiling blocks wait while it is held, and nothing could release it once it is off the list (its jobs would
     * keep their columns from every later pass, and its blocks would hang in the air).
     */
    private static void endEarly(ServerLevel level, Event e) {
        if (e.held >= 0) {
            e.held = -1;
            Debris.released(level, e);
        }
        if (!e.silent) LavaNet.CHANNEL.send(PacketDistributor.DIMENSION.with(() -> Level.OVERWORLD), LavaNet.Quake.end(e.id));
        finish(e);
    }

    /** A quake is over: kept for the status. */
    private static void finish(Event e) {
        answer(e);
        last = e;
    }

    /** Gives a command's waiting answer ({@link Event#onFound}) the count found so far, once. */
    private static void answer(Event e) {
        if (e.onFound == null) return;
        IntConsumer a = e.onFound;
        e.onFound = null;
        a.accept(e.pass == null ? 0 : e.pass.found());
    }

    private static void send(ServerPlayer p, LavaNet.Quake q) { LavaNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), q); }

    /**
     * Gate 1: every quake ends now (its end sent to the clients), the lowering jobs and builds stop, the ceiling blocks
     * waiting to drop are called off (those in the air are let go and fall on), and the schedule, the climb answers and
     * the queues are forgotten.
     */
    private static void stop(ServerLevel level) {
        for (Event e : RUNNING) endEarly(level, e);
        RUNNING.clear();
        Debris.stop(level.players());
        schedule = null;
        lastPhase = null;
        stopToppling();
        climbs = null;
        Build.clear();
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
        climbs = null;
        PLACED.clear();
        Lowering.clear();
        Topple.clear();
        Build.clear();
        Debris.reset();
    }

    // ================================================================================================ the command

    /**
     * {@code /remix quake}: when the next tremor and big quake come and what the last one did; {@code now [tremor|big|
     * climb] [seconds] [strength]} starts one at once (a climb answer at the source's block), even while the lava warns
     * or its clock is held; {@code hold [ticks]} holds the newest running quake that many ticks in (now if left out), for
     * pictures, and {@code release} lets it go on (and lets go of the staged drops); {@code drop [<pos>] [above
     * <blocks>]} stages a ceiling drop near the position, which hangs that many blocks over its landing row until the
     * release, for pictures; {@code build <pos> <height> [ticksPerBlock] [rider <player>]} towers
     * up a column of cobblestone as a player would, for the in-pack checks ({@link Build}); {@code spectators
     * act|watch} makes spectators actors or only watchers (not saved; watchers at start), for quakes and eruptions both.
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
                .then(Commands.literal("drop").executes(c -> drop(c, false, -1))
                        .then(above(false))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> drop(c, true, -1))
                                .then(above(true))))
                .then(Commands.literal("build")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("height", IntegerArgumentType.integer(1, 256))
                                        .executes(c -> build(c, 10, false))
                                        .then(Commands.argument("ticksPerBlock", IntegerArgumentType.integer(1, 200))
                                                .executes(c -> build(c, IntegerArgumentType.getInteger(c, "ticksPerBlock"), false))
                                                .then(Commands.literal("rider").then(Commands.argument("rider", EntityArgument.player())
                                                        .executes(c -> build(c, IntegerArgumentType.getInteger(c, "ticksPerBlock"), true)))))
                                        .then(Commands.literal("rider").then(Commands.argument("rider", EntityArgument.player())
                                                .executes(c -> build(c, 10, true)))))))
                .then(Commands.literal("spectators")
                        .then(Commands.literal("act").executes(c -> spectators(c, true)))
                        .then(Commands.literal("watch").executes(c -> spectators(c, false))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> above(boolean withPos) {
        return Commands.literal("above").then(Commands.argument("blocks", IntegerArgumentType.integer(0, 256))
                .executes(c -> drop(c, withPos, IntegerArgumentType.getInteger(c, "blocks"))));
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
        } else {
            say(c, "  last: no quake or tremor yet");
        }
        int pending = climbs == null ? 0 : climbs.pending();
        for (Event r : RUNNING) if (r.kind == QuakeSchedule.CLIMB) pending++;
        say(c, String.format(Locale.ROOT, "  climb answers: %d pending, %d this cycle; lowering jobs: %d running", pending, climbsThisCycle,
                Lowering.running()));
        say(c, String.format(Locale.ROOT, "  topple pass: %d chunks with changes round %d players, %d changes looked at, %.2f ms (at most %.2f in a tick)",
                Topple.lastChunks, Topple.lastPlayers, Topple.lastEntries, Topple.lastMs, Topple.lastMaxMs));
        here(c, level);
        return 1;
    }

    /** The status's last line: the blocks in the air, and what the source's chunk holds. */
    private static void here(CommandContext<CommandSourceStack> c, ServerLevel level) {
        Vec3 p = c.getSource().getPosition();
        LevelChunk chunk = level.getChunkSource().getChunkNow((int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4);
        ChangeLog log = chunk == null ? null : GalleryChanges.logOf(chunk);
        if (chunk == null || log == null) {
            say(c, String.format(Locale.ROOT, "  falling now: %d", Falling.count()));
            return;
        }
        int depth = ERConfig.INSTANCE.quakeSupportDepth.get();
        int bottom = GalleryChanges.bottom(), seaTop = GalleryChanges.seaTop(chunk);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        int[] n = new int[2];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(), under = new BlockPos.MutableBlockPos();
        log.forEach((key, first, gen) -> {
            int x = x0 + ChangeLog.x(key), y = bottom + ChangeLog.dy(key), z = z0 + ChangeLog.z(key);
            int k = BlockKinds.kindAt(chunk, log, bottom, seaTop, at.set(x, y, z));
            if (!Support.topples(k)) return;
            n[0]++;
            if (Support.falls(k, j -> BlockKinds.kindAt(chunk, log, bottom, seaTop, under.set(x, y - j, z)), depth)) n[1]++;
        });
        say(c, String.format(Locale.ROOT, "  falling now: %d; this chunk holds %d placed blocks, %d of them more than %d over rock",
                Falling.count(), n[0], n[1], depth));
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
        // the find runs now, within a tick's budget, so the answer can say what it found
        Topple.Work w = cfg.quakeTopple.get() ? new Topple.Work(level, now, watchers) : null;
        Event e = begin(level, kind, now, duration, str, q.drawSeed(), false, origin, null, watchers, actors(watchers), w);
        if (kind == QuakeSchedule.BIG) {
            q.bigStarted(now, duration, s);
            say(c, String.format(Locale.ROOT, "A big quake starts: %d s at strength %.2f (%d players feel it).", Math.round(duration / 20.0), str, e.told.size()));
            return 1;
        }
        CommandSourceStack src = c.getSource();
        IntConsumer answer = kind == QuakeSchedule.TREMOR
                ? n -> say(src, String.format(Locale.ROOT, "A tremor starts: %d placed blocks will topple.", n))
                : n -> say(src, String.format(Locale.ROOT, "The ground answers a climb at %d %d %d: %d placed blocks will topple.",
                        origin.getX(), origin.getY(), origin.getZ(), n));
        // a find round a big build can go on for a tick or two: the answer waits for its count
        if (e.pass != null && !e.pass.findDone) e.onFound = answer;
        else answer.accept(e.pass == null ? 0 : e.pass.found());
        return 1;
    }

    private static int build(CommandContext<CommandSourceStack> c, int every, boolean withRider) throws CommandSyntaxException {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, level, "pos");
        int height = IntegerArgumentType.getInteger(c, "height");
        ServerPlayer rider = withRider ? EntityArgument.getPlayer(c, "rider") : null;
        if (!Build.start(level, pos, height, every, rider, c.getSource())) {
            c.getSource().sendFailure(Component.literal(String.format(Locale.ROOT, "There is no room to build at %d %d %d.", pos.getX(), pos.getY(), pos.getZ())));
            return 0;
        }
        say(c, String.format(Locale.ROOT, "Building %d blocks at %d %d %d, one every %d ticks%s.", height, pos.getX(), pos.getY(), pos.getZ(), every,
                rider == null ? "" : ", with " + rider.getGameProfile().getName() + " on top"));
        return 1;
    }

    /**
     * {@code /remix quake drop [<pos>] [above <blocks>]}: a ceiling block near the position (the source's by default)
     * comes loose as in a big quake, warned of and dropped a second later, with no quake running; with {@code above}
     * it hangs that many blocks over its landing row until {@code release}.
     */
    private static int drop(CommandContext<CommandSourceStack> c, boolean withPos, int above) throws CommandSyntaxException {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        if (!Debris.terrainDamage()) {
            c.getSource().sendFailure(Component.literal(
                    "No ceiling debris: the terrain reset is off, and only the reset puts it back (terrainReset.enabled, lavaCycle.enabled)."));
            return 0;
        }
        BlockPos from = withPos ? BlockPosArgument.getLoadedBlockPos(c, level, "pos") : BlockPos.containing(c.getSource().getPosition());
        LavaFlood.Band band = LavaFlood.band();
        List<ServerPlayer> watchers = band == null ? List.of() : NearChunks.players(level, band);
        Debris.Piece pc = Debris.stage(level, from, above, watchers, level.getGameTime());
        if (pc == null) {
            c.getSource().sendFailure(Component.literal("No ceiling block within 8 blocks over here can fall (natural rock in "
                    + "#explorationremix:quake_debris with open space under it, solid rock over it and somewhere to land)."));
            return 0;
        }
        say(c, String.format(Locale.ROOT, "A ceiling block at %d %d %d falls %d blocks%s.", pc.pos.getX(), pc.pos.getY(), pc.pos.getZ(),
                pc.pos.getY() - pc.landY, pc.above < 0 ? "" : String.format(Locale.ROOT, " (held %d over the floor)", pc.above)));
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
        Debris.held(level, e);
        say(c, String.format(Locale.ROOT, "The quake is held %d ticks in.", e.held));
        return 1;
    }

    private static int release(CommandContext<CommandSourceStack> c) {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        Event e = null;
        for (Event r : RUNNING) if (r.held >= 0) e = r;
        int staged = Debris.releaseStaged(level);
        if (e == null) {
            if (staged > 0) {
                say(c, "The quake goes on.");
                return 1;
            }
            c.getSource().sendFailure(Component.literal("There is no held quake."));
            return 0;
        }
        e.start = level.getGameTime() - e.held;
        e.held = -1;
        // a big quake held ends later than the schedule drew it: tell the schedule its new start, or the tremor (and the
        // next big quake) that fell due while it was held would start at once and cut the rest of it short
        if (e.kind == QuakeSchedule.BIG) schedule(level).bigStarted(e.start, e.duration, settings(ERConfig.INSTANCE));
        retell(level, e);
        Debris.released(level, e);
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

    private static void say(CommandContext<CommandSourceStack> c, String msg) { say(c.getSource(), msg); }

    private static void say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
