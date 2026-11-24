package com.explorationremix.creature.body;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * The vent urchin as drawn this frame (0.21.3): its rig ({@link LurkerRig}) laid on its body ({@link LurkerBody}) part
 * way through the tick. The test sits in the body's frame, a boss on it at each walking spine's socket. Each walking
 * spine reaches from its socket to its point on the rock: its short base up and out to its knee, close to the test,
 * and its long needle down from there (a two-bone reach, the knee bent up and out: Taran's card, "Needles with one
 * knee"). The coat's short spines stand out along their ways (swaying, pointing at what it attends to); the five teeth
 * under it close to a point, and open as it bites. Dying, it settles onto the rock and its spines lose their hold and
 * lie down flat round it.
 *
 * <p>Plain numbers and JOML: the game hands it light and takes its quads (see LurkerModel).
 */
public final class LurkerPose {
    /** What the moment adds to the body. */
    public static final class Inputs {
        /** Ticks it has lived. */
        public double age;
        /** 0 to 1: how far it has died. */
        public double dying;
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
     * How much of its glow a point of it shows: by its way down the body and down its walking spine (pixels, see
     * LurkerRig; -1 off the walking spines) and its way round it (0 to 1).
     */
    public interface Shade {
        float at(float down, float leg, float round);
    }

    /** All of its glow everywhere. */
    public static final Shade STEADY = (down, leg, round) -> 1.0F;

    /** Takes each corner of each glowing quad, four to a quad, with the share of its glow there. */
    public interface GlowSink {
        void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, float shade);
    }

    /** Dying: its middle settles to this far over the rock under it (blocks at scale 1); its spines lie this far under level. */
    static final double DEATH_RIDE = 0.55, DEATH_SPLAY = Math.toRadians(13);

    public final LurkerRig rig;
    private final LurkerSpec spec;
    private final double k;

    // per part: a linear map from the part's pixels to blocks, where its origin is, its light
    private final Matrix3d[] lin;
    private final Vector3d[] org;
    private final int[] partLight;
    private final float[] x, y, z, qx, qy, qz;
    private boolean ready;

    /** This frame, for checks: each walking spine's socket, its knee, where its point is drawn, and how far short of its foot it fell. */
    final Vector3d[] socketAt = vectors(LurkerSpec.LEGS), kneeAt = vectors(LurkerSpec.LEGS), pointAt = vectors(LurkerSpec.LEGS);
    final double[] reachMiss = new double[LurkerSpec.LEGS];
    /** This frame: the body's centre, up and forward (after dying's settling). */
    public final Vector3d centre = new Vector3d(), upNow = new Vector3d(), forwardNow = new Vector3d();

    // scratch
    private final Vector3d p = new Vector3d(), f = new Vector3d(), u = new Vector3d(), l = new Vector3d();
    private final Vector3d t0 = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), n0 = new Vector3d(), dir = new Vector3d();
    private final Vector3d ax = new Vector3d(), ay = new Vector3d(), az = new Vector3d();
    private final Vector3d root = new Vector3d(), hip = new Vector3d(), tip = new Vector3d(), knee = new Vector3d(), end = new Vector3d(),
            pole = new Vector3d();
    private final double[] way = new double[3];

    public LurkerPose(LurkerRig rig) {
        this.rig = rig;
        this.spec = rig.spec;
        this.k = spec.scale / 16.0;
        int np = rig.partCount;
        lin = new Matrix3d[np];
        org = vectors(np);
        for (int i = 0; i < np; i++) lin[i] = new Matrix3d();
        partLight = new int[np];
        x = new float[rig.vertexCount]; y = new float[rig.vertexCount]; z = new float[rig.vertexCount];
        int nq = rig.quadCount();
        qx = new float[nq]; qy = new float[nq]; qz = new float[nq];
    }

    private static Vector3d[] vectors(int n) {
        Vector3d[] v = new Vector3d[n];
        for (int i = 0; i < n; i++) v[i] = new Vector3d();
        return v;
    }

    public boolean ready() { return ready; }

    // ------------------------------------------------------------------------------------------------ the frame

    /**
     * Poses the rig on {@code body} part way ({@code pt}) through the tick. Positions are kept from (ox, oy, oz) (the
     * entity's place as drawn); {@code light} may be null (all lit by {@code fallback}).
     */
    public void compute(LurkerBody body, double pt, Inputs in, double ox, double oy, double oz, Light light, int fallback) {
        ready = true;
        double dying = Math.max(0.0, Math.min(1.0, in.dying));
        double settle = smooth(dying), splay = smooth(Math.min(1.0, dying * 1.5));
        body.frame(pt, p, f, u);
        l.set(u).cross(f).normalize();
        if (settle > 0) {
            // dying: down onto the rock its spines stood on (their points' mean, along its way out)
            double ground = 0;
            int nf = 0;
            for (LurkerBody.Foot ft : body.feet) {
                ft.sole(pt, t0, n0);
                double g = t0.sub(p).dot(u);
                if (g < 0) { ground += g; nf++; }
            }
            double drop = (nf > 0 ? ground / nf : -spec.ride) + DEATH_RIDE * spec.scale;
            if (drop < 0) p.fma(drop * settle, u);
        }
        centre.set(p);
        upNow.set(u);
        forwardNow.set(f);

        int lBody = light == null ? fallback : light.at(p.x, p.y, p.z);
        // ---- the test: forward along its own -z, up along -y, its left along +x
        frameOf(rig.test, p, ax.set(l), ay.set(u).negate(), az.set(f).negate(), 1);
        partLight[rig.test] = lBody;
        teeth(body, pt, settle, lBody);
        coat(body, pt, lBody);
        spines(body, pt, splay, light, fallback, lBody);

        // ---- the vertices
        int vc = rig.vertexCount;
        for (int i = 0; i < vc; i++) {
            int part = rig.vPart[i];
            Matrix3d m = lin[part];
            Vector3d o = org[part];
            double lx = rig.vx[i], ly = rig.vy[i], lz = rig.vz[i];
            x[i] = (float) (o.x + m.m00 * lx + m.m10 * ly + m.m20 * lz - ox);
            y[i] = (float) (o.y + m.m01 * lx + m.m11 * ly + m.m21 * lz - oy);
            z[i] = (float) (o.z + m.m02 * lx + m.m12 * ly + m.m22 * lz - oz);
        }
        for (int q = 0, i = 0; q < qx.length; q++, i += 4) {
            double a0 = x[i + 2] - x[i], a1 = y[i + 2] - y[i], a2 = z[i + 2] - z[i];
            double b0 = x[i + 3] - x[i + 1], b1 = y[i + 3] - y[i + 1], b2 = z[i + 3] - z[i + 1];
            double c0 = a1 * b2 - a2 * b1, c1 = a2 * b0 - a0 * b2, c2 = a0 * b1 - a1 * b0;
            double ln = Math.sqrt(c0 * c0 + c1 * c1 + c2 * c2);
            if (ln < 1.0E-12) { qx[q] = 0; qy[q] = 1; qz[q] = 0; }
            else { qx[q] = (float) (c0 / ln); qy[q] = (float) (c1 / ln); qz[q] = (float) (c2 / ln); }
        }
    }

    /** Hands every quad's corners to {@code sink}; with {@code bright} they all take {@code fullBright}. */
    public void emit(Sink sink, int fullBright, boolean bright) {
        if (!ready) return;
        for (int q = 0, i = 0; q < qx.length; q++) {
            float nx = qx[q], ny = qy[q], nz = qz[q];
            int lt = bright ? fullBright : partLight[rig.vPart[i]];
            for (int c = 0; c < 4; c++, i++) sink.vertex(x[i], y[i], z[i], rig.vu[i], rig.vv[i], nx, ny, nz, lt);
        }
    }

    /**
     * Hands the quads of the spines that glow (cut across along them: LurkerRig) to {@code sink}, each corner shaded by
     * {@code shade} (0.21.4: the pulses that run down its body as it chases).
     */
    public void emitGlow(GlowSink sink, Shade shade) {
        if (!ready) return;
        for (int q : rig.glowQuads) {
            float nx = qx[q], ny = qy[q], nz = qz[q];
            for (int c = 0, i = q * 4; c < 4; c++, i++)
                sink.vertex(x[i], y[i], z[i], rig.vu[i], rig.vv[i], nx, ny, nz, shade.at(rig.vDown[i], rig.vLeg[i], rig.vRound[i]));
        }
    }

    // ------------------------------------------------------------------------------------------------ the walking spines

    private void spines(LurkerBody body, double pt, double splay, Light light, int fallback, int lBody) {
        for (int i = 0; i < LurkerSpec.LEGS; i++) {
            LurkerBody.Foot ft = body.feet[i];
            double[] s = spec.socket[i];
            double up = spec.upper[i], len = spec.length[i];
            hip.set(p).fma(s[0], f).fma(s[1], u).fma(s[2], l);
            socketAt[i].set(hip);
            // its boss on the test, square with it
            frameOf(rig.knob[i], hip, ax.set(l), ay.set(u).negate(), az.set(f).negate(), 1);
            partLight[rig.knob[i]] = lBody;
            ft.sole(pt, tip, n0);
            int lTip = light == null ? fallback : light.at(tip.x + n0.x * 0.2, tip.y + n0.y * 0.2, tip.z + n0.z * 0.2);
            // its knee bends up and out (out along the way to its point)
            t0.set(tip).sub(hip);
            t0.fma(-t0.dot(u), u);
            if (t0.lengthSquared() < 1.0E-8) radial(spec.restAngle[i], t0);
            t0.normalize();
            pole.set(u).fma(0.35, t0);
            if (splay > 0) {
                // dying: its hold gone, it lies down flat round the test, its knee sinking out to the side
                radial(spec.restAngle[i], t1);
                double r = 0.95 * spec.reach(i);
                t2.set(hip).fma(r * Math.cos(DEATH_SPLAY), t1).fma(-r * Math.sin(DEATH_SPLAY), u);
                tip.lerp(t2, splay);
                pole.lerp(t1.fma(0.25, u), splay);
            }
            reachMiss[i] = TwoBoneIk.solve(hip, tip, pole, up, len, knee, end);
            kneeAt[i].set(knee);
            // the base, from its socket to its knee
            dir.set(knee).sub(hip);
            if (dir.lengthSquared() < 1.0E-12) dir.set(u);
            dir.normalize();
            rod(rig.base[i], hip, dir, t0, 1.0);
            partLight[rig.base[i]] = lBody;
            // the needle, from its knee to its point (stretched a little to a point just past its reach)
            dir.set(tip).sub(knee);
            double d = dir.length();
            if (d < 1.0E-6) dir.set(end).sub(knee);
            dir.normalize();
            double stretch = Math.max(1.0, Math.min(1.15, d / len));
            pointAt[i].set(knee).fma(len * stretch, dir);
            rod(rig.spine[i], knee, dir, t0, stretch);
            partLight[rig.spine[i]] = mix(lBody, lTip, 0.65);
        }
    }

    // ------------------------------------------------------------------------------------------------ the coat, the teeth

    /** The coat's spines: from their sockets along their ways (the body sways them and turns them to what it attends to). */
    private void coat(LurkerBody body, double pt, int lBody) {
        for (int j = 0; j < spec.coat; j++) {
            double[] s = spec.coatSocket[j];
            root.set(p).fma(s[0], f).fma(s[1], u).fma(s[2], l);
            body.coatWay(j, pt, way);
            dir.set(f).mul(way[0]).fma(way[1], u).fma(way[2], l).normalize();
            rod(rig.coat[j], root, dir, u, 1.0);
            partLight[rig.coat[j]] = lBody;
        }
    }

    /** The teeth: closed, down and in under its middle, their points meeting; open, splayed out round the mouth. */
    private void teeth(LurkerBody body, double pt, double dying, int lBody) {
        double open = body.mouth(pt) * (1.0 - dying);
        for (int t = 0; t < LurkerSpec.TEETH; t++) {
            double[] r = spec.toothRoot[t];
            root.set(p).fma(r[0], f).fma(r[1], u).fma(r[2], l);
            // its way out from the mouth's middle, along the test's underside
            t0.set(f).mul(r[0]).fma(r[2], l);
            if (t0.lengthSquared() < 1.0E-12) t0.set(f);
            t0.normalize();
            t1.set(u).negate().fma(-0.45, t0).normalize();
            t2.set(u).mul(-0.55).fma(0.9, t0).normalize();
            dir.set(t1).lerp(t2, open).normalize();
            rod(rig.tooth[t], root, dir, t0, 1.0);
            partLight[rig.tooth[t]] = lBody;
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The way round the test at azimuth a (radians from forward toward its left), square to its way out. */
    private void radial(double a, Vector3d out) {
        out.set(f).mul(Math.cos(a)).fma(Math.sin(a), l);
    }

    /** A rod part from {@code a} along the unit {@code way} (its own y, scaled by {@code sy}), its upper side (its own x) toward {@code up}. */
    private void rod(int part, Vector3d a, Vector3d way, Vector3d up, double sy) {
        ay.set(way);
        ax.set(up);
        square(ax, ay);
        az.set(ax).cross(ay).normalize();
        double kx = k, ky = k * sy, kz = k;
        lin[part].set(ax.x * kx, ax.y * kx, ax.z * kx, ay.x * ky, ay.y * ky, ay.z * ky, az.x * kz, az.y * kz, az.z * kz);
        org[part].set(a);
    }

    /** Part {@code part}'s frame: origin, and its own x, y, z in the world (unit, square), scaled alike. */
    private void frameOf(int part, Vector3d origin, Vector3d xa, Vector3d ya, Vector3d za, double s) {
        double ks = k * s;
        lin[part].set(xa.x * ks, xa.y * ks, xa.z * ks, ya.x * ks, ya.y * ks, ya.z * ks, za.x * ks, za.y * ks, za.z * ks);
        org[part].set(origin);
    }

    /** Makes v square to the unit vector a (and unit); if it lies along a, any way square to a. */
    private static void square(Vector3d v, Vector3d a) {
        v.fma(-v.dot(a), a);
        if (v.lengthSquared() < 1.0E-10) {
            if (Math.abs(a.y) < 0.9) v.set(0, 1, 0); else v.set(1, 0, 0);
            v.fma(-v.dot(a), a);
        }
        v.normalize();
    }

    /** The unit way part way (t) from unit a to unit b, round the shorter arc. */
    private static void slerp(Vector3d a, Vector3d b, double t, Vector3d out) {
        double c = Math.max(-1.0, Math.min(1.0, a.dot(b)));
        if (c > 0.9995) { out.set(a).lerp(b, t).normalize(); return; }
        double ang = Math.acos(c), s = Math.sin(ang);
        if (s < 1.0E-6) { out.set(a).lerp(b, t).normalize(); return; }
        double wa = Math.sin((1 - t) * ang) / s, wb = Math.sin(t * ang) / s;
        out.set(a).mul(wa).fma(wb, b).normalize();
    }

    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }

    static int mix(int a, int b, double t) {
        int ab = a & 0xFFFF, as = (a >>> 16) & 0xFFFF, bb = b & 0xFFFF, bs = (b >>> 16) & 0xFFFF;
        int block = (int) Math.round(ab + (bb - ab) * t), sky = (int) Math.round(as + (bs - as) * t);
        return block | (sky << 16);
    }
}
