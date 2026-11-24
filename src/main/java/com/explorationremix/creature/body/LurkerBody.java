package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * The chimney lurker's body as it clings to the rock (0.21.0, Taran's ask: "lots of legs that land on real blocks").
 * A low prosoma ringed by twelve long legs ({@link LurkerSpec}), a heavy abdomen behind it on a springy waist, two
 * fangs and two feelers at its front. It holds to any rock as a spider does: on a floor, up and down the sheer walls of
 * its vent, over the rim and down into the chimney's mouth, its belly turned to the rock under it and its back away.
 *
 * <ul>
 *     <li>The body rides a set distance out from the rock round its box, its "up" the way out of that rock (eased, so
 *     going over an edge it rolls round it, and going from a floor up a wall it pitches up through the corner).</li>
 *     <li>Each foot stands on real rock: the face under where it rests, a wall in its way, the top of an edge it
 *     reaches over, the face under an edge it reaches round. It stays put while the body moves over it and steps when
 *     the gait says, landing ahead on whatever rock it finds there; no foot strays into its neighbours' room, so the
 *     legs never cross.</li>
 *     <li>It walks in waves running up each side from the back legs to the front, and runs in two alternating sets of
 *     six (a skitter), its feet tapping down in a crowded ring.</li>
 *     <li>Braced in its chimney's mouth (Taran's card: "Down in the chimney's mouth") it hangs head up on the flue's
 *     inner wall with its legs drawn in, and its front legs reach up over the rim to hook their tips over the top.</li>
 *     <li>Winding up its leap it sinks low and raises its front legs, its fangs spread; in the air its legs reach out
 *     ahead of it, ready to grab.</li>
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
        /** Where its body's centre (the prosoma's middle) should be: its box's position plus {@link LurkerSpec#ride} up. */
        public double x, y, z;
        /** The way it heads (unit), and the way out of the rock it holds to when the game knows it (else zero). */
        public final Vector3d heading = new Vector3d(0, 0, 1), up = new Vector3d();
        /** Its speed through the air (blocks a tick), for its pose in a leap. */
        public final Vector3d velocity = new Vector3d();
        public Mode mode = Mode.SURFACE;
        /** 0 to 1: how far it crouches to leap, how far it is braced in its chimney, how far its fangs are spread. */
        public double crouch, brace, fangs;
        /** 0 to 1: stirring (it has felt something): its front feet tap. */
        public double tense;
        /** It runs (the skitter). */
        public boolean hurry;
    }

    /** Hears each foot as it lands. */
    public interface Footfalls {
        void land(Foot foot, boolean running, double speed);
    }

    // ------------------------------------------------------------------------------------------------ tuning
    /** Further than this between ticks (blocks), it was put somewhere new: laid out afresh there. */
    static final double JUMP = 3.0;
    /** Rock further than this from the body is not felt; surfaces meeting in a hollow are blended over SOFT. */
    static final double REACH = 2.2, SOFT = 0.3;
    /** The feet's timing: walking, waves up each side from the back (the sides half a stride apart); running, two sets of six. */
    static final double[] WALK_OFFSET = new double[LurkerSpec.LEGS], RUN_OFFSET = new double[LurkerSpec.LEGS];

    static {
        for (int i = 0; i < LurkerSpec.LEGS; i++) {
            int pair = LurkerSpec.pair(i);
            boolean left = LurkerSpec.left(i);
            WALK_OFFSET[i] = ((5 - pair) / 6.0 + (left ? 0.0 : 0.5)) % 1.0;
            RUN_OFFSET[i] = ((pair + (left ? 0 : 1)) % 2 == 0) ? 0.0 : 0.5;
        }
    }

    /** The share of a stride each foot is down, walking and running. */
    static final double WALK_DUTY = 0.74, RUN_DUTY = 0.52;
    /** It breaks into a run above this speed, and back to a walk under the second (blocks a tick). */
    static final double RUN_FROM = 0.16, WALK_FROM = 0.12;
    static final int STEP_MIN = 3, STEP_MAX = 10;
    /** Standing, a foot further than this from where it rests steps back to it (blocks, at scale 1). */
    static final double SETTLE_FOOT = 0.38;
    /** A planted foot stretched past this share of its leg's reach has been left behind: it steps at once. */
    static final double OVERREACH = 0.99;
    /** A step lands no further ahead than this share of the leg's reach. */
    static final double MAX_AHEAD = 0.32;
    static final double MAX_AIM_SPEED = 0.5;
    /** How fast its way out and its heading turn (shares a tick), and the most its heading turns a tick (radians). */
    static final double UP_EASE = 0.32, TURN_EASE = 0.35, MAX_TURN = Math.toRadians(24);
    /** Crouched it sinks to this share of its ride, its legs drawn in to this share; braced, to these. */
    static final double CROUCH_RIDE = 0.52, CROUCH_IN = 0.86, BRACE_RIDE = 0.82, BRACE_IN = 0.8;
    /** The abdomen: its rest rise over the prosoma's back (radians), how hard it is pulled to rest, how much swing it keeps. */
    static final double ABDOMEN_RISE = Math.toRadians(14), ABDOMEN_PULL = 0.24, ABDOMEN_KEEP = 0.62, ABDOMEN_SAG = 0.22;

    // ------------------------------------------------------------------------------------------------ shape
    public final LurkerSpec spec;
    private final double k;

    // ------------------------------------------------------------------------------------------------ state
    private boolean laid;
    private final Vector3d lastBox = new Vector3d();
    /** The prosoma's middle and its frame (forward, up), now and last tick. */
    final Vector3d p = new Vector3d(), pPrev = new Vector3d(), f = new Vector3d(0, 0, 1), fPrev = new Vector3d(0, 0, 1),
            u = new Vector3d(0, 1, 0), uPrev = new Vector3d(0, 1, 0);
    /** Its own left this tick (u x f). */
    final Vector3d l = new Vector3d(1, 0, 0);
    private double crouch, crouchPrev, brace, bracePrev, fangs, fangsPrev, tense, run, runPrev;
    private double phase, phasePrev, speed, speedPrev, mix, mixPrev;
    private boolean running;
    private int standing;
    private Mode mode = Mode.SURFACE;
    private long ticks;
    private Footfalls footfalls;
    public final Foot[] feet = new Foot[LurkerSpec.LEGS];
    /** The abdomen's way back from the waist (unit), now, last tick and the tick before. */
    final Vector3d ab = new Vector3d(0, 0, -1), abPrev = new Vector3d(0, 0, -1), abOld = new Vector3d(0, 0, -1);
    /** The feelers' tips, now and last tick; where each is going. */
    final Vector3d[] palp = vectors(2), palpPrev = vectors(2);
    private final Vector3d[] palpGoal = vectors(2);
    private final int[] palpTimer = new int[2];
    private long seed = 0x10c4e5;

    // scratch
    private final double[] hit = new double[5], fld = new double[4];
    private final Vector3d t0 = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), t3 = new Vector3d(), t4 = new Vector3d(),
            t5 = new Vector3d();

    public LurkerBody(LurkerSpec spec) {
        this.spec = spec;
        this.k = spec.scale / 16.0;
        for (int i = 0; i < LurkerSpec.LEGS; i++) feet[i] = new Foot(i);
    }

    private static Vector3d[] vectors(int n) {
        Vector3d[] v = new Vector3d[n];
        for (int i = 0; i < n; i++) v[i] = new Vector3d();
        return v;
    }

    public void setFootfalls(Footfalls f) { this.footfalls = f; }
    /** Each body its own twitches (the entity's id, say). */
    public void setSeed(long s) { this.seed = s * 0x9E3779B97F4A7C15L + 0x10c4e5; }
    public boolean laid() { return laid; }
    public Mode mode() { return mode; }
    public boolean running() { return running; }
    public long ticks() { return ticks; }

    public double phase(double pt) { return phasePrev + (phase - phasePrev) * pt; }
    public double speed(double pt) { return speedPrev + (speed - speedPrev) * pt; }
    /** 0 walking, 1 running (eased between). */
    public double run(double pt) { return mixPrev + (mix - mixPrev) * pt; }
    public double crouch(double pt) { return crouchPrev + (crouch - crouchPrev) * pt; }
    public double brace(double pt) { return bracePrev + (brace - bracePrev) * pt; }
    public double fangs(double pt) { return fangsPrev + (fangs - fangsPrev) * pt; }

    /** The prosoma's middle and its frame part way through the tick (forward and up, square to each other). */
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

    /** The abdomen's way back from the waist part way through the tick. */
    public void abdomen(double pt, Vector3d out) {
        out.set(abPrev).lerp(ab, pt);
        if (out.lengthSquared() < 1.0E-8) out.set(ab);
        out.normalize();
    }

    public Vector3d centre() { return p; }
    public Vector3d up() { return u; }
    public Vector3d forward() { return f; }

    /** The region of blocks the next tick reads: round the box, with room for the legs' reach. */
    public void bounds(Drive in, int[] out) {
        double r = 3.6 * spec.scale;
        out[0] = (int) Math.floor(in.x - r); out[1] = (int) Math.floor(in.y - r); out[2] = (int) Math.floor(in.z - r);
        out[3] = (int) Math.floor(in.x + r); out[4] = (int) Math.floor(in.y + r); out[5] = (int) Math.floor(in.z + r);
    }

    // ------------------------------------------------------------------------------------------------ the tick

    public void tick(TerrainPatch rock, Drive in) {
        ticks++;
        pPrev.set(p); fPrev.set(f); uPrev.set(u);
        for (Foot ft : feet) {
            ft.prevAt.set(ft.at); ft.prevNrm.set(ft.nrm);
            ft.tPrev = ft.t; ft.toPrev.set(ft.to); ft.toNPrev.set(ft.toN); ft.swung = false;
        }
        phasePrev = phase; speedPrev = speed; mixPrev = mix;
        crouchPrev = crouch; bracePrev = brace; fangsPrev = fangs; runPrev = run;
        abOld.set(abPrev); abPrev.set(ab);
        for (int i = 0; i < 2; i++) palpPrev[i].set(palp[i]);
        mode = in.mode;
        if (!laid || lastBox.distanceSquared(in.x, in.y, in.z) > JUMP * JUMP) {
            lay(rock, in);
            return;
        }
        if (mode == Mode.DEAD) {
            // dying, it is carried as it lies wherever its box goes; the pose curls its legs and rolls it over
            double dx = in.x - lastBox.x, dy = in.y - lastBox.y, dz = in.z - lastBox.z;
            p.add(dx, dy, dz);
            for (Foot ft : feet) { ft.at.add(dx, dy, dz); ft.planted = false; ft.swinging = false; }
            for (Vector3d v : palp) v.add(dx, dy, dz);
            lastBox.set(in.x, in.y, in.z);
            return;
        }
        lastBox.set(in.x, in.y, in.z);

        // the moment's pose, eased
        crouch += (clamp01(in.crouch) - crouch) * (in.crouch > crouch ? 0.4 : 0.2);
        brace += (clamp01(in.brace) - brace) * 0.15;
        fangs += (clamp01(in.fangs) - fangs) * (in.fangs > fangs ? 0.5 : 0.25);
        tense += (clamp01(in.tense) - tense) * 0.2;
        run += ((in.hurry ? 1.0 : 0.0) - run) * 0.3;

        frameTick(rock, in);
        feet(rock, in);
        abdomenTick(rock);
        palps(rock, in);
    }

    /** How far out from the rock the body rides now (crouched lower, braced closer). */
    double rideNow() {
        return spec.ride * (1.0 - (1.0 - CROUCH_RIDE) * crouch) * (1.0 - (1.0 - BRACE_RIDE) * brace);
    }

    /** How far out its feet rest now (crouched and braced, drawn in). */
    private double spreadNow() {
        return (1.0 - (1.0 - CROUCH_IN) * crouch) * (1.0 - (1.0 - BRACE_IN) * brace);
    }

    // ------------------------------------------------------------------------------------------------ the frame

    /** Its way out, its heading and its place: from the rock under it, the way it heads and its box. */
    private void frameTick(TerrainPatch rock, Drive in) {
        // its way out: the rock's round its box (in the air, upright, pitched along its flight)
        Vector3d want = t0;
        boolean felt = false;
        if (mode == Mode.SURFACE && rock.field(in.x, in.y, in.z, REACH, SOFT, fld) && fld[0] < spec.ride + 1.2) {
            want.set(fld[1], fld[2], fld[3]);
            felt = true;
        }
        if (!felt) {
            if (mode == Mode.SURFACE && in.up.lengthSquared() > 0.5) want.set(in.up).normalize();
            else if (mode == Mode.AIR) {
                // flying: upright, tipped back along its rise and forward along its fall
                want.set(0, 1, 0);
                double hv = Math.sqrt(in.velocity.x * in.velocity.x + in.velocity.z * in.velocity.z);
                if (hv > 0.05) {
                    double pitch = Math.max(-0.7, Math.min(0.5, -Math.atan2(in.velocity.y, hv) * 0.6));
                    want.set(in.velocity.x / hv * Math.sin(pitch), Math.cos(pitch), in.velocity.z / hv * Math.sin(pitch));
                }
            } else want.set(u);
        }
        easeUp(want, mode == Mode.AIR ? 0.2 : UP_EASE);
        // its heading, along its rock
        Vector3d h = t1.set(in.heading);
        if (h.lengthSquared() < 1.0E-6) h.set(f);
        h.normalize();
        h.fma(-h.dot(u), u);
        if (h.lengthSquared() < 0.04) {
            // heading straight into or out of the rock: keep the way it faced
            h.set(f).fma(-f.dot(u), u);
            if (h.lengthSquared() < 1.0E-6) h.set(Math.abs(u.y) < 0.9 ? 0 : 1, Math.abs(u.y) < 0.9 ? 1 : 0, 0).fma(-u.y, u);
        }
        h.normalize();
        turnToward(h);
        // its place: with its box (which the game keeps at its ride out from the rock), sunk by its crouch, and held
        // off the rock where the box cuts a corner between the game's updates
        double drop = spec.ride - rideNow();
        Vector3d target = t2.set(in.x, in.y, in.z).fma(-drop, u);
        if (mode == Mode.SURFACE) {
            double moved = target.distance(p);
            double bob = 0.025 * spec.scale * Math.min(1.0, speed / 0.06) * Math.cos(4.0 * Math.PI * phase) * (1.0 - 0.6 * mix);
            target.fma(bob, u);
            p.lerp(target, moved > 1.2 ? 1.0 : 0.55);
            if (rock.field(p.x, p.y, p.z, REACH, SOFT, fld) && fld[0] < spec.ride + 0.6) {
                double m = rideNow() - fld[0];
                if (m > 0) p.add(fld[1] * m * 0.6, fld[2] * m * 0.6, fld[3] * m * 0.6);
            }
        } else p.lerp(target, 0.7);
        u.cross(f, l).normalize();
    }

    /** Turns its way out toward {@code want} by a share, keeping its heading square to it. */
    private void easeUp(Vector3d want, double rate) {
        if (u.dot(want) < -0.9) {
            // straight over: roll round its heading
            u.fma(0.35, l).normalize();
        }
        u.lerp(want, rate);
        if (u.lengthSquared() < 1.0E-8) u.set(want);
        u.normalize();
        f.fma(-f.dot(u), u);
        if (f.lengthSquared() < 1.0E-6) f.set(l).cross(u);
        f.normalize();
    }

    /** Turns its heading toward {@code h} (unit, along its rock), at most {@link #MAX_TURN} a tick. */
    private void turnToward(Vector3d h) {
        double c = Math.max(-1.0, Math.min(1.0, f.dot(h)));
        double ang = Math.acos(c);
        if (ang < 1.0E-5) { f.set(h); return; }
        double step = Math.min(MAX_TURN * (0.6 + 0.6 * run), ang * TURN_EASE + 0.01);
        if (step >= ang) { f.set(h); return; }
        // rotate f about u toward h
        double side = t3.set(f).cross(h).dot(u) >= 0 ? 1.0 : -1.0;
        double cs = Math.cos(step * side), sn = Math.sin(step * side);
        t3.set(u).cross(f);
        f.mul(cs).fma(sn, t3).normalize();
    }

    // ------------------------------------------------------------------------------------------------ laying out

    /** Laid out afresh where its box is: on the rock under it, its feet planted, its abdomen and feelers at rest. */
    private void lay(TerrainPatch rock, Drive in) {
        laid = true;
        lastBox.set(in.x, in.y, in.z);
        crouch = crouchPrev = clamp01(in.crouch);
        brace = bracePrev = clamp01(in.brace);
        fangs = fangsPrev = clamp01(in.fangs);
        run = runPrev = in.hurry ? 1 : 0;
        if (in.mode == Mode.SURFACE && rock.field(in.x, in.y, in.z, REACH, SOFT, fld)) u.set(fld[1], fld[2], fld[3]);
        else if (in.up.lengthSquared() > 0.5) u.set(in.up).normalize();
        else u.set(0, 1, 0);
        f.set(in.heading);
        f.fma(-f.dot(u), u);
        if (f.lengthSquared() < 1.0E-6) f.set(Math.abs(u.y) < 0.9 ? 0 : 1, Math.abs(u.y) < 0.9 ? 1 : 0, 0).fma(-u.y, u);
        f.normalize();
        u.cross(f, l).normalize();
        p.set(in.x, in.y, in.z).fma(-(spec.ride - rideNow()), u);
        for (Foot ft : feet) {
            rest(ft, ft.ideal);
            ft.idealPrev.set(ft.ideal);
            if (in.mode == Mode.SURFACE && target(rock, ft, ft.ideal, 0.0, t1, t2)) plant(ft, t1, t2);
            else { air(ft, ft.ideal); ft.at.set(ft.ideal); ft.nrm.set(u); ft.planted = false; }
            ft.swinging = false;
            ft.prevAt.set(ft.at); ft.prevNrm.set(ft.nrm);
        }
        restAbdomen(ab);
        abPrev.set(ab); abOld.set(ab);
        for (int i = 0; i < 2; i++) {
            palpRest(i, palp[i]);
            palpPrev[i].set(palp[i]);
            palpGoal[i].set(palp[i]);
        }
        pPrev.set(p); fPrev.set(f); uPrev.set(u);
        phasePrev = phase; speedPrev = speed = 0; mixPrev = mix = running ? 1 : 0;
        standing = 0;
    }

    // ------------------------------------------------------------------------------------------------ feet

    /** One of its twelve feet: where it stands and on what. */
    public static final class Foot {
        public final int index, pair;
        public final boolean left;
        /** On rock and staying there. */
        public boolean planted;
        /** Where the foot is (on rock when planted), and the way out of that rock. */
        public final Vector3d at = new Vector3d(), nrm = new Vector3d(0, 1, 0);
        public final Vector3d prevAt = new Vector3d(), prevNrm = new Vector3d(0, 1, 0);
        final Vector3d from = new Vector3d(), fromN = new Vector3d(0, 1, 0), to = new Vector3d(), toN = new Vector3d(0, 1, 0);
        final Vector3d toPrev = new Vector3d(), toNPrev = new Vector3d(0, 1, 0), liftUp = new Vector3d(0, 1, 0);
        final Vector3d ideal = new Vector3d(), idealPrev = new Vector3d();
        double t, step, lift, tPrev;
        boolean swinging, swung, toRock;
        /** A tap in place (stirring): the foot lifts and comes down where it was. */
        boolean tap;
        private final Vector3d sTo = new Vector3d(), sToN = new Vector3d();

        Foot(int index) {
            this.index = index;
            this.pair = LurkerSpec.pair(index);
            this.left = LurkerSpec.left(index);
        }

        /** Where the foot is part way through the tick: along its step's arc while it steps, else between ticks. */
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

        public boolean swinging() { return swinging || swung; }

        /**
         * A step's arc: eased from {@code from} to {@code to}, lifted along the two rocks' blended way out (and the
         * body's, {@code up}): higher over a corner, from a floor to a wall, so the foot clears the edge. A long leg's
         * foot rises quickly and comes down softly.
         */
        static void arc(Vector3d from, Vector3d fromN, Vector3d to, Vector3d toN, double lift, double t, Vector3d up, Vector3d p, Vector3d n) {
            double e = t * t * (3.0 - 2.0 * t);
            double mx = fromN.x + toN.x + up.x, my = fromN.y + toN.y + up.y, mz = fromN.z + toN.z + up.z;
            double ml = Math.sqrt(mx * mx + my * my + mz * mz);
            if (ml < 1.0E-3) { mx = up.x; my = up.y; mz = up.z; ml = 1.0; }
            double h = Math.sin(Math.PI * Math.pow(t, 0.75)) * lift * (1.0 + Math.max(0.0, 0.6 - fromN.dot(toN)));
            p.set(from.x + (to.x - from.x) * e + mx / ml * h, from.y + (to.y - from.y) * e + my / ml * h, from.z + (to.z - from.z) * e + mz / ml * h);
            n.set(fromN).lerp(toN, e);
            if (n.lengthSquared() < 1.0E-8) n.set(up);
            n.normalize();
        }
    }

    /** Where a foot rests now: out round its own way from the centre, on the rock a ride under the body. */
    void rest(Foot ft, Vector3d out) {
        double a = spec.restAngle[ft.index], r = spec.restRadius[ft.index] * spreadNow();
        double side = ft.left ? 1 : -1;
        out.set(p).fma(r * Math.cos(a), f).fma(side * r * Math.sin(a), l).fma(-rideNow(), u);
    }

    /** The coxa's root on the prosoma's rim. */
    void coxaRoot(int leg, Vector3d out) {
        double[] h = spec.hip[leg];
        out.set(p).fma(h[0], f).fma(h[1], u).fma(h[2], l);
    }

    /** The way a leg's coxa points: out round its rest way, a little down. */
    void coxaDir(int leg, Vector3d out) {
        double a = spec.restAngle[leg], side = LurkerSpec.left(leg) ? 1 : -1;
        out.set(f).mul(Math.cos(a)).fma(side * Math.sin(a), l).fma(-0.25, u).normalize();
    }

    /** A leg's hip: the tip of its coxa, where the femur turns. */
    void hip(int leg, Vector3d out) {
        coxaRoot(leg, out);
        coxaDir(leg, t5);
        out.fma(spec.coxa[leg], t5);
    }

    /** A foot's ankle over where it stands (see {@link #ankleOf}). */
    void ankle(int leg, Vector3d foot, Vector3d n, Vector3d out) {
        ankleOf(spec.meta[leg], p, u, foot, n, out);
    }

    /**
     * A foot's ankle, a metatarsus' length {@code m} from the foot: on rock that faces the way the body's back does
     * ({@code up}) the metatarsus stands up from the foot and leans in toward the body's centre; on rock turned from it
     * (the top of the rim it hooks over from its chimney, a wall ahead of it) it lies low along the rock toward the
     * body, the foot's tip hooked over.
     */
    static void ankleOf(double m, Vector3d centre, Vector3d up, Vector3d foot, Vector3d n, Vector3d out) {
        // in: from the foot toward the body's centre, along the foot's rock
        double ix = centre.x - foot.x, iy = centre.y - foot.y, iz = centre.z - foot.z;
        double in = ix * n.x + iy * n.y + iz * n.z;
        ix -= n.x * in; iy -= n.y * in; iz -= n.z * in;
        double il = Math.sqrt(ix * ix + iy * iy + iz * iz);
        if (il > 1.0E-6) { ix /= il; iy /= il; iz /= il; }
        double c = Math.max(0.0, Math.min(1.0, n.x * up.x + n.y * up.y + n.z * up.z));
        double stand = 0.3 + 0.54 * c, lean = 0.85 - 0.35 * c;
        double dx = n.x * stand + ix * lean, dy = n.y * stand + iy * lean, dz = n.z * stand + iz * lean;
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0E-6) { dx = n.x; dy = n.y; dz = n.z; dl = 1.0; }
        double k = m * 0.98 / dl;
        out.set(foot.x + dx * k, foot.y + dy * k, foot.z + dz * k);
    }

    private void feet(TerrainPatch rock, Drive in) {
        double moved = 0;
        for (Foot ft : feet) {
            ft.idealPrev.set(ft.ideal);
            rest(ft, ft.ideal);
            moved += ft.ideal.distance(ft.idealPrev);
        }
        moved = Math.min(0.7, moved / feet.length);
        speed += (moved - speed) * 0.3;
        boolean want = in.hurry || speed > (running ? WALK_FROM : RUN_FROM) * spec.scale;
        running = want;
        double stride = running ? spec.runStride : spec.walkStride;
        double duty = running ? RUN_DUTY : WALK_DUTY;
        double before = phase;
        phase += moved / stride;
        double mixStep = Math.max(0.06, (phase - before) * 1.5);
        mix += Math.max(-mixStep, Math.min(mixStep, (running ? 1.0 : 0.0) - mix));
        standing = moved < 0.004 ? standing + 1 : 0;

        if (mode == Mode.AIR) {
            for (Foot ft : feet) { ft.planted = false; ft.swinging = false; air(ft, t1); ft.at.lerp(t1, 0.4); ft.nrm.set(u); }
            return;
        }
        boolean anySwing = false;
        for (Foot ft : feet) anySwing |= ft.swinging;
        for (Foot ft : feet) {
            if (ft.swinging) {
                swing(rock, ft, stride, duty);
                continue;
            }
            if (raised(ft)) {
                // crouched to leap: the front legs come up off the rock, high and spread before it
                raisedPose(ft, t1);
                if (ft.planted) { ft.planted = false; ft.from.set(ft.at); }
                ft.at.lerp(t1, 0.35);
                ft.nrm.set(u);
                continue;
            }
            if (!ft.planted) {
                // back on the rock (after a leap, a fall, a crouch): down at once if rock is in reach, front feet first
                if (target(rock, ft, ft.ideal, 0.0, t1, t2)) begin(rock, ft, 3 + ft.pair / 2, stride, duty, 0.18 * spec.scale);
                else { air(ft, t1); ft.at.lerp(t1, 0.3); ft.nrm.set(u); }
                continue;
            }
            double offset = lerpPhase(WALK_OFFSET[ft.index], RUN_OFFSET[ft.index], mix);
            double a = Math.floor(before - offset - duty), b = Math.floor(phase - offset - duty);
            boolean turn = b > a && moved > 0.004;
            boolean lost = overreached(ft) || !standsOn(rock, ft.at, ft.nrm);
            if (turn || lost) {
                int ticksFor = lost && !turn ? 3 : stepTicks(stride, duty);
                begin(rock, ft, ticksFor, stride, duty, (running ? spec.runLift : spec.walkLift) * (lost && !turn ? 0.6 : 1.0));
                anySwing = true;
            }
        }
        // standing: one foot at a time steps back under where it rests (braced in its chimney, the front feet over the rim)
        if (standing > 5 && !anySwing) {
            Foot worst = null;
            double most = SETTLE_FOOT * spec.scale * (brace > 0.5 ? 0.6 : 1.0);
            for (Foot ft : feet) {
                if (!ft.planted || raised(ft)) continue;
                if (!target(rock, ft, ft.ideal, 0.0, t1, t2)) continue;
                double d = t1.distance(ft.at);
                if (d > most) { most = d; worst = ft; }
            }
            if (worst != null) begin(rock, worst, 6, stride, duty, 0.25 * spec.scale);
            else if (tense > 0.25 && rand(40) < 0.07 * tense) {
                // stirring: a front foot lifts and taps down again where it was
                Foot ft = feet[(int) (rand(41) * 4)];
                if (ft.planted) {
                    begin(rock, ft, 4, stride, duty, 0.12 * spec.scale);
                    ft.to.set(ft.from); ft.toN.set(ft.fromN); ft.toRock = true; ft.tap = true;
                }
            }
        }
    }

    /** Whether a foot is held up off the rock: the front pair, crouched to leap. */
    private boolean raised(Foot ft) { return ft.pair == 0 && crouch > 0.45; }

    /** The front legs raised in its crouch: high over its head and spread. */
    private void raisedPose(Foot ft, Vector3d out) {
        double side = ft.left ? 1 : -1;
        out.set(p).fma(1.35 * spec.scale, f).fma(side * 1.15 * spec.scale, l).fma(1.25 * spec.scale, u);
    }

    /** A foot's place in the air (leaping, falling): the front legs reach ahead, the middle ones out, the back ones trail. */
    private void air(Foot ft, Vector3d out) {
        double a = spec.restAngle[ft.index], r = spec.restRadius[ft.index] * 0.82;
        double side = ft.left ? 1 : -1;
        double ahead = ft.pair < 2 ? 0.55 : ft.pair > 3 ? -0.35 : 0.0;
        out.set(p).fma(r * Math.cos(a) + ahead * spec.scale, f).fma(side * r * Math.sin(a) * 0.9, l)
                .fma((ft.pair < 2 ? -0.1 : -0.45) * spec.scale, u);
    }

    private int stepTicks(double stride, double duty) {
        double s = Math.max(speed, 0.02);
        int t = (int) Math.round((1.0 - duty) * stride / s);
        return Math.max(STEP_MIN, Math.min(STEP_MAX, t));
    }

    /** Lifts a foot to step: from where it stands to where the gait will want it as it lands. */
    private void begin(TerrainPatch rock, Foot ft, int ticksFor, double stride, double duty, double lift) {
        ft.from.set(ft.at);
        ft.fromN.set(ft.nrm);
        ft.swinging = true;
        ft.planted = false;
        ft.tap = false;
        ft.t = 0.0;
        ft.step = 1.0 / ticksFor;
        ft.lift = lift;
        aim(rock, ft, ticksFor, stride, duty, true);
        ft.toPrev.set(ft.to);
        ft.toNPrev.set(ft.toN);
    }

    /** Where a step lands: ahead of where the foot rests by half the ground it covers while down, on what rock is there. */
    private void aim(TerrainPatch rock, Foot ft, double ticksLeft, double stride, double duty, boolean fresh) {
        if (ft.tap) return;
        double vx = ft.ideal.x - ft.idealPrev.x, vy = ft.ideal.y - ft.idealPrev.y, vz = ft.ideal.z - ft.idealPrev.z;
        // along its rock only
        double vu = vx * u.x + vy * u.y + vz * u.z;
        vx -= u.x * vu; vy -= u.y * vu; vz -= u.z * vu;
        double v = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double cap = MAX_AIM_SPEED * spec.scale;
        if (v > cap) { vx *= cap / v; vy *= cap / v; vz *= cap / v; v = cap; }
        Vector3d aimAt = t4.set(ft.ideal);
        if (v > 0.004) {
            aimAt.add(vx * ticksLeft, vy * ticksLeft, vz * ticksLeft);
            double ahead = Math.min(duty * stride * 0.5, spec.reach(ft.index) * MAX_AHEAD);
            aimAt.add(vx / v * ahead, vy / v * ahead, vz / v * ahead);
        }
        double lead = v * ticksLeft;
        boolean found = target(rock, ft, aimAt, lead, t1, t2);
        if (!found && !fresh && ft.toRock && standsOn(rock, ft.to, ft.toN)) return;
        if (!found) found = target(rock, ft, ft.ideal, 0.0, t1, t2);
        if (!found && standsOn(rock, ft.from, ft.fromN)) { t1.set(ft.from); t2.set(ft.fromN); found = true; }
        if (found) {
            boolean same = !fresh && ft.toRock && ft.toN.dot(t2) > 0.95 && ft.to.distanceSquared(t1) < 0.3 * 0.3;
            ft.toRock = true;
            if (same) { ft.to.lerp(t1, 0.5); ft.toN.lerp(t2, 0.5).normalize(); }
            else { ft.to.set(t1); ft.toN.set(t2); }
        } else {
            ft.toRock = false;
            if (fresh) { ft.to.set(aimAt); ft.toN.set(u); }
            else { ft.to.lerp(aimAt, 0.5); ft.toN.lerp(u, 0.5).normalize(); }
        }
    }

    private void swing(TerrainPatch rock, Foot ft, double stride, double duty) {
        ft.t = Math.min(1.0, ft.t + ft.step);
        ft.swung = true;
        double left = (1.0 - ft.t) / ft.step;
        if (ft.t < 0.8) aim(rock, ft, left, stride, duty, false);
        ft.liftUp.set(u);
        Foot.arc(ft.from, ft.fromN, ft.to, ft.toN, ft.lift, ft.t, u, ft.at, ft.nrm);
        // the foot never passes through rock on its way: a step over an edge lifts higher until it clears
        if (ft.t < 0.9 && rock.solid(ft.at.x + ft.nrm.x * 0.02, ft.at.y + ft.nrm.y * 0.02, ft.at.z + ft.nrm.z * 0.02)) {
            ft.lift += 0.2 * spec.scale;
            Foot.arc(ft.from, ft.fromN, ft.to, ft.toN, ft.lift, ft.t, u, ft.at, ft.nrm);
        }
        if (ft.t >= 1.0) {
            ft.swinging = false;
            boolean tapped = ft.tap;
            ft.tap = false;
            if (!ft.toRock || !standsOn(rock, ft.to, ft.toN)) {
                boolean found = target(rock, ft, ft.to, 0.0, t1, t2) || target(rock, ft, ft.ideal, 0.0, t1, t2);
                ft.toRock = found && t1.distanceSquared(ft.to) < 0.6 * 0.6;
                if (ft.toRock) { ft.to.set(t1); ft.toN.set(t2); }
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
    }

    /** Whether a foot at p (out of its rock along n) stands on rock. */
    private static boolean standsOn(TerrainPatch rock, Vector3d p, Vector3d n) {
        return rock.solid(p.x - n.x * 0.06, p.y - n.y * 0.06, p.z - n.z * 0.06);
    }

    private boolean overreached(Foot ft) {
        hip(ft.index, t3);
        ankle(ft.index, ft.at, ft.nrm, t0);
        return t0.distanceSquared(t3) > Math.pow(spec.reach(ft.index) * OVERREACH, 2);
    }

    /**
     * Rock a foot can stand on near {@code near} (where it rests on the rock under the body): out, p, and the way out
     * of that rock, n. In order: the face under it (down from over it, along the body's way out); with rock where it
     * would stand, the face in the way of its reach from the hip (a wall beside it, the curved wall of a narrow flue);
     * past an edge, the rock reached round it (the face under an edge, or the top of a wall it climbs toward: braced in
     * its chimney the front legs reach for that first, to hook over the rim); a ring of places round it; and last a
     * drop under it. Each must lie within the leg's reach of its hip (as it will be {@code lead} further on), in the
     * leg's own room round the body, and face the body or its way out.
     */
    private boolean target(TerrainPatch rock, Foot ft, Vector3d near, double lead, Vector3d out, Vector3d n) {
        int i = ft.index;
        Vector3d h = t3;
        hip(i, h);
        if (lead > 0) {
            double vx = near.x - ft.ideal.x, vy = near.y - ft.ideal.y, vz = near.z - ft.ideal.z;
            double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vl > 1.0E-6) h.add(vx / vl * Math.min(lead, vl), vy / vl * Math.min(lead, vl), vz / vl * Math.min(lead, vl));
        }
        // its way out from the body, along the rock
        double ox = near.x - p.x, oy = near.y - p.y, oz = near.z - p.z;
        double ou = ox * u.x + oy * u.y + oz * u.z;
        ox -= u.x * ou; oy -= u.y * ou; oz -= u.z * ou;
        double ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (ol < 1.0E-6) return false;
        ox /= ol; oy /= ol; oz /= ol;
        double nx = near.x, ny = near.y, nz = near.z;
        boolean hook = brace > 0.5 && ft.pair < 2;
        if (hook && overEdge(rock, i, h, nx, ny, nz, ox, oy, oz, out, n)) return true;
        // 1. the face under it
        if (cast(rock, i, h, nx + u.x * 0.9, ny + u.y * 0.9, nz + u.z * 0.9, nx - u.x * 1.3, ny - u.y * 1.3, nz - u.z * 1.3, out, n)) return true;
        // 2. a wall in the way of its reach
        if (rock.solid(nx + u.x * 0.4, ny + u.y * 0.4, nz + u.z * 0.4) || rock.solid(nx + u.x * 0.9, ny + u.y * 0.9, nz + u.z * 0.9)) {
            double ex = h.x + (nx + u.x * 0.3 - h.x) * 1.3, ey = h.y + (ny + u.y * 0.3 - h.y) * 1.3, ez = h.z + (nz + u.z * 0.3 - h.z) * 1.3;
            if (cast(rock, i, h, h.x, h.y, h.z, ex, ey, ez, out, n)) return true;
        }
        // 3. round an edge
        if (!hook && overEdge(rock, i, h, nx, ny, nz, ox, oy, oz, out, n)) return true;
        // 4. a ring of places round it, nearest first
        for (double r : RING_R) {
            for (int a = 0; a < RING_N; a++) {
                double ang = 2.0 * Math.PI * a / RING_N + 0.3;
                double ca = Math.cos(ang) * r * spec.scale, sa = Math.sin(ang) * r * spec.scale;
                double qx = nx + f.x * ca + l.x * sa, qy = ny + f.y * ca + l.y * sa, qz = nz + f.z * ca + l.z * sa;
                if (cast(rock, i, h, qx + u.x * 0.9, qy + u.y * 0.9, qz + u.z * 0.9, qx - u.x * 1.3, qy - u.y * 1.3, qz - u.z * 1.3, out, n))
                    return true;
            }
        }
        // 5. a drop under it
        return cast(rock, i, h, nx + u.x * 0.2, ny + u.y * 0.2, nz + u.z * 0.2, nx - u.x * 2.6, ny - u.y * 2.6, nz - u.z * 2.6, out, n);
    }

    private static final double[] RING_R = {0.4, 0.8};
    private static final int RING_N = 8;

    /**
     * Past an edge: the rock reached round it, inward toward the body from just beyond where the foot would rest and a
     * little under the body's rock (the face under an edge it stands near; or, climbing a wall toward its top, the top
     * itself, reached down onto from over it).
     */
    private boolean overEdge(TerrainPatch rock, int i, Vector3d h, double nx, double ny, double nz, double ox, double oy, double oz,
                             Vector3d out, Vector3d n) {
        double bx = nx - u.x * 0.7 + ox * 0.45, by = ny - u.y * 0.7 + oy * 0.45, bz = nz - u.z * 0.7 + oz * 0.45;
        return cast(rock, i, h, bx, by, bz, bx - ox * 1.9, by - oy * 1.9, bz - oz * 1.9, out, n);
    }

    /**
     * The first rock on the line from a to b, kept for leg {@code i} (hip at h) if it is a face the body could stand a
     * foot on: facing the body or its way out, within the leg's reach (and not folded tighter than the leg folds), and in
     * the leg's own room round the body.
     */
    private boolean cast(TerrainPatch rock, int i, Vector3d h, double ax, double ay, double az, double bx, double by, double bz,
                         Vector3d out, Vector3d n) {
        if (rock.solid(ax, ay, az)) return false;
        if (!rock.clip(ax, ay, az, bx, by, bz, hit) || hit[4] > 0.5) return false;
        double tt = hit[0];
        double hx = ax + (bx - ax) * tt, hy = ay + (by - ay) * tt, hz = az + (bz - az) * tt;
        double fnx = hit[1], fny = hit[2], fnz = hit[3];
        boolean facesOut = fnx * u.x + fny * u.y + fnz * u.z > -0.45;
        boolean facesBody = fnx * (p.x - hx) + fny * (p.y - hy) + fnz * (p.z - hz) > 0.05;
        if (!facesOut && !facesBody) return false;
        // in its own room round the body: on its own side, between its neighbours' ways
        double rx = hx - p.x, ry = hy - p.y, rz = hz - p.z;
        double along = rx * f.x + ry * f.y + rz * f.z, across = (rx * l.x + ry * l.y + rz * l.z) * (LurkerSpec.left(i) ? 1 : -1);
        if (across < 0.25 * spec.scale) return false;
        double ang = Math.atan2(across, along);
        if (Math.abs(ang - spec.restAngle[i]) > spec.sector + (brace > 0.5 ? 0.12 : 0.0)) return false;
        // within reach
        t0.set(hx, hy, hz);
        t4.set(fnx, fny, fnz);
        ankle(i, t0, t4, t1);
        double d = t1.distance(h);
        if (d > spec.reach(i) * 0.97) return false;
        if (d < Math.abs(spec.tibia[i] - spec.femur[i]) + 0.15 * spec.scale) return false;
        out.set(hx, hy, hz);
        n.set(fnx, fny, fnz);
        return true;
    }

    private static double lerpPhase(double a, double b, double t) {
        double d = b - a;
        d -= Math.floor(d + 0.5);
        return a + d * t;
    }

    // ------------------------------------------------------------------------------------------------ the abdomen

    /** The abdomen's way at rest: back from the waist, raised a little over the prosoma's back. */
    private void restAbdomen(Vector3d out) {
        double a = ABDOMEN_RISE * (1.0 - 0.6 * crouch);
        out.set(f).mul(-Math.cos(a)).fma(Math.sin(a), u).normalize();
    }

    /** The waist: where the abdomen hangs from the prosoma's back. */
    void waist(Vector3d out) {
        out.set(p).fma(-spec.waistBack, f).fma(spec.waistUp, u);
    }

    /**
     * The abdomen: heavy on its waist, it swings after the body's turns and sways with its steps, sags a little toward
     * the ground on a wall, and never into the rock (it rises off it).
     */
    private void abdomenTick(TerrainPatch rock) {
        restAbdomen(t0);
        // its weight: it hangs a little toward the ground (on a wall more, on a floor it rests up on its waist)
        t0.fma(ABDOMEN_SAG * (1.0 - Math.abs(u.y)), t1.set(0, -1, 0)).normalize();
        // inertia: it keeps some of its swing, and is pulled back toward rest
        t2.set(abPrev).sub(abOld).mul(ABDOMEN_KEEP);
        ab.set(abPrev).add(t2).lerp(t0, ABDOMEN_PULL);
        // its steps sway it from side to side
        double sway = 0.045 * Math.min(1.0, speed / 0.06) * Math.sin(2.0 * Math.PI * phase) * (1.0 - 0.5 * mix);
        ab.fma(sway, l);
        if (ab.lengthSquared() < 1.0E-8) ab.set(t0);
        ab.normalize();
        // never into the rock: its middle and tip lifted off it, toward the body's way out
        waist(t3);
        for (int it = 0; it < 6; it++) {
            double half = spec.abdomen * 0.55, tip = spec.abdomen * 0.95;
            boolean in = rock.solid(t3.x + ab.x * half - u.x * 0.35, t3.y + ab.y * half - u.y * 0.35, t3.z + ab.z * half - u.z * 0.35)
                    || rock.solid(t3.x + ab.x * tip - u.x * 0.2, t3.y + ab.y * tip - u.y * 0.2, t3.z + ab.z * tip - u.z * 0.2);
            if (!in) break;
            ab.fma(0.12, u).normalize();
        }
    }

    // ------------------------------------------------------------------------------------------------ the feelers

    /** A feeler's tip at rest: ahead and a little out, down toward the rock before its fangs. */
    private void palpRest(int i, Vector3d out) {
        double side = i == 0 ? 1 : -1;
        out.set(p).fma(spec.palpForward + 0.42 * spec.scale, f).fma(side * (spec.palpSide + 0.1 * spec.scale), l)
                .fma(-spec.ride * 0.55, u);
    }

    /**
     * The feelers: they rest ahead of the fangs, feel about now and then (more when it is stirring), tap the rock ahead
     * in turn as it walks, and are held up and out when it crouches to leap.
     */
    private void palps(TerrainPatch rock, Drive in) {
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? 1 : -1;
            Vector3d goal = palpGoal[i];
            if (--palpTimer[i] <= 0) {
                palpTimer[i] = (int) (12 + rand(50 + i) * (40 - 25 * tense));
                palpRest(i, goal);
                double jitter = 0.18 * spec.scale * (0.5 + tense);
                goal.fma((rand(52 + i) - 0.3) * jitter, f).fma((rand(54 + i) - 0.5) * jitter, l).fma((rand(56 + i) - 0.2) * jitter, u);
            }
            Vector3d want = t1.set(goal);
            if (speed > 0.02 && mode == Mode.SURFACE) {
                // walking: each taps down ahead in turn
                double ph = phase * 2.0 + i * 0.5;
                double lift = Math.max(0.0, Math.sin(2.0 * Math.PI * ph));
                palpRest(i, want);
                want.fma(0.12 * spec.scale * lift, u).fma(0.08 * spec.scale * Math.cos(2.0 * Math.PI * ph), f);
            }
            if (crouch > 0.05) {
                t2.set(p).fma(spec.palpForward + 0.3 * spec.scale, f).fma(side * (spec.palpSide + 0.25 * spec.scale), l).fma(0.25 * spec.scale, u);
                want.lerp(t2, crouch);
            }
            if (mode == Mode.AIR) {
                t2.set(p).fma(spec.palpForward + 0.5 * spec.scale, f).fma(side * spec.palpSide, l);
                want.lerp(t2, 0.8);
            }
            palp[i].lerp(want, 0.35);
            // within the feeler's length of its root
            palpRoot(i, t2);
            t3.set(palp[i]).sub(t2);
            double len = t3.length(), max = (spec.palpFemur + spec.palpTibia) * 0.96;
            if (len > max) palp[i].set(t2).fma(max / len, t3);
            // never in the rock
            if (rock.solid(palp[i].x, palp[i].y, palp[i].z)) palp[i].fma(0.15, u);
        }
    }

    /** A feeler's root beside the fangs. */
    void palpRoot(int i, Vector3d out) {
        double side = i == 0 ? 1 : -1;
        out.set(p).fma(spec.palpForward, f).fma(-spec.palpDown, u).fma(side * spec.palpSide, l);
    }

    /** A fang's base under the front of the head. */
    void cheliceraRoot(int i, Vector3d out) {
        double side = i == 0 ? 1 : -1;
        out.set(p).fma(spec.cheliceraForward, f).fma(-spec.cheliceraDown, u).fma(side * spec.cheliceraSide, l);
    }

    // ------------------------------------------------------------------------------------------------ small things

    /** A number from 0 to 1 that changes every tick, its own for each body and each use. */
    private double rand(int use) {
        long h = seed + ticks * 0x9E3779B97F4A7C15L + use * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
