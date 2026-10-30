package com.explorationremix.world.feature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeadEnds;
import com.explorationremix.world.terrain.Shape;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
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
 * of 3 to 4 blocks across through rock (never through water, never within 5 blocks of the ground, never through
 * anything but natural rock and soil). Off with the world option {@code joinDeadEnds}.
 */
public final class JoinDeadEndsFeature extends Feature<NoneFeatureConfiguration> {
    private static final TagKey<Block> CAVE_ROCK = TagKey.create(Registries.BLOCK, new ResourceLocation(ExplorationRemix.MOD_ID, "cave_rock"));
    private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
    /** At most this many tunnels a chunk. */
    private static final int MAX_LINKS = 3;
    /** The grid's highest cell row: about 220 blocks over its floor. */
    private static final int MAX_ROWS = 110;
    /** Since the server started: chunks looked at, blind ends found in them, and tunnels carved (for /remix deadends). */
    public static final java.util.concurrent.atomic.AtomicLong CHUNKS = new java.util.concurrent.atomic.AtomicLong(),
            FOUND = new java.util.concurrent.atomic.AtomicLong(), JOINED = new java.util.concurrent.atomic.AtomicLong();

    public JoinDeadEndsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        if (!DeepWorld.installed() || !ERConfig.INSTANCE.joinDeadEnds.get()) return false;
        Shape shape = DeepWorld.shape();
        if (shape == null) return false;
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
            DeadEnds.Link l = DeadEnds.link(g, t);
            if (l == null || !carve(level, cp, g, l, ctx.random())) continue;
            DeadEnds.open(g, l);
            made++;
        }
        JOINED.addAndGet(made);
        return made > 0;
    }

    /**
     * The grid over a chunk and the eight round it, from {@code yMin} up to the highest ground: rock, air, water (any
     * fluid) or outside (open within 4 blocks of the ground, or over it). Null if a chunk of the nine is missing.
     */
    public static DeadEnds.Grid read(LevelAccessor level, ChunkPos cp, int yMin, Heightmap.Types ground) {
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
     * Carves a planned tunnel, a little bent, 3 to 4 blocks across, unless it would let water in, come within 5 blocks
     * of the ground, or cut through more than a few blocks that are not natural rock or soil.
     */
    private static boolean carve(WorldGenLevel level, ChunkPos cp, DeadEnds.Grid g, DeadEnds.Link l, RandomSource random) {
        double ax = g.blockX(l.tip().i()) + 0.5, ay = g.blockY(l.tip().j()) + 0.5, az = g.blockZ(l.tip().k()) + 0.5;
        double bx = g.blockX(l.i()) + 0.5, by = g.blockY(l.j()) + 0.5, bz = g.blockZ(l.k()) + 0.5;
        double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        if (len < 1) return false;
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
            int reach = (int) Math.ceil(r + 1);
            for (int x = Mth.floor(cx - reach); x <= cx + reach; x++) for (int y = Mth.floor(cy - reach); y <= cy + reach; y++)
                for (int z = Mth.floor(cz - reach); z <= cz + reach; z++) {
                    double d = Math.sqrt((x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy) + (z + 0.5 - cz) * (z + 0.5 - cz));
                    if (d > r + 1) continue;
                    if (Math.abs((x >> 4) - cp.x) > 1 || Math.abs((z >> 4) - cp.z) > 1) return false;
                    at.set(x, y, z);
                    BlockState st = level.getBlockState(at);
                    if (!st.getFluidState().isEmpty()) return false; // no water or lava within a block of it
                    if (d > r || !seen.add(at.asLong())) continue;
                    if (y > level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 6) return false;
                    total++;
                    if (st.isAir()) continue;
                    if (st.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES) || st.is(CAVE_ROCK)) cut.add(at.immutable());
                    else blocked++;
                }
        }
        if (total == 0 || blocked > total * 0.15) return false;
        for (BlockPos p : cut) level.setBlock(p, CAVE_AIR, 2);
        return true;
    }
}
