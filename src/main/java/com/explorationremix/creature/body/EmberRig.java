package com.explorationremix.creature.body;

import java.util.ArrayList;
import java.util.List;

/**
 * The ember urchin's drawn shape (0.23.7), rigged to its body. Its ball is the one box of the "ball" part drawn as a
 * ball: each of the box's faces cut into a grid ({@link #BALL_GRID} a side) and pushed out onto the sphere, a point s
 * across the face (-1 to 1) landing where tan(pi/4 * s) points, as tools/ember_urchin.py paints it, so the plates and
 * their seams run unbroken over the faces' edges; its corners take the sphere's own way out as their normal, so it
 * shades round. The bosses at the walking spines' sockets and the coat's short spines are solid bones. Each walking
 * spine (its shaft and its needle, one rod) is bent along a curve as it is drawn ({@link EmberPose}: the cards' "Springy
 * needles that bow"), so its long faces are cut across every {@link #SLICE} pixels and each corner knows its row: how
 * far along the rod it lies.
 *
 * <p>Every face keeps the texture vanilla's model boxes give it (ModelPart.Cube's layout, {@link MossbackRig#faces}),
 * so the painted texture lands where tools/ember_urchin.py put it. Built once from the shape.
 */
public final class EmberRig {
    public final CreatureShape shape;
    public final EmberSpec spec;

    /** The ball's faces are cut into this many squares a side. */
    static final int BALL_GRID = 8;
    /** A walking spine's long faces are cut across this often (pixels along it). */
    static final double SLICE = 2.0;
    /** A walking spine's point glows from this far along it on (pixels; tools/ember_urchin.py TIP: its last seven). */
    static final double TIP_FROM = 38.0;

    /** What each vertex is part of, for its glow. */
    public static final byte BALL = 0, SPINE = 1, COAT = 2, BOSS = 3;

    /** Four to a quad: each vertex's part, its place in the part's own frame (pixels) and on the texture (0 to 1). */
    final int vertexCount;
    final int[] vPart;
    final float[] vx, vy, vz, vu, vv;
    /** Each vertex's kind ({@link #BALL} ...). */
    final byte[] vKind;
    /**
     * On a walking spine: its row (index into the spine's rows, {@link #rowAlong}), else -1. A walking spine's corners
     * are placed by the curve at their row, out across it by their own x and z.
     */
    final int[] vRow;
    /** Each vertex's way along its spine (pixels from its root; 0 off the spines) and its phase (0 to 1) for the glow. */
    final float[] vAlong, vPhase;
    final int partCount;
    /** The quads that glow (by their first vertex / 4): the ball's, the walking spines' points, the longer coat spines'. */
    final int[] glowQuads;

    // the parts, by their index in the shape
    final int ball;
    final int[] spine, boss, coat;
    /** The ball's radius, pixels. */
    final double ballRadius;
    /** The rows of a walking spine (every one alike): how far along it each lies (pixels from its root). */
    final double[] rowAlong;
    /** A walking spine's length, pixels. */
    final double spineLength;

    public EmberRig(CreatureShape shape, EmberSpec spec) {
        this.shape = shape;
        this.spec = spec;
        partCount = shape.parts.size();
        CreatureShape.Part ballPart = shape.part("ball");
        ball = ballPart.index;
        ballRadius = ballPart.boxes.get(0).w() * 0.5;
        spine = new int[spec.legs];
        boss = new int[spec.legs];
        for (int i = 0; i < spec.legs; i++) {
            spine[i] = shape.part("spine" + i).index;
            boss[i] = shape.part("boss" + i).index;
        }
        coat = new int[spec.coat];
        for (int j = 0; j < spec.coat; j++) coat[j] = shape.part("coat" + j).index;
        byte[] kind = new byte[partCount];
        int[] which = new int[partCount];
        java.util.Arrays.fill(kind, BOSS);
        kind[ball] = BALL;
        for (int i = 0; i < spec.legs; i++) { kind[spine[i]] = SPINE; which[spine[i]] = i; }
        for (int j = 0; j < spec.coat; j++) { kind[coat[j]] = COAT; which[coat[j]] = j; }

        // a walking spine's rows: every slice's ends, over the whole rod
        CreatureShape.Part sp0 = shape.part("spine0");
        List<Double> rows = new ArrayList<>();
        double len = 0;
        for (CreatureShape.Box b : sp0.boxes) {
            if (b.inflate() != 0) continue;
            double y0 = b.y(), y1 = b.y() + b.h();
            len = Math.max(len, y1);
            int n = Math.max(1, (int) Math.ceil((y1 - y0) / SLICE - 1.0E-9));
            for (int c = 0; c <= n; c++) addRow(rows, y0 + (y1 - y0) * c / n);
        }
        spineLength = len;
        rows.sort(Double::compare);
        rowAlong = new double[rows.size()];
        for (int r = 0; r < rowAlong.length; r++) rowAlong[r] = rows.get(r);

        List<double[]> verts = new ArrayList<>();
        List<Integer> parts = new ArrayList<>();
        List<Integer> lit = new ArrayList<>();
        for (CreatureShape.Part p : shape.parts) {
            byte k = kind[p.index];
            double phase = k == SPINE || k == COAT ? frac(which[p.index] * 0.6180339887) : 0.0;
            for (CreatureShape.Box box : p.boxes) {
                double[][][] faces = MossbackRig.faces(box);
                if (k == BALL) {
                    for (double[][] face : faces) ballFace(face, verts, parts, lit, p.index);
                    continue;
                }
                boolean cut = k == SPINE && box.inflate() == 0;
                double y0 = box.y(), y1 = box.y() + box.h();
                for (double[][] face : faces) {
                    double ax = face[2][0] - face[0][0], ay = face[2][1] - face[0][1], az = face[2][2] - face[0][2];
                    double bx = face[3][0] - face[1][0], by = face[3][1] - face[1][1], bz = face[3][2] - face[1][2];
                    double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                    if (Math.sqrt(nx * nx + ny * ny + nz * nz) < 1.0E-6) continue;
                    List<double[][]> pieces = new ArrayList<>();
                    if (cut && alongY(face, y0, y1)) {
                        int n = Math.max(1, (int) Math.ceil((y1 - y0) / SLICE - 1.0E-9));
                        for (int c = 0; c < n; c++) pieces.add(LurkerRig.sliceY(face, y0, y1, y0 + (y1 - y0) * c / n, y0 + (y1 - y0) * (c + 1) / n));
                    } else pieces.add(face);
                    for (double[][] q : pieces) {
                        double top = Math.max(Math.max(q[0][1], q[1][1]), Math.max(q[2][1], q[3][1]));
                        boolean glows = k == SPINE ? box.inflate() == 0 && top > TIP_FROM + 1.0E-6
                                : k == COAT && box.inflate() == 0 && box.h() >= 5;
                        if (glows) lit.add(verts.size() / 4);
                        for (double[] c : q) {
                            int row = k == SPINE ? rowOf(c[1]) : -1;
                            double along = k == SPINE || k == COAT ? Math.max(0.0, c[1]) : 0.0;
                            verts.add(new double[]{c[0], c[1], c[2], c[3] / shape.texWidth, c[4] / shape.texHeight, k, row, along, phase});
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
        vKind = new byte[vertexCount];
        vRow = new int[vertexCount];
        vAlong = new float[vertexCount]; vPhase = new float[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            double[] v = verts.get(i);
            vPart[i] = parts.get(i);
            vx[i] = (float) v[0]; vy[i] = (float) v[1]; vz[i] = (float) v[2];
            vu[i] = (float) v[3]; vv[i] = (float) v[4];
            vKind[i] = (byte) v[5];
            vRow[i] = (int) v[6];
            vAlong[i] = (float) v[7];
            vPhase[i] = (float) v[8];
        }
        glowQuads = new int[lit.size()];
        for (int i = 0; i < glowQuads.length; i++) glowQuads[i] = lit.get(i);
    }

    private static void addRow(List<Double> rows, double y) {
        for (double r : rows) if (Math.abs(r - y) < 1.0E-6) return;
        rows.add(y);
    }

    private int rowOf(double y) {
        int best = 0;
        for (int r = 1; r < rowAlong.length; r++) if (Math.abs(rowAlong[r] - y) < Math.abs(rowAlong[best] - y)) best = r;
        return best;
    }

    /**
     * One of the ball's box faces as a grid of quads pushed out onto the sphere: each corner where its place on the box
     * points (tan(pi/4 * s) of each coordinate s, -1 to 1 across the box), at the ball's radius; its texture as the box's
     * face has it there. The corners keep the face's winding (a b c d), so the quads face out as the box's did.
     */
    private void ballFace(double[][] f, List<double[]> verts, List<Integer> parts, List<Integer> lit, int part) {
        int n = BALL_GRID;
        double r = ballRadius;
        for (int a = 0; a < n; a++)
            for (int b = 0; b < n; b++) {
                double s0 = a / (double) n, s1 = (a + 1) / (double) n, t0 = b / (double) n, t1 = (b + 1) / (double) n;
                double[][] corner = {grid(f, s0, t0), grid(f, s1, t0), grid(f, s1, t1), grid(f, s0, t1)};
                lit.add(verts.size() / 4);
                for (double[] c : corner) {
                    double mx = Math.tan(Math.PI / 4.0 * clamp(c[0] / r)), my = Math.tan(Math.PI / 4.0 * clamp(c[1] / r)), mz = Math.tan(Math.PI / 4.0 * clamp(c[2] / r));
                    double l = Math.sqrt(mx * mx + my * my + mz * mz);
                    double dx = mx / l, dy = my / l, dz = mz / l;
                    // its phase for the glow: the slow breath rolls over the ball from one side to the other
                    double phase = 0.5 + 0.5 * (0.62 * dy + 0.55 * dx - 0.56 * dz);
                    verts.add(new double[]{dx * r, dy * r, dz * r, c[3] / shape.texWidth, c[4] / shape.texHeight, BALL, -1, 0, phase});
                    parts.add(part);
                }
            }
    }

    /** The point s along a face's first edge (a to b) and t along its second (b to c), with its texture there. */
    private static double[] grid(double[][] f, double s, double t) {
        double[] out = new double[5];
        for (int k = 0; k < 5; k++)
            out[k] = (1 - s) * (1 - t) * f[0][k] + s * (1 - t) * f[1][k] + s * t * f[2][k] + (1 - s) * t * f[3][k];
        return out;
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

    private static double clamp(double v) { return Math.max(-1.0, Math.min(1.0, v)); }

    private static double frac(double v) { return v - Math.floor(v); }

    public int quadCount() { return vertexCount / 4; }

    /** The urchin's shape packed in the mod's jar (or on the test class path), at {@code scale}. */
    public static EmberRig load(double scale) {
        CreatureShape shape = CreatureShape.load("ember_urchin");
        return new EmberRig(shape, new EmberSpec(shape, scale));
    }

    private static volatile EmberRig shared;

    /** The one rig every urchin is drawn with (and whose measurements every body moves by), at the game's scale. */
    public static EmberRig shared() {
        EmberRig r = shared;
        if (r == null) {
            synchronized (EmberRig.class) {
                r = shared;
                if (r == null) shared = r = load(EmberSpec.GAME_SCALE);
            }
        }
        return r;
    }
}
