package com.explorationremix.world.lava;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Basalt Galleries' terrain reset on the server (0.5.1): once a cycle, as the lava stands full, everything changed
 * in the Galleries since the last reset (by players, quakes and eruptions; recorded in each chunk's {@link ChangeLog}
 * by {@link GalleryChanges}) is put back as it was generated. Runs every overworld tick after the flood, while the
 * reset and the lava cycle are on in the config.
 *
 * <p><b>Where and when.</b> Only the loaded chunks round the players in or near the Galleries are put back, the way the
 * flood walks them ({@link NearChunks}), nearest first and within a budget of blocks a tick (config, halved while the
 * server is behind). A chunk that missed a reset (nobody was near) holds its old changes until a player comes near, and
 * then catches up in whatever phase the cycle is in. A change counts as old when it was recorded before the reset's
 * generation ({@link LavaFlood#resetGen}), which goes up once a cycle as the lava stands full; changes made while it
 * stands full wait for the next cycle. The chunks are looked over again when the generation goes up and every
 * {@link #IDLE_SCAN} ticks; with nothing old round the players that costs one check a chunk.
 *
 * <p><b>Writing.</b> A block goes back with {@code setBlock} and flags 2 | 16: sent to the players watching, no
 * neighbour or shape updates (so a torch on a placed block is not popped off as an item before its own entry puts it
 * back, and nothing is set off round it), but the old block's {@code onRemove} still runs (a chest drops what it held).
 * Recording is off while it writes. Under the lava sea ({@link Restore}) an open block comes back as the sea itself, a
 * lava source with its bits set in the chunk's flood masks, so the drain later takes it away again; everywhere else the
 * block comes back as it was generated, and a fluid next to it above the sea gets a tick so lava falls flow again round
 * rock that came back. A block where a player stands (inside a player's box grown a block down, so also the block under
 * their feet) is left for a later look, so nobody is shut in rock or has the floor taken from under them at once.
 */
public final class TerrainReset {
    private TerrainReset() {}

    /** How often, with nothing to do, the chunks round the players are looked over again, in ticks. */
    static final int IDLE_SCAN = 40;
    /** Written silently: sent to clients, with no neighbour or shape updates (see the class comment). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final Direction[] DIRECTIONS = Direction.values();

    // ------------------------------------------------------------------------------------------- state of this server
    private static long lastGen = -1;
    /** Work was left over last tick for want of budget. */
    private static boolean busy;
    /** As of the last look: chunks round the players, those with old changes left, changes remembered and old ones, and old ones held back where players stand. */
    private static int nearChunks, pendingChunks, heldBack;
    private static long remembered, old;
    private static final Run RUN = new Run();

    /** Every overworld tick, after the flood ({@code LavaFlood.tick}). */
    public static void tick(ServerLevel level, LavaFlood.Band band, LavaCycle.State state, long now) {
        ERConfig cfg = ERConfig.INSTANCE;
        int bottom = GalleryChanges.bottom();
        if (!cfg.resetEnabled.get() || !cfg.lavaCycleEnabled.get() || bottom == Integer.MIN_VALUE) {
            idle(now);
            return;
        }
        long gen = LavaFlood.resetGen(level);
        boolean moved = gen != lastGen;
        lastGen = gen;
        if (!busy && !moved && now % IDLE_SCAN != 0) return;
        List<ServerPlayer> near = NearChunks.players(level, band);
        if (near.isEmpty()) {
            idle(now);
            return;
        }
        int max = cfg.resetChangesPerTick.get();
        if (level.getServer().getAverageTickTime() > 40) max = Math.max(64, max / 2);
        long t0 = System.nanoTime();
        Pass p = new Pass(level, band, bottom, (int) Math.min(Integer.MAX_VALUE, gen), max, near);
        NearChunks.walk(level, near, NearChunks.radius(level), now, p::visit);
        nearChunks = p.chunks;
        pendingChunks = p.stale;
        heldBack = p.held;
        remembered = p.entries;
        old = p.old;
        // with budget left, what is still old is held back where players stand: look again on the next idle scan
        busy = p.spent && p.stale > 0;
        if (p.restored > 0) RUN.work(now, System.nanoTime() - t0, p.restored, p.written);
        if (!busy) RUN.end(now, p.held);
    }

    private static void idle(long now) {
        busy = false;
        nearChunks = pendingChunks = heldBack = 0;
        remembered = old = 0;
        RUN.end(now, 0);
    }

    /** One look over the chunks round the players: puts back what it can within the budget, and counts the rest. */
    private static final class Pass {
        final ServerLevel level;
        final LavaFlood.Band band;
        final int bottom, gen, max;
        final List<AABB> keepClear = new ArrayList<>();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), side = new BlockPos.MutableBlockPos();
        int chunks, stale, held, written, restored;
        long entries, old;
        boolean spent;

        Pass(ServerLevel level, LavaFlood.Band band, int bottom, int gen, int max, List<ServerPlayer> near) {
            this.level = level;
            this.band = band;
            this.bottom = bottom;
            this.gen = gen;
            this.max = max;
            for (ServerPlayer p : near) {
                AABB b = p.getBoundingBox();
                keepClear.add(new AABB(b.minX, b.minY - 1, b.minZ, b.maxX, b.maxY, b.maxZ));
            }
        }

        boolean visit(LevelChunk chunk) {
            LavaChunk d = LavaChunk.of(chunk);
            if (d == null) return true;
            chunks++;
            ChangeLog log = d.log;
            if (log.isEmpty()) return true;
            log.align(bottom);
            entries += log.size();
            if (!log.hasOlderThan(gen)) return true;
            if (!spent) restore(chunk, d);
            int left = log.countOlderThan(gen);
            old += left;
            if (left > 0) stale++;
            return true;
        }

        /** Puts back the chunk's old changes, lowest first, until the budget is spent. */
        private void restore(LevelChunk chunk, LavaChunk d) {
            ChangeLog log = d.log;
            d.align(band.tableY());
            int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
            boolean changed = false;
            for (int key : log.olderThan(gen)) {
                if (written >= max) {
                    spent = true;
                    break;
                }
                int lx = ChangeLog.x(key), lz = ChangeLog.z(key), y = bottom + ChangeLog.dy(key);
                int x = x0 + lx, z = z0 + lz;
                if (playerAt(x, y, z)) {
                    held++;
                    continue;
                }
                BlockState original = Block.stateById(log.state(key));
                int i = y - band.tableY();
                int slice = i >= 0 && i < band.rise() ? i : -1;
                int what = Restore.decide(LavaFlood.kindOf(original), slice, d.level);
                BlockState put = what == Restore.ORIGINAL ? original : LAVA;
                pos.set(x, y, z);
                if (chunk.getBlockState(pos) != put) {
                    GalleryChanges.setQuietly(level, pos, put, FLAGS);
                    written++;
                }
                Restore.mark(what, d.mask, d.glow, slice, lx, lz);
                log.remove(key);
                restored++;
                changed = true;
                // above the sea: lava falls and pools round the block flow again (under it, the sea's sources need none)
                if (slice < 0 || slice >= d.level) wakeFluids(chunk, x, y, z);
            }
            if (changed) chunk.setUnsaved(true);
        }

        private boolean playerAt(int x, int y, int z) {
            for (AABB b : keepClear) if (b.intersects(x, y, z, x + 1, y + 1, z + 1)) return true;
            return false;
        }

        /** Gives the fluid at (x, y, z) and at its six neighbours a tick, in loaded chunks only. */
        private void wakeFluids(LevelChunk chunk, int x, int y, int z) {
            tickFluid(chunk, side.set(x, y, z));
            for (Direction dir : DIRECTIONS) {
                side.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                LevelChunk c = (side.getX() >> 4) == chunk.getPos().x && (side.getZ() >> 4) == chunk.getPos().z
                        ? chunk : level.getChunkSource().getChunkNow(side.getX() >> 4, side.getZ() >> 4);
                if (c != null) tickFluid(c, side);
            }
        }

        private void tickFluid(LevelChunk c, BlockPos at) {
            if (level.isOutsideBuildHeight(at)) return;
            FluidState f = c.getFluidState(at);
            if (!f.isEmpty()) level.scheduleTick(at.immutable(), f.getType(), f.getType().getTickDelay(level));
        }
    }

    /** One run of the reset round the players, from its first block put back until nothing is left to do: for the log and the status. */
    private static final class Run {
        boolean active;
        long start, nanos, maxNanos, restored, written;
        int ticks;
        /** The last run that ended, for the status: blocks put back, ticks with work, average and most ms, ticks it took. */
        long lastRestored, lastSpan;
        int lastTicks;
        double lastAvgMs, lastMaxMs;

        void work(long now, long ns, int restoredNow, int writtenNow) {
            if (!active) {
                active = true;
                start = now;
                nanos = maxNanos = restored = written = 0;
                ticks = 0;
            }
            ticks++;
            nanos += ns;
            maxNanos = Math.max(maxNanos, ns);
            restored += restoredNow;
            written += writtenNow;
        }

        void end(long now, int held) {
            if (!active) return;
            active = false;
            lastRestored = restored;
            lastTicks = ticks;
            lastSpan = now - start + 1;
            lastAvgMs = ticks == 0 ? 0 : nanos / 1e6 / ticks;
            lastMaxMs = maxNanos / 1e6;
            ExplorationRemix.LOGGER.info("Terrain reset: {} changed blocks put back ({} written) in {} ticks ({} with work: {} ms average, {} ms at most){}",
                    restored, written, lastSpan, ticks, String.format(Locale.ROOT, "%.2f", lastAvgMs), String.format(Locale.ROOT, "%.2f", lastMaxMs),
                    held > 0 ? "; " + held + " held back where players stand, put back once they move" : "");
        }

        void clear() {
            active = false;
            lastRestored = lastSpan = 0;
            lastTicks = 0;
            lastAvgMs = lastMaxMs = 0;
        }
    }

    // ================================================================================================ the command

    /**
     * {@code /remix reset}: the reset's generation, when the next reset comes, and what the chunks round the players in
     * or near the Galleries remember; {@code /remix reset now} puts everything changed so far back at once.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("reset").executes(TerrainReset::status)
                .then(Commands.literal("now").executes(TerrainReset::now));
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        if (level == null) return 0;
        LavaClock clock = LavaFlood.clock(level);
        LavaFlood.Band b = LavaFlood.band();
        if (clock == null || b == null) {
            c.getSource().sendFailure(Component.literal("No terrain reset in this world: it has no Basalt Galleries with a lava table (see the server log)."));
            return 0;
        }
        ERConfig cfg = ERConfig.INSTANCE;
        String off = !cfg.resetEnabled.get() ? " (turned off in explorationremix-world.toml: terrainReset.enabled)"
                : !cfg.lavaCycleEnabled.get() ? " (off with the lava cycle, which is turned off in explorationremix-world.toml)" : "";
        LavaCycle cycle = clock.cycle(b.rise());
        int t = clock.tick(level.getGameTime());
        int full = cycle.tickOf(LavaCycle.Phase.FULL, 0);
        boolean doneThisCycle = LavaFlood.resetCycle() == clock.cycles();
        String next;
        if (!doneThisCycle && t >= full) next = "now";
        else next = "in " + LavaFlood.minutes(!doneThisCycle ? full - t : cycle.length() - t + full) + (clock.frozen() ? " once the clock runs again" : "");
        say(c, String.format(Locale.ROOT, "Terrain reset%s: generation %d; the next one comes when the lava stands full, %s",
                off, LavaFlood.resetGen(level), next));
        say(c, String.format(Locale.ROOT, "  round the players in or near the Galleries: %d chunks remember %d changed blocks, %d of them to be put back (%d chunks)%s",
                nearChunks, remembered, old, pendingChunks, heldBack > 0 ? ", " + heldBack + " held back where players stand" : ""));
        if (RUN.active) say(c, String.format(Locale.ROOT, "  putting back now: %d blocks so far in %d ticks with work", RUN.restored, RUN.ticks));
        if (RUN.lastSpan > 0) {
            say(c, String.format(Locale.ROOT, "  last reset here: %d blocks put back in %d ticks (%d with work: %.2f ms average, %.2f ms at most)",
                    RUN.lastRestored, RUN.lastSpan, RUN.lastTicks, RUN.lastAvgMs, RUN.lastMaxMs));
        }
        Vec3 p = c.getSource().getPosition();
        LevelChunk here = level.getChunkSource().getChunkNow((int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4);
        LavaChunk d = here == null ? null : LavaChunk.of(here);
        if (d != null) say(c, String.format(Locale.ROOT, "  this chunk remembers %d changed blocks", d.log.size()));
        return 1;
    }

    private static int now(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        if (level == null) return 0;
        ERConfig cfg = ERConfig.INSTANCE;
        if (!cfg.resetEnabled.get() || !cfg.lavaCycleEnabled.get()) {
            c.getSource().sendFailure(Component.literal("No terrain reset: " + (!cfg.resetEnabled.get()
                    ? "it is turned off in explorationremix-world.toml (terrainReset.enabled)."
                    : "it runs with the lava cycle, which is turned off in explorationremix-world.toml (lavaCycle.enabled).")));
            return 0;
        }
        long gen = LavaFlood.resetNow(level);
        if (gen < 0) {
            c.getSource().sendFailure(Component.literal("No terrain reset in this world: it has no Basalt Galleries with a lava table (see the server log)."));
            return 0;
        }
        say(c, "Terrain reset: everything changed in the Galleries so far goes back now, round the players in or near them (generation " + gen + ")");
        return 1;
    }

    private static ServerLevel overworld(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().getLevel(Level.OVERWORLD);
        if (level == null || !DeepWorld.installed()) {
            c.getSource().sendFailure(Component.literal("No deep world in this world, so no terrain reset (see the server log)."));
            return null;
        }
        return level;
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    /** When the server stops. */
    public static void reset() {
        lastGen = -1;
        busy = false;
        nearChunks = pendingChunks = heldBack = 0;
        remembered = old = 0;
        RUN.clear();
        GalleryChanges.reset();
    }
}
