package com.explorationremix.rune;

import com.explorationremix.ExplorationRemix;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads every rune JSON from datapacks. Runes are also synced to clients for tooltips and the editor. */
public final class RuneRegistry extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Map<ResourceLocation, Rune> RUNES = new ConcurrentHashMap<>();

    public RuneRegistry() { super(GSON, "runes"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, Rune> loaded = new ConcurrentHashMap<>();
        files.forEach((id, json) -> {
            try {
                loaded.put(id, Rune.fromJson(id, json.getAsJsonObject()));
            } catch (RuntimeException e) {
                ExplorationRemix.LOGGER.error("Bad rune {}: {}", id, e.getMessage());
            }
        });
        replaceAll(loaded.values());
        ExplorationRemix.LOGGER.info("Loaded {} runes", RUNES.size());
    }

    public static void replaceAll(Collection<Rune> runes) {
        RUNES.clear();
        for (Rune r : runes) RUNES.put(r.id(), r);
    }

    @Nullable
    public static Rune get(@Nullable ResourceLocation id) { return id == null ? null : RUNES.get(id); }

    @Nullable
    public static Rune get(String id) { return get(ResourceLocation.tryParse(id)); }

    public static Collection<Rune> all() { return RUNES.values(); }

    public static boolean isEmpty() { return RUNES.isEmpty(); }

    /** Weighted random pick for a rune block of a given tier and a tool's attunement. */
    @Nullable
    public static Rune roll(RandomSource random, int tier, String attunement, Collection<ResourceLocation> exclude) {
        List<Rune> pool = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (Rune r : RUNES.values()) {
            if (r.tier() > tier || exclude.contains(r.id())) continue;
            int w = r.weight();
            if (!attunement.isEmpty() && r.attunements().contains(attunement)) w *= 3;
            if (r.tier() == tier) w *= 2;
            if (w <= 0) continue;
            pool.add(r); weights.add(w); total += w;
        }
        if (pool.isEmpty()) return null;
        int pick = random.nextInt(total);
        for (int i = 0; i < pool.size(); i++) {
            pick -= weights.get(i);
            if (pick < 0) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }
}
