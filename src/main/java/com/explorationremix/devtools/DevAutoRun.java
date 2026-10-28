package com.explorationremix.devtools;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Headless world generation check, enabled with the environment variable ER_DEVRUN=1. Once the server is up it
 * generates the spawn cell's gates and routes, checks that every passage and route is open, writes pictures of the
 * real blocks into {@code remix_devrun/}, then stops the server (unless ER_DEVRUN=keep).
 */
public final class DevAutoRun {
    private static int ticks = -1;
    private static MinecraftServer server;

    private DevAutoRun() {}

    public static boolean enabled() { return System.getenv("ER_DEVRUN") != null; }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        server = event.getServer();
        ticks = 0;
        ExplorationRemix.LOGGER.info("[devrun] server started, running the world generation check");
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (ticks < 0 || event.phase != TickEvent.Phase.END) return;
        ticks++;
        if (ticks == 20) {
            try {
                run();
            } catch (Throwable t) {
                ExplorationRemix.LOGGER.error("[devrun] failed", t);
            }
        }
        if (ticks == 40 && !"keep".equals(System.getenv("ER_DEVRUN"))) {
            ExplorationRemix.LOGGER.info("[devrun] done, stopping server");
            server.halt(false);
        }
    }

    private static void run() throws IOException {
        File dir = new File(server.getServerDirectory(), "remix_devrun");
        dir.mkdirs();
        ServerLevel level = server.overworld();
        long start = System.nanoTime();
        try (PrintWriter out = new PrintWriter(new FileWriter(new File(dir, "report.txt")))) {
            Terrain t = DeepWorld.terrain();
            out.println("installed=" + DeepWorld.installed() + " minBuild=" + level.getMinBuildHeight() + " height=" + level.getHeight()
                    + " packFloor=" + DeepWorld.packFloor() + " floorY=" + DeepWorld.floorY());
            if (t == null || !DeepWorld.installed()) { out.println("NOT INSTALLED"); return; }
            if (level.getMinBuildHeight() != t.shape.floorY) out.println("HEIGHT MISMATCH: the world's floor is not the deep floor");
            Shape s = t.shape;
            for (int i = 0; i < s.layerCount; i++) {
                out.printf(Locale.ROOT, "layer %d %s: y %d..%d, fluid table y %d (%s), band above y %d..%d%n", i + 1, s.id(i), s.bottom(i), s.top(i),
                        s.waterY(i) - 1, s.lava(i) ? "lava" : "water", s.bandBottom(i + 1), s.bandTop(i + 1));
            }
            BlockPos spawn = level.getSharedSpawnPos();
            int cx = t.plans.cellOf(spawn.getX()), cz = t.plans.cellOf(spawn.getZ());
            String cellEnv = System.getenv("ER_DEVRUN_CELL");
            if (cellEnv != null && cellEnv.contains(",")) {
                cx = Integer.parseInt(cellEnv.split(",")[0].trim());
                cz = Integer.parseInt(cellEnv.split(",")[1].trim());
            }
            out.println("cell " + cx + " " + cz + " (spawn " + spawn.toShortString() + ")");
            out.flush();

            // Gates: walk each passage's centre line; every block on it must be open and dry. Plants and other
            // blocks a player walks through are listed but don't block the way.
            for (int b = 1; b <= s.layerCount; b++) {
                Plans.Gate g = t.plans.gate(b, cx, cz);
                long t0 = System.nanoTime();
                int samples = 0, solid = 0, wet = 0;
                Map<String, Integer> passable = new TreeMap<>(), blocking = new TreeMap<>();
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (int i = 0; i <= 400; i++) {
                    double tt = i / 400.0;
                    p.set(Math.floor(g.px(tt)), Math.floor(g.py(tt)), Math.floor(g.pz(tt)));
                    BlockState st = level.getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.FULL, true).getBlockState(p);
                    samples++;
                    if (!st.getFluidState().isEmpty()) wet++;
                    else if (!st.isAir()) {
                        String name = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.getBlock()).toString();
                        if (st.blocksMotion()) { solid++; blocking.merge(name, 1, Integer::sum); }
                        else passable.merge(name, 1, Integer::sum);
                    }
                }
                out.printf(Locale.ROOT, "gate %d: upper landing %.0f %.0f %.0f, lower landing %.0f %.0f %.0f, passage samples %d, blocked %d, flooded %d%s (%.1f s)%n",
                        b, g.ux, g.uFloor + 1, g.uz, g.lx, g.lFloor + 1, g.lz, samples, solid, wet, solid + wet == 0 ? "  OK" : "  PROBLEM",
                        (System.nanoTime() - t0) / 1e9);
                if (!blocking.isEmpty()) out.println("  blocking: " + blocking);
                if (!passable.isEmpty()) out.println("  passable: " + passable);
                out.flush();
                double y0 = Math.min(g.lFloor, g.uFloor) - 24, y1 = Math.max(g.lFloor, g.uFloor) + 40;
                BlockImages.write(along(level, g.ux, g.uz, g.lx, g.lz, 40, (int) y0, (int) y1), new File(dir, "gate_" + b + ".png"), 3);
            }

            // Routes: walk each one like a player, from the entry landing to the exit landing (or the bedrock landing).
            Map<String, Integer> biomes = new TreeMap<>();
            for (int li = 0; li < s.layerCount; li++) {
                Route r = t.plans.route(li, cx, cz);
                long t0 = System.nanoTime();
                Walker wk = new Walker(level);
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                int w = (int) Math.ceil(r.walkLength) + 1, h = s.top(li) - s.bottom(li) + 1;
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Map<String, Integer> blocks = new HashMap<>();
                double[] q0 = r.pathAt(0);
                wk.start(q0[2] + 1);
                for (int i = 0; i < w; i++) {
                    double[] q = r.pathAt(i);
                    int x = (int) Math.floor(q[0]), z = (int) Math.floor(q[1]);
                    ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
                    for (int y = s.bottom(li); y <= s.top(li); y++) {
                        p.set(x, y, z);
                        BlockState st = chunk.getBlockState(p);
                        img.setRGB(i, s.top(li) - y, BlockImages.color(level, p, st));
                        if (!st.isAir() && st.getFluidState().isEmpty()) {
                            String id = String.valueOf(ForgeRegistries.BLOCKS.getKey(st.getBlock()));
                            blocks.merge(id, 1, Integer::sum);
                        }
                    }
                    int plan = (int) Math.floor(q[2]);
                    if (plan >= s.bottom(li) && plan <= s.top(li)) img.setRGB(i, s.top(li) - plan, 0xE8C547);
                    boolean landing = r.exit != null ? Math.hypot(q[0] - r.exit.ux, q[1] - r.exit.uz) < r.exit.landR
                            : Math.hypot(q[0] - r.endX, q[1] - r.endZ) < r.endR;
                    if (landing) continue;
                    double feet = wk.step(x, z, q[2] + 1, i);
                    int fy = (int) Math.floor(feet);
                    if (fy >= s.bottom(li) && fy <= s.top(li)) img.setRGB(i, s.top(li) - fy, 0xE0453A);
                    if (i % 2 == 0) {
                        Holder<Biome> bh = level.getBiome(p.set(x, fy + 1, z));
                        biomes.merge(s.id(li) + " " + bh.unwrapKey().map(k -> k.location().toString()).orElse("?"), 1, Integer::sum);
                    }
                }
                boolean ok = wk.tooHigh + wk.noFooting + wk.wet + wk.bigDrops == 0;
                out.printf(Locale.ROOT, "route %d %s: walking line %.0f blocks, %d steps walked, jumps %d, too high %d, drops over 3 %d, no footing %d, wet %d, more than 2 off the plan %d%s (%.1f s)%n",
                        li + 1, s.id(li), r.walkLength, wk.steps, wk.jumps, wk.tooHigh, wk.bigDrops, wk.noFooting, wk.wet, wk.offPlan, ok ? "  OK" : "  PROBLEM",
                        (System.nanoTime() - t0) / 1e9);
                if (wk.where.length() > 0) out.println("  where:" + wk.where);
                out.println("  halls " + r.halls.size() + ", bridges " + r.bridges.size() + ", geodes " + r.geodes.size()
                        + (r.bedrockEnd ? String.format(Locale.ROOT, ", bedrock landing at %.0f %.0f %.0f", r.endX, r.endFloor + 1, r.endZ) : ""));
                StringBuilder sb = new StringBuilder("  rock along the route:");
                blocks.entrySet().stream().sorted((a, bb) -> bb.getValue() - a.getValue()).limit(12)
                        .forEach(e -> sb.append(' ').append(e.getKey().replace("minecraft:", "")).append('=').append(e.getValue()));
                out.println(sb);
                out.flush();
                BlockImages.write(img, new File(dir, "route_" + (li + 1) + ".png"), 2);
            }
            out.println("biomes on the route paths: " + biomes);

            // A slice through the whole cell, top to bottom.
            int mx = cx * t.settings.gateSpacing() + t.settings.gateSpacing() / 2, mz = cz * t.settings.gateSpacing() + t.settings.gateSpacing() / 2;
            long t0 = System.nanoTime();
            BlockImages.write(BlockImages.slice(level, "x", mx, mz, 300, s.floorY, 100), new File(dir, "cell_slice_x.png"), 1);
            out.printf(Locale.ROOT, "cell slice written (%.1f s)%n", (System.nanoTime() - t0) / 1e9);
            out.printf(Locale.ROOT, "total %.1f s%n", (System.nanoTime() - start) / 1e9);
        }
        ExplorationRemix.LOGGER.info("[devrun] report written to {}", dir.getAbsolutePath());
    }

    /**
     * Walks a route the way a player would: keeps its height where it can, steps or jumps up at most 1.25 blocks,
     * and falls to the next floor below where the floor ends. Slabs and other low blocks count as floor.
     */
    private static final class Walker {
        final ServerLevel level;
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double h;
        int steps, jumps, tooHigh, bigDrops, noFooting, wet, offPlan;
        final StringBuilder where = new StringBuilder();

        Walker(ServerLevel level) { this.level = level; }

        void start(double feet) { h = feet; }

        /** Moves onto column (x, z); returns the new standing height (the planned one when there is no footing). */
        double step(int x, int z, double plan, int at) {
            steps++;
            double best = Double.NaN, fall = Double.NaN;
            int top = (int) Math.floor(h) + 2, bottom = (int) Math.floor(h) - 9;
            for (int y = top; y >= bottom; y--) {
                double sh = standHeight(x, y, z);
                if (Double.isNaN(sh)) continue;
                double dh = sh - h;
                if (dh <= 1.25 && dh >= -0.6) { if (Double.isNaN(best) || Math.abs(dh) < Math.abs(best - h)) best = sh; }
                else if (dh < -0.6 && Double.isNaN(fall)) fall = sh;
            }
            double nh;
            if (!Double.isNaN(best)) {
                if (best - h > 0.6) jumps++;
                nh = best;
            } else if (!Double.isNaN(fall)) {
                if (h - fall > 3.01) { bigDrops++; note(String.format(Locale.ROOT, " drop %.1f at %d (%d %d %d)", h - fall, at, x, (int) Math.floor(fall), z)); }
                nh = fall;
            } else {
                boolean higher = false;
                for (int y = top + 1; y <= top + 4 && !higher; y++) higher = !Double.isNaN(standHeight(x, y, z));
                if (higher) { tooHigh++; note(" too high at " + at + " (" + x + " " + top + " " + z + ")"); }
                else { noFooting++; note(" no footing at " + at + " (" + x + " " + (int) Math.floor(plan) + " " + z + ")"); }
                nh = plan;
            }
            BlockState feet = level.getBlockState(p.set(x, (int) Math.floor(nh), z));
            if (!feet.getFluidState().isEmpty()) { wet++; note(" wet at " + at); }
            if (Math.abs(nh - plan) > 2.01) offPlan++;
            h = nh;
            return nh;
        }

        private void note(String s) { if (where.length() < 600) where.append(s); }

        /** Height a player stands at with their feet in block y, or NaN when they can't stand or don't fit there. */
        private double standHeight(int x, int y, int z) {
            BlockState f = level.getBlockState(p.set(x, y, z));
            double sh;
            VoxelShape fs = f.getCollisionShape(level, p);
            if (fs.isEmpty()) {
                BlockState b = level.getBlockState(p.set(x, y - 1, z));
                VoxelShape bs = b.getCollisionShape(level, p);
                if (bs.isEmpty()) return Double.NaN;
                sh = y - 1 + bs.max(Direction.Axis.Y);
                if (sh < y - 0.55) return Double.NaN; // a low block below an empty one: you'd stand on the block under it
            } else {
                double top = fs.max(Direction.Axis.Y);
                if (top > 0.55) return Double.NaN;
                sh = y + top;
            }
            for (int yy = (int) Math.floor(sh + 0.01); yy <= (int) Math.floor(sh + 1.79); yy++) {
                if (yy == y && !fs.isEmpty()) continue;
                BlockState a = level.getBlockState(p.set(x, yy, z));
                if (!a.getCollisionShape(level, p).isEmpty()) return Double.NaN;
            }
            return sh;
        }
    }

    /** A vertical slice of real blocks along the straight line from (x0, z0) to (x1, z1), extended by pad at each end. */
    private static BufferedImage along(ServerLevel level, double x0, double z0, double x1, double z1, int pad, int y0, int y1) {
        double len = Math.max(1, Math.hypot(x1 - x0, z1 - z0));
        double dx = (x1 - x0) / len, dz = (z1 - z0) / len;
        int w = (int) Math.ceil(len) + pad * 2, h = y1 - y0 + 1;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < w; i++) {
            double d = i - pad;
            int x = (int) Math.floor(x0 + dx * d), z = (int) Math.floor(z0 + dz * d);
            ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
            for (int y = y0; y <= y1; y++) {
                p.set(x, y, z);
                img.setRGB(i, y1 - y, BlockImages.color(level, p, chunk.getBlockState(p)));
            }
        }
        return img;
    }
}
