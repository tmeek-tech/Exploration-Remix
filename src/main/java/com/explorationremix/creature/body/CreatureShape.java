package com.explorationremix.creature.body;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A creature's shape as tools/creatures.py writes it (assets/explorationremix/geometry/<name>.json): a tree of named
 * parts, each a pivot, a rest rotation and boxes with their place on the texture. Read with plain JSON, no game
 * classes, so a body can measure itself from it on either side, and tests can read it from the source tree.
 *
 * <p>Model space is vanilla's: units of 1/16 block, y grows downward, the ground at y 24, the creature facing -z.
 */
public final class CreatureShape {
    public record Box(double x, double y, double z, double w, double h, double d, int u, int v, double inflate) {}

    public static final class Part {
        public final String name, parent;
        public final int index, parentIndex;
        /** Pivot in the parent's frame, rest rotation (radians, applied as vanilla does: z, then y, then x). */
        public final double px, py, pz, rx, ry, rz;
        public final List<Box> boxes;
        /** The pivot in model space, and the part's rest rotation in model space (the parent's times its own). */
        public final Vector3d restPivot = new Vector3d();
        public final Matrix3d restTurn = new Matrix3d();

        Part(String name, String parent, int index, int parentIndex, double[] pivot, double[] rot, List<Box> boxes) {
            this.name = name; this.parent = parent; this.index = index; this.parentIndex = parentIndex;
            this.px = pivot[0]; this.py = pivot[1]; this.pz = pivot[2];
            this.rx = rot[0]; this.ry = rot[1]; this.rz = rot[2];
            this.boxes = boxes;
        }
    }

    public final int texWidth, texHeight;
    public final List<Part> parts;
    private final Map<String, Part> byName = new HashMap<>();

    private CreatureShape(int texWidth, int texHeight, List<Part> parts) {
        this.texWidth = texWidth;
        this.texHeight = texHeight;
        this.parts = Collections.unmodifiableList(parts);
        for (Part p : parts) byName.put(p.name, p);
        for (Part p : parts) {
            Matrix3d own = new Matrix3d().rotationZYX(p.rz, p.ry, p.rx);
            if (p.parentIndex < 0) {
                p.restPivot.set(p.px, p.py, p.pz);
                p.restTurn.set(own);
            } else {
                Part q = parts.get(p.parentIndex);
                Vector3d off = new Vector3d(p.px, p.py, p.pz);
                q.restTurn.transform(off);
                p.restPivot.set(q.restPivot).add(off);
                p.restTurn.set(q.restTurn).mul(own);
            }
        }
    }

    public Part part(String name) {
        Part p = byName.get(name);
        if (p == null) throw new IllegalArgumentException("no part " + name);
        return p;
    }

    public boolean has(String name) { return byName.containsKey(name); }

    /** The shape packed in the mod's jar (or on the test class path). */
    public static CreatureShape load(String name) {
        String path = "/assets/explorationremix/geometry/" + name + ".json";
        try (InputStream in = CreatureShape.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    public static CreatureShape parse(Reader reader) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        List<Part> parts = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        double k = Math.PI / 180.0;
        for (JsonElement el : json.getAsJsonArray("parts")) {
            JsonObject p = el.getAsJsonObject();
            String name = p.get("name").getAsString(), parent = p.get("parent").getAsString();
            Integer pi = parent.equals("root") ? Integer.valueOf(-1) : index.get(parent);
            if (pi == null) throw new IllegalStateException("part " + name + " comes before its parent " + parent);
            List<Box> boxes = new ArrayList<>();
            for (JsonElement be : p.getAsJsonArray("boxes")) {
                JsonObject b = be.getAsJsonObject();
                double[] o = doubles(b.getAsJsonArray("origin")), s = doubles(b.getAsJsonArray("size")), uv = doubles(b.getAsJsonArray("uv"));
                boxes.add(new Box(o[0], o[1], o[2], s[0], s[1], s[2], (int) uv[0], (int) uv[1], b.get("inflate").getAsDouble()));
            }
            double[] r = doubles(p.getAsJsonArray("rotation"));
            parts.add(new Part(name, parent, parts.size(), pi, doubles(p.getAsJsonArray("pivot")),
                    new double[]{r[0] * k, r[1] * k, r[2] * k}, Collections.unmodifiableList(boxes)));
            index.put(name, parts.size() - 1);
        }
        return new CreatureShape(json.get("texture_width").getAsInt(), json.get("texture_height").getAsInt(), parts);
    }

    private static double[] doubles(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }
}
