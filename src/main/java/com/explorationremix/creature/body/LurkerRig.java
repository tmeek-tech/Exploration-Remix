package com.explorationremix.creature.body;

import java.util.ArrayList;
import java.util.List;

/**
 * The chimney lurker's drawn shape (0.21.0), rigged to its body: every part is a solid bone. The prosoma and the
 * abdomen are plates in their own frames (left, down, back, as the model has them); each leg segment, fang and feeler
 * segment is a rod running along its own y from its joint (see {@link LurkerPose}, which poses them).
 *
 * <p>Every face keeps the texture vanilla's model boxes give it (ModelPart.Cube's layout), so the painted texture lands
 * where tools/chimney_lurker.py put it. Built once from the shape.
 */
public final class LurkerRig {
    public final CreatureShape shape;
    public final LurkerSpec spec;

    /** Four to a quad: each vertex's part, its place in the part's own frame (pixels) and on the texture (0 to 1). */
    final int vertexCount;
    final int[] vPart;
    final float[] vx, vy, vz, vu, vv;
    final int partCount;

    // the parts, by their index in the shape
    final int prosoma, abdomen;
    /** Per leg: coxa, femur, tibia, metatarsus. */
    final int[][] leg = new int[LurkerSpec.LEGS][4];
    /** Per side (left, right): the fang's base and the fang; the feeler's femur and tibia. */
    final int[] chelicera = new int[2], fang = new int[2], palpFemur = new int[2], palpTibia = new int[2];

    public LurkerRig(CreatureShape shape, LurkerSpec spec) {
        this.shape = shape;
        this.spec = spec;
        partCount = shape.parts.size();
        prosoma = shape.part("prosoma").index;
        abdomen = shape.part("abdomen").index;
        for (int i = 0; i < LurkerSpec.LEGS; i++) {
            String n = LurkerSpec.legName(i);
            leg[i][0] = shape.part(n + "_coxa").index;
            leg[i][1] = shape.part(n + "_femur").index;
            leg[i][2] = shape.part(n + "_tibia").index;
            leg[i][3] = shape.part(n + "_meta").index;
        }
        for (int s = 0; s < 2; s++) {
            String side = s == 0 ? "l" : "r";
            chelicera[s] = shape.part("chelicera_" + side).index;
            fang[s] = shape.part("fang_" + side).index;
            palpFemur[s] = shape.part("palp_" + side + "_femur").index;
            palpTibia[s] = shape.part("palp_" + side + "_tibia").index;
        }

        List<double[]> verts = new ArrayList<>();
        List<Integer> parts = new ArrayList<>();
        for (CreatureShape.Part p : shape.parts) {
            for (CreatureShape.Box box : p.boxes) {
                double[][][] faces = MossbackRig.faces(box);
                for (double[][] q : faces) {
                    double ax = q[2][0] - q[0][0], ay = q[2][1] - q[0][1], az = q[2][2] - q[0][2];
                    double bx = q[3][0] - q[1][0], by = q[3][1] - q[1][1], bz = q[3][2] - q[1][2];
                    double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                    if (Math.sqrt(nx * nx + ny * ny + nz * nz) < 1.0E-6) continue;
                    for (double[] c : q) {
                        verts.add(new double[]{c[0], c[1], c[2], c[3] / shape.texWidth, c[4] / shape.texHeight});
                        parts.add(p.index);
                    }
                }
            }
        }
        vertexCount = verts.size();
        vPart = new int[vertexCount];
        vx = new float[vertexCount]; vy = new float[vertexCount]; vz = new float[vertexCount];
        vu = new float[vertexCount]; vv = new float[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            double[] v = verts.get(i);
            vPart[i] = parts.get(i);
            vx[i] = (float) v[0]; vy[i] = (float) v[1]; vz[i] = (float) v[2];
            vu[i] = (float) v[3]; vv[i] = (float) v[4];
        }
    }

    public int quadCount() { return vertexCount / 4; }

    /** The lurker's shape packed in the mod's jar (or on the test class path), at {@code scale}. */
    public static LurkerRig load(double scale) {
        CreatureShape shape = CreatureShape.load("chimney_lurker");
        return new LurkerRig(shape, new LurkerSpec(shape, scale));
    }

    private static volatile LurkerRig shared;

    /** The one rig every lurker is drawn with (and whose measurements every body moves by), at the game's scale. */
    public static LurkerRig shared() {
        LurkerRig r = shared;
        if (r == null) {
            synchronized (LurkerRig.class) {
                r = shared;
                if (r == null) shared = r = load(LurkerSpec.GAME_SCALE);
            }
        }
        return r;
    }
}
