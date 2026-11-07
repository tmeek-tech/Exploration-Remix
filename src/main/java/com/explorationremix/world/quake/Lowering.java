package com.explorationremix.world.quake;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * A build a player stands on comes down under them (0.9.0, spec 4.3.5): the pass does not topple the column under a
 * player but gives it a lowering job, which starts after the creak (or, with {@code quakes.toppleAtOnce}, the next
 * tick) and takes the column down so the player is not hurt.
 *
 * <ul>
 *     <li>A tower (the block under the player's feet stands on placed or climbing blocks) sinks: every
 *     {@code quakes.lowerTicks} ticks the job takes the block right under their feet by its way, so they drop one block
 *     onto the next and land before the next step (fall damage starts at a drop of 4). Blocks they place under
 *     themselves meanwhile go the same way.</li>
 *     <li>A bridge or an overhang (open space under the block they stand on) gives way at once: it and every falling
 *     block of its column are taken out (or given back under the lava), never dropped as falling blocks, which fall half
 *     as fast as a player and would land in their feet cell. The player falls the bridge's height.</li>
 *     <li>With {@code lowerTicks} 0 (the deadly alternative) a tower's falling blocks go at once by their ways.</li>
 * </ul>
 * A job ends when the block under the player no longer falls (the tower is down to {@code supportDepth} over rock), when
 * the player is no longer on the column (what is left of it goes back into the quake's list), after
 * {@link Support#MAX_STEPS} steps, or when toppling stops. At most {@link #MAX_JOBS} run at once, creaking ones
 * included, and a held quake's jobs wait for the release.
 */
final class Lowering {
    private Lowering() {}

    /** Most jobs at once, those still creaking included. */
    static final int MAX_JOBS = 16;
    /** Most ticks in a row a job waits for its player to land on the column (they drop or jump) before it gives up. */
    static final int MAX_WAIT = 40;
    /** How far over the column a player in the air still counts as coming down onto it. */
    private static final int OVER = 3;

    /** One column coming down under one player. */
    static final class Job {
        final UUID player;
        final int x, z;
        /** The quake that found it (it counts there and waits while it is held), or null for the {@code toppleAtOnce} check. */
        @Nullable final Quakes.Event event;
        /** The blocks the find filed under the player, top-down. */
        final LongArrayList positions;
        /** The columns this job's falling blocks went into. */
        final LongOpenHashSet spawned = new LongOpenHashSet();
        long nextStep;
        int steps, waited;
        /** Once the column gives way or goes at once: the blocks still to take, top-down; and whether they are taken out. */
        @Nullable LongArrayList going;
        boolean out;

        Job(UUID player, int x, int z, @Nullable Quakes.Event event, long start, LongArrayList positions) {
            this.player = player;
            this.x = x;
            this.z = z;
            this.event = event;
            this.nextStep = start;
            this.positions = positions;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();

    /** True when another job can start. */
    static boolean room() { return JOBS.size() < MAX_JOBS; }

    /** True when a job takes the column (x, z) down, or is about to. */
    static boolean runningAt(int x, int z) {
        for (Job j : JOBS) if (j.x == x && j.z == z) return true;
        return false;
    }

    static void add(Job j) { JOBS.add(j); }

    /** How many jobs run (or creak). */
    static int running() { return JOBS.size(); }

    /** Every job ends (toppling stops, the quakes are turned off, the server stops). */
    static void clear() { JOBS.clear(); }

    /** Every tick toppling runs: the jobs whose step is due take it, within the tick's budget. */
    static void tick(Topple.Work w) {
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job j = it.next();
            if (j.event != null && j.event.held >= 0) continue;
            if (w.now < j.nextStep) continue;
            if (!w.budget()) return;
            if (!step(w, j)) {
                end(w, j);
                it.remove();
            }
        }
    }

    /** One step of a job. Returns false when the job is over. */
    private static boolean step(Topple.Work w, Job j) {
        if (j.going != null) return takeGoing(w, j);
        ServerPlayer p = w.level.getServer().getPlayerList().getPlayer(j.player);
        if (p == null || p.isRemoved() || p.level() != w.level || p.isSpectator()) return false;
        AABB b = p.getBoundingBox();
        int ty = Support.feetBlockY(b.minY);
        BlockPos t = new BlockPos(j.x, ty, j.z);
        // standing on a block of the column: not on the open cell the last step left, where the server still sees them
        // until their client tells it they dropped (a player's moves reach it a tick or more late)
        if (!Support.standsOn(j.x, ty, j.z, b.minX, b.minY, b.minZ, b.maxX, b.maxZ) || Topple.kindAt(w.level, t) == Support.OPEN) {
            // still dropping onto the column after the last step, or jumping on it as they tower: wait for them to land
            if (over(w, j, b) && j.waited++ < MAX_WAIT) {
                j.nextStep = w.now + 1;
                return true;
            }
            return false;
        }
        j.waited = 0;
        if (!Support.lowersOn(Topple.fallsNow(w.level, t, w.depth), true, j.steps)) return false;
        int mode = Support.lowering(Topple.kindAt(w.level, t.below()), w.lowerTicks);
        if (mode == Support.SINKS) {
            int r = Topple.take(w, t, -1, false, Support.sideStart(Topple.columnStart(j.player.hashCode(), j.x, j.z), j.steps), j.spawned);
            if (r != Topple.LEFT) w.taken++;
            Topple.count(j.event, r, true);
            j.steps++;
            j.nextStep = w.now + w.lowerTicks;
            return true;
        }
        // a bridge or an overhang gives way at once, taken out rather than dropped; with lowerTicks 0 a tower goes at once
        LongArrayList going = new LongArrayList();
        BlockPos.MutableBlockPos m = t.mutable();
        for (int k = 0; k < Support.MAX_STEPS && Topple.fallsNow(w.level, m, w.depth); k++, m.move(0, -1, 0)) going.add(m.asLong());
        j.going = going;
        j.out = mode == Support.GIVES_WAY;
        return takeGoing(w, j);
    }

    /** Takes what is left of a column that gives way or goes at once, top-down, within the tick's budget. */
    private static boolean takeGoing(Topple.Work w, Job j) {
        LongArrayList going = j.going;
        int start = Topple.columnStart(j.player.hashCode(), j.x, j.z);
        while (!going.isEmpty() && w.budget()) {
            BlockPos p = BlockPos.of(going.removeLong(0));
            int r = Topple.take(w, p, -1, j.out, Support.sideStart(start, j.steps++), j.spawned);
            if (r != Topple.LEFT) w.taken++;
            Topple.count(j.event, r, true);
        }
        return !going.isEmpty();
    }

    /**
     * True when the player is over the column in the air, coming down onto it: within its cell (grown as
     * {@link Support#standsOn} grows it) and at most {@link #OVER} blocks over one of its placed blocks.
     */
    private static boolean over(Topple.Work w, Job j, AABB b) {
        if (!(b.maxX > j.x - 0.3 && b.minX < j.x + 1.3 && b.maxZ > j.z - 0.3 && b.minZ < j.z + 1.3)) return false;
        int ty = Support.feetBlockY(b.minY);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = 0; k <= OVER; k++) {
            if (Support.topples(Topple.kindAt(w.level, m.set(j.x, ty - k, j.z)))) return true;
        }
        return false;
    }

    /** A job is over: what is left of its blocks goes back into its quake's list while the quake runs. */
    private static void end(Topple.Work w, Job j) {
        Quakes.Event e = j.event;
        if (e != null && e.pass != null && Quakes.running(e)) e.pass.requeue(j.positions, w.level);
    }
}
