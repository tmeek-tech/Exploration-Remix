package com.explorationremix.creature.firefly;

import java.util.Random;

/**
 * One swarm's fireflies, simulated on the client round the swarm's centre (the server moves only the centre). In
 * flight each firefly steers by what its neighbours do, the way a flock does: it keeps a little apart from those
 * nearest it (separation), turns towards the way they fly (alignment) and drifts towards their middle (cohesion);
 * a spring pulls it back towards the swarm's centre, harder once it strays past the swarm's {@link #radius}, and a
 * slowly turning wander of its own keeps it from settling into a fixed orbit. Together they make a loose, looping cloud
 * that goes where the centre goes, carried along in the swarm's own air. A bigger swarm makes a wider cloud
 * ({@link #radius}), not a thicker one: seen from outside, its fireflies look as far apart as a small swarm's.
 *
 * <p>They keep clear of the bodies among them ({@link #addBody}: players and other creatures). A body moving through
 * the cloud pushes the air aside the way a post pushed through water does: on ahead of it, back round its sides and in
 * again behind it, and the fireflies are carried along by the air, so the cloud parts round the body and closes up
 * behind it; the faster the body, the further out the air it moves. A body standing among them has a cushion round it,
 * about a block deep, that the fireflies drift round rather than into.
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
     * The spring to the centre (at a swarm of {@link #BASE_COUNT}; a wider swarm's is weaker by the square of how much
     * wider it is, so its cloud fills its width as thinly as a small one's), and the extra pull on a firefly further out
     * than the swarm's {@link #radius}.
     */
    static final double K_CENTRE = 0.003, K_FAR = 0.02;
    /**
     * A swarm of up to {@link #BASE_COUNT} fireflies keeps within {@link #BASE_RADIUS} of its centre; a bigger one's
     * cloud is wider by the square root of how many more it holds, up to {@link #MAX_RADIUS}. Seen from outside, a cloud
     * is as crowded as the fireflies lying one behind another across it, so a wider one spreads them thinner in depth
     * to keep them as far apart on the eye as a small swarm's (by the cube root, as in 0.16.0, a big cloud looked
     * thicker than the old ones).
     */
    static final int BASE_COUNT = 16;
    static final double BASE_RADIUS = 1.8, MAX_RADIUS = 5.7;
    /** The wander: its push, and how far its heading turns each tick. */
    static final double W_WANDER = 0.006, WANDER_TURN = 0.35;
    static final double DAMPING = 0.965;
    /**
     * They fly in the swarm's own air: its centre's motion (blocks a tick, eased in by this share a tick) carries them
     * along, so a swarm on the move keeps its shape rather than trailing out behind; a centre that jumps further than
     * {@link #JUMP} in a tick has been moved, not flown.
     */
    static final double CENTRE_EASE = 0.25, JUMP = 2.0;
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
     * The cushion round a body: {@link #BERTH} deep from its box. A firefly inside it flies no nearer the body (none of
     * its flight towards the body is left a third of the way in) and is pushed back out, up to {@link #W_BERTH} a tick
     * against the box.
     */
    static final double BERTH = 1.25, W_BERTH = 0.02;
    /**
     * The air a moving body pushes aside: as it flows round a post of {@link #PART} more than the body's half-width, and
     * {@link #PART_RUN} more again at a sprint ({@link #RUN} blocks a tick; in between by its speed).
     */
    static final double PART = 0.45, PART_RUN = 1.15, RUN = 0.28;
    /** How much of the air's motion a firefly takes up a tick, so it eases into the flow and out of it again. */
    static final double CARRY = 0.6;
    /** Right ahead of a moving body the air spills aside at up to this share of its speed, so nothing is pushed along in front. */
    static final double SLIP = 0.4;
    /** The air moves no faster than this, blocks a tick, however many bodies push it. */
    static final double AIR_MAX = 0.45;
    /** A firefly being shoved may fly up to this fast (at its most urgent; its cruising limit at none). */
    static final double DODGE_SPEED = 0.34;
    /** At most this many bodies are kept clear of at once. */
    public static final int MAX_BODIES = 8;

    /**
     * A startled take-off ({@link #flush}): the wave runs across the patch at this many blocks a tick from the firefly
     * nearest the threat, each one going up to {@link #RIPPLE_JITTER} ticks late or early of its place in the wave; it
     * leaves its face this fast, outwards off it and away from the threat; then the flock flies up to
     * {@link #HURRY_SPEED} for {@link #HURRY_TICKS}, easing back to its cruising limit.
     */
    static final double RIPPLE = 0.35;
    static final int RIPPLE_JITTER = 1;
    static final double STARTLED_OFF = 0.11, STARTLED_AWAY = 0.12, STARTLED_UP = 0.03;
    static final double HURRY_SPEED = 0.3;
    static final int HURRY_TICKS = 100;
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
    // carried by the air the bodies push (blocks a tick), on top of its own flight
    private final double[] kx = new double[MAX], ky = new double[MAX], kz = new double[MAX];
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
    // the centre as it was last tick, and its motion (smoothed)
    private double lastCx, lastCy, lastCz, cvx, cvy, cvz;
    private boolean centred;
    private final double[] force = new double[3];
    private final double[] dodged = new double[7];
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
     * then wider by the square root of how many more, up to {@link #MAX_RADIUS}.
     */
    public static double radius(int n) {
        return Math.min(MAX_RADIUS, BASE_RADIUS * Math.sqrt(Math.max(1.0, n / (double) BASE_COUNT)));
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
        kx[i] = ky[i] = kz[i] = 0.0;
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
        kx[i] = ky[i] = kz[i] = 0.0;
    }

    /** Takes the firefly at index i out of the flock (the last one takes its index). */
    public void remove(int i) {
        int last = --size;
        if (i == last) return;
        x[i] = x[last]; y[i] = y[last]; z[i] = z[last];
        xo[i] = xo[last]; yo[i] = yo[last]; zo[i] = zo[last];
        vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last];
        kx[i] = kx[last]; ky[i] = ky[last]; kz[i] = kz[last];
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
     * What the bodies among them do to firefly i: the air's motion where it is (written into the returned array's first
     * three, blocks a tick), the push the cushions give its own flight (the next three), and how hard it is being shoved
     * (its seventh, 0 to 1: 0 clear of every body).
     *
     * <p>The air round a moving body flows as water does round a post pushed through it: shoved on ahead, back round the
     * sides and in again behind, as fast as the body against it and fading with the square of the distance, the post as
     * wide as the body's speed makes it ({@link #PART}, {@link #PART_RUN}) and rounded off at the body's top and bottom.
     * Right in front of the body the air spills aside, to whichever side of its path the firefly is on (dead ahead, the
     * side its own number gives), so nothing is pushed along before it.
     */
    double[] dodge(int i) {
        double ax = 0, ay = 0, az = 0, qx = 0, qy = 0, qz = 0, urgent = 0;
        for (int b = 0; b < bodies; b++) {
            int k = b * 9;
            double minX = body[k], minY = body[k + 1], minZ = body[k + 2], maxX = body[k + 3], maxY = body[k + 4], maxZ = body[k + 5];
            double mx = body[k + 6], my = body[k + 7], mz = body[k + 8];
            double sp = Math.sqrt(mx * mx + my * my + mz * mz);
            if (sp > 1.0E-3) {
                // from the body's upright middle line, rounded off at its ends
                double hw = Math.max(maxX - minX, maxZ - minZ) * 0.5, cap = Math.min(hw, (maxY - minY) * 0.5);
                double rx = x[i] - (minX + maxX) * 0.5, rz = z[i] - (minZ + maxZ) * 0.5;
                double ry = y[i] - clamp(y[i], minY + cap, maxY - cap);
                double r2 = rx * rx + ry * ry + rz * rz, r = Math.sqrt(r2);
                double post = hw + PART + PART_RUN * Math.min(1.0, sp / RUN), far = post * 5.0;
                if (r > 1.0E-6 && r < far) {
                    double ux = mx / sp, uy = my / sp, uz = mz / sp;
                    double nx = rx / r, ny = ry / r, nz = rz / r;
                    double cos = ux * nx + uy * ny + uz * nz;
                    // as fast as the body against it (and inside it), fading out with distance and above and below it
                    double q = sp * Math.min(1.0, post * post / r2) * (1.0 - r2 / (far * far)) / (1.0 + sq(ry / post));
                    ax += q * (2.0 * cos * nx - ux); ay += q * (2.0 * cos * ny - uy); az += q * (2.0 * cos * nz - uz);
                    if (cos > 0.0) {
                        // in front: spilling aside
                        double lx = rx - ux * cos * r, ly = ry - uy * cos * r, lz = rz - uz * cos * r;
                        double lat = Math.sqrt(lx * lx + ly * ly + lz * lz);
                        if (lat < post) {
                            if (lat < 0.05) {
                                double side = (id[i] & 1) == 0 ? 1.0 : -1.0, hl = Math.sqrt(ux * ux + uz * uz);
                                if (hl < 1.0E-6) { lx = side; lz = 0.0; } else { lx = -uz / hl * side; lz = ux / hl * side; }
                                ly = 0.0;
                            } else {
                                lx /= lat; ly /= lat; lz /= lat;
                            }
                            double s = SLIP * q * cos * (1.0 - lat / post);
                            ax += lx * s; ay += ly * s; az += lz * s;
                        }
                    }
                }
            }
            // the cushion: off the box, never nearer it
            double gx = x[i] - clamp(x[i], minX, maxX), gy = y[i] - clamp(y[i], minY, maxY), gz = z[i] - clamp(z[i], minZ, maxZ);
            double gap = Math.sqrt(gx * gx + gy * gy + gz * gz);
            if (gap < BERTH) {
                double dx = gx, dy = gy, dz = gz, l = gap;
                if (gap < 1.0E-4) {
                    // inside the box: out sideways from its middle
                    dx = x[i] - (minX + maxX) * 0.5; dy = 0.0; dz = z[i] - (minZ + maxZ) * 0.5;
                    l = Math.sqrt(dx * dx + dz * dz);
                    if (l < 1.0E-6) { dx = (id[i] & 1) == 0 ? 1.0 : -1.0; dz = 0.0; l = 1.0; }
                }
                dx /= l; dy /= l; dz /= l;
                double u = 1.0 - gap / BERTH;
                // how fast it closes on the body (its flight and the air's, less the body's own motion)
                double in = (vx[i] + kx[i] - mx) * dx + (vy[i] + ky[i] - my) * dy + (vz[i] + kz[i] - mz) * dz;
                double s = W_BERTH * u * u - (in < 0.0 ? in * Math.min(1.0, 3.0 * u) : 0.0);
                qx += dx * s; qy += dy * s; qz += dz * s;
                urgent = Math.max(urgent, u * u);
            }
        }
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        if (al > AIR_MAX) { double s = AIR_MAX / al; ax *= s; ay *= s; az *= s; al = AIR_MAX; }
        urgent = Math.max(urgent, sq(Math.min(1.0, al / RUN)));
        dodged[0] = ax; dodged[1] = ay; dodged[2] = az;
        dodged[3] = qx; dodged[4] = qy; dodged[5] = qz;
        dodged[6] = urgent;
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
        kx[i] = ky[i] = kz[i] = 0.0;
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
        if (centred) {
            double mx = cx - lastCx, my = cy - lastCy, mz = cz - lastCz;
            if (mx * mx + my * my + mz * mz > JUMP * JUMP) cvx = cvy = cvz = 0.0;
            else { cvx += (mx - cvx) * CENTRE_EASE; cvy += (my - cvy) * CENTRE_EASE; cvz += (mz - cvz) * CENTRE_EASE; }
        }
        lastCx = cx; lastCy = cy; lastCz = cz;
        centred = true;
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
        // the swarm's centre: a spring (weaker by the square of how much wider the swarm is), harder past its edge
        double dx = cx - x[i], dy = cy - y[i], dz = cz - z[i];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double k = K_CENTRE * sq(BASE_RADIUS / r) * pull;
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
        // slowed against the swarm's own air, which moves with its centre
        vx[i] = (vx[i] + fx - cvx) * DAMPING + cvx; vy[i] = (vy[i] + fy - cvy) * DAMPING + cvy; vz[i] = (vz[i] + fz - cvz) * DAMPING + cvz;
        // the bodies among them: the air they push carries it, their cushions turn it
        double urgent = 0.0, airX = 0.0, airY = 0.0, airZ = 0.0;
        if (bodies > 0) {
            double[] g = dodge(i);
            airX = g[0]; airY = g[1]; airZ = g[2];
            vx[i] += g[3]; vy[i] += g[4]; vz[i] += g[5];
            urgent = g[6];
            flare[i] = Math.max(flare[i], urgent * DODGE_FLARE);
        }
        kx[i] += (airX - kx[i]) * CARRY; ky[i] += (airY - ky[i]) * CARRY; kz[i] += (airZ - kz[i]) * CARRY;
        double top = MAX_SPEED + (BURST_SPEED - MAX_SPEED) * scattering() + Math.min(CATCH_UP, Math.max(0.0, d - r) * CATCH_UP_RATE);
        top = Math.max(top, MAX_SPEED + (HURRY_SPEED - MAX_SPEED) * hurry());
        top = Math.max(top, MAX_SPEED + (DODGE_SPEED - MAX_SPEED) * Math.min(1.0, urgent * 2.0));
        // the limits are on its flight about the swarm; the swarm's own motion comes on top
        double ux = vx[i] - cvx, uy = vy[i] - cvy, uz = vz[i] - cvz;
        double sp = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (sp > top) { double s = top / sp; ux *= s; uy *= s; uz *= s; }
        else if (sp < MIN_SPEED) {
            if (sp < 1.0E-6) { ux = wx[i]; uy = wy[i]; uz = wz[i]; sp = 1.0; }
            double s = MIN_SPEED / sp;
            ux *= s; uy *= s; uz *= s;
        }
        vx[i] = ux + cvx; vy[i] = uy + cvy; vz[i] = uz + cvz;
        // lost once it is far out with the burst over, until it is back inside the swarm
        if (d > r + LOST_BEYOND && scatterTicks == 0) lost[i] = true;
        else if (d < r) lost[i] = false;
        move(i, space, cx, cy, cz);
    }

    /**
     * Moves a firefly by its own flight and the air's, one axis at a time: an axis that would take it into rock stops
     * the air's and bounces its flight back. A lost firefly goes on through rock that lies between it and the centre
     * (never away from it, so never down through a floor the centre is above), and one already in rock (it, or the
     * rock, moved) goes straight on until it is out.
     */
    private void move(int i, Space space, double cx, double cy, double cz) {
        double dx = vx[i] + kx[i], dy = vy[i] + ky[i], dz = vz[i] + kz[i];
        if (!space.open(x[i], y[i], z[i])) {
            x[i] += dx; y[i] += dy; z[i] += dz;
            return;
        }
        boolean l = lost[i];
        if (space.open(x[i] + dx, y[i], z[i]) || (l && dx * (cx - x[i]) > 0)) x[i] += dx; else { vx[i] *= -0.5; kx[i] = 0.0; }
        if (space.open(x[i], y[i] + dy, z[i]) || (l && dy * (cy - y[i]) > 0)) y[i] += dy; else { vy[i] *= -0.5; ky[i] = 0.0; }
        if (space.open(x[i], y[i], z[i] + dz) || (l && dz * (cz - z[i]) > 0)) z[i] += dz; else { vz[i] *= -0.5; kz[i] = 0.0; }
    }

    /** Flies a firefly in to its perch, slowing as it nears, and sits it there. */
    private void arrive(int i) {
        double dx = px[i] - x[i], dy = py[i] - y[i], dz = pz[i] - z[i];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < ARRIVE) {
            x[i] = px[i]; y[i] = py[i]; z[i] = pz[i];
            vx[i] = vy[i] = vz[i] = 0.0;
            kx[i] = ky[i] = kz[i] = 0.0;
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
