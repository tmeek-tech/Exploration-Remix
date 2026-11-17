package com.explorationremix.creature.body;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * The Mossback as drawn this frame: its rig ({@link MossbackRig}) laid on its body ({@link MossbackBody}) part way
 * through the tick, with what the body leaves to the moment: the side-to-side sway of its trunk and tail in step with
 * its feet, its head turning to what it watches, breathing, the hunt (frills fanned, throat swollen, jaw open, the
 * tremble before a strike), the thrash with a catch, and its death roll. Each leg reaches from its shoulder to its foot
 * with its elbow out and up (two-bone IK), and each foot lies flat on its rock, toes turned a little out.
 *
 * <p>Plain numbers and JOML: the game hands it light and takes its quads (see MossbackModel).
 */
public final class MossbackPose {
    /** What the moment adds to the body. */
    public static final class Inputs {
        /** 0 to 1: the jaw's gape, how far it rears, how far its frills fan, how far through its wind-up. */
        public double jaw, rear, frill, windup;
        /** Ticks it has lived (for breathing and trembles). */
        public double age;
        /** Where its head looks, from the way its body faces (radians: right and down are positive), and whether it does. */
        public double lookYaw, lookPitch;
        public boolean look;
        /** It thrashes a catch, it eats. */
        public boolean thrash, eat;
        /** 0 to 1: how far it has rolled over dying. */
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

    // sway: the trunk's side to side bend (blocks at the middle, walking and trotting) and how each joint takes part
    static final double SWAY_WALK = 0.034, SWAY_TROT = 0.05, SWAY_SPEED = 0.11;
    static final double[] SWAY_TRUNK = {0.0, -0.25, -0.55, 1.0, -0.55, -0.65};
    /** The tail's wave: how far it lags per joint (radians) and how much it grows toward the tip. */
    static final double TAIL_LAG = 0.8, TAIL_GROW = 0.22;
    /** Swimming: the whole body undulates, a wave running back along it. */
    static final double SWIM_SWAY = 0.075, SWIM_RATE = 0.2;
    /** Feet: toes turned out (radians), front and hind; how far the toes droop mid-step. */
    static final double SPLAY_FRONT = Math.toRadians(14), SPLAY_HIND = Math.toRadians(26), TOE_DROOP = 0.45;

    public final MossbackRig rig;
    private final MossbackSpec spec;
    private final SpineCurve curve;
    private final int n;

    // joints this frame
    private final Vector3d[] jp, ju, jl;
    private final int[] jointLight;
    // stations this frame: point, left, down, back, and a scale across the body
    private final Vector3d[] sP, sL, sD, sB;
    private final double[] sScale;
    private final int[] sLight;
    // bones: a linear map from the bone's pixels to blocks, and where its pivot is
    private final Matrix3d[] bLin;
    private final Vector3d[] bT;
    private final int[] bLight;
    // vertices
    private final float[] x, y, z;
    private final float[] qx, qy, qz;
    private final int[] vLight;
    private boolean ready;

    // scratch
    private final Vector3d t0 = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), t3 = new Vector3d(), t4 = new Vector3d();
    private final Vector3d at = new Vector3d(), nrm = new Vector3d(), elbow = new Vector3d(), end = new Vector3d(), pole = new Vector3d();
    private final Matrix3d m0 = new Matrix3d(), m1 = new Matrix3d(), m2 = new Matrix3d(), m3 = new Matrix3d();
    private final Vector3d jawGap = new Vector3d();
    /** How far each foot's leg fell short of its ankle this frame (0 when it reached). */
    final double[] reachMiss = new double[4];
    private final Matrix3d jawTurn = new Matrix3d();

    public MossbackPose(MossbackRig rig) {
        this.rig = rig;
        this.spec = rig.spec;
        this.n = spec.arc.length;
        this.curve = new SpineCurve(spec.arc);
        jp = vectors(n); ju = vectors(n); jl = vectors(n);
        jointLight = new int[n];
        int ns = rig.stationCount();
        sP = vectors(ns); sL = vectors(ns); sD = vectors(ns); sB = vectors(ns);
        sScale = new double[ns];
        sLight = new int[ns];
        int nb = rig.boneCount();
        bLin = new Matrix3d[nb];
        bT = vectors(nb);
        for (int i = 0; i < nb; i++) bLin[i] = new Matrix3d();
        bLight = new int[nb];
        x = new float[rig.vertexCount]; y = new float[rig.vertexCount]; z = new float[rig.vertexCount];
        vLight = new int[rig.vertexCount];
        int nq = rig.quadCount();
        qx = new float[nq]; qy = new float[nq]; qz = new float[nq];
    }

    private static Vector3d[] vectors(int k) {
        Vector3d[] v = new Vector3d[k];
        for (int i = 0; i < k; i++) v[i] = new Vector3d();
        return v;
    }

    public boolean ready() { return ready; }

    // ------------------------------------------------------------------------------------------------ the frame

    /**
     * Poses the rig on {@code body} part way ({@code pt}) through the tick. Positions are kept from (ox, oy, oz) (the
     * entity's place as drawn); {@code light} may be null (all lit by {@code fallback}).
     */
    public void compute(MossbackBody body, double pt, Inputs in, double ox, double oy, double oz, Light light, int fallback) {
        ready = true;
        for (int i = 0; i < n; i++) body.joint(i, pt, jp[i], ju[i]);
        jointFrames();
        sway(body, pt);
        turns(in);
        double dying = Math.max(0.0, Math.min(1.0, in.dying));
        if (dying > 0) {
            // rolling onto its back it sinks onto the rock, so it ends lying on its back, not floating over it
            for (int i = 0; i <= MossbackSpec.TAIL_BASE; i++) jp[i].fma(-Math.max(0.0, spec.ride[i] - 0.27) * dying, ju[i]);
        }
        for (int i = 0; i < n; i++) jointLight[i] = light == null ? fallback : light.at(jp[i].x, jp[i].y, jp[i].z);

        // the spine's frames
        curve.set(jp, ju);
        double roll = dying * Math.PI;
        double tremble = in.windup * in.windup * 0.05 * Math.sin(in.age * 4.6);
        double breath = Math.sin(in.age * 0.09);
        for (int s = 0; s < sP.length; s++) {
            double arc = rig.station[s];
            curve.eval(arc, sP[s], sB[s], t0);
            // the chest trembles about its length winding up a strike; dying it rolls over
            double r = roll + tremble * bump(arc, spec.arc[MossbackSpec.NECK], spec.arc[MossbackSpec.HIPS]);
            if (r != 0) {
                double c = Math.cos(r), sn = Math.sin(r);
                t1.set(sB[s]).cross(t0);                       // left
                t0.mul(c).fma(sn, t1);
            }
            sL[s].set(sB[s]).cross(t0);
            sD[s].set(t0).negate();
            // breathing: the chest swells a little
            sScale[s] = 1.0 + 0.022 * breath * bump(arc, spec.arc[MossbackSpec.NECK], spec.arc[MossbackSpec.HIPS]);
            sLight[s] = lightAt(arc);
        }

        // the skin
        int vc = rig.vertexCount;
        for (int i = 0; i < vc; i++) {
            int s = rig.vStation[i];
            if (s < 0) continue;
            double k = sScale[s];
            double a = rig.va[i] * k, b = rig.vb[i] * k, c = rig.vc[i];
            Vector3d p = sP[s], l = sL[s], d = sD[s], bk = sB[s];
            x[i] = (float) (p.x + l.x * a + d.x * b + bk.x * c - ox);
            y[i] = (float) (p.y + l.y * a + d.y * b + bk.y * c - oy);
            z[i] = (float) (p.z + l.z * a + d.z * b + bk.z * c - oz);
            vLight[i] = sLight[s];
        }

        // the bones
        bones(in);
        legs(body, pt, in, dying, light, fallback);
        for (int i = 0; i < vc; i++) {
            int bi = rig.vBone[i];
            if (bi < 0) continue;
            Matrix3d m = bLin[bi];
            Vector3d tr = bT[bi];
            double lx = rig.va[i], ly = rig.vb[i], lz = rig.vc[i];
            x[i] = (float) (tr.x + m.m00 * lx + m.m10 * ly + m.m20 * lz - ox);
            y[i] = (float) (tr.y + m.m01 * lx + m.m11 * ly + m.m21 * lz - oy);
            z[i] = (float) (tr.z + m.m02 * lx + m.m12 * ly + m.m22 * lz - oz);
            vLight[i] = bLight[bi];
        }
        int jaw = rig.bone("jaw");
        jawTurn.set(bLin[jaw]).scale(16.0);
        jawGap.set(0, -0.5, -7.0);
        bLin[jaw].transform(jawGap).add(bT[jaw]).sub(ox, oy, oz);

        // each quad's facing, from its corners as posed
        for (int q = 0, i = 0; q < qx.length; q++, i += 4) {
            double ax = x[i + 2] - x[i], ay = y[i + 2] - y[i], az = z[i + 2] - z[i];
            double bx = x[i + 3] - x[i + 1], by = y[i + 3] - y[i + 1], bz = z[i + 3] - z[i + 1];
            double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
            double l = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (l < 1.0E-12) { qx[q] = 0; qy[q] = 1; qz[q] = 0; }
            else { qx[q] = (float) (cx / l); qy[q] = (float) (cy / l); qz[q] = (float) (cz / l); }
        }
    }

    /** Hands every quad's corners to {@code sink}; with {@code fullBright} they all take that light. */
    public void emit(Sink sink, int fullBright, boolean bright) {
        if (!ready) return;
        for (int q = 0, i = 0; q < qx.length; q++) {
            float nx = qx[q], ny = qy[q], nz = qz[q];
            for (int k = 0; k < 4; k++, i++)
                sink.vertex(x[i], y[i], z[i], rig.vu[i], rig.vv[i], nx, ny, nz, bright ? fullBright : vLight[i]);
        }
    }

    /** The gap between its jaws, as drawn: its place (from the origin) and the jaw's turn (the model's axes in the world). */
    public Vector3d jawGap() { return jawGap; }
    public Matrix3d jawTurn() { return jawTurn; }

    // ------------------------------------------------------------------------------------------------ the spine

    /** Each joint's left (from its way out and the way along the chain), for the sway and turns. */
    private void jointFrames() {
        for (int i = 0; i < n; i++) {
            Vector3d a = jp[Math.max(0, i - 1)], b = jp[Math.min(n - 1, i + 1)];
            t0.set(a).sub(b);                                    // forward
            if (t0.lengthSquared() < 1.0E-12) t0.set(0, 0, 1);
            t0.normalize();
            jl[i].set(ju[i]).cross(t0);
            if (jl[i].lengthSquared() < 1.0E-10) jl[i].set(1, 0, 0);
            jl[i].normalize();
        }
    }

    /**
     * The sway: walking or trotting, the trunk bends side to side in step with the feet (its middle out to the side of
     * the front foot reaching forward, so that side's shoulder leads), the tail waving after it; swimming, the whole body
     * undulates.
     */
    private void sway(MossbackBody body, double pt) {
        MossbackBody.Mode mode = body.mode();
        if (mode == MossbackBody.Mode.DEAD) return;
        if (mode == MossbackBody.Mode.WATER) {
            double ph = (body.ticks() + pt) * SWIM_RATE;
            for (int i = 1; i < n; i++) {
                double grow = 0.4 + 0.6 * i / (n - 1.0);
                jp[i].fma(SWIM_SWAY * grow * Math.sin(ph - i * 0.55), jl[i]);
            }
            return;
        }
        if (mode != MossbackBody.Mode.GROUND) return;
        double speed = body.speed(pt);
        double amt = Math.min(1.0, speed / SWAY_SPEED);
        amt = amt * amt * (3 - 2 * amt);
        if (amt < 1.0E-3) return;
        double trot = body.trot(pt);
        double a = (SWAY_WALK + (SWAY_TROT - SWAY_WALK) * trot) * amt;
        double ph = 2.0 * Math.PI * (body.phase(pt) - body.offset(0, pt));
        double c = Math.cos(ph);
        for (int i = 0; i <= MossbackSpec.TAIL_BASE; i++) jp[i].fma(a * SWAY_TRUNK[i] * c, jl[i]);
        double base = SWAY_TRUNK[MossbackSpec.TAIL_BASE];
        for (int i = MossbackSpec.TAIL_BASE + 1; i < n; i++) {
            int k = i - MossbackSpec.TAIL_BASE;
            jp[i].fma(a * base * (1.0 + TAIL_GROW * k) * Math.cos(ph - TAIL_LAG * k), jl[i]);
        }
    }

    // turns of the head, the neck and the chest this frame (radians, as the model's: yaw right and pitch down positive)
    private double headYaw, headPitch, headRoll;

    /** The neck and chest turns (moving the joints), and the head's own (kept for its bone). */
    private void turns(Inputs in) {
        double neckYaw = 0, neckPitch = 0, chestYaw = 0;
        headYaw = 0; headPitch = 0; headRoll = 0;
        if (in.look) {
            double yaw = clamp(in.lookYaw, Math.toRadians(60)), pitch = clamp(in.lookPitch, Math.toRadians(40));
            neckYaw += yaw * 0.45; headYaw += yaw * 0.55;
            neckPitch += pitch * 0.4; headPitch += pitch * 0.6;
        }
        if (in.eat) { neckPitch += 0.3; headPitch += 0.25; }
        if (in.thrash) {
            double th = Math.sin(in.age * 1.1);
            neckYaw += th * 0.45; headYaw += th * 0.35; headRoll += th * 0.25; chestYaw -= th * 0.15;
        }
        if (in.windup > 0) {
            double tr = in.windup * in.windup;
            neckYaw += 0.07 * tr * Math.sin(in.age * 5.3 + 1.0);
            headPitch += 0.06 * tr * Math.sin(in.age * 6.1 + 2.0);
        }
        headPitch += in.rear * 0.2 - in.jaw * 0.15;
        int c = MossbackSpec.MIDDLE, s = MossbackSpec.SHOULDERS, nk = MossbackSpec.NECK, h = MossbackSpec.HEAD;
        if (chestYaw != 0) for (int i = h; i <= s; i++) turn(jp[i], jp[c], ju[c], -chestYaw);
        if (neckYaw != 0) turn(jp[h], jp[nk], ju[nk], -neckYaw);
        if (neckPitch != 0) turn(jp[h], jp[nk], jl[nk], neckPitch);
    }

    /** Turns p about the axis through {@code about} along {@code axis} (unit) by {@code angle} (right-handed). */
    private void turn(Vector3d p, Vector3d about, Vector3d axis, double angle) {
        t1.set(p).sub(about);
        double c = Math.cos(angle), s = Math.sin(angle);
        double d = t1.dot(axis);
        t2.set(axis).cross(t1);
        t1.mul(c).fma(s, t2).fma(d * (1 - c), axis);
        p.set(about).add(t1);
    }

    private static double clamp(double v, double max) { return Math.max(-max, Math.min(max, v)); }

    /** 1 in the middle of [a, b], easing to 0 at its ends. */
    private static double bump(double s, double a, double b) {
        if (s <= a || s >= b) return 0;
        double t = (s - a) / (b - a);
        return Math.sin(Math.PI * t);
    }

    private int lightAt(double arc) {
        int i = 0;
        while (i < n - 2 && arc > spec.arc[i + 1]) i++;
        double t = (arc - spec.arc[i]) / (spec.arc[i + 1] - spec.arc[i]);
        t = Math.max(0, Math.min(1, t));
        return mixLight(jointLight[i], jointLight[i + 1], t);
    }

    static int mixLight(int a, int b, double t) {
        int ab = a & 0xFFFF, as = (a >>> 16) & 0xFFFF, bb = b & 0xFFFF, bs = (b >>> 16) & 0xFFFF;
        int block = (int) Math.round(ab + (bb - ab) * t), sky = (int) Math.round(as + (bs - as) * t);
        return block | (sky << 16);
    }

    // ------------------------------------------------------------------------------------------------ bones

    private void bones(Inputs in) {
        for (int i = 0; i < rig.bones.length; i++) {
            MossbackRig.Bone b = rig.bones[i];
            if (b.leg >= 0) continue;
            double rx = b.rx, ry = b.ry, rz = b.rz, sx = 1, sy = 1, sz = 1;
            switch (b.name) {
                case "head" -> { rx += headPitch; ry += headYaw; rz += headRoll; }
                case "jaw" -> rx += in.jaw * 0.85 + 0.12 * in.windup;
                case "throat" -> {
                    double breath = Math.sin(in.age * 0.09);
                    sy = 1.0 + 0.06 * breath + in.frill * 0.9 + in.rear * 0.4;
                    sx = 1.0 + in.frill * 0.3;
                    sz = 1.0 + in.frill * 0.2;
                }
                case "frill_left", "frill_right" -> {
                    double side = b.name.endsWith("left") ? 1 : -1;
                    double tr = in.windup * in.windup * 0.12 * Math.sin(in.age * 7.0);
                    ry += side * in.frill;
                    rz -= side * (in.frill * 0.25 + tr);
                }
                default -> {
                    if (b.name.startsWith("fern")) {
                        // a fern nods a little on its own
                        double k = b.name.hashCode() * 0.37;
                        rx += 0.05 * Math.sin(in.age * 0.06 + k);
                        rz += 0.04 * Math.sin(in.age * 0.045 + k * 1.7);
                    }
                }
            }
            Matrix3d own = m0.rotationZYX(rz, ry, rx);
            if (sx != 1 || sy != 1 || sz != 1) own.scale(sx, sy, sz);
            if (b.parent >= 0) {
                // from its parent bone: the parent's map, through its pivot and its own turn
                Matrix3d pm = bLin[b.parent];
                pm.transform(t0.set(b.pivot)).add(bT[b.parent]);
                bT[i].set(t0);
                bLin[i].set(pm).mul(own);
                bLight[i] = bLight[b.parent];
            } else {
                anchor(b.station, m1);                            // the model at rest -> the world now, about the station
                int s = b.station;
                t0.set(b.pivot).sub(rig.restP[s]);
                m1.transform(t0);
                bT[i].set(sP[s]).add(t0);
                bLin[i].set(m1).mul(b.parentTurn).mul(own);
                bLight[i] = sLight[s];
            }
        }
    }

    /** The turn (and the shrink from pixels to blocks) from the model's frame at rest to the world's now, at a station. */
    private void anchor(int s, Matrix3d out) {
        // world columns (left, down, back) times the rest frame's rows, over 16
        Vector3d l = sL[s], d = sD[s], b = sB[s], rl = rig.restL[s], rd = rig.restD[s], rb = rig.restB[s];
        m2.set(l.x, l.y, l.z, d.x, d.y, d.z, b.x, b.y, b.z);          // columns
        m3.set(rl.x, rl.y, rl.z, rd.x, rd.y, rd.z, rb.x, rb.y, rb.z).transpose();
        out.set(m2).mul(m3).scale(1.0 / 16.0);
    }

    // ------------------------------------------------------------------------------------------------ legs

    private void legs(MossbackBody body, double pt, Inputs in, double dying, Light light, int fallback) {
        for (int k = 0; k < 4; k++) {
            MossbackSpec.Leg leg = spec.legs[k];
            MossbackBody.Foot foot = body.feet[k];
            int s = rig.legStation[k];
            // its shoulder rides the spine
            anchor(s, m1);
            Vector3d sh = t0.set(rig.legPivot[k]).sub(rig.restP[s]);
            m1.transform(sh).add(sP[s]);
            Vector3d left = sL[s], up = t4.set(sD[s]).negate(), fwd = t3.set(sB[s]).negate();
            // its foot, and the ankle over it
            foot.sole(pt, at, nrm);
            boolean onRock = foot.planted || foot.swinging();
            if (!onRock) nrm.set(up);
            Vector3d ankle = t2.set(at).fma(leg.sole, nrm);
            if (dying > 0) {
                // dying, the legs go limp: out from the rolled-over body, up in the air
                double lx = sh.x + left.x * leg.side * leg.upper * 0.9 - up.x * (leg.lower * 0.6);
                double ly = sh.y + left.y * leg.side * leg.upper * 0.9 - up.y * (leg.lower * 0.6);
                double lz = sh.z + left.z * leg.side * leg.upper * 0.9 - up.z * (leg.lower * 0.6);
                ankle.set(ankle.x + (lx - ankle.x) * dying, ankle.y + (ly - ankle.y) * dying, ankle.z + (lz - ankle.z) * dying);
                nrm.lerp(up, dying).normalize();
            }
            pole.set(left).mul(leg.side).fma(0.9, up).fma(leg.front ? -0.25 : 0.25, fwd);
            TwoBoneIk.solve(sh, ankle, pole, leg.upper, leg.lower, elbow, end);
            reachMiss[k] = end.distance(ankle);

            int[] bones = rig.legBones[k];
            int footLight = light == null ? fallback : light.at(at.x + nrm.x * 0.15, at.y + nrm.y * 0.15, at.z + nrm.z * 0.15);
            int shoulderLight = sLight[s];
            // upper: along the bone its own x (outward for a left leg, so -x for a right one), its top up
            Vector3d bx = t1.set(elbow).sub(sh).normalize().mul(leg.side);
            frame(bones[0], bx, up, sh);
            bLight[bones[0]] = shoulderLight;
            // lower: down the bone its own y, its x toward the body's left
            Vector3d by = t1.set(end).sub(elbow).normalize();
            lowerFrame(bones[1], by, left, fwd, elbow);
            bLight[bones[1]] = mixLight(shoulderLight, footLight, 0.6);
            // the foot: flat on its rock, toes ahead and turned out, drooping as it swings
            footFrame(bones[2], leg, foot, pt, nrm, fwd, up, end);
            bLight[bones[2]] = footLight;
        }
    }

    /** A bone whose x is {@code bx} (unit) and whose top faces {@code up} as near as it can. */
    private void frame(int bone, Vector3d bx, Vector3d up, Vector3d origin) {
        // y (down) square to x, against up
        double d = up.dot(bx);
        double yx = -(up.x - bx.x * d), yy = -(up.y - bx.y * d), yz = -(up.z - bx.z * d);
        double l = Math.sqrt(yx * yx + yy * yy + yz * yz);
        if (l < 1.0E-6) { yx = 0; yy = -1; yz = 0; l = 1; }
        yx /= l; yy /= l; yz /= l;
        double zx = bx.y * yz - bx.z * yy, zy = bx.z * yx - bx.x * yz, zz = bx.x * yy - bx.y * yx;
        set(bone, bx.x, bx.y, bx.z, yx, yy, yz, zx, zy, zz, origin);
    }

    private void lowerFrame(int bone, Vector3d by, Vector3d left, Vector3d fwd, Vector3d origin) {
        double d = left.dot(by);
        double xx = left.x - by.x * d, xy = left.y - by.y * d, xz = left.z - by.z * d;
        double l = Math.sqrt(xx * xx + xy * xy + xz * xz);
        if (l < 1.0E-6) {
            // the leg lies along the body's left: take its x from the way ahead instead
            xx = fwd.y * by.z - fwd.z * by.y; xy = fwd.z * by.x - fwd.x * by.z; xz = fwd.x * by.y - fwd.y * by.x;
            l = Math.sqrt(xx * xx + xy * xy + xz * xz);
        }
        xx /= l; xy /= l; xz /= l;
        double zx = xy * by.z - xz * by.y, zy = xz * by.x - xx * by.z, zz = xx * by.y - xy * by.x;
        set(bone, xx, xy, xz, by.x, by.y, by.z, zx, zy, zz, origin);
    }

    private void footFrame(int bone, MossbackSpec.Leg leg, MossbackBody.Foot foot, double pt, Vector3d n, Vector3d fwd, Vector3d up,
                           Vector3d origin) {
        // ahead along its rock, turned out
        double d = fwd.dot(n);
        double fx = fwd.x - n.x * d, fy = fwd.y - n.y * d, fz = fwd.z - n.z * d;
        double l = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (l < 1.0E-6) {
            // its rock faces the way it heads (a wall straight ahead): toes up the wall
            fx = up.x; fy = up.y; fz = up.z;
            double e = fx * n.x + fy * n.y + fz * n.z;
            fx -= n.x * e; fy -= n.y * e; fz -= n.z * e;
            l = Math.max(1.0E-6, Math.sqrt(fx * fx + fy * fy + fz * fz));
        }
        fx /= l; fy /= l; fz /= l;
        double a = leg.side * (leg.front ? SPLAY_FRONT : SPLAY_HIND);
        double ca = Math.cos(a), sa = Math.sin(a);
        // turned about n: f cos a + (n x f) sin a
        double cx = n.y * fz - n.z * fy, cy = n.z * fx - n.x * fz, cz = n.x * fy - n.y * fx;
        double hx = fx * ca + cx * sa, hy = fy * ca + cy * sa, hz = fz * ca + cz * sa;
        // its own axes: y into the rock, z back along the foot, x = y cross z
        double yx = -n.x, yy = -n.y, yz = -n.z;
        double zx = -hx, zy = -hy, zz = -hz;
        double xx = yy * zz - yz * zy, xy = yz * zx - yx * zz, xz = yx * zy - yy * zx;
        // swinging, the toes droop (turned about its x)
        double droop = foot.swinging() ? TOE_DROOP * Math.sin(Math.PI * foot.swingT(pt)) : 0.0;
        if (!foot.planted && !foot.swinging()) droop = TOE_DROOP * 0.6;
        if (droop != 0) {
            double c = Math.cos(droop), s = Math.sin(droop);
            // y -> y c + (x cross y) s = y c + z s; z -> z c - y s
            double ny0 = yx * c + zx * s, ny1 = yy * c + zy * s, ny2 = yz * c + zz * s;
            double nz0 = zx * c - yx * s, nz1 = zy * c - yy * s, nz2 = zz * c - yz * s;
            yx = ny0; yy = ny1; yz = ny2; zx = nz0; zy = nz1; zz = nz2;
        }
        set(bone, xx, xy, xz, yx, yy, yz, zx, zy, zz, origin);
    }

    private void set(int bone, double xx, double xy, double xz, double yx, double yy, double yz, double zx, double zy, double zz, Vector3d origin) {
        double k = 1.0 / 16.0;
        bLin[bone].set(xx * k, xy * k, xz * k, yx * k, yy * k, yz * k, zx * k, zy * k, zz * k);
        bT[bone].set(origin);
    }
}
