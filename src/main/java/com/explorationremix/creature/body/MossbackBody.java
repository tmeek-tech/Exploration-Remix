package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * The Mossback's body as it lies on the rock, worked out a tick at a time on the client (the game itself moves only its
 * box). Its spine is a chain of joints from the head's hinge to the tip of its tail, and every part of it does what a
 * heavy lizard's would:
 *
 * <ul>
 *     <li>The middle of its body stays with its box, held its own height out from the rock under it (eased, so a step
 *     up the box takes in one tick is climbed, not jumped).</li>
 *     <li>The front reaches ahead the way it heads, each joint laid its own height out from the rock it is over: up a
 *     wall it meets, over the edge of a ledge, onto a step. The head leads its turns and the neck and chest follow.</li>
 *     <li>The hips are dragged after the middle along the way it came, so the body curves through its turns and bends
 *     round an edge for as long as it takes to cross it (the front up a wall while the hips are still on the floor).</li>
 *     <li>The tail hangs with its weight (Taran's pick): it drags behind, drapes over ledges, lies against a wall it
 *     climbs, and droops from a ceiling, swinging a little with its own momentum.</li>
 *     <li>Its four feet stand on the actual rock: each plants where its leg reaches the block surface under it (a floor,
 *     a slab, a step, a wall it presses against or the face below an edge), stays there while the body moves on, and
 *     steps when the gait says, landing ahead on whatever rock it finds there. It walks one foot at a time while it
 *     roams and trots in diagonal pairs when it runs (Taran's pick), and settles its feet when it stops.</li>
 * </ul>
 *
 * <p>No joint ever bends past its limit, so it never folds into a hairpin. Rock is read through a {@link TerrainPatch}
 * of the blocks round it, built each tick. Plain numbers and JOML, no game classes, so it can be tested and drawn on
 * its own; the game drives it from MossbackEntity and draws it through MossbackPose.
 */
public final class MossbackBody {
    // ------------------------------------------------------------------------------------------------ tuning
    /** Rock further than this from a joint is not felt. */
    static final double REACH = 1.5;
    /** Surfaces meeting in a hollow are blended over about this far (blocks): the fillet a joint rides round. */
    static final double SOFT = 0.1;
    /** A joint further out than its height plus HOLD is let go of over LET_GO more (it bridges hollows it cannot reach). */
    static final double HOLD = 0.25, LET_GO = 0.6;
    /** The most each joint bends from straight on (radians): the neck, shoulders, middle, hips, tail base, tail. */
    static final double BEND_NECK = Math.toRadians(40), BEND_SHOULDERS = Math.toRadians(32), BEND_MIDDLE = Math.toRadians(42),
            BEND_HIPS = Math.toRadians(32), BEND_TAIL = Math.toRadians(34);
    /** How much the neck and the head steer by the way the head leads rather than the body behind them. */
    static final double NECK_LEAD = 0.45, HEAD_LEAD = 0.65;
    /** The middle's height eases back to the rock by this share a tick after the box pops up or down. */
    static final double SETTLE_MIDDLE = 0.5;
    /** The tail: momentum kept a tick, its weight (blocks per tick per tick), stiffness at its base and tip. */
    static final double TAIL_DAMP = 0.55, TAIL_WEIGHT = 0.045, TAIL_STIFF_BASE = 0.45, TAIL_STIFF_TIP = 0.08;
    /** The tail lies on rock it touches: pulled the last bit onto it when within this of its underside. */
    static final double TAIL_CLING = 0.3, TAIL_CLING_PULL = 0.35;
    /**
     * Lying on rock it grips it: this share of its weight's pull along the rock is held (so it stays laid up a wall it
     * came down). Off the rock, a part of it rising from the part before loses this share of its stiffness for each
     * unit it rises (so past the top of a step it flops over onto it, never standing up in the air).
     */
    static final double TAIL_GRIP = 0.85, TAIL_LIFT_GIVE = 0.8;
    /** A joint's way out eases to the rock's by this share a tick. */
    static final double UP_EASE = 0.45;
    /**
     * How far the trunk's way out may roll from its box's about the spine: passing a block it leans a little toward or
     * away from it, never onto its side. (Climbing onto a wall or over an edge, where the spine runs steeply against its
     * box's way out, the rock alone decides.)
     */
    static final double MAX_ROLL = Math.toRadians(20);
    /** A box that moved further than this in a tick was put somewhere new: the body is laid afresh. */
    static final double JUMP = 3.0;

    // gait
    /** Walking (one foot at a time) or trotting (diagonal pairs): the share of a stride each foot is down. */
    static final double WALK_DUTY = 0.72, TROT_DUTY = 0.5;
    /** Speeds (blocks a tick) to break into a trot and to fall back to a walk. */
    static final double TROT_FROM = 0.155, WALK_FROM = 0.135;
    /** When in its stride each foot lifts (a share of the stride): walking hind left, front left, hind right, front right. */
    static final double[] WALK_OFFSET = {0.25, 0.75, 0.0, 0.5}, TROT_OFFSET = {0.375, 0.875, 0.875, 0.375};
    /** How high a foot lifts walking and trotting (blocks), and the fewest and most ticks a step takes. */
    static final double WALK_LIFT = 0.16, TROT_LIFT = 0.22;
    static final int STEP_MIN = 3, STEP_MAX = 9;
    /** Standing still, a foot further than this from where it would rest steps there. */
    static final double SETTLE_FOOT = 0.13;
    /** A planted foot whose leg is stretched past this share of its reach steps at once. */
    static final double OVERREACH = 1.02;
    /** A step is aimed ahead of the foot's resting place for at most this speed (blocks a tick). */
    static final double MAX_AIM_SPEED = 0.35;
    /** A foot lands at least this far out to its own side of the spine, on rock that does not face down at it. */
    static final double FOOT_SIDE = 0.14, FOOT_FACING = -0.25;
    /** Where a foot looks for rock when there is none where it would stand: this far ahead of it or behind (blocks). */
    static final double[] NEAR_BY = {0.3, -0.3, 0.55, -0.55};
    /** How far a landing foot reaches for rock it finds missing where it comes down (blocks). */
    static final double LAND_SEEK = 0.5;
    /** The hips and tail root ease toward straight behind the chest by this share a tick (standing, and moving). */
    static final double RELAX_STAND = 0.07, RELAX_MOVE = 0.012;

    public enum Mode { GROUND, AIR, WATER, DEAD }

    /** What moves the body this tick: from the entity. */
    public static final class Drive {
        /** The middle of its box, and half its width and height. */
        public double x, y, z, halfWidth = 0.7, halfHeight = 0.45;
        /** The way out of its rock and the way it heads, both eased (the entity's own). */
        public final Vector3d up = new Vector3d(0, 1, 0), forward = new Vector3d(0, 0, 1);
        /** The way its head leads: the way it heads now, not eased. */
        public final Vector3d lead = new Vector3d(0, 0, 1);
        public Mode mode = Mode.GROUND;
        /** How far it rears (0 to 1): its front lifts and its front feet let go. */
        public double rear;
        /** Breaks into a trot at any speed (it hunts). */
        public boolean hurry;
    }

    /** Hears each foot as it lands. */
    public interface Footfalls {
        void land(Foot foot, boolean trotting, double speed);
    }

    // ------------------------------------------------------------------------------------------------ shape
    public final MossbackSpec spec;
    public final int joints;

    // ------------------------------------------------------------------------------------------------ state
    final Vector3d[] pos, prev, old, up, upPrev;
    /** Each tail joint: lying on rock last tick, and the way out of that rock. */
    private final boolean[] touching;
    private final Vector3d[] touchN;
    public final Foot[] feet = new Foot[4];
    private boolean laid;
    private final Vector3d lastCentre = new Vector3d();
    private final Vector3d leadEased = new Vector3d(0, 0, 1);
    private double phase, phasePrev, speed, speedPrev, mix, mixPrev;
    private boolean trotting;
    private int standing;
    private Mode mode = Mode.GROUND, modePrev = Mode.GROUND;
    private double rear, rearPrev;
    private long ticks;
    private Footfalls footfalls;

    // scratch
    private final double[] f = new double[5];
    private final Vector3d t0 = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), t3 = new Vector3d(), t4 = new Vector3d(), n0 = new Vector3d(),
            n1 = new Vector3d();
    private final Frame front = new Frame(), hind = new Frame();

    public MossbackBody(MossbackSpec spec) {
        this.spec = spec;
        this.joints = spec.arc.length;
        pos = vectors(joints); prev = vectors(joints); old = vectors(joints); up = vectors(joints); upPrev = vectors(joints);
        touching = new boolean[joints];
        touchN = vectors(joints);
        for (int i = 0; i < joints; i++) { up[i].set(0, 1, 0); upPrev[i].set(0, 1, 0); }
        for (int i = 0; i < 4; i++) feet[i] = new Foot(spec.legs[i]);
    }

    private static Vector3d[] vectors(int n) {
        Vector3d[] v = new Vector3d[n];
        for (int i = 0; i < n; i++) v[i] = new Vector3d();
        return v;
    }

    public void setFootfalls(Footfalls f) { this.footfalls = f; }
    public boolean laid() { return laid; }
    public Mode mode() { return mode; }
    public boolean trotting() { return trotting; }
    public long ticks() { return ticks; }

    /** A joint's place and way out, part way from last tick to this one. */
    public void joint(int i, double pt, Vector3d p, Vector3d u) {
        p.set(prev[i]).lerp(pos[i], pt);
        u.set(upPrev[i]).lerp(up[i], pt);
        if (u.lengthSquared() < 1.0E-8) u.set(up[i]);
        u.normalize();
    }

    public Vector3d jointNow(int i) { return pos[i]; }

    /** The stride's phase (counts up a stride at a time) part way through the tick. */
    public double phase(double pt) { return phasePrev + (phase - phasePrev) * pt; }
    /** Its speed (blocks a tick), eased. */
    public double speed(double pt) { return speedPrev + (speed - speedPrev) * pt; }
    /** 0 walking, 1 trotting (eased between). */
    public double trot(double pt) { return mixPrev + (mix - mixPrev) * pt; }
    /** When in the stride a foot lands (a share of a stride), between the walk's timing and the trot's. */
    public double offset(int foot, double pt) { return lerpPhase(WALK_OFFSET[foot], TROT_OFFSET[foot], trot(pt)); }
    public double rear(double pt) { return rearPrev + (rear - rearPrev) * pt; }

    /** The region of blocks the next tick reads (joints and feet, with room for their reach), or round the box when laying it out. */
    public void bounds(Drive in, int[] out) {
        double x0, y0, z0, x1, y1, z1;
        boolean fresh = !laid || lastCentre.distanceSquared(in.x, in.y, in.z) > JUMP * JUMP;
        if (fresh) {
            double r = spec.length() + 1.0;
            x0 = in.x - r; y0 = in.y - r; z0 = in.z - r; x1 = in.x + r; y1 = in.y + r; z1 = in.z + r;
        } else {
            x0 = y0 = z0 = Double.MAX_VALUE;
            x1 = y1 = z1 = -Double.MAX_VALUE;
            for (int i = 0; i < joints; i++) {
                Vector3d p = pos[i];
                x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); z0 = Math.min(z0, p.z);
                x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); z1 = Math.max(z1, p.z);
            }
            x0 = Math.min(x0, in.x); y0 = Math.min(y0, in.y); z0 = Math.min(z0, in.z);
            x1 = Math.max(x1, in.x); y1 = Math.max(y1, in.y); z1 = Math.max(z1, in.z);
            double m = REACH + 1.0;
            x0 -= m; y0 -= m; z0 -= m; x1 += m; y1 += m; z1 += m;
        }
        out[0] = (int) Math.floor(x0); out[1] = (int) Math.floor(y0); out[2] = (int) Math.floor(z0);
        out[3] = (int) Math.floor(x1); out[4] = (int) Math.floor(y1); out[5] = (int) Math.floor(z1);
    }

    // ------------------------------------------------------------------------------------------------ the tick

    public void tick(TerrainPatch rock, Drive in) {
        ticks++;
        for (int i = 0; i < joints; i++) { prev[i].set(pos[i]); upPrev[i].set(up[i]); }
        for (Foot ft : feet) {
            ft.prevAt.set(ft.at); ft.prevNrm.set(ft.nrm);
            ft.tPrev = ft.t; ft.toPrev.set(ft.to); ft.toNPrev.set(ft.toN); ft.swung = false;
        }
        phasePrev = phase; speedPrev = speed; mixPrev = mix; rearPrev = rear; modePrev = mode;
        mode = in.mode;
        rear += (in.rear - rear) * 0.5;
        if (!laid || lastCentre.distanceSquared(in.x, in.y, in.z) > JUMP * JUMP) {
            lay(rock, in);
            return;
        }
        if (mode == Mode.DEAD) {
            // dying, it is carried as it lies wherever its box goes (falling, knocked back, sliding)
            double dx = in.x - lastCentre.x, dy = in.y - lastCentre.y, dz = in.z - lastCentre.z;
            for (int i = 0; i < joints; i++) { pos[i].add(dx, dy, dz); old[i].add(dx, dy, dz); }
            for (Foot ft : feet) ft.at.add(dx, dy, dz);
            lastCentre.set(in.x, in.y, in.z);
            return;
        }
        lastCentre.set(in.x, in.y, in.z);
        leadEased.lerp(in.lead, 0.6);
        if (leadEased.lengthSquared() < 1.0E-6) leadEased.set(in.lead);
        leadEased.normalize();
        boolean ground = mode == Mode.GROUND;

        middle(rock, in, ground);
        frontJoints(rock, in, ground);
        rearJoints(rock, ground);
        tail(rock);
        ups(rock, in);
        feet(rock, in);
    }

    /** The middle of its body: with its box, its height out from the rock under it, eased across the rock. */
    private void middle(TerrainPatch rock, Drive in, boolean ground) {
        int c = spec.middle;
        Vector3d target = t0.set(in.x, in.y, in.z);
        Vector3d n = n0.set(in.up);
        if (!(ground && project(rock, target, spec.ride[c], n))) {
            double ext = Math.abs(in.up.x) * in.halfWidth + Math.abs(in.up.y) * in.halfHeight + Math.abs(in.up.z) * in.halfWidth;
            target.set(in.x, in.y, in.z).fma(spec.ride[c] - ext, in.up);
            n.set(in.up);
        }
        double lag = t1.set(pos[c]).sub(target).dot(n) * SETTLE_MIDDLE;
        lag = Math.max(-0.6, Math.min(0.6, lag));
        pos[c].set(target).fma(lag, n);
    }

    /** Shoulders, neck and head reach ahead the way it heads, each laid on the rock it is over. */
    private void frontJoints(TerrainPatch rock, Drive in, boolean ground) {
        int c = spec.middle, s = spec.shoulders, nk = spec.neck, h = spec.head;
        double lift = rear;
        // shoulders: straight ahead of the middle along the way it heads, across its rock
        Vector3d dir = along(t1.set(in.forward), up[c]);
        if (dir.lengthSquared() < 1.0E-6) dir.set(pos[s]).sub(pos[c]);
        dir.normalize();
        reach(rock, pos[c], pos[s], dir, spec.length(c, s), spec.ride[s], 0.12 * lift, ground, up[s]);
        // neck: a share of the way the head leads
        steer(pos[c], pos[s], NECK_LEAD, up[s], t1);
        reach(rock, pos[s], pos[nk], t1, spec.length(s, nk), spec.ride[nk], 0.26 * lift, ground, up[nk]);
        limitBend(pos[c], pos[s], pos[nk], spec.length(s, nk), BEND_SHOULDERS);
        // head
        steer(pos[s], pos[nk], HEAD_LEAD, up[nk], t1);
        reach(rock, pos[nk], pos[h], t1, spec.length(nk, h), spec.ride[h], 0.38 * lift, ground, up[h]);
        limitBend(pos[s], pos[nk], pos[h], spec.length(nk, h), BEND_NECK);
    }

    /** The way on from b: straight on from a to b, turned by {@code share} toward the way the head leads (across the rock). */
    private void steer(Vector3d a, Vector3d b, double share, Vector3d rockUp, Vector3d out) {
        Vector3d on = t2.set(b).sub(a);
        if (on.lengthSquared() < 1.0E-9) on.set(leadEased);
        on.normalize();
        Vector3d lead = along(t3.set(leadEased), rockUp);
        if (lead.lengthSquared() < 1.0E-6) lead.set(on);
        else lead.normalize();
        out.set(on).mul(1.0 - share).fma(share, lead);
        if (out.lengthSquared() < 1.0E-6) out.set(on);
        out.normalize();
    }

    /**
     * Sets {@code p} {@code len} from {@code from} along {@code dir}: on the ground laid {@code height} plus
     * {@code lift} out from the rock; off it (leaping, falling) raised by {@code lift} along its way out {@code up}, so a
     * reared head stays up as it leaves the rock.
     */
    private void reach(TerrainPatch rock, Vector3d from, Vector3d p, Vector3d dir, double len, double height, double lift, boolean ground,
                       Vector3d up) {
        p.set(from).fma(len, dir);
        if (ground) adhere(rock, p, height + lift, n1);
        else if (lift > 1.0E-4) p.fma(lift, up);
        else return;
        p.sub(from);
        if (p.lengthSquared() < 1.0E-9) p.set(dir);
        p.normalize().mul(len).add(from);
    }

    /** Hips and tail base, dragged after the middle the way they came, laid on the rock. */
    private void rearJoints(TerrainPatch rock, boolean ground) {
        int s = spec.shoulders, c = spec.middle, hp = spec.hips, tb = spec.tailBase;
        double relax = standing > 4 ? RELAX_STAND : RELAX_MOVE;
        drag(rock, pos[s], pos[c], pos[hp], spec.length(c, hp), spec.ride[hp], ground, BEND_MIDDLE, relax);
        drag(rock, pos[c], pos[hp], pos[tb], spec.length(hp, tb), spec.ride[tb], ground, BEND_HIPS, relax);
        old[hp].set(pos[hp]);
        old[tb].set(pos[tb]);
    }

    private void drag(TerrainPatch rock, Vector3d a, Vector3d b, Vector3d p, double len, double height, boolean ground, double bend, double relax) {
        Vector3d dir = t1.set(p).sub(b);
        if (dir.lengthSquared() < 1.0E-9) dir.set(b).sub(a);
        dir.normalize();
        // the spine's own spring: a little way back toward straight on
        Vector3d on = t2.set(b).sub(a);
        if (relax > 0 && on.lengthSquared() > 1.0E-9) {
            dir.lerp(on.normalize(), relax);
            if (dir.lengthSquared() < 1.0E-9) dir.set(on);
            dir.normalize();
        }
        p.set(b).fma(len, dir);
        if (ground) {
            adhere(rock, p, height, n1);
            p.sub(b);
            if (p.lengthSquared() < 1.0E-9) p.set(dir);
            p.normalize().mul(len).add(b);
        }
        limitBend(a, b, p, len, bend);
    }

    /** The tail: dragged, with its own weight and momentum, resting on and lying against the rock it touches. */
    private void tail(TerrainPatch rock) {
        int first = spec.tailBase + 1;
        double buoyant = mode == Mode.WATER ? 0.0 : 1.0;
        for (int i = first; i < joints; i++) {
            Vector3d p = pos[i], q = pos[i - 1], qq = pos[i - 2];
            double len = spec.length(i - 1, i);
            double k = (double) (i - first) / Math.max(1, joints - 1 - first);
            double r = spec.radius[i];
            // momentum and weight (lying on rock, it grips: the pull along the rock is mostly held)
            double vx = (p.x - old[i].x) * TAIL_DAMP, vy = (p.y - old[i].y) * TAIL_DAMP, vz = (p.z - old[i].z) * TAIL_DAMP;
            old[i].set(p);
            t3.set(p);                                          // where it was
            double gx = 0, gy = -TAIL_WEIGHT * buoyant * (0.6 + 0.4 * k), gz = 0;
            boolean held = touching[i] && mode == Mode.GROUND;
            if (held) {
                // its weight along the rock is held (what pulls it off the rock, as under a ceiling, acts in full)
                Vector3d tn = touchN[i];
                double gn = gy * tn.y;
                if (gn <= 0) {
                    double tx = -tn.x * gn, ty = gy - tn.y * gn, tz = -tn.z * gn;
                    gx -= tx * TAIL_GRIP; gy -= ty * TAIL_GRIP; gz -= tz * TAIL_GRIP;
                }
            }
            p.add(vx + gx, vy + gy, vz + gz);
            // stiffness: a pull toward straight on from the segment before (off the rock and rising, less: it flops over)
            double stiff = TAIL_STIFF_BASE + (TAIL_STIFF_TIP - TAIL_STIFF_BASE) * k;
            Vector3d straight = t1.set(q).sub(qq).normalize();
            if (!held && buoyant > 0 && straight.y > 0) stiff *= 1.0 - TAIL_LIFT_GIVE * straight.y;
            straight.mul(len).add(q);
            p.lerp(straight, stiff);
            keepLength(q, p, len);
            limitBend(qq, q, p, len, BEND_TAIL);
            // it never passes through rock on its way: it stops against it and slides along it
            if (!rock.solid(t3.x, t3.y, t3.z) && rock.clip(t3.x, t3.y, t3.z, p.x, p.y, p.z, f) && f[4] < 0.5) {
                double hx = t3.x + (p.x - t3.x) * f[0], hy = t3.y + (p.y - t3.y) * f[0], hz = t3.z + (p.z - t3.z) * f[0];
                double rx = p.x - hx, ry = p.y - hy, rz = p.z - hz;
                double into = rx * f[1] + ry * f[2] + rz * f[3];
                p.set(hx + rx - f[1] * into + f[1] * 0.02, hy + ry - f[2] * into + f[2] * 0.02, hz + rz - f[3] * into + f[3] * 0.02);
                keepLength(q, p, len);
            }
            // on the rock: never into it, and lying on it when it touches
            lie(rock, i, q, qq, p, len, r);
            // nor does the segment from the joint before run through rock: it swings out past it
            if (!rock.solid(q.x, q.y, q.z) && rock.clip(q.x, q.y, q.z, p.x, p.y, p.z, f) && f[4] < 0.5) {
                double hx = q.x + (p.x - q.x) * f[0] + f[1] * r, hy = q.y + (p.y - q.y) * f[0] + f[2] * r, hz = q.z + (p.z - q.z) * f[0] + f[3] * r;
                p.set(hx, hy, hz);
                keepLength(q, p, len);
                lie(rock, i, q, qq, p, len, r);
            }
        }
    }

    /**
     * A tail joint against the rock: at least its thickness {@code r} out from it, and drawn the last bit onto it when
     * it touches (on the ground). It keeps its length from the joint before by swinging round that joint across the
     * rock's face (sliding along the floor, say), never by sinking back into it. Notes whether it touches, and the way
     * out of what it touches, for next tick's grip.
     */
    private void lie(TerrainPatch rock, int i, Vector3d q, Vector3d qq, Vector3d p, double len, double r) {
        touching[i] = false;
        if (!rock.field(p.x, p.y, p.z, REACH, SOFT, f)) return;
        double d = f[0];
        if (d < r + TAIL_CLING) {
            touching[i] = true;
            touchN[i].set(f[1], f[2], f[3]);
        }
        double want = d;
        if (d < r) want = r;
        else if (d < r + TAIL_CLING && mode == Mode.GROUND) want = d - (d - r) * TAIL_CLING_PULL;
        if (want == d) return;
        double nx = f[1], ny = f[2], nz = f[3];
        // how high the joint before lies over the height this one should lie at
        double h = (q.x - p.x) * nx + (q.y - p.y) * ny + (q.z - p.z) * nz + d - want;
        if (Math.abs(h) < len) {
            // across the rock's face from the joint before, the way it lies from it
            double ex = p.x - q.x, ey = p.y - q.y, ez = p.z - q.z;
            double en = ex * nx + ey * ny + ez * nz;
            ex -= nx * en; ey -= ny * en; ez -= nz * en;
            double el = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (el < 1.0E-6) {
                ex = q.x - qq.x; ey = q.y - qq.y; ez = q.z - qq.z;
                en = ex * nx + ey * ny + ez * nz;
                ex -= nx * en; ey -= ny * en; ez -= nz * en;
                el = Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (el < 1.0E-6) { p.add(nx * (want - d), ny * (want - d), nz * (want - d)); return; }
            }
            double across = Math.sqrt(len * len - h * h) / el;
            p.set(q.x - nx * h + ex * across, q.y - ny * h + ey * across, q.z - nz * h + ez * across);
        } else {
            p.add(nx * (want - d), ny * (want - d), nz * (want - d));
            keepLength(q, p, len);
        }
    }

    private static void keepLength(Vector3d from, Vector3d p, double len) {
        double dx = p.x - from.x, dy = p.y - from.y, dz = p.z - from.z;
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1.0E-9) return;
        p.set(from.x + dx / l * len, from.y + dy / l * len, from.z + dz / l * len);
    }

    /** Each joint's way out eases to the rock's under it (in the air, to the box's), passed back along the tail. */
    private void ups(TerrainPatch rock, Drive in) {
        boolean ground = mode == Mode.GROUND;
        for (int i = 0; i < joints; i++) {
            Vector3d want = t1;
            boolean felt = ground && rock.field(pos[i].x, pos[i].y, pos[i].z, REACH, SOFT, f) && f[0] < spec.ride[i] + HOLD + LET_GO;
            if (felt) {
                want.set(f[1], f[2], f[3]);
                if (i <= spec.tailBase) unroll(i, want, in.up);
            }
            else if (i > spec.tailBase) want.set(up[i - 1]);
            else want.set(in.up);
            Vector3d u = up[i];
            if (u.dot(want) < -0.95) u.fma(0.3, t2.set(pos[Math.max(0, i - 1)]).sub(pos[Math.min(joints - 1, i + 1)]).normalize());
            u.lerp(want, UP_EASE);
            if (u.lengthSquared() < 1.0E-8) u.set(want);
            u.normalize();
        }
    }

    /** Turns a trunk joint's wanted way out back about its spine to within MAX_ROLL of its box's (see there). */
    private void unroll(int i, Vector3d want, Vector3d boxUp) {
        Vector3d fwd = t2.set(pos[Math.max(0, i - 1)]).sub(pos[Math.min(joints - 1, i + 1)]);
        if (fwd.lengthSquared() < 1.0E-9) return;
        fwd.normalize();
        double fb = boxUp.dot(fwd);
        if (Math.abs(fb) > 0.7) return;
        // across the spine: the box's way out (b) and the rock's (w)
        double bx = boxUp.x - fwd.x * fb, by = boxUp.y - fwd.y * fb, bz = boxUp.z - fwd.z * fb;
        double bl = Math.sqrt(bx * bx + by * by + bz * bz);
        double fw = want.dot(fwd);
        double wx = want.x - fwd.x * fw, wy = want.y - fwd.y * fw, wz = want.z - fwd.z * fw;
        double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (wl < 1.0E-6) return;
        bx /= bl; by /= bl; bz /= bl;
        double cos = (bx * wx + by * wy + bz * wz) / wl;
        double cmax = Math.cos(MAX_ROLL);
        if (cos >= cmax) return;
        // the way it rolls, across the spine and square to the box's way out
        double sx = wx / wl - bx * cos, sy = wy / wl - by * cos, sz = wz / wl - bz * cos;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0E-6) { sx = fwd.y * bz - fwd.z * by; sy = fwd.z * bx - fwd.x * bz; sz = fwd.x * by - fwd.y * bx; sl = 1.0; }
        double smax = Math.sin(MAX_ROLL) / sl;
        want.set(fwd).mul(fw).add((bx * cmax + sx * smax) * wl, (by * cmax + sy * smax) * wl, (bz * cmax + sz * smax) * wl);
    }

    // ------------------------------------------------------------------------------------------------ rock

    /**
     * Pulls p to {@code height} out from the rock along the way out of it, letting go of a joint far out (it bridges
     * the hollow) and pushing one inside the rock out; {@code n} gets the way out. False when no rock lies in reach.
     */
    private boolean adhere(TerrainPatch rock, Vector3d p, double height, Vector3d n) {
        if (!rock.field(p.x, p.y, p.z, REACH, SOFT, f)) return false;
        double d = f[0];
        double w = d <= height + HOLD ? 1.0 : Math.max(0.0, 1.0 - (d - height - HOLD) / LET_GO);
        n.set(f[1], f[2], f[3]);
        if (w <= 0.0) return true;
        double sx = p.x, sy = p.y, sz = p.z;
        project(rock, p, height, n);
        if (w < 1.0) p.set(sx + (p.x - sx) * w, sy + (p.y - sy) * w, sz + (p.z - sz) * w);
        return true;
    }

    /** Moves p onto the surface {@code height} out from the rock (two steps along the way out); false if no rock in reach. */
    private boolean project(TerrainPatch rock, Vector3d p, double height, Vector3d n) {
        boolean any = false;
        for (int it = 0; it < 3; it++) {
            if (!rock.field(p.x, p.y, p.z, REACH, SOFT, f)) break;
            any = true;
            double m = height - f[0];
            p.add(f[1] * m, f[2] * m, f[3] * m);
            n.set(f[1], f[2], f[3]);
            if (Math.abs(m) < 1.0E-3) break;
        }
        return any;
    }

    /** {@code v} with its part along {@code n} taken out (left as is, not normalised). */
    private static Vector3d along(Vector3d v, Vector3d n) {
        double d = v.dot(n);
        return v.sub(n.x * d, n.y * d, n.z * d);
    }

    /**
     * Keeps the bend at b (between the way from a to b and the way from b to p) within {@code max}: p is turned about b
     * toward straight on, keeping its distance.
     */
    static void limitBend(Vector3d a, Vector3d b, Vector3d p, double len, double max) {
        double ax = b.x - a.x, ay = b.y - a.y, az = b.z - a.z;
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        double bx = p.x - b.x, by = p.y - b.y, bz = p.z - b.z;
        double bl = Math.sqrt(bx * bx + by * by + bz * bz);
        if (al < 1.0E-9 || bl < 1.0E-9) return;
        ax /= al; ay /= al; az /= al; bx /= bl; by /= bl; bz /= bl;
        double cos = ax * bx + ay * by + az * bz;
        double cmax = Math.cos(max);
        if (cos >= cmax) return;
        double wx = bx - ax * cos, wy = by - ay * cos, wz = bz - az * cos;
        double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (wl < 1.0E-6) {
            // folded straight back: bend any way across
            if (Math.abs(ay) < 0.9) { wx = -az; wy = 0; wz = ax; } else { wx = 1; wy = 0; wz = 0; }
            wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
        }
        wx /= wl; wy /= wl; wz /= wl;
        double smax = Math.sin(max);
        p.set(b.x + (ax * cmax + wx * smax) * len, b.y + (ay * cmax + wy * smax) * len, b.z + (az * cmax + wz * smax) * len);
    }

    // ------------------------------------------------------------------------------------------------ laying out

    /** Lays the body out afresh round its box: straight along the way it heads, on the rock, its feet planted. */
    private void lay(TerrainPatch rock, Drive in) {
        laid = true;
        lastCentre.set(in.x, in.y, in.z);
        leadEased.set(in.lead);
        if (leadEased.lengthSquared() < 1.0E-6) leadEased.set(in.forward);
        leadEased.normalize();
        boolean ground = in.mode == Mode.GROUND;
        for (int i = 0; i < joints; i++) up[i].set(in.up);
        pos[spec.middle].set(in.x, in.y, in.z);
        // start the middle on its target outright
        int c = spec.middle;
        Vector3d target = t0.set(in.x, in.y, in.z);
        if (!(ground && project(rock, target, spec.ride[c], n0))) {
            double ext = Math.abs(in.up.x) * in.halfWidth + Math.abs(in.up.y) * in.halfHeight + Math.abs(in.up.z) * in.halfWidth;
            target.set(in.x, in.y, in.z).fma(spec.ride[c] - ext, in.up);
        }
        pos[c].set(target);
        frontJoints(rock, in, ground);
        // the rear laid straight back, then let settle a few times so the tail drapes
        Vector3d back = along(t3.set(in.forward), up[c]);
        if (back.lengthSquared() < 1.0E-6) back.set(in.forward);
        back.normalize().negate();
        for (int i = c + 1; i < joints; i++) {
            pos[i].set(pos[i - 1]).fma(spec.length(i - 1, i), back);
            old[i].set(pos[i]);
        }
        for (int k = 0; k < 12; k++) {
            rearJoints(rock, ground);
            tail(rock);
            for (int i = spec.tailBase + 1; i < joints; i++) old[i].set(pos[i]);
            ups(rock, in);
        }
        for (int i = 0; i < joints; i++) { prev[i].set(pos[i]); upPrev[i].set(up[i]); }
        girdles();
        for (Foot ft : feet) {
            Frame g = ft.leg.front ? front : hind;
            neutral(ft, g, ft.ideal);
            ft.idealPrev.set(ft.ideal);
            if (ground && target(rock, ft, g, ft.ideal, 0.0, t1, t2)) plant(ft, t1, t2);
            else dangle(ft, g, true);
            ft.prevAt.set(ft.at);
            ft.prevNrm.set(ft.nrm);
        }
        phasePrev = phase; speedPrev = speed = 0; mixPrev = mix; rearPrev = rear; modePrev = mode;
        standing = 0;
    }

    // ------------------------------------------------------------------------------------------------ feet

    /** A girdle's frame: where its joint is, and its forward, up and left. */
    static final class Frame {
        final Vector3d pos = new Vector3d(), fwd = new Vector3d(0, 0, 1), up = new Vector3d(0, 1, 0), left = new Vector3d(1, 0, 0);
    }

    private void girdles() {
        frame(spec.shoulders, front);
        frame(spec.hips, hind);
    }

    private void frame(int j, Frame g) {
        g.pos.set(pos[j]);
        g.fwd.set(pos[j - 1]).sub(pos[j + 1]);
        if (g.fwd.lengthSquared() < 1.0E-9) g.fwd.set(0, 0, 1);
        g.fwd.normalize();
        g.up.set(up[j]);
        along(g.up, g.fwd);
        if (g.up.lengthSquared() < 1.0E-6) g.up.set(Math.abs(g.fwd.y) < 0.9 ? 0 : 1, Math.abs(g.fwd.y) < 0.9 ? 1 : 0, 0);
        g.up.normalize();
        g.up.cross(g.fwd, g.left);
        g.left.normalize();
    }

    /** One of its four feet: front or hind, left or right; where its sole is and on what. */
    public static final class Foot {
        public final MossbackSpec.Leg leg;
        /** On rock and staying there. */
        public boolean planted;
        /** Where the sole is (on rock when planted), and the way out of that rock (its leg's up while in the air). */
        public final Vector3d at = new Vector3d(), nrm = new Vector3d(0, 1, 0);
        public final Vector3d prevAt = new Vector3d(), prevNrm = new Vector3d(0, 1, 0);
        final Vector3d from = new Vector3d(), fromN = new Vector3d(0, 1, 0), to = new Vector3d(), toN = new Vector3d(0, 1, 0);
        final Vector3d ideal = new Vector3d(), idealPrev = new Vector3d();
        /** Swinging: how far through (0 to 1) and how much a tick; whether it lands on rock; how high it lifts. */
        double t, step, lift;
        boolean swinging, toRock;
        /** Last tick's: how far through the step, and where it was headed; whether it stepped this tick. */
        double tPrev;
        final Vector3d toPrev = new Vector3d(), toNPrev = new Vector3d(0, 1, 0), liftUp = new Vector3d(0, 1, 0);
        boolean swung;
        private final Vector3d sTo = new Vector3d(), sToN = new Vector3d();

        Foot(MossbackSpec.Leg leg) { this.leg = leg; }

        /**
         * Where the sole is, and the way out of its rock, part way through the tick: along its step's own arc while it
         * steps (so the arc stays round however few ticks the step takes), otherwise between last tick's place and this one's.
         */
        public void sole(double pt, Vector3d p, Vector3d n) {
            if (swung) {
                double tt = tPrev + (t - tPrev) * pt;
                sTo.set(toPrev).lerp(to, pt);
                sToN.set(toNPrev).lerp(toN, pt);
                if (sToN.lengthSquared() < 1.0E-8) sToN.set(toN);
                sToN.normalize();
                arc(from, fromN, sTo, sToN, lift, tt, liftUp, p, n);
                return;
            }
            p.set(prevAt).lerp(at, pt);
            n.set(prevNrm).lerp(nrm, pt);
            if (n.lengthSquared() < 1.0E-8) n.set(nrm);
            n.normalize();
        }

        /** Stepping this tick (or landing from a step). */
        public boolean swinging() { return swinging || swung; }

        /** How far through its step it is (0 to 1), part way through the tick. */
        public double swingT(double pt) { return swung ? tPrev + (t - tPrev) * pt : swinging ? t : 0.0; }

        /**
         * A step's arc: eased from {@code from} to {@code to}, lifted along the two rocks' blended way out (higher over a
         * corner, from a floor to a wall, so the foot clears the edge).
         */
        static void arc(Vector3d from, Vector3d fromN, Vector3d to, Vector3d toN, double lift, double t, Vector3d up, Vector3d p, Vector3d n) {
            double e = t * t * (3.0 - 2.0 * t);
            double mx = fromN.x + toN.x, my = fromN.y + toN.y, mz = fromN.z + toN.z;
            double ml = Math.sqrt(mx * mx + my * my + mz * mz);
            if (ml < 1.0E-3) { mx = up.x; my = up.y; mz = up.z; ml = 1.0; }
            double h = Math.sin(Math.PI * t) * lift * (1.0 + Math.max(0.0, 0.6 - fromN.dot(toN)));
            p.set(from.x + (to.x - from.x) * e + mx / ml * h, from.y + (to.y - from.y) * e + my / ml * h, from.z + (to.z - from.z) * e + mz / ml * h);
            n.set(fromN).lerp(toN, e);
            if (n.lengthSquared() < 1.0E-8) n.set(up);
            n.normalize();
        }
    }

    /** Where a foot rests, standing still: out from its shoulder and down on the rock under the girdle (if the rock is flat). */
    private void neutral(Foot ft, Frame g, Vector3d out) {
        MossbackSpec.Leg l = ft.leg;
        out.set(g.pos).fma(l.side * l.stance, g.left).fma(l.fore, g.fwd).fma(-spec.ride[l.joint], g.up);
    }

    private void shoulder(Foot ft, Frame g, Vector3d out) {
        MossbackSpec.Leg l = ft.leg;
        out.set(g.pos).fma(l.side * l.lateral, g.left).fma(-l.drop, g.up);
    }

    private void feet(TerrainPatch rock, Drive in) {
        girdles();
        // how far the feet's resting places moved: the stride runs on with it (turning on the spot as well as walking)
        double moved = 0;
        for (Foot ft : feet) {
            Frame g = ft.leg.front ? front : hind;
            ft.idealPrev.set(ft.ideal);
            neutral(ft, g, ft.ideal);
            moved += ft.ideal.distance(ft.idealPrev);
        }
        moved = Math.min(0.6, moved / 4.0);
        speed += (moved - speed) * 0.3;
        boolean want = in.hurry || speed > (trotting ? WALK_FROM : TROT_FROM);
        if (want != trotting) trotting = want;
        double stride = trotting ? spec.trotStride : spec.walkStride;
        double duty = trotting ? TROT_DUTY : WALK_DUTY;
        double before = phase;
        phase += moved / stride;
        // ease the feet's timing from one gait to the other over about a stride
        double mixTarget = trotting ? 1.0 : 0.0;
        double mixStep = Math.max(0.05, (phase - before) * 1.5);
        mix += Math.max(-mixStep, Math.min(mixStep, mixTarget - mix));
        standing = moved < 0.004 ? standing + 1 : 0;

        if (mode != Mode.GROUND) {
            for (Foot ft : feet) {
                Frame g = ft.leg.front ? front : hind;
                ft.planted = false;
                ft.swinging = false;
                if (mode == Mode.WATER) paddle(ft, g);
                else dangle(ft, g, false);
            }
            return;
        }

        boolean anySwing = false;
        for (Foot ft : feet) anySwing |= ft.swinging;
        int index = 0;
        for (Foot ft : feet) {
            Frame g = ft.leg.front ? front : hind;
            double offset = lerpPhase(WALK_OFFSET[index], TROT_OFFSET[index], mix);
            index++;
            if (ft.swinging) {
                swing(rock, ft, g, stride, duty);
                continue;
            }
            boolean rearing = ft.leg.front && rear > 0.45;
            if (rearing) {
                // reared up: the front feet let go and the claws come up
                dangle(ft, g, false);
                ft.planted = false;
                continue;
            }
            if (!ft.planted) {
                // back on the rock after a fall, a lunge or a rear: down at once, the front feet a tick before the hind
                // (if there is rock in reach; else it hangs)
                if (target(rock, ft, g, ft.ideal, 0.0, t1, t2)) begin(rock, ft, g, ft.leg.front ? 3 : 4, stride, duty, 0.08);
                else dangle(ft, g, false);
                continue;
            }
            // the gait lifts it: its turn in the stride came round this tick
            double a = Math.floor(before - offset - duty), b = Math.floor(phase - offset - duty);
            boolean turn = b > a && moved > 0.004;
            boolean lost = overreached(ft, g) || !rock.solid(ft.at.x - ft.nrm.x * 0.06, ft.at.y - ft.nrm.y * 0.06, ft.at.z - ft.nrm.z * 0.06);
            if (turn || lost) {
                int ticksFor = lost && !turn ? 3 : stepTicks(stride, duty);
                begin(rock, ft, g, ticksFor, stride, duty, trotting ? TROT_LIFT : WALK_LIFT);
                anySwing = true;
            }
        }
        // standing: one foot at a time steps back to where it rests
        if (standing > 4 && !anySwing) {
            Foot worst = null;
            double most = SETTLE_FOOT;
            for (Foot ft : feet) {
                if (!ft.planted) continue;
                Frame g = ft.leg.front ? front : hind;
                if (!target(rock, ft, g, ft.ideal, 0.0, t1, t2)) continue;
                double d = t1.distance(ft.at);
                if (d > most) { most = d; worst = ft; }
            }
            if (worst != null) begin(rock, worst, worst.leg.front ? front : hind, 7, stride, duty, 0.1);
        }
    }

    private int stepTicks(double stride, double duty) {
        double s = Math.max(speed, 0.02);
        int t = (int) Math.round((1.0 - duty) * stride / s);
        return Math.max(STEP_MIN, Math.min(STEP_MAX, t));
    }

    /** Lifts a foot to step: from where it is to where the gait will want it as it lands. */
    private void begin(TerrainPatch rock, Foot ft, Frame g, int ticksFor, double stride, double duty, double lift) {
        ft.from.set(ft.at);
        ft.fromN.set(ft.nrm);
        ft.swinging = true;
        ft.planted = false;
        ft.t = 0.0;
        ft.step = 1.0 / ticksFor;
        ft.lift = lift;
        aim(rock, ft, g, ticksFor, stride, duty, true);
    }

    /** Where a step lands: ahead of where the foot rests by half the ground it covers while down, on what rock is there. */
    private void aim(TerrainPatch rock, Foot ft, Frame g, double ticksLeft, double stride, double duty, boolean fresh) {
        double vx = ft.ideal.x - ft.idealPrev.x, vy = ft.ideal.y - ft.idealPrev.y, vz = ft.ideal.z - ft.idealPrev.z;
        // along its rock only (a box popping up a step or dropping off one says nothing of where the foot should go)
        double vu = vx * g.up.x + vy * g.up.y + vz * g.up.z;
        vx -= g.up.x * vu; vy -= g.up.y * vu; vz -= g.up.z * vu;
        double v = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (v > MAX_AIM_SPEED) { vx *= MAX_AIM_SPEED / v; vy *= MAX_AIM_SPEED / v; vz *= MAX_AIM_SPEED / v; v = MAX_AIM_SPEED; }
        Vector3d aimAt = t3.set(ft.ideal);
        double ahead = 0.0;
        if (v > 0.004) {
            aimAt.add(vx * ticksLeft, vy * ticksLeft, vz * ticksLeft);
            ahead = Math.min(duty * stride * 0.5, spec.maxStep);
            aimAt.add(vx / v * ahead, vy / v * ahead, vz / v * ahead);
        }
        double lead = v * ticksLeft + ahead;
        double sx = vx * ticksLeft, sy = vy * ticksLeft, sz = vz * ticksLeft;
        boolean found = target(rock, ft, g, aimAt, lead, t1, t2);
        // nothing there: still where it was headed, if that is rock in reach; else under where it rests; else back
        // where it lifted from (a gap all round: it puts its foot down again rather than step into the air)
        if (!found && !fresh && ft.toRock && standsOn(rock, ft.to, ft.toN) && reachable(ft, g, ft.to, ft.toN, sx, sy, sz)) return;
        if (!found) found = target(rock, ft, g, ft.ideal, 0.0, t1, t2);
        if (!found && standsOn(rock, ft.from, ft.fromN) && reachable(ft, g, ft.from, ft.fromN, sx, sy, sz)) {
            t1.set(ft.from);
            t2.set(ft.fromN);
            found = true;
        }
        if (found) {
            // eased toward a new place on the same rock; another rock (a step's top for its riser, the floor for a slab) outright
            boolean same = !fresh && ft.toRock && ft.toN.dot(t2) > 0.95 && ft.to.distanceSquared(t1) < 0.25 * 0.25;
            ft.toRock = true;
            if (same) { ft.to.lerp(t1, 0.5); ft.toN.lerp(t2, 0.5).normalize(); }
            else { ft.to.set(t1); ft.toN.set(t2); }
        } else {
            ft.toRock = false;
            if (fresh) { ft.to.set(aimAt); ft.toN.set(g.up); }
            else { ft.to.lerp(aimAt, 0.5); ft.toN.lerp(g.up, 0.5).normalize(); }
        }
    }

    private void swing(TerrainPatch rock, Foot ft, Frame g, double stride, double duty) {
        ft.t = Math.min(1.0, ft.t + ft.step);
        ft.swung = true;
        double left = (1.0 - ft.t) / ft.step;
        if (ft.t < 0.85) aim(rock, ft, g, left, stride, duty, false);
        ft.liftUp.set(g.up);
        Foot.arc(ft.from, ft.fromN, ft.to, ft.toN, ft.lift, ft.t, g.up, ft.at, ft.nrm);
        if (ft.t >= 1.0) {
            ft.swinging = false;
            if (!ft.toRock || !standsOn(rock, ft.to, ft.toN)) {
                // no rock where it comes down (it has gone, or there was none as it aimed): the nearest it can stand on,
                // if any is close, reached for as it lands
                boolean found = target(rock, ft, g, ft.to, 0.0, t1, t2) || target(rock, ft, g, ft.ideal, 0.0, t1, t2);
                ft.toRock = found && t1.distanceSquared(ft.to) < LAND_SEEK * LAND_SEEK;
                if (ft.toRock) { ft.to.set(t1); ft.toN.set(t2); }
            }
            if (ft.toRock) {
                plant(ft, ft.to, ft.toN);
                if (footfalls != null) footfalls.land(ft, trotting, speed);
            } else {
                ft.planted = false;
            }
        }
    }

    private void plant(Foot ft, Vector3d p, Vector3d n) {
        ft.at.set(p);
        ft.nrm.set(n);
        ft.planted = true;
        ft.swinging = false;
    }

    /** Off the rock (falling, lunging, rearing): the leg held out in the air, front legs reaching ahead, hind legs back. */
    private void dangle(Foot ft, Frame g, boolean outright) {
        MossbackSpec.Leg l = ft.leg;
        double fore = l.front ? 0.32 + 0.1 * rear : -0.3;
        double down = l.front && rear > 0.3 ? -0.05 : 0.2;
        Vector3d want = t3.set(g.pos).fma(l.side * (l.lateral + l.upper * 0.85), g.left).fma(fore, g.fwd).fma(-(l.drop + down), g.up);
        if (outright) ft.at.set(want);
        else ft.at.lerp(want, 0.45);
        ft.nrm.set(g.up);
    }

    /** Swimming: the legs paddle in turn. */
    private void paddle(Foot ft, Frame g) {
        MossbackSpec.Leg l = ft.leg;
        double ph = (ticks * 0.11 + (l.front == (l.side > 0) ? 0.0 : 0.5)) * Math.PI * 2.0;
        Vector3d want = t3.set(g.pos).fma(l.side * (l.lateral + l.upper * 0.8), g.left)
                .fma(0.22 * Math.sin(ph) + (l.front ? 0.05 : -0.05), g.fwd).fma(-(l.drop + 0.12 + 0.08 * Math.cos(ph)), g.up);
        ft.at.lerp(want, 0.5);
        ft.nrm.set(g.up);
    }

    /** Whether a foot has rock in its reach under where it rests now (as a step that finds it missing looks for it). */
    boolean rockInReach(TerrainPatch rock, Foot ft) {
        return target(rock, ft, ft.leg.front ? front : hind, ft.ideal, 0.0, t1, t2);
    }

    /** Whether a sole at p (out of its rock along n) stands on rock. */
    private static boolean standsOn(TerrainPatch rock, Vector3d p, Vector3d n) {
        return rock.solid(p.x - n.x * 0.06, p.y - n.y * 0.06, p.z - n.z * 0.06);
    }

    /** Whether a sole at p (out of its rock along n) is within the leg's reach of its shoulder moved on by (dx, dy, dz). */
    private boolean reachable(Foot ft, Frame g, Vector3d p, Vector3d n, double dx, double dy, double dz) {
        shoulder(ft, g, t4);
        double wx = p.x + n.x * ft.leg.sole - t4.x - dx, wy = p.y + n.y * ft.leg.sole - t4.y - dy, wz = p.z + n.z * ft.leg.sole - t4.z - dz;
        double reach = (ft.leg.upper + ft.leg.lower) * 0.98;
        return wx * wx + wy * wy + wz * wz <= reach * reach;
    }

    private boolean overreached(Foot ft, Frame g) {
        shoulder(ft, g, t3);
        double wx = ft.at.x + ft.nrm.x * ft.leg.sole - t3.x, wy = ft.at.y + ft.nrm.y * ft.leg.sole - t3.y, wz = ft.at.z + ft.nrm.z * ft.leg.sole - t3.z;
        double reach = ft.leg.upper + ft.leg.lower;
        return wx * wx + wy * wy + wz * wz > reach * reach * OVERREACH * OVERREACH;
    }

    /**
     * Rock for a foot near {@code near} (a point on the rock where it would stand): out, p, and the way out of the
     * rock, n. In order: down onto the rock from just above (drawn in from rock it would sit in: against a wall it
     * stands narrow), down onto a step or slab higher up, a wall or edge in the way of the reach from the shoulder, and
     * the face under an edge (reaching down over it); then, should all of these miss (a gap under it, or short of a
     * riser), the same two downward looks a little ahead of it and behind, and last the riser of a step just ahead. Each
     * must lie within the leg's reach of the shoulder (as it will be {@code lead} further on). False when none does.
     */
    private boolean target(TerrainPatch rock, Foot ft, Frame g, Vector3d near, double lead, Vector3d p, Vector3d n) {
        MossbackSpec.Leg l = ft.leg;
        Vector3d sh = t0;
        shoulder(ft, g, sh);
        if (lead > 0) {
            double vx = near.x - ft.ideal.x, vy = near.y - ft.ideal.y, vz = near.z - ft.ideal.z;
            double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vl > 1.0E-6) sh.add(vx / vl * Math.min(lead, vl), vy / vl * Math.min(lead, vl), vz / vl * Math.min(lead, vl));
        }
        double reach = (l.upper + l.lower) * 1.02;
        Vector3d u = g.up, side = n0.set(g.left).mul(l.side);
        // 1. down from just above, drawn in toward the body out of any rock
        double px = near.x, py = near.y, pz = near.z;
        for (int k = 0; k < 6; k++) {
            if (!rock.solid(px + u.x * 0.3, py + u.y * 0.3, pz + u.z * 0.3) && !rock.solid(px + u.x * 0.05, py + u.y * 0.05, pz + u.z * 0.05)) break;
            px -= side.x * 0.08; py -= side.y * 0.08; pz -= side.z * 0.08;
        }
        if (cast(rock, px + u.x * 0.3, py + u.y * 0.3, pz + u.z * 0.3, px - u.x * 0.85, py - u.y * 0.85, pz - u.z * 0.85, sh, g, l, reach, p, n))
            return true;
        // 2. a step or slab above: down from higher up
        if (cast(rock, near.x + u.x * 1.1, near.y + u.y * 1.1, near.z + u.z * 1.1, near.x + u.x * 0.25, near.y + u.y * 0.25, near.z + u.z * 0.25,
                sh, g, l, reach, p, n)) return true;
        // 3. a wall or edge in the way, reaching out from the shoulder
        double ex = near.x + (near.x - sh.x) * 0.3, ey = near.y + (near.y - sh.y) * 0.3, ez = near.z + (near.z - sh.z) * 0.3;
        if (cast(rock, sh.x, sh.y, sh.z, ex, ey, ez, sh, g, l, reach, p, n)) return true;
        // 4. the face under an edge: in toward the body from just under where it would stand
        double bx = near.x - u.x * 0.25, by = near.y - u.y * 0.25, bz = near.z - u.z * 0.25;
        if (cast(rock, bx, by, bz, bx - side.x * 0.5, by - side.y * 0.5, bz - side.z * 0.5, sh, g, l, reach, p, n)) return true;
        // 5. a little ahead or behind: across a gap to its far side or back to its near one, up onto a step it fell short of
        Vector3d w = g.fwd;
        for (double d : NEAR_BY) {
            double qx = near.x + w.x * d, qy = near.y + w.y * d, qz = near.z + w.z * d;
            if (cast(rock, qx + u.x * 0.3, qy + u.y * 0.3, qz + u.z * 0.3, qx - u.x * 0.85, qy - u.y * 0.85, qz - u.z * 0.85, sh, g, l, reach, p, n))
                return true;
            if (cast(rock, qx + u.x * 1.1, qy + u.y * 1.1, qz + u.z * 1.1, qx + u.x * 0.25, qy + u.y * 0.25, qz + u.z * 0.25, sh, g, l, reach, p, n))
                return true;
        }
        // 6. the riser of a step ahead, too high to stand on its top
        double rx = near.x + u.x * 0.08, ry = near.y + u.y * 0.08, rz = near.z + u.z * 0.08;
        return cast(rock, rx, ry, rz, rx + w.x * 0.5, ry + w.y * 0.5, rz + w.z * 0.5, sh, g, l, reach, p, n);
    }

    private boolean cast(TerrainPatch rock, double ax, double ay, double az, double bx, double by, double bz, Vector3d sh,
                         Frame g, MossbackSpec.Leg l, double reach, Vector3d p, Vector3d n) {
        if (!rock.clip(ax, ay, az, bx, by, bz, f) || f[4] > 0.5) return false;
        double t = f[0];
        double hx = ax + (bx - ax) * t, hy = ay + (by - ay) * t, hz = az + (bz - az) * t;
        // its own side of the body, on rock it can stand on (not the underside of something over it)
        double lateral = ((hx - g.pos.x) * g.left.x + (hy - g.pos.y) * g.left.y + (hz - g.pos.z) * g.left.z) * l.side;
        if (lateral < FOOT_SIDE) return false;
        if (f[1] * g.up.x + f[2] * g.up.y + f[3] * g.up.z < FOOT_FACING) return false;
        double wx = hx + f[1] * l.sole - sh.x, wy = hy + f[2] * l.sole - sh.y, wz = hz + f[3] * l.sole - sh.z;
        if (wx * wx + wy * wy + wz * wz > reach * reach) return false;
        p.set(hx, hy, hz);
        n.set(f[1], f[2], f[3]);
        return true;
    }

    /** Between two phase offsets the short way round the stride. */
    private static double lerpPhase(double a, double b, double t) {
        double d = b - a;
        d -= Math.floor(d + 0.5);
        return a + d * t;
    }
}
