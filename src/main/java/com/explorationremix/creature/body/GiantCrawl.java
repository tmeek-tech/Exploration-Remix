package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * How the giant Skein's head crawls (0.21.5): the game moves the middle of its head over the rock itself, held
 * {@link #RIDE} out from it, the way a spider walks a wall, and never by the game's own collision (its box no longer
 * catches on the rock, slides off it or falls from it). Each tick it reads the rock round it ({@link TerrainPatch}: a
 * field that rounds every outer edge and fillets every hollow, so a body held a set way out from the rock moves over a
 * smooth skin with no corners in it), turns its heading a little towards where it is going in the plane of the rock
 * under it, steps on, and settles back onto its ride. Its heading is carried round with the rock's way out as that
 * turns, so it pours over an edge, or up out of a hollow onto a wall, or from a wall onto a ceiling, without a break.
 *
 * <p>The fillets are generous ({@link #SOFT}): over a knob or a dip of a block or so in the rock, the skin it rides
 * bulges gently or bridges the dip, so its head glides over small roughness and follows only the rock's big shapes
 * (Taran's card, 24 Nov 2026). The point it lays on its trail ({@link #foot}) is the skin's own point on the rock: on
 * the rock wherever the rock is convex, a little out over it in a hollow, never inside it.
 *
 * <p>It turns no tighter than a circle of {@link #TURN_RADIUS} as it goes (a little tighter when it is nearly still),
 * and slows to turn hard, so the way its head lays down (and its long body follows) is one smooth curve. It cannot
 * fall: only let go ({@link #fall}), or lose the rock when the rock itself goes (dug away under it).
 *
 * <p>Plain numbers and JOML, no game classes: the entity drives it with the level's rock, the tests with a world of
 * their own.
 */
public final class GiantCrawl {
    /** How far out from the rock the middle of its head rides (blocks): about half its head's width. */
    public static final double RIDE = 1.2;
    /** Surfaces meeting in a hollow are blended over this (blocks): the skin's fillets (see TerrainPatch#field). */
    public static final double SOFT = 0.75;
    /** Rock further than this is not felt (far enough that a surface coming into reach changes the skin by a hair). */
    static final double REACH = RIDE + 4.5 * SOFT;
    /** How far round it the patch reads (blocks), and how far it may go from the patch's middle before it reads afresh. */
    static final int AROUND = (int) Math.ceil(REACH) + 2;
    static final double REREAD = 1.5;
    /** Further than this past its ride from the rock, it has lost its hold. */
    public static final double LOST = 1.6;
    /**
     * It holds only the rock on its own side: rock whose way out to it turns further than this (a dot) from its own way
     * out lies across the gap from it, and is not felt: the roof of a low passage over a floor, the far wall of a cleft,
     * and the face of rock coming down to a little over its head (the mouth of a low passage, which it goes in under
     * rather than climbing). Round a convex edge and into a hollow's corner the rock it comes to lies at its own level
     * or below it (a quarter round at most), so it is felt. Rock across the gap still keeps it out (see SQUEEZE).
     */
    static final double HOLD = -0.1;
    /** The widest circle it turns in as it goes (blocks), and the least it can turn a tick (radians) however slow. */
    public static final double TURN_RADIUS = 2.4, MIN_TURN = 0.045;
    /** It eases its pace up or down by at most this a tick (blocks a tick, a tick). */
    public static final double ACCEL = 0.012;
    /** Ticks barely moving while it means to go, before it counts as stalled. */
    static final int STALL_TICKS = 8;
    /**
     * Where the rock falls away under it (past a knob, into a dip) it eases back down onto its ride, this much of the way
     * a tick, rather than dropping at once, and so glides over small roughness; it is never left more than
     * {@link #GLIDE} above its ride. Where the rock rises it rides up at once (it never goes into the rock).
     */
    static final double EASE = 0.3, GLIDE = 0.9;
    /** The point it lays on its trail is never further in from the middle of its head than its ride and this. */
    static final double FOOT_SLACK = 0.35;
    /**
     * Its own way out ({@link #up}) turns this much of the way a tick towards the rock's under it, and no more than
     * {@link #UP_MOST} (radians): the rock's way out wavers over rough rock and in a tube of rock is pulled every way,
     * and its own, which decides which side it holds and how its head lies, follows it smoothly.
     */
    static final double UP_FOLLOW = 0.5, UP_MOST = 0.4;
    /**
     * It never comes nearer than this to rock on any side (blocks from the middle of its head): where a passage is too
     * low or narrow for its ride (a squeeze), it goes no further in than this, and its ride gives way to it, so the rock
     * across the gap from it (which it does not hold) can never be pushed into either.
     */
    public static final double SQUEEZE = 0.9;

    /** The middle of its head, held {@link #RIDE} out from the rock. */
    public final Vector3d pos = new Vector3d();
    /** The rock's way out under it, and the way it heads (along the rock, square to {@link #up}). */
    public final Vector3d up = new Vector3d(0, 1, 0), heading = new Vector3d(0, 0, 1);
    /** Its pace now (blocks a tick). */
    public double speed;
    /** How far it went last tick, how far its heading turned (radians) and how far the rock's way out turned. */
    public double moved, turned, upTurned;
    /** Ticks in a row it has barely moved while it meant to go (wedged somewhere). */
    public int stalled;
    /** Its pace while falling (blocks a tick, along each axis). */
    public final Vector3d fallVelocity = new Vector3d();

    private final TerrainPatch rock = new TerrainPatch();
    private final double[] f = new double[8], g = new double[8];
    private int readX = Integer.MIN_VALUE, readY, readZ, readAge;
    private final Vector3d was = new Vector3d(), wasUp = new Vector3d(), tmp = new Vector3d(), tmp2 = new Vector3d();

    /** The patch of rock read last. */
    public TerrainPatch rock() { return rock; }

    /** Sets it down at {@code p} heading along {@code h} (laid along the rock once it settles). */
    public void place(double x, double y, double z, double hx, double hy, double hz) {
        pos.set(x, y, z);
        heading.set(hx, hy, hz);
        if (heading.lengthSquared() < 1.0E-8) heading.set(0, 0, 1);
        heading.normalize();
        speed = 0.0;
        stalled = 0;
        fallVelocity.set(0, 0, 0);
        readX = Integer.MIN_VALUE;
    }

    /**
     * Reads the rock round it, afresh when it has gone far from where the patch was last read, the patch is old, or
     * {@code force}; true when there is rock in reach on its own side.
     */
    public boolean read(Terrain t, boolean force) {
        int x = (int) Math.floor(pos.x), y = (int) Math.floor(pos.y), z = (int) Math.floor(pos.z);
        if (force || readX == Integer.MIN_VALUE || Math.abs(x - readX) > REREAD || Math.abs(y - readY) > REREAD
                || Math.abs(z - readZ) > REREAD || ++readAge > 40) {
            rock.build(t, x - AROUND, y - AROUND, z - AROUND, x + AROUND, y + AROUND, z + AROUND);
            readX = x; readY = y; readZ = z; readAge = 0;
        }
        return field(pos.x, pos.y, pos.z);
    }

    /** The field on its own side at (x, y, z) into f (see TerrainPatch#field). */
    private boolean field(double x, double y, double z) {
        return rock.field(x, y, z, REACH, SOFT, f, up.x, up.y, up.z, HOLD, true);
    }

    /** The field at (x, y, z) from all the rock round it, whichever side. */
    private boolean fieldAny(double x, double y, double z) {
        return rock.field(x, y, z, REACH, SOFT, f, 0, 0, 0, -2.0, true);
    }

    /**
     * Settles it onto its ride out from the rock nearest it, whichever side (set down, or landed): false when there is
     * no rock in reach. Its way out is read afresh, and its heading laid along the rock.
     */
    public boolean settle(Terrain t) {
        read(t, true);
        if (!fieldAny(pos.x, pos.y, pos.z)) return false;
        // its way out from the nearest rock alone (in a tube the blend of all of it, every side, points nowhere)
        up.set(f[5], f[6], f[7]);
        for (int it = 0; it < 6; it++) {
            if (!field(pos.x, pos.y, pos.z)) break;
            double m = RIDE - f[0];
            pos.add(f[1] * m, f[2] * m, f[3] * m);
            if (Math.abs(m) < 1.0E-3) break;
            read(t, false);
        }
        if (field(pos.x, pos.y, pos.z)) up.set(f[1], f[2], f[3]);
        flatten(heading, up, null);
        speed = 0.0;
        return true;
    }

    /**
     * One tick crawling towards (tx, ty, tz) at a pace of {@code want} (blocks a tick): its heading turned towards it
     * along the rock, a step on, and back onto its ride. False (and it stays put) when it holds no rock.
     */
    public boolean step(Terrain t, double tx, double ty, double tz, double want) {
        was.set(pos);
        wasUp.set(up);
        if (!read(t, false)) {
            if (!read(t, true)) { moved = 0.0; upTurned = 0.0; return false; }
        }
        if (f[0] > RIDE + LOST) { moved = 0.0; upTurned = 0.0; return false; }
        // towards the goal along the rock: turned no faster than its circle lets it at its pace
        double wx = tx - pos.x, wy = ty - pos.y, wz = tz - pos.z;
        double along = wx * up.x + wy * up.y + wz * up.z;
        wx -= up.x * along; wy -= up.y * along; wz -= up.z * along;
        double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
        double bend = 0.0;
        turned = 0.0;
        if (wl > 1.0E-4) {
            wx /= wl; wy /= wl; wz /= wl;
            double cos = heading.x * wx + heading.y * wy + heading.z * wz;
            double sin = (heading.y * wz - heading.z * wy) * up.x + (heading.z * wx - heading.x * wz) * up.y + (heading.x * wy - heading.y * wx) * up.z;
            bend = Math.atan2(sin, cos);
            double most = Math.max(MIN_TURN, speed / TURN_RADIUS);
            // a goal lying mostly straight out from the rock (across a gap) gives a way along the rock that swings round
            // with every tilt of the rock under it: it turns towards that only as far as the goal lies along the rock
            double off = Math.abs(along);
            if (off > wl) most *= Math.max(0.15, wl / off);
            double a = Math.max(-most, Math.min(most, bend));
            rotate(heading, up, a);
            turned = Math.abs(a);
        }
        // slower to turn hard (a long body swings wide otherwise), and as it comes to its goal
        double goal = Math.sqrt(Math.pow(tx - pos.x, 2) + Math.pow(ty - pos.y, 2) + Math.pow(tz - pos.z, 2));
        double pace = want * (0.35 + 0.65 * Math.max(0.0, Math.cos(Math.min(Math.abs(bend), Math.PI / 2))));
        if (goal < 2.5) pace *= Math.max(0.25, goal / 2.5);
        speed += Math.max(-ACCEL * 1.5, Math.min(ACCEL, pace - speed));
        speed = Math.max(0.0, speed);
        // on along its heading, no further into a squeeze than it is: where the way on is too low for its ride, it
        // ducks down towards the rock it holds (no nearer than its squeeze) and goes in under
        double room = room(pos.x, pos.y, pos.z);
        boolean went = false;
        for (int k = 0; k < 3 && !went; k++) {
            double s = speed / (1 << k);
            for (int duck = 0; duck <= 3 && !went; duck++) {
                tmp2.set(pos).fma(s, heading).fma(-0.1 * duck, up);
                if (room(tmp2.x, tmp2.y, tmp2.z) >= Math.min(SQUEEZE, room - 1.0E-3)) { pos.set(tmp2); went = true; }
            }
        }
        if (!went) speed *= 0.5;
        ride(t);
        // its own way out eases round to the rock's where it is now (its heading carried round with it)
        if (read(t, false) && field(pos.x, pos.y, pos.z)) turnUp();
        moved = was.distance(pos);
        upTurned = angle(wasUp, up);
        stalled = want > 0.02 && moved < 0.3 * Math.max(0.02, speed) ? stalled + 1 : 0;
        return true;
    }

    /**
     * Back onto its ride out from the rock it holds (a few steps along the way out), its heading carried round: out at
     * once where it has come too near, eased back in where the rock fell away (see {@link #EASE}).
     */
    private void ride(Terrain t) {
        for (int it = 0; it < 4; it++) {
            if (!read(t, false) || !field(pos.x, pos.y, pos.z)) return;
            double m = RIDE - f[0];
            if (m < 0.0) {
                // too far out: once a tick, part of the way in (and never left more than GLIDE out)
                if (it > 0) return;
                m = Math.min(m * EASE, m + GLIDE);
            }
            if (Math.abs(m) < 2.0E-3) return;
            // never into a squeeze: where its ride would take it nearer rock (on any side) than it may go, it goes
            // only as far as it may
            double room = room(pos.x, pos.y, pos.z), fx = f[1], fy = f[2], fz = f[3];
            boolean went = false;
            for (int k = 0; k < 4 && !went; k++) {
                double mk = m / (1 << k);
                tmp2.set(pos.x + fx * mk, pos.y + fy * mk, pos.z + fz * mk);
                if (room(tmp2.x, tmp2.y, tmp2.z) >= Math.min(SQUEEZE, room - 1.0E-3)) { pos.set(tmp2); went = true; }
            }
            if (!went) return;
        }
    }

    /** The plain distance from (x, y, z) to the nearest rock on any side (out to its reach; less than 0 inside rock). */
    private double room(double x, double y, double z) {
        return rock.field(x, y, z, REACH, SOFT, g, 0, 0, 0, -2.0, true) ? g[4] : REACH;
    }

    /** Its own way out turned part of the way towards the rock's (in f), its heading carried round with it. */
    private void turnUp() {
        tmp.set(f[1], f[2], f[3]);
        double a = angle(up, tmp);
        if (a < 1.0E-6) return;
        double turn = a < 0.02 ? a : Math.min(UP_MOST, a * UP_FOLLOW);
        // about the axis square to both (or, turned right round, its heading's)
        double kx = up.y * tmp.z - up.z * tmp.y, ky = up.z * tmp.x - up.x * tmp.z, kz = up.x * tmp.y - up.y * tmp.x;
        double kl = Math.sqrt(kx * kx + ky * ky + kz * kz);
        if (kl < 1.0E-9) { kx = heading.x; ky = heading.y; kz = heading.z; kl = 1.0; }
        kx /= kl; ky /= kl; kz /= kl;
        double cs = Math.cos(turn), sn = Math.sin(turn);
        double cx = ky * up.z - kz * up.y, cy = kz * up.x - kx * up.z, cz = kx * up.y - ky * up.x;
        double d = kx * up.x + ky * up.y + kz * up.z;
        tmp2.set(up.x * cs + cx * sn + kx * d * (1 - cs), up.y * cs + cy * sn + ky * d * (1 - cs), up.z * cs + cz * sn + kz * d * (1 - cs));
        tmp2.normalize();
        carry(heading, up, tmp2);
        up.set(tmp2);
    }

    /**
     * The point it lays on its trail: from the middle of its head straight in along its way out by its gap from the
     * rock (the skin's point on the rock, never inside it: no nearer the head than the rock is), into {@code foot}, and
     * its way out into {@code n}.
     */
    public boolean foot(Terrain t, Vector3d foot, Vector3d n) {
        if (!read(t, false) || !field(pos.x, pos.y, pos.z)) return false;
        double d = footDepth();
        foot.set(pos.x - up.x * d, pos.y - up.y * d, pos.z - up.z * d);
        n.set(up);
        return true;
    }

    /**
     * How far in from the middle of its head its trail's point lies (from the field just read): the plain distance to
     * the nearest rock, so the point lies on the rock where that is straight under it and never inside it, but no
     * further than its ride and {@link #FOOT_SLACK} (where it glides over a dip, its head glides with it).
     */
    private double footDepth() {
        return Math.max(0.0, Math.min(f[4], RIDE + FOOT_SLACK));
    }

    /**
     * The same for a head the client is only told the place and way out of (from the server): its point on the rock and
     * its way out, into {@code foot} and {@code n}; false with no rock in reach.
     */
    public boolean footAt(Terrain t, double x, double y, double z, double ux, double uy, double uz, Vector3d foot, Vector3d n) {
        pos.set(x, y, z);
        up.set(ux, uy, uz);
        if (up.lengthSquared() < 1.0E-8) up.set(0, 1, 0);
        up.normalize();
        boolean held = read(t, false);
        if (!held) held = fieldAny(x, y, z);
        if (!held) return false;
        double d = footDepth();
        foot.set(x - up.x * d, y - up.y * d, z - up.z * d);
        n.set(up);
        return true;
    }

    /** How far the middle of its head is from the rock it holds (from the last read), or +inf. */
    public double gap() {
        return field(pos.x, pos.y, pos.z) ? f[0] : Double.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------------------------------------------ falling

    /** Gravity, a tick, and the air's drag (as the game's own falls have them). */
    public static final double GRAVITY = 0.08, DRAG = 0.98;

    /**
     * One tick falling: gravity has it, it moves no further than the rock lets it, and once it comes within its ride of
     * rock under it (or beside it, slid down a wall) it lands there. True when it has landed (settled on its ride,
     * {@link #up} the rock's way out), false while it falls on.
     */
    public boolean fall(Terrain t) {
        was.set(pos);
        fallVelocity.y = (fallVelocity.y - GRAVITY) * DRAG;
        fallVelocity.x *= 0.91;
        fallVelocity.z *= 0.91;
        double len = fallVelocity.length();
        int n = Math.max(1, (int) Math.ceil(len / 0.4));
        for (int i = 0; i < n; i++) {
            tmp2.set(pos).fma(1.0 / n, fallVelocity);
            pos.set(tmp2);
            read(t, false);
            if (fieldAny(pos.x, pos.y, pos.z) && f[0] <= RIDE) {
                // within its ride of rock: back out along the rock's way out, onto its ride, and it has landed
                up.set(f[1], f[2], f[3]);
                double m = RIDE - f[0];
                pos.add(f[1] * m, f[2] * m, f[3] * m);
                if (up.y > 0.5) {
                    fallVelocity.set(0, 0, 0);
                    settle(t);
                    moved = was.distance(pos);
                    return true;
                }
                // a wall or a ledge's edge: it slides down it, and loses what speed it had across
                fallVelocity.x = 0;
                fallVelocity.z = 0;
            }
        }
        moved = was.distance(pos);
        return false;
    }

    // ------------------------------------------------------------------------------------------------ vectors

    /** v carried round with the rock's way out as that turns from {@code from} to {@code to} (both unit), kept along the rock. */
    static void carry(Vector3d v, Vector3d from, Vector3d to) {
        double c = from.dot(to);
        double kx = from.y * to.z - from.z * to.y, ky = from.z * to.x - from.x * to.z, kz = from.x * to.y - from.y * to.x;
        double s = Math.sqrt(kx * kx + ky * ky + kz * kz);
        if (s > 1.0E-9 && c > -0.999) {
            kx /= s; ky /= s; kz /= s;
            double a = Math.atan2(s, c), cs = Math.cos(a), sn = Math.sin(a);
            double d = kx * v.x + ky * v.y + kz * v.z;
            double cx = ky * v.z - kz * v.y, cy = kz * v.x - kx * v.z, cz = kx * v.y - ky * v.x;
            v.set(v.x * cs + cx * sn + kx * d * (1 - cs), v.y * cs + cy * sn + ky * d * (1 - cs), v.z * cs + cz * sn + kz * d * (1 - cs));
        }
        flatten(v, to, from);
    }

    /** v laid along the rock facing {@code n} and made unit length (or, with nothing left, along {@code fallback} crossed round). */
    static void flatten(Vector3d v, Vector3d n, Vector3d fallback) {
        double d = v.dot(n);
        v.fma(-d, n);
        if (v.lengthSquared() < 1.0E-10) {
            if (fallback != null) v.set(fallback).fma(-fallback.dot(n), n);
            if (v.lengthSquared() < 1.0E-10) v.set(Math.abs(n.y) < 0.9 ? 0 : 1, Math.abs(n.y) < 0.9 ? 1 : 0, 0).fma(-(Math.abs(n.y) < 0.9 ? n.y : n.x), n);
        }
        v.normalize();
    }

    /** v (along the rock) turned by {@code a} radians about the rock's way out {@code n}. */
    static void rotate(Vector3d v, Vector3d n, double a) {
        double cs = Math.cos(a), sn = Math.sin(a);
        double cx = n.y * v.z - n.z * v.y, cy = n.z * v.x - n.x * v.z, cz = n.x * v.y - n.y * v.x;
        v.set(v.x * cs + cx * sn, v.y * cs + cy * sn, v.z * cs + cz * sn);
        flatten(v, n, null);
    }

    static double angle(Vector3d a, Vector3d b) {
        double c = Math.max(-1.0, Math.min(1.0, a.dot(b) / Math.max(1.0E-9, a.length() * b.length())));
        return Math.acos(c);
    }
}
