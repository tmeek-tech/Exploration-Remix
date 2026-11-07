package com.explorationremix.world.lava;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.GlowBlock;
import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.HallGlowFeature;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The Basalt Galleries' lava cycle on the server: one shared lava sea that rises {@link #RISE} blocks over the
 * Galleries' lava table and falls again on the layer's own clock ({@link LavaClock}, kept in the overworld's saved
 * data), never set off by players. Does nothing when the deep world is not installed.
 *
 * <p><b>Real lava, cheaply.</b> The lava is vanilla lava sources, written one slice (one Y, one chunk) at a time by the
 * rules in {@link Flood}. Only chunks within the player radius (the server's view distance by default) of a player in
 * or near the Galleries are kept up to date, nearest first, taking players in turn; every other chunk keeps the height
 * it was left at in its own record ({@link LavaChunk}, saved with the chunk) and catches up when such a player comes
 * near, so do chunks generated mid-cycle (they start at the table). Each tick spends at most a budget of block checks
 * and block changes (config), halved while the server is behind (its average tick over 40 ms); a slice once begun is
 * finished, so a chunk's record always matches its blocks.
 *
 * <p>A block is written straight into its chunk section, the way the chunk itself would write it but without what a
 * lava source would set off there: no neighbour or shape updates, no fluid tick (a sea of new sources would schedule
 * thousands of lava ticks every step), no {@code onPlace}. What the chunk would have done that still matters is done
 * by hand: the light engine re-lights the block, a section that turns empty or not is reported to it, the chunk is
 * marked unsaved, and the change goes to the players watching the chunk (one section packet per chunk per step).
 * Heightmaps and sky light sources are left alone: they never change this far under the ground.
 *
 * <p><b>Nothing left behind.</b> The drain writes back exactly what the rise took (air, the glow, and a fall's flowing
 * lava at its level, kept in the chunk's record) the same way, and wakes no lava of the terrain's own, so a cycle leaves
 * the Galleries as it found them. (Until 0.8.0 it cleared a fall's lava to air and woke the lava round it to pour it
 * again: a re-poured fall ran out over the floor at its foot, nudged the sea in a neighbour chunk that had not drained
 * yet into blocks this chunk had, where the fall then fed that lava for good, and Immersive Weathering turned flowing
 * lava on basalt into basalt.)
 *
 * <p><b>The clock</b> is the overworld's game time less the start of the current cycle, so every player on a server
 * sees the same cycle and it carries on where it was after a restart. Clients hear where it is ({@link LavaNet.Sync})
 * when they join, on each phase change, after a command and every five seconds while in or near the Galleries, and
 * count on with their own game time in between ({@code client.LavaWarnings} plays the warning signs).
 *
 * <p><b>0.5.1.</b> The quakes, eruptions and the terrain reset run on this clock, each tick after the flood. The reset's
 * generation (kept with the clock, {@link LavaCycleData#resetGen}) goes up once a cycle as the lava stands full: every
 * change the Galleries' change logs ({@link ChangeLog}, kept in {@link LavaChunk}) hold from before then is put back.
 */
public final class LavaFlood {
    private LavaFlood() {}

    /** How many blocks the Galleries' lava climbs over its table at the height of its cycle. */
    public static final int RISE = Terrain.surge(Terrain.GALLERIES);
    /**
     * A player up to this many blocks over the Galleries' top counts as near them: 0.5.0's reach (the band's 16 rows and
     * 32 over them), kept since 0.5.1 removed the band, as the Wasteland's two lowest sheets look down into the Galleries
     * through its openings.
     */
    static final int NEAR = 48;
    /** How often the clients of players in or near the Galleries hear where the cycle is, in ticks. */
    static final int SYNC_EVERY = 100;
    /** How often, with nothing left to do, the chunks round players are looked over again (as well as whenever a chunk loads). */
    static final int IDLE_SCAN = 40;

    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** True for a block state the rising lava would take: a section without one is skipped whole. */
    private static final Predicate<BlockState> TAKEN = s -> Flood.takes(kindOf(s));

    /** Where the flood is: the lava table's Y, the rise, the Galleries' top and bottom, and the Y under which a player is near. */
    public record Band(int tableY, int rise, int top, int bottom, int nearY) {}

    // ------------------------------------------------------------------------------------------- state of this server
    private static boolean setUp;
    @Nullable private static Band band;
    @Nullable private static LavaCycleData data;
    @Nullable private static LavaCycle.Phase lastPhase;
    private static boolean lastOn;
    private static int lastTarget = -1;
    /** Work was left over last tick. */
    private static boolean busy;
    /** A chunk loaded, or a command moved the clock: look the chunks over on the next tick. */
    private static boolean wake;
    private static int pending, nearChunks;
    private static final ChunkSlice SLICE = new ChunkSlice();
    private static final Stats STATS = new Stats();

    // ================================================================================================ events

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<LevelChunk> event) {
        if (DeepWorld.installed()) LavaChunk.attach(event);
    }

    @SubscribeEvent
    public static void levelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (event.level.dimension() != Level.OVERWORLD || !(event.level instanceof ServerLevel level)) return;
        tick(level);
    }

    @SubscribeEvent
    public static void chunkLoad(ChunkEvent.Load event) {
        // the chunk may not be ready yet (this can run inside a blocking chunk load): only note that one came, and
        // look at it from the next tick on
        if (event.getLevel() instanceof ServerLevel l && l.dimension() == Level.OVERWORLD) wake = true;
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) { tell(event.getEntity()); }

    @SubscribeEvent
    public static void respawned(PlayerEvent.PlayerRespawnEvent event) { tell(event.getEntity()); }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) { tell(event.getEntity()); }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        reset();
        TerrainReset.reset();
        com.explorationremix.world.quake.Quakes.reset();
        com.explorationremix.world.eruption.Eruptions.reset();
    }

    private static void reset() {
        setUp = false;
        band = null;
        data = null;
        lastPhase = null;
        lastOn = false;
        lastTarget = -1;
        busy = false;
        wake = false;
        pending = 0;
        nearChunks = 0;
        STATS.clear();
        SLICE.release();
    }

    // ================================================================================================ setup

    /** On the first overworld tick (the deep world is installed and checked by then): find the band and load the clock. */
    private static void setUp(ServerLevel level) {
        setUp = true;
        band = null;
        data = null;
        if (!DeepWorld.installed()) return;
        Shape s = DeepWorld.shape();
        int g = Terrain.GALLERIES;
        if (s == null || s.layerCount <= g || !s.lava(g)) {
            ExplorationRemix.LOGGER.warn("Lava cycle off: this world has no Basalt Galleries with a lava table");
            return;
        }
        int table = s.waterY(g), rise = Terrain.surge(g);
        // never touch anything outside the Galleries: the whole band must lie inside the layer
        for (int y = table; y < table + rise; y++) {
            if (s.layerAt(y) != g) {
                ExplorationRemix.LOGGER.error("Lava cycle off: its band y {} to {} leaves the Basalt Galleries (y {} to {})", table, table + rise - 1, s.bottom(g), s.top(g));
                return;
            }
        }
        band = new Band(table, rise, s.top(g), s.bottom(g), s.top(g) + NEAR);
        data = LavaCycleData.get(level, nextLengths(0));
        LavaClock c = data.clock;
        ExplorationRemix.LOGGER.info("Lava cycle: the Galleries' lava rises from its table at y {} to y {} on a {} cycle ({}){}; {} cycles so far",
                table - 1, table + rise - 1, minutes(c.cycle(rise).length()), describe(c.cycle(rise)),
                ERConfig.INSTANCE.lavaCycleEnabled.get() ? "" : ", but it is turned off in the config", c.cycles());
    }

    /** The phase lengths of the next cycle: the length command's, or the config's, shared out by the config. */
    private static int[] nextLengths(int override) {
        ERConfig cfg = ERConfig.INSTANCE;
        int total = override > 0 ? override : (int) Math.round(cfg.lavaCycleMinutes.get() * LavaCycle.MINUTE);
        double[] shares = {cfg.lavaCalm.get(), cfg.lavaWarning.get(), cfg.lavaRise.get(), cfg.lavaFull.get(), cfg.lavaDrain.get()};
        return LavaCycle.scale(Math.max(1, total), shares);
    }

    // ================================================================================================ the tick

    private static void tick(ServerLevel level) {
        if (!setUp) setUp(level);
        if (band == null || data == null) return;
        long now = level.getGameTime();
        LavaClock clock = data.clock;
        if (clock.advance(now, nextLengths(clock.lengthOverride()))) data.setDirty();
        LavaCycle.State s = clock.cycle(band.rise()).at(clock.tick(now));
        boolean on = ERConfig.INSTANCE.lavaCycleEnabled.get();
        int target = on ? s.level() : 0;
        if (on != lastOn || s.phase() != lastPhase) {
            phaseChanged(lastOn ? lastPhase : null, on ? s.phase() : null, s);
            lastOn = on;
            lastPhase = s.phase();
            send(level, PacketDistributor.DIMENSION.with(() -> Level.OVERWORLD));
        } else if (now % SYNC_EVERY == 0) {
            for (ServerPlayer p : level.players()) if (p.getY() < band.nearY()) send(level, PacketDistributor.PLAYER.with(() -> p));
        }
        // the terrain is put back once a cycle, from when the lava stands full (or from the drain, if a command skipped
        // the full phase); the cycle count keeps a restart or a clock command inside that cycle from adding another
        if (on && (s.phase() == LavaCycle.Phase.FULL || s.phase() == LavaCycle.Phase.DRAIN) && data.resetCycle != clock.cycles()) {
            data.resetCycle = clock.cycles();
            data.resetGen++;
            data.setDirty();
            if (ERConfig.INSTANCE.resetEnabled.get()) {
                ExplorationRemix.LOGGER.info("Terrain reset: the lava stands full, so everything changed in the Basalt Galleries since the last reset goes back (generation {})", data.resetGen);
            }
        }
        STATS.tick();
        flood(level, target, now);
        // 0.5.1: the terrain reset follows the flood (it reads the heights the flood left), then the quakes and eruptions
        TerrainReset.tick(level, band, s, now);
        com.explorationremix.world.quake.Quakes.tick(level, band, s, now);
        com.explorationremix.world.eruption.Eruptions.tick(level, band, s, now);
        // 0.9.0: the falling blocks they let go of, for their hits, holds and discards
        Falling.tick(level, band);
    }

    /**
     * The cycle has entered a new phase ({@code to}; null when the cycle has stopped, {@code from} null when it starts).
     * Logs what the flood cost in the phase that ended.
     */
    private static void phaseChanged(@Nullable LavaCycle.Phase from, @Nullable LavaCycle.Phase to, LavaCycle.State s) {
        if (from != null && STATS.phaseBlocks > 0) {
            ExplorationRemix.LOGGER.info("Lava cycle: {} over: {} blocks changed in {} ticks ({} of them with work: {} per tick, {} ms average, {} ms at most)",
                    from.id(), STATS.phaseBlocks, STATS.phaseTicks, STATS.phaseBusy, STATS.phaseBusy == 0 ? 0 : STATS.phaseBlocks / STATS.phaseBusy,
                    String.format(Locale.ROOT, "%.2f", STATS.phaseBusy == 0 ? 0 : STATS.phaseNanos / 1e6 / STATS.phaseBusy),
                    String.format(Locale.ROOT, "%.2f", STATS.phaseMaxNanos / 1e6));
        }
        if (to != null) ExplorationRemix.LOGGER.debug("Lava cycle: {} ({} left)", to.id(), minutes(s.left()));
        STATS.newPhase();
    }

    // ================================================================================================ the flood

    /** Brings the chunks round the players in or near the Galleries toward the lava height {@code target}, within the budget. */
    private static void flood(ServerLevel level, int target, long now) {
        Band b = band;
        boolean moved = target != lastTarget;
        lastTarget = target;
        if (!busy && !moved && !wake && now % IDLE_SCAN != 0) return;
        wake = false;
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (p.getY() < b.nearY() && !p.isRemoved()) near.add(p);
        if (near.isEmpty()) {
            busy = false;
            pending = 0;
            nearChunks = 0;
            return;
        }
        ERConfig cfg = ERConfig.INSTANCE;
        int radius = cfg.lavaRadius.get() > 0 ? cfg.lavaRadius.get() : level.getServer().getPlayerList().getViewDistance() + 1;
        int maxChecks = cfg.lavaChecksPerTick.get(), maxChanges = cfg.lavaChangesPerTick.get();
        if (level.getServer().getAverageTickTime() > 40) {
            maxChecks = Math.max(256, maxChecks / 2);
            maxChanges = Math.max(16, maxChanges / 2);
        }
        long t0 = System.nanoTime();
        ServerChunkCache source = level.getChunkSource();
        LongOpenHashSet seen = near.size() > 1 ? new LongOpenHashSet() : null;
        int n = near.size(), first = (int) Math.floorMod(now, (long) n);
        int[] off = new int[2];
        int[] cx0 = new int[n], cz0 = new int[n];
        for (int k = 0; k < n; k++) {
            cx0[k] = SectionPos.blockToSectionCoord(near.get(k).getBlockX());
            cz0[k] = SectionPos.blockToSectionCoord(near.get(k).getBlockZ());
        }
        Work w = new Work(maxChecks, maxChanges);
        int stale = 0, chunks = 0;
        for (int r = 0; r <= radius; r++) {
            for (int k = 0; k < n; k++) {
                int pk = (first + k) % n;
                int size = Flood.ringSize(r);
                for (int j = 0; j < size; j++) {
                    Flood.ring(r, j, off);
                    int cx = cx0[pk] + off[0], cz = cz0[pk] + off[1];
                    if (seen != null && !seen.add(ChunkPos.asLong(cx, cz))) continue;
                    LevelChunk chunk = source.getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    LavaChunk d = LavaChunk.of(chunk);
                    if (d == null) continue;
                    chunks++;
                    d.align(b.tableY());
                    if (d.level == target) continue;
                    if (!w.spent()) bring(level, chunk, d, target, w);
                    if (d.level != target) stale++;
                }
            }
        }
        pending = stale;
        nearChunks = chunks;
        busy = stale > 0;
        if (w.checks > 0) STATS.work(System.nanoTime() - t0, w.checks, w.changes);
    }

    /** What one tick may still spend, and has spent. */
    private static final class Work {
        final int maxChecks, maxChanges;
        int checks, changes;

        Work(int maxChecks, int maxChanges) {
            this.maxChecks = maxChecks;
            this.maxChanges = maxChanges;
        }

        boolean spent() { return checks >= maxChecks || changes >= maxChanges; }
    }

    /** Moves one chunk's lava toward {@code target}, a whole slice at a time, until it is there or the tick's budget is spent. */
    private static void bring(ServerLevel level, LevelChunk chunk, LavaChunk d, int target, Work w) {
        Band b = band;
        while (d.level != target && !w.spent()) {
            int i = Flood.nextSlice(d.level, target);
            boolean up = d.level < target;
            int y = b.tableY() + i;
            int si = chunk.getSectionIndex(y);
            LevelChunkSection sec = chunk.getSection(si);
            // a slice flooded by a build that kept no taken blocks drains the old way (LavaChunk)
            SLICE.begin(level, chunk, sec, si, y, i, d, !up && i < d.legacyTop);
            int changed;
            if (up) {
                if (sec.maybeHas(TAKEN)) {
                    changed = Flood.rise(SLICE, d.mask, d.glow, d.kept, i);
                    w.checks += 256;
                } else {
                    changed = 0;
                    w.checks += 1;
                }
                d.level++;
            } else {
                w.checks += Math.max(1, d.mask.count(i));
                changed = Flood.drain(SLICE, d.mask, d.glow, d.kept, i);
                d.level--;
                if (d.legacyTop > i) d.legacyTop = i;
            }
            SLICE.end();
            w.changes += changed;
            chunk.setUnsaved(true);
        }
    }

    /** What the flood sees in a block state. */
    static int kindOf(BlockState s) {
        if (s.isAir()) return Flood.AIR;
        if (s.getBlock() instanceof GlowBlock) return Flood.GLOW;
        if (s.getBlock() instanceof LiquidBlock) {
            Fluid f = s.getFluidState().getType();
            if (f == Fluids.LAVA) return Flood.LAVA;
            if (f == Fluids.FLOWING_LAVA) return Flood.FLOWING;
            return Flood.OTHER;
        }
        if (s.is(BlockTags.FIRE)) return Flood.FIRE;
        return Flood.OTHER;
    }

    /** One slice of one chunk, written straight into its section (see the class comment for what is done by hand). */
    private static final class ChunkSlice implements Flood.Slice {
        private ServerLevel level;
        private ServerChunkCache source;
        private ThreadedLevelLightEngine light;
        private LevelChunk chunk;
        private LevelChunkSection sec;
        private LavaChunk data;
        private Flood.Mask mask;
        private int si, y, ly, x0, z0, i;
        /** The Galleries' bottom the change logs count from (MIN_VALUE: none). */
        private int logBottom;
        private boolean wasEmpty, legacy;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        void begin(ServerLevel level, LevelChunk chunk, LevelChunkSection sec, int si, int y, int i, LavaChunk data, boolean legacy) {
            this.level = level;
            this.data = data;
            this.mask = data.mask;
            this.legacy = legacy;
            this.source = level.getChunkSource();
            this.light = source.getLightEngine();
            this.chunk = chunk;
            this.sec = sec;
            this.si = si;
            this.y = y;
            this.ly = y & 15;
            this.x0 = chunk.getPos().getMinBlockX();
            this.z0 = chunk.getPos().getMinBlockZ();
            this.i = i;
            this.logBottom = GalleryChanges.bottom();
            this.wasEmpty = sec.hasOnlyAir();
        }

        void end() {
            boolean empty = sec.hasOnlyAir();
            if (empty != wasEmpty) light.updateSectionStatus(SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(si)), empty);
            release();
        }

        /** Lets go of the level, its chunk source and light engine, so this static slice never keeps a stopped server in memory. */
        void release() {
            level = null;
            source = null;
            light = null;
            chunk = null;
            sec = null;
            data = null;
            mask = null;
        }

        @Override
        public int kind(int x, int z) { return kindOf(sec.getBlockState(x, ly, z)); }

        @Override
        public int state(int x, int z) { return Block.getId(sec.getBlockState(x, ly, z)); }

        @Override
        public void fill(int x, int z) { put(x, z, LAVA); }

        @Override
        public void clear(int x, int z) { put(x, z, AIR); }

        @Override
        public void glow(int x, int z) { put(x, z, HallGlowFeature.bandGlow()); }

        @Override
        public void put(int x, int z, int state) { put(x, z, Block.stateById(state)); }

        private void put(int x, int z, BlockState s) {
            sec.setBlockState(x, ly, z, s);
            pos.set(x0 + x, y, z0 + z);
            light.checkBlock(pos);
            source.blockChanged(pos);
        }

        /**
         * After the drain put back what the lava took at (x, z): wakes only lava that changed since the last terrain
         * reset (its chunk's change log holds the position: lava a player let in, a shaft dug under the sea), at the
         * block itself, above, below and on its four sides, so it drains away; never the terrain's own lava (a fall the
         * drain gave back as it was, a pool), which a fluid tick would pour again by vanilla's rules, and never the
         * sea's own sources in a chunk that has not drained this slice yet. A slice flooded by an older build drains
         * the old way ({@link #wakeLegacy}).
         */
        @Override
        public void wake(int x, int z) {
            if (legacy) {
                wakeLegacy(x, z);
                return;
            }
            if (logBottom == Integer.MIN_VALUE) return;
            wakeChanged(x0 + x, y, z0 + z);
            wakeChanged(x0 + x, y + 1, z0 + z);
            wakeChanged(x0 + x, y - 1, z0 + z);
            wakeChanged(x0 + x - 1, y, z0 + z);
            wakeChanged(x0 + x + 1, y, z0 + z);
            wakeChanged(x0 + x, y, z0 + z - 1);
            wakeChanged(x0 + x, y, z0 + z + 1);
        }

        private void wakeChanged(int wx, int wy, int wz) {
            int dy = wy - logBottom;
            if (!ChangeLog.holds(dy) || !GalleryChanges.inLayer(wy)) return;
            boolean here = (wx >> 4) == chunk.getPos().x && (wz >> 4) == chunk.getPos().z;
            LevelChunk c = here ? chunk : source.getChunkNow(wx >> 4, wz >> 4);
            if (c == null) return;
            LavaChunk d = here ? data : LavaChunk.of(c);
            if (d == null || d.log.isEmpty()) return;
            d.log.align(logBottom);
            if (!d.log.has(ChangeLog.key(wx & 15, dy, wz & 15))) return;
            BlockState s = c.getBlockState(pos.set(wx, wy, wz));
            FluidState f = s.getFluidState();
            if (f.getType() != Fluids.LAVA && f.getType() != Fluids.FLOWING_LAVA) return;
            if (f.isSource()) {
                // the sea's own sources never flow: a chunk that has not drained this slice yet takes them itself
                int j = wy - (y - i);
                if (j >= 0 && j < d.mask.slices()) {
                    d.align(y - i);
                    if (d.mask.get(j, wx & 15, wz & 15)) return;
                }
            }
            level.scheduleTick(new BlockPos(wx, wy, wz), f.getType(), f.getType().getTickDelay(level));
        }

        /**
         * The drain's wake before this build kept the taken blocks: the lava above, below (unless the sea's own) and on
         * the four sides of a block the drain cleared gets a tick, so a fall whose flowing lava the sea took and the drain
         * cleared to air pours down again.
         */
        private void wakeLegacy(int x, int z) {
            // above: the slices over this one were drained before it, so lava there is never the sea's
            BlockState up = ly < 15 ? sec.getBlockState(x, ly + 1, z) : chunk.getBlockState(pos.set(x0 + x, y + 1, z0 + z));
            tickIfLava(up, x0 + x, y + 1, z0 + z);
            // below: lava the sea fed downward into a block that was not open when that slice rose (a shaft dug under the
            // sea) has nothing over it now; the chunk's own sea there goes with the next slice, and sources need no tick
            if (!mask.get(i - 1, x, z)) {
                BlockState down = ly > 0 ? sec.getBlockState(x, ly - 1, z) : chunk.getBlockState(pos.set(x0 + x, y - 1, z0 + z));
                if (down.getFluidState().getType() == Fluids.FLOWING_LAVA) tickIfLava(down, x0 + x, y - 1, z0 + z);
            }
            side(x - 1, z);
            side(x + 1, z);
            side(x, z - 1);
            side(x, z + 1);
        }

        private void side(int x, int z) {
            if (x >= 0 && x < 16 && z >= 0 && z < 16) {
                // this slice is dry now: any lava left in it is natural
                tickIfLava(sec.getBlockState(x, ly, z), x0 + x, y, z0 + z);
                return;
            }
            int wx = x0 + x, wz = z0 + z;
            LevelChunk n = source.getChunkNow(wx >> 4, wz >> 4);
            if (n == null) return;
            // the sea in a neighbour that has not drained this slice yet goes when that chunk drains it
            LavaChunk nd = LavaChunk.of(n);
            if (nd != null) {
                nd.align(y - i);
                if (nd.mask.get(i, wx & 15, wz & 15)) return;
            }
            tickIfLava(n.getSection(si).getBlockState(wx & 15, ly, wz & 15), wx, y, wz);
        }

        private void tickIfLava(BlockState s, int x, int y, int z) {
            FluidState f = s.getFluidState();
            Fluid type = f.getType();
            if (type != Fluids.LAVA && type != Fluids.FLOWING_LAVA) return;
            level.scheduleTick(new BlockPos(x, y, z), type, type.getTickDelay(level));
        }
    }

    // ================================================================================================ clients

    /** What the clients need to know about the cycle now. */
    private static LavaNet.Sync sync(ServerLevel level) {
        Band b = band;
        if (b == null || data == null || !ERConfig.INSTANCE.lavaCycleEnabled.get()) return LavaNet.Sync.off();
        LavaClock c = data.clock;
        return new LavaNet.Sync(true, c.tick(level.getGameTime()), c.lengths(), c.frozen(), b.tableY(), b.rise(), b.top(), b.bottom());
    }

    private static void send(ServerLevel level, PacketDistributor.PacketTarget to) { LavaNet.CHANNEL.send(to, sync(level)); }

    /** Tells one player's client where the cycle is (on joining, respawning or changing dimension). */
    private static void tell(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer sp) || !(sp.level() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) {
            LavaNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), LavaNet.Sync.off());
            return;
        }
        if (!setUp) setUp(level);
        send(level, PacketDistributor.PLAYER.with(() -> sp));
    }

    // ================================================================================================ for the commands

    /** Where the flood sits, or null when the cycle cannot run in this world. */
    @Nullable
    public static Band band() { return band; }

    /**
     * True while the lava clock is held ({@code /remix lava freeze} or {@code level}, which DevShots' setup sends): no
     * quake or eruption starts on its own then (0.9.0).
     */
    public static boolean held() { return data != null && data.clock.frozen(); }

    /**
     * The lava height the flood is bringing the chunks round the players to now: the cycle's height, 0 with the cycle
     * turned off, -1 before the flood's first tick. A chunk whose record stands elsewhere is still catching up.
     */
    static int target() { return lastTarget; }

    /** The clock and the cycle, or null when the cycle cannot run in this world. */
    @Nullable
    static LavaClock clock(ServerLevel level) {
        if (!setUp) setUp(level);
        return data == null ? null : data.clock;
    }

    /** After a command changed the clock: save it, tell every client, and look the chunks over on the next tick. */
    static void clockChanged(ServerLevel level) {
        if (data == null) return;
        // a command that puts the cycle back before its full lava lets that full lava reset the terrain again
        if (band != null) {
            LavaCycle.Phase p = data.clock.cycle(band.rise()).at(data.clock.tick(level.getGameTime())).phase();
            if (p == LavaCycle.Phase.CALM || p == LavaCycle.Phase.WARNING || p == LavaCycle.Phase.RISE) data.resetCycle = -1;
        }
        data.setDirty();
        wake = true;
        send(level, PacketDistributor.DIMENSION.with(() -> Level.OVERWORLD));
    }

    /**
     * The terrain reset's generation (0.5.1): the change logs put back everything recorded before it. 0 when the cycle
     * cannot run in this world.
     */
    public static long resetGen(ServerLevel level) {
        if (!setUp) setUp(level);
        return data == null ? 0 : data.resetGen;
    }

    /** The cycle count the reset's generation last went up in for a full lava, or -1 (for the reset's status). */
    static long resetCycle() { return data == null ? -1 : data.resetCycle; }

    /** {@code /remix reset now}: the reset's generation goes up, so everything changed so far is put back now. */
    static long resetNow(ServerLevel level) {
        if (!setUp) setUp(level);
        if (data == null) return -1;
        data.resetGen++;
        data.setDirty();
        return data.resetGen;
    }

    /** Phase lengths for a cycle of {@code totalTicks} (0: the config's length), shared out by the config's shares. */
    static int[] lengthsFor(int totalTicks) { return nextLengths(totalTicks); }

    /** How many chunks round the players in or near the Galleries were looked at last time, and how many are still catching up. */
    static int nearChunks() { return nearChunks; }
    static int pending() { return pending; }
    static Stats stats() { return STATS; }

    /** The record of the chunk holding (x, z), if it is loaded: its lava height, or -1. */
    static int chunkLevel(ServerLevel level, int x, int z) {
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        LavaChunk d = c == null ? null : LavaChunk.of(c);
        return d == null ? -1 : d.level;
    }

    static String minutes(int ticks) {
        int s = Math.max(0, ticks) / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    static String describe(LavaCycle c) {
        StringBuilder sb = new StringBuilder();
        for (LavaCycle.Phase p : LavaCycle.Phase.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.id()).append(' ').append(minutes(c.length(p)));
        }
        return sb.toString();
    }

    /** What the flood has cost: per tick with work over the last {@link #WINDOW} such ticks, and per phase for the log. */
    static final class Stats {
        static final int WINDOW = 200;
        private final long[] nanos = new long[WINDOW];
        private final int[] changes = new int[WINDOW], checks = new int[WINDOW];
        private int next, filled;
        long phaseBlocks, phaseNanos, phaseMaxNanos;
        int phaseTicks, phaseBusy;

        void tick() { phaseTicks++; }

        void work(long ns, int checked, int changed) {
            nanos[next] = ns;
            checks[next] = checked;
            changes[next] = changed;
            next = (next + 1) % WINDOW;
            filled = Math.min(WINDOW, filled + 1);
            phaseBlocks += changed;
            phaseNanos += ns;
            phaseMaxNanos = Math.max(phaseMaxNanos, ns);
            phaseBusy++;
        }

        void newPhase() {
            phaseBlocks = phaseNanos = phaseMaxNanos = 0;
            phaseTicks = phaseBusy = 0;
        }

        void clear() {
            newPhase();
            next = filled = 0;
        }

        /** Ticks with work in the window. */
        int ticks() { return filled; }
        double avgMs() { long s = 0; for (int k = 0; k < filled; k++) s += nanos[k]; return filled == 0 ? 0 : s / 1e6 / filled; }
        double maxMs() { long m = 0; for (int k = 0; k < filled; k++) m = Math.max(m, nanos[k]); return m / 1e6; }
        double avgChanges() { long s = 0; for (int k = 0; k < filled; k++) s += changes[k]; return filled == 0 ? 0 : s / (double) filled; }
        double avgChecks() { long s = 0; for (int k = 0; k < filled; k++) s += checks[k]; return filled == 0 ? 0 : s / (double) filled; }
    }
}
