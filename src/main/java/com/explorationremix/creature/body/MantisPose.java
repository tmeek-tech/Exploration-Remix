package com.explorationremix.creature.body;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * The stilt mantis as drawn this frame (0.20.0): its rig ({@link MantisRig}) laid on its body ({@link MantisBody}) part
 * way through the tick. Each walking leg reaches from its hip to the ankle over its foot with the knee arched high and
 * out (two-bone IK), the tarsus down to the foot on its block. The prothorax rises from the thorax with the head on
 * its tip, looking where the body has it look; the grabbing arms hang from the prothorax and reach for their hands
 * (another two-bone reach, the femur's spines on the inside of the fold); the abdomen lies along its swaying joints and
 * breathes; the wings lie folded on it, or flare out and up to show their eyespots. Dying, its legs give way: it
 * sinks onto the rock and tips over on its side.
 *
 * <p>Plain numbers and JOML: the game hands it light and takes its quads (see MantisModel).
 */
public final class MantisPose {
    /** What the moment adds to the body. */
    public static final class Inputs {
        /** Ticks it has lived (for breathing and the wings' buzz). */
        public double age;
        /** 0 to 1: how far it has fallen dying. */
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

    /** The wings flared: out from the body, tips raised, outer edges lifted (radians). */
    static final double WING_OUT = Math.toRadians(72), WING_RAISE = Math.toRadians(36), WING_EDGE = Math.toRadians(26);
    /** Dying: how far it rolls over (radians) and how low its thorax ends over the rock (blocks at scale 1). */
    static final double DEATH_ROLL = Math.toRadians(62), DEATH_RIDE = 0.42;

    public final MantisRig rig;
    private final MantisSpec spec;
    private final double k;
    private final MantisBody.Skeleton sk = new MantisBody.Skeleton();

    // per part: a linear map from the part's pixels to blocks, where its origin is, its light
    private final Matrix3d[] lin;
    private final Vector3d[] org;
    private final int[] partLight;
    private final float[] x, y, z, qx, qy, qz;
    private boolean ready;

    /** This frame, for checks: each leg's hip, knee, ankle and foot, and how far it fell short of its ankle. */
    final Vector3d[] hipAt = vectors(4), kneeAt = vectors(4), ankleAt = vectors(4), footAt = vectors(4);
    final double[] reachMiss = new double[4];
    /** This frame: each hand (the arms' tibia tips) and the head. */
    final Vector3d[] handAt = vectors(2);
    final Vector3d headAt = new Vector3d();

    // scratch
    private final Vector3d t0 = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), t3 = new Vector3d(), t4 = new Vector3d();
    private final Vector3d ax = new Vector3d(), ay = new Vector3d(), az = new Vector3d();
    private final Vector3d jn = new Vector3d(), en = new Vector3d(), pl = new Vector3d(), n0 = new Vector3d();
    private final Vector3d[] abJ = vectors(5);
    private final Vector3d flatLeft = new Vector3d();

    public MantisPose(MantisRig rig) {
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
    public void compute(MantisBody body, double pt, Inputs in, double ox, double oy, double oz, Light light, int fallback) {
        ready = true;
        double dying = Math.max(0.0, Math.min(1.0, in.dying));
        double fall = dying * dying * (3 - 2 * dying);
        double crouch = body.crouch(pt), alert = body.alert(pt), run = body.run(pt);
        body.frame(pt, sk.p, sk.f, sk.u);
        double bodyYaw = body.yaw(pt);
        double sy = Math.sin(bodyYaw), cy = Math.cos(bodyYaw);
        flatLeft.set(cy, 0, sy);
        // its feet's ground under it (for dying: it sinks onto it)
        double ground = 0;
        int nf = 0;
        for (MantisBody.Foot ft : body.feet) {
            ft.sole(pt, t0, n0);
            ground += t0.y;
            nf++;
        }
        ground = nf > 0 ? ground / nf : sk.p.y - spec.ride;
        if (fall > 0) {
            // its legs give way: down toward the rock, rolling onto its right side
            double low = ground + DEATH_RIDE * spec.scale;
            sk.p.y += (low - sk.p.y) * fall;
            t0.set(sk.u).cross(sk.f).normalize();               // left
            double r = DEATH_ROLL * fall, c = Math.cos(r), s = Math.sin(r);
            t1.set(sk.u).mul(c).fma(-s, t0);
            sk.u.set(t1).normalize();
        }
        double breath = Math.sin(in.age * 0.11);
        double proPitch = body.proPitch(crouch, alert) + 0.025 * breath - fall * (spec.proPitch + 0.25);
        double lookPitch = body.lookPitch(pt) + fall * 0.7;
        body.skeleton(sk, proPitch, bodyYaw + body.lookYaw(pt) * (1.0 - fall), lookPitch, body.tilt(pt) * (1.0 - fall) + fall * 0.5, run);
        headAt.set(sk.head);

        int lBody = light == null ? fallback : light.at(sk.p.x, sk.p.y, sk.p.z);
        int lHead = light == null ? fallback : light.at(sk.head.x, sk.head.y, sk.head.z);

        // ---- the plates: thorax, prothorax, head
        frameOf(rig.thorax, sk.p, ax.set(sk.l), ay.set(sk.u).negate(), az.set(sk.f).negate(), 1, 1);
        partLight[rig.thorax] = lBody;
        ay.set(sk.proUp).negate();
        az.set(sk.proDir).negate();
        ax.set(ay).cross(az).normalize();
        frameOf(rig.prothorax, sk.proRoot, ax, ay, az, 1, 1);
        partLight[rig.prothorax] = lBody;
        frameOf(rig.head, sk.head, sk.hx, sk.hy, sk.hz, 1, 1);
        partLight[rig.head] = lHead;

        // ---- the antennae: from their roots to their springing tips
        for (int i = 0; i < 2; i++) {
            Vector3d root = sk.antRoot[i];
            t0.set(body.antTipPrev[i]).lerp(body.antTip[i], pt);
            if (fall > 0) t0.lerp(t1.set(root).fma(spec.antenna, sk.antRest[i]), fall);
            ay.set(t0).sub(root);
            if (ay.lengthSquared() < 1.0E-10) ay.set(sk.antRest[i]);
            ay.normalize();
            square(ax.set(sk.hx).mul(i == 0 ? 1 : -1), ay);
            az.set(ax).cross(ay).normalize();
            frameOf(rig.antenna[i], root, ax, ay, az, 1, 1);
            partLight[rig.antenna[i]] = lHead;
        }

        // ---- the abdomen: along its joints, breathing
        for (int i = 0; i < 5; i++) abJ[i].set(body.abPrev[i]).lerp(body.ab[i], pt);
        // its root rides the thorax as drawn this frame (the joints follow on from it)
        t0.set(sk.abRoot).sub(abJ[0]);
        for (int i = 0; i < 5; i++) abJ[i].add(t0);
        if (fall > 0) {
            // dying it droops onto the rock behind it
            t1.set(abJ[0]);
            for (int i = 0; i < 4; i++) {
                double a = -0.25 - 0.1 * i;
                t2.set(sk.f).mul(-Math.cos(a)).fma(Math.sin(a), sk.u);
                t1.fma(spec.abdomen[i], t2);
                abJ[i + 1].lerp(t1, fall);
            }
        }
        for (int i = 0; i < 4; i++) {
            az.set(abJ[i + 1]).sub(abJ[i]);
            if (az.lengthSquared() < 1.0E-10) az.set(sk.f).negate();
            az.normalize();
            ay.set(sk.u).negate();
            square(ay, az);
            ax.set(ay).cross(az).normalize();
            double swell = 1.0 + 0.035 * Math.sin(in.age * 0.11 - i * 0.7) * (i == 1 || i == 2 ? 1.0 : 0.5);
            frameOf(rig.abdomen[i], abJ[i], ax, ay, az, swell, swell);
            partLight[rig.abdomen[i]] = lBody;
        }

        // ---- the wings: folded along the abdomen's top, or flared
        double w = body.wings(pt) + 0.08 * fall;
        double buzz = run * 0.07 * Math.sin(in.age * 2.9);
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? 1 : -1;
            Vector3d hinge = sk.hinge[i];
            t0.set(abJ[2]).fma(2.3 * k, sk.u).sub(hinge);
            az.set(t0).normalize().fma(side * 0.05, sk.l).normalize();
            ax.set(sk.l);
            square(ax, az);
            ay.set(az).cross(ax).normalize();
            if (w > 1.0E-3 || buzz != 0) {
                double out = WING_OUT * w, raise = WING_RAISE * w + buzz, edge = WING_EDGE * w;
                rotate(ax, sk.u, -side * out); rotate(ay, sk.u, -side * out); rotate(az, sk.u, -side * out);
                n0.set(ax);
                rotate(ay, n0, raise); rotate(az, n0, raise);
                n0.set(az);
                rotate(ax, n0, -side * edge); rotate(ay, n0, -side * edge);
            }
            frameOf(i == 0 ? rig.wingLeft : rig.wingRight, hinge, ax, ay, az, 1, 1);
            partLight[i == 0 ? rig.wingLeft : rig.wingRight] = lBody;
        }

        // ---- the walking legs
        legs(body, pt, fall, light, fallback, lBody);
        // ---- the grabbing arms
        arms(body, pt, fall, lBody);

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
            double l = Math.sqrt(c0 * c0 + c1 * c1 + c2 * c2);
            if (l < 1.0E-12) { qx[q] = 0; qy[q] = 1; qz[q] = 0; }
            else { qx[q] = (float) (c0 / l); qy[q] = (float) (c1 / l); qz[q] = (float) (c2 / l); }
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

    // ------------------------------------------------------------------------------------------------ legs

    private void legs(MantisBody body, double pt, double fall, Light light, int fallback, int lBody) {
        Vector3d l = sk.l, u = sk.u, f = sk.f;
        for (int i = 0; i < 4; i++) {
            MantisBody.Foot ft = body.feet[i];
            double side = ft.left ? 1 : -1;
            double[] h = spec.hip[i];
            Vector3d hip = hipAt[i].set(sk.p).fma(h[0], f).fma(h[1], u).fma(h[2], l);
            Vector3d foot = footAt[i];
            ft.sole(pt, foot, n0);
            if (fall > 0) {
                // dying, its feet slide in under it as its legs fold
                t0.set(sk.p.x, foot.y, sk.p.z);
                foot.lerp(t0, 0.3 * fall);
            }
            Vector3d ankle = ankleAt[i].set(foot).fma(spec.ankleUp, n0).fma(-side * spec.ankleIn, flatLeft);
            // the knee arches up and out, a little ahead for the middle legs and behind for the hind ones
            pl.set(u).fma(side * 0.55, l).fma(ft.mid ? 0.14 : -0.14, f);
            if (fall > 0) pl.lerp(t0.set(0, 1, 0), fall * 0.5);
            Vector3d knee = kneeAt[i];
            TwoBoneIk.solve(hip, ankle, pl, spec.femur, spec.tibia, knee, en);
            reachMiss[i] = en.distance(ankle);
            int lFoot = light == null ? fallback : light.at(foot.x, foot.y + 0.2, foot.z);
            int lKnee = light == null ? fallback : light.at(knee.x, knee.y, knee.z);
            int[] parts = rig.leg[i];
            // the femur: its hot side up and out
            rod(parts[0], hip, knee, t1.set(u).fma(side, l));
            partLight[parts[0]] = lKnee;
            rod(parts[1], knee, en, t1.set(l).mul(side).fma(0.4, u));
            partLight[parts[1]] = mix(lKnee, lFoot, 0.5);
            rod(parts[2], en, foot, t1.set(l).mul(side));
            partLight[parts[2]] = lFoot;
        }
    }

    /** A rod part from {@code a} toward {@code b} (its own y), its hot side (its own x) turned toward {@code hot}. */
    private void rod(int part, Vector3d a, Vector3d b, Vector3d hot) {
        ay.set(b).sub(a);
        if (ay.lengthSquared() < 1.0E-12) ay.set(0, -1, 0);
        ay.normalize();
        ax.set(hot);
        square(ax, ay);
        az.set(ax).cross(ay).normalize();
        frameOf(part, a, ax, ay, az, 1, 1);
    }

    // ------------------------------------------------------------------------------------------------ arms

    private void arms(MantisBody body, double pt, double fall, int lBody) {
        Vector3d f = sk.f, u = sk.u, l = sk.l;
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? 1 : -1;
            Vector3d sh = sk.shoulder[i];
            Vector3d c = t0.set(body.coxaPrev[i]).lerp(body.coxa[i], pt);
            Vector3d h = t1.set(body.handPrev[i]).lerp(body.hand[i], pt);
            Vector3d po = t2.set(body.polePrev[i]).lerp(body.pole[i], pt);
            if (fall > 0) {
                body.foldedArm(i, 1.0, t3, t4, n0);
                c.lerp(t3, fall); h.lerp(t4, fall); po.lerp(n0, fall);
            }
            // from the thorax's frame to the world
            Vector3d cw = n0.set(f).mul(c.x).fma(c.y, u).fma(c.z, l);
            if (cw.lengthSquared() < 1.0E-10) cw.set(0, -1, 0);
            cw.normalize();
            Vector3d a = t3.set(sh).fma(spec.armCoxa, cw);
            Vector3d hw = t4.set(sh).fma(h.x, f).fma(h.y, u).fma(h.z, l);
            pl.set(f).mul(po.x).fma(po.y, u).fma(po.z, l);
            TwoBoneIk.solve(a, hw, pl, spec.armFemur, spec.armTibia, jn, en);
            handAt[i].set(en);
            int[] parts = rig.arm[i];
            rod(parts[0], sh, a, t2.set(l).mul(side));
            // the femur: its spines (its own z) toward the inside of the fold, where the tibia closes
            ay.set(jn).sub(a);
            if (ay.lengthSquared() < 1.0E-12) ay.set(f);
            ay.normalize();
            az.set(en).sub(a);
            square(az, ay);
            if (az.lengthSquared() < 1.0E-10) { az.set(f).negate(); square(az, ay); }
            ax.set(ay).cross(az).normalize();
            frameOf(parts[1], a, ax, ay, az, 1, 1);
            rod(parts[2], jn, en, t2.set(l).mul(side));
            partLight[parts[0]] = lBody;
            partLight[parts[1]] = lBody;
            partLight[parts[2]] = lBody;
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Part {@code part}'s frame: origin, and its own x, y, z in the world (unit, square), its x and y scaled. */
    private void frameOf(int part, Vector3d origin, Vector3d xa, Vector3d ya, Vector3d za, double sx, double sy) {
        double kx = k * sx, ky = k * sy;
        lin[part].set(xa.x * kx, xa.y * kx, xa.z * kx, ya.x * ky, ya.y * ky, ya.z * ky, za.x * k, za.y * k, za.z * k);
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

    /** Turns v about the unit axis by angle (right-handed). */
    private static void rotate(Vector3d v, Vector3d axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle), d = v.dot(axis);
        double cx = axis.y * v.z - axis.z * v.y, cy = axis.z * v.x - axis.x * v.z, cz = axis.x * v.y - axis.y * v.x;
        v.set(v.x * c + cx * s + axis.x * d * (1 - c), v.y * c + cy * s + axis.y * d * (1 - c), v.z * c + cz * s + axis.z * d * (1 - c));
    }

    static int mix(int a, int b, double t) {
        int ab = a & 0xFFFF, as = (a >>> 16) & 0xFFFF, bb = b & 0xFFFF, bs = (b >>> 16) & 0xFFFF;
        int block = (int) Math.round(ab + (bb - ab) * t), sky = (int) Math.round(as + (bs - as) * t);
        return block | (sky << 16);
    }
}
