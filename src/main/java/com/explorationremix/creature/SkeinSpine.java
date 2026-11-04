package com.explorationremix.creature;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * The line the Skein's long body lies along, worked out once a tick: a neck that bends with the head, and behind it a
 * body that goes exactly where the base of its neck has been, the way a long animal's body follows its own front.
 *
 * <p>The base of the neck (the lead) is kept about {@link #NECK_LAG} behind the head along the way the head has come
 * ({@link SkeinTrail}), and steers for the trail a little further on ({@link #LEAD_LOOK}), never turning tighter than
 * {@link #TURN}: so it starts round a corner before it gets there and cuts it on the inside, as a spline through the
 * head's path would, and settles back onto the trail without swinging out past it; where the head turned right back on
 * itself, it swings round in a loop (on the side the neck swung out to) and comes back onto the trail. Where it has
 * been is kept ({@link #path}); the body behind the neck lies along that, a joint a link, so the body slides along a
 * line that stays put, only ever forwards and only as the lead goes on: it never swings about, and lies dead still
 * while the head does. The neck is a smooth curve from the head (leaving it straight back from the way the head points)
 * to the lead (coming in the way the lead heads), its joints spread along it; where the head faces back over its neck,
 * the neck swings out the other way from the head's turn (the way the back of the head went round as it turned where it
 * stood) and curls back to the lead, and if it must be longer than {@link #NECK_LAG} to reach round, it stretches a
 * little (and the lead catches up) rather than pushing the body back.
 *
 * <p>Over an edge, from a floor onto a wall, the lead and the neck keep to the rock the trail crosses, so the body pours
 * over edges as the head went; only its turns across the rock are smoothed. Its back faces the rock under it, kept
 * square to the way the body runs: where the body runs up a step, its plates turn with it, the way the joint before
 * faced carried round with the body's turn. Between ticks it is drawn part way from where it was to where it is
 * ({@link #sample}), as the game draws the head.
 *
 * <p>Plain numbers, no game classes, so it can be tested on its own.
 */
public final class SkeinSpine {
    /** How far apart the joints are (blocks). */
    public static final double LINK = 0.15;
    /** How far along the trail behind the head the lead is kept (blocks): the neck's length, near enough. */
    static final double NECK_LAG = 0.9;
    /** How far along the trail ahead of its own place the lead steers for (blocks): further, corners cut wider. */
    static final double LEAD_LOOK = 0.9;
    /** The tightest the lead turns (radians a block): the body never curves tighter than this round. */
    static final double TURN = 2.0;
    /**
     * How hard the lead turns towards the point it steers for: 2 would follow the circle through that point, and swing
     * out past the trail after a corner; 4 settles back onto the trail without swinging out past it.
     */
    static final double STEER = 4.0;
    /** How much faster than the head the lead may go to make up ground it lost (blocks a tick). */
    static final double CATCH = 0.12;
    /** With the way on further round behind it than this (radians), the lead turns the way it last turned. */
    static final double FOLDED = Math.toRadians(150);
    /** The furthest the lead strays from the trail (blocks): room for its widest loop. */
    static final double DRIFT = 1.8;
    /** The lead moves in steps no longer than this (blocks). */
    static final double SUB = 0.05;
    /** How many pieces the neck's curve is measured in. */
    static final int NECK_PIECES = 16;
    /** How many links the neck has: the joint at its end is at the lead. */
    static final int NECK_JOINTS = (int) Math.round(NECK_LAG / LINK);
    /** How many ticks the way the head or the lead last turned is remembered. */
    static final int TURN_MEMORY = 60;
    /**
     * Between these (the sine of the angle between the rock's normal and the way the body runs), its back turns from
     * the way the joint before faced, carried round, to the rock's own normal: running up a step over a floor's
     * points, the body runs into the rock's normal, and only its turn says how it faces.
     */
    static final double SQUARE_FROM = 0.3, SQUARE_TO = 0.7;
    private static final int STRIDE = 6;

    private final int joints;
    /** The body (each joint's position and normal): now, and a tick ago. */
    private final double[] cur, prev;
    /** The lead: its position and normal, the way it heads (towards the head), and how far along the trail it has got. */
    private final double[] lead = new double[6], heading = new double[3];
    private double leadAlong;
    /** Where the lead has been, newest first: the body behind the neck lies along it. */
    private final SkeinTrail path;
    /** The neck's curve as last measured: its points (position, normal), how far along it each is, and its length. */
    private final double[] neck = new double[STRIDE * (NECK_PIECES + 1)], neckArc = new double[NECK_PIECES + 1];
    private double neckLength;
    /** The neck's curve before it is kept to the rock, and how much longer than its length it had to be last tick. */
    private final double[] raw = new double[3 * (NECK_PIECES + 1)];
    private double excess;
    /** The way the head last turned (about its rock's normal: -1, 0 for not lately, 1), how long ago, and the way it faced. */
    private int headTurn, headTurnAge;
    private final double[] facing = new double[3];
    private boolean faced;
    /** The way the lead last turned (-1, 0 for not lately, 1), and how long ago. */
    private int leadTurn, leadTurnAge;
    /** The trail it lies along, and how many times that had been laid when this was laid on it. */
    @Nullable private SkeinTrail on;
    private int lays;
    private final double[] at = new double[6], tmp = new double[6], v = new double[3], pos = new double[3];
    /** The rock round the lead, or the neck, laid out flat. */
    private final Flat flat = new Flat();
    private final double[] pathArcs, pathOut;
    /** For the dev trace: how far the lead is from the trail. */
    private double offTrail;

    /** A chain of {@code joints} joints, the first at the head. */
    public SkeinSpine(int joints) {
        this.joints = Math.max(2, joints);
        this.cur = new double[STRIDE * this.joints];
        this.prev = new double[STRIDE * this.joints];
        this.path = new SkeinTrail(length() + 1.5);
        // the joints behind the neck: each a link further back along the lead's path
        int behind = Math.max(1, this.joints - NECK_JOINTS - 1);
        this.pathArcs = new double[behind];
        for (int k = 0; k < behind; k++) pathArcs[k] = (k + 1) * LINK;
        this.pathOut = new double[STRIDE * behind];
    }

    public int joints() { return joints; }
    /** How far along the chain its last joint is. */
    public double length() { return (joints - 1) * LINK; }
    /** Joint i's position (0..2) and the way its back faces (3..5), at data()[6i..6i+5]: as it is this tick. */
    public double[] data() { return cur; }
    /** As {@link #data}, a tick ago. */
    double[] before() { return prev; }
    /** The lead's position and normal, and how far the neck's curve runs from the head to it. */
    double[] lead() { return lead; }
    double neckLength() { return neckLength; }
    /** Whether it lies along {@code trail} as that was last laid (else the next {@link #follow} lays it afresh). */
    public boolean on(SkeinTrail trail) { return trail == on && trail.lays() == lays; }

    /** Lays the body along the trail back from the head, at once: as it lies when first set down or seen. */
    public void lay(SkeinTrail trail) {
        lay(trail, 0.0, 0.0, 0.0);
    }

    private void lay(SkeinTrail trail, double fx, double fy, double fz) {
        on = trail;
        lays = trail.lays();
        headTurn = 0; headTurnAge = 0; leadTurn = 0; leadTurnAge = 0;
        faced = false;
        double uh = trail.headAlong();
        leadAlong = uh - NECK_LAG;
        trail.at(leadAlong, lead, 0);
        // heading the way the trail runs there, towards the head
        trail.at(Math.min(uh, leadAlong + 0.1), tmp, 0);
        double hx = tmp[0] - lead[0], hy = tmp[1] - lead[1], hz = tmp[2] - lead[2];
        if (hx * hx + hy * hy + hz * hz < 1.0E-8) {
            trail.at(leadAlong - 0.1, tmp, 0);
            hx = lead[0] - tmp[0]; hy = lead[1] - tmp[1]; hz = lead[2] - tmp[2];
        }
        setHeading(hx, hy, hz);
        // what the body behind the neck lies along: the trail back from the lead
        int n = (int) Math.ceil(path.keep() / 0.25) + 2;
        double[] arcs = new double[n], s = new double[STRIDE * n];
        for (int k = 0; k < n; k++) arcs[k] = NECK_LAG + k * 0.25;
        trail.sampleAll(arcs, s);
        float[] data = new float[STRIDE * n];
        for (int o = 0; o < data.length; o += STRIDE) {
            data[o] = (float) (s[o] - lead[0]);
            data[o + 1] = (float) (s[o + 1] - lead[1]);
            data[o + 2] = (float) (s[o + 2] - lead[2]);
            data[o + 3] = (float) s[o + 3];
            data[o + 4] = (float) s[o + 4];
            data[o + 5] = (float) s[o + 5];
        }
        data[0] = 0.0F; data[1] = 0.0F; data[2] = 0.0F;
        path.read(data, lead[0], lead[1], lead[2]);
        offTrail = 0.0;
        excess = 0.0;
        place(trail, fx, fy, fz);
        System.arraycopy(cur, 0, prev, 0, cur.length);
    }

    /**
     * A tick: the head has gone on to the head of {@code trail}, and points along (fx, fy, fz) (anything along its
     * rock's normal is not counted; none at all, and the neck leaves it the way the trail comes in). The lead comes on
     * after it, and the body after the lead. A trail laid afresh since (a teleport, say) has the body laid afresh.
     */
    public void follow(SkeinTrail trail, double fx, double fy, double fz) {
        if (!on(trail)) {
            lay(trail, fx, fy, fz);
            return;
        }
        System.arraycopy(cur, 0, prev, 0, cur.length);
        noteTurn(trail, fx, fy, fz);
        moveLead(trail);
        place(trail, fx, fy, fz);
        for (double c : cur) {
            // never left lying nowhere (a trail of no length, say): laid afresh along the trail
            if (!Double.isFinite(c)) { lay(trail, 0.0, 0.0, 0.0); return; }
        }
    }

    /** Notes which way the head turned (about its rock's normal) since the last tick, if it turned. */
    private void noteTurn(SkeinTrail trail, double fx, double fy, double fz) {
        trail.at(trail.headAlong(), at, 0);
        double nx = at[3], ny = at[4], nz = at[5];
        double d = fx * nx + fy * ny + fz * nz;
        fx -= nx * d; fy -= ny * d; fz -= nz * d;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (headTurnAge++ > TURN_MEMORY) headTurn = 0;
        if (leadTurnAge++ > TURN_MEMORY) leadTurn = 0;
        if (fl < 1.0E-4) return;
        fx /= fl; fy /= fl; fz /= fl;
        if (faced) {
            double cx = facing[1] * fz - facing[2] * fy, cy = facing[2] * fx - facing[0] * fz, cz = facing[0] * fy - facing[1] * fx;
            double a = Math.atan2(cx * nx + cy * ny + cz * nz, facing[0] * fx + facing[1] * fy + facing[2] * fz);
            if (Math.abs(a) > Math.toRadians(2.0)) { headTurn = a > 0 ? 1 : -1; headTurnAge = 0; }
        }
        facing[0] = fx; facing[1] = fy; facing[2] = fz;
        faced = true;
    }

    /**
     * The lead comes on after the head: as far as keeps it {@link #NECK_LAG} behind it along the trail (and the neck
     * no longer than that), at most {@link #CATCH} more than the head went; in short steps, each turned towards the
     * trail {@link #LEAD_LOOK} on. It steers on the rock round it laid out flat, so over an edge it goes on onto the
     * rock beyond as the trail does.
     */
    private void moveLead(SkeinTrail trail) {
        double uh = trail.headAlong(), step = trail.lastStep();
        double go = Math.max(0.0, Math.min(step + CATCH, Math.max(uh - leadAlong - NECK_LAG, excess)));
        int n = (int) Math.ceil(go / SUB - 1.0E-9);
        if (n <= 0) return;
        double ds = go / n;
        double from = leadAlong - 0.3;
        flat.build(trail, from, Math.max(from + 0.3, Math.min(uh, leadAlong + LEAD_LOOK + go + 0.6)), leadAlong);
        flat.toFlat(lead[0], lead[1], lead[2], leadAlong, pos);
        v[0] = heading[0]; v[1] = heading[1]; v[2] = heading[2];
        flat.vecToFlat(leadAlong, v);
        double fnx = flat.nx, fny = flat.ny, fnz = flat.nz;
        flatten(v, fnx, fny, fnz);
        for (int i = 0; i < n; i++) {
            steer(uh, ds, fnx, fny, fnz);
            pos[0] += v[0] * ds; pos[1] += v[1] * ds; pos[2] += v[2] * ds;
            double hi = Math.min(uh, leadAlong + LEAD_LOOK + 0.5);
            double u = flat.toWorld(pos, leadAlong, hi, leadAlong + ds, lead);
            // never far from the trail
            double ox = pos[0] - flat.base[0], oy = pos[1] - flat.base[1], oz = pos[2] - flat.base[2];
            double ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (ol > DRIFT) {
                double k = DRIFT / ol;
                pos[0] = flat.base[0] + ox * k; pos[1] = flat.base[1] + oy * k; pos[2] = flat.base[2] + oz * k;
                u = flat.toWorld(pos, leadAlong, hi, u, lead);
                ol = DRIFT;
            }
            offTrail = ol;
            leadAlong = Math.max(leadAlong, u);
            path.record(lead[0], lead[1], lead[2], lead[3], lead[4], lead[5]);
        }
        // its heading, back on the rock it has got to
        flat.vecToWorld(v);
        setHeading(v[0], v[1], v[2]);
    }

    /**
     * Turns the lead's heading (v, laid out flat on the plane facing (nx, ny, nz), as its place pos) towards the trail
     * {@link #LEAD_LOOK} on from where it has got to.
     */
    private void steer(double uh, double ds, double nx, double ny, double nz) {
        flat.flatAt(Math.min(uh, leadAlong + LEAD_LOOK), at);
        double ex = at[0] - pos[0], ey = at[1] - pos[1], ez = at[2] - pos[2];
        double en = ex * nx + ey * ny + ez * nz;
        ex -= nx * en; ey -= ny * en; ez -= nz * en;
        double el = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (el < 1.0E-4) return;
        double hx = v[0], hy = v[1], hz = v[2];
        double cos = (hx * ex + hy * ey + hz * ez) / el;
        double sin = ((hy * ez - hz * ey) * nx + (hz * ex - hx * ez) * ny + (hx * ey - hy * ex) * nz) / el;
        double a = Math.atan2(sin, cos), k;
        if (Math.abs(a) > FOLDED) {
            // the way on is right behind it: round the way it last turned, else the side the neck swung out to
            int side = leadTurn != 0 ? leadTurn : headTurn != 0 ? -headTurn : a >= 0 ? 1 : -1;
            k = side * TURN;
        } else if (Math.abs(a) >= Math.PI / 2) {
            k = Math.signum(a) * TURN;
        } else {
            // the circle that runs on from the way it heads through the point it steers for
            k = Math.max(-TURN, Math.min(TURN, STEER * sin / Math.max(el, 0.25)));
        }
        if (Math.abs(k) > 0.3 * TURN) { leadTurn = k > 0 ? 1 : -1; leadTurnAge = 0; }
        double t = k * ds, c = Math.cos(t), s = Math.sin(t);
        // turned about the normal (the heading lies across it)
        double cx = ny * hz - nz * hy, cy = nz * hx - nx * hz, cz = nx * hy - ny * hx;
        v[0] = hx * c + cx * s; v[1] = hy * c + cy * s; v[2] = hz * c + cz * s;
        flatten(v, nx, ny, nz);
    }

    /** v laid flat on the plane facing (nx, ny, nz) and made unit length (left as it was if nothing is left). */
    private static void flatten(double[] v, double nx, double ny, double nz) {
        double d = v[0] * nx + v[1] * ny + v[2] * nz;
        double x = v[0] - nx * d, y = v[1] - ny * d, z = v[2] - nz * d, l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1.0E-9) return;
        v[0] = x / l; v[1] = y / l; v[2] = z / l;
    }

    /** Sets the lead's heading to (x, y, z) laid flat on its rock, made unit length (kept as it was if none is left). */
    private void setHeading(double x, double y, double z) {
        double d = x * lead[3] + y * lead[4] + z * lead[5];
        x -= lead[3] * d; y -= lead[4] * d; z -= lead[5] * d;
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1.0E-9) {
            if (heading[0] * heading[0] + heading[1] * heading[1] + heading[2] * heading[2] > 0.5) return;
            // any way along the rock
            x = Math.abs(lead[4]) < 0.9 ? 0.0 : 1.0; y = Math.abs(lead[4]) < 0.9 ? 1.0 : 0.0; z = 0.0;
            d = x * lead[3] + y * lead[4] + z * lead[5];
            x -= lead[3] * d; y -= lead[4] * d; z -= lead[5] * d;
            l = Math.sqrt(x * x + y * y + z * z);
        }
        heading[0] = x / l; heading[1] = y / l; heading[2] = z / l;
    }

    /**
     * Lays the body: the neck's curve from the head (straight back from the way it points along (fx, fy, fz)) to the
     * lead (coming in the way it heads), its ends pulled out so that it runs {@link #NECK_LAG} where it can, kept to
     * the rock the trail crosses; then the lead's path.
     */
    private void place(SkeinTrail trail, double fx, double fy, double fz) {
        double uh = trail.headAlong();
        trail.at(uh, at, 0);
        double px = at[0], py = at[1], pz = at[2], nx = at[3], ny = at[4], nz = at[5];
        // the way the head points, flat on its rock; none, the way the trail comes in
        double d = fx * nx + fy * ny + fz * nz;
        fx -= nx * d; fy -= ny * d; fz -= nz * d;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1.0E-4) {
            trail.at(uh - 0.3, tmp, 0);
            fx = px - tmp[0]; fy = py - tmp[1]; fz = pz - tmp[2];
            d = fx * nx + fy * ny + fz * nz;
            fx -= nx * d; fy -= ny * d; fz -= nz * d;
            fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (fl < 1.0E-4) { fx = heading[0]; fy = heading[1]; fz = heading[2]; fl = 1.0; }
        }
        fx /= fl; fy /= fl; fz /= fl;
        // the stretch of rock from the lead to the head laid out flat on the head's plane, the lead with it
        double from = Math.min(leadAlong - 0.5, uh - 0.3);
        flat.build(trail, from, uh, uh);
        flat.toFlat(lead[0], lead[1], lead[2], leadAlong, pos);
        double lx = pos[0], ly = pos[1], lz = pos[2];
        double d0 = (lx - px) * nx + (ly - py) * ny + (lz - pz) * nz;
        lx -= nx * d0; ly -= ny * d0; lz -= nz * d0;
        v[0] = heading[0]; v[1] = heading[1]; v[2] = heading[2];
        flat.vecToFlat(leadAlong, v);
        flatten(v, nx, ny, nz);
        double cx = lx - px, cy = ly - py, cz = lz - pz, chord = Math.sqrt(cx * cx + cy * cy + cz * cz);
        // the head facing back over its neck: the neck swings out the way the back of the head went round, and curls back
        double curl = 0.0, sx = 0.0, sy = 0.0, sz = 0.0;
        if (chord > 1.0E-6) {
            double back = -(fx * cx + fy * cy + fz * cz) / chord;
            curl = Math.max(0.0, Math.min(1.0, (-0.2 - back) / 0.7));
            curl = curl * curl * (3.0 - 2.0 * curl);
            int side = headTurn != 0 ? headTurn : leadTurn != 0 ? -leadTurn : 1;
            sx = (ny * fz - nz * fy) * side; sy = (nz * fx - nx * fz) * side; sz = (nx * fy - ny * fx) * side;
        }
        // pulled out as far as makes it its length (or as little as keeps its ends' ways, if it is longer even so)
        double least = 0.25 * Math.max(chord, 0.2), pull = least;
        double len = curve(px, py, pz, -fx, -fy, -fz, sx * curl, sy * curl, sz * curl, lx, ly, lz, least);
        excess = Math.max(0.0, len - NECK_LAG);
        if (len < NECK_LAG) {
            double lo = least, hi = 2.0 * NECK_LAG;
            if (curve(px, py, pz, -fx, -fy, -fz, sx * curl, sy * curl, sz * curl, lx, ly, lz, hi) <= NECK_LAG) pull = hi;
            else {
                for (int it = 0; it < 14; it++) {
                    double mid = 0.5 * (lo + hi);
                    if (curve(px, py, pz, -fx, -fy, -fz, sx * curl, sy * curl, sz * curl, lx, ly, lz, mid) < NECK_LAG) lo = mid;
                    else hi = mid;
                }
                pull = 0.5 * (lo + hi);
            }
        }
        curve(px, py, pz, -fx, -fy, -fz, sx * curl, sy * curl, sz * curl, lx, ly, lz, pull);
        // each point folded back onto the rock, from the head back to the lead; reckoned along the flat (so the neck
        // keeps its length, and the body behind its place, however the rock under the head turns)
        double u = uh, du = (uh - leadAlong) / NECK_PIECES;
        for (int i = 0; i <= NECK_PIECES; i++) {
            int o = STRIDE * i, r = 3 * i;
            if (i == 0) { set(neck, o, px, py, pz, nx, ny, nz); neckArc[0] = 0.0; continue; }
            neckArc[i] = neckArc[i - 1] + Math.sqrt(sq(raw[r] - raw[r - 3]) + sq(raw[r + 1] - raw[r - 2]) + sq(raw[r + 2] - raw[r - 1]));
            if (i == NECK_PIECES) set(neck, o, lead[0], lead[1], lead[2], lead[3], lead[4], lead[5]);
            else {
                double t = (double) i / NECK_PIECES;
                pos[0] = raw[r]; pos[1] = raw[r + 1]; pos[2] = raw[r + 2];
                u = flat.toWorld(pos, from, u, u - du, tmp);
                // the rock's normal, blended into the lead's towards it (the lead may be off the trail)
                double mx = tmp[3] * (1.0 - t) + lead[3] * t, my = tmp[4] * (1.0 - t) + lead[4] * t, mz = tmp[5] * (1.0 - t) + lead[5] * t;
                double ml = Math.sqrt(mx * mx + my * my + mz * mz);
                if (ml < 1.0E-6) { mx = tmp[3]; my = tmp[4]; mz = tmp[5]; ml = 1.0; }
                set(neck, o, tmp[0], tmp[1], tmp[2], mx / ml, my / ml, mz / ml);
            }
        }
        neckLength = neckArc[NECK_PIECES];
        // each joint: spread along the neck (however long it has to be just now, so the body behind never slides back
        // when the neck reaches round), the last at the lead; then along the lead's path behind it
        int last = Math.min(NECK_JOINTS, joints - 1);
        for (int j = 0; j <= last; j++) onNeck(neckLength * j / NECK_JOINTS, cur, STRIDE * j);
        if (last + 1 < joints) {
            path.sampleAll(pathArcs, pathOut);
            System.arraycopy(pathOut, 0, cur, STRIDE * (last + 1), STRIDE * (joints - last - 1));
        }
        square();
    }

    /**
     * The neck's curve from (px, py, pz), leaving it along (dx, dy, dz) turned (sx, sy, sz) aside, to the lead, coming
     * in the way it heads, each end pulled out {@code pull}: its points into {@link #raw}, and its length.
     */
    private double curve(double px, double py, double pz, double dx, double dy, double dz, double sx, double sy, double sz,
                         double lx, double ly, double lz, double pull) {
        double ax = px + (dx + sx) * pull, ay = py + (dy + sy) * pull, az = pz + (dz + sz) * pull;
        double bx = lx + heading[0] * pull, by = ly + heading[1] * pull, bz = lz + heading[2] * pull;
        double len = 0.0;
        for (int i = 0; i <= NECK_PIECES; i++) {
            double t = (double) i / NECK_PIECES, s = 1.0 - t;
            double w0 = s * s * s, w1 = 3.0 * s * s * t, w2 = 3.0 * s * t * t, w3 = t * t * t;
            int r = 3 * i;
            raw[r] = w0 * px + w1 * ax + w2 * bx + w3 * lx;
            raw[r + 1] = w0 * py + w1 * ay + w2 * by + w3 * ly;
            raw[r + 2] = w0 * pz + w1 * az + w2 * bz + w3 * lz;
            if (i > 0) len += Math.sqrt(sq(raw[r] - raw[r - 3]) + sq(raw[r + 1] - raw[r - 2]) + sq(raw[r + 2] - raw[r - 1]));
        }
        return len;
    }

    /** The point {@code s} along the neck's curve (position and normal) into o at off. */
    private void onNeck(double s, double[] o, int off) {
        int i = 0;
        while (i < NECK_PIECES - 1 && neckArc[i + 1] < s) i++;
        double len = neckArc[i + 1] - neckArc[i];
        double t = len < 1.0E-9 ? 0.0 : Math.max(0.0, Math.min(1.0, (s - neckArc[i]) / len));
        int a = STRIDE * i, b = STRIDE * (i + 1);
        for (int k = 0; k < 3; k++) o[off + k] = neck[a + k] + (neck[b + k] - neck[a + k]) * t;
        double x = neck[a + 3] + (neck[b + 3] - neck[a + 3]) * t, y = neck[a + 4] + (neck[b + 4] - neck[a + 4]) * t,
                z = neck[a + 5] + (neck[b + 5] - neck[a + 5]) * t;
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1.0E-6) { x = neck[b + 3]; y = neck[b + 4]; z = neck[b + 5]; l = 1.0; }
        o[off + 3] = x / l; o[off + 4] = y / l; o[off + 5] = z / l;
    }

    /**
     * Turns each joint's back square to the way the body runs there: the rock's normal (which each joint holds till
     * now) with any part along the body taken out; where the body runs (nearly) into it, as up a step over floor, the
     * way the joint before faced instead, carried round as the body turned between them.
     */
    private void square() {
        double qx = cur[3], qy = cur[4], qz = cur[5];
        double px = cur[STRIDE] - cur[0], py = cur[STRIDE + 1] - cur[1], pz = cur[STRIDE + 2] - cur[2];
        double pl = Math.sqrt(px * px + py * py + pz * pz);
        if (pl > 1.0E-9) { px /= pl; py /= pl; pz /= pl; }
        for (int j = 1; j < joints; j++) {
            int r = STRIDE * j, a = STRIDE * (j - 1), b = STRIDE * Math.min(joints - 1, j + 1);
            double tx = cur[b] - cur[a], ty = cur[b + 1] - cur[a + 1], tz = cur[b + 2] - cur[a + 2];
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            double nx = cur[r + 3], ny = cur[r + 4], nz = cur[r + 5];
            if (tl < 1.0E-9 || pl < 1.0E-9) {
                qx = nx; qy = ny; qz = nz;
                continue;
            }
            tx /= tl; ty /= tl; tz /= tl;
            // the rock's normal, square to the body
            double d = nx * tx + ny * ty + nz * tz;
            double sx = nx - tx * d, sy = ny - ty * d, sz = nz - tz * d;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            // the way the joint before faced, carried round as the body turned from there to here, square to it
            v[0] = qx; v[1] = qy; v[2] = qz;
            carry(v, px, py, pz, tx, ty, tz);
            double c = v[0] * tx + v[1] * ty + v[2] * tz;
            double cx = v[0] - tx * c, cy = v[1] - ty * c, cz = v[2] - tz * c;
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            double k = sl <= SQUARE_FROM ? 0.0 : sl >= SQUARE_TO ? 1.0 : (sl - SQUARE_FROM) / (SQUARE_TO - SQUARE_FROM);
            k = k * k * (3.0 - 2.0 * k);
            double mx = (sl > 1.0E-9 ? sx / sl : 0.0) * k + (cl > 1.0E-9 ? cx / cl : 0.0) * (1.0 - k);
            double my = (sl > 1.0E-9 ? sy / sl : 0.0) * k + (cl > 1.0E-9 ? cy / cl : 0.0) * (1.0 - k);
            double mz = (sl > 1.0E-9 ? sz / sl : 0.0) * k + (cl > 1.0E-9 ? cz / cl : 0.0) * (1.0 - k);
            double ml = Math.sqrt(mx * mx + my * my + mz * mz);
            if (ml < 1.0E-6) { mx = nx; my = ny; mz = nz; ml = 1.0; }
            qx = mx / ml; qy = my / ml; qz = mz / ml;
            cur[r + 3] = qx; cur[r + 4] = qy; cur[r + 5] = qz;
            px = tx; py = ty; pz = tz;
        }
    }

    /**
     * The point {@code s} blocks along the chain from the head, {@code pt} of the way from where it was a tick ago (0)
     * to where it is (1): its position (0..2) and the way its back faces (3..5, unit length) at o[off..off+5]. Between
     * joints it lies on the straight between them; past the last, on along the last link.
     */
    public void sample(double s, double pt, double[] o, int off) {
        double u = Math.max(0.0, s) / LINK;
        int i = Math.min((int) Math.floor(u), joints - 2);
        double f = u - i, fn = Math.min(1.0, f);
        int a = STRIDE * i, b = STRIDE * (i + 1);
        for (int k = 0; k < 3; k++) {
            double pa = prev[a + k] + (cur[a + k] - prev[a + k]) * pt, pb = prev[b + k] + (cur[b + k] - prev[b + k]) * pt;
            o[off + k] = pa + (pb - pa) * f;
        }
        double na0 = prev[a + 3] + (cur[a + 3] - prev[a + 3]) * pt, nb0 = prev[b + 3] + (cur[b + 3] - prev[b + 3]) * pt;
        double na1 = prev[a + 4] + (cur[a + 4] - prev[a + 4]) * pt, nb1 = prev[b + 4] + (cur[b + 4] - prev[b + 4]) * pt;
        double na2 = prev[a + 5] + (cur[a + 5] - prev[a + 5]) * pt, nb2 = prev[b + 5] + (cur[b + 5] - prev[b + 5]) * pt;
        double x = na0 + (nb0 - na0) * fn, y = na1 + (nb1 - na1) * fn, z = na2 + (nb2 - na2) * fn;
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1.0E-6) { x = cur[b + 3]; y = cur[b + 4]; z = cur[b + 5]; l = Math.max(1.0E-6, Math.sqrt(x * x + y * y + z * z)); }
        o[off + 3] = x / l; o[off + 4] = y / l; o[off + 5] = z / l;
    }

    /** The points at each of {@code arcs} (blocks along the chain), six numbers each in o, as {@link #sample}. */
    public void sampleAll(double[] arcs, double pt, double[] o) {
        for (int k = 0; k < arcs.length; k++) sample(arcs[k], pt, o, STRIDE * k);
    }

    /**
     * The body as it lies now at {@code arcs} along it (the first 0, the head), as SkeinTrail#write gives a trail: six
     * floats each, the position less (ox, oy, oz) and the way its back faces. Read back as a trail, a body laid along
     * it lies (nearly) as this one does.
     */
    public float[] write(double[] arcs, double ox, double oy, double oz) {
        double[] s = new double[STRIDE * arcs.length];
        sampleAll(arcs, 1.0, s);
        float[] out = new float[s.length];
        for (int o = 0; o < s.length; o += STRIDE) {
            out[o] = (float) (s[o] - ox);
            out[o + 1] = (float) (s[o + 1] - oy);
            out[o + 2] = (float) (s[o + 2] - oz);
            out[o + 3] = (float) s[o + 3];
            out[o + 4] = (float) s[o + 4];
            out[o + 5] = (float) s[o + 5];
        }
        return out;
    }

    /**
     * For the dev trace: the most any joint moved this tick (and which), the most any joint behind the neck moved and
     * went back along the body (towards its tail), the sharpest bend behind the neck along the rock (degrees a link), the
     * neck's length, how far behind the head along the trail the lead is and how far off the trail, and the shortest
     * and longest links.
     */
    public String moves() {
        double most = 0.0, body = 0.0, back = 0.0, bend = 0.0, shortest = Double.MAX_VALUE, longest = 0.0;
        int which = 0;
        for (int j = 0; j < joints; j++) {
            double m = dist(cur, j, prev, j);
            if (m > most) { most = m; which = j; }
            if (j > 0) {
                double l = dist(cur, j - 1, cur, j);
                shortest = Math.min(shortest, l);
                longest = Math.max(longest, l);
            }
            if (j > NECK_JOINTS) {
                body = Math.max(body, m);
                // against the way on to the joint before it, as it lay
                int a = STRIDE * j, b = STRIDE * (j - 1);
                double tx = prev[b] - prev[a], ty = prev[b + 1] - prev[a + 1], tz = prev[b + 2] - prev[a + 2];
                double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tl > 1.0E-9) back = Math.max(back, -((cur[a] - prev[a]) * tx + (cur[a + 1] - prev[a + 1]) * ty + (cur[a + 2] - prev[a + 2]) * tz) / tl);
                if (j < joints - 1) bend = Math.max(bend, bendAt(cur, j));
            }
        }
        double lag = on == null ? 0.0 : on.headAlong() - leadAlong;
        return String.format(Locale.ROOT, "most moved %.3f (joint %d), body %.3f, back %.3f, bend %.1f deg, neck %.2f, lead %.2f behind and %.2f off the trail, links %.3f to %.3f",
                most, which, body, back, Math.toDegrees(bend), neckLength, lag, offTrail, shortest, longest);
    }

    /** The turn at joint j of chain c along its rock: between the link into it and the link out of it, about its normal (radians). */
    static double bendAt(double[] c, int j) {
        int a = STRIDE * (j - 1), b = STRIDE * j, d = STRIDE * (j + 1);
        double ux = c[b] - c[a], uy = c[b + 1] - c[a + 1], uz = c[b + 2] - c[a + 2];
        double wx = c[d] - c[b], wy = c[d + 1] - c[b + 1], wz = c[d + 2] - c[b + 2];
        double nx = c[b + 3], ny = c[b + 4], nz = c[b + 5];
        double ud = ux * nx + uy * ny + uz * nz, wd = wx * nx + wy * ny + wz * nz;
        ux -= nx * ud; uy -= ny * ud; uz -= nz * ud;
        wx -= nx * wd; wy -= ny * wd; wz -= nz * wd;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz), wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (ul < 1.0E-6 || wl < 1.0E-6) return 0.0;
        double cx = uy * wz - uz * wy, cy = uz * wx - ux * wz, cz = ux * wy - uy * wx;
        return Math.abs(Math.atan2(cx * nx + cy * ny + cz * nz, ux * wx + uy * wy + uz * wz));
    }

    private static void set(double[] a, int o, double x, double y, double z, double nx, double ny, double nz) {
        a[o] = x; a[o + 1] = y; a[o + 2] = z; a[o + 3] = nx; a[o + 4] = ny; a[o + 5] = nz;
    }

    private static double dist(double[] p, int i, double[] q, int j) {
        int a = STRIDE * i, b = STRIDE * j;
        return Math.sqrt(sq(p[a] - q[b]) + sq(p[a + 1] - q[b + 1]) + sq(p[a + 2] - q[b + 2]));
    }

    private static double sq(double v) { return v * v; }

    /** v carried round from rock facing (ax, ay, az) to rock facing (bx, by, bz): turned as the one normal turns into the other. */
    static void carry(double[] v, double ax, double ay, double az, double bx, double by, double bz) {
        double c = ax * bx + ay * by + az * bz;
        if (c > 0.9999) return;
        double kx = ay * bz - az * by, ky = az * bx - ax * bz, kz = ax * by - ay * bx;
        double s = Math.sqrt(kx * kx + ky * ky + kz * kz);
        if (s < 1.0E-6) return;          // opposite: nothing sensible to carry it round by
        kx /= s; ky /= s; kz /= s;
        double x = v[0], y = v[1], z = v[2];
        double kd = kx * x + ky * y + kz * z;
        double crx = ky * z - kz * y, cry = kz * x - kx * z, crz = kx * y - ky * x;
        v[0] = x * c + crx * s + kx * kd * (1 - c);
        v[1] = y * c + cry * s + ky * kd * (1 - c);
        v[2] = z * c + crz * s + kz * kd * (1 - c);
    }

    /**
     * A stretch of the trail and the rock it crosses laid out flat on the plane of one place on it, as a strip of paper
     * stuck along the rock would lie peeled off and laid flat: each sample's rock turned onto the plane, edge after
     * edge, and the trail laid along it. A point near the stretch is taken from the flat to the rock (and back) by the
     * turn of the rock under the trail nearest it, so a curve drawn on the flat lies on the rock, round edges as the
     * trail goes.
     */
    private static final class Flat {
        static final double STEP = 0.08;
        static final int MAX = 64;
        int size, ref;
        double from, du;
        /** Each sample: how far along the trail, its place and its rock's normal, its place on the flat, and the turn from its rock onto the flat. */
        final double[] u = new double[MAX], p = new double[3 * MAX], n = new double[3 * MAX], q = new double[3 * MAX], r = new double[9 * MAX];
        /** The flat's plane: its normal. */
        double nx, ny, nz;
        /** The place on the flat's line nearest the last point taken to the rock, and the stretch it lies on. */
        final double[] base = new double[3];
        private int seg;
        private double segT;
        private final double[] s = new double[6], m = new double[9];

        /** Lays out the trail from {@code from} to {@code to} along it, flat on its plane at {@code ref}. */
        void build(SkeinTrail trail, double from, double to, double ref) {
            int count = Math.max(2, Math.min(MAX, (int) Math.ceil((to - from) / STEP) + 1));
            this.from = from;
            this.du = (to - from) / (count - 1);
            size = count;
            for (int k = 0; k < count; k++) {
                u[k] = from + du * k;
                trail.at(u[k], s, 0);
                p[3 * k] = s[0]; p[3 * k + 1] = s[1]; p[3 * k + 2] = s[2];
                n[3 * k] = s[3]; n[3 * k + 1] = s[4]; n[3 * k + 2] = s[5];
            }
            int ri = Math.max(0, Math.min(count - 1, (int) Math.round((ref - from) / du)));
            this.ref = ri;
            for (int i = 0; i < 9; i++) r[9 * ri + i] = i % 4 == 0 ? 1.0 : 0.0;
            q[3 * ri] = p[3 * ri]; q[3 * ri + 1] = p[3 * ri + 1]; q[3 * ri + 2] = p[3 * ri + 2];
            nx = n[3 * ri]; ny = n[3 * ri + 1]; nz = n[3 * ri + 2];
            for (int k = ri + 1; k < count; k++) lay(k, k - 1, 1.0);
            for (int k = ri - 1; k >= 0; k--) lay(k, k + 1, -1.0);
        }

        /** Sample k laid out from its neighbour j (laid out already), the stretch between running the way {@code way}. */
        private void lay(int k, int j, double way) {
            rotation(n[3 * k], n[3 * k + 1], n[3 * k + 2], n[3 * j], n[3 * j + 1], n[3 * j + 2], m);
            int a = 9 * j, b = 9 * k;
            for (int row = 0; row < 3; row++)
                for (int col = 0; col < 3; col++)
                    r[b + 3 * row + col] = r[a + 3 * row] * m[col] + r[a + 3 * row + 1] * m[3 + col] + r[a + 3 * row + 2] * m[6 + col];
            double dx = (p[3 * k] - p[3 * j]) * way, dy = (p[3 * k + 1] - p[3 * j + 1]) * way, dz = (p[3 * k + 2] - p[3 * j + 2]) * way;
            double ex = r[b] * dx + r[b + 1] * dy + r[b + 2] * dz;
            double ey = r[b + 3] * dx + r[b + 4] * dy + r[b + 5] * dz;
            double ez = r[b + 6] * dx + r[b + 7] * dy + r[b + 8] * dz;
            // laid on the plane, its length kept (where the rock's normal does not quite square with the trail, as
            // round an edge the head eased over, the stretch is turned flat rather than shortened)
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz), en = ex * nx + ey * ny + ez * nz;
            ex -= nx * en; ey -= ny * en; ez -= nz * en;
            double el = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (el > 1.0E-9) { ex *= len / el; ey *= len / el; ez *= len / el; }
            q[3 * k] = q[3 * j] + ex * way; q[3 * k + 1] = q[3 * j + 1] + ey * way; q[3 * k + 2] = q[3 * j + 2] + ez * way;
        }

        /** The sample at or before {@code uu} (and the part of the way on to the next, in segT). */
        private int at(double uu) {
            double f = (uu - from) / du;
            int k = Math.max(0, Math.min(size - 2, (int) Math.floor(f)));
            segT = Math.max(0.0, Math.min(1.0, f - k));
            return k;
        }

        /** The trail's place {@code uu} along it, on the flat, into o. */
        void flatAt(double uu, double[] o) {
            int k = at(uu);
            double t = segT;
            for (int i = 0; i < 3; i++) o[i] = q[3 * k + i] + (q[3 * k + 3 + i] - q[3 * k + i]) * t;
        }

        /** The point (x, y, z) on the rock, beside the trail's place {@code uu}, laid out on the flat into o. */
        void toFlat(double x, double y, double z, double uu, double[] o) {
            int k = at(uu);
            double t = segT;
            int b = 9 * (t < 0.5 ? k : k + 1);
            double dx = x - (p[3 * k] + (p[3 * k + 3] - p[3 * k]) * t);
            double dy = y - (p[3 * k + 1] + (p[3 * k + 4] - p[3 * k + 1]) * t);
            double dz = z - (p[3 * k + 2] + (p[3 * k + 5] - p[3 * k + 2]) * t);
            o[0] = q[3 * k] + (q[3 * k + 3] - q[3 * k]) * t + r[b] * dx + r[b + 1] * dy + r[b + 2] * dz;
            o[1] = q[3 * k + 1] + (q[3 * k + 4] - q[3 * k + 1]) * t + r[b + 3] * dx + r[b + 4] * dy + r[b + 5] * dz;
            o[2] = q[3 * k + 2] + (q[3 * k + 5] - q[3 * k + 2]) * t + r[b + 6] * dx + r[b + 7] * dy + r[b + 8] * dz;
        }

        /** v, a way along the rock at the trail's place {@code uu}, turned onto the flat. */
        void vecToFlat(double uu, double[] v) {
            int k = at(uu);
            int b = 9 * (segT < 0.5 ? k : k + 1);
            double x = v[0], y = v[1], z = v[2];
            v[0] = r[b] * x + r[b + 1] * y + r[b + 2] * z;
            v[1] = r[b + 3] * x + r[b + 4] * y + r[b + 5] * z;
            v[2] = r[b + 6] * x + r[b + 7] * y + r[b + 8] * z;
        }

        /**
         * The point f on the flat taken to the rock: beside the place on the trail's line nearest it (looked for only
         * from {@code lo} to {@code hi} along it; of two places about as near, the one nearer {@code pref}), turned
         * as that rock turns. Its place and the rock's normal there into o; returns how far along the trail that is.
         */
        double toWorld(double[] f, double lo, double hi, double pref, double[] o) {
            double bestCost = Double.MAX_VALUE;
            int bk = 0;
            double bt = 0.0;
            for (int k = 0; k < size - 1; k++) {
                double u0 = u[k], u1 = u[k + 1];
                if (u1 < lo || u0 > hi) continue;
                double tLo = Math.max(0.0, (lo - u0) / du), tHi = Math.min(1.0, (hi - u0) / du);
                if (tHi < tLo) continue;
                double sx = q[3 * k + 3] - q[3 * k], sy = q[3 * k + 4] - q[3 * k + 1], sz = q[3 * k + 5] - q[3 * k + 2];
                double ss = sx * sx + sy * sy + sz * sz;
                double t = ss < 1.0E-12 ? tLo : ((f[0] - q[3 * k]) * sx + (f[1] - q[3 * k + 1]) * sy + (f[2] - q[3 * k + 2]) * sz) / ss;
                t = Math.max(tLo, Math.min(tHi, t));
                double px = q[3 * k] + sx * t - f[0], py = q[3 * k + 1] + sy * t - f[1], pz = q[3 * k + 2] + sz * t - f[2];
                double uu = u0 + du * t;
                double cost = px * px + py * py + pz * pz + SkeinTrail.PREFER * (uu - pref) * (uu - pref);
                if (cost < bestCost) { bestCost = cost; bk = k; bt = t; }
            }
            seg = bk;
            segT = bt;
            int k = bk;
            for (int i = 0; i < 3; i++) base[i] = q[3 * k + i] + (q[3 * k + 3 + i] - q[3 * k + i]) * bt;
            double dx = f[0] - base[0], dy = f[1] - base[1], dz = f[2] - base[2];
            int b = 9 * (bt < 0.5 ? k : k + 1);
            // back from the flat onto that rock: the turn undone (its transpose)
            double wx = r[b] * dx + r[b + 3] * dy + r[b + 6] * dz;
            double wy = r[b + 1] * dx + r[b + 4] * dy + r[b + 7] * dz;
            double wz = r[b + 2] * dx + r[b + 5] * dy + r[b + 8] * dz;
            o[0] = p[3 * k] + (p[3 * k + 3] - p[3 * k]) * bt + wx;
            o[1] = p[3 * k + 1] + (p[3 * k + 4] - p[3 * k + 1]) * bt + wy;
            o[2] = p[3 * k + 2] + (p[3 * k + 5] - p[3 * k + 2]) * bt + wz;
            double mx = n[3 * k] + (n[3 * k + 3] - n[3 * k]) * bt, my = n[3 * k + 1] + (n[3 * k + 4] - n[3 * k + 1]) * bt,
                    mz = n[3 * k + 2] + (n[3 * k + 5] - n[3 * k + 2]) * bt;
            double ml = Math.sqrt(mx * mx + my * my + mz * mz);
            if (ml < 1.0E-6) { mx = n[3 * k + 3]; my = n[3 * k + 4]; mz = n[3 * k + 5]; ml = 1.0; }
            o[3] = mx / ml; o[4] = my / ml; o[5] = mz / ml;
            return u[k] + du * bt;
        }

        /** v, a way on the flat, turned onto the rock of the point last taken to the rock. */
        void vecToWorld(double[] v) {
            int b = 9 * (segT < 0.5 ? seg : seg + 1);
            double x = v[0], y = v[1], z = v[2];
            v[0] = r[b] * x + r[b + 3] * y + r[b + 6] * z;
            v[1] = r[b + 1] * x + r[b + 4] * y + r[b + 7] * z;
            v[2] = r[b + 2] * x + r[b + 5] * y + r[b + 8] * z;
        }

        /** The turn (a matrix, by rows, into m) that takes the normal a onto the normal b the shortest way. */
        static void rotation(double ax, double ay, double az, double bx, double by, double bz, double[] m) {
            double c = ax * bx + ay * by + az * bz;
            double kx = ay * bz - az * by, ky = az * bx - ax * bz, kz = ax * by - ay * bx;
            double s = Math.sqrt(kx * kx + ky * ky + kz * kz);
            if (s < 1.0E-9) {
                for (int i = 0; i < 9; i++) m[i] = i % 4 == 0 ? 1.0 : 0.0;
                return;
            }
            kx /= s; ky /= s; kz /= s;
            double t = 1.0 - c;
            m[0] = c + t * kx * kx;      m[1] = t * kx * ky - s * kz; m[2] = t * kx * kz + s * ky;
            m[3] = t * ky * kx + s * kz; m[4] = c + t * ky * ky;      m[5] = t * ky * kz - s * kx;
            m[6] = t * kz * kx - s * ky; m[7] = t * kz * ky + s * kx; m[8] = c + t * kz * kz;
        }
    }
}
