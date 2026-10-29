package com.explorationremix.devtools;

import com.explorationremix.ExplorationRemix;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Development only, and only when the environment variable ER_SHOTS names a shot list: once a world is open (single
 * player, or a server where the player is an operator), takes a screenshot of each listed biome from the best view
 * /remix view finds, then quits. Each line of the list is "file-name biome [n]", or "file-name @ x y z yaw pitch" for
 * a picture from a chosen spot. Screenshots go to the game's screenshots folder.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class DevShots {
    private static final String LIST = System.getenv("ER_SHOTS");
    /** The least ticks to wait at each view (ER_SHOTS_SETTLE), for slow renderers. */
    private static final int SETTLE = Integer.getInteger("er.shots.settle", envInt("ER_SHOTS_SETTLE", 300));

    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private enum Phase { WAIT_WORLD, SETUP, NEXT, VIEWING, SETTLING, SHOOT, DONE }

    private static Phase phase = Phase.WAIT_WORLD;
    private static List<String[]> shots;
    private static int index = -1, ticks, stable, lastChunks;
    private static volatile boolean viewDone;
    private static double startX, startY, startZ;
    private static final List<String> log = new ArrayList<>();

    private DevShots() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (LIST == null || e.phase != TickEvent.Phase.END || phase == Phase.DONE) return;
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) return;
        boolean remote = server == null;
        ticks++;
        switch (phase) {
            case WAIT_WORLD -> {
                if (ticks < 200) return;
                try {
                    shots = new ArrayList<>();
                    for (String line : Files.readAllLines(Path.of(LIST), StandardCharsets.UTF_8)) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) shots.add(line.split("\\s+"));
                    }
                } catch (IOException ex) {
                    ExplorationRemix.LOGGER.error("[shots] cannot read {}", LIST, ex);
                    phase = Phase.DONE;
                    return;
                }
                mc.options.hideGui = true;
                mc.options.fov().set(80);
                String[] setup = {"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
                        "gamerule doMobSpawning false", "weather clear"};
                if (remote) {
                    // /remix view generates chunks on the server thread, which can go quiet for longer than the
                    // client's 30 second read timeout. Give this test connection ten minutes instead.
                    io.netty.channel.Channel ch = mc.getConnection().getConnection().channel();
                    if (ch.pipeline().get("timeout") != null)
                        ch.pipeline().replace("timeout", "timeout", new io.netty.handler.timeout.ReadTimeoutHandler(600));
                    mc.getConnection().sendCommand("gamemode spectator");
                    for (String cmd : setup) mc.getConnection().sendCommand(cmd);
                } else {
                    server.execute(() -> {
                        ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
                        if (sp != null) sp.setGameMode(GameType.SPECTATOR);
                        for (String cmd : setup) run(server, null, cmd);
                    });
                }
                phase = Phase.NEXT;
                ticks = 0;
            }
            case NEXT -> {
                if (ticks < 40) return;
                index++;
                if (index >= shots.size()) {
                    finish(mc);
                    return;
                }
                String[] s = shots.get(index);
                String cmd = s[1].equals("@") && s.length >= 7 ? "tp @s " + String.join(" ", java.util.Arrays.copyOfRange(s, 2, 7))
                        : "remix view " + s[1] + (s.length > 2 ? " " + s[2] : "");
                ExplorationRemix.LOGGER.info("[shots] {} of {}: {}", index + 1, shots.size(), cmd);
                viewDone = false;
                startX = mc.player.getX();
                startY = mc.player.getY();
                startZ = mc.player.getZ();
                if (remote) {
                    // On a server the command runs there; the view is ready when the server moves the player.
                    mc.getConnection().sendCommand(cmd);
                } else {
                    server.execute(() -> {
                        ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
                        run(server, sp, cmd);
                        viewDone = true;
                    });
                }
                phase = Phase.VIEWING;
                ticks = 0;
            }
            case VIEWING -> {
                if (remote) {
                    double moved = Math.abs(mc.player.getX() - startX) + Math.abs(mc.player.getY() - startY) + Math.abs(mc.player.getZ() - startZ);
                    if (moved < 1 && ticks < 6000) return;
                } else if (!viewDone) return;
                phase = Phase.SETTLING;
                ticks = 0;
                stable = 0;
                lastChunks = -1;
            }
            case SETTLING -> {
                // Let the chunks arrive and every section around the camera get built before the picture. On a busy
                // server the chunk count can stand still for seconds while chunks are still being generated, so every
                // chunk within six of the camera must be there too.
                int chunks = mc.level.getChunkSource().getLoadedChunksCount();
                int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
                boolean all = true;
                for (int dx = -6; dx <= 6 && all; dx++)
                    for (int dz = -6; dz <= 6 && all; dz++)
                        if (dx * dx + dz * dz <= 36 && mc.level.getChunkSource().getChunk(pcx + dx, pcz + dz,
                                net.minecraft.world.level.chunk.ChunkStatus.FULL, false) == null) all = false;
                boolean quiet = all && mc.levelRenderer.hasRenderedAllChunks() && chunks == lastChunks;
                lastChunks = chunks;
                stable = quiet ? stable + 1 : 0;
                if ((ticks > SETTLE && stable > 80) || ticks > Math.max(6000, SETTLE + 600)) {
                    net.minecraft.core.BlockPos eye = net.minecraft.core.BlockPos.containing(mc.player.getEyePosition());
                    ExplorationRemix.LOGGER.info("[shots] settled after {} ticks: chunks around {}, all sections built {}, light at the eye sky {} block {}",
                            ticks, all, mc.levelRenderer.hasRenderedAllChunks(), mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye),
                            mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eye));
                    phase = Phase.SHOOT;
                    ticks = 0;
                }
            }
            case SHOOT -> {
                String name = shots.get(index)[0] + ".png";
                Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {});
                String where = String.format(java.util.Locale.ROOT, "%s %.1f %.1f %.1f yaw %.0f pitch %.0f", name, mc.player.getX(), mc.player.getY(),
                        mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot());
                log.add(where);
                ExplorationRemix.LOGGER.info("[shots] took {}", where);
                phase = Phase.NEXT;
                ticks = 0;
            }
            default -> {}
        }
    }

    private static void run(IntegratedServer server, ServerPlayer player, String command) {
        CommandSourceStack src = server.createCommandSourceStack();
        if (player != null) src = src.withEntity(player).withPosition(player.position()).withLevel(player.serverLevel());
        server.getCommands().performPrefixedCommand(src, command);
    }

    private static void finish(Minecraft mc) {
        phase = Phase.DONE;
        try {
            Files.write(mc.gameDirectory.toPath().resolve("screenshots").resolve("shots_done.txt"), log, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            ExplorationRemix.LOGGER.error("[shots] cannot write the shot log", ex);
        }
        ExplorationRemix.LOGGER.info("[shots] all done, quitting");
        mc.stop();
    }
}
