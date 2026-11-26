package com.explorationremix.rune.stone;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rune stones' shipped files (0.23.1, tools/rune_stones.py) against the blocks the code makes: every kind's every
 * host has its blockstate's variants, each variant's model, each model's own textures, and for each glowing carving the
 * light drawn while it is dug ({@code _lit}, read by client.rune.stone.RuneStoneClient); every kind has its loot table
 * (one rune, a second now and then) and its name, and is mined with a pickaxe.
 */
class RuneStoneDataTest {
    static final Path RES = Path.of("src/main/resources");
    static final Path ASSETS = RES.resolve("assets/explorationremix"), DATA = RES.resolve("data/explorationremix");

    static JsonObject json(Path p) throws IOException {
        try (Reader r = Files.newBufferedReader(p)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    static Path texture(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
        return RES.resolve("assets/" + ns + "/textures/" + path + ".png");
    }

    @Test
    void everyStoneHasItsLookLootAndName() throws IOException {
        List<String> fails = new ArrayList<>();
        JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
        String pickaxe = Files.readString(RES.resolve("data/minecraft/tags/blocks/mineable/pickaxe.json"));
        for (RuneStoneKind k : RuneStoneKind.values()) {
            Path bs = ASSETS.resolve("blockstates/" + k.id + ".json");
            if (!Files.exists(bs)) {
                fails.add(k.id + ": no blockstate");
                continue;
            }
            JsonObject variants = json(bs).getAsJsonObject("variants");
            if (variants.size() != k.hosts.size()) fails.add(k.id + ": " + variants.size() + " variants for " + k.hosts.size() + " hosts");
            for (RuneStoneHost h : k.hosts) {
                JsonElement v = variants.get("host=" + h.getSerializedName());
                if (v == null) {
                    fails.add(k.id + ": no variant for " + h.getSerializedName());
                    continue;
                }
                List<JsonElement> models = new ArrayList<>();
                if (v.isJsonArray()) v.getAsJsonArray().forEach(models::add);
                else models.add(v);
                for (JsonElement m : models) {
                    String model = m.getAsJsonObject().get("model").getAsString();
                    Path mp = ASSETS.resolve("models/" + model.substring(model.indexOf(':') + 1) + ".json");
                    if (!Files.exists(mp)) {
                        fails.add(k.id + ": no model " + model);
                        continue;
                    }
                    JsonObject mj = json(mp);
                    if (mj.has("parent")) {
                        String parent = mj.get("parent").getAsString();
                        if (parent.startsWith("explorationremix:") && !Files.exists(ASSETS.resolve("models/" + parent.substring(parent.indexOf(':') + 1) + ".json")))
                            fails.add(model + ": no parent " + parent);
                    }
                    JsonObject tex = mj.getAsJsonObject("textures");
                    if (tex == null) {
                        fails.add(model + ": no textures");
                        continue;
                    }
                    int glows = 0;
                    for (Map.Entry<String, JsonElement> e : tex.entrySet()) {
                        String t = e.getValue().getAsString();
                        if (t.startsWith("#")) continue;
                        // the host's own faces are the game's (minecraft:block/...); the carving and its light are the mod's
                        if (t.startsWith("explorationremix:") && !Files.exists(texture(t))) fails.add(model + ": no texture " + t);
                        if (t.endsWith("_glow")) {
                            glows++;
                            String lit = t.substring(0, t.length() - "_glow".length()) + "_lit";
                            if (!Files.exists(texture(lit))) fails.add(model + ": no dug light " + lit);
                        }
                    }
                    if (glows == 0) fails.add(model + ": no glowing carving");
                }
            }
            Path loot = DATA.resolve("loot_tables/blocks/" + k.id + ".json");
            if (!Files.exists(loot)) fails.add(k.id + ": no loot table");
            if (!lang.has("block.explorationremix." + k.id)) fails.add(k.id + ": no name");
            if (!pickaxe.contains("explorationremix:" + k.id)) fails.add(k.id + ": not mined with a pickaxe");
        }
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails));
    }
}
