package com.explorationremix.creature.body;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * The ember urchin as drawn this frame (0.23.7): its rig ({@link EmberRig}) laid on its body ({@link EmberBody}) part
 * way through the tick. The ball sits at its middle, turned by its lean, swelling a little as it breathes; a boss on it
 * at each walking spine's socket; each walking spine bent along its bow ({@link EmberBody.Bow}) from its socket to its
 * point, the corners of each row of its rod set across the bow there; the coat's short spines standing out along their
 * ways. The ball's corners take the sphere's own way out as their normal, so it shades round; the rest take their
 * quad's.
 *
 * <p>Plain numbers and JOML: the game hands it light and takes its quads (see EmberUrchinModel).
 */
public final class EmberPose {
    /** What the moment adds to the body. */
    public static final class Inputs {
        /** Ticks it has lived. */
        public double age;
        /** -1 to 1: its ball's breath (it swells a little as its embers brighten). */
        public double breath;
    }

    /** The packed light at a point of the world. */
    public interface Light {
        int at(double x, double y, double z);
    }

    /** Takes each corner of each quad, four to a quad (positions from the origin given to compute, in blocks). */
    public interface Sink {
        void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int light);
    }

    /**
     * How much of its glow a point of it shows: by what it is part of ({@link EmberRig#BALL}, {@link EmberRig#SPINE},
     * {@link EmberRig#COAT}), its way along its spine (pixels from its root) and its phase (0 to 1: over the ball, the
     * way the breath rolls; on a spine, its own).
     */
    public interface Shade {
        float at(int kind, float along, float phase);
    }

    /** All of its glow everywhere. */
    public static final Shade STEADY = (kind, along, phase) -> 1.0F;

    /** Takes each corner of each glowing quad, four to a quad, with the share of its glow there. */
    public interface GlowSink {
        void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, float shade);
    }

    /** How much its ball swells at the top of a breath. */
    static final double SWELL = 0.018;

    public final EmberRig rig;
    private final EmberSpec spec;
    private final double k;

    // per part: a linear map from the part's pixels to blocks, and where its origin is
    private final Matrix3d[] lin;
    private final Vector3d[] org;
    private final float[] x, y, z, nx, ny, nz;
    private final int[] light;
    // per walking spine, per row: where the row lies, the rod's way across it in its bow (X) and round it (Z), its light
    private final int rows;
    private final double[] rx, ry, rz, xx, xy, xz, zx, zy, zz;
    private final int[] rowLight;
    private boolean ready;

    /** This frame: the ball's middle, each walking spine's point as drawn. */
    public final Vector3d centre = new Vector3d();
    final Vector3d[] pointAt;

    // scratch
    private final Matrix3d turn = new Matrix3d();
    private final EmberBody.Bow bow = new EmberBody.Bow();
    private final Vector3d ax = new Vector3d(), ay = new Vector3d(), az = new Vector3d(), hip = new Vector3d(), tip = new Vector3d(),
            bend = new Vector3d(), pt0 = new Vector3d(), tg = new Vector3d(), nm = new Vector3d(), rd = new Vector3d(), dir = new Vector3d();
    private final double[] way = new double[3];

    public EmberPose(EmberRig rig) {
        this.rig = rig;
        this.spec = rig.spec;
        this.k = spec.scale / 16.0;
        int np = rig.partCount;
        lin = new Matrix3d[np];
        org = new Vector3d[np];
        for (int i = 0; i < np; i++) { lin[i] = new Matrix3d(); org[i] = new Vector3d(); }
        int vc = rig.vertexCount;
        x = new float[vc]; y = new float[vc]; z = new float[vc];
        nx = new float[vc]; ny = new float[vc]; nz = new float[vc];
        light = new int[vc];
        rows = rig.rowAlong.length;
        int n = spec.legs * rows;
        rx = new double[n]; ry = new double[n]; rz = new double[n];
        xx = new double[n]; xy = new double[n]; xz = new double[n];
        zx = new double[n]; zy = new double[n]; zz = new double[n];
        rowLight = new int[n];
        pointAt = new Vector3d[spec.legs];
        for (int i = 0; i < spec.legs; i++) pointAt[i] = new Vector3d();
    }

    public boolean ready() { return ready; }

    // ------------------------------------------------------------------------------------------------ the frame

    /**
     * Poses the rig on {@code body} part way ({@code pt}) through the tick. Positions are kept from (ox, oy, oz) (the
     * entity's place as drawn); {@code lights} may be null (all lit by {@code fallback}).
     */
    public void compute(EmberBody body, double pt, Inputs in, double ox, double oy, double oz, Light lights, int fallback) {
        ready = true;
        body.centre(pt, centre);
        body.turn(pt, turn);
        int lBody = lights == null ? fallback : lights.at(centre.x, centre.y, centre.z);
        // ---- the ball: the model's pixels (x left, y down, z back) turned half round its z axis into the world, then leant
        double swell = 1.0 + SWELL * Math.max(-1.0, Math.min(1.0, in.breath));
        turn.transform(ax.set(-1, 0, 0));
        turn.transform(ay.set(0, -1, 0));
        turn.transform(az.set(0, 0, 1));
        frameOf(rig.ball, centre, k * swell);
        // ---- the bosses, square with the ball
        for (int i = 0; i < spec.legs; i++) {
            body.socket(i, turn, centre, hip);
            frameOf(rig.boss[i], hip, k);
        }
        // ---- the coat, standing out along its ways
        for (int j = 0; j < spec.coat; j++) {
            double[] s = spec.coatSocket[j];
            turn.transform(hip.set(s[0], s[1], s[2])).add(centre);
            body.coatWay(j, pt, way);
            turn.transform(dir.set(way[0], way[1], way[2]));
            rod(rig.coat[j], hip, dir);
        }
        // ---- the walking spines, along their bows
        for (int i = 0; i < spec.legs; i++) {
            EmberBody.Leg lg = body.legs[i];
            body.socket(i, turn, centre, hip);
            lg.tip(pt, tip);
            lg.bend(pt, bend);
            double L = spec.length[i];
            bow.set(hip, tip, bend, L);
            bow.point(L, pointAt[i]);
            int lTip = lights == null ? fallback : lights.at(tip.x, tip.y, tip.z);
            // the rod's way round it: across its bow at its root, carried along (it turns only in its bow's plane)
            for (int r = 0; r < rows; r++) {
                double s = Math.min(L, rig.rowAlong[r] * k);
                bow.point(s, pt0);
                bow.tangent(s, tg);
                bow.normal(s, nm);
                rd.set(nm).cross(tg);
                int o = i * rows + r;
                rx[o] = pt0.x; ry[o] = pt0.y; rz[o] = pt0.z;
                xx[o] = nm.x; xy[o] = nm.y; xz[o] = nm.z;
                zx[o] = rd.x; zy[o] = rd.y; zz[o] = rd.z;
                rowLight[o] = mix(lBody, lTip, Math.min(1.0, s / L * 1.25));
            }
        }

        // ---- the vertices
        int vc = rig.vertexCount;
        for (int v = 0; v < vc; v++) {
            int part = rig.vPart[v];
            int row = rig.vRow[v];
            double lx = rig.vx[v], ly = rig.vy[v], lz = rig.vz[v];
            if (row >= 0) {
                int o = spineOf(part) * rows + row;
                double cx = lx * k, cz = lz * k;
                x[v] = (float) (rx[o] + xx[o] * cx + zx[o] * cz - ox);
                y[v] = (float) (ry[o] + xy[o] * cx + zy[o] * cz - oy);
                z[v] = (float) (rz[o] + xz[o] * cx + zz[o] * cz - oz);
                light[v] = rowLight[o];
            } else {
                Matrix3d m = lin[part];
                Vector3d g = org[part];
                x[v] = (float) (g.x + m.m00 * lx + m.m10 * ly + m.m20 * lz - ox);
                y[v] = (float) (g.y + m.m01 * lx + m.m11 * ly + m.m21 * lz - oy);
                z[v] = (float) (g.z + m.m02 * lx + m.m12 * ly + m.m22 * lz - oz);
                light[v] = lBody;
            }
        }
        // ---- the normals: the ball's round, the rest their quad's
        float cx = (float) (centre.x - ox), cy = (float) (centre.y - oy), cz = (float) (centre.z - oz);
        for (int q = 0, v = 0; q < vc / 4; q++, v += 4) {
            if (rig.vKind[v] == EmberRig.BALL) {
                for (int c = 0; c < 4; c++) {
                    float dx = x[v + c] - cx, dy = y[v + c] - cy, dz = z[v + c] - cz;
                    float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (l < 1.0E-6F) { nx[v + c] = 0; ny[v + c] = 1; nz[v + c] = 0; }
                    else { nx[v + c] = dx / l; ny[v + c] = dy / l; nz[v + c] = dz / l; }
                }
                continue;
            }
            double a0 = x[v + 2] - x[v], a1 = y[v + 2] - y[v], a2 = z[v + 2] - z[v];
            double b0 = x[v + 3] - x[v + 1], b1 = y[v + 3] - y[v + 1], b2 = z[v + 3] - z[v + 1];
            double c0 = a1 * b2 - a2 * b1, c1 = a2 * b0 - a0 * b2, c2 = a0 * b1 - a1 * b0;
            double ln = Math.sqrt(c0 * c0 + c1 * c1 + c2 * c2);
            float fx, fy, fz;
            if (ln < 1.0E-12) { fx = 0; fy = 1; fz = 0; }
            else { fx = (float) (c0 / ln); fy = (float) (c1 / ln); fz = (float) (c2 / ln); }
            for (int c = 0; c < 4; c++) { nx[v + c] = fx; ny[v + c] = fy; nz[v + c] = fz; }
        }
    }

    /** Hands every quad's corners to {@code sink}; with {@code bright} they all take {@code fullBright}. */
    public void emit(Sink sink, int fullBright, boolean bright) {
        if (!ready) return;
        for (int v = 0, vc = rig.vertexCount; v < vc; v++)
            sink.vertex(x[v], y[v], z[v], rig.vu[v], rig.vv[v], nx[v], ny[v], nz[v], bright ? fullBright : light[v]);
    }

    /** Hands the quads that glow (the ball, the walking spines' points, the longer coat spines') to {@code sink}, shaded by {@code shade}. */
    public void emitGlow(GlowSink sink, Shade shade) {
        if (!ready) return;
        for (int q : rig.glowQuads)
            for (int c = 0, v = q * 4; c < 4; c++, v++)
                sink.vertex(x[v], y[v], z[v], rig.vu[v], rig.vv[v], nx[v], ny[v], nz[v], shade.at(rig.vKind[v], rig.vAlong[v], rig.vPhase[v]));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private int[] spineIndex;

    /** The walking spine a part is (its index among the spines). */
    private int spineOf(int part) {
        if (spineIndex == null) {
            int[] m = new int[rig.partCount];
            java.util.Arrays.fill(m, -1);
            for (int i = 0; i < spec.legs; i++) m[rig.spine[i]] = i;
            spineIndex = m;
        }
        return spineIndex[part];
    }

    /** Part {@code part}'s frame: origin, and its own x, y, z (in {@link #ax}, {@link #ay}, {@link #az}), scaled by {@code s}. */
    private void frameOf(int part, Vector3d origin, double s) {
        lin[part].set(ax.x * s, ax.y * s, ax.z * s, ay.x * s, ay.y * s, ay.z * s, az.x * s, az.y * s, az.z * s);
        org[part].set(origin);
    }

    /** A rod part from {@code a} along the unit {@code way} (its own y), its own x any way square to it. */
    private void rod(int part, Vector3d a, Vector3d w) {
        Vector3d ry0 = tg.set(w).normalize();
        Vector3d rx0 = nm.set(Math.abs(ry0.y) < 0.9 ? 0 : 1, Math.abs(ry0.y) < 0.9 ? 1 : 0, 0);
        EmberBody.square(rx0, ry0);
        Vector3d rz0 = rd.set(rx0).cross(ry0).normalize();
        lin[part].set(rx0.x * k, rx0.y * k, rx0.z * k, ry0.x * k, ry0.y * k, ry0.z * k, rz0.x * k, rz0.y * k, rz0.z * k);
        org[part].set(a);
    }

    static int mix(int a, int b, double t) {
        int ab = a & 0xFFFF, as = (a >>> 16) & 0xFFFF, bb = b & 0xFFFF, bs = (b >>> 16) & 0xFFFF;
        int block = (int) Math.round(ab + (bb - ab) * t), sky = (int) Math.round(as + (bs - as) * t);
        return block | (sky << 16);
    }
}
