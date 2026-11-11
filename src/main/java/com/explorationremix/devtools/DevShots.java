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
 * /remix view finds, then quits. Each line of the list is "file-name biome [n]", "file-name biome [n] near x z" (the
 * view searched for round x z), "file-name @ x y z yaw pitch" for a picture from a chosen spot, or "file-name here
 * [ticks]" for a picture from where the camera is now, with no teleport: it waits that many client ticks (20 if not
 * given) for the server's messages and the particles they bring, then for the usual quiet and still view, but without
 * the least wait of ER_SHOTS_SETTLE (0.9.0, spec 6.7: a held quake or eruption, pictured from the spot the last view
 * left the camera at), or "file-name now [ticks]" for a picture from where the camera is after that many client ticks
 * (0 if not given) after the line before it, with no settling at all, once the renderer has no section left to build
 * (at most 40 ticks more): a moment that does not hold still, such as a block crashing down or a bomb's blast a few
 * ticks after it (the driver's breath of 40 ticks between lines is left out before it). Three more kinds of line take no picture: "! command" runs a command (for example "! remix lava
 * level 40", to set the Basalt Galleries' flood before the next pictures) and waits for its reply, "! wait ticks" idles
 * that many client ticks (to let the server finish work the last command started), and "! waitgame ticks" waits until
 * the level's game time has gone that many server ticks past the line's start (the rig's client runs slower than the
 * server, so a moment of an eruption is reached by the server's clock, not the client's). Steps for testing gear press, hold and release keys,
 * turn, pick a hotbar slot, show the HUD and snap a frame at once (see clientStep). Screenshots go to the game's
 * screenshots folder.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class DevShots {
    private static final String LIST = System.getenv("ER_SHOTS");
    /** The least ticks to wait at each view (ER_SHOTS_SETTLE), for slow renderers. */
    private static final int SETTLE = Integer.getInteger("er.shots.settle", envInt("ER_SHOTS_SETTLE", 300));
    /** How many chunks around the camera must be loaded before the picture (ER_SHOTS_RADIUS): 6, or more to be sure the
     * whole view is there, up to the render distance. */
    private static final int RADIUS = envInt("ER_SHOTS_RADIUS", 6);
    /** How many frames in a row the count of drawn sections must stand still before the picture (ER_SHOTS_FRAMES). */
    private static final int STILL_FRAMES = Math.max(1, envInt("ER_SHOTS_FRAMES", 20));
    /** After this many ticks at a view the picture is taken anyway, and the log and the shot list say it never settled. */
    private static final int GIVE_UP = Math.max(12000, SETTLE + 600);

    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A "here" line's wait for messages and particles when it gives none, in client ticks. */
    private static final int HERE_WAIT = 20;

    private enum Phase { WAIT_WORLD, SETUP, NEXT, VIEWING, HERE, NOW, SETTLING, SHOOT, COMMAND, WAITING, WAIT_GAME, DONE }

    private static Phase phase = Phase.WAIT_WORLD;
    private static List<String[]> shots;
    private static int index = -1, ticks, quiet, still, lastChunks, lastSections, frameCount, lastFrame, settleFrame, waitTicks;
    private static boolean gaveUp;
    /** The picture under way is a "here" one: it settles without the {@link #SETTLE} floor. */
    private static boolean here;
    /** The game time a "! waitgame" line waits for. */
    private static long gameUntil;
    private static volatile boolean viewDone;
    /** System chat lines received while the shots run (the view commands' replies), and the last of them. */
    private static volatile int replies;
    private static volatile String lastReply = "";
    private static int repliesBefore;
    private static double startX, startY, startZ;
    private static final List<String> log = new ArrayList<>();

    private DevShots() {}

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent e) {
        if (LIST != null && e.phase == TickEvent.Phase.END) frameCount++;
    }

    @SubscribeEvent
    public static void chat(net.minecraftforge.client.event.ClientChatReceivedEvent e) {
        if (LIST == null || !e.isSystem()) return;
        lastReply = e.getMessage().getString();
        replies++;
    }

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
                // the Basalt Galleries' lava is held at its table too: rising lava and the warnings' shake never let a
                // view settle (and with the lava clock held no quake or eruption starts on its own). The camera player
                // is a spectator, so spectators are made actors: without that no debris falls and no Caldera erupts
                // round it when a command starts one
                String[] setup = {"time set noon", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
                        "gamerule doMobSpawning false", "weather clear", "remix lava level 0",
                        "remix quake spectators act"};
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
                // a breath between lines, but none before a "now" line: its ticks count from the line before it
                boolean now = index + 1 < shots.size() && shots.get(index + 1).length >= 2 && shots.get(index + 1)[1].equals("now");
                if (ticks < 40 && !now) return;
                index++;
                if (index >= shots.size()) {
                    finish(mc);
                    return;
                }
                String[] s = shots.get(index);
                if (s[0].equals("!")) {
                    // a step without a picture: a command, a pause, or something the player does
                    if (s.length >= 2 && clientStep(mc, s)) {
                        // the next line follows at once (the game reads the keys in its next tick)
                        ticks = 39;
                        return;
                    }
                    if (s.length == 3 && s[1].equals("wait")) {
                        waitTicks = count(s[2], 0);
                        ExplorationRemix.LOGGER.info("[shots] {} of {}: waiting {} ticks", index + 1, shots.size(), waitTicks);
                        phase = Phase.WAITING;
                    } else if (s.length == 3 && s[1].equals("waitgame")) {
                        int n = count(s[2], 0);
                        gameUntil = mc.level.getGameTime() + n;
                        ExplorationRemix.LOGGER.info("[shots] {} of {}: waiting {} game ticks, until game time {}", index + 1, shots.size(), n,
                                gameUntil);
                        phase = Phase.WAIT_GAME;
                    } else if (s.length > 1) {
                        String command = String.join(" ", java.util.Arrays.copyOfRange(s, 1, s.length));
                        ExplorationRemix.LOGGER.info("[shots] {} of {}: command {}", index + 1, shots.size(), command);
                        repliesBefore = replies;
                        if (remote) mc.getConnection().sendCommand(command);
                        else server.execute(() -> run(server, server.getPlayerList().getPlayer(mc.player.getUUID()), command));
                        phase = Phase.COMMAND;
                    }
                    ticks = 0;
                    return;
                }
                if (s.length >= 2 && s[1].equals("now")) {
                    // a picture of a passing moment from where the camera is: no settling, only the sections built
                    waitTicks = s.length > 2 ? count(s[2], 0) : 0;
                    ExplorationRemix.LOGGER.info("[shots] {} of {}: now, after {} ticks", index + 1, shots.size(), waitTicks);
                    phase = Phase.NOW;
                    ticks = 0;
                    return;
                }
                if (s.length >= 2 && s[1].equals("here")) {
                    // a picture from where the camera is: no command, only a wait for the server's messages and their
                    // particles before the usual settling
                    waitTicks = s.length > 2 ? count(s[2], HERE_WAIT) : HERE_WAIT;
                    ExplorationRemix.LOGGER.info("[shots] {} of {}: here, after {} ticks", index + 1, shots.size(), waitTicks);
                    here = true;
                    phase = Phase.HERE;
                    ticks = 0;
                    return;
                }
                here = false;
                int near = java.util.Arrays.asList(s).indexOf("near");
                String cmd = s[1].equals("@") && s.length >= 7 ? "tp @s " + String.join(" ", java.util.Arrays.copyOfRange(s, 2, 7))
                        : near > 0 && near + 2 < s.length
                        ? "execute positioned " + s[near + 1] + " 64 " + s[near + 2] + " run remix view " + s[1] + (near > 2 ? " " + s[2] : "")
                        : "remix view " + s[1] + (s.length > 2 ? " " + s[2] : "");
                ExplorationRemix.LOGGER.info("[shots] {} of {}: {}", index + 1, shots.size(), cmd);
                viewDone = false;
                repliesBefore = replies;
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
                    // The server answers every view command (after moving the player, if it found a view); far views
                    // can take minutes while it generates the land round them.
                    if (replies == repliesBefore && ticks < 6000) return;
                    String reply = lastReply;
                    if (reply.startsWith("No ") || reply.startsWith("Unknown") || reply.startsWith("There ")) {
                        ExplorationRemix.LOGGER.info("[shots] skipped: {}", reply);
                        log.add(shots.get(index)[0] + ".png skipped: " + reply);
                        phase = Phase.NEXT;
                        ticks = 0;
                        return;
                    }
                } else if (!viewDone) return;
                settle();
            }
            case HERE -> {
                if (ticks < waitTicks) return;
                settle();
            }
            case NOW -> {
                if (ticks < waitTicks || !mc.levelRenderer.hasRenderedAllChunks() && ticks < waitTicks + 40) return;
                gaveUp = false;
                ExplorationRemix.LOGGER.info("[shots] now after {} ticks, build queue empty {}", ticks, mc.levelRenderer.hasRenderedAllChunks());
                phase = Phase.SHOOT;
                ticks = 0;
            }
            case SETTLING -> {
                // Let the chunks arrive, get lit and every section in view get built before the picture. On a busy server
                // the chunk count can stand still for seconds while chunks are still being generated, so every chunk
                // within RADIUS of the camera must be there too, for more than 80 ticks. And an empty build queue says
                // little: the renderer builds the sections in view a few at a time and finds the ones behind a section
                // only once that one is built, so the count of drawn sections must also stand still for STILL_FRAMES
                // frames. On a software renderer that takes minutes, and a shader shows its sky through every section
                // not drawn yet.
                int chunks = mc.level.getChunkSource().getLoadedChunksCount();
                int view = mc.options.getEffectiveRenderDistance();
                boolean all = missing(mc, Math.min(RADIUS, view)) == 0;
                boolean calm = all && mc.level.isLightUpdateQueueEmpty() && mc.levelRenderer.hasRenderedAllChunks() && chunks == lastChunks;
                lastChunks = chunks;
                quiet = calm ? quiet + 1 : 0;
                if (frameCount != lastFrame) {
                    lastFrame = frameCount;
                    int sections = mc.levelRenderer.countRenderedChunks();
                    still = calm && sections == lastSections ? still + 1 : 0;
                    lastSections = sections;
                }
                boolean settled = (here || ticks > SETTLE) && quiet > 80 && still >= STILL_FRAMES;
                if (settled || ticks > GIVE_UP) {
                    gaveUp = !settled;
                    net.minecraft.core.BlockPos eye = net.minecraft.core.BlockPos.containing(mc.player.getEyePosition());
                    ExplorationRemix.LOGGER.info("[shots] {} after {} ticks and {} frames, {} still: chunks within {} {}, missing within the "
                                    + "render distance {}: {}, sections drawn {}, build queue empty {}, light at the eye sky {} block {}",
                            settled ? "settled" : "gave up waiting", ticks, frameCount - settleFrame, still, Math.min(RADIUS, view), all,
                            view, missing(mc, view), lastSections, mc.levelRenderer.hasRenderedAllChunks(),
                            mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye),
                            mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eye));
                    phase = Phase.SHOOT;
                    ticks = 0;
                }
            }
            case SHOOT -> {
                String name = shots.get(index)[0] + ".png";
                Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {});
                String where = String.format(java.util.Locale.ROOT, "%s %.1f %.1f %.1f yaw %.0f pitch %.0f%s", name, mc.player.getX(), mc.player.getY(),
                        mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(), gaveUp ? " (never settled)" : "");
                log.add(where);
                // the particles alive and the frame rate over the last wait, for the effects' cost
                ExplorationRemix.LOGGER.info("[shots] took {} (particles {}, fps {})", where, mc.particleEngine.countParticles(), mc.getFps());
                phase = Phase.NEXT;
                ticks = 0;
            }
            case COMMAND -> {
                // the command's reply (most commands answer at once; stop waiting after 200 ticks)
                if (replies == repliesBefore && ticks < 200) return;
                String reply = replies == repliesBefore ? "(no reply)" : lastReply;
                ExplorationRemix.LOGGER.info("[shots] command replied: {}", reply);
                log.add("! " + String.join(" ", java.util.Arrays.copyOfRange(shots.get(index), 1, shots.get(index).length)) + ": " + reply);
                phase = Phase.NEXT;
                ticks = 0;
            }
            case WAITING -> {
                if (ticks < waitTicks) return;
                phase = Phase.NEXT;
                // the next line follows at once: the wait was the pause
                ticks = 39;
            }
            case WAIT_GAME -> {
                // the game time comes from the server with every time update, a second apart; stop waiting after
                // GIVE_UP client ticks all the same
                if (mc.level.getGameTime() < gameUntil && ticks < GIVE_UP) return;
                if (mc.level.getGameTime() < gameUntil) {
                    ExplorationRemix.LOGGER.info("[shots] gave up waiting for game time {} (it is {})", gameUntil, mc.level.getGameTime());
                    log.add("! " + String.join(" ", java.util.Arrays.copyOfRange(shots.get(index), 1, shots.get(index).length))
                            + ": gave up at game time " + mc.level.getGameTime());
                }
                phase = Phase.NEXT;
                ticks = 0;
            }
            default -> {}
        }
    }

    /**
     * What the player does, for testing gear in the game: "! press use|attack|runes|inventory" (one click of that
     * key), "! hold use|attack" and "! release use|attack" (keep it held down until released), "! slot n" (hotbar slot
     * 0 to 8), "! look yaw pitch", "! gui on|off" (the hotbar and HUD), "! view first|third|front" (the camera),
     * "! swing light|strong combo tau" (the player's own timed swing held still tau ticks in; "! swing off" lets go),
     * "! close" (the open screen) and "! snap name"
     * (a picture of this very frame, without moving or settling, for things in flight and screens). False if the
     * line is none of these.
     */
    private static boolean clientStep(Minecraft mc, String[] s) {
        String what = s[1], arg = s.length > 2 ? s[2] : "";
        switch (what) {
            case "press", "hold", "release" -> {
                net.minecraft.client.KeyMapping key = switch (arg) {
                    case "use" -> mc.options.keyUse;
                    case "attack" -> mc.options.keyAttack;
                    case "inventory" -> mc.options.keyInventory;
                    case "runes" -> com.explorationremix.client.rune.RuneKeys.OPEN;
                    default -> null;
                };
                if (key == null) return false;
                if (!what.equals("release")) net.minecraft.client.KeyMapping.click(key.getKey());
                if (!what.equals("press")) key.setDown(what.equals("hold"));
            }
            case "slot" -> {
                try {
                    mc.player.getInventory().selected = Math.max(0, Math.min(8, Integer.parseInt(arg)));
                } catch (NumberFormatException ex) {
                    return false;
                }
            }
            case "look" -> {
                if (s.length < 4) return false;
                try {
                    float yaw = Float.parseFloat(s[2]), pitch = Float.parseFloat(s[3]);
                    mc.player.setYRot(yaw);
                    mc.player.setXRot(pitch);
                    mc.player.yRotO = yaw;
                    mc.player.xRotO = pitch;
                    mc.player.setYHeadRot(yaw);
                } catch (NumberFormatException ex) {
                    return false;
                }
            }
            case "gui" -> mc.options.hideGui = !arg.equals("on");
            case "swing" -> {
                // "! swing light|strong combo tau [chain]": the player's own swing held still that many ticks in (with "chain",
                // a light one starts where the chain's swings before it left the hand, pressed as fast as they go); "! swing off"
                if (arg.equals("off")) {
                    com.explorationremix.client.combat.SwingClient.devPose(com.explorationremix.combat.swing.SwingKind.LIGHT, 0, -1.0F, false);
                } else {
                    if (s.length < 5) return false;
                    try {
                        com.explorationremix.client.combat.SwingClient.devPose(arg.equals("strong") ? com.explorationremix.combat.swing.SwingKind.STRONG
                                : com.explorationremix.combat.swing.SwingKind.LIGHT, Integer.parseInt(s[3]), Float.parseFloat(s[4]),
                                s.length > 5 && s[5].equals("chain"));
                    } catch (NumberFormatException ex) {
                        return false;
                    }
                }
            }
            case "view" -> mc.options.setCameraType(switch (arg) {
                case "third" -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
                case "front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
                default -> net.minecraft.client.CameraType.FIRST_PERSON;
            });
            case "close" -> {
                if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) mc.player.closeContainer();
                else if (mc.screen != null) mc.setScreen(null);
            }
            case "snap" -> {
                if (arg.isEmpty()) return false;
                String name = arg + ".png";
                Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {});
                String where = String.format(java.util.Locale.ROOT, "%s %.1f %.1f %.1f yaw %.0f pitch %.0f (snap%s)", name, mc.player.getX(),
                        mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(),
                        mc.screen == null ? "" : ", screen " + mc.screen.getClass().getSimpleName());
                log.add(where);
                ExplorationRemix.LOGGER.info("[shots] snapped {}", where);
            }
            default -> {
                return false;
            }
        }
        ExplorationRemix.LOGGER.info("[shots] {} of {}: {}", index + 1, shots.size(), String.join(" ", java.util.Arrays.copyOfRange(s, 1, s.length)));
        if (!what.equals("snap")) log.add("! " + String.join(" ", java.util.Arrays.copyOfRange(s, 1, s.length)));
        return true;
    }

    /** Starts settling at the view the camera has now (after a view's teleport, or at once for a "here" line). */
    private static void settle() {
        phase = Phase.SETTLING;
        ticks = 0;
        quiet = 0;
        still = 0;
        lastChunks = -1;
        lastSections = -1;
        lastFrame = frameCount;
        settleFrame = frameCount;
    }

    /** A line's count of ticks, or {@code fallback} when it is not a number; never below 0. */
    private static int count(String word, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(word));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** How many chunks within radius (a circle) of the camera the client has not got yet. */
    private static int missing(Minecraft mc, int radius) {
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4, n = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                if (dx * dx + dz * dz <= radius * radius && mc.level.getChunkSource().getChunk(pcx + dx, pcz + dz,
                        net.minecraft.world.level.chunk.ChunkStatus.FULL, false) == null) n++;
        return n;
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
