package com.explorationremix.world.feature;

import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.ChoirSites;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the Hollow Choir's arena (0.21.9): the bowl of worked deepslate sunk into a deep dark cavern's floor, its
 * seats, its two ramps, its rim walk with the three key sockets, its braziers, its broken pillars and its dais. Where
 * the bowl goes is planned per cell ({@link ChoirSites}) and its whole shape is a function of that site
 * ({@link ChoirArena}), so every chunk builds its own part of it and they meet exactly.
 *
 * <p>It runs in the deep dark's own chunks, in the last step, after the ways are opened and the sculk is laid, so it
 * lays its stone over whatever the cavern had. It writes nothing outside its own chunk and reads nothing outside it,
 * and it lays the bowl's rock itself rather than trusting what is under the cavern's floor, so a rift or a lower
 * cavern passing beneath cannot leave the arena with a hole in it.
 *
 * <p>Pillars are the one thing it cannot fix after the fact: a stone pillar standing through the bowl would be left
 * hanging from the ceiling, so the planner leaves those unstood instead ({@code Network.dropExposedPillars}).
 */
public final class ChoirArenaFeature extends Feature<NoneFeatureConfiguration> {
    /** For /remix choir: chunks that built part of an arena since the game started. */
    public static final AtomicLong CHUNKS = new AtomicLong();

    public ChoirArenaFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        // every arena lies at least 72 blocks inside its cell, so only the cell this chunk's middle is in can hold one
        ChoirSites.Site s = t.network.choirSite(t.plans.cellOf(minX + 8), t.plans.cellOf(minZ + 8));
        if (s == null || !s.mayReach(minX, minZ, minX + 15, minZ + 15)) return false;
        CHUNKS.incrementAndGet();

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= minX + 15; x++)
            for (int z = minZ; z <= minZ + 15; z++) column(level, s, x, z, p);
        fittings(level, s, minX, minZ, p);
        return true;
    }

    /** One column of the arena: its top block, the worked stone under it, a plug under that, and air above. */
    private static void column(WorldGenLevel level, ChoirSites.Site s, int x, int z, BlockPos.MutableBlockPos p) {
        int top = ChoirArena.topY(s, x + 0.5, z + 0.5);
        if (top == ChoirArena.NONE) return;
        // the apron only takes the rim's stone where the cavern's own floor is about level with it
        double r = Math.hypot(x + 0.5 - s.x(), z + 0.5 - s.z());
        if (r > ChoirSites.RIM_R) {
            int ground = groundNear(level, x, z, top, p);
            if (ground == ChoirArena.NONE) return;
            top = ground;
        }
        level.setBlock(p.set(x, top, z), ChoirArena.surface(s, x, z), 2);
        BlockState sub = ChoirArena.sub(s, x, z);
        for (int d = 1; d <= ChoirArena.SUB; d++) level.setBlock(p.set(x, top - d, z), sub, 2);
        // only a void or water under the bowl is plugged: the first real rock ends it
        for (int d = ChoirArena.SUB + 1; d <= ChoirArena.PLUG; d++) {
            p.set(x, top - d, z);
            BlockState was = level.getBlockState(p);
            if (!was.isAir() && was.getFluidState().isEmpty()) break;
            level.setBlock(p, ChoirArena.plug(), 2);
        }
        int upTo = (int) s.rimY() + ChoirArena.CLEAR_UP;
        for (int y = top + 1; y <= upTo; y++) {
            p.set(x, y, z);
            if (level.getBlockState(p).isAir()) continue;
            level.setBlock(p, Blocks.CAVE_AIR.defaultBlockState(), 2);
        }
    }

    /** The cavern's own floor in an apron column, within a few blocks of the rim's level, or NONE. */
    private static int groundNear(WorldGenLevel level, int x, int z, int want, BlockPos.MutableBlockPos p) {
        for (int d = 0; d <= 3; d++)
            for (int sign = 1; sign >= -1; sign -= 2) {
                int y = want + sign * d;
                p.set(x, y, z);
                if (!level.getBlockState(p).isSolidRender(level, p)) continue;
                p.set(x, y + 1, z);
                if (level.getBlockState(p).isAir()) return y;
            }
        return ChoirArena.NONE;
    }

    /** The sockets, the braziers, the broken pillars and the dais ring, each from the chunk that holds its column. */
    private static void fittings(WorldGenLevel level, ChoirSites.Site s, int minX, int minZ, BlockPos.MutableBlockPos p) {
        int maxX = minX + 15, maxZ = minZ + 15;
        int rim = (int) s.rimY();
        for (int[] c : ChoirArena.sockets(s)) {
            if (!in(c[0], c[1], minX, minZ, maxX, maxZ)) continue;
            level.setBlock(p.set(c[0], rim, c[1]), Blocks.CHISELED_DEEPSLATE.defaultBlockState(), 2);
            level.setBlock(p.set(c[0], rim + 1, c[1]), ERBlocks.KEY_SOCKET.get().defaultBlockState(), 2);
        }
        for (int[] b : ChoirArena.braziers(s)) {
            if (!in(b[0], b[1], minX, minZ, maxX, maxZ)) continue;
            level.setBlock(p.set(b[0], b[2], b[1]), ChoirArena.brazier(true), 2);
        }
        for (ChoirArena.Stump st : ChoirArena.stumps(s)) {
            for (int dx = 0; dx < 2; dx++)
                for (int dz = 0; dz < 2; dz++) {
                    int x = st.x() + dx, z = st.z() + dz;
                    if (!in(x, z, minX, minZ, maxX, maxZ)) continue;
                    int foot = ChoirArena.topY(s, x + 0.5, z + 0.5);
                    if (foot == ChoirArena.NONE) continue;
                    for (int h = 1; h <= st.height(); h++)
                        level.setBlock(p.set(x, foot + h, z), ChoirArena.stumpBlock(h == st.height()), 2);
                }
        }
    }

    private static boolean in(int x, int z, int minX, int minZ, int maxX, int maxZ) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
