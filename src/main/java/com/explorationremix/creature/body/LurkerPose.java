package com.explorationremix.creature.body;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * The chimney lurker as drawn this frame (0.21.0): its rig ({@link LurkerRig}) laid on its body ({@link LurkerBody})
 * part way through the tick. Each leg's coxa juts from the prosoma's rim; the femur rises from it to a knee arched high
 * over the body and the tibia comes down to the ankle over its foot (two-bone IK), the metatarsus down to the foot on
 * its block. The abdomen lies along its swinging waist and breathes; the fangs hang under the head, folded, and spread
 * as it winds up and strikes; the feelers reach for their tips. Dying, its legs curl in under it and it rolls over
 * onto its back.
 *
 * <p>Plain numbers and JOML: the game hands it light and takes its quads (see LurkerModel).
 */
public final class LurkerPose {
    /** What the moment adds to the body. */
    public static final class Inputs {
        /** Ticks it has lived (for its breathing). */
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

    /** Dying: how far it rolls (radians: over onto its back) and how low its centre ends over the rock (blocks at scale 1). */
    static final double DEATH_ROLL = Math.toRadians(165), DEATH_RIDE = 0.42;

    public final LurkerRig rig;
    private final LurkerSpec spec;
    private final double k;

    // per part: a linear map from the part's pixels to blocks, where its origin is, its light
    private final Matrix3d[] lin;
    private final Vector3d[] org;
    private final int[] partLight;
    private final float[] x, y, z, qx, qy, qz;
    private boolean ready;

    /** This frame, for checks: each leg's hip, knee, ankle and foot, and how far it fell short of its ankle. */
    final Vector3d[] hipAt = vectors(LurkerSpec.LEGS), kneeAt = vectors(LurkerSpec.LEGS), ankleAt = vectors(LurkerSpec.LEGS),
            footAt = vectors(LurkerSpec.LEGS);
    final double[] reachMiss = new double[LurkerSpec.LEGS];
    /** This frame: the body's centre, up and forward (after dying's roll). */
    public final Vector3d centre = new Vector3d(), upNow = new Vector3d(), forwardNow = new Vector3d();

    // scratch
    private final Vector3d p = new Vector3d(), f = new Vector3d(), u = new Vector3d(), l = new Vector3d();
    private final Vector3d t0 = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), t3 = new Vector3d(), n0 = new Vector3d();
    private final Vector3d ax = new Vector3d(), ay = new Vector3d(), az = new Vector3d(), pl = new Vector3d(), en = new Vector3d();
    private final Vector3d root = new Vector3d(), hip = new Vector3d(), knee = new Vector3d(), foot = new Vector3d(), ankle = new Vector3d();

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
        double curl = smooth(Math.min(1.0, dying * 1.6)), roll = smooth(Math.max(0.0, (dying - 0.25) / 0.75));
        body.frame(pt, p, f, u);
        // the rock under it: its feet's mean, out along its way out
        double ground = 0;
        int nf = 0;
        for (LurkerBody.Foot ft : body.feet) {
            if (!ft.planted && !ft.swinging()) continue;
            ft.sole(pt, t0, n0);
            ground += t0.sub(p).dot(u);
            nf++;
        }
        ground = nf > 0 ? ground / nf : -spec.ride;
        if (roll > 0) {
            // dying: down onto the rock and over onto its back, rolling round its heading
            p.fma((Math.max(-spec.ride, ground) + DEATH_RIDE * spec.scale) * roll, u);
            double r = DEATH_ROLL * roll, c = Math.cos(r), s = Math.sin(r);
            l.set(u).cross(f).normalize();
            t1.set(u).mul(c).fma(-s, l);
            u.set(t1).normalize();
        }
        l.set(u).cross(f).normalize();
        centre.set(p);
        upNow.set(u);
        forwardNow.set(f);

        int lBody = light == null ? fallback : light.at(p.x, p.y, p.z);
        double breath = Math.sin(in.age * 0.09) * (1.0 - dying);

        // ---- the prosoma
        frameOf(rig.prosoma, p, ax.set(l), ay.set(u).negate(), az.set(f).negate(), 1, 1, 1);
        partLight[rig.prosoma] = lBody;

        // ---- the abdomen: along its swinging waist, breathing
        body.waist(root);
        if (roll > 0) root.set(p).fma(-spec.waistBack, f).fma(spec.waistUp, u);
        Vector3d a = t2;
        body.abdomen(pt, a);
        if (curl > 0) {
            // dying it settles back in line with the body
            t3.set(f).negate().fma(0.15, u).normalize();
            a.lerp(t3, curl).normalize();
        }
        az.set(a);
        ay.set(u).negate();
        square(ay, az);
        ax.set(ay).cross(az).normalize();
        double swell = 1.0 + 0.03 * breath;
        frameOf(rig.abdomen, root, ax, ay, az, swell, swell, 1);
        partLight[rig.abdomen] = light == null ? fallback : light.at(root.x + a.x * spec.abdomen * 0.5, root.y + a.y * spec.abdomen * 0.5,
                root.z + a.z * spec.abdomen * 0.5);

        // ---- the fangs and feelers
        fangs(body, pt, curl, lBody);
        palps(body, pt, curl, lBody);

        // ---- the legs
        legs(body, pt, curl, roll, light, fallback, lBody);

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

    // ------------------------------------------------------------------------------------------------ legs

    private void legs(LurkerBody body, double pt, double curl, double roll, Light light, int fallback, int lBody) {
        for (int i = 0; i < LurkerSpec.LEGS; i++) {
            LurkerBody.Foot ft = body.feet[i];
            double side = ft.left ? 1 : -1;
            double[] h = spec.hip[i];
            // the coxa from the rim, out and a little down
            root.set(p).fma(h[0], f).fma(h[1], u).fma(h[2], l);
            double ra = spec.restAngle[i];
            t0.set(f).mul(Math.cos(ra)).fma(side * Math.sin(ra), l).fma(-0.25 - 0.45 * curl, u).normalize();
            hip.set(root).fma(spec.coxa[i], t0);
            hipAt[i].set(hip);
            ft.sole(pt, foot, n0);
            if (curl > 0) {
                // dying, its feet draw in over its belly: the legs fold (rolled onto its back, they curl up in the air)
                t1.set(p).fma(0.5 * spec.scale * Math.cos(ra), f).fma(side * 0.62 * spec.scale * Math.sin(ra), l).fma(-0.42 * spec.scale, u);
                foot.lerp(t1, curl);
                n0.lerp(t3.set(u).negate(), curl).normalize();
            }
            footAt[i].set(foot);
            // the ankle over the foot: the metatarsus stands up from the rock and leans in toward the body (or lies
            // along rock turned from it, hooked over an edge)
            LurkerBody.ankleOf(spec.meta[i], p, u, foot, n0, ankle);
            if (!ft.planted) {
                // a foot in the air out of the leg's reach (left behind as it sets off), or nearer its hip than the
                // folded leg comes, is carried along the line to its hip, as the leg would carry it
                double d = ankle.distance(hip), max = spec.reach(i) * 0.995, min = Math.abs(spec.tibia[i] - spec.femur[i]) * 1.05 + 0.05;
                if (d > max || (d < min && d > 1.0E-6)) {
                    t3.set(hip).sub(ankle).mul((d - (d > max ? max : min)) / d);
                    ankle.add(t3);
                    foot.add(t3);
                }
            }
            ankleAt[i].set(ankle);
            // the knee arches high over the body: up its way out and out from the body
            t1.set(hip).sub(p);
            t1.fma(-t1.dot(u), u);
            if (t1.lengthSquared() < 1.0E-8) t1.set(l).mul(side);
            t1.normalize();
            pl.set(u).fma(0.55, t1);
            if (curl > 0) pl.lerp(t2.set(t1).fma(-0.3, u), curl);
            TwoBoneIk.solve(hip, ankle, pl, spec.femur[i], spec.tibia[i], knee, en);
            kneeAt[i].set(knee);
            reachMiss[i] = en.distance(ankle);
            int lFoot = light == null ? fallback : light.at(foot.x + n0.x * 0.2, foot.y + n0.y * 0.2, foot.z + n0.z * 0.2);
            int lKnee = light == null ? fallback : light.at(knee.x, knee.y, knee.z);
            int[] parts = rig.leg[i];
            // each rod's front (its own -z) turned to face out and up from the leg's bend, its upper side (+x) up
            rod(parts[0], root, hip, t2.set(u));
            partLight[parts[0]] = lBody;
            rod(parts[1], hip, knee, t2.set(u).fma(0.3, t1));
            partLight[parts[1]] = mix(lBody, lKnee, 0.5);
            rod(parts[2], knee, en, t2.set(u).fma(0.5, t1));
            partLight[parts[2]] = mix(lKnee, lFoot, 0.5);
            rod(parts[3], en, foot, t2.set(t1));
            partLight[parts[3]] = lFoot;
        }
    }

    // ------------------------------------------------------------------------------------------------ fangs, feelers

    /** The fangs: their bases hang down and a little forward under the head; the fangs fold in under them, or spread. */
    private void fangs(LurkerBody body, double pt, double curl, int lBody) {
        double open = body.fangs(pt) * (1.0 - curl);
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? 1 : -1;
            root.set(p).fma(spec.cheliceraForward, f).fma(-spec.cheliceraDown, u).fma(side * spec.cheliceraSide, l);
            t0.set(u).mul(-0.78).fma(0.6, f).fma(side * 0.12 * open, l).normalize();
            hip.set(root).fma(spec.chelicera, t0);
            rod(rig.chelicera[i], root, hip, t1.set(f));
            partLight[rig.chelicera[i]] = lBody;
            // folded: in toward the other fang and back; spread: out and forward, ready to strike
            t1.set(l).mul(-side * 0.75).fma(-0.35, f).fma(-0.4, u).normalize();
            t2.set(l).mul(side * 0.55).fma(0.7, f).fma(-0.3, u).normalize();
            t1.lerp(t2, open).normalize();
            knee.set(hip).fma(spec.fang, t1);
            rod(rig.fang[i], hip, knee, t2.set(f));
            partLight[rig.fang[i]] = lBody;
        }
    }

    /** The feelers: a short femur up and out from beside the fangs, the tibia down to the tip the body holds. */
    private void palps(LurkerBody body, double pt, double curl, int lBody) {
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? 1 : -1;
            root.set(p).fma(spec.palpForward, f).fma(-spec.palpDown, u).fma(side * spec.palpSide, l);
            t0.set(body.palpPrev[i]).lerp(body.palp[i], pt);
            if (curl > 0) t0.lerp(t1.set(root).fma(0.2 * spec.scale, u).fma(0.1 * spec.scale, f), curl);
            pl.set(u).fma(side * 0.4, l).fma(0.2, f);
            TwoBoneIk.solve(root, t0, pl, spec.palpFemur, spec.palpTibia, knee, en);
            rod(rig.palpFemur[i], root, knee, t1.set(u));
            rod(rig.palpTibia[i], knee, en, t1.set(u));
            partLight[rig.palpFemur[i]] = lBody;
            partLight[rig.palpTibia[i]] = lBody;
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A rod part from {@code a} toward {@code b} (its own y), its upper side (its own x) turned toward {@code up}. */
    private void rod(int part, Vector3d a, Vector3d b, Vector3d up) {
        ay.set(b).sub(a);
        if (ay.lengthSquared() < 1.0E-12) ay.set(0, -1, 0);
        ay.normalize();
        ax.set(up);
        square(ax, ay);
        az.set(ax).cross(ay).normalize();
        frameOf(part, a, ax, ay, az, 1, 1, 1);
    }

    /** Part {@code part}'s frame: origin, and its own x, y, z in the world (unit, square), each scaled. */
    private void frameOf(int part, Vector3d origin, Vector3d xa, Vector3d ya, Vector3d za, double sx, double sy, double sz) {
        double kx = k * sx, ky = k * sy, kz = k * sz;
        lin[part].set(xa.x * kx, xa.y * kx, xa.z * kx, ya.x * ky, ya.y * ky, ya.z * ky, za.x * kz, za.y * kz, za.z * kz);
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

    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }

    static int mix(int a, int b, double t) {
        int ab = a & 0xFFFF, as = (a >>> 16) & 0xFFFF, bb = b & 0xFFFF, bs = (b >>> 16) & 0xFFFF;
        int block = (int) Math.round(ab + (bb - ab) * t), sky = (int) Math.round(as + (bs - as) * t);
        return block | (sky << 16);
    }
}
