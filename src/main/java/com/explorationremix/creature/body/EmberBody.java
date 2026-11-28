package com.explorationremix.creature.body;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * The ember urchin's body as it holds itself in the Wasteland's tunnels (0.23.7, Taran: "more of a ball than a disk ...
 * and straddle the cave walls. It should also walk around"). A black ball a block and a quarter across, thirty long
 * walking spines all round it and a coat of short ones between them ({@link EmberSpec}). It never turns and has no front
 * (the cards' "Crawls on its spines"): it glides level wherever its box goes, and its spines carry it.
 *
 * <ul>
 *     <li>Every walking spine that can reach rock braces on it: its point set on a real block, found by looking out from
 *     its socket round its own way out ({@link #search}). Its rod is a little longer than the reach to its point, so it
 *     presses there and bows like a spring (the cards' "Springy needles that bow": {@link Bow}, a circular bow of the
 *     spine's own length between its socket and its point, bowing away from the rock its point is on). In a tunnel its
 *     spines brace on the floor, both walls and the roof all round it: it straddles the tunnel, its ball in the
 *     middle.</li>
 *     <li>The spines that reach no rock (along the tunnel, mostly) stand straight out round it, swaying, and turn toward
 *     what it attends to (the cards' "spines turn to you").</li>
 *     <li>As it goes, a braced spine whose point falls too far behind its way out, is drawn out to the end of its reach or
 *     is pressed in too far (bowed past bearing) lifts and steps: to where it will hold longest as the ball goes on (far
 *     ahead for one it is coming toward, near in for one it is leaving). No two neighbours lift together, so a ripple of
 *     steps runs over it.</li>
 *     <li>Going quicker (chasing someone at a player's walk, 0.24.3), its steps are quicker and more of its spines step
 *     at once, earlier, and its ball leans further into its going: a scuttle ({@link #quick}).</li>
 *     <li>Its ball leans a little into its going and gives a little at each step (a spring), so it rides its spines.</li>
 *     <li>Dying, its spines let go and droop and it settles onto the rock under it.</li>
 * </ul>
 *
 * <p>Plain numbers and JOML, no game classes: the entity drives it ({@link Drive}) and hands it the rock
 * ({@link TerrainPatch}) each tick (on the server too, so its spines prick where they are: {@link #touches}), the pose
 * ({@link EmberPose}) draws it, and the offline clips walk it the same way.
 */
public final class EmberBody {
    public enum Mode { LIVE, DEAD }

    /** What moves the body this tick: from the entity. */
    public static final class Drive {
        /** Where its ball's middle is. */
        public double x, y, z;
        public Mode mode = Mode.LIVE;
        /** What its spines turn to (a place in the world) and how much (0 not at all, 1 all on it). */
        public final Vector3d focus = new Vector3d();
        public double focusAmount;
        /** 0 to 1: how far it bristles (flared: near someone, or hurt). */
        public double flare;
    }

    /** Hears each spine's point as it lands. */
    public interface Footfalls {
        void land(Leg leg, double speed);
    }

    // ------------------------------------------------------------------------------------------------ tuning
    /** Further than this between ticks (blocks), it was put somewhere new: laid out afresh there. */
    static final double JUMP = 2.0;
    /**
     * A spine's point is set where the reach from its socket (as a share of its length) lies between these: near enough
     * that it presses and bows a little, never so near that it bows past bearing. It aims for the third.
     */
    static final double PLANT_MIN = 0.76, PLANT_MAX = 0.99, PLANT_AIM = 0.92;
    /**
     * A braced spine bears from SLACK down to STEP_SHORT (bowed past bearing at that: it steps) and from TAUT up to
     * STEP_LONG (drawn out past its length: it steps); its point bears until the reach to it swings STEP_ANGLE off its way out.
     */
    static final double SLACK = 0.95, STEP_SHORT = 0.72, TAUT = 0.985, STEP_LONG = 1.02;
    static final double STEP_ANGLE = Math.toRadians(76);
    /** Looking for rock: rings this far off its way out (radians), the near ones first, with this many looks round each. */
    static final double[] RINGS = {0, Math.toRadians(14), Math.toRadians(28), Math.toRadians(42), Math.toRadians(54), Math.toRadians(64), Math.toRadians(72)};
    static final int[] ROUND = {1, 6, 10, 14, 16, 16, 18};
    /** The near rings looked through first; the rest only when they hold nothing (pressed close to rock). */
    static final int NEAR_RINGS = 4;
    /** A point bites its rock at least this steeply (the cosine of its reach with the rock's way in), never glancing off. */
    static final double MIN_BITE = 0.22;
    /** Its rod leaves the ball no flatter than this (the cosine of its root's way with its way out). */
    static final double ROOT_OUT = 0.12;
    /**
     * A bow that would pass through rock (a bump in the wall between its socket and its point) is turned round its reach,
     * the least of these that clears it (radians), checked at these shares of its length.
     */
    static final double[] BOW_TURNS = {Math.toRadians(45), Math.toRadians(-45), Math.toRadians(90), Math.toRadians(-90),
            Math.toRadians(135), Math.toRadians(-135), Math.PI};
    static final double[] BOW_CHECK = {0.2, 0.4, 0.6, 0.8};
    /** How long ahead a new point is judged to hold as the ball goes on (ticks, looked at every LIFE_STEP). */
    static final int LIFE_CAP = 60, LIFE_STEP = 3;
    /** At most this many spines up at once (and, pulled past bearing, a few more), each step this many ticks. */
    static final int MOST_UP = 6, FORCED_UP = 9;
    static final int SWING = 6, SWING_QUICK = 4;
    /**
     * Its scuttle ({@link #quick}): going from QUICK_FROM to QUICK_AT (blocks a tick: from a little over its own walk to
     * about a player's walk), its steps grow up to this much quicker (ticks off each), up to this many more of its spines
     * are up at once and lift a tick, a braced spine steps this much sooner (of its strain), and a new point is judged
     * over a shorter life (as it bears so much less long).
     */
    static final double QUICK_FROM = 0.07, QUICK_AT = 0.19;
    static final int QUICK_SWING = 3, QUICK_SWING_QUICK = 2, QUICK_UP = 4, QUICK_LIFTS = 2;
    static final double QUICK_SOONER = 0.25, QUICK_LIFE = 0.25, QUICK_LEAN = 0.8;
    /**
     * A step lifts its point this far off its rock (blocks at scale 1), and draws it in toward the ball by this share of
     * its reach at the step's height (as a leg's knee bends to lift it), so that it swings round inside corners, not
     * through them.
     */
    static final double LIFT = 0.32, DRAW_IN = 0.18;
    /** A spine reaching no rock looks again every this many ticks (each its own tick). */
    static final int FREE_LOOK = 5;
    /** Standing, a braced spine pulled harder than this steps back nearer its way out, one at a time. */
    static final double SETTLE = 0.62;
    /** Its ball leans into its going by up to LEAN (radians) and wobbles by WOBBLE. */
    static final double LEAN = Math.toRadians(5), WOBBLE = Math.toRadians(1.5);
    /** Its ball's give at each step: a spring (its pull a tick, the share of its speed kept) and the kicks of a landing and a lift. */
    static final double SPRING = 0.16, DAMP = 0.7, KICK_LAND = 0.012, KICK_LIFT = 0.005, GIVE_MOST = 0.08;
    /** It walks above this speed (blocks a tick). */
    static final double MOVING = 0.008;
    /** Its free spines sway by this much (radians) and bend toward what it attends to by up to the second. */
    static final double SWAY = 0.07, TURN_TO = Math.toRadians(38);
    /** A free spine pressed on rock too near to brace on folds down along it by up to this (radians). */
    static final double FOLD = Math.toRadians(68);
    /** Its coat lies back this far calm (radians), standing out when it bristles. */
    static final double COAT_LIE = Math.toRadians(30);
    /** Dying, its spines droop this far toward the rock under them (of their way, mixed with straight down). */
    static final double DROOP = 1.1;

    // ------------------------------------------------------------------------------------------------ shape
    public final EmberSpec spec;

    // ------------------------------------------------------------------------------------------------ state
    private boolean laid;
    private final Vector3d lastBox = new Vector3d();
    /** The ball's middle, now and last tick (its box's place and its give). */
    final Vector3d p = new Vector3d(), pPrev = new Vector3d();
    /** Its lean, as a turn (axis times radians), now and last tick. */
    private final Vector3d lean = new Vector3d(), leanPrev = new Vector3d();
    private final Matrix3d turn = new Matrix3d(), turnPrev = new Matrix3d();
    /** Its give (the spring of its ball on its spines) and the give's speed. */
    private final Vector3d give = new Vector3d(), giveVel = new Vector3d();
    /** Its going (blocks a tick), eased. */
    private final Vector3d vel = new Vector3d();
    private double speed, flare, focusAmount;
    /** How far it scuttles (0 at its own walk, 1 at a player's): see {@link #QUICK_FROM}. */
    private double quick;
    private int standing, settleNext;
    private Mode mode = Mode.LIVE;
    private long ticks;
    private Footfalls footfalls;
    public final Leg[] legs;
    /** The coat's spines' ways out, in the ball's own frame, now and last tick; each one's way to lie back along. */
    final double[][] coat, coatPrev;
    private final double[][] coatLie;
    private long seed = 0xE3B1A5;

    // scratch
    private final double[] hit = new double[5];
    private final Bow bow = new Bow();
    private final Vector3d t0 = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), t3 = new Vector3d(), t4 = new Vector3d(),
            t5 = new Vector3d(), hipNow = new Vector3d(), wayNow = new Vector3d(), found = new Vector3d(), foundN = new Vector3d();
    /** Scratch of their own: {@link #bendFor}'s, {@link #search}'s, {@link #life}'s and the bow checks', the getters'. */
    private final Vector3d bt = new Vector3d(), sHip = new Vector3d(), sWay = new Vector3d(), sA = new Vector3d(), sB = new Vector3d(),
            sTip = new Vector3d(), sN = new Vector3d(), sBend = new Vector3d(), sRoot = new Vector3d(), lt = new Vector3d(), bp = new Vector3d(),
            rt = new Vector3d(), lw = new Vector3d();
    /** {@link #clearBend}'s own. */
    private final Bow cb = new Bow();
    private final Vector3d cAxis = new Vector3d(), cTry = new Vector3d(), cP = new Vector3d(), cRoot = new Vector3d(), cWay = new Vector3d(),
            cOut = new Vector3d();

    public EmberBody(EmberSpec spec) {
        this.spec = spec;
        legs = new Leg[spec.legs];
        for (int i = 0; i < spec.legs; i++) {
            legs[i] = new Leg(i);
            System.arraycopy(spec.way[i], 0, legs[i].free, 0, 3);
            // each bows to one side of its own when pressed square on (a fixed way across it)
            double[] w = spec.way[i];
            double ax = -w[2], ay = 0.35 * (i % 2 == 0 ? 1 : -1), az = w[0];
            double d = ax * w[0] + ay * w[1] + az * w[2];
            ax -= d * w[0]; ay -= d * w[1]; az -= d * w[2];
            double l = Math.sqrt(ax * ax + ay * ay + az * az);
            if (l < 1.0E-6) { ax = 1; ay = 0; az = 0; l = 1; }
            legs[i].side[0] = ax / l; legs[i].side[1] = ay / l; legs[i].side[2] = az / l;
        }
        int n = spec.coat;
        coat = new double[n][3];
        coatPrev = new double[n][3];
        coatLie = new double[n][3];
        for (int j = 0; j < n; j++) {
            double[] d = spec.coatWay[j];
            // lying back: toward the ball's underside round it, as a pelt lies (any way across, at the poles)
            double gx = -d[0] * d[1], gy = -(1 - d[1] * d[1]), gz = -d[2] * d[1];
            double gl = Math.sqrt(gx * gx + gy * gy + gz * gz);
            if (gl < 1.0E-4) { gx = 1; gy = 0; gz = 0; gl = 1; }
            coatLie[j][0] = gx / gl; coatLie[j][1] = gy / gl; coatLie[j][2] = gz / gl;
            System.arraycopy(d, 0, coat[j], 0, 3);
            System.arraycopy(d, 0, coatPrev[j], 0, 3);
        }
    }

    public void setFootfalls(Footfalls f) { this.footfalls = f; }
    /** Each body its own sway (the entity's id, say). */
    public void setSeed(long s) { this.seed = s * 0x9E3779B97F4A7C15L + 0xE3B1A5; }
    public boolean laid() { return laid; }
    public Mode mode() { return mode; }
    public long ticks() { return ticks; }
    public double speed() { return speed; }

    /** The ball's middle part way through the tick. */
    public void centre(double pt, Vector3d out) { out.set(pPrev).lerp(p, pt); }

    /** The ball's turn (its lean) part way through the tick: from its own frame to the world. */
    public void turn(double pt, Matrix3d out) {
        rotation(rt.set(leanPrev).lerp(lean, pt), out);
    }

    /** The region of blocks the next tick reads: round the ball, with room for its spines' reach. */
    public void bounds(Drive in, int[] out) {
        double r = spec.span() + 0.6 * spec.scale;
        out[0] = (int) Math.floor(in.x - r); out[1] = (int) Math.floor(in.y - r); out[2] = (int) Math.floor(in.z - r);
        out[3] = (int) Math.floor(in.x + r); out[4] = (int) Math.floor(in.y + r); out[5] = (int) Math.floor(in.z + r);
    }

    // ------------------------------------------------------------------------------------------------ the tick

    public void tick(TerrainPatch rock, Drive in) {
        ticks++;
        pPrev.set(p);
        leanPrev.set(lean);
        turnPrev.set(turn);
        for (Leg lg : legs) { lg.prevAt.set(lg.at); lg.prevNrm.set(lg.nrm); lg.prevBend.set(lg.bend); }
        for (int j = 0; j < coat.length; j++) System.arraycopy(coat[j], 0, coatPrev[j], 0, 3);
        mode = in.mode;
        if (!laid || lastBox.distanceSquared(in.x, in.y, in.z) > JUMP * JUMP) {
            lay(rock, in);
            return;
        }
        // its going, eased
        t0.set(in.x, in.y, in.z).sub(lastBox);
        lastBox.set(in.x, in.y, in.z);
        vel.lerp(t0, 0.35);
        speed += (vel.length() - speed) * 0.3;
        quick = clamp01((speed - QUICK_FROM * spec.scale) / ((QUICK_AT - QUICK_FROM) * spec.scale));
        flare += (clamp01(in.flare) - flare) * (in.flare > flare ? 0.35 : 0.05);
        leanTick();
        giveTick();
        p.set(in.x, in.y, in.z).add(give);
        if (mode == Mode.DEAD) dying(rock);
        else spines(rock);
        coatTick(in);
    }

    /** Its lean into its going (and a slow wobble), eased. */
    private void leanTick() {
        double age = ticks + (seed & 0xFFF);
        double s = mode == Mode.DEAD ? 0.0 : Math.min(1.0, speed / (0.055 * spec.scale));
        t1.set(vel.z, 0, -vel.x);                       // the way round which its top tips forward: up x going
        double l = t1.length();
        if (l > 1.0E-6) t1.mul(LEAN * (1.0 + QUICK_LEAN * quick) * s / l); else t1.zero();
        double wob = mode == Mode.DEAD ? 0.0 : WOBBLE * (1.0 + 1.5 * flare);
        t1.add(Math.sin(age * 0.043) * wob, Math.sin(age * 0.029 + 1.3) * wob * 0.6, Math.cos(age * 0.037 + 0.4) * wob);
        lean.lerp(t1, 0.12);
        rotation(lean, turn);
    }

    /** The spring of its ball on its spines: pulled back to its box's place, the kicks of its steps fading. */
    private void giveTick() {
        giveVel.fma(-SPRING, give).mul(DAMP);
        give.add(giveVel);
        double g = give.length(), most = GIVE_MOST * spec.scale;
        if (g > most) give.mul(most / g);
    }

    private void kick(Vector3d way, double amount) {
        giveVel.fma(amount * spec.scale, way);
    }

    // ------------------------------------------------------------------------------------------------ laying out

    /** Laid out afresh where its box is: every spine that reaches rock braced on it, the rest standing out. */
    private void lay(TerrainPatch rock, Drive in) {
        laid = true;
        lastBox.set(in.x, in.y, in.z);
        p.set(in.x, in.y, in.z);
        pPrev.set(p);
        vel.zero();
        give.zero();
        giveVel.zero();
        speed = 0;
        quick = 0;
        flare = clamp01(in.flare);
        lean.zero();
        leanPrev.zero();
        turn.identity();
        turnPrev.identity();
        for (Leg lg : legs) {
            lg.swinging = false;
            System.arraycopy(spec.way[lg.index], 0, lg.free, 0, 3);
            if (in.mode == Mode.LIVE && search(rock, lg, 0.0, found, foundN)) plant(lg, found, foundN);
            else freePose(rock, lg, true);
            lg.prevAt.set(lg.at); lg.prevNrm.set(lg.nrm); lg.prevBend.set(lg.bend);
        }
        for (int j = 0; j < coat.length; j++) {
            System.arraycopy(spec.coatWay[j], 0, coat[j], 0, 3);
            System.arraycopy(spec.coatWay[j], 0, coatPrev[j], 0, 3);
        }
        standing = 0;
    }

    // ------------------------------------------------------------------------------------------------ the spines

    /** One of its walking spines: where its point is, and on what. */
    public static final class Leg {
        public final int index;
        /** Its point set on rock and staying there. */
        public boolean planted;
        /** Where its point is (on rock when planted), the way out of that rock, and the way its rod bows. */
        public final Vector3d at = new Vector3d(), nrm = new Vector3d(0, 1, 0), bend = new Vector3d(1, 0, 0);
        public final Vector3d prevAt = new Vector3d(), prevNrm = new Vector3d(0, 1, 0), prevBend = new Vector3d(1, 0, 0);
        /** Stepping: where it lands and on what; its point's way and reach from its socket as it lifted (in the ball's frame). */
        final Vector3d to = new Vector3d(), toN = new Vector3d(0, 1, 0), fromN = new Vector3d(0, 1, 0), fromBend = new Vector3d();
        final double[] fromWay = new double[3];
        double fromLen, t, step, lift;
        boolean swinging, toRock;
        /** How hard its planted point pulls at it (1: it must step). */
        double strain;
        /** Reaching no rock: its way out now (in the ball's frame). */
        final double[] free = new double[3];
        /** The way it bows when pressed square on (in the ball's frame, across its way out). */
        final double[] side = new double[3];
        /**
         * Reaching no rock it can brace on but pressed against rock too near: how far (0 to 1) it has folded down along
         * that rock, and the way it folds (in the ball's frame, across its way out).
         */
        double fold;
        final double[] foldWay = new double[3];

        Leg(int index) {
            this.index = index;
        }

        /** Where its point is part way through the tick. */
        public void tip(double pt, Vector3d out) { out.set(prevAt).lerp(at, pt); }

        /** The way its rod bows part way through the tick (not unit). */
        public void bend(double pt, Vector3d out) {
            out.set(prevBend).lerp(bend, pt);
            if (out.lengthSquared() < 1.0E-8) out.set(bend);
        }

        public boolean swinging() { return swinging; }
    }

    /** How many of its spines are braced on rock now. */
    public int plantedCount() {
        int n = 0;
        for (Leg lg : legs) if (lg.planted) n++;
        return n;
    }

    public int swingingCount() {
        int n = 0;
        for (Leg lg : legs) if (lg.swinging) n++;
        return n;
    }

    /** A spine's socket where it is now, and its way out. */
    Vector3d socket(int i, Vector3d out) {
        double[] s = spec.socket[i];
        out.set(s[0], s[1], s[2]);
        return turn.transform(out).add(p);
    }

    void way(int i, Vector3d out) {
        double[] w = spec.way[i];
        turn.transform(out.set(w[0], w[1], w[2]));
    }

    /** A spine's socket, the ball turned by {@code turnAt} round its middle at {@code centre} (for drawing part way through a tick). */
    public void socket(int i, Matrix3d turnAt, Vector3d centre, Vector3d out) {
        double[] s = spec.socket[i];
        turnAt.transform(out.set(s[0], s[1], s[2])).add(centre);
    }

    private void spines(TerrainPatch rock) {
        int up = 0;
        for (Leg lg : legs) if (lg.swinging) up++;
        for (Leg lg : legs) if (lg.swinging) swing(rock, lg);
        boolean moving = speed > MOVING * spec.scale;
        standing = moving ? 0 : standing + 1;
        // its scuttle: quicker steps, more up at once, stepping sooner
        int swingFor = (int) Math.round(SWING - QUICK_SWING * quick), swingQuick = (int) Math.round(SWING_QUICK - QUICK_SWING_QUICK * quick);
        int mostUp = MOST_UP + (int) Math.round(QUICK_UP * quick), forcedUp = FORCED_UP + (int) Math.round(QUICK_UP * quick);
        int lifts = 2 + (int) Math.round(QUICK_LIFTS * quick);
        double soon = 0.8 - QUICK_SOONER * quick;
        Leg pick = null;
        for (Leg lg : legs) {
            if (lg.swinging) continue;
            if (!lg.planted) {
                freePose(rock, lg, false);
                if (lg.planted) continue;
                // reaching no rock: now and then it looks again (the ball has come nearer some)
                if ((ticks + lg.index) % FREE_LOOK == 0 && up < mostUp && !neighbourUp(lg) && search(rock, lg, swingFor, found, foundN)) {
                    begin(lg, swingFor, found, foundN, true);
                    up++;
                }
                continue;
            }
            socket(lg.index, hipNow);
            way(lg.index, wayNow);
            lg.strain = strain(hipNow, wayNow, lg.at, spec.length[lg.index]);
            bendTick(rock, lg);
            boolean lost = !standsOn(rock, lg.at, lg.nrm) || bowInRock(rock, lg);
            if (lost || lg.strain >= 1.3) {
                // pulled past bearing (or its rock gone, or its bow pressed into rock however it turns): up at once,
                // neighbours or not
                if (up < forcedUp) { step(rock, lg, swingQuick); up++; }
                continue;
            }
            if (lg.strain >= 1.0 || (moving && lg.strain > soon)) {
                if (!neighbourUp(lg) && (pick == null || lg.strain > pick.strain)) pick = lg;
            }
        }
        // the hardest pulled spine that may lift lifts, and a second if the going is quick (more, scuttling)
        for (int k = 0; k < lifts && pick != null && up < mostUp; k++) {
            step(rock, pick, swingFor);
            up++;
            Leg next = null;
            for (Leg lg : legs) {
                if (!lg.planted || lg.swinging || lg == pick) continue;
                if ((lg.strain >= 1.0 || (moving && lg.strain > soon)) && !neighbourUp(lg) && (next == null || lg.strain > next.strain)) next = lg;
            }
            pick = next;
        }
        // standing: one spine at a time steps back nearer its way out
        if (standing > 10 && up == 0 && ticks % 4 == 0) {
            for (int tries = 0; tries < 3; tries++) {
                Leg lg = legs[settleNext];
                settleNext = (settleNext + 1) % legs.length;
                if (!lg.planted || lg.strain < SETTLE || neighbourUp(lg)) continue;
                step(rock, lg, SWING + 2);
                break;
            }
        }
        // bristling: now and then a braced spine lifts and taps down again where it was
        if (flare > 0.35 && up < 3 && rand(40) < 0.07 * flare) {
            Leg lg = legs[(int) (rand(41) * legs.length)];
            if (lg.planted && !neighbourUp(lg)) {
                begin(lg, 4, lg.at, lg.nrm, true);
                lg.lift = 0.14 * spec.scale;
            }
        }
    }

    private boolean neighbourUp(Leg lg) {
        for (int j : spec.neighbours[lg.index]) if (legs[j].swinging) return true;
        return false;
    }

    /**
     * How hard a braced point at {@code at} pulls at its spine, its socket at {@code hip} and its way out {@code way}: 1
     * where the reach to it has come in to {@link #STEP_SHORT} of its length (bowed past bearing), gone out to {@link
     * #STEP_LONG} of it, or swung {@link #STEP_ANGLE} off its way out.
     */
    static double strain(Vector3d hip, Vector3d way, Vector3d at, double len) {
        double dx = at.x - hip.x, dy = at.y - hip.y, dz = at.z - hip.z;
        double c = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (c < 1.0E-6) return 2.0;
        double q = c / len;
        double s = Math.max((SLACK - q) / (SLACK - STEP_SHORT), (q - TAUT) / (STEP_LONG - TAUT));
        double cos = (dx * way.x + dy * way.y + dz * way.z) / c;
        double ang = Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
        return Math.max(s, ang / STEP_ANGLE);
    }

    /** A spine lifts to step: to where it will hold longest as the ball goes on, or out into the air if it reaches no rock there. */
    private void step(TerrainPatch rock, Leg lg, int ticksFor) {
        if (search(rock, lg, ticksFor, found, foundN)) begin(lg, ticksFor, found, foundN, true);
        else {
            way(lg.index, wayNow);
            System.arraycopy(spec.way[lg.index], 0, lg.free, 0, 3);
            socket(lg.index, hipNow);
            t0.set(hipNow).fma(spec.length[lg.index], wayNow);
            begin(lg, ticksFor, t0, wayNow, false);
        }
    }

    /** Lifts a spine to step: from where its point is to {@code to} (on rock, its way out {@code n}, if {@code rock}). */
    private void begin(Leg lg, int ticksFor, Vector3d to, Vector3d n, boolean onRock) {
        socket(lg.index, hipNow);
        t0.set(lg.at).sub(hipNow);
        lg.fromLen = Math.max(1.0E-3, t0.length());
        toLocal(t0.div(lg.fromLen), lg.fromWay);
        lg.fromN.set(lg.nrm);
        lg.fromBend.set(lg.bend);
        lg.to.set(to);
        lg.toN.set(n);
        lg.toRock = onRock;
        lg.swinging = true;
        lg.planted = false;
        lg.t = 0.0;
        lg.step = 1.0 / ticksFor;
        lg.lift = LIFT * spec.scale;
        // the ball gives a little toward the spine that let go of it
        t1.set(lg.at).sub(hipNow);
        if (t1.lengthSquared() > 1.0E-6) kick(t1.normalize(), KICK_LIFT);
    }

    /**
     * A step: its point swings round its socket from the way it lifted from to the way it lands, its reach eased between,
     * lifted clear of the rock; it lands where it was aimed (looking again if that rock has gone).
     */
    private void swing(TerrainPatch rock, Leg lg) {
        lg.t = Math.min(1.0, lg.t + lg.step);
        int i = lg.index;
        double L = spec.length[i];
        socket(i, hipNow);
        way(i, wayNow);
        if (!lg.toRock) {
            // stepping out into the air: its target goes with the ball
            fromLocal(lg.free, t0);
            lg.to.set(hipNow).fma(L, t0);
            lg.toN.set(t0);
        }
        t0.set(lg.to).sub(hipNow);
        double toLen = Math.max(1.0E-3, t0.length());
        t0.div(toLen);
        fromLocal(lg.fromWay, t1);
        double e = lg.t * lg.t * (3.0 - 2.0 * lg.t);
        slerp(t1, t0, e, t2);
        // lifted: off the rock it leaves and the rock it comes to, and out from the ball; drawn in as it goes over
        double up = Math.sin(Math.PI * Math.pow(lg.t, 0.8));
        double len = (lg.fromLen + (toLen - lg.fromLen) * e) * (1.0 - DRAW_IN * up);
        t3.set(lg.fromN).lerp(lg.toN, e).fma(0.6, wayNow);
        if (t3.lengthSquared() < 1.0E-6) t3.set(wayNow);
        t3.normalize();
        lg.at.set(hipNow).fma(len, t2).fma(up * lg.lift, t3);
        // never through rock on its way: lifted higher until it clears, and meanwhile drawn in toward its socket (in the
        // open) until it is out of the rock
        if (lg.t < 0.95 && rock.solid(lg.at.x, lg.at.y, lg.at.z)) {
            lg.lift += 0.15 * spec.scale;
            lg.at.fma(0.15 * spec.scale, t3);
            for (int k = 0; k < 8 && rock.solid(lg.at.x, lg.at.y, lg.at.z); k++) lg.at.lerp(hipNow, 0.25);
        }
        lg.nrm.set(lg.fromN).lerp(lg.toN, e);
        if (lg.nrm.lengthSquared() < 1.0E-8) lg.nrm.set(wayNow);
        lg.nrm.normalize();
        // its bow turns from the one it had to the one it will have
        bendFor(lg.index, hipNow, lg.to, lg.toN, t4);
        lg.bend.set(lg.fromBend).lerp(t4, e);
        if (lg.bend.lengthSquared() < 1.0E-6) lg.bend.set(t4);
        // and turned round its reach if it would pass through rock on the way
        clearBend(rock, i, hipNow, lg.at, lg.bend, lg.bend);
        if (lg.t >= 1.0) {
            lg.swinging = false;
            if (lg.toRock && !standsOn(rock, lg.to, lg.toN)) {
                lg.toRock = search(rock, lg, 0.0, found, foundN);
                if (lg.toRock) { lg.to.set(found); lg.toN.set(foundN); }
            }
            if (lg.toRock) {
                plant(lg, lg.to, lg.toN);
                t1.set(hipNow).sub(lg.to);
                if (t1.lengthSquared() > 1.0E-6) kick(t1.normalize(), KICK_LAND);
                if (footfalls != null) footfalls.land(lg, speed);
            } else {
                lg.planted = false;
                freePose(rock, lg, true);
            }
        }
    }

    private void plant(Leg lg, Vector3d at, Vector3d n) {
        lg.at.set(at);
        lg.nrm.set(n);
        lg.planted = true;
        lg.swinging = false;
        lg.strain = 0;
        socket(lg.index, hipNow);
        bendFor(lg.index, hipNow, at, n, lg.bend);
    }

    /**
     * A braced spine's bow, eased toward the one its reach now asks (it turns smoothly as its reach swings round), turned
     * round its reach if that one would pass through rock; if the eased bow lies in rock where that one is clear, it is
     * there at once.
     */
    private void bendTick(TerrainPatch rock, Leg lg) {
        int i = lg.index;
        socket(i, hipNow);
        bendFor(i, hipNow, lg.at, lg.nrm, t4);
        boolean clear = clearBend(rock, i, hipNow, lg.at, t4, t4);
        lg.bend.lerp(t4, 0.35 + 0.35 * quick);
        if (lg.bend.lengthSquared() < 1.0E-6 || (clear && !bowClear(rock, i, hipNow, lg.at, lg.bend, false))) lg.bend.set(t4);
    }

    /**
     * {@code want} (the way spine {@code i} bows, its socket at {@code hip}, its point at {@code at}) as it is if its bow
     * is clear of rock, else turned round its reach as little as clears it (its root still leaving the ball outward),
     * into {@code out} (which may be {@code want}); want itself if no turn clears it. Whether the bow given is clear.
     */
    boolean clearBend(TerrainPatch rock, int i, Vector3d hip, Vector3d at, Vector3d want, Vector3d out) {
        if (bowClear(rock, i, hip, at, want, false)) { out.set(want); return true; }
        cAxis.set(at).sub(hip);
        double c = cAxis.length();
        // straight, it has no bow to turn
        if (c < 1.0E-4 || c >= spec.length[i] * (1.0 - 1.0E-4)) { out.set(want); return false; }
        cAxis.div(c);
        double ax = cAxis.x, ay = cAxis.y, az = cAxis.z, kv = ax * want.x + ay * want.y + az * want.z;
        for (double a : BOW_TURNS) {
            // want turned by a round the reach (Rodrigues)
            double ca = Math.cos(a), sa = Math.sin(a);
            cTry.set(want).mul(ca)
                    .add((ay * want.z - az * want.y) * sa, (az * want.x - ax * want.z) * sa, (ax * want.y - ay * want.x) * sa)
                    .fma(kv * (1.0 - ca), cAxis);
            if (bowClear(rock, i, hip, at, cTry, true)) { out.set(cTry); return true; }
        }
        out.set(want);
        return false;
    }

    /** Whether spine {@code i}'s bow from {@code hip} to {@code at}, bowing toward {@code bend}, keeps out of rock (and its root out of the ball). */
    private boolean bowClear(TerrainPatch rock, int i, Vector3d hip, Vector3d at, Vector3d bend, boolean rootOut) {
        cb.set(hip, at, bend, spec.length[i]);
        if (rootOut) {
            way(i, cWay);
            if (cb.rootWay(cRoot).dot(cWay) < ROOT_OUT) return false;
        }
        for (double f : BOW_CHECK) {
            cb.point(f * cb.length, cP);
            if (rock.solid(cP.x, cP.y, cP.z)) return false;
        }
        return true;
    }

    /**
     * The way a spine bows, its socket at {@code hip}, its point at {@code at} on rock whose way out is {@code n}: away
     * from that rock (so its bow never dips into it), turned a little to its own side; pressed square on, to its own side.
     */
    void bendFor(int i, Vector3d hip, Vector3d at, Vector3d n, Vector3d out) {
        Vector3d c5 = bt.set(at).sub(hip);
        double c = c5.length();
        if (c < 1.0E-6) { way(i, out); return; }
        c5.div(c);
        double nx = n.x, ny = n.y, nz = n.z, nd = nx * c5.x + ny * c5.y + nz * c5.z;
        out.set(nx - nd * c5.x, ny - nd * c5.y, nz - nd * c5.z);
        double[] sd = legs[i].side;
        double sx = sd[0], sy = sd[1], sz = sd[2];
        // its own side, in the world
        double wx = turn.m00 * sx + turn.m10 * sy + turn.m20 * sz, wy = turn.m01 * sx + turn.m11 * sy + turn.m21 * sz, wz = turn.m02 * sx + turn.m12 * sy + turn.m22 * sz;
        double d = wx * c5.x + wy * c5.y + wz * c5.z;
        out.add((wx - d * c5.x) * 0.3, (wy - d * c5.y) * 0.3, (wz - d * c5.z) * 0.3);
        if (out.lengthSquared() < 1.0E-8) out.set(wx - d * c5.x, wy - d * c5.y, wz - d * c5.z);
        if (out.lengthSquared() < 1.0E-8) square(out.set(0, 1, 0), c5);
        out.normalize();
    }

    /**
     * Whether a braced spine's bow has come to lie in rock (the ball has gone on round a corner, say): looked at in two
     * places along it, and two more scuttling (when its bow sweeps along the rock so much quicker).
     */
    private boolean bowInRock(TerrainPatch rock, Leg lg) {
        int i = lg.index;
        socket(i, hipNow);
        bow.set(hipNow, lg.at, lg.bend, spec.length[i]);
        bow.point(0.4 * bow.length, bp);
        if (rock.solid(bp.x, bp.y, bp.z)) return true;
        bow.point(0.7 * bow.length, bp);
        if (rock.solid(bp.x, bp.y, bp.z)) return true;
        if (quick < 0.3) return false;
        bow.point(0.22 * bow.length, bp);
        if (rock.solid(bp.x, bp.y, bp.z)) return true;
        bow.point(0.86 * bow.length, bp);
        return rock.solid(bp.x, bp.y, bp.z);
    }

    /**
     * A spine reaching no rock: straight out along its way (swaying, turned toward what it attends to); if its way now
     * meets rock within reach it braces there at once, and if the rock is too near for that it presses on it, bowed,
     * until it next looks round ({@code snap}: set there at once, else eased).
     */
    private void freePose(TerrainPatch rock, Leg lg, boolean snap) {
        int i = lg.index;
        double L = spec.length[i];
        socket(i, hipNow);
        fromLocal(lg.free, t0);
        t1.set(hipNow).fma(L, t0);
        lg.planted = false;
        if (mode == Mode.LIVE && rock.clip(hipNow.x, hipNow.y, hipNow.z, t1.x, t1.y, t1.z, hit) && hit[4] < 0.5) {
            double c = hit[0] * L;
            t2.set(hipNow).fma(c, t0);
            t3.set(hit[1], hit[2], hit[3]);
            if (c >= PLANT_MIN * L && -t3.dot(t0) >= MIN_BITE) {
                plant(lg, t2, t3);
                return;
            }
            // too near to brace on: pressed against it, and folding down along it (off the rock's way in)
            t2.fma(0.02, t3);
            if (snap) lg.at.set(t2); else lg.at.lerp(t2, 0.5);
            lg.nrm.set(t3);
            bendFor(i, hipNow, lg.at, t3, t4);
            lg.bend.lerp(t4, snap ? 1.0 : 0.4);
            way(i, wayNow);
            t4.set(t3).fma(-t3.dot(wayNow), wayNow);
            if (t4.lengthSquared() < 1.0E-4) fromLocal(lg.side, t4);
            t4.normalize();
            toLocal(t4, lg.foldWay);
            lg.fold = Math.min(1.0, lg.fold + (snap ? 1.0 : 0.12));
            return;
        }
        lg.fold = Math.max(0.0, lg.fold - 0.04);
        if (snap) lg.at.set(t1); else lg.at.lerp(t1, 0.5);
        lg.nrm.set(t0);
        // straight: its bow is only the way its rod's sides face
        way(i, wayNow);
        double[] sd = lg.side;
        t4.set(sd[0], sd[1], sd[2]);
        turn.transform(t4);
        lg.bend.lerp(t4, snap ? 1.0 : 0.3);
    }

    // ------------------------------------------------------------------------------------------------ finding rock

    /**
     * Where spine {@code leg}'s point should be set, judged from where its socket will be in {@code ahead} ticks: it looks
     * out from there round its way out (the near rings first, the far ones only if they hold nothing) for rock within
     * reach that its point bites into, its bow clear of rock and its root clear of the ball, and takes the one that will
     * hold longest as the ball goes on ({@link #LIFE_CAP}), then the nearest its way out and the nearest {@link
     * #PLANT_AIM} of its length. {@code out} gets the point (just in the rock), {@code n} the way out of it. False if it
     * reaches no rock that will do.
     */
    boolean search(TerrainPatch rock, Leg leg, double ahead, Vector3d out, Vector3d n) {
        int i = leg.index;
        double L = spec.length[i];
        Vector3d hip = socket(i, sHip).fma(ahead, vel);
        if (rock.solid(hip.x, hip.y, hip.z)) return false;
        Vector3d w = sWay;
        way(i, w);
        // two ways across its way out, to look round it
        Vector3d ea = sA, eb = sB;
        square(ea.set(Math.abs(w.y) < 0.9 ? 0 : 1, Math.abs(w.y) < 0.9 ? 1 : 0, 0), w);
        eb.set(w).cross(ea).normalize();
        boolean moving = speed > MOVING * spec.scale;
        // scuttling, a point bears so much less long that its life is judged over less
        int lifeCap = Math.max(4 * LIFE_STEP, (int) Math.round(LIFE_CAP * (1.0 - (1.0 - QUICK_LIFE) * quick)));
        double best = -Double.MAX_VALUE;
        boolean any = false;
        double turnRound = (seed & 0xFF) / 256.0 * 2.0 * Math.PI;
        for (int ring = 0; ring < RINGS.length; ring++) {
            if (ring == NEAR_RINGS && any) break;
            double a = RINGS[ring], ca = Math.cos(a), sa = Math.sin(a);
            int nr = ROUND[ring];
            for (int k = 0; k < nr; k++) {
                double b = turnRound + ring * 0.7 + 2.0 * Math.PI * k / nr, cb = Math.cos(b), sb = Math.sin(b);
                double dx = w.x * ca + (ea.x * cb + eb.x * sb) * sa, dy = w.y * ca + (ea.y * cb + eb.y * sb) * sa, dz = w.z * ca + (ea.z * cb + eb.z * sb) * sa;
                double reach = PLANT_MAX * L;
                if (!rock.clip(hip.x, hip.y, hip.z, hip.x + dx * reach, hip.y + dy * reach, hip.z + dz * reach, hit) || hit[4] > 0.5) continue;
                double c = hit[0] * reach, q = c / L;
                if (q < PLANT_MIN) continue;
                double bite = -(hit[1] * dx + hit[2] * dy + hit[3] * dz);
                if (bite < MIN_BITE) continue;
                sTip.set(hip.x + dx * c, hip.y + dy * c, hip.z + dz * c);
                sN.set(hit[1], hit[2], hit[3]);
                // its bow: clear of rock, its root leaving the ball outward
                bendFor(i, hip, sTip, sN, sBend);
                bow.set(hip, sTip, sBend, L);
                if (bow.rootWay(sRoot).dot(w) < ROOT_OUT) continue;
                if (bowBlocked(rock)) continue;
                double life = moving ? life(hip, w, sTip, L, lifeCap) : lifeCap;
                double score = (moving ? 3.0 * life / lifeCap : 0.0) - a / RINGS[NEAR_RINGS - 1] - 4.0 * Math.abs(q - PLANT_AIM);
                if (score > best) {
                    best = score;
                    out.set(sTip);
                    n.set(sN);
                    any = true;
                }
            }
        }
        return any;
    }

    /** Whether the bow just set ({@link #bow}) passes through rock short of its point. */
    private boolean bowBlocked(TerrainPatch rock) {
        bow.point(0.3 * bow.length, bp);
        if (rock.solid(bp.x, bp.y, bp.z)) return true;
        bow.point(0.55 * bow.length, bp);
        if (rock.solid(bp.x, bp.y, bp.z)) return true;
        bow.point(0.8 * bow.length, bp);
        return rock.solid(bp.x, bp.y, bp.z);
    }

    /** How many ticks (up to {@code cap}) a point at {@code at} would bear as the ball goes on as it goes now, its socket at {@code hip} now. */
    private int life(Vector3d hip, Vector3d way, Vector3d at, double len, int cap) {
        for (int t = LIFE_STEP; t <= cap; t += LIFE_STEP) {
            lt.set(hip).fma(t, vel);
            if (strain(lt, way, at, len) >= 1.0) return t - LIFE_STEP;
        }
        return cap;
    }

    /** How far it scuttles now (0 at its own walk, 1 at a player's). */
    public double quick() { return quick; }

    /** Whether a point at p (out of its rock along n) stands on rock. */
    private static boolean standsOn(TerrainPatch rock, Vector3d p, Vector3d n) {
        return rock.solid(p.x - n.x * 0.06, p.y - n.y * 0.06, p.z - n.z * 0.06);
    }

    // ------------------------------------------------------------------------------------------------ dying

    /** Dying: every spine lets go and droops toward the rock under it, pressed on whatever it meets. */
    private void dying(TerrainPatch rock) {
        for (Leg lg : legs) {
            lg.swinging = false;
            lg.planted = false;
            int i = lg.index;
            way(i, wayNow);
            // down along the ball's skin from its socket (the topmost stay standing, leaning to their own side)
            double[] sd = lg.side;
            t1.set(sd[0], sd[1], sd[2]);
            turn.transform(t1);
            t0.set(0, -1, 0).fma(wayNow.y, wayNow).mul(DROOP).add(wayNow).fma(0.2, t1);
            if (t0.lengthSquared() < 1.0E-6) t0.set(wayNow);
            t0.normalize();
            toLocal(t0, t2);
            double[] f = lg.free;
            f[0] += (t2.x - f[0]) * 0.18; f[1] += (t2.y - f[1]) * 0.18; f[2] += (t2.z - f[2]) * 0.18;
            double fl = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
            if (fl > 1.0E-6) { f[0] /= fl; f[1] /= fl; f[2] /= fl; }
            socket(i, hipNow);
            fromLocal(f, t0);
            double L = spec.length[i];
            t1.set(hipNow).fma(L, t0);
            if (rock.clip(hipNow.x, hipNow.y, hipNow.z, t1.x, t1.y, t1.z, hit) && hit[4] < 0.5) {
                t1.set(hipNow).fma(Math.max(0.3, hit[0]) * L, t0);
                lg.nrm.set(hit[1], hit[2], hit[3]);
            } else lg.nrm.set(t0);
            lg.at.lerp(t1, 0.3);
            bendFor(i, hipNow, lg.at, lg.nrm, t4);
            lg.bend.lerp(t4, 0.3);
        }
    }

    // ------------------------------------------------------------------------------------------------ the coat and the free spines

    /**
     * The coat: lying back calm, standing out as it bristles, swaying slowly; those on the side toward what it attends
     * to swing to point at it, and its free walking spines turn toward it too, as far as their sockets let them.
     */
    private void coatTick(Drive in) {
        double want = mode == Mode.DEAD ? 0.0 : clamp01(in.focusAmount);
        focusAmount += (want - focusAmount) * (want > focusAmount ? 0.3 : 0.06);
        // where it attends, in the ball's own frame (from its middle)
        double qx = 0, qy = 0, qz = 0;
        boolean focus = focusAmount > 0.02 && in.focus.lengthSquared() > 0;
        if (focus) {
            t0.set(in.focus).sub(p);
            turn.transformTranspose(t0);
            qx = t0.x; qy = t0.y; qz = t0.z;
        }
        double age = ticks + (seed & 0xFF);
        double lie = mode == Mode.DEAD ? 1.4 : (1.0 - flare) * Math.sin(COAT_LIE);
        double sway = mode == Mode.DEAD ? 0.0 : SWAY * (1.0 + flare);
        for (int j = 0; j < coat.length; j++) {
            double[] d = spec.coatWay[j], g = coatLie[j], s = spec.coatSocket[j];
            double ph = j * 2.399963;
            double wx = d[0] + g[0] * lie, wy = d[1] + g[1] * lie, wz = d[2] + g[2] * lie;
            wx += Math.sin(age * 0.06 + ph) * sway; wy += Math.cos(age * 0.05 + ph * 1.7) * sway; wz += Math.sin(age * 0.045 + ph * 0.6) * sway;
            if (focus) {
                double ex = qx - s[0], ey = qy - s[1], ez = qz - s[2], el = Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (el > 1.0E-4) {
                    ex /= el; ey /= el; ez /= el;
                    double dot = ex * d[0] + ey * d[1] + ez * d[2];
                    double w = focusAmount * clamp01((dot + 0.3) / 1.0) * 0.9;
                    wx += (ex - dot * d[0]) * w; wy += (ey - dot * d[1]) * w; wz += (ez - dot * d[2]) * w;
                }
            }
            ease(coat[j], wx, wy, wz, 0.2);
        }
        if (mode == Mode.DEAD) return;
        // its free walking spines: out along their ways, swaying; toward what it attends to
        for (Leg lg : legs) {
            if (lg.planted || lg.swinging) continue;
            int i = lg.index;
            double[] d = spec.way[i], s = spec.socket[i], sd = lg.side;
            double ph = i * 1.618034;
            double sw = sway * 0.8;
            double ox = sd[1] * d[2] - sd[2] * d[1], oy = sd[2] * d[0] - sd[0] * d[2], oz = sd[0] * d[1] - sd[1] * d[0];
            double a1 = Math.sin(age * 0.05 + ph) * sw, a2 = Math.cos(age * 0.041 + ph * 1.3) * sw;
            double wx = d[0] + sd[0] * a1 + ox * a2, wy = d[1] + sd[1] * a1 + oy * a2, wz = d[2] + sd[2] * a1 + oz * a2;
            if (lg.fold > 0) {
                // folded down along rock too near to brace on
                double fa = lg.fold * FOLD, fc = Math.cos(fa), fs = Math.sin(fa);
                double[] fw = lg.foldWay;
                wx = wx * fc + fw[0] * fs; wy = wy * fc + fw[1] * fs; wz = wz * fc + fw[2] * fs;
            }
            if (focus) {
                double ex = qx - s[0], ey = qy - s[1], ez = qz - s[2], el = Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (el > 1.0E-4) {
                    ex /= el; ey /= el; ez /= el;
                    double dot = ex * d[0] + ey * d[1] + ez * d[2];
                    if (dot > 0.1) {
                        // turned toward it by up to TURN_TO, the more the nearer its way already lies
                        double w = focusAmount * Math.tan(TURN_TO) * clamp01((dot - 0.1) / 0.6);
                        wx += (ex - dot * d[0]) * w; wy += (ey - dot * d[1]) * w; wz += (ez - dot * d[2]) * w;
                    }
                }
            }
            ease(lg.free, wx, wy, wz, 0.15);
        }
    }

    /** Eases unit way c toward (wx, wy, wz) by a share, kept unit. */
    private static void ease(double[] c, double wx, double wy, double wz, double rate) {
        double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (wl < 1.0E-6) return;
        wx /= wl; wy /= wl; wz /= wl;
        c[0] += (wx - c[0]) * rate; c[1] += (wy - c[1]) * rate; c[2] += (wz - c[2]) * rate;
        double cl = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
        if (cl > 1.0E-6) { c[0] /= cl; c[1] /= cl; c[2] /= cl; }
    }

    /** A coat spine's way out part way through the tick, in the ball's own frame (unit). */
    public void coatWay(int j, double pt, double[] out) {
        double[] a = coatPrev[j], b = coat[j];
        double x = a[0] + (b[0] - a[0]) * pt, y = a[1] + (b[1] - a[1]) * pt, z = a[2] + (b[2] - a[2]) * pt;
        double d = Math.sqrt(x * x + y * y + z * z);
        if (d < 1.0E-6) { System.arraycopy(spec.coatWay[j], 0, out, 0, 3); return; }
        out[0] = x / d; out[1] = y / d; out[2] = z / d;
    }

    // ------------------------------------------------------------------------------------------------ pricking

    /**
     * Whether its ball or any spine (the whole of each, bowed as it is now) lies in the box (x0, y0, z0) to (x1, y1, z1):
     * the spine's index, {@code -1} for the ball, {@code -2} for none. {@code at} gets the place touched.
     */
    public int touches(double x0, double y0, double z0, double x1, double y1, double z1, Vector3d at) {
        double r = spec.radius * 1.05;
        double cx = clamp(p.x, x0, x1), cy = clamp(p.y, y0, y1), cz = clamp(p.z, z0, z1);
        if ((cx - p.x) * (cx - p.x) + (cy - p.y) * (cy - p.y) + (cz - p.z) * (cz - p.z) < r * r) {
            at.set(cx, cy, cz);
            return -1;
        }
        for (Leg lg : legs) {
            int i = lg.index;
            double L = spec.length[i];
            // its reach first: a spine whose whole length lies clear of the box is passed over
            socket(i, hipNow);
            if (hipNow.x + L < x0 || hipNow.x - L > x1 || hipNow.y + L < y0 || hipNow.y - L > y1 || hipNow.z + L < z0 || hipNow.z - L > z1) continue;
            bow.set(hipNow, lg.at, lg.bend, L);
            int n = 12;
            for (int k = 2; k <= n; k++) {
                bow.point(bow.length * k / n, t3);
                if (t3.x >= x0 && t3.x <= x1 && t3.y >= y0 && t3.y <= y1 && t3.z >= z0 && t3.z <= z1) {
                    at.set(t3);
                    return i;
                }
            }
        }
        return -2;
    }

    /** Spine {@code i}'s bow as it is now (for tests and checks). */
    public Bow bowOf(int i, Bow out) {
        socket(i, hipNow);
        Leg lg = legs[i];
        return out.set(hipNow, lg.at, lg.bend, spec.length[i]);
    }

    // ------------------------------------------------------------------------------------------------ the bow

    /**
     * A walking spine's rod as it is drawn: {@code length} long from its socket to its point. Nearer than its length,
     * it bows: a circular arc of its own length through both, bowing toward {@code bend} (across the reach); as far or
     * further, straight (stretched a little at most).
     */
    public static final class Bow {
        public final Vector3d a = new Vector3d(), b = new Vector3d();
        /** Along the reach, across it toward the bow, and round (the third way: along x across). */
        final Vector3d along = new Vector3d(), across = new Vector3d(), round = new Vector3d(), centre = new Vector3d();
        public double length, chord;
        /** Half the arc's turn (radians; 0 straight) and its radius. */
        double half, radius;
        /** Straight: how much it is stretched to reach. */
        double stretch = 1.0;
        boolean straight;

        /** The most a rod stretches to reach its point. */
        static final double MOST_STRETCH = 1.02;

        public Bow set(Vector3d from, Vector3d to, Vector3d bend, double len) {
            a.set(from);
            b.set(to);
            length = len;
            along.set(to).sub(from);
            chord = along.length();
            if (chord < 1.0E-4) {
                // its point at its socket (never so when posed): straight out along its bow's way
                along.set(bend);
                if (along.lengthSquared() < 1.0E-8) along.set(0, 1, 0);
                along.normalize();
                square(across.set(0, 1, 0), along);
                round.set(across).cross(along).normalize();
                chord = 0.0;
                straight = true;
                half = 0;
                radius = Double.POSITIVE_INFINITY;
                stretch = 1.0;
                return this;
            }
            along.div(chord);
            across.set(bend).fma(-bend.dot(along), along);
            if (across.lengthSquared() < 1.0E-8) square(across.set(0, 1, 0), along);
            else across.normalize();
            round.set(across).cross(along).normalize();
            double q = chord / len;
            if (q >= 1.0 - 1.0E-4) {
                straight = true;
                half = 0;
                radius = Double.POSITIVE_INFINITY;
                stretch = Math.min(MOST_STRETCH, Math.max(1.0, q));
                return this;
            }
            straight = false;
            stretch = 1.0;
            half = halfTurn(Math.max(0.3, q));
            radius = len / (2.0 * half);
            // the arc's middle sits on the far side from the bow
            centre.set(a).add(b).mul(0.5).fma(-radius * Math.cos(half), across);
            return this;
        }

        /** The arc's half turn phi for a reach of q of its length: sin(phi) / phi = q (Newton's way from a series guess). */
        static double halfTurn(double q) {
            double phi = Math.min(Math.PI - 1.0E-3, Math.sqrt(6.0 * (1.0 - q)));
            for (int k = 0; k < 8; k++) {
                double f = Math.sin(phi) - q * phi, d = Math.cos(phi) - q;
                if (Math.abs(d) < 1.0E-9) break;
                double next = phi - f / d;
                if (next <= 1.0E-4) next = phi * 0.5;
                if (next >= Math.PI) next = (phi + Math.PI) * 0.5;
                if (Math.abs(next - phi) < 1.0E-10) { phi = next; break; }
                phi = next;
            }
            return phi;
        }

        /** The point {@code s} along the rod (blocks from its socket). */
        public Vector3d point(double s, Vector3d out) {
            if (straight) return out.set(a).fma(s * stretch, along);
            double th = -half + 2.0 * half * s / length;
            return out.set(centre).fma(radius * Math.sin(th), along).fma(radius * Math.cos(th), across);
        }

        /** The rod's way at {@code s} (unit). */
        public Vector3d tangent(double s, Vector3d out) {
            if (straight) return out.set(along);
            double th = -half + 2.0 * half * s / length;
            return out.set(along).mul(Math.cos(th)).fma(-Math.sin(th), across);
        }

        /** Across the rod at {@code s}, in its bow (unit: out from the arc's middle). */
        public Vector3d normal(double s, Vector3d out) {
            if (straight) return out.set(across);
            double th = -half + 2.0 * half * s / length;
            return out.set(along).mul(Math.sin(th)).fma(Math.cos(th), across);
        }

        /** The rod's way as it leaves its socket. */
        Vector3d rootWay(Vector3d out) { return tangent(0.0, out); }
    }

    // ------------------------------------------------------------------------------------------------ small things

    /** A way in the world into the ball's own frame. */
    private void toLocal(Vector3d w, double[] out) {
        lw.set(w);
        turn.transformTranspose(lw);
        out[0] = lw.x; out[1] = lw.y; out[2] = lw.z;
    }

    private void toLocal(Vector3d w, Vector3d out) {
        turn.transformTranspose(out.set(w));
    }

    /** A way in the ball's own frame into the world. */
    private void fromLocal(double[] w, Vector3d out) {
        turn.transform(out.set(w[0], w[1], w[2]));
    }

    /** The turn of rotation vector r (its axis times its angle, radians). */
    static void rotation(Vector3d r, Matrix3d out) {
        double a = r.length();
        if (a < 1.0E-9) { out.identity(); return; }
        out.rotation(a, r.x / a, r.y / a, r.z / a);
    }

    /** Makes v square to the unit vector a (and unit); if it lies along a, any way square to a. */
    static void square(Vector3d v, Vector3d a) {
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

    /** A number from 0 to 1 that changes every tick, its own for each body and each use. */
    private double rand(int use) {
        long h = seed + ticks * 0x9E3779B97F4A7C15L + use * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
}
