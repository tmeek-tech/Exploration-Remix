package com.explorationremix.creature.body;

import java.util.ArrayList;
import java.util.List;

/**
 * The stilt mantis's drawn shape (0.20.0), rigged to its body: every part is a solid bone. The thorax, prothorax,
 * head, abdomen segments and wings are plates in their own frames (left, down, back, as the model has them); each leg,
 * arm and antenna segment is a rod running along its own y from its joint (see {@link MantisPose}, which poses them).
 *
 * <p>Every face keeps the texture vanilla's model boxes give it (ModelPart.Cube's layout), so the painted texture lands
 * where tools/stilt_mantis.py put it. A box flat in one direction (a wing, a lobe, the arms' spines) has its two faces
 * set a hair apart, so each side shows its own painting without the two fighting. Built once from the shape.
 */
public final class MantisRig {
    /** How far apart the two faces of a flat box are set (pixels). */
    static final double FLAT_GAP = 0.05;

    public final CreatureShape shape;
    public final MantisSpec spec;

    /** Four to a quad: each vertex's part, its place in the part's own frame (pixels) and on the texture (0 to 1). */
    final int vertexCount;
    final int[] vPart;
    final float[] vx, vy, vz, vu, vv;
    final int partCount;

    // the parts, by their index in the shape
    final int thorax, prothorax, head, wingLeft, wingRight;
    final int[] abdomen = new int[4];
    /** Per walking leg ({@link MantisSpec#LEGS}): femur, tibia, tarsus. */
    final int[][] leg = new int[4][3];
    /** Per arm ({@link MantisSpec#ARMS}): coxa, femur, tibia. */
    final int[][] arm = new int[2][3];
    final int[] antenna = new int[2];

    public MantisRig(CreatureShape shape, MantisSpec spec) {
        this.shape = shape;
        this.spec = spec;
        partCount = shape.parts.size();
        thorax = shape.part("thorax").index;
        prothorax = shape.part("prothorax").index;
        head = shape.part("head").index;
        wingLeft = shape.part("wing_left").index;
        wingRight = shape.part("wing_right").index;
        for (int i = 0; i < 4; i++) abdomen[i] = shape.part("abdomen" + (i + 1)).index;
        for (int i = 0; i < 4; i++) {
            String l = MantisSpec.LEGS[i];
            leg[i][0] = shape.part(l + "_femur").index;
            leg[i][1] = shape.part(l + "_tibia").index;
            leg[i][2] = shape.part(l + "_tarsus").index;
        }
        for (int i = 0; i < 2; i++) {
            String a = MantisSpec.ARMS[i];
            arm[i][0] = shape.part(a + "_coxa").index;
            arm[i][1] = shape.part(a + "_femur").index;
            arm[i][2] = shape.part(a + "_tibia").index;
        }
        antenna[0] = shape.part("antenna_left").index;
        antenna[1] = shape.part("antenna_right").index;

        List<double[]> verts = new ArrayList<>();
        List<Integer> parts = new ArrayList<>();
        for (CreatureShape.Part p : shape.parts) {
            for (CreatureShape.Box box : p.boxes) {
                boolean flat = box.w() < 1.0E-6 || box.h() < 1.0E-6 || box.d() < 1.0E-6;
                double[][][] faces = MossbackRig.faces(box);
                for (double[][] q : faces) {
                    double ax = q[2][0] - q[0][0], ay = q[2][1] - q[0][1], az = q[2][2] - q[0][2];
                    double bx = q[3][0] - q[1][0], by = q[3][1] - q[1][1], bz = q[3][2] - q[1][2];
                    double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                    double area = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (area < 1.0E-6) continue;
                    double ox = 0, oy = 0, oz = 0;
                    if (flat) { ox = nx / area * FLAT_GAP * 0.5; oy = ny / area * FLAT_GAP * 0.5; oz = nz / area * FLAT_GAP * 0.5; }
                    for (double[] c : q) {
                        verts.add(new double[]{c[0] + ox, c[1] + oy, c[2] + oz, c[3] / shape.texWidth, c[4] / shape.texHeight});
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

    /** The mantis's shape packed in the mod's jar (or on the test class path), at {@code scale}. */
    public static MantisRig load(double scale) {
        CreatureShape shape = CreatureShape.load("stilt_mantis");
        return new MantisRig(shape, new MantisSpec(shape, scale));
    }

    private static volatile MantisRig shared;

    /** The one rig every stilt mantis is drawn with (and whose measurements every body moves by), at the game's scale. */
    public static MantisRig shared() {
        MantisRig r = shared;
        if (r == null) {
            synchronized (MantisRig.class) {
                r = shared;
                if (r == null) shared = r = load(MantisSpec.GAME_SCALE);
            }
        }
        return r;
    }
}
