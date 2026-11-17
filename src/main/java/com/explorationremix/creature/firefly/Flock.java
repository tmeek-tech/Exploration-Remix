package com.explorationremix.creature.firefly;

import java.util.Random;

/**
 * One swarm's fireflies, simulated on the client round the swarm's centre (the server moves only the centre). In
 * flight each firefly steers by what its neighbours do, the way a flock does: it keeps a little apart from those
 * nearest it (separation), turns towards the way they fly (alignment) and drifts towards their middle (cohesion);
 * a spring pulls it back towards the swarm's centre, harder once it strays past the swarm's {@link #radius}, and a
 * slowly turning wander of its own keeps it from settling into a fixed orbit. Together they make a loose, looping cloud
 * that trails after the centre as it drifts. A bigger swarm makes a wider cloud ({@link #radius}), with as much room
 * round each firefly as a small one has.
 *
 * <p>They keep clear of the bodies moving among them ({@link #addBody}: players and other creatures). Each firefly
 * looks a few ticks ahead along a body's way and moves aside, to whichever side of its path it is on, before the body
 * gets there; a body standing still is given about a block of room, a running one more. Fireflies just behind a moving
 * body are drawn a little after it, into its wake, so the cloud parts round it like water and closes up again behind.
 *
 * <p>A burst ({@link #burst}) flings every firefly away from where the strike came from and slackens the pull for a
 * second and a half, so the cloud blows apart and then gathers again round the centre. To land, each firefly is given
 * a point on a block face ({@link #settleOn}); after a short wait of its own it flies to it and sits there until the
 * swarm takes off: calmly ({@link #takeOff}), the fireflies lift off their faces one after another; startled
 * ({@link #flush}), they go up in a wave that starts with those nearest whatever startled them and runs across the
 * patch, each one flaring as it lifts, and the cloud flies hard for a few seconds after.
 *
 * <p>Distances are in blocks, speeds in blocks a tick. Rock comes in through {@link Space}, so the flock itself needs
 * no game classes and can be tested on its own.
 */
public final class Flock {
    /** The most fireflies one flock holds. */
    public static final int MAX = 160;
    /** What each firefly is doing: flying, flying to its perch, sitting on it, or about to lift off it. */
    public static final byte FLY = 0, SETTLE = 1, PERCH = 2, LIFT = 3;

    /** Whether a point is open air a firefly may fly through (not rock, not water). */
    @FunctionalInterface
    public interface Space {
        boolean open(double x, double y, double z);
    }

    /** Space with no rock in it. */
    public static final Space ANYWHERE = (x, y, z) -> true;

    // ------------------------------------------------------------------------------------------------ tuning
    /** Neighbours are the fireflies within this; those within {@link #SEPARATION} push each other apart. */
    static final double NEIGHBOUR = 1.2, SEPARATION = 0.5;
    static final double W_SEPARATE = 0.025, W_ALIGN = 0.03, W_COHERE = 0.002;
    /**
     * The spring to the centre (at a swarm of {@link #BASE_COUNT}; a wider swarm's is as much weaker as it is wider, so
     * its cloud fills its width), and the extra pull on a firefly further out than the swarm's {@link #radius}.
     */
    static final double K_CENTRE = 0.003, K_FAR = 0.02;
    /**
     * A swarm of up to {@link #BASE_COUNT} fireflies keeps within {@link #BASE_RADIUS} of its centre; a bigger one's
     * cloud is wider by the cube root of how many more it holds, as much room round each, up to {@link #MAX_RADIUS}.
     */
    static final int BASE_COUNT = 16;
    static final double BASE_RADIUS = 1.8, MAX_RADIUS = 4.2;
    /** The wander: its push, and how far its heading turns each tick. */
    static final double W_WANDER = 0.006, WANDER_TURN = 0.35;
    static final double DAMPING = 0.965;
    /** Cruising speed limit and the least speed a flying firefly keeps (three blocks a second, and half a block). */
    static final double MAX_SPEED = 0.15, MIN_SPEED = 0.025;
    /** A firefly left behind may fly this much faster, a tick, for each block it is beyond the radius, up to {@link #CATCH_UP}. */
    static final double CATCH_UP_RATE = 0.05, CATCH_UP = 0.15;
    /** A burst: the speed it flings them out at, the speed limit just after it, and how long the pull stays slack. */
    static final double BURST = 0.32, BURST_SPEED = 0.42;
    static final int SCATTER_TICKS = 40;
    /**
     * Further than this beyond the radius from the centre (once a burst is over) a firefly is lost, behind a wall say:
     * it may go through rock on its way back.
     */
    static final double LOST_BEYOND = 1.7;
    /** Flying to its perch: top speed, and how near counts as there. */
    static final double SETTLE_SPEED = 0.1, ARRIVE = 0.03;
    /** Lifting off its face calmly: the push away from it, and the longest wait before it goes (ticks). */
    static final double LIFT_SPEED = 0.07;
    static final int LIFT_WAIT = 12;
    /** How fast a firefly's glow eases into its settled breathing, and back to its flying glow (a tick); startled, faster. */
    static final double SETTLE_EASE = 1.0 / 16.0, WAKE_EASE = 1.0 / 6.0, STARTLED_WAKE_EASE = 1.0 / 3.0;

    /**
     * Bodies: one standing still is kept {@link #BERTH} clear (blocks from its box), one moving at {@link #RUN} (a
     * sprint, blocks a tick) {@link #BERTH_RUN} more, in between by its speed.
     */
    static final double BERTH = 0.9, BERTH_RUN = 1.0, RUN = 0.28;
    /** How far ahead (ticks) a firefly sees a moving body coming: it starts aside this long before the body is level. */
    static final double LOOK = 8.0;
    /** The push aside at its strongest (a firefly against the body's box, now), a tick. */
    static final double W_DODGE = 0.07;
    /** A dodging firefly may fly up to this fast (at its most urgent; its cruising limit at none). */
    static final double DODGE_SPEED = 0.34;
    /**
     * Fireflies behind a moving body within {@link #WAKE_REACH} times its berth are drawn after it, by this share of
     * its motion a tick at the box (none at the edge): the eddy it leaves.
     */
    static final double WAKE = 0.06, WAKE_REACH = 1.4;
    /** At most this many bodies are kept clear of at once. */
    public static final int MAX_BODIES = 8;

    /**
     * A startled take-off ({@link #flush}): the wave runs across the patch at this many blocks a tick from the firefly
     * nearest the threat, each one going up to {@link #RIPPLE_JITTER} ticks late or early of its place in the wave; it
     * leaves its face this fast, outwards off it and away from the threat; then the flock flies up to
     * {@link #HURRY_SPEED} for {@link #HURRY_TICKS}, easing back to its cruising limit.
     */
    static final double RIPPLE = 0.45;
    static final int RIPPLE_JITTER = 2;
    static final double STARTLED_OFF = 0.11, STARTLED_AWAY = 0.12, STARTLED_UP = 0.03;
    static final double HURRY_SPEED = 0.3;
    static final int HURRY_TICKS = 70;
    /**
     * A startled firefly flares, its glow swelling for a moment ({@link #flare}: 1 as it lifts off or is flung, and as
     * much as half that as it dodges), fading by this a tick.
     */
    static final double FLARE_FADE = 1.0 / 14.0, DODGE_FLARE = 0.5;

    // ------------------------------------------------------------------------------------------------ state
    private final Random rng;
    private final double[] x = new double[MAX], y = new double[MAX], z = new double[MAX];
    private final double[] xo = new double[MAX], yo = new double[MAX], zo = new double[MAX];
    private final double[] vx = new double[MAX], vy = new double[MAX], vz = new double[MAX];
    private final double[] wx = new double[MAX], wy = new double[MAX], wz = new double[MAX];
    // the perch: a point on a face, and the way the face looks
    private final double[] px = new double[MAX], py = new double[MAX], pz = new double[MAX];
    private final double[] nx = new double[MAX], ny = new double[MAX], nz = new double[MAX];
    private final double[] settled = new double[MAX], settledO = new double[MAX];
    private final double[] flare = new double[MAX], flareO = new double[MAX];
    // a startled lift-off: the way away from the threat (flat), set by flush
    private final double[] ax = new double[MAX], az = new double[MAX];
    private final boolean[] startled = new boolean[MAX];
    private final byte[] mode = new byte[MAX];
    private final boolean[] lost = new boolean[MAX];    // strayed far out (behind a wall, say): on its way back
    private final int[] wait = new int[MAX];
    private final int[] id = new int[MAX];
    private int size, nextId;
    private int scatterTicks, hurryTicks;
    private final double[] force = new double[3];
    private final double[] dodged = new double[4];
    // the bodies to keep clear of this tick: box (min x y z, max x y z) and motion a tick (x y z)
    private final double[] body = new double[MAX_BODIES * 9];
    private int bodies;

    public Flock(long seed) {
        this.rng = new Random(seed);
    }

    public int size() { return size; }
    /** A firefly's own number: it keeps it while others come and go, so its pulse never jumps. */
    public int id(int i) { return id[i]; }
    public byte mode(int i) { return mode[i]; }
    public double x(int i) { return x[i]; }
    public double y(int i) { return y[i]; }
    public double z(int i) { return z[i]; }
    public double vx(int i) { return vx[i]; }
    public double vy(int i) { return vy[i]; }
    public double vz(int i) { return vz[i]; }
    /** Where it was a tick ago, interpolated towards where it is now ({@code t} from 0 to 1). */
    public double x(int i, double t) { return xo[i] + (x[i] - xo[i]) * t; }
    public double y(int i, double t) { return yo[i] + (y[i] - yo[i]) * t; }
    public double z(int i, double t) { return zo[i] + (z[i] - zo[i]) * t; }
    /** 0 while it flies, easing to 1 as it settles on its face (for {@link Pulse#brightness}). */
    public double settledness(int i, double t) { return settledO[i] + (settled[i] - settledO[i]) * t; }
    /** How much it flares (0 to 1): startled, its glow swells for a moment and fades. */
    public double flare(int i, double t) { return flareO[i] + (flare[i] - flareO[i]) * t; }
    /** How far through its burst the flock still is: 1 just after one, 0 once the pull is back. */
    public double scattering() { return scatterTicks / (double) SCATTER_TICKS; }
    /** How far through its hurry after a startled take-off: 1 as it goes up, 0 once it cruises again. */
    public double hurry() { return hurryTicks / (double) HURRY_TICKS; }
    /** Whether a firefly is on its face (sitting, or about to lift off it). */
    public boolean perched(int i) { return mode[i] == PERCH || mode[i] == LIFT; }

    /**
     * How far from its centre a swarm of {@code n} fireflies keeps: {@link #BASE_RADIUS} up to {@link #BASE_COUNT},
     * then wider by the cube root of how many more, up to {@link #MAX_RADIUS}.
     */
    public static double radius(int n) {
        return Math.min(MAX_RADIUS, BASE_RADIUS * Math.cbrt(Math.max(1.0, n / (double) BASE_COUNT)));
    }

    /** This flock's radius, by how many it has now. */
    public double radius() { return radius(size); }

    // ------------------------------------------------------------------------------------------------ comings and goings

    /** A new firefly near the centre, flying; returns its index, or -1 when the flock is full. */
    public int add(double cx, double cy, double cz) {
        if (size >= MAX) return -1;
        int i = size++;
        double[] r = inBall(0.8 * radius(size) / BASE_RADIUS);
        x[i] = xo[i] = cx + r[0];
        y[i] = yo[i] = cy + r[1] * 0.6;
        z[i] = zo[i] = cz + r[2];
        double[] v = inBall(0.05);
        vx[i] = v[0]; vy[i] = v[1]; vz[i] = v[2];
        double[] w = unitVector();
        wx[i] = w[0]; wy[i] = w[1]; wz[i] = w[2];
        mode[i] = FLY;
        lost[i] = false;
        startled[i] = false;
        wait[i] = 0;
        settled[i] = settledO[i] = 0.0;
        flare[i] = flareO[i] = 0.0;
        id[i] = nextId++;
        return i;
    }

    /** Puts firefly i at (px, py, pz), flying at (qx, qy, qz): for tests that need the flock just so. */
    void put(int i, double px, double py, double pz, double qx, double qy, double qz) {
        x[i] = xo[i] = px; y[i] = yo[i] = py; z[i] = zo[i] = pz;
        vx[i] = qx; vy[i] = qy; vz[i] = qz;
    }

    /** Takes the firefly at index i out of the flock (the last one takes its index). */
    public void remove(int i) {
        int last = --size;
        if (i == last) return;
        x[i] = x[last]; y[i] = y[last]; z[i] = z[last];
        xo[i] = xo[last]; yo[i] = yo[last]; zo[i] = zo[last];
        vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last];
        wx[i] = wx[last]; wy[i] = wy[last]; wz[i] = wz[last];
        px[i] = px[last]; py[i] = py[last]; pz[i] = pz[last];
        nx[i] = nx[last]; ny[i] = ny[last]; nz[i] = nz[last];
        settled[i] = settled[last]; settledO[i] = settledO[last];
        flare[i] = flare[last]; flareO[i] = flareO[last];
        ax[i] = ax[last]; az[i] = az[last];
        startled[i] = startled[last];
        mode[i] = mode[last];
        lost[i] = lost[last];
        wait[i] = wait[last];
        id[i] = id[last];
    }

    /** Takes out the {@code k} fireflies nearest (ox, oy, oz): the ones a hunter's jaws closed on. */
    public void removeNearest(double ox, double oy, double oz, int k) {
        for (int n = 0; n < k && size > 0; n++) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < size; i++) {
                double d = sq(x[i] - ox) + sq(y[i] - oy) + sq(z[i] - oz);
                if (d < bestD) { bestD = d; best = i; }
            }
            remove(best);
        }
    }

    // ------------------------------------------------------------------------------------------------ bodies among them

    /** Forgets the bodies of the last tick: give it this tick's with {@link #addBody} before {@link #step}. */
    public void clearBodies() { bodies = 0; }

    /**
     * A body to keep clear of this tick: its box, and how far it moved in the last tick (blocks). Past
     * {@link #MAX_BODIES} more are ignored.
     */
    public void addBody(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double mvx, double mvy, double mvz) {
        if (bodies >= MAX_BODIES) return;
        int k = bodies++ * 9;
        body[k] = minX; body[k + 1] = minY; body[k + 2] = minZ;
        body[k + 3] = maxX; body[k + 4] = maxY; body[k + 5] = maxZ;
        body[k + 6] = mvx; body[k + 7] = mvy; body[k + 8] = mvz;
    }

    public int bodies() { return bodies; }

    /**
     * How a body's box keeps firefly i clear of it: the push aside (written into the returned array's first three, a tick)
     * and how urgent it is (its fourth, 0 to 1: 0 clear of every body). The firefly looks along each body's way up to
     * {@link #LOOK} ticks ahead, to when the body is level with it, and keeps the berth from where the box will be then:
     * aside from the body's path, to whichever side of it the firefly is on (straight ahead of it, the side its own
     * number gives), never ahead of it; nearer and sooner, harder. Behind a moving body it is drawn after it a little.
     */
    double[] dodge(int i) {
        double fx = 0, fy = 0, fz = 0, urgent = 0;
        for (int b = 0; b < bodies; b++) {
            int k = b * 9;
            double mvx = body[k + 6], mvy = body[k + 7], mvz = body[k + 8];
            double sp2 = mvx * mvx + mvy * mvy + mvz * mvz, sp = Math.sqrt(sp2);
            double berth = BERTH + BERTH_RUN * Math.min(1.0, sp / RUN);
            double cx = (body[k] + body[k + 3]) * 0.5, cy = (body[k + 1] + body[k + 4]) * 0.5, cz = (body[k + 2] + body[k + 5]) * 0.5;
            // when the body will be level with it (none: it is behind the body, or the body stands still)
            double t = 0.0;
            if (sp > 1.0E-3) t = Math.max(0.0, Math.min(LOOK, ((x[i] - cx) * mvx + (y[i] - cy) * mvy + (z[i] - cz) * mvz) / sp2));
            // the gap to the box as it will be then
            double ox = mvx * t, oy = mvy * t, oz = mvz * t;
            double gx = x[i] - clamp(x[i], body[k] + ox, body[k + 3] + ox);
            double gy = y[i] - clamp(y[i], body[k + 1] + oy, body[k + 4] + oy);
            double gz = z[i] - clamp(z[i], body[k + 2] + oz, body[k + 5] + oz);
            double gap = Math.sqrt(gx * gx + gy * gy + gz * gz);
            if (sp > 1.0E-3 && t == 0.0 && gap < berth * WAKE_REACH) {
                // behind it: drawn a little after it
                double w = WAKE * (1.0 - gap / (berth * WAKE_REACH));
                fx += mvx * w; fy += mvy * w; fz += mvz * w;
            }
            if (gap >= berth) continue;
            // away from the box: from its middle when inside it
            double dx = gap > 1.0E-4 ? gx : x[i] - (cx + ox), dy = gap > 1.0E-4 ? gy : y[i] - (cy + oy), dz = gap > 1.0E-4 ? gz : z[i] - (cz + oz);
            if (sp > 1.0E-3) {
                // aside from its path, never ahead of it
                double along = (dx * mvx + dy * mvy + dz * mvz) / sp2;
                dx -= mvx * along; dy -= mvy * along; dz -= mvz * along;
                if (dx * dx + dy * dy + dz * dz < 1.0E-6) {
                    // dead ahead: off to the side its number gives, a little up
                    double side = (id[i] & 1) == 0 ? 1.0 : -1.0;
                    double hx = -mvz, hz = mvx, hl = Math.sqrt(hx * hx + hz * hz);
                    if (hl < 1.0E-6) { hx = 1.0; hz = 0.0; hl = 1.0; }
                    dx = hx / hl * side; dy = 0.3; dz = hz / hl * side;
                }
            }
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l < 1.0E-9) { dx = 0.0; dy = 1.0; dz = 0.0; l = 1.0; }
            double u = 1.0 - gap / berth;
            u *= u * (1.0 - 0.6 * t / LOOK);
            double s = W_DODGE * u / l;
            fx += dx * s; fy += dy * s; fz += dz * s;
            urgent = Math.max(urgent, u);
        }
        dodged[0] = fx; dodged[1] = fy; dodged[2] = fz; dodged[3] = urgent;
        return dodged;
    }

    // ------------------------------------------------------------------------------------------------ landing and taking off

    /** Sends a firefly to sit on this point, after {@code delay} ticks more of flying. */
    public void settleOn(int i, Perches.Spot s, int delay) {
        perchAt(i, s);
        mode[i] = SETTLE;
        wait[i] = Math.max(0, delay);
    }

    /** Puts a firefly on this point at once, already settled (a swarm first seen sitting). */
    public void seat(int i, Perches.Spot s) {
        perchAt(i, s);
        mode[i] = PERCH;
        x[i] = xo[i] = s.x(); y[i] = yo[i] = s.y(); z[i] = zo[i] = s.z();
        vx[i] = vy[i] = vz[i] = 0.0;
        settled[i] = settledO[i] = 1.0;
    }

    private void perchAt(int i, Perches.Spot s) {
        px[i] = s.x(); py[i] = s.y(); pz[i] = s.z();
        nx[i] = s.nx(); ny[i] = s.ny(); nz[i] = s.nz();
    }

    /** The swarm takes off calmly: those on their way to a perch fly on, those sitting lift off one after another. */
    public void takeOff() {
        for (int i = 0; i < size; i++) {
            if (mode[i] == SETTLE) mode[i] = FLY;
            else if (mode[i] == PERCH) { mode[i] = LIFT; wait[i] = rng.nextInt(LIFT_WAIT + 1); startled[i] = false; }
        }
    }

    /**
     * Something startled the landed swarm from (fx, fy, fz): every firefly on its face lifts off in a wave that starts
     * with the one nearest the threat and runs across the patch at {@link #RIPPLE} blocks a tick, each flaring as it
     * goes and flying off its face and away; those still on their way down turn and flee at once. The flock then flies
     * hard for a while ({@link #HURRY_TICKS}).
     */
    public void flush(double fx, double fy, double fz) {
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < size; i++)
            if (perched(i)) nearest = Math.min(nearest, Math.sqrt(sq(x[i] - fx) + sq(y[i] - fy) + sq(z[i] - fz)));
        for (int i = 0; i < size; i++) {
            double dx = x[i] - fx, dz = z[i] - fz, l = Math.sqrt(dx * dx + dz * dz);
            if (l < 1.0E-4) { double[] r = unitVector(); dx = r[0]; dz = r[2]; l = Math.sqrt(dx * dx + dz * dz) + 1.0E-9; }
            ax[i] = dx / l; az[i] = dz / l;
            if (mode[i] == PERCH || mode[i] == LIFT) {
                double d = Math.sqrt(dx * dx + sq(y[i] - fy) + dz * dz);
                mode[i] = LIFT;
                startled[i] = true;
                wait[i] = Math.max(0, (int) Math.round((d - nearest) / RIPPLE) + rng.nextInt(2 * RIPPLE_JITTER + 1) - RIPPLE_JITTER);
            } else {
                // in the air: away at once
                mode[i] = FLY;
                wait[i] = 0;
                vx[i] += ax[i] * STARTLED_AWAY; vy[i] += STARTLED_UP; vz[i] += az[i] * STARTLED_AWAY;
                flare[i] = Math.max(flare[i], 0.6);
            }
        }
        hurryTicks = HURRY_TICKS;
    }

    /** Something struck at the swarm from (fx, fy, fz): every firefly is flung away from it, and the pull goes slack. */
    public void burst(double fx, double fy, double fz) {
        scatterTicks = SCATTER_TICKS;
        for (int i = 0; i < size; i++) {
            double dx = x[i] - fx, dy = y[i] - fy, dz = z[i] - fz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double[] r = unitVector();
            if (d < 1.0E-3) { dx = r[0]; dy = r[1]; dz = r[2]; d = 1.0; }
            // mostly straight away from the strike, every one a little askew
            dx = dx / d + r[0] * 0.6; dy = dy / d + r[1] * 0.6 + 0.25; dz = dz / d + r[2] * 0.6;
            double s = BURST * (0.7 + 0.6 * rng.nextDouble()) / Math.sqrt(dx * dx + dy * dy + dz * dz);
            vx[i] = dx * s; vy[i] = dy * s; vz[i] = dz * s;
            mode[i] = FLY;
            wait[i] = 0;
            flare[i] = 1.0;
        }
    }

    // ------------------------------------------------------------------------------------------------ one tick

    /** Moves every firefly one tick round the centre (cx, cy, cz), keeping out of rock and clear of the bodies. */
    public void step(double cx, double cy, double cz, Space space) {
        if (scatterTicks > 0) scatterTicks--;
        if (hurryTicks > 0) hurryTicks--;
        for (int i = 0; i < size; i++) {
            xo[i] = x[i]; yo[i] = y[i]; zo[i] = z[i];
            settledO[i] = settled[i];
            flareO[i] = flare[i];
        }
        double pull = 1.0 - scattering();
        double r = radius();
        for (int i = 0; i < size; i++) {
            switch (mode[i]) {
                case PERCH -> { }
                case LIFT -> { if (--wait[i] <= 0) liftOff(i); }
                case SETTLE -> {
                    if (wait[i] > 0) { wait[i]--; fly(i, cx, cy, cz, pull, r, space); }
                    else arrive(i);
                }
                default -> fly(i, cx, cy, cz, pull, r, space);
            }
            boolean sits = perched(i);
            double wake = startled[i] ? STARTLED_WAKE_EASE : WAKE_EASE;
            settled[i] = sits ? Math.min(1.0, settled[i] + SETTLE_EASE) : Math.max(0.0, settled[i] - wake);
            flare[i] = Math.max(0.0, flare[i] - FLARE_FADE);
        }
    }

    private boolean flying(int j) {
        return mode[j] == FLY || (mode[j] == SETTLE && wait[j] > 0);
    }

    /**
     * The steering on firefly i from its neighbours and the swarm's centre (cx, cy, cz), its wander and the bodies
     * left out: apart from those nearest it, the way its neighbours fly, towards their middle, and back to the centre,
     * harder past the radius {@code r}; {@code pull} (0 to 1) slackens the last two after a burst. Written into, and
     * returned as, a three-number array the flock reuses.
     */
    double[] steer(int i, double cx, double cy, double cz, double pull, double r) {
        double fx = 0, fy = 0, fz = 0;
        // the neighbours: keep apart from the nearest, fly as they fly, drift to their middle
        int n = 0;
        double mx = 0, my = 0, mz = 0, ax = 0, ay = 0, az = 0;
        for (int j = 0; j < size; j++) {
            if (j == i || !flying(j)) continue;
            double dx = x[i] - x[j], dy = y[i] - y[j], dz = z[i] - z[j];
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > NEIGHBOUR * NEIGHBOUR) continue;
            n++;
            mx += x[j]; my += y[j]; mz += z[j];
            ax += vx[j]; ay += vy[j]; az += vz[j];
            if (d2 < SEPARATION * SEPARATION && d2 > 1.0E-8) {
                double d = Math.sqrt(d2);
                double s = W_SEPARATE * (SEPARATION - d) / SEPARATION / d;
                fx += dx * s; fy += dy * s; fz += dz * s;
            }
        }
        if (n > 0) {
            fx += (ax / n - vx[i]) * W_ALIGN;
            fy += (ay / n - vy[i]) * W_ALIGN;
            fz += (az / n - vz[i]) * W_ALIGN;
            fx += (mx / n - x[i]) * W_COHERE * pull;
            fy += (my / n - y[i]) * W_COHERE * pull;
            fz += (mz / n - z[i]) * W_COHERE * pull;
        }
        // the swarm's centre: a spring (as much weaker as the swarm is wider), harder past the swarm's edge
        double dx = cx - x[i], dy = cy - y[i], dz = cz - z[i];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double k = K_CENTRE * BASE_RADIUS / r * pull;
        fx += dx * k; fy += dy * k; fz += dz * k;
        if (d > r) {
            double s = K_FAR * (d - r) / d * pull;
            fx += dx * s; fy += dy * s; fz += dz * s;
        }
        force[0] = fx; force[1] = fy; force[2] = fz;
        return force;
    }

    /** {@link #steer} for the flock's radius now. */
    double[] steer(int i, double cx, double cy, double cz, double pull) {
        return steer(i, cx, cy, cz, pull, radius());
    }

    private void fly(int i, double cx, double cy, double cz, double pull, double r, Space space) {
        double[] f = steer(i, cx, cy, cz, pull, r);
        double fx = f[0], fy = f[1], fz = f[2];
        double d = Math.sqrt(sq(cx - x[i]) + sq(cy - y[i]) + sq(cz - z[i]));
        // its own wander, turning a little each tick
        double[] w = unitVector();
        wx[i] += w[0] * WANDER_TURN; wy[i] += w[1] * WANDER_TURN; wz[i] += w[2] * WANDER_TURN;
        double wl = Math.sqrt(wx[i] * wx[i] + wy[i] * wy[i] + wz[i] * wz[i]);
        if (wl < 1.0E-6) { wx[i] = 1.0; wy[i] = 0.0; wz[i] = 0.0; wl = 1.0; }
        wx[i] /= wl; wy[i] /= wl; wz[i] /= wl;
        fx += wx[i] * W_WANDER; fy += wy[i] * W_WANDER * 0.6; fz += wz[i] * W_WANDER;
        // the bodies among them
        double urgent = 0.0;
        if (bodies > 0) {
            double[] g = dodge(i);
            fx += g[0]; fy += g[1]; fz += g[2];
            urgent = g[3];
            flare[i] = Math.max(flare[i], urgent * DODGE_FLARE);
        }

        vx[i] = (vx[i] + fx) * DAMPING; vy[i] = (vy[i] + fy) * DAMPING; vz[i] = (vz[i] + fz) * DAMPING;
        double top = MAX_SPEED + (BURST_SPEED - MAX_SPEED) * scattering() + Math.min(CATCH_UP, Math.max(0.0, d - r) * CATCH_UP_RATE);
        top = Math.max(top, MAX_SPEED + (HURRY_SPEED - MAX_SPEED) * hurry());
        top = Math.max(top, MAX_SPEED + (DODGE_SPEED - MAX_SPEED) * Math.min(1.0, urgent * 2.0));
        double sp = Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
        if (sp > top) { double s = top / sp; vx[i] *= s; vy[i] *= s; vz[i] *= s; }
        else if (sp < MIN_SPEED) {
            if (sp < 1.0E-6) { vx[i] = wx[i]; vy[i] = wy[i]; vz[i] = wz[i]; sp = 1.0; }
            double s = MIN_SPEED / sp;
            vx[i] *= s; vy[i] *= s; vz[i] *= s;
        }
        // lost once it is far out with the burst over, until it is back inside the swarm
        if (d > r + LOST_BEYOND && scatterTicks == 0) lost[i] = true;
        else if (d < r) lost[i] = false;
        move(i, space, cx, cy, cz);
    }

    /**
     * Moves a firefly by its velocity, one axis at a time: an axis that would take it into rock bounces back. A lost
     * firefly goes on through rock that lies between it and the centre (never away from it, so never down through a
     * floor the centre is above), and one already in rock (it, or the rock, moved) goes straight on until it is out.
     */
    private void move(int i, Space space, double cx, double cy, double cz) {
        if (!space.open(x[i], y[i], z[i])) {
            x[i] += vx[i]; y[i] += vy[i]; z[i] += vz[i];
            return;
        }
        boolean l = lost[i];
        if (space.open(x[i] + vx[i], y[i], z[i]) || (l && vx[i] * (cx - x[i]) > 0)) x[i] += vx[i]; else vx[i] *= -0.5;
        if (space.open(x[i], y[i] + vy[i], z[i]) || (l && vy[i] * (cy - y[i]) > 0)) y[i] += vy[i]; else vy[i] *= -0.5;
        if (space.open(x[i], y[i], z[i] + vz[i]) || (l && vz[i] * (cz - z[i]) > 0)) z[i] += vz[i]; else vz[i] *= -0.5;
    }

    /** Flies a firefly in to its perch, slowing as it nears, and sits it there. */
    private void arrive(int i) {
        double dx = px[i] - x[i], dy = py[i] - y[i], dz = pz[i] - z[i];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < ARRIVE) {
            x[i] = px[i]; y[i] = py[i]; z[i] = pz[i];
            vx[i] = vy[i] = vz[i] = 0.0;
            mode[i] = PERCH;
            return;
        }
        double want = Math.min(SETTLE_SPEED, 0.02 + d * 0.2) / d;
        vx[i] += (dx * want - vx[i]) * 0.3;
        vy[i] += (dy * want - vy[i]) * 0.3;
        vz[i] += (dz * want - vz[i]) * 0.3;
        // never overshoot the perch
        double sp = Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
        if (sp > d) { double s = d / sp; vx[i] *= s; vy[i] *= s; vz[i] *= s; }
        x[i] += vx[i]; y[i] += vy[i]; z[i] += vz[i];
    }

    private void liftOff(int i) {
        double[] r = unitVector();
        if (startled[i]) {
            // up off its face and away from what startled it, flaring
            vx[i] = nx[i] * STARTLED_OFF + ax[i] * STARTLED_AWAY + r[0] * 0.03;
            vy[i] = ny[i] * STARTLED_OFF + STARTLED_UP + r[1] * 0.03;
            vz[i] = nz[i] * STARTLED_OFF + az[i] * STARTLED_AWAY + r[2] * 0.03;
            flare[i] = 1.0;
        } else {
            vx[i] = nx[i] * LIFT_SPEED + r[0] * 0.02;
            vy[i] = ny[i] * LIFT_SPEED + r[1] * 0.02 + 0.01;
            vz[i] = nz[i] * LIFT_SPEED + r[2] * 0.02;
        }
        mode[i] = FLY;
        wait[i] = 0;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private double[] unitVector() {
        double a = rng.nextDouble() * Math.PI * 2.0, u = rng.nextDouble() * 2.0 - 1.0, s = Math.sqrt(1.0 - u * u);
        return new double[]{s * Math.cos(a), u, s * Math.sin(a)};
    }

    private double[] inBall(double r) {
        double[] u = unitVector();
        double k = r * Math.cbrt(rng.nextDouble());
        return new double[]{u[0] * k, u[1] * k, u[2] * k};
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }

    private static double sq(double v) { return v * v; }
}
