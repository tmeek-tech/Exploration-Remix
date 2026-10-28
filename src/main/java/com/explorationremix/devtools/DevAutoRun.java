package com.explorationremix.devtools;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.explorationremix.world.plan.CellPlan;
import com.explorationremix.world.plan.Chamber;
import com.explorationremix.world.plan.Gate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless smoke test, enabled with the environment variable ER_DEVRUN=1: once the server is up it generates the
 * chunks around the first gate of the spawn cell, dumps block columns and density slices into {@code remix_devrun/},
 * then stops the server. Used by the build to check world generation without a client.
 */
public final class DevAutoRun {
    private static int ticks = -1;
    private static MinecraftServer server;

    private DevAutoRun() {}

    public static boolean enabled() { return System.getenv("ER_DEVRUN") != null; }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!enabled()) return;
        server = event.getServer();
        ticks = 0;
        ExplorationRemix.LOGGER.info("[devrun] server started, running the world generation smoke test");
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (ticks < 0 || event.phase != TickEvent.Phase.END) return;
        ticks++;
        if (ticks == 10) {
            try {
                run();
            } catch (Throwable t) {
                ExplorationRemix.LOGGER.error("[devrun] failed", t);
            }
        }
        if (ticks == 30) {
            ExplorationRemix.LOGGER.info("[devrun] done, stopping server");
            server.halt(false);
        }
    }

    private static void run() throws IOException {
        File dir = new File(server.getServerDirectory(), "remix_devrun");
        dir.mkdirs();
        ServerLevel level = server.overworld();
        try (PrintWriter out = new PrintWriter(new FileWriter(new File(dir, "report.txt")))) {
            out.println("installed=" + DeepWorld.installed() + " packFloor=" + DeepWorld.packFloor() + " floorY=" + DeepWorld.floorY()
                    + " minBuild=" + level.getMinBuildHeight() + " height=" + level.getHeight());
            for (Layer l : DeepWorld.layers()) out.println("layer " + l.id() + " y " + l.bottom() + ".." + l.top());
            if (!DeepWorld.installed()) return;
            BlockPos spawn = level.getSharedSpawnPos();
            Gate g1 = DeepWorld.plans().gateAt(1, spawn.getX(), spawn.getZ());
            out.printf("spawn %s, gate1 upper %.0f %.0f %.0f lower %.0f %.0f %.0f%n", spawn, g1.upperX(), g1.upperY(), g1.upperZ(), g1.lowerX(), g1.lowerY(), g1.lowerZ());
            List<BlockPos> probes = new ArrayList<>();
            probes.add(new BlockPos((int) g1.upperX(), (int) g1.upperY(), (int) g1.upperZ()));
            probes.add(new BlockPos((int) g1.lowerX(), (int) g1.lowerY(), (int) g1.lowerZ()));
            for (Layer l : DeepWorld.layers()) {
                CellPlan plan = DeepWorld.plans().cellAt(l.index(), spawn.getX(), spawn.getZ());
                for (Chamber ch : plan.chambers) {
                    if (ch.kind() == Chamber.Kind.REFUGE || ch.kind() == Chamber.Kind.CATHEDRAL) probes.add(new BlockPos((int) ch.x(), (int) ch.y(), (int) ch.z()));
                }
                double[] mid = plan.routePoint(0.5);
                probes.add(new BlockPos((int) mid[0], (int) mid[1], (int) mid[2]));
                if (plan.bedrockLanding != null) probes.add(new BlockPos((int) plan.bedrockLanding.x(), (int) plan.bedrockLanding.y(), (int) plan.bedrockLanding.z()));
            }
            long t0 = System.nanoTime();
            int generated = 0;
            for (BlockPos p : probes) {
                ChunkPos cp = new ChunkPos(p);
                level.getChunk(cp.x, cp.z, ChunkStatus.FULL, true);
                generated++;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            out.println("generated " + generated + " chunks in " + ms + " ms (" + (ms / Math.max(1, generated)) + " ms/chunk, includes neighbours)");
            for (BlockPos p : probes) {
                out.println("column " + p.getX() + "," + p.getZ() + " (probe y " + p.getY() + ")");
                dumpColumn(level, p.getX(), p.getZ(), out);
                BlockState at = level.getBlockState(p);
                out.println("  at probe: " + ForgeRegistries.BLOCKS.getKey(at.getBlock()) + " biome " + level.getBiome(p).unwrapKey().map(k -> k.location().toString()).orElse("?"));
            }
            // The gate tube: every block along its centre line should be open.
            int open = 0, total = 0;
            StringBuilder tube = new StringBuilder();
            for (int i = 0; i <= 24; i++) {
                double t = i / 24.0;
                BlockPos tp = new BlockPos((int) Math.floor(g1.px(t)), (int) Math.floor(g1.py(t)), (int) Math.floor(g1.pz(t)));
                ChunkPos cp = new ChunkPos(tp);
                level.getChunk(cp.x, cp.z, ChunkStatus.FULL, true);
                BlockState st = level.getBlockState(tp);
                boolean isOpen = st.isAir() || !st.getFluidState().isEmpty();
                if (isOpen) open++;
                total++;
                tube.append(isOpen ? '.' : '#');
                if (i % 6 == 0) tube.append('[').append(tp.getX()).append(',').append(tp.getY()).append(',').append(tp.getZ()).append(']');
            }
            out.println("gate1 tube centre line: " + open + "/" + total + " open " + tube);
            tubeSlice(level, g1, new File(dir, "slice_gate1_tube.png"), out);
            slice(level, (int) g1.upperX(), (int) g1.upperZ(), 384, true, new File(dir, "slice_gate1_x.png"), out);
            slice(level, (int) g1.upperX(), (int) g1.upperZ(), 384, false, new File(dir, "slice_gate1_z.png"), out);
            ChunkAccess chunk = level.getChunk(new BlockPos((int) g1.lowerX(), 0, (int) g1.lowerZ()));
            out.println("sections in a deep chunk: " + chunk.getSections().length);
        }
    }

    /** A slice that follows the gate's centre line (extended along its tangents), so the whole tube is visible. */
    private static void tubeSlice(ServerLevel level, Gate g, File file, PrintWriter out) throws IOException {
        int width = 160;
        int minY = Math.max(level.getMinBuildHeight(), (int) g.lowerY() - 60), maxY = Math.min(level.getMaxBuildHeight(), (int) g.upperY() + 60);
        BufferedImage img = new BufferedImage(width, maxY - minY, BufferedImage.TYPE_INT_RGB);
        long t0 = System.nanoTime();
        for (int i = 0; i < width; i++) {
            double t = -0.5 + 2.0 * i / (width - 1);
            double x, z;
            if (t < 0) { x = g.px(0) + (g.px(0) - g.px(0.05)) * (-t / 0.05); z = g.pz(0) + (g.pz(0) - g.pz(0.05)) * (-t / 0.05); }
            else if (t > 1) { x = g.px(1) + (g.px(1) - g.px(0.95)) * ((t - 1) / 0.05); z = g.pz(1) + (g.pz(1) - g.pz(0.95)) * ((t - 1) / 0.05); }
            else { x = g.px(t); z = g.pz(t); }
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            level.getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, true);
            for (int y = minY; y < maxY; y++) {
                BlockState st = level.getBlockState(new BlockPos(bx, y, bz));
                int rgb;
                if (st.isAir()) rgb = 0;
                else if (!st.getFluidState().isEmpty()) rgb = st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? 0xc04010 : 0x2050c0;
                else if (st.is(com.explorationremix.registry.ERBlocks.BAND_STONE.get())) rgb = 0x101018;
                else rgb = 0x7a7a7a;
                boolean onLine = t >= 0 && t <= 1 && Math.abs(y - g.py(t)) < 0.5;
                if (onLine) rgb = 0x00ff00;
                img.setRGB(i, maxY - 1 - y, rgb);
            }
        }
        ImageIO.write(img, "png", file);
        out.println("tube slice " + file.getName() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }

    private static void dumpColumn(ServerLevel level, int x, int z, PrintWriter out) {
        String last = null;
        int runStart = level.getMaxBuildHeight() - 1;
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            String id = String.valueOf(ForgeRegistries.BLOCKS.getKey(st.getBlock()));
            if (!id.equals(last)) {
                if (last != null) out.println("  " + runStart + ".." + (y + 1) + " " + last);
                last = id;
                runStart = y;
            }
        }
        out.println("  " + runStart + ".." + level.getMinBuildHeight() + " " + last);
    }

    private static void slice(ServerLevel level, int cx, int cz, int width, boolean alongX, File file, PrintWriter out) throws IOException {
        RandomState rs = level.getChunkSource().randomState();
        DensityFunction density = rs.router().finalDensity();
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        BufferedImage img = new BufferedImage(width, maxY - minY, BufferedImage.TYPE_INT_RGB);
        long t0 = System.nanoTime();
        for (int i = 0; i < width; i++) {
            int x = alongX ? cx - width / 2 + i : cx;
            int z = alongX ? cz : cz - width / 2 + i;
            for (int y = minY; y < maxY; y++) {
                double d = density.compute(new DensityFunction.SinglePointContext(x, y, z));
                int rgb;
                if (d > 0) {
                    int band = DeepWorld.bandAt(y);
                    Layer l = DeepWorld.layerAt(y);
                    if (y < DeepWorld.floorY() + 5) rgb = 0x202020;
                    else if (band != 0) rgb = 0x101018;
                    else if (l != null) rgb = switch (l.index()) { case 0 -> 0x6a5a40; case 1 -> 0x4a5a70; case 2 -> 0x70402a; default -> 0x3a3a40; };
                    else rgb = 0x7a7a7a;
                } else {
                    rgb = 0;
                    Layer l = DeepWorld.layerAt(y);
                    if (l != null && y < DeepWorld.packFloor()) {
                        int table = com.explorationremix.world.gen.DeepFluids.tableLevel(l, DeepWorld.plans(), x, z);
                        if (y < table && !DeepWorld.plans().cellAt(l.index(), x, z).insideRefuge(x, y, z)) rgb = l.fluid() == Layer.FluidKind.LAVA ? 0xc04010 : 0x2050c0;
                    }
                }
                img.setRGB(i, maxY - 1 - y, rgb);
            }
        }
        ImageIO.write(img, "png", file);
        out.println("slice " + file.getName() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }
}
