package com.explorationremix.client.model;

import com.explorationremix.ExplorationRemix;
import com.google.gson.Gson;
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
import java.util.List;
import java.util.Map;

/**
 * A creature's shape as the data generator wrote it: a tree of boxes with the UVs that the texture was painted for.
 * Loaded straight from the mod jar so model layers and textures always agree.
 */
public final class CreatureShape {
    private static final Gson GSON = new Gson();
    private static final Map<String, CreatureShape> CACHE = new HashMap<>();

    public int texture_width = 64, texture_height = 64;
    public float scale = 1.0F;
    public float shadow = 0.5F;
    public boolean hang;
    public List<Part> parts = List.of();

    public static final class Part {
        public String name;
        public String parent;
        public float[] pivot = {0, 0, 0};
        public List<Box> boxes = List.of();
        public String role = "static";
        public float phase;
        public int side;
        public String axis = "x";
        public float amp = 1.0F;
    }

    public static final class Box {
        public float[] from = {0, 0, 0};
        public float[] size = {1, 1, 1};
        public int[] uv = {0, 0};
        public float inflate;
    }

    public static CreatureShape load(String id) {
        return CACHE.computeIfAbsent(id, k -> {
            String path = "/assets/" + ExplorationRemix.MOD_ID + "/creatures/" + k + ".json";
            try (InputStream in = CreatureShape.class.getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("Missing creature shape " + path);
                return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), CreatureShape.class);
            } catch (Exception e) {
                ExplorationRemix.LOGGER.error("Could not load creature shape {}", k, e);
                CreatureShape fallback = new CreatureShape();
                Part body = new Part(); body.name = "body"; body.pivot = new float[]{0, 16, 0};
                Box b = new Box(); b.from = new float[]{-4, -4, -4}; b.size = new float[]{8, 8, 8};
                body.boxes = List.of(b); body.role = "body";
                fallback.parts = List.of(body);
                return fallback;
            }
        });
    }

    public LayerDefinition toLayerDefinition() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        Map<String, PartDefinition> defs = new HashMap<>();
        for (Part p : parts) {
            CubeListBuilder cubes = CubeListBuilder.create();
            for (Box b : p.boxes) {
                cubes.texOffs(b.uv[0], b.uv[1]).addBox(b.from[0], b.from[1], b.from[2], b.size[0], b.size[1], b.size[2], new CubeDeformation(b.inflate));
            }
            PartDefinition parent = p.parent == null || p.parent.isEmpty() ? root : defs.getOrDefault(p.parent, root);
            defs.put(p.name, parent.addOrReplaceChild(p.name, cubes, PartPose.offset(p.pivot[0], p.pivot[1], p.pivot[2])));
        }
        return LayerDefinition.create(mesh, texture_width, texture_height);
    }
}
