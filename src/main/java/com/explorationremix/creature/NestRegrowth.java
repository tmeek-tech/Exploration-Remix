package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which gate nests are broken, and by which run. A broken nest stays open for the run that broke it and regrows when
 * that run ends, so nothing in a gate is permanent. The nest geometry itself is rebuilt by {@link NestBuilder}.
 */
public final class NestRegrowth extends SavedData {
    private static final String NAME = "explorationremix_nests";

    /** gate key -> state */
    private final Map<String, Entry> nests = new HashMap<>();

    public static final class Entry {
        public long brokenByRun;
        public int clustersBroken;
        public int clustersTotal;
        public BlockPos center = BlockPos.ZERO;
        /** Clusters still standing. */
        public final List<BlockPos> clusters = new ArrayList<>();
        /** Every cluster position the nest was laid out with; regrowth restores this. */
        public final List<BlockPos> layout = new ArrayList<>();

        public boolean open() { return brokenByRun != 0 && clustersTotal > 0 && clustersBroken >= clustersTotal; }
    }

    public static NestRegrowth get(MinecraftServer server) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        return level.getDataStorage().computeIfAbsent(NestRegrowth::load, NestRegrowth::new, NAME);
    }

    public static String key(int band, int cellX, int cellZ) { return band + ":" + cellX + ":" + cellZ; }

    public Entry entry(String key) { return nests.computeIfAbsent(key, k -> new Entry()); }

    public Entry entryOrNull(String key) { return nests.get(key); }

    /** Records that a run broke one egg cluster of a nest. Returns true when this broke the last one and the gate is open. */
    public boolean breakCluster(String key, long runId, BlockPos cluster) {
        Entry e = entry(key);
        if (e.brokenByRun != runId) { e.brokenByRun = runId; e.clustersBroken = 0; }
        if (e.clusters.remove(cluster)) e.clustersBroken++;
        setDirty();
        return e.open();
    }

    public void register(String key, BlockPos center, List<BlockPos> clusters) {
        Entry e = entry(key);
        e.center = center;
        e.clusters.clear();
        e.clusters.addAll(clusters);
        e.layout.clear();
        e.layout.addAll(clusters);
        e.clustersTotal = clusters.size();
        e.clustersBroken = 0;
        e.brokenByRun = 0;
        setDirty();
    }

    public boolean isOpen(String key, long runId) {
        Entry e = nests.get(key);
        return e != null && e.brokenByRun == runId && e.open();
    }

    /** A run ended: every nest it broke regrows. */
    public static void onRunEnded(MinecraftServer server, long runId) {
        if (server == null || runId == 0) return;
        NestRegrowth data = get(server);
        List<String> regrow = new ArrayList<>();
        data.nests.forEach((key, e) -> { if (e.brokenByRun == runId) regrow.add(key); });
        for (String key : regrow) {
            Entry e = data.nests.get(key);
            e.brokenByRun = 0;
            e.clustersBroken = 0;
            e.clusters.clear();
            e.clusters.addAll(e.layout);
            NestBuilder.regrow(server.getLevel(Level.OVERWORLD), key, e);
        }
        if (!regrow.isEmpty()) {
            data.setDirty();
            ExplorationRemix.LOGGER.info("Run {} ended: {} nests regrow", runId, regrow.size());
        }
    }

    public static NestRegrowth load(CompoundTag tag) {
        NestRegrowth d = new NestRegrowth();
        ListTag list = tag.getList("Nests", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Entry e = new Entry();
            e.brokenByRun = t.getLong("Run");
            e.clustersBroken = t.getInt("Broken");
            e.clustersTotal = t.getInt("Total");
            e.center = BlockPos.of(t.getLong("Center"));
            long[] cs = t.getLongArray("Clusters");
            for (long c : cs) e.clusters.add(BlockPos.of(c));
            long[] ls = t.getLongArray("Layout");
            for (long c : ls) e.layout.add(BlockPos.of(c));
            if (e.layout.isEmpty()) e.layout.addAll(e.clusters);
            d.nests.put(t.getString("Key"), e);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        nests.forEach((key, e) -> {
            CompoundTag t = new CompoundTag();
            t.putString("Key", key);
            t.putLong("Run", e.brokenByRun);
            t.putInt("Broken", e.clustersBroken);
            t.putInt("Total", e.clustersTotal);
            t.putLong("Center", e.center.asLong());
            long[] cs = new long[e.clusters.size()];
            for (int i = 0; i < cs.length; i++) cs[i] = e.clusters.get(i).asLong();
            t.putLongArray("Clusters", cs);
            long[] ls = new long[e.layout.size()];
            for (int i = 0; i < ls.length; i++) ls[i] = e.layout.get(i).asLong();
            t.putLongArray("Layout", ls);
            list.add(t);
        });
        tag.put("Nests", list);
        return tag;
    }
}
