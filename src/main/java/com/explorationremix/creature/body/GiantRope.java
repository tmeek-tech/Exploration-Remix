package com.explorationremix.creature.body;

/**
 * The giant Skein's body as it drops from a ceiling (0.21.5, as the drop card's first pick: head first, its body peeling
 * off after it like a rope): a heavy rope of beads along its length. Its head lets go and plunges straight down; each
 * bead behind holds the rock it lay on until the body ahead of it pulls it off (or the peel that runs down the body from
 * the head reaches it), then falls, twisting onto its belly, swings and comes to rest on whatever it lands on, its links
 * kept their length, never sinking into rock. When it has (nearly) all come to rest, or lain a while, the giant crawls
 * out of the coils it lies in (its trail is laid along them).
 *
 * <p>Each bead is a point on the body's line, kept {@link #CLEAR} from all rock (the line's own height off the rock it
 * crawls on, so the body drawn round it lies on the rock whichever way it has rolled), with the way its back faces for
 * drawing; samples come back as the body's own do, a point on the rock and its back ({@link #sample}).
 *
 * <p>Plain numbers over a {@link Terrain}: the server and each client drop it alike from where the body lay.
 */
public final class GiantRope {
    /** Blocks between beads along the body, and how far each lies out from the rock (its line's height off it, half its thickness). */
    public static final double SPACE = 0.6, CLEAR = 0.75;
    /** Gravity and the air's drag on the beads (as the game's falls), and the rock's grip on a bead resting on it. */
    static final double GRAVITY = 0.08, DRAG = 0.985, GRIP = 0.6;
    /** A bead resting on rock and sliding slower than this (blocks a tick) along it stops there (the rock holds it). */
    static final double STICK = 0.06;
    /**
     * How fast a bead's back rolls round (radians a tick): falling, up to the sky (it twists as it falls, to land on its
     * belly); resting on rock, out from it (over onto its belly, if it landed on its back or its side).
     */
    static final double ROLL = 0.35;
    /** A bead this little further than its clearance from rock still rests on it. */
    static final double TOUCH = 0.06;
    /** How fast the peel runs down the body from the head (beads a tick), and how hard a pull takes a bead off. */
    static final double PEEL = 1.6, PULL = 1.2;
    /** Passes of the links' lengths each tick. */
    static final int PASSES = 8;
    /**
     * At rest: no bead moving faster than this (blocks a tick) for this many ticks; the longest it falls; and the longest
     * it lies once its head is down (its body sliding down a cliff below its ledge or hanging off it is let be then).
     */
    static final double STILL = 0.05;
    static final int STILL_TICKS = 6, MOST_TICKS = 140, DOWN_MOST = 70;
    /**
     * Near enough at rest to get up from: all of it off the rock it lay on, none moving faster than this (a body
     * sliding down a wall slides about 0.13 a tick), and none with further than this to roll (radians) onto its belly,
     * these ticks running.
     */
    static final double QUIET = 0.2, QUIET_TURN = 0.35;
    static final int QUIET_TICKS = 3;
    /** The most its sides are eased out of the rock in a tick (blocks). */
    static final double SIDE_STEP = 0.15;

    private final int n;
    /** Its body's width either side of its line, at the head and at the tip, and how far its sides keep off the rock. */
    private final double half, tail, room;
    private final double[] x, xo, was, nrm, wasN, held;
    private final boolean[] holding, touching;
    private final double[] contact;
    private double peel, headVy;
    private boolean headDown;
    private int ticks, still, quiet, down;
    private double moving;
    private int movingAt;
    private final RockClearance rock = new RockClearance();
    private final double[] q = new double[4], c = new double[6];

    /** A rope as long as a body of {@code length} blocks, as thin as it is thick. */
    public GiantRope(double length) {
        this(length, 0.0, 0.0, 0.0);
    }

    /**
     * A rope as long as a body of {@code length} blocks and {@code half} blocks wide either side of its line at the
     * head, narrowing to {@code tail} at its tip, whose sides are kept {@code room} from the rock (RockClearance#
     * clearWide): it lands beside a wall, or across a cleft, without its sides going into the rock.
     */
    public GiantRope(double length, double half, double tail, double room) {
        this.half = half;
        this.tail = tail;
        this.room = room;
        this.n = Math.max(2, (int) Math.ceil(length / SPACE) + 1);
        x = new double[3 * n]; xo = new double[3 * n]; was = new double[3 * n];
        nrm = new double[3 * n]; wasN = new double[3 * n]; held = new double[3 * n];
        holding = new boolean[n]; touching = new boolean[n]; contact = new double[3 * n];
    }

    public int beads() { return n; }
    /** The place along the body of bead i (blocks back from the head). */
    public static double beadAt(int i) { return i * SPACE; }
    public int ticks() { return ticks; }
    /** Whether its head has come down on rock. */
    public boolean headDown() { return headDown; }

    /**
     * Sets it up from the body as it lies: {@code at} holds six numbers a bead (a point on the rock and its back, as the
     * body samples them at {@link #beadAt}); beads further back than {@code free} blocks (the front reared up off the
     * rock is free already) hold the rock they lie on.
     */
    public void start(double[] at, double free) {
        for (int i = 0; i < n; i++) {
            int o = 6 * i, k = 3 * i;
            double nx = at[o + 3], ny = at[o + 4], nz = at[o + 5];
            x[k] = at[o] + nx * CLEAR; x[k + 1] = at[o + 1] + ny * CLEAR; x[k + 2] = at[o + 2] + nz * CLEAR;
            nrm[k] = nx; nrm[k + 1] = ny; nrm[k + 2] = nz;
            held[k] = nx; held[k + 1] = ny; held[k + 2] = nz;
            holding[i] = beadAt(i) > free;
            touching[i] = false;
        }
        System.arraycopy(x, 0, xo, 0, x.length);
        System.arraycopy(x, 0, was, 0, x.length);
        System.arraycopy(nrm, 0, wasN, 0, nrm.length);
        peel = 0.0;
        headVy = 0.0;
        headDown = false;
        ticks = 0;
        still = 0;
        quiet = 0;
        down = 0;
    }

    /** The head's bead: its place (the line of the body there, {@link #CLEAR} out from the rock under it) into o. */
    public void head(double[] o) {
        o[0] = x[0]; o[1] = x[1]; o[2] = x[2];
    }

    /** One tick of the fall. */
    public void tick(Terrain t) {
        ticks++;
        System.arraycopy(x, 0, was, 0, x.length);
        System.arraycopy(nrm, 0, wasN, 0, nrm.length);
        // the head plunges straight down until it comes down on rock
        if (!headDown) {
            headVy = (headVy - GRAVITY) * DRAG;
            int steps = Math.max(1, (int) Math.ceil(Math.abs(headVy) / 0.3));
            for (int s = 0; s < steps && !headDown; s++) {
                x[1] += headVy / steps;
                boolean near = rock.nearest(t, x[0], x[1], x[2], CLEAR, q);
                if (near && q[0] <= 0.0) {
                    // in rock (it fell through a thin floor's corner): back up out of it
                    for (int i = 1; i <= 20 && rock.inRock(t, x[0], x[1], x[2]); i++) x[1] += 0.1;
                } else if (near && q[2] > 0.3) {
                    x[0] += q[1] * (CLEAR - q[0]); x[1] += q[2] * (CLEAR - q[0]); x[2] += q[3] * (CLEAR - q[0]);
                } else if (underHead(t)) {
                    // falling past the edge of a ledge this near, its broad head catches on it: up onto its top
                    for (int i = 1; i <= 20 && underHead(t); i++) x[1] += 0.05;
                } else continue;
                headDown = true;
                contact[0] = 0; contact[1] = 1; contact[2] = 0;
                touching[0] = true;
            }
            xo[0] = x[0]; xo[1] = x[1] - headVy; xo[2] = x[2];
        } else {
            xo[0] = x[0]; xo[1] = x[1]; xo[2] = x[2];
        }
        holding[0] = false;
        // the peel runs on down the body; a bead pulled hard by the one ahead lets go
        peel += PEEL;
        for (int i = 1; i < n; i++) {
            if (!holding[i]) continue;
            int k = 3 * i, p = 3 * (i - 1);
            double dx = x[k] - x[p], dy = x[k + 1] - x[p + 1], dz = x[k + 2] - x[p + 2];
            if (i <= peel || Math.sqrt(dx * dx + dy * dy + dz * dz) > SPACE * PULL) holding[i] = false;
        }
        // the free beads swing on and fall
        for (int i = 1; i < n; i++) {
            if (holding[i]) continue;
            int k = 3 * i;
            double vx = (x[k] - xo[k]) * DRAG, vy = (x[k + 1] - xo[k + 1]) * DRAG, vz = (x[k + 2] - xo[k + 2]) * DRAG;
            if (touching[i]) {
                // resting on rock: the rock's grip takes most of its slide along it
                double vn = vx * contact[k] + vy * contact[k + 1] + vz * contact[k + 2];
                double ax = vx - contact[k] * vn, ay = vy - contact[k + 1] * vn, az = vz - contact[k + 2] * vn;
                double slide = Math.sqrt(ax * ax + ay * ay + az * az) < STICK ? 0.0 : 1.0 - GRIP;
                vx = contact[k] * vn + ax * slide;
                vy = contact[k + 1] * vn + ay * slide;
                vz = contact[k + 2] * vn + az * slide;
            }
            xo[k] = x[k]; xo[k + 1] = x[k + 1]; xo[k + 2] = x[k + 2];
            x[k] += vx; x[k + 1] += vy - GRAVITY; x[k + 2] += vz;
        }
        // the links kept their length (a held bead and the head do not give)
        for (int pass = 0; pass < PASSES; pass++) {
            for (int i = 0; i + 1 < n; i++) {
                int a = 3 * i, b = 3 * (i + 1);
                double wa = i == 0 || holding[i] ? 0.0 : 1.0, wb = holding[i + 1] ? 0.0 : 1.0;
                if (wa + wb <= 0.0) continue;
                double dx = x[b] - x[a], dy = x[b + 1] - x[a + 1], dz = x[b + 2] - x[a + 2];
                double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (l < 1.0E-9) continue;
                double e = (l - SPACE) / l / (wa + wb);
                x[a] += dx * e * wa; x[a + 1] += dy * e * wa; x[a + 2] += dz * e * wa;
                x[b] -= dx * e * wb; x[b + 1] -= dy * e * wb; x[b + 2] -= dz * e * wb;
            }
            // and none in rock
            for (int i = 1; i < n; i++) if (!holding[i]) keepOut(t, i, pass == PASSES - 1);
        }
        // their backs: a held bead's is its rock's; a free one's carried on from last tick, square to the body's line,
        // rolling round it up to the sky as it falls (it twists to land on its belly) or out from the rock it rests on
        boolean rolling = false;
        double turnLeft = 0.0;
        for (int i = 0; i < n; i++) {
            int k = 3 * i;
            if (holding[i]) { nrm[k] = held[k]; nrm[k + 1] = held[k + 1]; nrm[k + 2] = held[k + 2]; continue; }
            int a = 3 * Math.max(0, i - 1), b = 3 * Math.min(n - 1, i + 1);
            double tx = x[a] - x[b], ty = x[a + 1] - x[b + 1], tz = x[a + 2] - x[b + 2];
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            double bx = wasN[k], by = wasN[k + 1], bz = wasN[k + 2];
            if (tl > 1.0E-6) {
                tx /= tl; ty /= tl; tz /= tl;
                double d = bx * tx + by * ty + bz * tz;
                bx -= tx * d; by -= ty * d; bz -= tz * d;
                double bl = Math.sqrt(bx * bx + by * by + bz * bz);
                if (bl > 1.0E-6) {
                    bx /= bl; by /= bl; bz /= bl;
                    // the way it rolls to: out from the rock it rests on, else (falling) up
                    double cx = touching[i] ? contact[k] : 0.0, cy = touching[i] ? contact[k + 1] : 1.0, cz = touching[i] ? contact[k + 2] : 0.0;
                    double cd = cx * tx + cy * ty + cz * tz;
                    cx -= tx * cd; cy -= ty * cd; cz -= tz * cd;
                    // (hanging straight down, or square to the rock it rests on, it has no way to roll to)
                    if (cx * cx + cy * cy + cz * cz > (touching[i] ? 1.0E-6 : 0.09)) {
                        double sn = (by * cz - bz * cy) * tx + (bz * cx - bx * cz) * ty + (bx * cy - by * cx) * tz;
                        double cs = bx * cx + by * cy + bz * cz;
                        double left = Math.atan2(sn, cs);
                        if (Math.abs(sn) < 1.0E-9 && cs < 0.0) left = Math.PI;
                        double r = Math.max(-ROLL, Math.min(ROLL, left));
                        if (touching[i]) {
                            rolling |= Math.abs(r) > 0.03;
                            turnLeft = Math.max(turnLeft, Math.abs(left) - Math.abs(r));
                        }
                        double c = Math.cos(r), sr = Math.sin(r);
                        double ux = ty * bz - tz * by, uy = tz * bx - tx * bz, uz = tx * by - ty * bx;
                        bx = bx * c + ux * sr; by = by * c + uy * sr; bz = bz * c + uz * sr;
                    }
                    nrm[k] = bx; nrm[k + 1] = by; nrm[k + 2] = bz;
                }
            } else {
                double bl = Math.sqrt(bx * bx + by * by + bz * bz);
                if (bl > 1.0E-6) { nrm[k] = bx / bl; nrm[k + 1] = by / bl; nrm[k + 2] = bz / bl; }
            }
        }
        // nor its sides in rock, square to its back as it has turned (its head's too, falling past a wall as well)
        if (half > 0.0) for (int i = 0; i < n; i++) if (!holding[i]) keepSidesOut(t, i);
        double most = 0.0;
        movingAt = -1;
        for (int i = 0; i < n; i++) {
            int k = 3 * i;
            double vx = x[k] - was[k], vy = x[k + 1] - was[k + 1], vz = x[k + 2] - was[k + 2], v = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (v > most) { most = v; movingAt = i; }
        }
        boolean anyHolding = false;
        for (int i = 0; i < n; i++) anyHolding |= holding[i];
        still = headDown && !anyHolding && !rolling && most < STILL ? still + 1 : 0;
        quiet = headDown && !anyHolding && turnLeft < QUIET_TURN && most < QUIET ? quiet + 1 : 0;
        moving = most;
        if (headDown) down++;
    }

    private final double[] wideOut = new double[6];

    /** How far out from its line its head is broad (blocks): falling past the edge of a ledge this near, it lands on it. */
    static final double HEAD_HALF = 0.7;

    /**
     * Whether the top of some rock lies just under its head's broad underside, out to {@link #HEAD_HALF} round the
     * head's bead and {@link #CLEAR} down (the top of a ledge: rock there, none a little higher; a wall it falls beside
     * is rock at both, and does not count).
     */
    private boolean underHead(Terrain t) {
        double y = x[1] - CLEAR - 0.02;
        for (int k = 0; k < 4; k++) {
            double px = x[0] + (k == 0 ? HEAD_HALF : k == 1 ? -HEAD_HALF : 0.0), pz = x[2] + (k == 2 ? HEAD_HALF : k == 3 ? -HEAD_HALF : 0.0);
            if (rock.inRock(t, px, y, pz) && !rock.inRock(t, px, y + 0.35, pz)) return true;
        }
        return false;
    }

    /**
     * Bead i's sides out of the rock: {@link #room} from it, its width there either side of its line, square to its back;
     * eased out, at most {@link #SIDE_STEP} a tick, so its links' pull and the rock's push settle rather than fight.
     */
    private void keepSidesOut(Terrain t, int i) {
        int k = 3 * i, a = 3 * Math.max(0, i - 1), b = 3 * Math.min(n - 1, i + 1);
        double tx = x[a] - x[b], ty = x[a + 1] - x[b + 1], tz = x[a + 2] - x[b + 2];
        double bx = nrm[k], by = nrm[k + 1], bz = nrm[k + 2];
        double sx = ty * bz - tz * by, sy = tz * bx - tx * bz, sz = tx * by - ty * bx;
        if (sx * sx + sy * sy + sz * sz < 1.0E-8) return;
        double w = half + (tail - half) * i / Math.max(1.0, n - 1.0);
        // its line is the bead itself (CLEAR out from the rock it rests on): the belly under it, kept as it is
        double m = rock.clearWide(t, x[k] - bx * CLEAR, x[k + 1] - by * CLEAR, x[k + 2] - bz * CLEAR, bx, by, bz, sx, sy, sz, CLEAR, w, room, wideOut);
        if (m <= 1.0E-4) return;
        double mx = wideOut[0] + bx * CLEAR - x[k], my = wideOut[1] + by * CLEAR - x[k + 1], mz = wideOut[2] + bz * CLEAR - x[k + 2];
        double ml = Math.sqrt(mx * mx + my * my + mz * mz);
        if (ml > SIDE_STEP) { mx *= SIDE_STEP / ml; my *= SIDE_STEP / ml; mz *= SIDE_STEP / ml; }
        // moved, not thrown: its last place moves with it, so the push adds nothing to its swing
        x[k] += mx; x[k + 1] += my; x[k + 2] += mz;
        xo[k] += mx; xo[k + 1] += my; xo[k + 2] += mz;
    }

    /** Bead i out of the rock: at least {@link #CLEAR} from it; resting on it, it notes the rock's way out there. */
    private void keepOut(Terrain t, int i, boolean note) {
        int k = 3 * i;
        // a bead resting on rock lies just its clearance from it: felt a little further out, or it never counts as resting
        if (!rock.nearest(t, x[k], x[k + 1], x[k + 2], CLEAR + TOUCH, q)) {
            if (note) touching[i] = false;
            return;
        }
        if (q[0] <= 0.0) {
            // in rock: back the way it came, then up, until it is out
            double bx = xo[k] - x[k], by = xo[k + 1] - x[k + 1], bz = xo[k + 2] - x[k + 2];
            double bl = Math.sqrt(bx * bx + by * by + bz * bz);
            if (bl < 1.0E-6) { bx = 0; by = 1; bz = 0; bl = 1; }
            for (int s = 1; s <= 24; s++) {
                double px = x[k] + bx / bl * 0.1 * s, py = x[k + 1] + by / bl * 0.1 * s + (s > 12 ? 0.1 * (s - 12) : 0), pz = x[k + 2] + bz / bl * 0.1 * s;
                if (!rock.inRock(t, px, py, pz)) { x[k] = px; x[k + 1] = py; x[k + 2] = pz; break; }
            }
            if (!rock.nearest(t, x[k], x[k + 1], x[k + 2], CLEAR, q) || q[0] <= 0.0) return;
        }
        double m = CLEAR - q[0];
        if (m > 0.0) {
            x[k] += q[1] * m; x[k + 1] += q[2] * m; x[k + 2] += q[3] * m;
        }
        if (note) {
            touching[i] = true;
            contact[k] = q[1]; contact[k + 1] = q[2]; contact[k + 2] = q[3];
        }
    }

    /** Whether it has all come to rest (or fallen as long as it ever does). */
    public boolean settled() {
        return still >= STILL_TICKS || ticks >= MOST_TICKS;
    }

    /**
     * Whether it may get up from where it fell: its head down {@code daze} ticks or more, and all its body fallen and
     * (nearly) come to rest ({@link #QUIET}), or fallen as long as it ever does; its head never down, once it has fallen
     * that long. Worked out from the rope alone (tick it until then), so the server and every client stop it at the same
     * tick and lay the same body from it.
     */
    public boolean restful(int daze) {
        if (!headDown) return ticks >= MOST_TICKS;
        return down >= daze && (quiet >= QUIET_TICKS || still >= STILL_TICKS || down >= DOWN_MOST || ticks >= MOST_TICKS);
    }

    /** For the dev listing: how far its fastest bead moved last tick (blocks), and which bead that was. */
    public double moving() { return moving; }
    public int movingAt() { return movingAt; }

    /** How many beads still hold the rock they lay on. */
    public int holding() {
        int h = 0;
        for (boolean b : holding) if (b) h++;
        return h;
    }

    /**
     * The body {@code s} blocks back from the head, {@code pt} of the way from a tick ago to now: a point on the rock
     * under its line (its bead's place less {@link #CLEAR} along its back) at o[off..off+2], its back at o[off+3..off+5].
     */
    public void sample(double s, double pt, double[] o, int off) {
        double u = Math.max(0.0, s) / SPACE;
        int i = Math.min((int) Math.floor(u), n - 2);
        double f = Math.min(1.0, u - i);
        int a = 3 * i, b = 3 * (i + 1);
        double bx = 0, by = 0, bz = 0;
        for (int k = 0; k < 3; k++) {
            double na = wasN[a + k] + (nrm[a + k] - wasN[a + k]) * pt, nb = wasN[b + k] + (nrm[b + k] - wasN[b + k]) * pt;
            double v = na + (nb - na) * f;
            if (k == 0) bx = v; else if (k == 1) by = v; else bz = v;
        }
        double bl = Math.sqrt(bx * bx + by * by + bz * bz);
        if (bl < 1.0E-6) { bx = 0; by = 1; bz = 0; bl = 1; }
        bx /= bl; by /= bl; bz /= bl;
        for (int k = 0; k < 3; k++) {
            double pa = was[a + k] + (x[a + k] - was[a + k]) * pt, pb = was[b + k] + (x[b + k] - was[b + k]) * pt;
            o[off + k] = pa + (pb - pa) * f - (k == 0 ? bx : k == 1 ? by : bz) * CLEAR;
        }
        o[off + 3] = bx; o[off + 4] = by; o[off + 5] = bz;
    }
}
