package com.explorationremix.rune;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Every generated rune file must load, and the rune block's roll must respect tiers and exclusions. */
class RuneDataTest {
    private static List<Rune> loadAll() throws IOException {
        Path dir = Paths.get("src/main/resources/data/explorationremix/runes");
        assertTrue(Files.isDirectory(dir), "run tools/gen_data.py first");
        List<Rune> runes = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String id = f.getFileName().toString().replace(".json", "");
                JsonObject json = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                runes.add(Rune.fromJson(new ResourceLocation("explorationremix", id), json));
            }
        }
        return runes;
    }

    @Test
    void everyRuneFileLoadsAndTheStarterSetExists() throws IOException {
        List<Rune> runes = loadAll();
        assertTrue(runes.size() >= 30, "expected the full rune set, got " + runes.size());
        Set<String> ids = new java.util.HashSet<>();
        for (Rune r : runes) ids.add(r.id().getPath());
        for (String needed : List.of("on_hit", "spark", "on_jump", "leap", "blast", "twice", "keen_edge")) assertTrue(ids.contains(needed), needed);
        for (Rune r : runes) {
            assertTrue(r.cost() >= 0, r.id() + " cost");
            assertTrue(r.tier() >= 0 && r.tier() <= 3, r.id() + " tier");
            if (r.kind() == RuneKind.PASSIVE) assertEquals(0, r.cost(), r.id() + " passives cost nothing");
        }
    }

    @Test
    void rollNeverHandsOutARuneAboveTheTierOrOneThatIsExcluded() throws IOException {
        List<Rune> runes = loadAll();
        RuneRegistry.replaceAll(runes);
        RandomSource random = RandomSource.create(20261028L);
        ResourceLocation excluded = new ResourceLocation("explorationremix", "spark");
        assertNull(RuneRegistry.roll(random, 0, "", Set.of()), "tiers start at 1, so tier 0 offers nothing");
        for (int tier = 1; tier <= 3; tier++) {
            for (int i = 0; i < 500; i++) {
                Rune r = RuneRegistry.roll(random, tier, "", Set.of(excluded));
                assertNotNull(r);
                assertTrue(r.tier() <= tier, r.id() + " above tier " + tier);
                assertNotEquals(excluded, r.id());
            }
        }
        // attunement makes its family three times as likely
        int movement = 0, total = 4000;
        for (int i = 0; i < total; i++) if (RuneRegistry.roll(random, 3, "movement", Set.of()).attunements().contains("movement")) movement++;
        int plain = 0;
        for (int i = 0; i < total; i++) if (RuneRegistry.roll(random, 3, "", Set.of()).attunements().contains("movement")) plain++;
        assertTrue(movement > plain * 1.5, "attuned " + movement + " vs plain " + plain);
    }
}
