package com.explorationremix.creature.body;

import java.util.ArrayList;
import java.util.List;

/**
 * The vent urchin's drawn shape (0.21.3), rigged to its body: every part is a solid bone. The test is a ball in its
 * own frame (left, down, back, as the model has it), and so is the boss at each walking spine's socket; each walking
 * spine's base and needle, each coat spine and each tooth is a rod running along its own y from its root (see
 * {@link LurkerPose}, which poses them).
 *
 * <p>Every face keeps the texture vanilla's model boxes give it (ModelPart.Cube's layout), so the painted texture lands
 * where tools/chimney_lurker.py put it. Built once from the shape.
 *
 * <p>0.21.4 (Taran: "make the lights on the vent urchin pulse down it's body when it's chasing"): the long faces of every
 * spine that glows (each walking spine's base and needle, each coat spine) are cut across every {@link #SLICE} pixels,
 * and each corner knows how far down the body it lies, so the glowing layer can shade pulses running down it ({@link
 * LurkerPose#emitGlow}). The main pass draws the same cut faces, so the glow lies exactly on them.
 */
public final class LurkerRig {
    public final CreatureShape shape;
    public final LurkerSpec spec;

    /** Four to a quad: each vertex's part, its place in the part's own frame (pixels) and on the texture (0 to 1). */
    final int vertexCount;
    final int[] vPart;
    final float[] vx, vy, vz, vu, vv;
    final int partCount;

    /** A glowing spine's long faces are cut across this often (pixels along it). */
    static final double SLICE = 3.0;
    /**
     * Each vertex's way down the body (pixels): from its crown down over its test to a spine's root, then along the
     * spine; its way down its walking spine (pixels from its socket; -1 off the walking spines); its way round the body
     * (0 to 1, from forward toward its left). -1 for all three on what does not glow.
     */
    final float[] vDown, vLeg, vRound;
    /** The quads of the spines that glow (by their first vertex / 4). */
    final int[] glowQuads;

    // the parts, by their index in the shape
    final int test;
    final int[] base = new int[LurkerSpec.LEGS], spine = new int[LurkerSpec.LEGS], knob = new int[LurkerSpec.LEGS], tooth = new int[LurkerSpec.TEETH];
    final int[] coat;

    public LurkerRig(CreatureShape shape, LurkerSpec spec) {
        this.shape = shape;
        this.spec = spec;
        partCount = shape.parts.size();
        test = shape.part("test").index;
        for (int i = 0; i < LurkerSpec.LEGS; i++) {
            base[i] = shape.part("base" + i).index;
            spine[i] = shape.part("spine" + i).index;
            knob[i] = shape.part("knob" + i).index;
        }
        for (int t = 0; t < LurkerSpec.TEETH; t++) tooth[t] = shape.part("tooth" + t).index;
        coat = new int[spec.coat];
        for (int j = 0; j < spec.coat; j++) coat[j] = shape.part("coat" + j).index;

        // how far down the body each glowing part's root lies (pixels), how far down its walking spine, how far round
        double k = spec.scale / 16.0, crown = spec.halfHeight / k;
        double[] rootDown = new double[partCount], rootLeg = new double[partCount], round = new double[partCount];
        boolean[] glows = new boolean[partCount];
        for (int i = 0; i < LurkerSpec.LEGS; i++) {
            CreatureShape.Part b = shape.part("base" + i);
            double down = crown + b.py;   // the model's y runs down, its crown at -crown
            glows[base[i]] = glows[spine[i]] = true;
            rootDown[base[i]] = down;
            rootDown[spine[i]] = down + spec.upper[i] / k;
            rootLeg[base[i]] = 0.0;
            rootLeg[spine[i]] = spec.upper[i] / k;
            round[base[i]] = round[spine[i]] = i / (double) LurkerSpec.LEGS;
        }
        for (int j = 0; j < spec.coat; j++) {
            CreatureShape.Part c = shape.part("coat" + j);
            glows[coat[j]] = true;
            rootDown[coat[j]] = crown + c.py;
            rootLeg[coat[j]] = -1.0;
            double a = Math.atan2(c.px, -c.pz) / (2.0 * Math.PI);   // forward is the model's -z, its left +x
            round[coat[j]] = a - Math.floor(a);
        }

        List<double[]> verts = new ArrayList<>();
        List<Integer> parts = new ArrayList<>();
        List<Integer> lit = new ArrayList<>();
        for (CreatureShape.Part p : shape.parts) {
            boolean glow = glows[p.index];
            boolean coatPart = glow && rootLeg[p.index] < 0;
            for (CreatureShape.Box box : p.boxes) {
                double[][][] faces = MossbackRig.faces(box);
                // a glowing rod (not the knob round its root): its long faces cut across along it
                boolean cut = glow && box.inflate() == 0;
                double y0 = box.y(), y1 = box.y() + box.h();
                for (double[][] face : faces) {
                    double ax = face[2][0] - face[0][0], ay = face[2][1] - face[0][1], az = face[2][2] - face[0][2];
                    double bx = face[3][0] - face[1][0], by = face[3][1] - face[1][1], bz = face[3][2] - face[1][2];
                    double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                    if (Math.sqrt(nx * nx + ny * ny + nz * nz) < 1.0E-6) continue;
                    List<double[][]> pieces = new ArrayList<>();
                    if (cut && alongY(face, y0, y1)) {
                        int n = Math.max(1, (int) Math.ceil((y1 - y0) / SLICE - 1.0E-9));
                        for (int c = 0; c < n; c++) pieces.add(sliceY(face, y0, y1, y0 + (y1 - y0) * c / n, y0 + (y1 - y0) * (c + 1) / n));
                    } else pieces.add(face);
                    for (double[][] q : pieces) {
                        if (glow) lit.add(verts.size() / 4);
                        for (double[] c : q) {
                            double leg = -1.0, down = -1.0, rnd = -1.0;
                            if (glow) {
                                down = rootDown[p.index] + (coatPart ? 0.3 : 1.0) * Math.max(0.0, c[1]);
                                leg = coatPart ? -1.0 : rootLeg[p.index] + Math.max(0.0, c[1]);
                                rnd = round[p.index];
                            }
                            verts.add(new double[]{c[0], c[1], c[2], c[3] / shape.texWidth, c[4] / shape.texHeight, down, leg, rnd});
                            parts.add(p.index);
                        }
                    }
                }
            }
        }
        vertexCount = verts.size();
        vPart = new int[vertexCount];
        vx = new float[vertexCount]; vy = new float[vertexCount]; vz = new float[vertexCount];
        vu = new float[vertexCount]; vv = new float[vertexCount];
        vDown = new float[vertexCount]; vLeg = new float[vertexCount]; vRound = new float[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            double[] v = verts.get(i);
            vPart[i] = parts.get(i);
            vx[i] = (float) v[0]; vy[i] = (float) v[1]; vz[i] = (float) v[2];
            vu[i] = (float) v[3]; vv[i] = (float) v[4];
            vDown[i] = (float) v[5]; vLeg[i] = (float) v[6]; vRound[i] = (float) v[7];
        }
        glowQuads = new int[lit.size()];
        for (int i = 0; i < glowQuads.length; i++) glowQuads[i] = lit.get(i);
    }

    /** Whether a box's face runs its length along y (two of its corners at each end). */
    private static boolean alongY(double[][] q, double y0, double y1) {
        int lo = 0, hi = 0;
        for (double[] c : q) {
            if (Math.abs(c[1] - y0) < 1.0E-9) lo++;
            else if (Math.abs(c[1] - y1) < 1.0E-9) hi++;
        }
        return lo == 2 && hi == 2;
    }

    /** The part of a face running along y between ya and yb (the face spans y0 to y1), its texture cut to match. */
    static double[][] sliceY(double[][] q, double y0, double y1, double ya, double yb) {
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] c = q[i];
            // its partner: the corner across the face's length (same x and z, the other end)
            double[] o = null;
            for (int j = 0; j < 4; j++) {
                double[] e = q[j];
                if (j != i && Math.abs(e[0] - c[0]) < 1.0E-9 && Math.abs(e[2] - c[2]) < 1.0E-9) { o = e; break; }
            }
            boolean top = Math.abs(c[1] - y0) < 1.0E-9;
            double[] a = top ? c : o, b = top ? o : c;          // a at y0, b at y1
            double y = top ? ya : yb;
            double t = (y - y0) / (y1 - y0);
            out[i] = new double[]{c[0], y, c[2], a[3] + (b[3] - a[3]) * t, a[4] + (b[4] - a[4]) * t};
        }
        return out;
    }

    public int quadCount() { return vertexCount / 4; }

    /** The urchin's shape packed in the mod's jar (or on the test class path), at {@code scale}. */
    public static LurkerRig load(double scale) {
        CreatureShape shape = CreatureShape.load("chimney_lurker");
        return new LurkerRig(shape, new LurkerSpec(shape, scale));
    }

    private static volatile LurkerRig shared;

    /** The one rig every urchin is drawn with (and whose measurements every body moves by), at the game's scale. */
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
