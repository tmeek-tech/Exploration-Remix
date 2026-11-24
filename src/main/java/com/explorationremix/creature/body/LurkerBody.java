package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * The vent urchin's body as it stands on the rock (0.21.3, Taran's ask: "more sea urchin-like than spider-like"; the
 * 0.21.0 ask's legs that "land on real blocks" kept). A round test ringed by sixteen walking spines, each a short base
 * from its socket to a knee close to the test and a long needle from there to its point (Taran's card: "Needles with
 * one knee": it walks like a spider on thin needles), a coat of short spines all over it, five teeth under it
 * ({@link LurkerSpec}). It holds to any rock as the spider did: a floor, the sheer walls of its vent, over its rim, its
 * underside to the rock and its crown away.
 *
 * <ul>
 *     <li>Each walking spine's point rests on a real block: where its needle, hung from its knee's place at ease and
 *     lowered round its own way out, first meets rock ({@link #contact}); one that meets an edge in its way reaches on
 *     over it to the rock beyond. Its knee bends to keep its point there as the test moves (a two-bone reach, in the
 *     pose).</li>
 *     <li>It has no front and never turns (Taran's card: "Glides any way, never turns"): its spines step wherever it
 *     goes. A spine steps when the going has brought its point in too near its socket, drawn it out toward the end of
 *     its reach or swung it round out of its own room among its neighbours, landing where it will have the most way to
 *     go (far out for a spine it is coming toward, near in for one it is leaving, ahead round the test for one it is
 *     passing); no two neighbours lift together, so a ripple runs round the ring.</li>
 *     <li>Sat in its vent's mouth (Taran's card: "Half sunk into the mouth") it sits upright, its lower half down in the
 *     flue, its spines reaching out over the rim all round.</li>
 *     <li>Its coat sways slowly; its spines swing to point at what it attends to (Taran's card: "Point at footsteps,
 *     then you").</li>
 *     <li>Leaping, it flips to bring its mouth to the front, its walking spines reaching forward round it.</li>
 * </ul>
 *
 * <p>Plain numbers and JOML, no game classes: the entity drives it ({@link Drive}) and hands it the rock
 * ({@link TerrainPatch}) each client tick, the pose ({@link LurkerPose}) draws it, and the offline clips walk it the
 * same way.
 */
public final class LurkerBody {
    public enum Mode { SURFACE, AIR, DEAD }

    /** What moves the body this tick: from the entity. */
    public static final class Drive {
        /** Where its middle should be: its box's position plus {@link LurkerSpec#ride} up. */
        public double x, y, z;
        /** The way it heads (unit; only for laying it out afresh), and the rock's way out when the game knows it (else zero). */
        public final Vector3d heading = new Vector3d(0, 0, 1), up = new Vector3d();
        /** Its speed through the air (blocks a tick), for its pose in a leap. */
        public final Vector3d velocity = new Vector3d();
        public Mode mode = Mode.SURFACE;
        /** 0 to 1: how far it crouches to leap, how far its mouth is open, how stirred it is (footsteps felt). */
        public double crouch, mouth, tense;
        /** It runs. */
        public boolean hurry;
        /** Sat in its vent's mouth, or rising out of it and gliding over its rim: held straight up whatever rock is near. */
        public boolean upright;
        /**
         * Climbing down or up the inside of its flue, held upright (0.21.4): its spines braced on the flue's walls, every
         * other one reaching down the wall under it ({@link #braced}), the rest up the wall over it; its going up and down
         * counted, so its spines step ahead of it.
         */
        public boolean climbing;
        /** What its spines point at (a place in the world) and how much (0 not at all, 1 all on it). */
        public final Vector3d focus = new Vector3d();
        public double focusAmount;
    }

    /** Hears each spine's point as it lands. */
    public interface Footfalls {
        void land(Foot foot, boolean running, double speed);
    }

    // ------------------------------------------------------------------------------------------------ tuning
    /** Further than this between ticks (blocks), it was put somewhere new: laid out afresh there. */
    static final double JUMP = 3.0;
    /** Rock further than this from the body is not felt; surfaces meeting in a hollow are blended over SOFT. */
    static final double REACH = 2.2, SOFT = 0.3;
    /**
     * A needle is lowered from this far over level (radians, up) to this far past straight down, in steps, then refined;
     * one already against rock at its highest (a wall beside it) is raised first, as far as the third, until it clears.
     */
    static final double SWEEP_FROM = Math.toRadians(-32), SWEEP_TO = Math.toRadians(118), SWEEP_STEP = Math.toRadians(6),
            SWEEP_UP = Math.toRadians(-84);
    static final int SWEEP_REFINE = 4;
    /**
     * Climbing its flue, a braced spine is swung from straight down (or up) to this far off it (radians), in steps; its
     * way to its point must be clear for this share of it.
     */
    static final double BRACE_TO = Math.toRadians(80), BRACE_STEP = Math.toRadians(4), BRACE_CLEAR = 0.9;
    /** Climbing its flue, a spine its test is coming toward lands this share of the way out to the end of its reach, one it is leaving this near in. */
    static final double BRACE_LONG = 0.96, BRACE_SHORT = 1.06;
    /** Climbing its flue: its steps this quick (ticks), as many spines up at once as running, two lifting at a tick. */
    static final int BRACE_SWING = 3;
    /** Off its flue's walls, a spine further than this from where it would rest steps there at once (blocks). */
    static final double UNBRACE = 0.6;
    /**
     * Where a step lands: a spine the test will come toward hangs its needle this much longer (its point lands far out),
     * one it will go away from this much shorter (near in).
     */
    static final double LAND_LONG = 1.08, LAND_SHORT = 0.82;
    /** How much of its room round the test a spine the test will pass lands ahead into. */
    static final double LAND_ROUND = 0.75;
    /** At most this many spines up at once, walking and running. */
    static final int AIR_WALK = 5, AIR_RUN = 7;
    /** A spine further than this from where it would rest steps back to it, standing (blocks). */
    static final double SETTLE = 0.3;
    /** It runs above this speed, and walks again under the second (blocks a tick). */
    static final double RUN_FROM = 0.16, WALK_FROM = 0.12;
    /** How fast its way out turns (a share a tick). */
    static final double UP_EASE = 0.3, AIR_UP_EASE = 0.24;

    // ------------------------------------------------------------------------------------------------ shape
    public final LurkerSpec spec;

    // ------------------------------------------------------------------------------------------------ state
    private boolean laid;
    private final Vector3d lastBox = new Vector3d();
    /** The test's middle and its frame (forward, up), now and last tick. */
    final Vector3d p = new Vector3d(), pPrev = new Vector3d(), f = new Vector3d(0, 0, 1), fPrev = new Vector3d(0, 0, 1),
            u = new Vector3d(0, 1, 0), uPrev = new Vector3d(0, 1, 0);
    /** Its own left this tick (u x f). */
    final Vector3d l = new Vector3d(1, 0, 0);
    /** Its going over the rock (blocks a tick, along the rock), eased. */
    private final Vector3d vel = new Vector3d();
    private double crouch, crouchPrev, mouth, mouthPrev, tense, focusAmount;
    private double speed, speedPrev, mix, mixPrev;
    private boolean running, upright, climbing;
    private int standing, settleNext;
    private Mode mode = Mode.SURFACE;
    private long ticks;
    private Footfalls footfalls;
    public final Foot[] feet = new Foot[LurkerSpec.LEGS];
    /** The coat's spines' ways out, in the test's own frame (forward, up, left), now and last tick. */
    final double[][] coat, coatPrev;
    /** Each coat spine's sway: two ways across it (the test's frame) and its own phases. */
    private final double[][] swayA, swayB;
    private final double[] swayPhase;
    private long seed = 0x10c4e5;

    // scratch
    private final double[] hit = new double[5], fld = new double[4];
    /** The last lowered needle ({@link #lower}): whether it met rock on its shaft over an edge, how far along, at what angle. */
    private boolean lowEdge;
    private double lowFrac, lowAngle;
    private final Vector3d t0 = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), t3 = new Vector3d(), t4 = new Vector3d(),
            t5 = new Vector3d(), t6 = new Vector3d(), hipNow = new Vector3d(), knee0 = new Vector3d(), over = new Vector3d(),
            overN = new Vector3d();

    public LurkerBody(LurkerSpec spec) {
        this.spec = spec;
        for (int i = 0; i < LurkerSpec.LEGS; i++) feet[i] = new Foot(i);
        int n = spec.coat;
        coat = new double[n][3];
        coatPrev = new double[n][3];
        swayA = new double[n][3];
        swayB = new double[n][3];
        swayPhase = new double[n];
        for (int j = 0; j < n; j++) {
            double[] d = spec.coatDir[j];
            // two ways square to its rest way: across it round the test, and along it toward the crown
            double ax = -d[2], az = d[0], al = Math.sqrt(ax * ax + az * az);
            if (al < 1.0E-6) { ax = 1; az = 0; al = 1; }
            swayA[j][0] = ax / al; swayA[j][1] = 0; swayA[j][2] = az / al;
            double[] a = swayA[j];
            swayB[j][0] = d[1] * a[2] - d[2] * a[1];
            swayB[j][1] = d[2] * a[0] - d[0] * a[2];
            swayB[j][2] = d[0] * a[1] - d[1] * a[0];
            swayPhase[j] = j * 2.399963;
            System.arraycopy(d, 0, coat[j], 0, 3);
            System.arraycopy(d, 0, coatPrev[j], 0, 3);
        }
    }

    public void setFootfalls(Footfalls f) { this.footfalls = f; }
    /** Each body its own twitches (the entity's id, say). */
    public void setSeed(long s) { this.seed = s * 0x9E3779B97F4A7C15L + 0x10c4e5; }
    public boolean laid() { return laid; }
    public Mode mode() { return mode; }
    public boolean running() { return running; }
    public long ticks() { return ticks; }

    public double speed(double pt) { return speedPrev + (speed - speedPrev) * pt; }
    /** 0 walking, 1 running (eased between). */
    public double run(double pt) { return mixPrev + (mix - mixPrev) * pt; }
    public double crouch(double pt) { return crouchPrev + (crouch - crouchPrev) * pt; }
    public double mouth(double pt) { return mouthPrev + (mouth - mouthPrev) * pt; }

    /** The test's middle and its frame part way through the tick (forward and up, square to each other). */
    public void frame(double pt, Vector3d pos, Vector3d fwd, Vector3d up) {
        pos.set(pPrev).lerp(p, pt);
        fwd.set(fPrev).lerp(f, pt);
        if (fwd.lengthSquared() < 1.0E-8) fwd.set(f);
        fwd.normalize();
        up.set(uPrev).lerp(u, pt);
        up.fma(-up.dot(fwd), fwd);
        if (up.lengthSquared() < 1.0E-8) up.set(u);
        up.normalize();
    }

    /** A coat spine's way out part way through the tick, in the test's own frame (forward, up, left; unit). */
    public void coatWay(int j, double pt, double[] out) {
        double[] a = coatPrev[j], b = coat[j];
        double x = a[0] + (b[0] - a[0]) * pt, y = a[1] + (b[1] - a[1]) * pt, z = a[2] + (b[2] - a[2]) * pt;
        double d = Math.sqrt(x * x + y * y + z * z);
        if (d < 1.0E-6) { System.arraycopy(spec.coatDir[j], 0, out, 0, 3); return; }
        out[0] = x / d; out[1] = y / d; out[2] = z / d;
    }

    public Vector3d centre() { return p; }
    public Vector3d up() { return u; }
    public Vector3d forward() { return f; }

    /** The region of blocks the next tick reads: round the box, with room for the spines' reach. */
    public void bounds(Drive in, int[] out) {
        double r = spec.span() + 0.8 * spec.scale;
        out[0] = (int) Math.floor(in.x - r); out[1] = (int) Math.floor(in.y - r); out[2] = (int) Math.floor(in.z - r);
        out[3] = (int) Math.floor(in.x + r); out[4] = (int) Math.floor(in.y + r); out[5] = (int) Math.floor(in.z + r);
    }

    // ------------------------------------------------------------------------------------------------ the tick

    public void tick(TerrainPatch rock, Drive in) {
        ticks++;
        pPrev.set(p); fPrev.set(f); uPrev.set(u);
        for (Foot ft : feet) { ft.prevAt.set(ft.at); ft.prevNrm.set(ft.nrm); }
        for (int j = 0; j < coat.length; j++) System.arraycopy(coat[j], 0, coatPrev[j], 0, 3);
        speedPrev = speed; mixPrev = mix;
        crouchPrev = crouch; mouthPrev = mouth;
        mode = in.mode;
        upright = in.upright;
        boolean wasClimbing = climbing;
        climbing = in.upright && in.climbing;
        if (!laid || lastBox.distanceSquared(in.x, in.y, in.z) > JUMP * JUMP) {
            lay(rock, in);
            return;
        }
        if (mode == Mode.DEAD) {
            // dying, it is carried as it lies wherever its box goes; the pose lays it down and droops its spines
            double dx = in.x - lastBox.x, dy = in.y - lastBox.y, dz = in.z - lastBox.z;
            p.add(dx, dy, dz);
            for (Foot ft : feet) { ft.at.add(dx, dy, dz); ft.planted = false; ft.swinging = false; }
            lastBox.set(in.x, in.y, in.z);
            coatTick(in);
            return;
        }
        lastBox.set(in.x, in.y, in.z);

        // the moment's pose, eased
        crouch += (clamp01(in.crouch) - crouch) * (in.crouch > crouch ? 0.4 : 0.2);
        mouth += (clamp01(in.mouth) - mouth) * (in.mouth > mouth ? 0.5 : 0.3);
        tense += (clamp01(in.tense) - tense) * 0.2;

        frameTick(rock, in);
        // off its flue's walls (sat in its mouth, or down on the floor at its foot): its braced spines out to where they rest
        if (wasClimbing && !climbing && mode == Mode.SURFACE) unbrace(rock);
        feet(rock, in);
        coatTick(in);
    }

    /** Its spines braced up or down its flue's walls step out at once (a quick ripple) to where they would rest now. */
    private void unbrace(TerrainPatch rock) {
        for (Foot ft : feet) {
            if (!ft.planted || ft.swinging) continue;
            hip(ft.index, hipNow);
            if (!contact(rock, ft.index, hipNow, spec.restAngle[ft.index], spec.length[ft.index], t1, t2)) continue;
            if (t1.distance(ft.at) > UNBRACE * spec.scale) begin(rock, ft, 4 + ft.index % 3, 0.3 * spec.scale);
        }
    }

    /** How far out from the rock its middle rides now (crouched lower). */
    double rideNow() {
        return spec.ride - (spec.ride - spec.crouchRide) * crouch;
    }

    // ------------------------------------------------------------------------------------------------ the frame

    /** Its way out and its place: from the rock under it (or held upright, or along its flight) and its box. */
    private void frameTick(TerrainPatch rock, Drive in) {
        Vector3d want = t0;
        boolean felt = false;
        if (upright) {
            want.set(0, 1, 0);
            felt = true;
        } else if (mode == Mode.SURFACE && rock.field(in.x, in.y, in.z, REACH, SOFT, fld) && fld[0] < spec.ride + 1.2) {
            want.set(fld[1], fld[2], fld[3]);
            felt = true;
        }
        if (!felt) {
            if (mode == Mode.SURFACE && in.up.lengthSquared() > 0.5) want.set(in.up).normalize();
            else if (mode == Mode.AIR && in.velocity.lengthSquared() > 0.08 * 0.08) {
                // flying at its quarry: over onto its side, its mouth (under it) to the front
                want.set(in.velocity).normalize().negate();
                if (want.y < -0.2) want.y = -0.2;
                want.normalize();
            } else if (mode == Mode.AIR) want.set(0, 1, 0);
            else want.set(u);
        }
        easeUp(want, mode == Mode.AIR ? AIR_UP_EASE : UP_EASE);
        // its place: with its box (which the game keeps at its ride out from the rock), sunk by its crouch
        double drop = spec.ride - rideNow();
        Vector3d target = t2.set(in.x, in.y, in.z).fma(-drop, u);
        if (mode == Mode.SURFACE) {
            double moved = target.distance(p);
            p.lerp(target, moved > 1.2 ? 1.0 : 0.55);
            if (!upright && rock.field(p.x, p.y, p.z, REACH, SOFT, fld) && fld[0] < spec.ride + 0.6) {
                double m = rideNow() * 0.8 - fld[0];
                if (m > 0) p.add(fld[1] * m * 0.6, fld[2] * m * 0.6, fld[3] * m * 0.6);
            }
        } else p.lerp(target, 0.7);
        // its going, along the rock (up and down too, climbing its flue)
        t3.set(p).sub(pPrev);
        if (!climbing) t3.fma(-t3.dot(u), u);
        vel.lerp(t3, 0.45);
        double s = vel.length();
        speed += (s - speed) * 0.35;
        running = in.hurry || speed > (running ? WALK_FROM : RUN_FROM) * spec.scale;
        mix += Math.max(-0.15, Math.min(0.15, (running ? 1.0 : 0.0) - mix));
    }

    /**
     * Turns its way out toward {@code want} by a share. It never turns round its way out (it has no front): its
     * forward is carried with the turn (the shortest way round), so its spines keep their places round it.
     */
    private void easeUp(Vector3d want, double rate) {
        t1.set(u).lerp(want, rate);
        if (t1.lengthSquared() < 1.0E-8) t1.set(want);
        t1.normalize();
        if (u.dot(want) < -0.95) {
            // straight over: roll round its left first
            t1.set(u).fma(0.4, l).normalize();
        }
        carry(u, t1, f);
        u.set(t1);
        square(f, u);
        u.cross(f, l).normalize();
    }

    /** Makes {@code f} square to unit {@code u} (any square way, should it lie along u), unit. */
    private static void square(Vector3d f, Vector3d u) {
        f.fma(-f.dot(u), u);
        if (f.lengthSquared() < 1.0E-6) {
            if (Math.abs(u.y) < 0.9) f.set(0, 1, 0);
            else f.set(1, 0, 0);
            f.fma(-f.dot(u), u);
        }
        f.normalize();
    }

    /** Turns v by the shortest turn that takes unit a to unit b. */
    private static void carry(Vector3d a, Vector3d b, Vector3d v) {
        double c = a.dot(b);
        double ax = a.y * b.z - a.z * b.y, ay = a.z * b.x - a.x * b.z, az = a.x * b.y - a.y * b.x;
        double s = Math.sqrt(ax * ax + ay * ay + az * az);
        if (s < 1.0E-9) return;
        ax /= s; ay /= s; az /= s;
        double ang = Math.atan2(s, c), cs = Math.cos(ang), sn = Math.sin(ang);
        // Rodrigues
        double kd = ax * v.x + ay * v.y + az * v.z;
        double cx = ay * v.z - az * v.y, cy = az * v.x - ax * v.z, cz = ax * v.y - ay * v.x;
        v.set(v.x * cs + cx * sn + ax * kd * (1 - cs), v.y * cs + cy * sn + ay * kd * (1 - cs), v.z * cs + cz * sn + az * kd * (1 - cs));
    }

    // ------------------------------------------------------------------------------------------------ laying out

    /** Laid out afresh where its box is: on the rock under it, its spines lowered onto it, its coat at rest. */
    private void lay(TerrainPatch rock, Drive in) {
        laid = true;
        lastBox.set(in.x, in.y, in.z);
        crouch = crouchPrev = clamp01(in.crouch);
        mouth = mouthPrev = clamp01(in.mouth);
        tense = clamp01(in.tense);
        if (in.upright) u.set(0, 1, 0);
        else if (in.mode == Mode.SURFACE && rock.field(in.x, in.y, in.z, REACH, SOFT, fld)) u.set(fld[1], fld[2], fld[3]);
        else if (in.up.lengthSquared() > 0.5) u.set(in.up).normalize();
        else u.set(0, 1, 0);
        f.set(in.heading);
        square(f, u);
        u.cross(f, l).normalize();
        p.set(in.x, in.y, in.z).fma(-(spec.ride - rideNow()), u);
        vel.zero();
        for (Foot ft : feet) {
            hip(ft.index, hipNow);
            if (in.mode == Mode.SURFACE && contact(rock, ft.index, hipNow, spec.restAngle[ft.index], spec.length[ft.index], t1, t2)) plant(ft, t1, t2);
            else { air(ft, t1); ft.at.set(t1); ft.nrm.set(u); ft.planted = false; }
            ft.swinging = false;
            ft.prevAt.set(ft.at); ft.prevNrm.set(ft.nrm);
        }
        for (int j = 0; j < coat.length; j++) {
            System.arraycopy(spec.coatDir[j], 0, coat[j], 0, 3);
            System.arraycopy(spec.coatDir[j], 0, coatPrev[j], 0, 3);
        }
        pPrev.set(p); fPrev.set(f); uPrev.set(u);
        speedPrev = speed = 0; mixPrev = mix = running ? 1 : 0;
        standing = 0;
    }

    // ------------------------------------------------------------------------------------------------ the spines

    /** One of its sixteen walking spines: where its point rests and on what. */
    public static final class Foot {
        public final int index;
        /** On rock and staying there. */
        public boolean planted;
        /** Where its point is (on rock when planted), and the way out of that rock. */
        public final Vector3d at = new Vector3d(), nrm = new Vector3d(0, 1, 0);
        public final Vector3d prevAt = new Vector3d(), prevNrm = new Vector3d(0, 1, 0);
        /** Stepping: where it lands, on what; its point's way and distance from its socket as it lifted (in the test's frame). */
        final Vector3d to = new Vector3d(), toN = new Vector3d(0, 1, 0), fromN = new Vector3d(0, 1, 0);
        final double[] fromWay = new double[3];
        double fromLen, t, step, lift;
        boolean swinging, toRock;
        /** A tap in place (stirring): the point lifts and comes down where it was. */
        boolean tap;
        /** How hard its planted point pulls at it (1: it must step). */
        double strain;

        Foot(int index) {
            this.index = index;
        }

        /** Where its point is part way through the tick, and the way out of the rock under it. */
        public void sole(double pt, Vector3d p, Vector3d n) {
            p.set(prevAt).lerp(at, pt);
            n.set(prevNrm).lerp(nrm, pt);
            if (n.lengthSquared() < 1.0E-8) n.set(nrm);
            n.normalize();
        }

        public boolean swinging() { return swinging; }
    }

    /** How many of its spines stand on rock now. */
    public int plantedCount() {
        int n = 0;
        for (Foot ft : feet) if (ft.planted) n++;
        return n;
    }

    /** A spine's socket on the test, where it is now. */
    void hip(int leg, Vector3d out) {
        double[] s = spec.socket[leg];
        out.set(p).fma(s[0], f).fma(s[1], u).fma(s[2], l);
    }

    /** The way round the test at azimuth a (radians from forward toward its left), along its rock. */
    private void radial(double a, Vector3d out) {
        out.set(f).mul(Math.cos(a)).fma(Math.sin(a), l);
    }

    private void feet(TerrainPatch rock, Drive in) {
        if (mode == Mode.AIR) {
            // in the air: every spine up, reaching forward round its mouth (under it, to the front as it flies)
            for (Foot ft : feet) {
                ft.planted = false;
                ft.swinging = false;
                air(ft, t1);
                ft.at.lerp(t1, 0.4);
                ft.nrm.set(u);
            }
            return;
        }
        int up = 0;
        for (Foot ft : feet) if (ft.swinging) up++;
        // stepping spines swing on
        for (Foot ft : feet) if (ft.swinging) swing(rock, ft);
        boolean moving = speed > 0.006 * spec.scale;
        standing = moving ? 0 : standing + 1;
        int most = running || climbing ? AIR_RUN : AIR_WALK;
        // planted spines: how hard each pulls, and which must step
        Foot pick = null;
        for (Foot ft : feet) {
            if (ft.swinging) continue;
            if (!ft.planted) {
                // back on the rock (after a leap, a fall): down at once if rock is in reach
                hip(ft.index, hipNow);
                if (contact(rock, ft.index, hipNow, spec.restAngle[ft.index], spec.length[ft.index], t1, t2)) {
                    begin(rock, ft, 3 + (ft.index % 3), 0.2 * spec.scale);
                    up++;
                } else {
                    air(ft, t1);
                    ft.at.lerp(t1, 0.3);
                    ft.nrm.set(u);
                }
                continue;
            }
            ft.strain = strain(ft);
            boolean lost = !standsOn(rock, ft.at, ft.nrm);
            if (lost || ft.strain >= 1.3) {
                // pulled past bearing (or the rock gone from under it): up at once, neighbours or not
                begin(rock, ft, swingTicks(), liftNow());
                up++;
                continue;
            }
            if (ft.strain >= 1.0 || (moving && ft.strain > 0.72)) {
                if (pick == null || ft.strain > pick.strain) {
                    if (!neighbourUp(ft)) pick = ft;
                }
            }
        }
        // the hardest pulled spine that may lift lifts (and, running, a second)
        for (int k = 0; k < (running || climbing ? 2 : 1) && pick != null && up < most; k++) {
            begin(rock, pick, swingTicks(), liftNow());
            up++;
            Foot next = null;
            for (Foot ft : feet) {
                if (!ft.planted || ft.swinging || ft == pick) continue;
                if ((ft.strain >= 1.0 || (moving && ft.strain > 0.72)) && !neighbourUp(ft) && (next == null || ft.strain > next.strain)) next = ft;
            }
            pick = next;
        }
        // standing: one spine at a time steps back to where it would rest
        if (standing > 6 && up == 0 && ticks % 3 == 0) {
            for (int tries = 0; tries < 2; tries++) {
                Foot ft = feet[settleNext];
                settleNext = (settleNext + 1) % feet.length;
                if (!ft.planted) continue;
                hip(ft.index, hipNow);
                if (!contact(rock, ft.index, hipNow, spec.restAngle[ft.index], spec.length[ft.index], t1, t2)) continue;
                if (t1.distance(ft.at) > SETTLE * spec.scale * (upright ? 0.6 : 1.0)) {
                    begin(rock, ft, 6, 0.28 * spec.scale);
                    break;
                }
            }
        }
        // stirring: a spine lifts and taps down again where it was
        if (tense > 0.25 && up == 0 && rand(40) < 0.09 * tense) {
            Foot ft = feet[(int) (rand(41) * feet.length)];
            if (ft.planted) {
                begin(rock, ft, 4, 0.16 * spec.scale);
                ft.to.set(ft.at); ft.toN.set(ft.nrm); ft.toRock = true; ft.tap = true;
            }
        }
    }

    private boolean neighbourUp(Foot ft) {
        int n = feet.length;
        return feet[(ft.index + 1) % n].swinging || feet[(ft.index + n - 1) % n].swinging;
    }

    /**
     * How hard a planted spine's point pulls at it: 1 where its point has come in to {@link LurkerSpec#near} of its rest
     * from its socket, gone out to {@link LurkerSpec#far} of it, or swung round to the edge of its room among its
     * neighbours.
     */
    private double strain(Foot ft) {
        int i = ft.index;
        hip(i, hipNow);
        t3.set(ft.at).sub(hipNow);
        double dist = t3.length(), d = dist / spec.rest[i];
        double s = d < 1.0 ? (1.0 - d) / (1.0 - spec.near) : (d - 1.0) / (spec.far - 1.0);
        // one the going is easing (in near as the test draws away from it, out far as it comes back over it) is in no hurry
        double toward = dist > 1.0E-4 ? vel.dot(t3) / dist : 0.0;
        if ((d < 1.0 && toward < -0.004 * spec.scale) || (d > 1.0 && toward > 0.004 * spec.scale)) s *= 0.35;
        // round the test: its point's way from the middle, along the rock, against its own way
        t3.set(ft.at).sub(p);
        t3.fma(-t3.dot(u), u);
        if (t3.lengthSquared() > 1.0E-6) {
            radial(spec.restAngle[i], t4);
            t5.set(u).cross(t4);
            double ang = Math.atan2(t3.dot(t5), t3.dot(t4));
            s = Math.max(s, Math.abs(ang) / spec.sector);
        }
        return s;
    }

    private double liftNow() {
        return (running ? 0.42 : 0.34) * spec.scale;
    }

    private int swingTicks() {
        if (climbing) return BRACE_SWING;
        int t = (int) Math.round(0.7 * spec.scale / Math.max(speed, 0.05));
        return Math.max(3, Math.min(7, t));
    }

    /** A spine's point in the air (leaping, falling): reaching out and forward round its mouth. */
    private void air(Foot ft, Vector3d out) {
        int i = ft.index;
        hip(i, hipNow);
        radial(spec.restAngle[i], t4);
        out.set(t4).mul(0.72).fma(-0.69, u).normalize().mul(spec.rest[i]).add(hipNow);
    }

    /** Lifts a spine to step: from where its point stands to where the going will want it as it lands. */
    private void begin(TerrainPatch rock, Foot ft, int ticksFor, double lift) {
        hip(ft.index, hipNow);
        t0.set(ft.at).sub(hipNow);
        ft.fromLen = Math.max(1.0E-3, t0.length());
        toLocal(t0.div(ft.fromLen), ft.fromWay);
        ft.fromN.set(ft.nrm);
        ft.swinging = true;
        ft.planted = false;
        ft.tap = false;
        ft.t = 0.0;
        ft.step = 1.0 / ticksFor;
        ft.lift = lift;
        aim(rock, ft, ticksFor, true);
    }

    /**
     * Where a step lands: from where its socket will be as it lands, its needle hung from its knee's place and lowered
     * round its own way onto the rock: a spine the test is coming toward hangs it longer ({@link #LAND_LONG}: it lands far
     * out), one it is going away from shorter ({@link #LAND_SHORT}: near in), one it is passing lands ahead round the test;
     * standing, at rest.
     */
    private void aim(TerrainPatch rock, Foot ft, double ticksLeft, boolean fresh) {
        if (ft.tap) return;
        int i = ft.index;
        double L = spec.length[i];
        hip(i, t6);
        t6.fma(ticksLeft, vel);
        if (climbing) {
            // braced on its flue's wall: far down (or up) the wall it is coming toward, near in on the side it is leaving
            double toward = vel.dot(u) * (bracedDown(i) ? -1.0 : 1.0);
            double dist = spec.rest[i] * (toward > 0.01 * spec.scale ? spec.far * BRACE_LONG : toward < -0.01 * spec.scale ? spec.near * BRACE_SHORT : 1.0);
            if (brace(rock, i, t6, spec.restAngle[i], dist, t1, t2) || brace(rock, i, t6, spec.restAngle[i], spec.rest[i], t1, t2)) {
                ft.toRock = true;
                ft.to.set(t1);
                ft.toN.set(t2);
                return;
            }
        }
        double a = spec.restAngle[i], len = L;
        double v = vel.length();
        if (v > 0.006 * spec.scale) {
            radial(a, t4);
            t5.set(u).cross(t4);
            double c = vel.dot(t4) / v, s = vel.dot(t5) / v;
            len = c > 0 ? L * (1.0 + (LAND_LONG - 1.0) * c) : L * (1.0 - (1.0 - LAND_SHORT) * -c);
            a += spec.sector * LAND_ROUND * s;
        }
        boolean found = contact(rock, i, t6, a, len, t1, t2);
        if (!found) found = contact(rock, i, t6, spec.restAngle[i], L, t1, t2);
        if (!found && !fresh && ft.toRock && standsOn(rock, ft.to, ft.toN)) return;
        if (found) {
            ft.toRock = true;
            ft.to.set(t1);
            ft.toN.set(t2);
        } else {
            ft.toRock = false;
            air(ft, t1);
            ft.to.set(t1);
            ft.toN.set(u);
        }
    }

    /**
     * A step: its point swings round its socket from the way it lifted from to the way it lands, lifted clear of the
     * rock (more over an edge), its distance from its socket eased between; it lands where it was aimed.
     */
    private void swing(TerrainPatch rock, Foot ft) {
        ft.t = Math.min(1.0, ft.t + ft.step);
        double left = (1.0 - ft.t) / ft.step;
        if (ft.t < 0.7 && (ticks + ft.index) % 2 == 0) aim(rock, ft, left, false);
        int i = ft.index;
        hip(i, hipNow);
        t0.set(ft.to).sub(hipNow);
        double toLen = Math.max(1.0E-3, t0.length());
        t0.div(toLen);
        // its way as it lifted, in the world now
        fromWorld(ft.fromWay, t1);
        double e = ft.t * ft.t * (3.0 - 2.0 * ft.t);
        slerp(t1, t0, e, t2);
        double len = ft.fromLen + (toLen - ft.fromLen) * e;
        // lifted: up off the rock (out of it, and away from the body's underside)
        double over = 1.0 + Math.max(0.0, 0.6 - ft.fromN.dot(ft.toN));
        double rise = Math.sin(Math.PI * Math.pow(ft.t, 0.8)) * ft.lift * over;
        t3.set(ft.fromN).lerp(ft.toN, e).add(u);
        if (t3.lengthSquared() < 1.0E-6) t3.set(u);
        t3.normalize();
        ft.at.set(hipNow).fma(len, t2).fma(rise, t3);
        // never through rock on its way: lifted higher until it clears
        if (ft.t < 0.95 && rock.solid(ft.at.x, ft.at.y, ft.at.z)) {
            ft.lift += 0.2 * spec.scale;
            ft.at.fma(0.2 * spec.scale, t3);
        }
        ft.nrm.set(ft.fromN).lerp(ft.toN, e);
        if (ft.nrm.lengthSquared() < 1.0E-8) ft.nrm.set(u);
        ft.nrm.normalize();
        if (ft.t >= 1.0) {
            ft.swinging = false;
            boolean tapped = ft.tap;
            ft.tap = false;
            if (!ft.toRock || !standsOn(rock, ft.to, ft.toN)) {
                hip(i, hipNow);
                boolean found = contact(rock, i, hipNow, spec.restAngle[i], spec.length[i], t1, t2);
                ft.toRock = found;
                if (found) { ft.to.set(t1); ft.toN.set(t2); }
            }
            if (ft.toRock) {
                plant(ft, ft.to, ft.toN);
                if (footfalls != null && !tapped) footfalls.land(ft, running, speed);
            } else ft.planted = false;
        }
    }

    private void plant(Foot ft, Vector3d at, Vector3d n) {
        ft.at.set(at);
        ft.nrm.set(n);
        ft.planted = true;
        ft.swinging = false;
        ft.strain = 0;
    }

    /** Whether a point at p (out of its rock along n) stands on rock. */
    private static boolean standsOn(TerrainPatch rock, Vector3d p, Vector3d n) {
        return rock.solid(p.x - n.x * 0.06, p.y - n.y * 0.06, p.z - n.z * 0.06);
    }

    /** Where spine {@code leg}'s knee sits at ease, its socket at {@code h}, its way round the test {@code a}. */
    void kneeAtEase(int leg, Vector3d h, double a, Vector3d out) {
        radial(a, out);
        double c = Math.cos(LurkerSpec.KNEE_UP), s = Math.sin(LurkerSpec.KNEE_UP);
        out.mul(c * spec.upper[leg]).fma(s * spec.upper[leg], u).add(h);
    }

    /**
     * Where spine {@code leg}'s point (its socket at {@code h}) comes to rest round the way {@code a} (radians round the
     * test from forward toward its left): its needle, {@code len} long, hung from its knee's place at ease and lowered
     * ({@link #lower}) until it meets rock; one meeting an edge in its way on its shaft (its point beyond in the air)
     * reaches on over it to the rock beyond. {@code out} gets the point, {@code n} the way out of its rock. False if it
     * meets none, or none its knee lets it reach ({@link LurkerSpec#near} to {@link LurkerSpec#far} of its rest).
     */
    boolean contact(TerrainPatch rock, int leg, Vector3d h, double a, double len, Vector3d out, Vector3d n) {
        if (rock.solid(h.x, h.y, h.z)) return false;
        kneeAtEase(leg, h, a, knee0);
        Vector3d from = knee0;
        double reach = len;
        if (rock.solid(knee0.x, knee0.y, knee0.z)) {
            // pressed to rock (a wall beside it): from its socket, the whole spine's length
            from = h;
            reach = len + spec.upper[leg];
        }
        // climbing its flue: braced on its wall over or under it, where there is one
        if (climbing && brace(rock, leg, h, a, spec.rest[leg], out, n)) return true;
        if (!lower(rock, from, a, reach, SWEEP_FROM, out, n)) return false;
        if (lowEdge && lowFrac < 0.9) {
            // over an edge in its way: on over it, from just over the edge, to the rock beyond
            double rest = reach * (1.0 - lowFrac);
            over.set(out).fma(0.05, n).fma(0.12 * spec.scale, u);
            if (!rock.solid(over.x, over.y, over.z) && lower(rock, over, a, rest, lowAngle, t3, overN) && !lowEdge) {
                out.set(t3);
                n.set(overN);
            }
        }
        double d = out.distance(h) / spec.rest[leg];
        return d >= spec.near && d <= spec.far;
    }

    /** Climbing its flue, whether spine {@code leg} braces down the flue's wall under it (every other one) or up the wall over it. */
    private static boolean bracedDown(int leg) { return leg % 2 == 0; }

    /**
     * Climbing its flue (0.21.4): spine {@code leg}'s point braced on the flue's wall, {@code dist} from its socket
     * {@code h} round the way {@code a}: its spine held straight from its socket and swung from straight down (or up,
     * {@link #bracedDown}) toward level until its point first meets rock with nothing in its way before it. {@code out}
     * gets the point (just in the rock), {@code n} the way out of it. False if it meets none (no wall there).
     */
    private boolean brace(TerrainPatch rock, int leg, Vector3d h, double a, double dist, Vector3d out, Vector3d n) {
        double side = bracedDown(leg) ? -1.0 : 1.0;
        radial(a, t4);
        double clearAt = -1.0;
        for (double th = 0.0; th <= BRACE_TO + 1.0E-9; th += BRACE_STEP) {
            int r = braceAt(rock, h, side, th, dist);
            if (r == 1) {
                // refined: the angle its point first touches rock
                double lo = clearAt < 0 ? th : clearAt, hi = th;
                if (clearAt >= 0) {
                    for (int k = 0; k < SWEEP_REFINE; k++) {
                        double mid = (lo + hi) * 0.5;
                        if (braceAt(rock, h, side, mid, dist) == 1) hi = mid;
                        else lo = mid;
                    }
                }
                double s = Math.sin(hi), c = Math.cos(hi) * side;
                out.set(h).fma(dist * s, t4).fma(dist * c, u);
                // the way out of that rock: where a line from a little short of its point meets it
                t5.set(h).fma(dist * 0.85 * s, t4).fma(dist * 0.85 * c, u);
                if (rock.clip(t5.x, t5.y, t5.z, out.x + (out.x - t5.x) * 0.5, out.y + (out.y - t5.y) * 0.5, out.z + (out.z - t5.z) * 0.5, hit) && hit[4] < 0.5)
                    n.set(hit[1], hit[2], hit[3]);
                else n.set(t4).negate();
                return true;
            }
            clearAt = r == 0 ? th : -1.0;
        }
        return false;
    }

    /**
     * A braced spine's straight line from {@code h}, {@code th} off straight down ({@code side} -1) or up: 1 if its point
     * lies in rock with its way there clear, 0 if all of it is clear, -1 if rock is in its way short of its point.
     */
    private int braceAt(TerrainPatch rock, Vector3d h, double side, double th, double dist) {
        double s = Math.sin(th), c = Math.cos(th) * side;
        double px = h.x + dist * (t4.x * s + u.x * c), py = h.y + dist * (t4.y * s + u.y * c), pz = h.z + dist * (t4.z * s + u.z * c);
        double qx = h.x + (px - h.x) * BRACE_CLEAR, qy = h.y + (py - h.y) * BRACE_CLEAR, qz = h.z + (pz - h.z) * BRACE_CLEAR;
        if (rock.clip(h.x, h.y, h.z, qx, qy, qz, hit)) return -1;
        return rock.solid(px, py, pz) ? 1 : 0;
    }

    /**
     * Lowers a needle {@code len} long from {@code o}, round the way {@code a}, from the angle {@code from} under level
     * (radians; up is negative) to {@link #SWEEP_TO} past straight down, in steps, then refined, until it meets rock: its
     * point (just in the rock) or, met on its shaft over an edge (its point beyond in the air), where its shaft meets the
     * edge ({@link #lowEdge}, {@link #lowFrac}), and the way out of that rock. A needle already against rock as it starts
     * is first raised (as far as {@link #SWEEP_UP}) until it clears. False if it meets none, or starts in rock.
     */
    private boolean lower(TerrainPatch rock, Vector3d o, double a, double len, double from, Vector3d out, Vector3d n) {
        lowEdge = false;
        double ca = Math.cos(a), sa = Math.sin(a);
        double rx = f.x * ca + l.x * sa, ry = f.y * ca + l.y * sa, rz = f.z * ca + l.z * sa;
        double clear = Double.NaN;
        if (reach(rock, o, rx, ry, rz, from, len)) {
            if (hit[4] > 0.5) return false;
            if (hit[1] * u.x + hit[2] * u.y + hit[3] * u.z < -0.5) {
                // under a roof (a low ceiling over it, 0.21.4): lowered from under it, never raised up through a hole in it
                double th = from + SWEEP_STEP;
                while (th < 0.0 && reach(rock, o, rx, ry, rz, th, len) && hit[1] * u.x + hit[2] * u.y + hit[3] * u.z < -0.5) th += SWEEP_STEP;
                from = th;
            } else {
                for (double th = from - SWEEP_STEP; th >= SWEEP_UP - 1.0E-9; th -= SWEEP_STEP) {
                    if (!reach(rock, o, rx, ry, rz, th, len)) { clear = th; break; }
                }
                if (Double.isNaN(clear)) return false;
                from = clear + SWEEP_STEP;
            }
        }
        for (double th = from; th <= SWEEP_TO + 1.0E-9; th += SWEEP_STEP) {
            if (!reach(rock, o, rx, ry, rz, th, len)) { clear = th; continue; }
            if (hit[4] > 0.5) return false;
            double hi = th;
            if (!Double.isNaN(clear)) {
                double lo = clear;
                for (int k = 0; k < SWEEP_REFINE; k++) {
                    double mid = (lo + hi) * 0.5;
                    if (reach(rock, o, rx, ry, rz, mid, len) && hit[4] < 0.5) hi = mid;
                    else lo = mid;
                }
                reach(rock, o, rx, ry, rz, hi, len);
            }
            double c = Math.cos(hi), s = Math.sin(hi);
            double wx = rx * c - u.x * s, wy = ry * c - u.y * s, wz = rz * c - u.z * s;
            n.set(hit[1], hit[2], hit[3]);
            lowAngle = hi;
            double px = o.x + wx * len, py = o.y + wy * len, pz = o.z + wz * len;
            if (rock.solid(px, py, pz) || hit[0] > 0.985) {
                out.set(px, py, pz);
                lowFrac = 1.0;
            } else {
                double tt = hit[0] * len;
                out.set(o.x + wx * tt, o.y + wy * tt, o.z + wz * tt);
                lowEdge = true;
                lowFrac = hit[0];
            }
            return true;
        }
        return false;
    }

    /** Whether the needle from h, pointing th under level round the way (rx, ry, rz), meets rock within len (into {@link #hit}). */
    private boolean reach(TerrainPatch rock, Vector3d h, double rx, double ry, double rz, double th, double len) {
        double c = Math.cos(th), s = Math.sin(th);
        double dx = (rx * c - u.x * s) * len, dy = (ry * c - u.y * s) * len, dz = (rz * c - u.z * s) * len;
        return rock.clip(h.x, h.y, h.z, h.x + dx, h.y + dy, h.z + dz, hit);
    }

    // ------------------------------------------------------------------------------------------------ the coat

    /**
     * The coat: its spines sway slowly; they swing to point at what it attends to (those on the side toward it, as far
     * as their sockets let them), sweep back from its mouth as it flies, and droop as it dies.
     */
    private void coatTick(Drive in) {
        double want = mode == Mode.DEAD ? 0.0 : clamp01(in.focusAmount);
        boolean snap = want > focusAmount + 0.3;
        focusAmount += (want - focusAmount) * (want > focusAmount ? 0.5 : 0.08);
        double rate = snap ? 0.6 : 0.22;
        // where it attends, in the test's own frame (from its middle)
        double qx = 0, qy = 0, qz = 0;
        boolean focus = focusAmount > 0.02 && in.focus.lengthSquared() > 0;
        if (focus) {
            t0.set(in.focus).sub(p);
            qx = t0.dot(f); qy = t0.dot(u); qz = t0.dot(l);
        }
        // down in the world, in its frame (for drooping)
        double gx = -f.y, gy = -u.y, gz = -l.y;
        double age = ticks + (seed & 0xFF);
        double sway = mode == Mode.DEAD ? 0.0 : 0.08 + 0.05 * tense;
        for (int j = 0; j < coat.length; j++) {
            double[] d = spec.coatDir[j], A = swayA[j], B = swayB[j], s = spec.coatSocket[j];
            double sa = Math.sin(age * 0.061 + swayPhase[j]) * sway, sb = Math.cos(age * 0.047 + swayPhase[j] * 1.7) * sway;
            double wx = d[0] + A[0] * sa + B[0] * sb, wy = d[1] + A[1] * sa + B[1] * sb, wz = d[2] + A[2] * sa + B[2] * sb;
            if (focus) {
                // toward what it attends to, from its own socket: the near side's spines bend to it
                double ex = qx - s[0], ey = qy - s[1], ez = qz - s[2], el = Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (el > 1.0E-4) {
                    ex /= el; ey /= el; ez /= el;
                    double dot = ex * d[0] + ey * d[1] + ez * d[2];
                    double w = focusAmount * clamp01((dot + 0.35) / 1.0) * 0.95;
                    wx += (ex - dot * d[0]) * w; wy += (ey - dot * d[1]) * w; wz += (ez - dot * d[2]) * w;
                }
            }
            if (mode == Mode.AIR) { wy += 0.55; }
            if (mode == Mode.DEAD) { wx += gx * 0.7; wy += gy * 0.7; wz += gz * 0.7; }
            double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (wl < 1.0E-6) continue;
            wx /= wl; wy /= wl; wz /= wl;
            double[] c = coat[j];
            c[0] += (wx - c[0]) * rate; c[1] += (wy - c[1]) * rate; c[2] += (wz - c[2]) * rate;
            double cl = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
            if (cl > 1.0E-6) { c[0] /= cl; c[1] /= cl; c[2] /= cl; }
        }
    }

    // ------------------------------------------------------------------------------------------------ small things

    /** A way in the world into the test's own frame (forward, up, left). */
    private void toLocal(Vector3d w, double[] out) {
        out[0] = w.dot(f); out[1] = w.dot(u); out[2] = w.dot(l);
    }

    /** A way in the test's own frame into the world. */
    private void fromWorld(double[] w, Vector3d out) {
        out.set(f).mul(w[0]).fma(w[1], u).fma(w[2], l);
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

    /** A number from 0 to 1 that changes every tick, its own for each body and each use. */
    private double rand(int use) {
        long h = seed + ticks * 0x9E3779B97F4A7C15L + use * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
