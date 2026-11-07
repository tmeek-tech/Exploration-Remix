package com.explorationremix.world.lava;

import com.explorationremix.config.ERConfig;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * The loaded chunks round the players in or near the Basalt Galleries, walked the way the lava flood walks them: rings
 * outward from each player, the players taking turns ring by ring, each chunk once, only chunks already loaded (never
 * loading one). Shared by the terrain reset and the quakes (0.5.1).
 */
public final class NearChunks {
    private NearChunks() {}

    /** The players in or near the Galleries: in the overworld, under the band's near height. */
    public static List<ServerPlayer> players(ServerLevel level, LavaFlood.Band band) {
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (p.getY() < band.nearY() && !p.isRemoved()) near.add(p);
        return near;
    }

    /** How far round each such player the flood keeps the lava up to date, in chunks: the config's, or the view distance and one. */
    public static int radius(ServerLevel level) {
        int r = ERConfig.INSTANCE.lavaRadius.get();
        return r > 0 ? r : level.getServer().getPlayerList().getViewDistance() + 1;
    }

    /** Called for each chunk of a walk. */
    @FunctionalInterface
    public interface Visitor {
        /** Returns false to end the walk. */
        boolean visit(LevelChunk chunk);
    }

    /**
     * Visits each loaded chunk within {@code radius} chunks of the players once, nearest rings first; the player who
     * goes first changes with {@code now}, so no player's chunks always wait for another's. Ends early when the visitor
     * returns false.
     */
    public static void walk(ServerLevel level, List<ServerPlayer> near, int radius, long now, Visitor v) {
        int n = near.size();
        if (n == 0) return;
        ServerChunkCache source = level.getChunkSource();
        LongOpenHashSet seen = n > 1 ? new LongOpenHashSet() : null;
        int first = (int) Math.floorMod(now, (long) n);
        int[] off = new int[2], cx0 = new int[n], cz0 = new int[n];
        for (int k = 0; k < n; k++) {
            cx0[k] = SectionPos.blockToSectionCoord(near.get(k).getBlockX());
            cz0[k] = SectionPos.blockToSectionCoord(near.get(k).getBlockZ());
        }
        for (int r = 0; r <= radius; r++) {
            int size = Flood.ringSize(r);
            for (int k = 0; k < n; k++) {
                int pk = (first + k) % n;
                for (int j = 0; j < size; j++) {
                    Flood.ring(r, j, off);
                    int cx = cx0[pk] + off[0], cz = cz0[pk] + off[1];
                    if (seen != null && !seen.add(ChunkPos.asLong(cx, cz))) continue;
                    LevelChunk chunk = source.getChunkNow(cx, cz);
                    if (chunk != null && !v.visit(chunk)) return;
                }
            }
        }
    }
}
