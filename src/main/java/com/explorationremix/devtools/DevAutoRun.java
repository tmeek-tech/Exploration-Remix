package com.explorationremix.devtools;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Headless world generation check, enabled with the environment variable ER_DEVRUN=1. Once the server is up it
 * generates the spawn cell's gates, checks that every passage is open, lists each layer's network, writes pictures of
 * the real blocks into {@code remix_devrun/}, then stops the server (unless ER_DEVRUN=keep).
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

            // The network of each layer, as /remix info tells it at the layer's entry landing. (0.2.0 walked its route
            // here; the route is gone, and the voxel tests walk the network instead.)
            for (int li = 0; li < s.layerCount; li++) {
                Plans.Gate en = t.plans.gate(li + 1, cx, cz);
                out.println("layer " + (li + 1) + " " + s.id(li) + ":");
                for (String line : t.network.describe(li, (int) Math.floor(en.lx), (int) Math.floor(en.lFloor) + 1, (int) Math.floor(en.lz)))
                    out.println("  " + line);
                out.flush();
            }

            // A slice through the whole cell, top to bottom.
            int mx = cx * t.settings.gateSpacing() + t.settings.gateSpacing() / 2, mz = cz * t.settings.gateSpacing() + t.settings.gateSpacing() / 2;
            long t0 = System.nanoTime();
            BlockImages.write(BlockImages.slice(level, "x", mx, mz, 300, s.floorY, 100), new File(dir, "cell_slice_x.png"), 1);
            out.printf(Locale.ROOT, "cell slice written (%.1f s)%n", (System.nanoTime() - t0) / 1e9);
            out.printf(Locale.ROOT, "total %.1f s%n", (System.nanoTime() - start) / 1e9);
        }
        ExplorationRemix.LOGGER.info("[devrun] report written to {}", dir.getAbsolutePath());
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
