package com.explorationremix.client.model;

import com.explorationremix.ExplorationRemix;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A creature's shape from assets/explorationremix/geometry/<name>.json, written by tools/creatures.py: a tree of named
 * parts, each a pivot, a rest rotation (degrees) and boxes with their place on the texture. The same file tells the
 * texture painter which pixel lands on which face, so shape and texture cannot drift apart. It is read from the mod's
 * own jar (not a resource pack), so it is there when layer definitions are registered.
 */
public final class Geometry {
    private Geometry() {}

    public static LayerDefinition load(String name) {
        String path = "/assets/" + ExplorationRemix.MOD_ID + "/geometry/" + name + ".json";
        JsonObject json;
        try (InputStream in = Geometry.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
        MeshDefinition mesh = new MeshDefinition();
        Map<String, PartDefinition> parts = new HashMap<>();
        parts.put("root", mesh.getRoot());
        for (JsonElement el : json.getAsJsonArray("parts")) {
            JsonObject p = el.getAsJsonObject();
            PartDefinition parent = parts.get(p.get("parent").getAsString());
            if (parent == null) throw new IllegalStateException(name + ": part " + p.get("name") + " comes before its parent");
            CubeListBuilder cubes = CubeListBuilder.create();
            for (JsonElement be : p.getAsJsonArray("boxes")) {
                JsonObject b = be.getAsJsonObject();
                float[] o = floats(b.getAsJsonArray("origin"));
                float[] s = floats(b.getAsJsonArray("size"));
                float[] uv = floats(b.getAsJsonArray("uv"));
                float inflate = b.get("inflate").getAsFloat();
                cubes.texOffs((int) uv[0], (int) uv[1]).addBox(o[0], o[1], o[2], s[0], s[1], s[2], new CubeDeformation(inflate));
            }
            float[] pv = floats(p.getAsJsonArray("pivot"));
            float[] r = floats(p.getAsJsonArray("rotation"));
            float k = (float) (Math.PI / 180.0);
            PartDefinition part = parent.addOrReplaceChild(p.get("name").getAsString(), cubes,
                    PartPose.offsetAndRotation(pv[0], pv[1], pv[2], r[0] * k, r[1] * k, r[2] * k));
            parts.put(p.get("name").getAsString(), part);
        }
        return LayerDefinition.create(mesh, json.get("texture_width").getAsInt(), json.get("texture_height").getAsInt());
    }

    private static float[] floats(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }
}
