package com.explorationremix.block;

import com.explorationremix.ExplorationRemix;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.WeakHashMap;

/**
 * The tube worms' warning (0.20.6; Taran: "send a propagating signal to nearby tube worms to pull in"): a clump a player
 * startles ({@link TubeWormWatch}) passes the scare to every clump within {@link #HOP} blocks of it, and each of those
 * on again, so it runs through the colony as a ripple at about twelve blocks a second (card pick), the clumps snapping
 * in as it reaches them, each with a faint tick. It reaches {@link #REACH} blocks from where it began (card pick:
 * across the colony, about 16), thinning out over the last few so its edge is ragged, not a ring. A clump it reached
 * passes on no other for {@link #RECENT} ticks, so the scares of a player walking through do not echo back and forth.
 * Clumps already in pass it on too. A sneaking player's startle sends none (card pick: only the nearest flinch).
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class TubeWormSignals {
    private TubeWormSignals() {}

    /**
     * How far a clump passes the scare (blocks, middle to middle, at most {@link #HOP_UP} up or down): over the gaps
     * between a colony's clumps. Over the Deep Gardens' worms (GardensFieldTest's ground) it reaches 95% of the worms
     * within 11 blocks of where it began, and all of them from 7 starts in 10; at 3.3 blocks it was 59%, and often
     * died in the first clump.
     */
    public static final double HOP = 6.5;
    public static final int HOP_UP = 3;
    /** How far from its start the scare reaches (blocks), and from where it begins to thin out. */
    public static final double REACH = 16.0, THIN_FROM = 11.0;
    /** Ticks it takes over a block (card pick: about twelve blocks a second). */
    static final double TICKS_PER_BLOCK = 20.0 / 12.0;
    /** A clump the scare reached passes on no other for this long (ticks). */
    static final int RECENT = 40;
    /** The faint ticks: at most one a level in this many ticks. */
    static final int TICK_EVERY = 2;

    private static final Map<ServerLevel, Waves> WAVES = new WeakHashMap<>();

    /** A level's running scares: arrivals by time, and when each clump was last reached. */
    static final class Waves {
        /** {tick it arrives, clump, where its scare began} */
        final PriorityQueue<long[]> queue = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        /** The clumps the scare is on its way to, and when it gets there (the soonest way). */
        final Long2LongOpenHashMap due = new Long2LongOpenHashMap();
        /** The clumps it reached, and when. */
        final Long2LongOpenHashMap reached = new Long2LongOpenHashMap();
        long lastTick = Long.MIN_VALUE;
        /** Each running scare's tally, by where it began, for the debug log. */
        final Map<Long, Tally> tallies = new HashMap<>();

        boolean recent(long k, long now) { return reached.containsKey(k) && now - reached.get(k) < RECENT; }
    }

    /**
     * A scare's tally: when it began and last reached a clump, the clumps it reached and pulled in, how far it went,
     * and the server's time spent passing it on.
     */
    static final class Tally {
        final long began;
        long last, nanos;
        int reached, pulled;
        double far;

        Tally(long began) { this.began = began; last = began; }
    }

    /**
     * A player startled the clump at {@code pos}: it snaps in, and with {@code warn} sends the scare on through its
     * colony.
     */
    public static void startle(ServerLevel level, BlockPos pos, BlockState state, boolean warn) {
        if (state.getValue(TubeWormsBlock.OUT)) TubeWormsBlock.pullIn(level, pos, state);
        Waves w = WAVES.computeIfAbsent(level, l -> new Waves());
        long now = level.getGameTime(), k = pos.asLong();
        if (w.recent(k, now)) return;
        w.reached.put(k, now);
        w.due.remove(k);
        if (!warn) return;
        Tally t = new Tally(now);
        w.tallies.put(k, t);
        long t0 = System.nanoTime();
        pass(level, w, pos, pos, now);
        t.nanos += System.nanoTime() - t0;
    }

    /**
     * The scare at {@code from} (begun at {@code start}) goes on to the clumps round it that it has not reached. The
     * blocks round it are read straight from their chunks' sections, and sections without worms are passed over
     * whole, so a scare across a colony costs the server well under a millisecond.
     */
    static void pass(ServerLevel level, Waves w, BlockPos from, BlockPos start, long now) {
        int r = (int) Math.ceil(HOP), fx = from.getX(), fy = from.getY(), fz = from.getZ();
        int sx = start.getX(), sy = start.getY(), sz = start.getZ();
        long begun = start.asLong();
        for (int cx = (fx - r) >> 4; cx <= (fx + r) >> 4; cx++)
            for (int cz = (fz - r) >> 4; cz <= (fz + r) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;                                  // not loaded: the scare stops there
                for (int cy = (fy - HOP_UP) >> 4; cy <= (fy + HOP_UP) >> 4; cy++) {
                    int si = chunk.getSectionIndexFromSectionY(cy);
                    if (si < 0 || si >= chunk.getSectionsCount()) continue;
                    LevelChunkSection sec = chunk.getSection(si);
                    if (sec.hasOnlyAir() || !sec.maybeHas(TubeWormSignals::worms)) continue;
                    int x0 = Math.max(fx - r, cx << 4), x1 = Math.min(fx + r, (cx << 4) + 15);
                    int y0 = Math.max(fy - HOP_UP, cy << 4), y1 = Math.min(fy + HOP_UP, (cy << 4) + 15);
                    int z0 = Math.max(fz - r, cz << 4), z1 = Math.min(fz + r, (cz << 4) + 15);
                    for (int y = y0; y <= y1; y++)
                        for (int z = z0; z <= z1; z++)
                            for (int x = x0; x <= x1; x++) {
                                int dx = x - fx, dy = y - fy, dz = z - fz, d2 = dx * dx + dy * dy + dz * dz;
                                if (d2 == 0 || d2 > HOP * HOP || !worms(sec.getBlockState(x & 15, y & 15, z & 15))) continue;
                                int ox = x - sx, oy = y - sy, oz = z - sz;
                                if (ox * ox + oy * oy + oz * oz > REACH * REACH) continue;
                                long k = BlockPos.asLong(x, y, z);
                                if (w.recent(k, now)) continue;
                                long at = now + Math.max(1L, Math.round(Math.sqrt(d2) * TICKS_PER_BLOCK + level.random.nextDouble() * 0.8));
                                if (w.due.containsKey(k) && w.due.get(k) <= at) continue;
                                w.due.put(k, at);
                                w.queue.add(new long[]{at, k, begun});
                            }
                }
            }
    }

    private static boolean worms(BlockState s) { return s.getBlock() instanceof TubeWormsBlock; }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level)) return;
        Waves w = WAVES.get(level);
        if (w == null) return;
        long now = level.getGameTime();
        while (!w.queue.isEmpty() && w.queue.peek()[0] <= now) {
            long[] a = w.queue.poll();
            if (!w.due.containsKey(a[1]) || w.due.get(a[1]) != a[0]) continue;   // reached sooner another way
            w.due.remove(a[1]);
            arrive(level, w, BlockPos.of(a[1]), BlockPos.of(a[2]), now);
        }
        if (w.queue.isEmpty() && !w.tallies.isEmpty()) {
            for (Map.Entry<Long, Tally> en : w.tallies.entrySet()) {
                Tally t = en.getValue();
                ExplorationRemix.LOGGER.debug("[tube worms] a scare from {} reached {} clumps ({} pulled in) over {} ticks, {} blocks out at most, {} ms of the server's time",
                        BlockPos.of(en.getKey()).toShortString(), t.reached, t.pulled, t.last - t.began, String.format(java.util.Locale.ROOT, "%.1f", t.far),
                        String.format(java.util.Locale.ROOT, "%.2f", t.nanos / 1.0E6));
            }
            w.tallies.clear();
        }
        if (now % 100 == 0) w.reached.long2LongEntrySet().removeIf(en -> now - en.getLongValue() >= RECENT);
        if (w.queue.isEmpty() && w.reached.isEmpty() && w.tallies.isEmpty()) WAVES.remove(level);
    }

    /** The scare reaches the clump at {@code pos}: it snaps in with a faint tick, and passes it on. */
    static void arrive(ServerLevel level, Waves w, BlockPos pos, BlockPos start, long now) {
        if (!level.isLoaded(pos)) return;
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof TubeWormsBlock)) return;
        w.reached.put(pos.asLong(), now);
        double d = Math.sqrt(pos.distSqr(start));
        // its edge: the further out, the likelier a clump does not feel it (and passes nothing on)
        if (d > THIN_FROM && level.random.nextDouble() < (d - THIN_FROM) / (REACH - THIN_FROM)) return;
        Tally t = w.tallies.get(start.asLong());
        if (t != null) {
            t.reached++;
            t.last = now;
            t.far = Math.max(t.far, d);
        }
        long t0 = System.nanoTime();
        if (s.getValue(TubeWormsBlock.OUT)) {
            if (t != null) t.pulled++;
            TubeWormsBlock.pullIn(level, pos, s);
            if (now - w.lastTick >= TICK_EVERY) {
                w.lastTick = now;
                float vol = (float) (0.16 * (1.0 - 0.6 * d / REACH));
                level.playSound(null, pos, SoundEvents.BIG_DRIPLEAF_TILT_DOWN, SoundSource.BLOCKS, vol, 1.85F + level.random.nextFloat() * 0.25F);
            }
        }
        pass(level, w, pos, start, now);
        if (t != null) t.nanos += System.nanoTime() - t0;
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level) WAVES.remove(level);
    }
}
