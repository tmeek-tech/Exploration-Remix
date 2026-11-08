package com.explorationremix.world.eruption;

import com.explorationremix.config.ERConfig;
import com.explorationremix.entity.LavaBomb;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.Walkway;
import com.explorationremix.world.lava.Falling;
import com.explorationremix.world.lava.LavaCycle;
import com.explorationremix.world.lava.LavaFlood;
import com.explorationremix.world.lava.LavaNet;
import com.explorationremix.world.lava.NearChunks;
import com.explorationremix.world.quake.Quakes;
import com.explorationremix.world.terrain.Net;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The Caldera's eruptions on the server (0.9.0, spec 5): a vent on top of one of the cinder stacks smokes and rumbles,
 * then bursts, throwing up a fountain of lava; each Caldera on {@link EruptionSchedule}'s clock, more often once the lava
 * rises. Each eruption is told to the players who can see and hear it ({@link LavaNet.Eruption}), whose clients play its
 * smoke, fountain, plume and sounds. Not saved: the clocks are drawn again when the server starts, and an eruption
 * running then is simply over.
 *
 * <p><b>Gates</b> (spec 3.1), every tick after the flood, the reset and the quakes ({@code LavaFlood.tick}):
 * <ol>
 *     <li>With {@code eruptions.enabled} off, every eruption ends (its end is sent to the clients) and everything is
 *     forgotten; the clocks are drawn again when it comes back on.</li>
 *     <li>Nothing starts on its own while the lava clock is held ({@link LavaFlood#held}, which DevShots' setup does) or
 *     while an eruption or a quake is held by command. Commands still start eruptions.</li>
 *     <li>The players in or near the Galleries are the watchers, who are told of the eruptions within
 *     {@code heardRange} of them; those not in spectator mode, or all of them with {@code /remix quake spectators act},
 *     are the actors ({@link Quakes#actors}), who wake the Calderas: only a Caldera with a vent within {@code range} of
 *     an actor keeps a clock, and only such a vent erupts.</li>
 * </ol>
 *
 * <p><b>Calderas.</b> Every {@link #LOOK_EVERY} ticks the vents round each actor are looked up
 * ({@link Terrain#ventSitesNear}, kept per 64-block cell of the actor's position, the last {@link #MEMO_CELLS} cells:
 * the answer is the seed's alone) and gathered by their Caldera (the field's {@code chamber}). A Caldera no actor has been
 * within {@code range} of for {@link #FORGET} ticks is forgotten. When one's eruption is due, one of its vents within
 * {@code range} of an actor is drawn, nearer ones more often ({@code ventNearBias}), read from the world's blocks
 * ({@link Vents#read}) and started. One eruption at a time in each Caldera, at most {@link #MAX_RUNNING} in the server.
 *
 * <p><b>Timeline</b> (spec 5.3): {@code warnSeconds} of warning, then a burst of {@code burstSeconds} (a quarter more or
 * less) at a strength of 0.8 to 1.2; the end is told {@link #TAIL} ticks after the burst, when the plume the client
 * keeps puffing after it is done. Players who come within {@code heardRange} while it runs are told it within a second.
 *
 * <p><b>Bombs</b> (spec 5.3, 5.4): through the burst, {@code bombs} times the strength of them, 40 % in its first second
 * and at most two a tick ({@link BombFlight#launches}); the first {@code bombsNear} come down near an actor on open
 * ground within 48 blocks of the vent when one is there, the rest round the vent ({@link BombFlight}). A bomb is thrown
 * only from a vent on entity-ticking ground to a target on it: an eruption with none to throw is a show only, as the far
 * ones are. Every bomb in the air is tracked: one no longer on entity-ticking ground fizzles (gate 6 of 3.1), all of
 * them when eruptions are turned off, and those of a held eruption hang still ({@link #holds}), as do their craters'
 * rocks in the air. Where a bomb strikes it bursts ({@link Blast}). Through the burst, whoever stands in the vent's mouth
 * burns ({@link #burn}).
 */
public final class Eruptions {
    private Eruptions() {}

    /** How often the vents round the actors are looked up, in ticks. */
    static final int LOOK_EVERY = 100;
    /** A Caldera no actor has been near for this many ticks is forgotten. */
    static final int FORGET = 12_000;
    /** The vents round each 64-block cell are kept for the last this many cells. */
    static final int MEMO_CELLS = 256;
    /** The cells' size, in blocks, and how far a cell's middle lies from its corners (rounded up). */
    static final int CELL = 64, CELL_REACH = 46;
    /** Most eruptions running at once in the server. */
    static final int MAX_RUNNING = 3;
    /** Players who came near are told every this many ticks. */
    static final int TELL_EVERY = 20;
    /** The eruption's end is told this many ticks after its burst, once the client's plume is done. */
    static final int TAIL = 200;
    /** An eruption due whose vents could not be read is tried again this many ticks on. */
    static final int RETRY = 100;
    /**
     * Through the burst, every this many ticks, whoever stands in the vent's mouth burns: up to this many rows over the
     * crater floor, and half a block past its rim.
     */
    static final int BURN_EVERY = 10, BURN_ROWS = 6;
    static final double BURN_OVER = 0.5;
    /** A bomb dropped by command falls from this many blocks over its target (or from under a lower roof). */
    static final int DROP_FROM = 12;

    // ------------------------------------------------------------------------------------------- state of this server
    @Nullable private static EruptionSchedule schedule;
    /** The Calderas the actors have been near, by chamber. */
    private static final Map<Integer, Caldera> CALDERAS = new LinkedHashMap<>();
    /** The vent sites round each 64-block cell an actor stood in, the last {@link #MEMO_CELLS} of them. */
    private static final Map<Long, List<Net.Vent>> MEMO = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<Net.Vent>> eldest) { return size() > MEMO_CELLS; }
    };
    /** The range the memo was filled for (the config is read live). */
    private static int memoRange = -1;
    /** The eruptions under way, and those whose plume is still told. */
    private static final List<Eruption> RUNNING = new ArrayList<>();
    /** The bombs in the air, with the eruption that threw each (null for one a command dropped). */
    private static final Map<LavaBomb, Eruption> BOMBS = new IdentityHashMap<>();
    /**
     * Held by command while no eruption runs: the bombs a command dropped, and the rocks their craters threw, hang still
     * until released, so they can be pictured in the air. It ends by itself once nothing it holds is left.
     */
    private static boolean dropsHeld;
    /** The rocks thrown by the craters of bombs a command dropped, while they are in the air (held with the drops). */
    private static final Set<FallingBlockEntity> DROP_ROCKS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static int nextId = 1;
    /** The lava's phase as of the last tick (-1 before the first). */
    private static int lastPhase = -1;

    /** A Caldera: one of the field's Caldera patches, with the vents of it the actors have been near. */
    static final class Caldera {
        final int chamber;
        /** Its vents found so far, by their column. */
        final Map<Long, Net.Vent> vents = new LinkedHashMap<>();
        final EruptionSchedule.Clock clock;
        long lastSeen;
        /** Its last eruption, for the status. */
        @Nullable Eruption last;

        Caldera(int chamber, EruptionSchedule.Clock clock, long now) {
            this.chamber = chamber;
            this.clock = clock;
            this.lastSeen = now;
        }

        void add(Net.Vent v) { vents.putIfAbsent(key(v), v); }
    }

    /** One eruption under way. */
    static final class Eruption {
        final int id;
        final Caldera caldera;
        final Net.Vent vent;
        final Vents.Read at;
        /** The game time it started at; a release moves it on by the time it was held. */
        long start;
        final int pre, burst;
        final float strength, scale;
        final long seed;
        /** How far it is told, the config's {@code heardRange} when it started. */
        final int range;
        /** Its own draws (its bombs'), from its seed, so a command's seed repeats it. */
        final Random random;
        /** Ticks into it while held by command, or -1. */
        int held = -1;
        /** A hold asked for a moment it has not reached yet: it holds there when it comes, or -1. */
        int holdAt = -1;
        /** The players told of it, dropped when they go out of its range so they are told again when they come back. */
        final Set<UUID> told = new HashSet<>();
        /** What it did, for the status. */
        int bombs, near, craters, craterBlocks, glow, intoLava;
        /** When its bombs go, in ticks into the burst ({@link BombFlight#launches}), and how many of them have had their turn. */
        int[] plan = new int[0];
        int next;

        Eruption(int id, Caldera caldera, Net.Vent vent, Vents.Read at, long start, int pre, int burst, float strength, float scale,
                 long seed, int range) {
            this.id = id;
            this.caldera = caldera;
            this.vent = vent;
            this.at = at;
            this.start = start;
            this.pre = pre;
            this.burst = burst;
            this.strength = strength;
            this.scale = scale;
            this.seed = seed;
            this.range = range;
            this.random = new Random(seed);
        }

        /** Ticks into it now. */
        int into(long now) { return held >= 0 ? held : (int) Math.min(Integer.MAX_VALUE, now - start); }

        /** True while it warns or bursts (not in the plume's tail): it keeps its Caldera from another and counts against the cap. */
        boolean active(long now) { return into(now) < pre + burst; }

        /** True during its burst. */
        boolean bursting(long now) {
            int t = into(now);
            return t >= pre && t < pre + burst;
        }

        double x() { return vent.x; }
        double z() { return vent.z; }

        LavaNet.Eruption message() {
            return new LavaNet.Eruption(LavaNet.Eruption.START, id, vent.x, vent.z, at.top(), at.startY(), (float) vent.r, start, pre, burst,
                    strength, scale, seed, held, range);
        }

        /** True for a player within its range of the mouth. */
        boolean reaches(ServerPlayer p) {
            return p.distanceToSqr(vent.x, at.startY() + 0.5, vent.z) < (double) range * range;
        }
    }

    // ================================================================================================ the tick

    /** Every overworld tick, after the flood, the reset and the quakes ({@code LavaFlood.tick}). */
    public static void tick(ServerLevel level, LavaFlood.Band band, LavaCycle.State state, long now) {
        ERConfig cfg = ERConfig.INSTANCE;
        // gate 1: turned off, every eruption ends and everything is forgotten
        if (!cfg.eruptionsEnabled.get()) {
            if (schedule != null || !RUNNING.isEmpty() || !CALDERAS.isEmpty() || !BOMBS.isEmpty()) stop();
            return;
        }
        EruptionSchedule sched = schedule(level);
        EruptionSchedule.Settings s = settings(cfg);
        int phase = cfg.lavaCycleEnabled.get() ? state.phase().ordinal() : EruptionSchedule.CALM;
        lastPhase = phase;
        List<ServerPlayer> watchers = NearChunks.players(level, band);
        List<ServerPlayer> actors = Quakes.actors(watchers);
        int range = cfg.eruptionRange.get();
        if (now % LOOK_EVERY == 0) look(sched, actors, now, phase, s, range);
        for (Caldera c : CALDERAS.values()) sched.phase(c.clock, phase, now, s);
        for (Iterator<Eruption> it = RUNNING.iterator(); it.hasNext(); ) {
            Eruption e = it.next();
            if (e.held < 0 && e.holdAt >= 0 && now - e.start >= e.holdAt) {
                // a hold asked for this moment: the eruption stops here
                e.held = e.holdAt;
                e.holdAt = -1;
                retell(level, e);
            }
            if (now % TELL_EVERY == 0) tellLate(level, e, watchers);
            if (e.held < 0) {
                launch(level, band, e, actors, now, cfg);
                burn(level, e, now, cfg);
            }
            if (e.held < 0 && now - e.start >= e.pre + e.burst + TAIL) {
                it.remove();
                for (UUID id : e.told) {
                    ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                    if (p != null) send(p, LavaNet.Eruption.end(e.id));
                }
            }
        }
        sweep(level);
        // gate 2: nothing starts on its own while the lava clock, an eruption or a quake is held
        if (LavaFlood.held() || held() || Quakes.held()) return;
        for (Caldera c : CALDERAS.values()) {
            if (!EruptionSchedule.due(c.clock, now) || busy(c, now)) continue;
            if (active(now) >= MAX_RUNNING) break;
            due(level, band, sched, s, c, actors, watchers, range, now, phase);
        }
    }

    /**
     * Every {@link #LOOK_EVERY} ticks: the vents within {@code range} of each actor, gathered into their Calderas (a new
     * one gets its clock), and the Calderas no actor has been near for {@link #FORGET} ticks forgotten.
     */
    private static void look(EruptionSchedule sched, List<ServerPlayer> actors, long now, int phase, EruptionSchedule.Settings s, int range) {
        Terrain t = DeepWorld.terrain();
        if (t == null) return;
        if (range != memoRange) {
            MEMO.clear();
            memoRange = range;
        }
        double r2 = (double) range * range;
        for (ServerPlayer p : actors) {
            for (Net.Vent v : sitesAround(t, p.getX(), p.getZ(), range)) {
                double dx = v.x - p.getX(), dz = v.z - p.getZ();
                if (dx * dx + dz * dz > r2) continue;
                caldera(sched, v, now, phase, s).lastSeen = now;
            }
        }
        CALDERAS.values().removeIf(c -> now - c.lastSeen > FORGET && !busy(c, now));
    }

    /** The Caldera of vent {@code v}, with the vent added: a Caldera not tracked yet is, with its first clock. */
    private static Caldera caldera(EruptionSchedule sched, Net.Vent v, long now, int phase, EruptionSchedule.Settings s) {
        Caldera c = CALDERAS.get(v.chamber);
        if (c == null) {
            c = new Caldera(v.chamber, sched.firstSeen(now, phase, s), now);
            CALDERAS.put(v.chamber, c);
        }
        c.add(v);
        return c;
    }

    /** The vent sites within {@code range} of anywhere in the 64-block cell of (x, z), kept for the cell. */
    private static List<Net.Vent> sitesAround(Terrain t, double x, double z, int range) {
        int cx = Mth.floor(x) >> 6, cz = Mth.floor(z) >> 6;
        long key = ChunkPos.asLong(cx, cz);
        List<Net.Vent> vs = MEMO.get(key);
        if (vs == null) {
            vs = List.copyOf(t.ventSitesNear(cx * CELL + CELL / 2, cz * CELL + CELL / 2, range + CELL_REACH));
            MEMO.put(key, vs);
        }
        return vs;
    }

    /**
     * Caldera {@code c}'s eruption is due: one of its vents within {@code range} of an actor is drawn (nearer ones more
     * often) and read from the world, trying the others nearest first when it cannot be; with no vent near an actor the
     * eruption is skipped (the next gap drawn), and with none that reads it is tried again {@link #RETRY} ticks on.
     */
    private static void due(ServerLevel level, LavaFlood.Band band, EruptionSchedule sched, EruptionSchedule.Settings s, Caldera c,
                            List<ServerPlayer> actors, List<ServerPlayer> watchers, int range, long now, int phase) {
        List<Net.Vent> near = new ArrayList<>();
        List<Double> dist = new ArrayList<>();
        for (Net.Vent v : c.vents.values()) {
            double d = nearestActor(v, actors);
            if (d > range) continue;
            near.add(v);
            dist.add(d);
        }
        if (near.isEmpty()) {
            sched.started(c.clock, now, s);
            return;
        }
        double[] d = new double[dist.size()];
        for (int i = 0; i < d.length; i++) d[i] = dist.get(i);
        int first = sched.pick(d, s.ventNearBias());
        List<Integer> order = new ArrayList<>();
        order.add(first);
        for (int i = 0; i < d.length; i++) if (i != first) order.add(i);
        order.subList(1, order.size()).sort(Comparator.comparingDouble(i -> d[i]));
        for (int i : order) {
            Net.Vent v = near.get(i);
            Vents.Read at = Vents.read(level, band, v);
            if (at == null) continue;
            begin(level, c, v, at, now, sched.drawStrength(), sched.drawSeed(), sched, s, phase, watchers);
            return;
        }
        EruptionSchedule.later(c.clock, now, RETRY);
    }

    /** The horizontal distance from vent {@code v} to the nearest of {@code actors}, or infinity. */
    private static double nearestActor(Net.Vent v, List<ServerPlayer> actors) {
        double best = Double.POSITIVE_INFINITY;
        for (ServerPlayer p : actors) {
            double dx = v.x - p.getX(), dz = v.z - p.getZ();
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }

    /**
     * Starts an eruption of vent {@code v} (read as {@code at}) of Caldera {@code c} at {@code now}: told to the watchers
     * within its range at once, and the Caldera's next drawn from now (a command's moves the clock as the clock's own
     * would, so none follows it at once).
     */
    private static Eruption begin(ServerLevel level, Caldera c, Net.Vent v, Vents.Read at, long now, float strength, long seed,
                                  EruptionSchedule sched, EruptionSchedule.Settings s, int phase, List<ServerPlayer> watchers) {
        Eruption e = new Eruption(nextId++, c, v, at, now, EruptionSchedule.pre(s), sched.drawBurst(s), strength,
                EruptionSchedule.scale(phase, s.risingScale()), seed, ERConfig.INSTANCE.eruptionHeardRange.get());
        e.plan = BombFlight.launches((int) Math.round(ERConfig.INSTANCE.eruptionBombs.get() * strength), e.burst, e.random);
        if (e.plan.length > 0) warmWays(at);
        RUNNING.add(e);
        c.last = e;
        sched.started(c.clock, now, s);
        for (ServerPlayer p : watchers) {
            if (!e.reaches(p)) continue;
            e.told.add(p.getUUID());
            send(p, e.message());
        }
        return e;
    }

    /**
     * Reads the ways down round the vent {@code at} on a worker thread, while the eruption warns. Each bomb's blast asks
     * whether a way down is near ({@link Blast}'s way guard), and the first such question round a place in a server's
     * run makes the field find the holes' openings for the lattice cells about it, which costs about a tenth of a second
     * (measured: 70 to 150 ms cold); the field keeps them in caches every thread shares, so asking here, off the server's
     * thread, at the four corners of the square its bombs come down in ({@link BombFlight#REACH}), leaves the bursts only
     * the cheap look-up (well under a millisecond). Pure in the seed: the answers are not used, and a failure only means
     * the first burst pays as before.
     */
    private static void warmWays(Vents.Read at) {
        int x = at.x(), z = at.z(), y = at.top(), d = (int) Math.ceil(BombFlight.REACH);
        try {
            Util.backgroundExecutor().execute(() -> {
                try {
                    for (int sx = -1; sx <= 1; sx += 2) {
                        for (int sz = -1; sz <= 1; sz += 2) {
                            Walkway.nearWayDown(x + sx * d, z + sz * d, y - Blast.WAY_ROWS, y + Blast.WAY_ROWS, Blast.WAY_MARGIN, Blast.WAY_MARGIN);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // the bursts ask again on the server's thread
                }
            });
        } catch (RuntimeException ignored) {
            // the executor is shutting down with the server
        }
    }

    /**
     * Tells the eruption to watchers who came within its range since, and forgets those who left it, so they are told
     * again if they come back (a client takes an eruption it already has by its id). One who leaves while it is held is
     * told its end: the release is told only to those still near.
     */
    private static void tellLate(ServerLevel level, Eruption e, List<ServerPlayer> watchers) {
        Set<UUID> near = new HashSet<>();
        for (ServerPlayer p : watchers) if (e.reaches(p)) near.add(p.getUUID());
        for (Iterator<UUID> it = e.told.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (near.contains(id)) continue;
            it.remove();
            if (e.held >= 0) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                if (p != null) send(p, LavaNet.Eruption.end(e.id));
            }
        }
        for (ServerPlayer p : watchers) {
            if (!near.contains(p.getUUID()) || !e.told.add(p.getUUID())) continue;
            send(p, e.message());
        }
    }

    /** Tells everyone told of the eruption where it is now (after a hold or a release). */
    private static void retell(ServerLevel level, Eruption e) {
        for (UUID id : e.told) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null && p.level() == level) send(p, e.message());
        }
    }

    /** An eruption ends before its time: every client in the overworld drops it. */
    private static void endEarly(Eruption e) {
        LavaNet.CHANNEL.send(PacketDistributor.DIMENSION.with(() -> Level.OVERWORLD), LavaNet.Eruption.end(e.id));
    }

    private static void send(ServerPlayer p, LavaNet.Eruption m) { LavaNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), m); }

    // ================================================================================================ the bombs

    /**
     * Throws the bombs of eruption {@code e} whose turn has come by now (its plan's ticks into the burst), at most
     * {@link BombFlight#PER_TICK} this tick; the first {@code bombsNear} of the plan try to come down near an actor. Not
     * while it is held.
     */
    private static void launch(ServerLevel level, LavaFlood.Band band, Eruption e, List<ServerPlayer> actors, long now, ERConfig cfg) {
        int t = e.into(now) - e.pre;
        if (t < 0 || t >= e.burst) return;
        for (int k = 0; k < BombFlight.PER_TICK && e.next < e.plan.length && e.plan[e.next] <= t; k++) {
            throwBomb(level, band, e, actors, e.next < cfg.eruptionBombsNear.get(), cfg);
            e.next++;
        }
    }

    /**
     * Throws one bomb of eruption {@code e} (spec 5.4.2): a near bomb when {@code near} and an actor can take one, else a
     * ring bomb, its target drawn again up to 4 times while it is not on entity-ticking ground or its flight does not
     * clear the stack. Not thrown when the vent is not on entity-ticking ground, the room over its mouth is too low for
     * the apex, or no target is found. It goes from within {@code 0.6 r} of the axis, half a block over the start row,
     * with its motion set before it is added, so the packet that brings it to the clients carries it.
     */
    private static void throwBomb(ServerLevel level, LavaFlood.Band band, Eruption e, List<ServerPlayer> actors, boolean near, ERConfig cfg) {
        Vents.Read at = e.at;
        Net.Vent v = e.vent;
        double vx = v.x, vz = v.z, launchY = at.startY() + 0.5;
        if (!level.isPositionEntityTicking(BlockPos.containing(vx, launchY, vz))) return;
        Random r = e.random;
        double a = BombFlight.apex(e.strength, at.room(), r.nextDouble());
        if (Double.isNaN(a)) return;
        double ang = r.nextDouble() * Math.PI * 2, off = 0.6 * v.r * Math.sqrt(r.nextDouble());
        double ox = off * Math.cos(ang), oz = off * Math.sin(ang);
        // the launch point itself must be on entity-ticking ground too: up to 1.8 blocks off the axis it can lie in the
        // next chunk, and a bomb added there would never fly (the sweep would fizzle it at the mouth)
        if (!level.isPositionEntityTicking(BlockPos.containing(vx + ox, launchY, vz + oz))) return;
        double clearY = at.mouthY() + BombFlight.CLEAR_ROWS - launchY;
        int mouth = at.mouthY(), orElse = band.tableY() + 9;
        // the ground under a target: the first cell down from the mouth's row that is not open or holds lava, its top
        BombFlight.Drop drop = (tx, tz) -> launchY - (Vents.ground(level, Mth.floor(vx + tx), Mth.floor(vz + tz), mouth, orElse) + 1);
        BombFlight.Ticking ticking = (tx, tz) -> level.isPositionEntityTicking(BlockPos.containing(vx + tx, mouth, vz + tz));
        BombFlight.Flight f = null;
        boolean wasNear = false;
        if (near) {
            List<ServerPlayer> eligible = eligible(level, v, actors);
            if (!eligible.isEmpty()) {
                ServerPlayer p = eligible.get(r.nextInt(eligible.size()));
                int lo = cfg.eruptionNearMinBlocks.get(), hi = Math.max(lo, cfg.eruptionNearMaxBlocks.get());
                f = BombFlight.near(ox, oz, p.getX() - vx, p.getZ() - vz, lo, hi, v.stackR, drop, ticking, a, clearY, r);
                wasNear = f != null;
            }
        }
        for (int i = 0; f == null && i < BombFlight.RING_DRAWS; i++) {
            double ta = r.nextDouble() * Math.PI * 2, d = BombFlight.ringDistance(v.stackR, r.nextDouble());
            double tx = d * Math.cos(ta), tz = d * Math.sin(ta);
            if (!ticking.at(tx, tz)) continue;
            BombFlight.Flight g = BombFlight.aim(ox, oz, tx, tz, drop, a, v.stackR, clearY);
            if (g != null && ticking.at(g.tx(), g.tz())) f = g;
        }
        if (f == null) return;
        LavaBomb b = EREntities.LAVA_BOMB.get().create(level);
        if (b == null) return;
        b.setPos(vx + ox, launchY, vz + oz);
        b.setDeltaMovement(f.vx(), f.vy(), f.vz());
        b.seed(r.nextLong());
        if (!level.addFreshEntity(b)) return;
        BOMBS.put(b, e);
        e.bombs++;
        if (wasNear) e.near++;
    }

    /**
     * The actors a near bomb may come down by: alive in this level, on the ground, within {@link BombFlight#NEAR_REACH}
     * blocks across of the vent, and not on a refuge ({@link Vents#refuge}).
     */
    private static List<ServerPlayer> eligible(ServerLevel level, Net.Vent v, List<ServerPlayer> actors) {
        List<ServerPlayer> out = new ArrayList<>();
        double r2 = BombFlight.NEAR_REACH * BombFlight.NEAR_REACH;
        for (ServerPlayer p : actors) {
            double dx = p.getX() - v.x, dz = p.getZ() - v.z;
            if (dx * dx + dz * dz > r2 || p.level() != level || !p.isAlive() || !p.onGround()) continue;
            if (!Vents.refuge(level, p.blockPosition())) out.add(p);
        }
        return out;
    }

    /** Every tick: bombs gone are forgotten, and one no longer on entity-ticking ground fizzles (gate 6 of 3.1). */
    private static void sweep(ServerLevel level) {
        for (Iterator<LavaBomb> it = BOMBS.keySet().iterator(); it.hasNext(); ) {
            LavaBomb b = it.next();
            if (b.isRemoved() || b.level() != level) {
                it.remove();
                continue;
            }
            if (level.isPositionEntityTicking(b.blockPosition())) continue;
            b.letGo();
            b.fizzle();
            it.remove();
        }
        DROP_ROCKS.removeIf(f -> f.isRemoved() || f.level() != level);
        if (dropsHeld && !dropsInAir()) dropsHeld = false;
    }

    /**
     * Through the burst, every {@link #BURN_EVERY} ticks (spec 5.3): the living things whose box lies within the crater's
     * radius and half a block of the axis and within {@link #BURN_ROWS} rows over the crater floor are set on fire for 5
     * s and take {@code ventBurn} as lava's damage. Standing in a vent's mouth burns.
     */
    private static void burn(ServerLevel level, Eruption e, long now, ERConfig cfg) {
        int t = e.into(now) - e.pre;
        if (t < 0 || t >= e.burst || t % BURN_EVERY != 0) return;
        double x = e.vent.x, z = e.vent.z, reach = e.vent.r + BURN_OVER, floor = e.at.top() + 1;
        AABB box = new AABB(x - reach, floor, z - reach, x + reach, floor + BURN_ROWS, z + reach);
        int damage = cfg.eruptionVentBurn.get();
        for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, box, EntitySelector.NO_SPECTATORS)) {
            AABB b = l.getBoundingBox();
            double dx = Mth.clamp(x, b.minX, b.maxX) - x, dz = Mth.clamp(z, b.minZ, b.maxZ) - z;
            if (dx * dx + dz * dz > reach * reach) continue;
            l.setSecondsOnFire(5);
            if (damage > 0) l.hurt(level.damageSources().lava(), damage);
        }
    }

    /** The eruption that threw bomb {@code b}, or null (one a command dropped, or one not tracked). */
    @Nullable
    static Eruption eruptionOf(LavaBomb b) { return BOMBS.get(b); }

    /** Bomb {@code b}'s blast took {@code blocks} blocks and {@code glow} of the glow: counted for the status. */
    static void cratered(LavaBomb b, int blocks, int glow) {
        Eruption e = BOMBS.get(b);
        if (e == null) return;
        e.craters++;
        e.craterBlocks += blocks;
        e.glow += glow;
    }

    /**
     * Who holds {@code rock}, a rock eruption {@code e}'s crater threw out: it hangs still while that eruption runs held
     * by command; one of a command's bomb ({@code e} null) is kept with the drops while it is in the air, and hangs still
     * while they are held.
     */
    static Falling.Owner ejectaOwner(@Nullable Eruption e, FallingBlockEntity rock) {
        if (e == null) DROP_ROCKS.add(rock);
        return new Falling.Owner() {
            @Override public boolean holds(FallingBlockEntity f) { return e == null ? dropsHeld : e.held >= 0 && RUNNING.contains(e); }
            @Override public void landed(FallingBlockEntity f, BlockPos at) { DROP_ROCKS.remove(f); }
            @Override public void shattered(FallingBlockEntity f, BlockPos at) { DROP_ROCKS.remove(f); }
            @Override public void gone(FallingBlockEntity f) { DROP_ROCKS.remove(f); }
        };
    }

    /** True while a bomb a command dropped, or a rock its crater threw, is in the air. */
    private static boolean dropsInAir() { return BOMBS.containsValue(null) || !DROP_ROCKS.isEmpty(); }

    /**
     * True while bomb {@code b} is to hang still in the air: the eruption that threw it is held by command, or, for one a
     * command dropped, the drops are.
     */
    public static boolean holds(LavaBomb b) {
        if (!BOMBS.containsKey(b)) return false;
        Eruption e = BOMBS.get(b);
        return e == null ? dropsHeld : e.held >= 0;
    }

    /** Bomb {@code b} came down in the lava (or another fluid) and fizzles there: counted for the status. */
    public static void intoLava(LavaBomb b) {
        Eruption e = BOMBS.get(b);
        if (e != null) e.intoLava++;
    }

    /** Before the save (spec 6.6): every bomb is let go of any hold and discarded, so none is saved frozen. */
    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        for (LavaBomb b : BOMBS.keySet()) {
            b.letGo();
            b.discard();
        }
        BOMBS.clear();
    }

    /** True while an eruption, or the drops, are held by command (no quake or eruption starts on its own then). */
    public static boolean held() {
        if (dropsHeld) return true;
        for (Eruption e : RUNNING) if (e.held >= 0) return true;
        return false;
    }

    /** How many eruptions warn or burst now. */
    private static int active(long now) {
        int n = 0;
        for (Eruption e : RUNNING) if (e.active(now)) n++;
        return n;
    }

    /** True while an eruption of Caldera {@code c} warns or bursts. */
    private static boolean busy(Caldera c, long now) {
        for (Eruption e : RUNNING) if (e.caldera == c && e.active(now)) return true;
        return false;
    }

    /**
     * Gate 1: every eruption ends now (its end sent to the clients), every bomb in the air fizzles, and the clocks and
     * Calderas are forgotten.
     */
    private static void stop() {
        for (Eruption e : RUNNING) endEarly(e);
        RUNNING.clear();
        for (LavaBomb b : BOMBS.keySet()) {
            b.letGo();
            b.fizzle();
        }
        BOMBS.clear();
        dropsHeld = false;
        DROP_ROCKS.clear();
        CALDERAS.clear();
        schedule = null;
        lastPhase = -1;
    }

    /** When the server stops: forgets everything (the next world may differ). */
    public static void reset() {
        schedule = null;
        CALDERAS.clear();
        MEMO.clear();
        memoRange = -1;
        RUNNING.clear();
        BOMBS.clear();
        dropsHeld = false;
        DROP_ROCKS.clear();
        nextId = 1;
        lastPhase = -1;
    }

    private static EruptionSchedule schedule(ServerLevel level) {
        if (schedule == null) schedule = new EruptionSchedule(level.getRandom().nextLong());
        return schedule;
    }

    /** The config's numbers for the schedule. */
    private static EruptionSchedule.Settings settings(ERConfig cfg) {
        return new EruptionSchedule.Settings(cfg.eruptionMinMinutes.get(), cfg.eruptionMaxMinutes.get(), cfg.eruptionRisingMinMinutes.get(),
                cfg.eruptionRisingMaxMinutes.get(), cfg.eruptionWakeSeconds.get(), cfg.eruptionWarnSeconds.get(), cfg.eruptionBurstSeconds.get(),
                cfg.eruptionVentNearBias.get(), cfg.eruptionRisingScale.get());
    }

    private static long key(Net.Vent v) { return ChunkPos.asLong(Mth.floor(v.x), Mth.floor(v.z)); }

    // ================================================================================================ the command

    /**
     * {@code /remix erupt}: the Calderas near here, when each erupts next and what its last eruption did, and where their
     * vents stand; {@code now [<pos>] [strength] [seed]} starts the vent nearest the position (the source's by default)
     * at once, even while the lava clock is held, a seed making it repeatable; {@code hold [ticks]} holds the running
     * eruption nearest the source that many ticks in (the burst's start if left out), for pictures, and {@code release}
     * lets it go on; {@code bomb <pos> [seed]} drops one bomb onto a position from 12 blocks over it, eruption or not, for
     * the blasts' checks. With no eruption running, {@code hold} holds the bombs a command dropped and the rocks in the
     * air instead, and {@code release} lets them go.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("erupt").executes(Eruptions::status)
                .then(Commands.literal("now").executes(c -> now(c, false, -1, null))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> now(c, true, -1, null))
                                .then(Commands.argument("strength", FloatArgumentType.floatArg(0.1F, 2F))
                                        .executes(c -> now(c, true, FloatArgumentType.getFloat(c, "strength"), null))
                                        .then(Commands.argument("seed", IntegerArgumentType.integer())
                                                .executes(c -> now(c, true, FloatArgumentType.getFloat(c, "strength"), (long) IntegerArgumentType.getInteger(c, "seed")))))))
                .then(Commands.literal("hold").executes(c -> hold(c, -1))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0)).executes(c -> hold(c, IntegerArgumentType.getInteger(c, "ticks")))))
                .then(Commands.literal("release").executes(Eruptions::release))
                .then(Commands.literal("bomb").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> bomb(c, null))
                        .then(Commands.argument("seed", IntegerArgumentType.integer())
                                .executes(c -> bomb(c, (long) IntegerArgumentType.getInteger(c, "seed"))))));
    }

    /** The overworld with its Galleries, or null after telling the source why there are no eruptions. */
    @Nullable
    private static ServerLevel galleries(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().getLevel(Level.OVERWORLD);
        if (level == null || !DeepWorld.installed() || LavaFlood.band() == null || DeepWorld.terrain() == null) {
            c.getSource().sendFailure(Component.literal("No eruptions in this world: it has no Basalt Galleries (see the server log)."));
            return null;
        }
        if (!ERConfig.INSTANCE.eruptionsEnabled.get()) {
            c.getSource().sendFailure(Component.literal("No eruptions: they are turned off in explorationremix-world.toml (eruptions.enabled)."));
            return null;
        }
        return level;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        ERConfig cfg = ERConfig.INSTANCE;
        long now = level.getGameTime();
        int range = cfg.eruptionRange.get();
        Vec3 here = c.getSource().getPosition();
        Terrain t = DeepWorld.terrain();
        LavaFlood.Band band = LavaFlood.band();
        // the vents of every Caldera with one within range of here (a patch is at most about 210 blocks across)
        List<Net.Vent> sites = new ArrayList<>(t.ventSitesNear(Mth.floor(here.x), Mth.floor(here.z), range + 220));
        Map<Integer, List<Net.Vent>> byChamber = new LinkedHashMap<>();
        for (Net.Vent v : sites) byChamber.computeIfAbsent(v.chamber, k -> new ArrayList<>()).add(v);
        List<Map.Entry<Integer, List<Net.Vent>>> near = new ArrayList<>();
        for (Map.Entry<Integer, List<Net.Vent>> en : byChamber.entrySet()) if (nearest(en.getValue(), here) <= range) near.add(en);
        near.sort(Comparator.comparingDouble(en -> nearest(en.getValue(), here)));
        int phase = lastPhase < 0 ? EruptionSchedule.CALM : lastPhase;
        EruptionSchedule.Settings s = settings(cfg);
        String held = held() ? ", held" : LavaFlood.held() ? ", the lava clock is held so none start" : "";
        double lo = EruptionSchedule.rising(phase) ? s.risingMinMinutes() : s.minMinutes();
        double hi = Math.max(lo, EruptionSchedule.rising(phase) ? s.risingMaxMinutes() : s.maxMinutes());
        String every = EruptionSchedule.allows(phase, s)
                ? String.format(Locale.ROOT, ", so each erupts every %s to %s minutes", num(lo), num(hi))
                : "; none while the lava is " + phaseWord(phase);
        say(c, String.format(Locale.ROOT, "Eruptions%s: %d Calderas within %d blocks of here; the lava is %s%s", held, near.size(), range,
                phaseWord(phase), every));
        for (Map.Entry<Integer, List<Net.Vent>> en : near) {
            Caldera cd = CALDERAS.get(en.getKey());
            int reach = 0;
            for (Net.Vent v : en.getValue()) if (dist(v, here) <= range) reach++;
            String next = cd == null ? "asleep, with nobody near enough to wake it"
                    : busy(cd, now) ? "erupting now" : cd.clock.nextAt() == EruptionSchedule.NEVER ? "no eruption due"
                    : "the next eruption in " + time(cd.clock.nextAt() - now);
            Eruption l = cd == null ? null : cd.last;
            say(c, String.format(Locale.ROOT, "  Caldera %d (%d vents, %d within reach): %s; %s", en.getKey(), en.getValue().size(),
                    reach, next, l == null ? "no eruption yet" : lastLine(l, now)));
            List<Net.Vent> vs = new ArrayList<>(en.getValue());
            vs.sort(Comparator.comparingDouble(v -> dist(v, here)));
            StringBuilder b = new StringBuilder("  vents:");
            for (int i = 0; i < Math.min(8, vs.size()); i++) {
                Net.Vent v = vs.get(i);
                Vents.Read r = band == null ? null : Vents.read(level, band, v);
                b.append(i == 0 ? " " : ", ").append(Mth.floor(v.x)).append(' ').append(r == null ? "?" : String.valueOf(r.top())).append(' ')
                        .append(Mth.floor(v.z)).append(" (").append(Math.round(dist(v, here))).append(" blocks)");
            }
            say(c, b.toString());
        }
        say(c, String.format(Locale.ROOT, "  bombs in flight: %d", BOMBS.size()));
        return 1;
    }

    /** The status's account of eruption {@code l}. */
    private static String lastLine(Eruption l, long now) {
        return String.format(Locale.ROOT, "the last %s ago from the vent at %d %d %d, %d bombs (%d near a player): %d craters (%d blocks, %d glow), %d into the lava",
                time(now - l.start), Mth.floor(l.vent.x), l.at.top(), Mth.floor(l.vent.z), l.bombs, l.near, l.craters, l.craterBlocks, l.glow, l.intoLava);
    }

    private static double nearest(List<Net.Vent> vs, Vec3 here) {
        double best = Double.POSITIVE_INFINITY;
        for (Net.Vent v : vs) best = Math.min(best, dist(v, here));
        return best;
    }

    private static double dist(Net.Vent v, Vec3 here) {
        double dx = v.x - here.x, dz = v.z - here.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int now(CommandContext<CommandSourceStack> c, boolean withPos, float strength, @Nullable Long seed) throws CommandSyntaxException {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        LavaFlood.Band band = LavaFlood.band();
        ERConfig cfg = ERConfig.INSTANCE;
        int range = cfg.eruptionRange.get();
        long now = level.getGameTime();
        if (active(now) >= MAX_RUNNING) {
            c.getSource().sendFailure(Component.literal("There are already 3 eruptions running."));
            return 0;
        }
        Vec3 from = withPos ? Vec3.atCenterOf(BlockPosArgument.getBlockPos(c, "pos")) : c.getSource().getPosition();
        List<Net.Vent> vs = new ArrayList<>(DeepWorld.terrain().ventSitesNear(Mth.floor(from.x), Mth.floor(from.z), range));
        vs.removeIf(v -> dist(v, from) > range);
        vs.sort(Comparator.comparingDouble(v -> dist(v, from)));
        Net.Vent v = null;
        Vents.Read at = null;
        for (Net.Vent cand : vs) {
            at = Vents.read(level, band, cand);
            if (at != null) {
                v = cand;
                break;
            }
        }
        if (v == null) {
            c.getSource().sendFailure(Component.literal(String.format(Locale.ROOT, "There is no Caldera vent within %d blocks of here.", range)));
            return 0;
        }
        EruptionSchedule sched = schedule(level);
        EruptionSchedule.Settings s = settings(cfg);
        int phase = lastPhase < 0 ? EruptionSchedule.CALM : lastPhase;
        Caldera cd = caldera(sched, v, now, phase, s);
        // the vent's Caldera erupts once at a time: a command's eruption takes the place of one running there
        for (Iterator<Eruption> it = RUNNING.iterator(); it.hasNext(); ) {
            Eruption e = it.next();
            if (e.caldera != cd) continue;
            // its bombs in the air are let go of its hold, or they would hang there for good
            e.held = -1;
            e.holdAt = -1;
            endEarly(e);
            it.remove();
        }
        List<ServerPlayer> watchers = NearChunks.players(level, band);
        Eruption e = begin(level, cd, v, at, now, strength > 0 ? strength : sched.drawStrength(), seed != null ? seed : sched.drawSeed(), sched, s,
                phase, watchers);
        String show = e.plan.length == 0 ? ""
                : !level.isPositionEntityTicking(BlockPos.containing(v.x, at.startY(), v.z)) ? ", a show only: no ticking ground within reach of its bombs"
                : at.room() - BombFlight.ROOM_OVER < BombFlight.APEX_MIN ? ", a show only: too little room over the vent for its bombs" : "";
        say(c, String.format(Locale.ROOT, "The vent at %d %d %d erupts: %s of warning, then a %s burst at strength %.2f (%d bombs%s).",
                at.x(), at.top(), at.z(), secs(e.pre), secs(e.burst), e.strength, e.plan.length, show));
        return 1;
    }

    private static int hold(CommandContext<CommandSourceStack> c, int ticks) {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        Eruption e = nearestRunning(c.getSource().getPosition(), level.getGameTime());
        if (e == null) {
            // no eruption runs: the bombs a command dropped, and the rocks in the air, are held instead
            if (!dropsInAir()) {
                c.getSource().sendFailure(Component.literal("There is no eruption to hold."));
                return 0;
            }
            dropsHeld = true;
            say(c, "The dropped bombs and the rocks in the air are held.");
            return 1;
        }
        long now = level.getGameTime();
        int at = Math.min(ticks < 0 ? e.pre : ticks, Math.max(0, e.pre + e.burst - 1));
        if (e.held < 0 && at > e.into(now)) {
            e.holdAt = at;
            say(c, String.format(Locale.ROOT, "The eruption will be held %d ticks in (it is %d in now).", at, e.into(now)));
            return 1;
        }
        e.held = at;
        e.holdAt = -1;
        retell(level, e);
        say(c, String.format(Locale.ROOT, "The eruption is held %d ticks in.", e.held));
        return 1;
    }

    private static int release(CommandContext<CommandSourceStack> c) {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        Eruption e = null;
        for (Eruption r : RUNNING) if (r.held >= 0 || r.holdAt >= 0) e = r;
        if (e == null) {
            if (dropsHeld) {
                dropsHeld = false;
                say(c, "The dropped bombs and their rocks go on.");
                return 1;
            }
            c.getSource().sendFailure(Component.literal("There is no held eruption."));
            return 0;
        }
        e.holdAt = -1;
        if (e.held >= 0) {
            e.start = level.getGameTime() - e.held;
            e.held = -1;
            retell(level, e);
        }
        say(c, "The eruption goes on.");
        return 1;
    }

    /**
     * One bomb dropped onto {@code pos} from {@link #DROP_FROM} blocks over it at 0.6 blocks a tick (spec 5.6), bursting
     * by the rules of every bomb; a seed repeats its blast's draws. Under a lower roof it drops from the highest open
     * cell under the roof instead, so it never starts inside the rock and bursts there. Refused where the ground is not
     * entity-ticking.
     */
    private static int bomb(CommandContext<CommandSourceStack> c, @Nullable Long seed) throws CommandSyntaxException {
        ServerLevel level = galleries(c);
        if (level == null) return 0;
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        LevelChunk ch = level.isPositionEntityTicking(pos) ? level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) : null;
        if (ch == null) {
            c.getSource().sendFailure(Component.literal(String.format(Locale.ROOT, "There is no ticking ground at %d %d %d for a bomb.",
                    pos.getX(), pos.getY(), pos.getZ())));
            return 0;
        }
        // the open cells over the target, up to the drop's height: it starts at the foot of the highest of them, or with
        // none, in the target's own cell when that is open (else just over it, where it bursts at once)
        int up = 0;
        while (up < DROP_FROM) {
            BlockPos p = pos.above(up + 1);
            if (!Vents.open(ch, p, ch.getBlockState(p))) break;
            up++;
        }
        if (up == 0 && !Vents.open(ch, pos, ch.getBlockState(pos))) up = 1;
        LavaBomb b = EREntities.LAVA_BOMB.get().create(level);
        if (b == null) return 0;
        b.setPos(pos.getX() + 0.5, pos.getY() + up, pos.getZ() + 0.5);
        b.setDeltaMovement(0, -0.6, 0);
        if (seed != null) b.seed(seed);
        if (!level.addFreshEntity(b)) return 0;
        BOMBS.put(b, null);
        say(c, String.format(Locale.ROOT, "A lava bomb falls on %d %d %d.", pos.getX(), pos.getY(), pos.getZ()));
        return 1;
    }

    /** The running eruption (warning or bursting) nearest {@code p}, or null. */
    @Nullable
    private static Eruption nearestRunning(Vec3 p, long now) {
        Eruption best = null;
        double bd = Double.POSITIVE_INFINITY;
        for (Eruption e : RUNNING) {
            if (!e.active(now)) continue;
            double dx = e.x() - p.x, dz = e.z() - p.z, d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static String phaseWord(int phase) {
        return switch (phase) {
            case EruptionSchedule.WARNING -> "warning";
            case EruptionSchedule.RISE -> "rising";
            case EruptionSchedule.FULL -> "full";
            case EruptionSchedule.DRAIN -> "draining";
            default -> "calm";
        };
    }

    private static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    /** Ticks as seconds, with a tenth. */
    private static String secs(int ticks) { return String.format(Locale.ROOT, "%.1f s", ticks / 20.0); }

    /** Ticks as minutes and seconds (0:00 when due). */
    private static String time(long ticks) {
        long s = Math.max(0, ticks) / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
