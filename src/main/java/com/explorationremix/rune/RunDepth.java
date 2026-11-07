package com.explorationremix.rune;

import com.explorationremix.rune.net.RuneNet;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Shape;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

/**
 * How deep the current run has gone, which sets how many slots each piece of gear has awake: 0 home (the surface and
 * the Upper Caves), 1 the Blue Reaches, 2 the Deep Gardens and below. The deepest layer reached counts until the run
 * ends: the player dies (a new player entity starts at 0) or climbs back above the first band. Kept in the player's
 * saved data on the server; the client is told whenever it changes.
 */
public final class RunDepth {
    public static final String KEY = "explorationremix:run_depth";
    /** Set when an operator chose the depth by command: it then stays put, wherever the player goes, until unpinned. */
    public static final String PIN = "explorationremix:run_depth_pinned";
    public static final int MAX = 2;
    /** The local player's run depth, as the server last said. */
    private static volatile int clientDepth;

    private RunDepth() {}

    public static int get(Player p) {
        if (p.level().isClientSide) return clientDepth;
        return Math.max(0, Math.min(MAX, p.getPersistentData().getInt(KEY)));
    }

    public static void set(ServerPlayer p, int depth) {
        int d = Math.max(0, Math.min(MAX, depth));
        if (p.getPersistentData().getInt(KEY) != d || !p.getPersistentData().contains(KEY)) {
            p.getPersistentData().putInt(KEY, d);
        }
        sync(p);
    }

    /** An operator's choice (/remix rune depth): kept wherever the player goes, until {@link #unpin} or death. */
    public static void pin(ServerPlayer p, int depth) {
        p.getPersistentData().putBoolean(PIN, true);
        set(p, depth);
    }

    /** Back to following the run, which starts again from where the player stands. */
    public static void unpin(ServerPlayer p) {
        p.getPersistentData().remove(PIN);
        set(p, Math.max(0, depthAt(p.level(), p.getY())));
    }

    public static boolean pinned(Player p) { return p.getPersistentData().getBoolean(PIN); }

    public static void sync(ServerPlayer p) {
        RuneNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new RuneNet.DepthSync(get(p)));
    }

    /** Called on the client when the server says. */
    public static void acceptClient(int depth) { clientDepth = Math.max(0, Math.min(MAX, depth)); }

    public static int clientDepth() { return clientDepth; }

    /** The depth of the place a player stands: -1 outside the deep world's dimension (the depth is kept there). */
    public static int depthAt(Level level, double y) {
        if (level.dimension() != Level.OVERWORLD) return -1;
        Shape s = DeepWorld.shape();
        if (s == null || !DeepWorld.installed()) return 0;
        int iy = (int) Math.floor(y);
        if (iy >= s.packFloor) return 0;
        int layer = s.layerAt(iy);
        if (layer >= 1) return 2;
        int band = s.bandAt(iy);
        if (band >= 2) return 2;
        return 1;
    }

    /** Once a second: deeper than ever this run wakes slots; home again ends the run. */
    public static void update(ServerPlayer p) {
        if (pinned(p)) return;
        int here = depthAt(p.level(), p.getY());
        if (here < 0) return;
        int now = get(p);
        if (here == 0 && now != 0) set(p, 0);
        else if (here > now) set(p, here);
    }
}
