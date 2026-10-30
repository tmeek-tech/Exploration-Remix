package com.explorationremix.world.feature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeadEnds;
import com.explorationremix.world.terrain.Shape;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Joins the blind ends of the caves above the old floor to a cave nearby (see {@link DeadEnds}): one run per chunk,
 * first of all features, so ores, moss and plants dress the new tunnels like any other cave. It reads the blocks of
 * the chunk and the eight round it, finds the blind ends whose tips lie in this chunk, and carves at most three tunnels
 * of 3 to 4 blocks across through rock (never through water or beside it, never within 5 blocks of the ground, never
 * through anything but natural rock and soil), trying the next best tunnel when one is turned down. Off with the world
 * option {@code joinDeadEnds}.
 */
public final class JoinDeadEndsFeature extends Feature<NoneFeatureConfiguration> {
    private static final TagKey<Block> CAVE_ROCK = TagKey.create(Registries.BLOCK, new ResourceLocation(ExplorationRemix.MOD_ID, "cave_rock"));
    private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
    /** The sides water runs in from. */
    private static final Direction[] FLOWS_IN = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    /** At most this many tunnels a chunk. */
    private static final int MAX_LINKS = 3;
    /** The grid's highest cell row: about 220 blocks over its floor. */
    private static final int MAX_ROWS = 110;
    /** The tunnels tried for a blind end, best first, before giving up on it. */
    private static final int TRIES = 4;
    /** Why a tunnel was not carved (see {@link #carve}). */
    private static final int CARVED = 0, WET = 1, GROUND = 2, BLOCKED = 3, EDGE = 4;
    /**
     * Since the server started, for /remix deadends: chunks looked at, blind ends found in them, tunnels carved,
     * blind ends with no cave in reach, tunnels turned down for water, the ground, other blocks or the chunks' edge,
     * and the time spent (nanoseconds).
     */
    public static final java.util.concurrent.atomic.AtomicLong CHUNKS = new java.util.concurrent.atomic.AtomicLong(),
            FOUND = new java.util.concurrent.atomic.AtomicLong(), JOINED = new java.util.concurrent.atomic.AtomicLong(),
            NO_CAVE = new java.util.concurrent.atomic.AtomicLong(), NO_WET = new java.util.concurrent.atomic.AtomicLong(),
            NO_GROUND = new java.util.concurrent.atomic.AtomicLong(), NO_BLOCKED = new java.util.concurrent.atomic.AtomicLong(),
            NO_EDGE = new java.util.concurrent.atomic.AtomicLong(), NANOS = new java.util.concurrent.atomic.AtomicLong();

    public JoinDeadEndsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        if (!DeepWorld.installed() || !ERConfig.INSTANCE.joinDeadEnds.get()) return false;
        Shape shape = DeepWorld.shape();
        if (shape == null) return false;
        long start = System.nanoTime();
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        DeadEnds.Grid g = read(level, cp, shape.packFloor + 8, Heightmap.Types.OCEAN_FLOOR_WG);
        if (g == null) return false;
        int made = 0;
        List<DeadEnds.Tip> tips = DeadEnds.tips(g, 8, 15, 8, 15);
        CHUNKS.incrementAndGet();
        FOUND.addAndGet(tips.size());
        for (DeadEnds.Tip t : tips) {
            if (made >= MAX_LINKS) break;
            List<DeadEnds.Link> ls = DeadEnds.links(g, t, TRIES);
            if (ls.isEmpty()) NO_CAVE.incrementAndGet();
            for (DeadEnds.Link l : ls) {
                int why = carve(level, cp, g, l, ctx.random());
                if (why == CARVED) {
                    DeadEnds.open(g, l);
                    made++;
                    break;
                }
                (why == WET ? NO_WET : why == GROUND ? NO_GROUND : why == BLOCKED ? NO_BLOCKED : NO_EDGE).incrementAndGet();
            }
        }
        JOINED.addAndGet(made);
        NANOS.addAndGet(System.nanoTime() - start);
        return made > 0;
    }

    /**
     * The grid over a chunk and the eight round it, from {@code yMin} up to the highest ground: rock, air, water (any
     * fluid) or outside (open within 4 blocks of the ground, or over it). Null if a chunk of the nine is missing.
     */
    public static DeadEnds.Grid read(LevelAccessor level, ChunkPos cp, int yMin, Heightmap.Types ground) {
        return read(level, cp, yMin, ground, false);
    }

    /**
     * The grid as above; with {@code rockOnly}, only natural rock and soil count as rock, so the caves' own shapes show
     * without what features and structures put in them.
     */
    public static DeadEnds.Grid read(LevelAccessor level, ChunkPos cp, int yMin, Heightmap.Types ground, boolean rockOnly) {
        int x0 = (cp.x - 1) << 4, z0 = (cp.z - 1) << 4;
        ChunkAccess[] chunks = new ChunkAccess[9];
        int[] heights = new int[48 * 48];
        int top = Integer.MIN_VALUE;
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
            int cx = cp.x - 1 + a, cz = cp.z - 1 + b;
            if (!level.hasChunk(cx, cz)) return null;
            ChunkAccess ch = level.getChunk(cx, cz);
            chunks[a * 3 + b] = ch;
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int h = ch.getHeight(ground, lx, lz); // the top block
                heights[(a * 16 + lx) * 48 + b * 16 + lz] = h;
                top = Math.max(top, h);
            }
        }
        int y0 = yMin & ~1;
        int ny = Math.min(MAX_ROWS, (top + 2 - y0) / DeadEnds.CELL);
        if (ny < 8) return null;
        DeadEnds.Grid g = new DeadEnds.Grid(x0, y0, z0, 24, ny, 24);
        byte[] open = new byte[24 * ny * 24], wet = new byte[open.length];
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
            ChunkAccess ch = chunks[a * 3 + b];
            for (int y = y0; y < y0 + ny * DeadEnds.CELL; y++) {
                int si = ch.getSectionIndex(y);
                if (si < 0 || si >= ch.getSectionsCount()) continue;
                LevelChunkSection sec = ch.getSection(si);
                int j = (y - y0) / DeadEnds.CELL;
                for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                    int i = (a * 16 + lx) / DeadEnds.CELL, k = (b * 16 + lz) / DeadEnds.CELL;
                    int n = g.index(i, j, k);
                    if (sec.hasOnlyAir()) { open[n]++; continue; }
                    BlockState s = sec.getBlockState(lx, y & 15, lz);
                    if (!s.getFluidState().isEmpty()) { open[n]++; wet[n]++; }
                    else if (s.isAir() || !s.blocksMotion()) open[n]++;
                    else if (rockOnly && !s.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES) && !s.is(CAVE_ROCK)) open[n]++;
                }
            }
        }
        for (int j = 0; j < ny; j++) for (int k = 0; k < 24; k++) for (int i = 0; i < 24; i++) {
            int n = g.index(i, j, k);
            if (open[n] < 4) continue;
            int low = Integer.MAX_VALUE;
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) low = Math.min(low, heights[(i * 2 + dx) * 48 + k * 2 + dz]);
            int cellTop = y0 + j * DeadEnds.CELL + 1;
            if (cellTop >= low - 4) g.set(i, j, k, DeadEnds.OUTSIDE);
            else g.set(i, j, k, wet[n] * 2 > open[n] ? DeadEnds.WATER : DeadEnds.AIR);
        }
        return g;
    }

    /**
     * Carves a planned tunnel, a little bent, 3 to 4 blocks across, unless it would let water in (water may lie under
     * it), come within 5 blocks of the ground, or cut through more than a few blocks that are not natural rock or soil;
     * and says which.
     */
    private static int carve(WorldGenLevel level, ChunkPos cp, DeadEnds.Grid g, DeadEnds.Link l, RandomSource random) {
        double ax = g.blockX(l.tip().i()) + 0.5, ay = g.blockY(l.tip().j()) + 0.5, az = g.blockZ(l.tip().k()) + 0.5;
        double bx = g.blockX(l.i()) + 0.5, by = g.blockY(l.j()) + 0.5, bz = g.blockZ(l.k()) + 0.5;
        double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        if (len < 1) return BLOCKED;
        // A sideways bend at the middle, up to 2 blocks.
        double px = -(bz - az) / len, pz = (bx - ax) / len, bend = (random.nextDouble() * 2 - 1) * 2;
        double mx = (ax + bx) / 2 + px * bend, my = (ay + by) / 2, mz = (az + bz) / 2 + pz * bend;
        double r = 1.5 + random.nextDouble() * 0.5;
        List<BlockPos> cut = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int blocked = 0, total = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (double s = 0; s <= 1.0001; s += 0.5 / len) {
            double u = 1 - s;
            double cx = u * u * ax + 2 * u * s * mx + s * s * bx, cy = u * u * ay + 2 * u * s * my + s * s * by, cz = u * u * az + 2 * u * s * mz + s * s * bz;
            int reach = (int) Math.ceil(r);
            for (int x = Mth.floor(cx - reach); x <= cx + reach; x++) for (int y = Mth.floor(cy - reach); y <= cy + reach; y++)
                for (int z = Mth.floor(cz - reach); z <= cz + reach; z++) {
                    double d = Math.sqrt((x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy) + (z + 0.5 - cz) * (z + 0.5 - cz));
                    if (d > r) continue;
                    if (Math.abs((x >> 4) - cp.x) > 1 || Math.abs((z >> 4) - cp.z) > 1) return EDGE;
                    if (!seen.add(at.set(x, y, z).asLong())) continue;
                    BlockState st = level.getBlockState(at);
                    if (!st.getFluidState().isEmpty()) return WET; // never through water
                    if (y > level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 6) return GROUND;
                    total++;
                    if (st.isAir()) continue;
                    if (st.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES) || st.is(CAVE_ROCK)) cut.add(at.immutable());
                    else blocked++;
                }
        }
        if (total == 0 || blocked > total * 0.15) return BLOCKED;
        // Water beside or over a block it opens would run in; water under it stays put.
        for (BlockPos p : cut)
            for (Direction dir : FLOWS_IN) {
                at.setWithOffset(p, dir);
                if (Math.abs((at.getX() >> 4) - cp.x) > 1 || Math.abs((at.getZ() >> 4) - cp.z) > 1) return EDGE;
                if (!level.getFluidState(at).isEmpty()) return WET;
            }
        for (BlockPos p : cut) level.setBlock(p, CAVE_AIR, 2);
        return CARVED;
    }
}
