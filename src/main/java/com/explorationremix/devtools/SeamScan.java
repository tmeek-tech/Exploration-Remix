package com.explorationremix.devtools;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.UpperShapes;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /remix seams [radius]}: where the Upper Caves' noise shapes meet the pack's own caves, in the loaded chunks
 * within radius chunks (4 if not given), from the old floor up to 4 blocks under the ground. It counts
 * <ul>
 *     <li>thin crusts: runs of 1 to 4 solid blocks, one over the other, with open space (air, water or a plant) over and
 *     under them, by thickness, and among them the seams: one side open in a shape, the other in the pack's caves
 *     (a floor of ours over the pack's cave, or the pack's floor over ours);</li>
 *     <li>what lies over and under the crusts of 1 to 3 blocks: our air, water or plants (open in a shape) or the pack's,
 *     the commonest pairs first;</li>
 *     <li>water over air: water with air right under it, which hangs (and falls once anything touches it);</li>
 *     <li>water beside air at its own level, which spills once anything touches it;</li>
 *     <li>one-block rims: water held on a side by a single block with open space past it;</li>
 *     <li>water over a thin crust (1 or 2 blocks with open space under them).</li>
 * </ul>
 * and lists the nearest seams and water over air, at least 12 blocks apart, for pictures. Leaves count as open (a bush
 * standing on a crust is not part of it). "Ours" is what {@link UpperShapes#openKind} says (the shapes and their water,
 * before any merging with the pack's caves), so the counts mean the same in worlds made before and after it.
 */
public final class SeamScan {
    private SeamScan() {}

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("seams").executes(c -> scan(c, 4))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 24)).executes(c -> scan(c, IntegerArgumentType.getInteger(c, "radius"))))
                .then(Commands.literal("count").then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                        .executes(c -> count(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("at").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> at(c, IntegerArgumentType.getInteger(c, "x"),
                                IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")))))));
    }

    /**
     * {@code /remix seams at <x> <y> <z>}: the column at x z from 8 blocks over y to 8 under it, as the world has it and as
     * the merge reads it ({@link UpperShapes#seamDebug}), with the shapes' summary of the column.
     */
    private static int at(CommandContext<CommandSourceStack> c, int x, int y, int z) {
        UpperShapes shapes = DeepWorld.shapes();
        if (shapes == null) {
            c.getSource().sendFailure(Component.literal("The Upper Caves do not grow from noise in this world."));
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        List<String> lines = shapes.seamDebug(x, z, y - 8, y + 8);
        StringBuilder b = new StringBuilder(shapes.describe(x, z)).append('\n').append(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            int h = y + 8 - (i - 1);
            BlockState s = level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null ? null : level.getBlockState(new BlockPos(x, h, z));
            String name = s == null ? "?" : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            b.append('\n').append(String.format(Locale.ROOT, "%-18s ", name)).append(lines.get(i));
        }
        String msg = b.toString();
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** {@code /remix seams count <radius>}: how many chunks within radius chunks are loaded, for timing world generation. */
    private static int count(CommandContext<CommandSourceStack> c, int radius) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 at = c.getSource().getPosition();
        int px = (int) Math.floor(at.x) >> 4, pz = (int) Math.floor(at.z) >> 4, n = 0, all = 0;
        for (int cx = px - radius; cx <= px + radius; cx++) for (int cz = pz - radius; cz <= pz + radius; cz++) {
            all++;
            if (level.getChunkSource().getChunkNow(cx, cz) != null) n++;
        }
        final int loaded = n, total = all;
        c.getSource().sendSuccess(() -> Component.literal("loaded " + loaded + " of " + total + " chunks"), false);
        return loaded;
    }

    private static boolean solid(BlockState s) {
        return s.blocksMotion() && !s.is(BlockTags.LEAVES) && s.getFluidState().isEmpty();
    }

    private static boolean water(BlockState s) {
        return s.getFluidState().is(FluidTags.WATER);
    }

    private static boolean air(BlockState s) {
        return s.isAir();
    }

    private static final String[] KIND = {"our air", "our water", "our plant", "pack air", "pack water", "pack plant"};

    /** What an open block is: our air, water or plant (open in a shape), or the pack's. */
    private static int kind(BlockState s, UpperShapes shapes, int x, int y, int z) {
        boolean ours = shapes != null && shapes.openKind(x, y, z) >= 0;
        return (ours ? 0 : 3) + (water(s) ? 1 : air(s) ? 0 : 2);
    }

    private static int scan(CommandContext<CommandSourceStack> c, int radius) {
        if (!DeepWorld.installed()) {
            c.getSource().sendFailure(Component.literal("The deep world is not installed in this world."));
            return 0;
        }
        UpperShapes shapes = DeepWorld.shapes();
        ServerLevel level = c.getSource().getLevel();
        Vec3 at = c.getSource().getPosition();
        int px = (int) Math.floor(at.x), pz = (int) Math.floor(at.z);
        int floor = DeepWorld.packFloor() + 1;
        long[] thin = new long[5], seamDown = new long[5], seamUp = new long[5];
        long waterOverAir = 0, waterBesideAir = 0, rims = 0, waterOnCrust = 0, plantsOnCrust = 0, columns = 0;
        List<int[]> seams = new ArrayList<>(), hanging = new ArrayList<>();
        long[][] pair = new long[6][6];
        List<List<int[]>> pairSpots = new ArrayList<>();
        java.util.Map<String, Long> sideCount = new java.util.TreeMap<>();
        java.util.Map<String, List<int[]>> sideSpots = new java.util.HashMap<>();
        for (int k = 0; k < 36; k++) pairSpots.add(new ArrayList<>());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int chunks = 0;
        for (int cx = (px >> 4) - radius; cx <= (px >> 4) + radius; cx++) {
            for (int cz = (pz >> 4) - radius; cz <= (pz >> 4) + radius; cz++) {
                LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz);
                if (ch == null) continue;
                // the four neighbours, for the water's sides at the chunk's edge
                if (level.getChunkSource().getChunkNow(cx + 1, cz) == null || level.getChunkSource().getChunkNow(cx - 1, cz) == null
                        || level.getChunkSource().getChunkNow(cx, cz + 1) == null || level.getChunkSource().getChunkNow(cx, cz - 1) == null) continue;
                chunks++;
                for (int x = cx << 4; x < (cx << 4) + 16; x++) for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                    columns++;
                    int top = ch.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15) - 4;
                    if (top <= floor) top = ch.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15) - 4;
                    int n = top - floor + 1;
                    if (n <= 2) continue;
                    BlockState[] col = new BlockState[n];
                    for (int i = 0; i < n; i++) col[i] = ch.getBlockState(m.set(x, floor + i, z));
                    // thin crusts: a run of solid blocks between open blocks
                    int i = 1;
                    while (i < n - 1) {
                        if (!solid(col[i]) || solid(col[i - 1])) { i++; continue; }
                        int j = i;
                        while (j < n && solid(col[j])) j++;
                        if (j >= n) break;
                        int len = j - i;
                        if (len <= 4) {
                            thin[len]++;
                            int yb = floor + i - 1, ya = floor + j;      // the open blocks under and over the run
                            boolean oursUnder = shapes != null && shapes.openKind(x, yb, z) >= 0;
                            boolean oursOver = shapes != null && shapes.openKind(x, ya, z) >= 0;
                            if (len <= 3) {
                                int ko = kind(col[j], shapes, x, ya, z), ku = kind(col[i - 1], shapes, x, yb, z);
                                pair[ko][ku]++;
                                pairSpots.get(ko * 6 + ku).add(new int[]{x, ya, z, len});
                            }
                            boolean airUnder = air(col[i - 1]);
                            int side = Integer.MIN_VALUE;
                            if (oursOver && !oursUnder && airUnder) {
                                seamDown[len]++;
                                if (len <= 2) seams.add(new int[]{x, ya, z, len});
                                side = yb;
                            } else if (oursUnder && !oursOver) {
                                seamUp[len]++;
                                if (len <= 2) seams.add(new int[]{x, ya, z, len});
                                side = ya;
                            }
                            if (side != Integer.MIN_VALUE && len <= 3) {
                                // how the merge reads the pack's side of the seam: its cave (so the merge should have
                                // opened the rock), rock (a feature's opening, or the open air over the ground), or stop
                                String mc = shapes.mergeClass(x, side, z);
                                BlockState sb = side == yb ? col[i - 1] : col[j];
                                String key = mc + (sb.is(net.minecraft.world.level.block.Blocks.CAVE_AIR) ? " (cave air)" : water(sb) ? " (water)" : "");
                                sideCount.merge(key, 1L, Long::sum);
                                sideSpots.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{x, side, z, len});
                            }
                            if (len <= 2 && airUnder) {
                                if (water(col[j])) waterOnCrust++;
                                else if (!air(col[j])) plantsOnCrust++;
                            }
                        }
                        i = j;
                    }
                    for (int k = 1; k < n - 1; k++) {
                        if (!water(col[k])) continue;
                        int y = floor + k;
                        if (air(col[k - 1])) {
                            waterOverAir++;
                            hanging.add(new int[]{x, y, z, 0});
                        }
                        boolean beside = false;
                        for (int d = 0; d < 4; d++) {
                            int dx = d == 0 ? 1 : d == 1 ? -1 : 0, dz = d == 2 ? 1 : d == 3 ? -1 : 0;
                            BlockState s = level.getBlockState(m.set(x + dx, y, z + dz));
                            if (air(s)) beside = true;
                            else if (solid(s) && !solid(level.getBlockState(m.set(x + 2 * dx, y, z + 2 * dz)))
                                    && !water(level.getBlockState(m.set(x + 2 * dx, y, z + 2 * dz)))) rims++;
                        }
                        if (beside) waterBesideAir++;
                    }
                }
            }
        }
        if (chunks == 0) {
            c.getSource().sendFailure(Component.literal("No loaded chunks (with their neighbours) round you."));
            return 0;
        }
        double per = 1.0 / chunks;
        say(c, String.format(Locale.ROOT, "%d chunks from y %d to 4 under the ground. Thin crusts (open over and under), per chunk: 1 block %.2f, 2 %.2f, 3 %.2f, 4 %.2f",
                chunks, floor, thin[1] * per, thin[2] * per, thin[3] * per, thin[4] * per));
        say(c, String.format(Locale.ROOT, "  shape over the pack's cave (air under): 1 block %.2f, 2 %.2f, 3 %.2f, 4 %.2f; the pack's over a shape: 1 %.2f, 2 %.2f, 3 %.2f, 4 %.2f",
                seamDown[1] * per, seamDown[2] * per, seamDown[3] * per, seamDown[4] * per, seamUp[1] * per, seamUp[2] * per, seamUp[3] * per, seamUp[4] * per));
        say(c, String.format(Locale.ROOT, "  on crusts of 1-2 blocks over air: water %.2f, plants or other blocks %.2f per chunk", waterOnCrust * per, plantsOnCrust * per));
        say(c, String.format(Locale.ROOT, "Water over air %d (%.2f a chunk), water beside air %d, one-block rims %d", waterOverAir, waterOverAir * per, waterBesideAir, rims));
        say(c, "Crusts of 1-3 blocks, what is over / under them (per chunk):");
        List<int[]> order = new ArrayList<>();
        for (int a = 0; a < 6; a++) for (int b = 0; b < 6; b++) if (pair[a][b] > 0) order.add(new int[]{a, b});
        order.sort((p, q) -> Long.compare(pair[q[0]][q[1]], pair[p[0]][p[1]]));
        List<int[]> shown = new ArrayList<>(order.subList(0, Math.min(12, order.size())));
        for (int[] o : order) if (!shown.contains(o) && (o[0] < 3 || o[1] < 3)) shown.add(o);
        for (int[] o : shown) {
            List<int[]> spots = pairSpots.get(o[0] * 6 + o[1]);
            spots.sort(java.util.Comparator.comparingDouble(a -> Math.hypot(a[0] - px, a[2] - pz)));
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < Math.min(3, spots.size()); k++) b.append(k == 0 ? " e.g. " : ", ").append(spots.get(k)[0]).append(' ').append(spots.get(k)[1]).append(' ').append(spots.get(k)[2]);
            say(c, String.format(Locale.ROOT, "  %s over %s: %.2f%s", KIND[o[0]], KIND[o[1]], pair[o[0]][o[1]] * per, b));
        }
        if (!sideCount.isEmpty()) {
            say(c, "Seams of 1-3 blocks, the pack's side as the merge reads it (per chunk):");
            for (java.util.Map.Entry<String, Long> e : sideCount.entrySet()) {
                List<int[]> spots = sideSpots.get(e.getKey());
                spots.sort(java.util.Comparator.comparingDouble(a -> Math.hypot(a[0] - px, a[2] - pz)));
                StringBuilder b = new StringBuilder();
                for (int k = 0; k < Math.min(4, spots.size()); k++) b.append(k == 0 ? " e.g. " : ", ").append(spots.get(k)[0]).append(' ').append(spots.get(k)[1]).append(' ').append(spots.get(k)[2]);
                say(c, String.format(Locale.ROOT, "  %s: %.2f%s", e.getKey(), e.getValue() * per, b));
            }
        }
        listNearest(c, "seam", seams, px, pz);
        listNearest(c, "water over air", hanging, px, pz);
        return chunks;
    }

    private static void listNearest(CommandContext<CommandSourceStack> c, String what, List<int[]> all, int px, int pz) {
        all.sort(java.util.Comparator.comparingDouble(a -> Math.hypot(a[0] - px, a[2] - pz)));
        List<int[]> picked = new ArrayList<>();
        for (int[] a : all) {
            boolean far = true;
            for (int[] b : picked) far &= Math.hypot(a[0] - b[0], a[2] - b[2]) >= 12 || Math.abs(a[1] - b[1]) > 12;
            if (far) picked.add(a);
            if (picked.size() >= 8) break;
        }
        for (int[] a : picked) say(c, String.format(Locale.ROOT, "  %s at %d %d %d%s", what, a[0], a[1], a[2], a[3] > 0 ? " (" + a[3] + " thick)" : ""));
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
