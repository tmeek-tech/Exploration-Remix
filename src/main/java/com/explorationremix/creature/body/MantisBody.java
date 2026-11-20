package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * The stilt mantis's body as it stands on the rock (0.20.0, Taran's ask: long skinny legs that land on real blocks). A
 * stiff thorax rides high between four long stilts; each foot is set on the top of a real block (a floor, a step, a
 * slab: never the air, never a wall's face) and stays there while the body moves over it, until the gait lifts it and
 * swings it on to the next place it can stand. The knees arch high over the body, as a crane fly's or a harvestman's
 * do. The thorax's height, pitch and roll follow its feet, so it walks up steps and over rubble on its legs while its
 * box pops up a step at a time. Its long abdomen and its antennae sway after it on springs; its head turns in quick
 * flicks and holds, as a mantis's does; its folded grabbing arms pick at the floor in turn while it grazes.
 *
 * <p>Plain numbers and JOML, no game classes: the entity drives it ({@link Drive}) and hands it the rock
 * ({@link TerrainPatch}) each client tick, the pose ({@link MantisPose}) draws it, and the offline clips walk it the
 * same way.
 */
public final class MantisBody {
    public enum Mode { GROUND, AIR, DEAD }

    /** What moves the body this tick: from the entity. */
    public static final class Drive {
        /** Where its box stands: the middle of the box's bottom. */
        public double x, y, z;
        /** The way its body faces and the way its head looks (the game's yaw in radians: 0 south, growing west), and how far its head looks down. */
        public double bodyYaw, headYaw, headPitch;
        public Mode mode = Mode.GROUND;
        /** 0 to 1: how low it crouches to graze, how tall it stands on watch. */
        public double crouch, alert;
        /** It runs (the trot). */
        public boolean hurry;
        /** Grazing: crouched, its arms pick at the floor in turn. */
        public boolean graze;
        /** Its startle display: arms raised and spread, wings flared. */
        public boolean display;
        /** Watching something: it tilts its head. */
        public boolean watch;
    }

    /** Hears each foot as it lands. */
    public interface Footfalls {
        void land(Foot foot, boolean running, double speed);
    }

    // ------------------------------------------------------------------------------------------------ tuning
    /** Further than this between ticks (blocks), it was put somewhere new: laid out afresh there. */
    static final double JUMP = 3.0;
    /** The feet's timing: walking, the hind left, middle left, hind right, middle right in turn; running, diagonal pairs. */
    static final double[] WALK_OFFSET = {0.25, 0.75, 0.0, 0.5}, RUN_OFFSET = {0.5, 0.0, 0.0, 0.5};
    /** The share of a stride each foot is down, walking and running. */
    static final double WALK_DUTY = 0.75, RUN_DUTY = 0.5;
    /** It breaks into a run above this speed, and back to a walk under the second (blocks a tick). */
    static final double RUN_FROM = 0.2, WALK_FROM = 0.16;
    static final int STEP_MIN = 4, STEP_MAX = 13;
    /** Standing, a foot further than this from where it rests steps back to it (blocks, at scale 1). */
    static final double SETTLE_FOOT = 0.32;
    /** A planted foot further than this share of the leg's reach from its hip has been left behind: it steps at once. */
    static final double OVERREACH = 1.0;
    /** A step lands no further ahead than this share of the leg's reach. */
    static final double MAX_AHEAD = 0.35;
    static final double MAX_AIM_SPEED = 0.45;
    /** A foot stands only on rock whose way out is at least this much up (the top of a block, not a wall's face). */
    static final double FLOOR_UP = 0.55;
    /** Grazing, the body sinks this far (blocks, at scale 1) and the prothorax drops this far (radians). */
    static final double CROUCH_DROP = 0.55, CROUCH_PITCH = Math.toRadians(43);
    /** On watch it stands this much taller (blocks at scale 1) and lifts its prothorax this much. */
    static final double ALERT_RISE = 0.14, ALERT_PITCH = Math.toRadians(9);
    /** How far its pitch and roll follow the feet (shares), and their limits (radians). */
    static final double PITCH_FOLLOW = 0.8, ROLL_FOLLOW = 0.55, MAX_PITCH = Math.toRadians(24), MAX_ROLL = Math.toRadians(14);
    /** The abdomen's rest curve: each segment's rise over the thorax's back (radians), front to tip: it curls up at the tip. */
    static final double[] ABDOMEN_RISE = {Math.toRadians(-7), Math.toRadians(2), Math.toRadians(15), Math.toRadians(31)};
    /** How hard each abdomen joint is pulled back to its rest place a tick, and how much of its swing it keeps. */
    static final double[] ABDOMEN_STIFF = {0.5, 0.42, 0.33, 0.26};
    static final double ABDOMEN_KEEP = 0.82;
    /** The head: a turn bigger than this is a flick (fast); smaller ones it follows slowly. */
    static final double FLICK = Math.toRadians(11), FLICK_RATE = 0.62, TRACK_RATE = 0.16;
    /** How far its head may turn from the way its body faces (a mantis looks over its shoulder), and up or down. */
    static final double MAX_HEAD_YAW = Math.toRadians(105), MAX_HEAD_PITCH = Math.toRadians(65);
    /** Standing, its head looks this far down (it watches the ground ahead); grazing, this much more. */
    static final double HEAD_DOWN = Math.toRadians(12), GRAZE_HEAD_DOWN = Math.toRadians(38);
    /** The antennae: how hard their tips are pulled to rest, how much swing they keep, and how often one flicks. */
    static final double ANTENNA_PULL = 0.42, ANTENNA_KEEP = 0.62, ANTENNA_FLICK = 0.035;
    /** The grazing arms: ticks for both arms to pick once each. */
    static final double GRAZE_TICKS = 46;

    // ------------------------------------------------------------------------------------------------ shape
    public final MantisSpec spec;
    private final double k;

    // ------------------------------------------------------------------------------------------------ state
    private boolean laid;
    private final Vector3d lastBox = new Vector3d();
    /** The thorax's middle and its frame (forward, up), now and last tick. */
    final Vector3d p = new Vector3d(), pPrev = new Vector3d(), f = new Vector3d(0, 0, 1), fPrev = new Vector3d(0, 0, 1),
            u = new Vector3d(0, 1, 0), uPrev = new Vector3d(0, 1, 0);
    private double yaw, yawPrev, height, pitch, roll;
    private double crouch, crouchPrev, alert, alertPrev, display, displayPrev, wing, wingPrev, run, runPrev;
    private double phase, phasePrev, speed, speedPrev, mix, mixPrev;
    private boolean running;
    private int standing;
    private Mode mode = Mode.GROUND;
    private long ticks;
    private Footfalls footfalls;
    public final Foot[] feet = new Foot[4];
    /** The abdomen's joints, front to tip (the first rides the thorax), now, last tick and the tick before. */
    final Vector3d[] ab = vectors(5), abPrev = vectors(5), abOld = vectors(5);
    /** The head's turn from the way the body faces (the game's sense: right positive), its pitch, its tilt; now and last tick. */
    private double lookYaw, lookYawPrev, lookPitch, lookPitchPrev, tilt, tiltPrev, tiltGoal;
    private int tiltTimer;
    /** The antennae's tips, now, last tick and the tick before. */
    final Vector3d[] antTip = vectors(2), antTipPrev = vectors(2), antTipOld = vectors(2);
    /**
     * The arms: where each hand (the tibia's tip) is held and the way its elbow bends, in the thorax's frame (forward,
     * up, left) from its shoulder, and the coxa's way from the shoulder; now and last tick.
     */
    final Vector3d[] hand = vectors(2), handPrev = vectors(2), pole = vectors(2), polePrev = vectors(2), coxa = vectors(2),
            coxaPrev = vectors(2);
    private double grazeClock;
    private final Vector3d[] grazeAt = vectors(2);
    private final boolean[] grazeFound = new boolean[2];
    private final int[] grazePick = new int[2];
    private long seed = 0x5eed;

    // scratch
    private final double[] hit = new double[5];
    private final Vector3d t0 = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), t3 = new Vector3d(), t4 = new Vector3d();
    /** The level heading and its left (this tick), and the thorax's own left. */
    private final Vector3d fl = new Vector3d(), ll = new Vector3d(), lb = new Vector3d(1, 0, 0);
    private final Skeleton sk = new Skeleton();

    public MantisBody(MantisSpec spec) {
        this.spec = spec;
        this.k = spec.scale / 16.0;
        for (int i = 0; i < 4; i++) feet[i] = new Foot(i);
    }

    private static Vector3d[] vectors(int n) {
        Vector3d[] v = new Vector3d[n];
        for (int i = 0; i < n; i++) v[i] = new Vector3d();
        return v;
    }

    public void setFootfalls(Footfalls f) { this.footfalls = f; }
    /** Each body its own twitches and tilts (the entity's id, say). */
    public void setSeed(long s) { this.seed = s * 0x9E3779B97F4A7C15L + 0x5eed; }
    public boolean laid() { return laid; }
    public Mode mode() { return mode; }
    public boolean running() { return running; }
    public long ticks() { return ticks; }

    public double phase(double pt) { return phasePrev + (phase - phasePrev) * pt; }
    public double speed(double pt) { return speedPrev + (speed - speedPrev) * pt; }
    /** 0 walking, 1 running (eased between). */
    public double run(double pt) { return mixPrev + (mix - mixPrev) * pt; }
    public double crouch(double pt) { return crouchPrev + (crouch - crouchPrev) * pt; }
    public double alert(double pt) { return alertPrev + (alert - alertPrev) * pt; }
    public double display(double pt) { return displayPrev + (display - displayPrev) * pt; }
    public double wings(double pt) { return wingPrev + (wing - wingPrev) * pt; }
    public double lookYaw(double pt) { return lookYawPrev + (lookYaw - lookYawPrev) * pt; }
    public double lookPitch(double pt) { return lookPitchPrev + (lookPitch - lookPitchPrev) * pt; }
    public double tilt(double pt) { return tiltPrev + (tilt - tiltPrev) * pt; }
    /** The body's heading (the game's yaw, radians), eased, part way through the tick. */
    public double yaw(double pt) { return yawPrev + wrap(yaw - yawPrev) * pt; }

    /** The thorax's middle and its frame part way through the tick (forward and up, square to each other). */
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

    public Vector3d thorax() { return p; }

    /** The region of blocks the next tick reads: round the box, with room for the legs' reach. */
    public void bounds(Drive in, int[] out) {
        double r = spec.reach() + 0.8;
        double up = spec.ride + spec.reach();
        out[0] = (int) Math.floor(in.x - r); out[1] = (int) Math.floor(in.y - spec.reach() - 0.5); out[2] = (int) Math.floor(in.z - r);
        out[3] = (int) Math.floor(in.x + r); out[4] = (int) Math.floor(in.y + up); out[5] = (int) Math.floor(in.z + r);
    }

    // ------------------------------------------------------------------------------------------------ the skeleton

    /**
     * The places its parts hang from, worked out from the thorax's frame and the moment's pose: the prothorax's root
     * and way, the head's place and frame (left, down, back: its face looks along -back), its mouth, its shoulders,
     * the antennae's roots and rest ways, the abdomen's root and the wings' hinges.
     */
    public static final class Skeleton {
        public final Vector3d p = new Vector3d(), f = new Vector3d(), u = new Vector3d(), l = new Vector3d();
        public final Vector3d proRoot = new Vector3d(), proDir = new Vector3d(), proUp = new Vector3d();
        public final Vector3d head = new Vector3d(), hx = new Vector3d(), hy = new Vector3d(), hz = new Vector3d(), mouth = new Vector3d();
        public final Vector3d[] shoulder = vectors(2), antRoot = vectors(2), antRest = vectors(2), hinge = vectors(2);
        public final Vector3d abRoot = new Vector3d();
        final Vector3d tmp = new Vector3d();
    }

    /**
     * Fills in {@code s} (whose thorax frame p, f, u is set): the prothorax raised {@code proPitch} over the thorax's
     * forward, the head looking along the game's {@code yawAbs} and {@code pitch} (down positive), tilted {@code tilt}.
     */
    void skeleton(Skeleton s, double proPitch, double yawAbs, double pitch, double tilt, double runShare) {
        s.l.set(s.u).cross(s.f).normalize();
        double cp = Math.cos(proPitch), sp = Math.sin(proPitch);
        s.proRoot.set(s.p).fma(spec.proForward, s.f).fma(spec.proUp, s.u);
        s.proDir.set(s.f).mul(cp).fma(sp, s.u);
        s.proUp.set(s.u).mul(cp).fma(-sp, s.f);
        s.head.set(s.proRoot).fma(spec.prothorax, s.proDir);
        // the head looks where it looks, steady in the world whatever the body's pitch
        double cy = Math.cos(yawAbs), sy = Math.sin(yawAbs), cpp = Math.cos(pitch), spp = Math.sin(pitch);
        s.hz.set(sy * cpp, spp, -cy * cpp);                         // back: against the way it looks
        s.hy.set(0, -1, 0).fma(-s.hz.dot(0, -1, 0), s.hz);
        if (s.hy.lengthSquared() < 1.0E-6) s.hy.set(s.f).negate();
        s.hy.normalize();
        s.hx.set(s.hy).cross(s.hz).normalize();
        if (tilt != 0) {
            double ct = Math.cos(tilt), st = Math.sin(tilt);
            s.tmp.set(s.hy).mul(ct).fma(-st, s.hx);
            s.hy.set(s.tmp).normalize();
            s.hx.set(s.hy).cross(s.hz).normalize();
        }
        s.mouth.set(s.head).fma(3.0 * k, s.hy).fma(-1.2 * k, s.hz);
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? 1 : -1;
            s.shoulder[i].set(s.proRoot).fma(spec.shoulderAlong, s.proDir).fma(side * spec.shoulderSide, s.l).fma(-1.0 * k, s.proUp);
            s.antRoot[i].set(s.head).fma(side * 1.2 * k, s.hx).fma(-2.0 * k, s.hy).fma(-2.2 * k, s.hz);
            // out, up and ahead; swept back when it runs
            s.antRest[i].set(s.hx).mul(side * 0.5).fma(-0.8, s.hy).fma(-0.42 + 0.7 * runShare, s.hz).normalize();
            s.hinge[i].set(s.p).fma(2.0 * k, s.u).fma(-1.6 * k, s.f).fma(side * 0.25 * k, s.l);
        }
        s.abRoot.set(s.p).fma(-spec.abdomenBack, s.f).fma(0.2 * k, s.u);
    }

    /** The prothorax's rise over the thorax's forward for a crouch and an alert (radians). */
    double proPitch(double crouch, double alert) {
        return spec.proPitch - crouch * CROUCH_PITCH + alert * ALERT_PITCH;
    }

    /** How high the thorax rides over its feet's ground for a crouch and an alert (blocks). */
    double ride(double crouch, double alert) {
        return spec.ride - crouch * CROUCH_DROP * spec.scale + alert * ALERT_RISE * spec.scale;
    }

    // ------------------------------------------------------------------------------------------------ the tick

    public void tick(TerrainPatch rock, Drive in) {
        ticks++;
        pPrev.set(p); fPrev.set(f); uPrev.set(u);
        for (Foot ft : feet) {
            ft.prevAt.set(ft.at); ft.prevNrm.set(ft.nrm);
            ft.tPrev = ft.t; ft.toPrev.set(ft.to); ft.swung = false;
        }
        phasePrev = phase; speedPrev = speed; mixPrev = mix; yawPrev = yaw;
        crouchPrev = crouch; alertPrev = alert; displayPrev = display; wingPrev = wing; runPrev = run;
        lookYawPrev = lookYaw; lookPitchPrev = lookPitch; tiltPrev = tilt;
        for (int i = 0; i < 5; i++) { abOld[i].set(abPrev[i]); abPrev[i].set(ab[i]); }
        for (int i = 0; i < 2; i++) {
            antTipOld[i].set(antTipPrev[i]); antTipPrev[i].set(antTip[i]);
            handPrev[i].set(hand[i]); polePrev[i].set(pole[i]); coxaPrev[i].set(coxa[i]);
        }
        mode = in.mode;
        if (!laid || lastBox.distanceSquared(in.x, in.y, in.z) > JUMP * JUMP) {
            lay(rock, in);
            return;
        }
        if (mode == Mode.DEAD) {
            // dying, it is carried as it stands wherever its box goes; the pose lets it fall
            double dx = in.x - lastBox.x, dy = in.y - lastBox.y, dz = in.z - lastBox.z;
            p.add(dx, dy, dz);
            for (Vector3d a : ab) a.add(dx, dy, dz);
            for (Vector3d a : antTip) a.add(dx, dy, dz);
            for (Foot ft : feet) ft.at.add(dx, dy, dz);
            lastBox.set(in.x, in.y, in.z);
            return;
        }
        lastBox.set(in.x, in.y, in.z);

        // the moment's pose, eased
        double crouchT = in.graze && mode == Mode.GROUND ? 1.0 : Math.max(0.0, Math.min(1.0, in.crouch));
        crouch += (crouchT - crouch) * 0.09;
        alert += (Math.max(0.0, Math.min(1.0, in.alert)) - alert) * 0.2;
        display += ((in.display ? 1.0 : 0.0) - display) * (in.display ? 0.45 : 0.18);
        double wingT = in.display ? 1.0 : (in.hurry ? 0.16 : 0.0);
        wing += (wingT - wing) * (wingT > wing ? 0.42 : 0.14);
        run += ((in.hurry ? 1.0 : 0.0) - run) * 0.25;

        // its heading
        double dyaw = wrap(in.bodyYaw - yaw);
        yaw += dyaw * 0.45;
        double sy = Math.sin(yaw), cy = Math.cos(yaw);
        fl.set(-sy, 0, cy);
        ll.set(cy, 0, sy);

        body(rock, in);
        feet(rock, in);
        head(in);
        abdomen();
        antennae();
        arms(rock, in);
    }

    /** Laid out afresh where its box stands: standing tall, its feet on the rock under where they rest. */
    private void lay(TerrainPatch rock, Drive in) {
        laid = true;
        lastBox.set(in.x, in.y, in.z);
        yaw = in.bodyYaw;
        double sy = Math.sin(yaw), cy = Math.cos(yaw);
        fl.set(-sy, 0, cy);
        ll.set(cy, 0, sy);
        crouch = crouchPrev = 0;
        alert = alertPrev = 0;
        double ride = ride(0, 0);
        height = in.y + ride;
        pitch = roll = 0;
        laidHeight = false;
        p.set(in.x, height, in.z);
        lb.set(ll);
        f.set(fl); u.set(0, 1, 0);
        for (Foot ft : feet) {
            neutral(ft, in.y, ft.ideal);
            ft.idealPrev.set(ft.ideal);
            if (target(rock, ft, ft.ideal, 0, t1, t2)) plant(ft, t1, t2);
            else { ft.at.set(ft.ideal); ft.nrm.set(0, 1, 0); ft.planted = false; }
            ft.swinging = false;
            ft.prevAt.set(ft.at); ft.prevNrm.set(ft.nrm);
        }
        body(rock, in);
        // its head straight ahead, its abdomen and antennae at rest, its arms folded
        lookYaw = 0; lookPitch = HEAD_DOWN; tilt = 0;
        setFrame(sk);
        skeleton(sk, proPitch(0, 0), yaw, lookPitch, 0, 0);
        restAbdomen(ab);
        for (int i = 0; i < 5; i++) { abPrev[i].set(ab[i]); abOld[i].set(ab[i]); }
        for (int i = 0; i < 2; i++) {
            antTip[i].set(sk.antRoot[i]).fma(spec.antenna, sk.antRest[i]);
            antTipPrev[i].set(antTip[i]); antTipOld[i].set(antTip[i]);
            foldedArm(i, 0.0, coxa[i], hand[i], pole[i]);
            coxaPrev[i].set(coxa[i]); handPrev[i].set(hand[i]); polePrev[i].set(pole[i]);
        }
        pPrev.set(p); fPrev.set(f); uPrev.set(u);
        lookYawPrev = lookYaw; lookPitchPrev = lookPitch; tiltPrev = tilt;
        yawPrev = yaw;
        phasePrev = phase; speedPrev = speed = 0; mixPrev = mix = 0;
        running = false;
    }

    private Skeleton setFrame(Skeleton s) {
        s.p.set(p); s.f.set(f); s.u.set(u);
        return s;
    }

    // ------------------------------------------------------------------------------------------------ the thorax

    /** The thorax: over its box, as high over its feet's ground as it rides, pitched and rolled to follow them. */
    private void body(TerrainPatch rock, Drive in) {
        double ride = ride(crouch, alert);
        double sum = 0, mid = 0, hind = 0, left = 0, right = 0;
        int n = 0, nm = 0, nh = 0, nl = 0, nr = 0;
        for (Foot ft : feet) {
            double y;
            if (ft.planted) y = ft.at.y;
            else if (ft.swinging && ft.toRock) y = ft.to.y;
            else continue;
            sum += y; n++;
            if (ft.mid) { mid += y; nm++; } else { hind += y; nh++; }
            if (ft.left) { left += y; nl++; } else { right += y; nr++; }
        }
        double ground = n > 0 ? sum / n : in.y;
        if (mode == Mode.AIR) ground = in.y;
        // a little bob, twice a stride: highest as the body vaults over its feet
        double amt = Math.min(1.0, speed / 0.08);
        double bob = 0.022 * spec.scale * amt * Math.cos(4.0 * Math.PI * phase) * (1.0 - 0.5 * mix);
        double want = ground + ride + bob;
        // never down in its box's floor, nor far over it (a foot on a high ledge does not lift it off its feet)
        want = Math.max(want, in.y + ride * 0.62);
        want = Math.min(want, in.y + ride + 0.9 * spec.scale);
        if (!laidHeight) { height = want; laidHeight = true; }
        height += (want - height) * (mode == Mode.AIR ? 0.6 : 0.28);
        double pt = 0, rt = 0;
        if (nm > 0 && nh > 0 && mode == Mode.GROUND) {
            double span = (spec.rest[0][0] - spec.rest[2][0]);
            pt = Math.atan2(mid / nm - hind / nh, span) * PITCH_FOLLOW;
        }
        if (nl > 0 && nr > 0 && mode == Mode.GROUND) {
            double span = 2.0 * spec.rest[0][1];
            rt = Math.atan2(left / nl - right / nr, span) * ROLL_FOLLOW;
        }
        pt -= 0.1 * run;                                          // it leans into a run
        pitch += (clamp(pt, MAX_PITCH) - pitch) * 0.22;
        roll += (clamp(rt, MAX_ROLL) - roll) * 0.22;
        // its weight shifts a little from side to side with its steps
        double sway = 0.025 * spec.scale * amt * Math.sin(2.0 * Math.PI * (phase - 0.1)) * (1.0 - 0.6 * mix);
        p.set(in.x, height, in.z).fma(sway, ll);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        f.set(fl).mul(cp).add(0, sp, 0);
        t0.set(fl).mul(-sp).add(0, cp, 0);                         // up, pitched
        t1.set(t0).cross(f);                                       // left, pitched
        double cr = Math.cos(roll), sr = Math.sin(roll);
        u.set(t0).mul(cr).fma(-sr, t1).normalize();
        f.normalize();
        lb.set(u).cross(f).normalize();
    }

    private boolean laidHeight;

    // ------------------------------------------------------------------------------------------------ feet

    /** One of its four walking feet: middle or hind, left or right; where it stands and on what. */
    public static final class Foot {
        public final int index;
        public final boolean left, mid;
        /** On rock and staying there. */
        public boolean planted;
        /** Where the foot is (on rock when planted), and the way out of that rock. */
        public final Vector3d at = new Vector3d(), nrm = new Vector3d(0, 1, 0);
        public final Vector3d prevAt = new Vector3d(), prevNrm = new Vector3d(0, 1, 0);
        final Vector3d from = new Vector3d(), to = new Vector3d(), toN = new Vector3d(0, 1, 0), toPrev = new Vector3d();
        final Vector3d ideal = new Vector3d(), idealPrev = new Vector3d();
        double t, step, lift, tPrev;
        boolean swinging, swung, toRock;

        Foot(int index) {
            this.index = index;
            this.left = MantisSpec.left(index);
            this.mid = MantisSpec.mid(index);
        }

        /** Where the foot is part way through the tick: along its step's arc while it steps, else between ticks. */
        public void sole(double pt, Vector3d p, Vector3d n) {
            if (swung) {
                double tt = tPrev + (t - tPrev) * pt;
                double tx = toPrev.x + (to.x - toPrev.x) * pt, ty = toPrev.y + (to.y - toPrev.y) * pt, tz = toPrev.z + (to.z - toPrev.z) * pt;
                arc(from, tx, ty, tz, lift, tt, p);
                n.set(0, 1, 0);
                return;
            }
            p.set(prevAt).lerp(at, pt);
            n.set(prevNrm).lerp(nrm, pt);
            if (n.lengthSquared() < 1.0E-8) n.set(nrm);
            n.normalize();
        }

        public boolean swinging() { return swinging || swung; }

        public double swingT(double pt) { return swung ? tPrev + (t - tPrev) * pt : swinging ? t : 0.0; }

        /**
         * A step's arc from {@code from} to (tx, ty, tz), lifted {@code lift} at its middle. Stepping up, the foot
         * rises first and then reaches across, so it clears the step's edge; stepping down, it reaches across first
         * and then comes down. A long leg's foot is lifted high and set down softly.
         */
        static void arc(Vector3d from, double tx, double ty, double tz, double lift, double t, Vector3d out) {
            double dy = ty - from.y;
            double eh, ev;
            if (dy > 0.15) {
                ev = smooth(Math.min(1.0, t * 1.7));
                eh = smooth(Math.max(0.0, (t - 0.18) / 0.82));
            } else if (dy < -0.15) {
                eh = smooth(Math.min(1.0, t * 1.5));
                ev = smooth(Math.max(0.0, (t - 0.3) / 0.7));
            } else {
                eh = ev = smooth(t);
            }
            // high in the first half, and lowered gently onto the rock
            double h = Math.sin(Math.PI * Math.pow(t, 0.8)) * lift;
            out.set(from.x + (tx - from.x) * eh, from.y + dy * ev + h, from.z + (tz - from.z) * eh);
        }
    }

    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }

    /** Where a foot rests standing still: out to its side, ahead or behind, on its body's ground ({@code ground}). */
    private void neutral(Foot ft, double ground, Vector3d out) {
        double[] r = spec.rest[ft.index];
        double wide = 1.0 + 0.1 * crouch;
        out.set(p.x, ground, p.z).fma(r[0] * (1.0 - 0.08 * crouch), fl).fma(r[1] * wide, ll);
    }

    /** A foot's hip, where it hangs from the thorax now. */
    private void hip(Foot ft, Vector3d out) {
        double[] h = spec.hip[ft.index];
        out.set(p).fma(h[0], f).fma(h[1], u).fma(h[2], lb);
    }

    /** A foot's ankle over where it stands on rock: the tarsus stands up and in from the foot toward the body. */
    void ankle(Foot ft, Vector3d foot, Vector3d n, Vector3d out) {
        double side = ft.left ? 1 : -1;
        out.set(foot).fma(spec.ankleUp, n).fma(-side * spec.ankleIn, ll);
    }

    private void feet(TerrainPatch rock, Drive in) {
        double ground = height - ride(crouch, alert);
        double moved = 0;
        for (Foot ft : feet) {
            ft.idealPrev.set(ft.ideal);
            neutral(ft, ground, ft.ideal);
            double dx = ft.ideal.x - ft.idealPrev.x, dz = ft.ideal.z - ft.idealPrev.z;
            moved += Math.sqrt(dx * dx + dz * dz);
        }
        moved = Math.min(0.6, moved / 4.0);
        speed += (moved - speed) * 0.3;
        boolean want = in.hurry || speed > (running ? WALK_FROM : RUN_FROM) * spec.scale;
        running = want;
        double stride = running ? spec.trotStride : spec.walkStride;
        double duty = running ? RUN_DUTY : WALK_DUTY;
        double before = phase;
        phase += moved / stride;
        double mixStep = Math.max(0.05, (phase - before) * 1.5);
        mix += Math.max(-mixStep, Math.min(mixStep, (running ? 1.0 : 0.0) - mix));
        standing = moved < 0.003 ? standing + 1 : 0;

        if (mode == Mode.AIR) {
            for (Foot ft : feet) { ft.planted = false; ft.swinging = false; dangle(ft); }
            return;
        }
        boolean anySwing = false;
        for (Foot ft : feet) anySwing |= ft.swinging;
        for (Foot ft : feet) {
            double offset = lerpPhase(WALK_OFFSET[ft.index], RUN_OFFSET[ft.index], mix);
            if (ft.swinging) {
                swing(rock, ft, stride, duty);
                continue;
            }
            if (!ft.planted) {
                // back on its feet (after a fall, or laid where there was no rock): down at once if rock is in reach
                if (target(rock, ft, ft.ideal, 0.0, t1, t2)) begin(rock, ft, 4, stride, duty, 0.15 * spec.scale);
                else dangle(ft);
                continue;
            }
            double a = Math.floor(before - offset - duty), b = Math.floor(phase - offset - duty);
            boolean turn = b > a && moved > 0.003;
            boolean lost = overreached(ft) || !standsOn(rock, ft.at, ft.nrm);
            if (turn || lost) {
                int ticksFor = lost && !turn ? 4 : stepTicks(stride, duty);
                begin(rock, ft, ticksFor, stride, duty, (running ? spec.trotLift : spec.walkLift) * (lost && !turn ? 0.6 : 1.0));
                anySwing = true;
            }
        }
        // standing: one foot at a time steps back under where it rests
        if (standing > 6 && !anySwing) {
            Foot worst = null;
            double most = SETTLE_FOOT * spec.scale;
            for (Foot ft : feet) {
                if (!ft.planted) continue;
                if (!target(rock, ft, ft.ideal, 0.0, t1, t2)) continue;
                double dx = t1.x - ft.at.x, dz = t1.z - ft.at.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > most) { most = d; worst = ft; }
            }
            if (worst != null) begin(rock, worst, 9, stride, duty, 0.22 * spec.scale);
        }
    }

    private int stepTicks(double stride, double duty) {
        double s = Math.max(speed, 0.02);
        int t = (int) Math.round((1.0 - duty) * stride / s);
        return Math.max(STEP_MIN, Math.min(STEP_MAX, t));
    }

    /** Lifts a foot to step: from where it stands to where the gait will want it as it lands. */
    private void begin(TerrainPatch rock, Foot ft, int ticksFor, double stride, double duty, double lift) {
        ft.from.set(ft.at);
        ft.swinging = true;
        ft.planted = false;
        ft.t = 0.0;
        ft.step = 1.0 / ticksFor;
        ft.lift = lift;
        aim(rock, ft, ticksFor, stride, duty, true);
        ft.toPrev.set(ft.to);
    }

    /** Where a step lands: ahead of where the foot rests by half the ground it covers while down, on rock it can stand on. */
    private void aim(TerrainPatch rock, Foot ft, double ticksLeft, double stride, double duty, boolean fresh) {
        double vx = ft.ideal.x - ft.idealPrev.x, vz = ft.ideal.z - ft.idealPrev.z;
        double v = Math.sqrt(vx * vx + vz * vz);
        double cap = MAX_AIM_SPEED * spec.scale;
        if (v > cap) { vx *= cap / v; vz *= cap / v; v = cap; }
        Vector3d aimAt = t4.set(ft.ideal);
        double ahead = 0.0;
        if (v > 0.003) {
            aimAt.add(vx * ticksLeft, 0, vz * ticksLeft);
            ahead = Math.min(duty * stride * 0.5, spec.reach() * MAX_AHEAD);
            aimAt.add(vx / v * ahead, 0, vz / v * ahead);
        }
        double lead = v * ticksLeft;
        boolean found = target(rock, ft, aimAt, lead, t1, t2);
        if (!found && !fresh && ft.toRock && standsOn(rock, ft.to, ft.toN)) return;
        if (!found) found = target(rock, ft, ft.ideal, 0.0, t1, t2);
        if (!found && standsOn(rock, ft.from, ft.nrm)) { t1.set(ft.from); t2.set(ft.nrm); found = true; }
        if (found) {
            boolean same = !fresh && ft.toRock && Math.abs(ft.to.y - t1.y) < 0.05 && ft.to.distanceSquared(t1) < 0.3 * 0.3;
            ft.toRock = true;
            if (same) ft.to.lerp(t1, 0.5);
            else ft.to.set(t1);
            ft.toN.set(t2);
        } else {
            ft.toRock = false;
            if (fresh) ft.to.set(aimAt);
            else ft.to.lerp(aimAt, 0.5);
            ft.toN.set(0, 1, 0);
        }
    }

    private void swing(TerrainPatch rock, Foot ft, double stride, double duty) {
        ft.t = Math.min(1.0, ft.t + ft.step);
        ft.swung = true;
        double left = (1.0 - ft.t) / ft.step;
        if (ft.t < 0.8) aim(rock, ft, left, stride, duty, false);
        Foot.arc(ft.from, ft.to.x, ft.to.y, ft.to.z, ft.lift, ft.t, ft.at);
        ft.nrm.set(0, 1, 0);
        // the foot never passes through rock on its way: a step over an edge lifts higher until it clears
        if (ft.t < 0.9 && rock.solid(ft.at.x, ft.at.y + 0.02, ft.at.z)) {
            ft.lift += 0.18 * spec.scale;
            Foot.arc(ft.from, ft.to.x, ft.to.y, ft.to.z, ft.lift, ft.t, ft.at);
        }
        if (ft.t >= 1.0) {
            ft.swinging = false;
            if (!ft.toRock || !standsOn(rock, ft.to, ft.toN)) {
                boolean found = target(rock, ft, ft.to, 0.0, t1, t2) || target(rock, ft, ft.ideal, 0.0, t1, t2);
                ft.toRock = found && t1.distanceSquared(ft.to) < 0.6 * 0.6;
                if (ft.toRock) { ft.to.set(t1); ft.toN.set(t2); }
            }
            if (ft.toRock) {
                plant(ft, ft.to, ft.toN);
                if (footfalls != null) footfalls.land(ft, running, speed);
            } else {
                ft.planted = false;
            }
        }
    }

    private void plant(Foot ft, Vector3d at, Vector3d n) {
        ft.at.set(at);
        ft.nrm.set(n);
        ft.planted = true;
        ft.swinging = false;
    }

    /** Off the rock (falling, knocked off a ledge): the leg hangs down and out from its hip. */
    private void dangle(Foot ft) {
        hip(ft, t3);
        double side = ft.left ? 1 : -1;
        double[] r = spec.rest[ft.index];
        t1.set(t3).fma(side * spec.femur * 0.9, ll).fma(r[0] * 0.4, fl).add(0, -spec.tibia * 0.75, 0);
        ft.at.lerp(t1, 0.4);
        ft.nrm.set(0, 1, 0);
    }

    /** Whether a foot at p (out of its rock along n) stands on rock. */
    private static boolean standsOn(TerrainPatch rock, Vector3d p, Vector3d n) {
        return rock.solid(p.x - n.x * 0.06, p.y - n.y * 0.06, p.z - n.z * 0.06);
    }

    private boolean overreached(Foot ft) {
        hip(ft, t3);
        ankle(ft, ft.at, ft.nrm, t0);
        return t0.distanceSquared(t3) > Math.pow(spec.reach() * OVERREACH, 2);
    }

    /**
     * Rock a foot can stand on near {@code near} (a point on the ground where it would stand): p, and the way out of
     * the rock, n. Down onto the top of a block from over it (a block or two up, so it steps onto steps), drawn in
     * toward the body when that point lies in rock (against a wall it stands narrower); then a ring of places round
     * it. Each must be the top of something (not a wall's face), and its ankle within the leg's reach of the hip (as
     * the hip will be {@code lead} further on). False when none is.
     */
    private boolean target(TerrainPatch rock, Foot ft, Vector3d near, double lead, Vector3d out, Vector3d n) {
        hip(ft, t3);
        if (lead > 0) {
            double vx = near.x - ft.ideal.x, vz = near.z - ft.ideal.z;
            double vl = Math.sqrt(vx * vx + vz * vz);
            if (vl > 1.0E-6) t3.add(vx / vl * Math.min(lead, vl), 0, vz / vl * Math.min(lead, vl));
        }
        double side = ft.left ? 1 : -1;
        double up = 1.6 * spec.scale, down = 2.6 * spec.scale;
        // drawn in toward the body out of any rock (a wall where it would stand)
        double px = near.x, pz = near.z;
        for (int i = 0; i < 6; i++) {
            if (!rock.solid(px, near.y + up * 0.5, pz)) break;
            px -= ll.x * side * 0.2 * spec.scale;
            pz -= ll.z * side * 0.2 * spec.scale;
        }
        if (castDown(rock, ft, px, near.y + up, pz, near.y - down, out, n)) return true;
        // a ring of places round it, nearest first
        for (double r : RING_R) {
            for (int a = 0; a < RING_N; a++) {
                double ang = 2.0 * Math.PI * a / RING_N;
                double ox = Math.cos(ang) * r * spec.scale, oz = Math.sin(ang) * r * spec.scale;
                if (castDown(rock, ft, near.x + ox, near.y + up, near.z + oz, near.y - down, out, n)) return true;
            }
        }
        return false;
    }

    private static final double[] RING_R = {0.35, 0.7, 1.05};
    private static final int RING_N = 8;

    /** Straight down from (x, y0, z) to y1: the first top of rock, kept if the leg reaches it from its hip (in t3). */
    private boolean castDown(TerrainPatch rock, Foot ft, double x, double y0, double z, double y1, Vector3d out, Vector3d n) {
        // never from inside rock: start just under the ceiling of the space it is in
        if (rock.solid(x, y0, z)) {
            double y = y0;
            while (y > y1 && rock.solid(x, y, z)) y -= 0.25;
            if (y <= y1) return false;
            y0 = y;
        }
        if (!rock.clip(x, y0, z, x, y1, z, hit) || hit[4] > 0.5) return false;
        if (hit[2] < FLOOR_UP) return false;
        double hy = y0 + (y1 - y0) * hit[0];
        t0.set(x, hy, z);
        // the ankle over it within the leg's reach, and not folded tighter than the leg folds
        ankle(ft, t0, t2.set(hit[1], hit[2], hit[3]), t1);
        double d = t1.distance(t3);
        if (d > spec.reach() * 0.97) return false;
        if (d < Math.abs(spec.tibia - spec.femur) + 0.12 * spec.scale) return false;
        // the foot on its own side of the body
        double lateral = ((x - p.x) * ll.x + (z - p.z) * ll.z) * (ft.left ? 1 : -1);
        if (lateral < 0.35 * spec.scale) return false;
        out.set(t0);
        n.set(hit[1], hit[2], hit[3]);
        return true;
    }

    private static double lerpPhase(double a, double b, double t) {
        double d = b - a;
        d -= Math.floor(d + 0.5);
        return a + d * t;
    }

    // ------------------------------------------------------------------------------------------------ head

    /** Its head: it turns toward what it looks at in quick flicks, then follows slowly; watching, it tilts it. */
    private void head(Drive in) {
        double rel = clamp(wrap(in.headYaw - yaw), MAX_HEAD_YAW);
        double pitchT = clamp(in.headPitch + HEAD_DOWN + crouch * GRAZE_HEAD_DOWN, MAX_HEAD_PITCH);
        double dy = rel - lookYaw, dp = pitchT - lookPitch;
        double big = Math.sqrt(dy * dy + dp * dp);
        double rate = big > FLICK ? FLICK_RATE : TRACK_RATE;
        lookYaw += dy * rate;
        lookPitch += dp * rate;
        // the tilt: watching, it cocks its head now one way, now the other, and a little toward where it looks
        if (--tiltTimer <= 0) {
            tiltTimer = 30 + (int) (rand(1) * 70);
            tiltGoal = in.watch ? (rand(2) - 0.5) * 0.9 : (rand(3) - 0.5) * 0.25;
        }
        double tiltT = tiltGoal + (in.watch ? -lookYaw * 0.25 : 0.0);
        double dt = tiltT - tilt;
        tilt += dt * (Math.abs(dt) > 0.15 ? 0.45 : 0.1);
    }

    // ------------------------------------------------------------------------------------------------ abdomen

    /** The abdomen's joints at rest behind the thorax as it stands now. */
    private void restAbdomen(Vector3d[] out) {
        out[0].set(sk.abRoot);
        for (int i = 0; i < 4; i++) {
            double a = ABDOMEN_RISE[i] - crouch * 0.12;
            t0.set(f).mul(-Math.cos(a)).fma(Math.sin(a), u);
            out[i + 1].set(out[i]).fma(spec.abdomen[i], t0);
        }
    }

    private void abdomen() {
        setFrame(sk);
        skeleton(sk, proPitch(crouch, alert), yaw + lookYaw, lookPitch, tilt, run);
        ab[0].set(sk.abRoot);
        for (int i = 0; i < 4; i++) {
            // the rest way of this segment, from the segment before it as it lies now (a curve that bends as one)
            double a = ABDOMEN_RISE[i] - crouch * 0.12;
            t0.set(f).mul(-Math.cos(a)).fma(Math.sin(a), u);
            t1.set(ab[i]).fma(spec.abdomen[i], t0);                  // its rest place
            Vector3d j = ab[i + 1];
            // inertia: it keeps some of its swing, and is pulled back toward rest
            t2.set(abPrev[i + 1]).sub(abOld[i + 1]).mul(ABDOMEN_KEEP);
            j.set(abPrev[i + 1]).add(t2);
            j.lerp(t1, ABDOMEN_STIFF[i]);
            // its length
            t2.set(j).sub(ab[i]);
            double len = t2.length();
            if (len < 1.0E-6) t2.set(t0); else t2.div(len);
            j.set(ab[i]).fma(spec.abdomen[i], t2);
        }
    }

    // ------------------------------------------------------------------------------------------------ antennae

    private void antennae() {
        for (int i = 0; i < 2; i++) {
            Vector3d root = sk.antRoot[i];
            t1.set(root).fma(spec.antenna, sk.antRest[i]);
            Vector3d tip = antTip[i];
            t2.set(antTipPrev[i]).sub(antTipOld[i]).mul(ANTENNA_KEEP);
            tip.set(antTipPrev[i]).add(t2);
            tip.lerp(t1, ANTENNA_PULL);
            // now and then one flicks
            if (rand(10 + i) < ANTENNA_FLICK) {
                tip.fma((rand(12 + i) - 0.5) * 0.5 * spec.antenna, sk.hx).fma((rand(14 + i) - 0.7) * 0.6 * spec.antenna, sk.hy);
            }
            t2.set(tip).sub(root);
            double len = t2.length();
            if (len < 1.0E-6) t2.set(sk.antRest[i]); else t2.div(len);
            tip.set(root).fma(spec.antenna, t2);
        }
    }

    // ------------------------------------------------------------------------------------------------ arms

    /**
     * Its grabbing arms. Folded under its head (the femur raised along the prothorax, the tibia closed on it), spread
     * high in its startle display, and while it grazes each in turn reaches down to the floor ahead, picks at it and
     * brings what it took up to its mouth. Held in the thorax's frame from the shoulder, eased.
     */
    private void arms(TerrainPatch rock, Drive in) {
        boolean grazing = in.graze && crouch > 0.72 && display < 0.3;
        if (grazing) grazeClock += 1.0 / GRAZE_TICKS;
        for (int i = 0; i < 2; i++) {
            Vector3d sh = sk.shoulder[i];
            Vector3d wantHand = t1, wantPole = t2, wantCoxa = t4;
            foldedArm(i, run, wantCoxa, wantHand, wantPole);
            double rate = 0.35;
            if (display > 0.05) {
                displayArm(i, t0, t3);
                double d = display;
                wantCoxa.lerp(t0, d).normalize();
                // the hand from the displayed arm's own FK (in t3), blended
                wantHand.lerp(t3, d);
                wantPole.lerp(DISPLAY_POLE[i], d);
                rate = 0.5;
            } else if (grazing) {
                double q = grazeClock + i * 0.5;
                int cycle = (int) Math.floor(q);
                q -= cycle;
                if (grazePick[i] != cycle) {
                    grazePick[i] = cycle;
                    grazeFound[i] = floorAhead(rock, i, grazeAt[i]);
                }
                if (grazeFound[i] && q < 0.62) {
                    // to the body's frame from the shoulder
                    local(grazeAt[i], sh, t0);
                    if (q < 0.3) {
                        // reach down and pick: the hand on the mat, scraping back a little
                        double scrape = q < 0.18 ? 0.0 : (q - 0.18) / 0.12;
                        wantHand.set(t0).add(-0.12 * spec.scale * scrape, 0.04 * spec.scale, 0);
                        wantPole.set(0.35, 0.9, (i == 0 ? 1 : -1) * 0.25);
                        wantCoxa.set(0.45, -0.88, (i == 0 ? 1 : -1) * 0.12).normalize();
                        rate = q < 0.18 ? 0.42 : 0.5;
                    } else {
                        // up to its mouth, and held there while it chews
                        local(sk.mouth, sh, t0);
                        double chew = q > 0.42 ? 0.02 * spec.scale * Math.sin(ticks * 1.9 + i) : 0.0;
                        wantHand.set(t0).add(0, chew, (i == 0 ? -1 : 1) * 0.03 * spec.scale);
                        wantPole.set(0.75, 0.55, (i == 0 ? 1 : -1) * 0.2);
                        rate = 0.4;
                    }
                }
            }
            coxa[i].lerp(wantCoxa, rate).normalize();
            hand[i].lerp(wantHand, rate);
            pole[i].lerp(wantPole, rate);
        }
    }

    private static final Vector3d[] DISPLAY_POLE = {new Vector3d(0.2, 0.8, 0.6), new Vector3d(0.2, 0.8, -0.6)};

    /**
     * The folded arm (in the thorax's frame from its shoulder: forward, up, left): the coxa hanging down and forward,
     * the femur raised up along the prothorax, the tibia closed back down on it. Running it is tucked tighter.
     */
    void foldedArm(int i, double runShare, Vector3d coxaOut, Vector3d handOut, Vector3d poleOut) {
        double side = i == 0 ? 1 : -1;
        coxaOut.set(0.52, -0.84, side * 0.1).normalize();
        double cx = coxaOut.x * spec.armCoxa, cy = coxaOut.y * spec.armCoxa, cz = coxaOut.z * spec.armCoxa;
        // the femur up and forward, the tibia closed down on it, open this much (radians)
        double fa = Math.toRadians(62 + 8 * runShare), open = Math.toRadians(24 - 10 * runShare);
        double fdx = Math.cos(fa), fdy = Math.sin(fa);
        double jx = cx + fdx * spec.armFemur, jy = cy + fdy * spec.armFemur, jz = cz + side * 0.03 * spec.armFemur;
        // the tibia: back down the femur, opened forward by `open`
        double ta = fa + Math.PI + open;
        double hx = jx + Math.cos(ta) * spec.armTibia, hy = jy + Math.sin(ta) * spec.armTibia;
        handOut.set(hx, hy, jz);
        poleOut.set(fdx, fdy, side * 0.05);
    }

    /** The startle display: coxa raised forward and out, femur up and out, tibia opened wide. Coxa way and hand (FK). */
    private void displayArm(int i, Vector3d coxaOut, Vector3d handOut) {
        double side = i == 0 ? 1 : -1;
        coxaOut.set(0.75, 0.35, side * 0.5).normalize();
        double cx = coxaOut.x * spec.armCoxa, cy = coxaOut.y * spec.armCoxa, cz = coxaOut.z * spec.armCoxa;
        double fx = 0.25, fy = 0.85, fz = side * 0.45, fn = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fn; fy /= fn; fz /= fn;
        double jx = cx + fx * spec.armFemur, jy = cy + fy * spec.armFemur, jz = cz + fz * spec.armFemur;
        // the tibia opened out forward, a hook held high
        handOut.set(jx + 0.8 * spec.armTibia, jy + 0.15 * spec.armTibia, jz + side * 0.25 * spec.armTibia);
    }

    /** A point of the floor ahead under its head for arm {@code i} to pick at (a little to that arm's side). */
    private boolean floorAhead(TerrainPatch rock, int i, Vector3d out) {
        double side = i == 0 ? 1 : -1;
        Vector3d sh = sk.shoulder[i];
        double ahead = (0.45 + 0.35 * rand(20 + i)) * spec.scale;
        double across = side * (0.05 + 0.22 * rand(22 + i)) * spec.scale;
        double x = sh.x + fl.x * ahead + ll.x * across, z = sh.z + fl.z * ahead + ll.z * across;
        double y0 = sh.y, y1 = sh.y - 2.2 * spec.scale;
        if (rock.solid(x, y0, z)) return false;
        if (!rock.clip(x, y0, z, x, y1, z, hit) || hit[4] > 0.5 || hit[2] < FLOOR_UP) return false;
        out.set(x, y0 + (y1 - y0) * hit[0], z);
        // within the arm's reach of its shoulder
        return out.distance(sh) < (spec.armCoxa + spec.armFemur + spec.armTibia) * 0.95;
    }

    /** A world point as the thorax's frame has it from {@code origin}: forward, up, left. */
    private void local(Vector3d world, Vector3d origin, Vector3d out) {
        double dx = world.x - origin.x, dy = world.y - origin.y, dz = world.z - origin.z;
        out.set(dx * f.x + dy * f.y + dz * f.z, dx * u.x + dy * u.y + dz * u.z, dx * lb.x + dy * lb.y + dz * lb.z);
    }

    // ------------------------------------------------------------------------------------------------ small things

    /** A number from 0 to 1 that changes every tick, its own for each body and each use. */
    private double rand(int use) {
        long h = seed + ticks * 0x9E3779B97F4A7C15L + use * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    static double wrap(double a) {
        a %= 2.0 * Math.PI;
        if (a > Math.PI) a -= 2.0 * Math.PI;
        if (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }

    private static double clamp(double v, double max) { return Math.max(-max, Math.min(max, v)); }
}
